package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * High-resolution hero backdrops from free, keyless sources: TVmaze for shows (CC BY-SA, tvmaze.com)
 * and the Steam store CDN for games. Every call blocks, so call it off the main thread. Images are kept
 * as downloaded in cacheDir/artwork; a small index in filesDir remembers hits for 60 days and misses for 7.
 * Returns null, without any request, when {@code online_artwork} is off.
 */
final class Artwork {
    private static final String USER_AGENT = "CleanYourTV-launcher/1.0 (Android TV home screen; +https://github.com/SferaDev/android-tv)";
    private static final String TVMAZE = "https://api.tvmaze.com/";
    private static final String STEAM_SEARCH = "https://store.steampowered.com/api/storesearch/?l=english&cc=US&term=";
    private static final String STEAM_DETAILS = "https://store.steampowered.com/api/appdetails?appids=";
    private static final String STEAM_CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/";
    private static final String INDEX_NAME = "artwork_index.json";
    private static final long BUDGET_BYTES = 40L * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    /** TVmaze allows 20 calls per 10 s per IP; one every 600 ms stays under it. */
    private static final long TVMAZE_GAP_MS = 600;
    private static final long BACKOFF_MS = 3000;

    private static final Object LOCK = new Object();
    private static long lastTvmazeCall;

    private Artwork() {}

    static File backdropForShow(Context ctx, String title) {
        if (!enabled(ctx) || ArtworkPick.normalise(title).isEmpty()) return null;
        String key = "show:" + ArtworkPick.normalise(title);
        return fetch(ctx, key, ".jpg", () -> {
            String found = tvmaze("singlesearch/shows?q=" + encode(title));
            if (found == null) return Lookup.MISS;
            String id = idOf(found);
            if (id == null) return Lookup.MISS;
            String images = tvmaze("shows/" + id + "/images");
            if (images == null) return Lookup.MISS;
            String url = ArtworkPick.backdropUrl(images);
            return url == null ? Lookup.MISS : Lookup.of(url);
        });
    }

    static File backdropForGame(Context ctx, String name) {
        if (!enabled(ctx) || !ArtworkPick.isGame(name)) return null;
        String key = "game:" + ArtworkPick.normalise(name);
        return fetch(ctx, key, ".jpg", () -> {
            long id = steamId(name);
            if (id == 0) return Lookup.MISS;
            return Lookup.of(new String[] {STEAM_CDN + id + "/library_hero.jpg"}, () -> {
                // Newer apps keep images under hashed paths that only appdetails knows.
                String details = text(STEAM_DETAILS + id);
                List<String> urls = details == null ? new ArrayList<>() : ArtworkPick.appDetailsUrls(details, id);
                urls.add(STEAM_CDN + id + "/header.jpg");
                return urls.toArray(new String[0]);
            });
        });
    }

    private static boolean enabled(Context ctx) {
        return ctx != null && Config.load(ctx).onlineArtwork;
    }

    /** Candidate image URLs in preference order, or a definite miss; null means the lookup failed and nothing is remembered. */
    private static final class Lookup {
        static final Lookup MISS = new Lookup(new String[0], null);
        final String[] urls;
        /** More candidates, asked for only when none of urls exist. */
        final Fallback fallback;

        private Lookup(String[] urls, Fallback fallback) {
            this.urls = urls;
            this.fallback = fallback;
        }

        static Lookup of(String... urls) {
            return new Lookup(urls, null);
        }

        static Lookup of(String[] urls, Fallback fallback) {
            return new Lookup(urls, fallback);
        }
    }

    private interface Fallback {
        String[] more() throws IOException;
    }

    private interface Resolver {
        Lookup resolve() throws IOException;
    }

    /** Also holds {@link ImageCache}'s downloads; both trim the folder oldest-used first to the same budget. */
    static File cacheDir(Context ctx) {
        return new File(ctx.getCacheDir(), "artwork");
    }

    private static File fetch(Context ctx, String key, String ext, Resolver resolver) {
        File dir = cacheDir(ctx);
        File indexFile = new File(ctx.getFilesDir(), INDEX_NAME);
        synchronized (LOCK) {
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                long now = System.currentTimeMillis();
                ArtworkIndex index = ArtworkIndex.fromJson(read(indexFile));
                ArtworkIndex.Entry cached = lookupFresh(index, key, dir, now);
                if (cached != null) {
                    if (cached.miss) return null;
                    File hit = new File(dir, cached.file);
                    hit.setLastModified(now);
                    return hit;
                }

                Lookup lookup = resolver.resolve();
                if (lookup == null) return null;
                String name = sha1(key) + ext;
                File out = new File(dir, name);
                String used = null;
                String[] urls = lookup.urls;
                for (int pass = 0; pass < 2 && used == null; pass++) {
                    for (String url : urls) {
                        if (download(url, out)) {
                            used = url;
                            break;
                        }
                    }
                    if (used != null || lookup.fallback == null || pass == 1) break;
                    urls = lookup.fallback.more();
                }
                index.put(key, used == null ? new ArtworkIndex.Entry("", null, now, true) : new ArtworkIndex.Entry(used, name, now, false));
                trim(index, dir, used == null ? null : name);
                StoredText.write(indexFile, index.toJson());
                return used == null ? null : out;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    private static ArtworkIndex.Entry lookupFresh(ArtworkIndex index, String key, File dir, long now) {
        List<String> stale = index.prune(now);
        for (String name : stale) new File(dir, name).delete();
        ArtworkIndex.Entry e = index.get(key);
        if (e == null) return null;
        if (!e.miss && (e.file == null || !new File(dir, e.file).isFile())) return null;
        return e;
    }

    private static void trim(ArtworkIndex index, File dir, String keep) {
        trim(dir, keep);
        String[] left = dir.list();
        if (left != null) index.forgetMissing(new HashSet<>(Arrays.asList(left)));
    }

    /** Deletes the oldest-used files over the budget, never {@code keep}. */
    static void trim(File dir, String keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        List<ArtworkIndex.CacheFile> infos = new ArrayList<>();
        for (File f : files) infos.add(new ArtworkIndex.CacheFile(f.getName(), f.length(), f.lastModified()));
        for (String name : ArtworkIndex.evict(infos, BUDGET_BYTES, keep)) new File(dir, name).delete();
    }

    private static long steamId(String name) throws IOException {
        String json = text(STEAM_SEARCH + encode(name));
        return json == null ? 0 : ArtworkPick.steamAppId(json, name);
    }

    private static String idOf(String showJson) {
        try {
            long id = new org.json.JSONObject(showJson).optLong("id", 0);
            return id > 0 ? String.valueOf(id) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** One TVmaze GET, spaced under the rate limit; a 429 waits once and retries. A 404 gives null. */
    private static String tvmaze(String path) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long wait = lastTvmazeCall + TVMAZE_GAP_MS - System.currentTimeMillis();
            if (wait > 0) sleep(wait);
            lastTvmazeCall = System.currentTimeMillis();
            HttpURLConnection c = open(TVMAZE + path);
            try {
                int code = c.getResponseCode();
                if (code == 429) {
                    sleep(BACKOFF_MS);
                    continue;
                }
                if (code == 404) return null;
                if (code != 200) throw new IOException("HTTP " + code);
                return new String(StoredText.readAll(c.getInputStream(), MAX_JSON_BYTES), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("rate limited");
    }

    private static String text(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            return new String(StoredText.readAll(c.getInputStream(), MAX_JSON_BYTES), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** Saves an image to out through a temp file; false on 404 or anything that is not an image. */
    private static boolean download(String url, File out) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code == 404 || code == 403) return false;
            if (code != 200) throw new IOException("HTTP " + code);
            String type = c.getContentType();
            if (type == null || !type.startsWith("image/")) return false;
            byte[] data = StoredText.readAll(c.getInputStream(), MAX_IMAGE_BYTES);
            File tmp = new File(out.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(data);
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                throw new IOException("rename failed");
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private static String read(File f) {
        try {
            return StoredText.read(f, MAX_JSON_BYTES);
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    private static String encode(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
