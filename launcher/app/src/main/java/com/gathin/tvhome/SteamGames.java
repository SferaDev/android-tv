package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The games of the Sunshine host, read as a GameStream client and launched through Moonlight's shortcut activity. */
final class SteamGames {
    private static final String TAG = "TvHomeGames";
    static final String BIG_PICTURE = "Steam Big Picture";
    private static final String DESKTOP = "Desktop";
    static final String MOONLIGHT_PACKAGE = "com.limelight";
    static final long REFRESH_MS = 15L * 60_000;
    private static final int COVER_WIDTH = 300;
    /** Covers that failed are tried again on a later refresh, at most this often. */
    static final long COVER_RETRY_MS = 2 * 60_000;
    private static final int COVER_THREADS = 2;
    /** An app the host has no art for is fetched again after this, in case art was added since. */
    static final long NO_ART_MS = 24L * 3600_000;
    private static final int PLACEHOLDER_SHARERS = 3;
    private static final int LAUNCHES_MAX_BYTES = 64 * 1024;
    private static final String MARKS_VERSION = "2";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean COVERS_RUNNING = new AtomicBoolean();
    private static volatile long lastAttempt, lastCoverAttempt;
    private static volatile boolean syncNow;
    private static volatile Runnable coverListener;
    private static volatile boolean placeholdersChecked;
    private static volatile String hostUuid = "", hostName = "";

    private SteamGames() { }

    /** What the last sync learned: the host's Moonlight identity and its apps in the host's order. */
    static final class Cache {
        final String uuid, name;
        final List<GameStreamClient.App> apps;

        Cache(String uuid, String name, List<GameStreamClient.App> apps) {
            this.uuid = uuid;
            this.name = name;
            this.apps = apps;
        }
    }

    static String toJson(Cache cache) throws JSONException {
        JSONArray apps = new JSONArray();
        for (GameStreamClient.App app : cache.apps) {
            apps.put(new JSONObject().put("id", app.id).put("title", app.title).put("hdr", app.hdr));
        }
        return new JSONObject().put("uuid", cache.uuid).put("name", cache.name).put("apps", apps).toString();
    }

    static Cache parseCache(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        List<GameStreamClient.App> apps = new ArrayList<>();
        JSONArray array = root.getJSONArray("apps");
        for (int i = 0; i < array.length(); i++) {
            JSONObject app = array.getJSONObject(i);
            apps.add(new GameStreamClient.App(app.getInt("id"), app.getString("title"), app.optBoolean("hdr")));
        }
        return new Cache(root.getString("uuid"), root.optString("name", ""), apps);
    }

    /** When a game was last launched from here, by host app id and by title (the fallback when the id changes). */
    static final class Launches {
        final Map<Integer, Long> byId = new HashMap<>();
        final Map<String, Long> byName = new HashMap<>();

        long of(GameStreamClient.App app) {
            Long id = byId.get(app.id), name = byName.get(app.title);
            return Math.max(id == null ? 0 : id, name == null ? 0 : name);
        }
    }

    /**
     * Every game: those launched from here, latest first, then the rest A to Z ignoring case (the Home strip takes the
     * first few, the Games tab sorts them again). Desktop and Big Picture come last, in that order (Big Picture is
     * added when the host has no such app, and launches by name).
     */
    static List<SteamGame> order(List<GameStreamClient.App> apps, Launches launches) {
        List<GameStreamClient.App> regular = new ArrayList<>();
        SteamGame desktop = null, bigPicture = null;
        for (GameStreamClient.App app : apps) {
            if (DESKTOP.equalsIgnoreCase(app.title)) {
                if (desktop == null) desktop = new SteamGame(app.id, app.title);
            } else if (BIG_PICTURE.equalsIgnoreCase(app.title)) {
                if (bigPicture == null) bigPicture = new SteamGame(app.id, app.title);
            } else {
                regular.add(app);
            }
        }
        regular.sort((a, b) -> {
            int recent = Long.compare(launches.of(b), launches.of(a));
            return recent != 0 ? recent : a.title.compareToIgnoreCase(b.title);
        });
        List<SteamGame> games = new ArrayList<>();
        for (GameStreamClient.App app : regular) {
            games.add(new SteamGame(app.id, app.title));
        }
        if (desktop != null) games.add(desktop);
        games.add(bigPicture != null ? bigPicture : new SteamGame(0, BIG_PICTURE));
        return games;
    }

    static String launchesToJson(Launches launches) throws JSONException {
        JSONObject ids = new JSONObject(), names = new JSONObject();
        for (Map.Entry<Integer, Long> e : launches.byId.entrySet()) ids.put(String.valueOf(e.getKey()), e.getValue());
        for (Map.Entry<String, Long> e : launches.byName.entrySet()) names.put(e.getKey(), e.getValue());
        return new JSONObject().put("ids", ids).put("names", names).toString();
    }

    static Launches parseLaunches(String json) throws JSONException {
        Launches launches = new Launches();
        JSONObject root = new JSONObject(json);
        JSONObject ids = root.optJSONObject("ids"), names = root.optJSONObject("names");
        for (Iterator<String> it = ids == null ? Collections.<String>emptyIterator() : ids.keys(); it.hasNext(); ) {
            String key = it.next();
            try {
                launches.byId.put(Integer.parseInt(key), ids.getLong(key));
            } catch (NumberFormatException ignored) {
                // A hand-edited key that is not an id.
            }
        }
        for (Iterator<String> it = names == null ? Collections.<String>emptyIterator() : names.keys(); it.hasNext(); ) {
            String key = it.next();
            launches.byName.put(key, names.getLong(key));
        }
        return launches;
    }

    /** Drops launches of apps the host no longer has. */
    static Launches prune(Launches launches, List<GameStreamClient.App> apps) {
        Launches kept = new Launches();
        for (GameStreamClient.App app : apps) {
            Long id = launches.byId.get(app.id), name = launches.byName.get(app.title);
            if (id != null) kept.byId.put(app.id, id);
            if (name != null) kept.byName.put(app.title, name);
        }
        return kept;
    }

    /** The UI calls this when it starts a game with {@link #launchIntent(Config, SteamGame)}; it sets the row order. */
    static synchronized void recordLaunch(Context ctx, SteamGame game) {
        Launches launches = readLaunches(ctx);
        long now = System.currentTimeMillis();
        if (game.appId != 0) launches.byId.put(game.appId, now);
        launches.byName.put(game.name, now);
        writeLaunches(ctx, launches);
    }

    private static synchronized Launches readLaunches(Context ctx) {
        try {
            String text = StoredText.read(launchesFile(ctx), LAUNCHES_MAX_BYTES);
            return text == null ? new Launches() : parseLaunches(text);
        } catch (IOException | JSONException e) {
            return new Launches();
        }
    }

    private static synchronized void writeLaunches(Context ctx, Launches launches) {
        try {
            StoredText.write(launchesFile(ctx), launchesToJson(launches));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not save launches: " + e);
        }
    }

    private static File launchesFile(Context ctx) {
        return new File(ctx.getFilesDir(), "gamestream_launches.json");
    }

    /**
     * The host's games from the cache in launch order, Big Picture last, hidden ones included (the Games tab lists
     * them all); empty until the first sync, or when no host is configured. No network.
     */
    static List<SteamGame> items(Context ctx, Config cfg) {
        Cache cache = readCache(ctx, cfg);
        return cache == null ? new ArrayList<>() : order(cache.apps, readLaunches(ctx));
    }

    static boolean configured(Config cfg) {
        return !cfg.gamestreamHost.isEmpty();
    }

    /** The cached cover, or null when it is not cached (yet) or the host has no art for the app. */
    static File cover(Context ctx, int appId) {
        File file = new File(coverDir(ctx), appId + ".png");
        return file.isFile() && file.length() > 0 ? file : null;
    }

    /** Starts the game's stream in Moonlight; AppName must match the host's app title exactly. */
    static Intent launchIntent(Config cfg, String appName) {
        return new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(MOONLIGHT_PACKAGE, "com.limelight.ShortcutTrampoline"))
                .putExtra("UUID", cfg.moonlightHostUuid.isEmpty() ? hostUuid : cfg.moonlightHostUuid)
                .putExtra("Name", cfg.moonlightHostName.isEmpty() ? hostName : cfg.moonlightHostName)
                .putExtra("AppName", appName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Like {@link #launchIntent(Config, String)}, also naming the host app id, which Moonlight prefers over the name. */
    static Intent launchIntent(Config cfg, SteamGame game) {
        Intent intent = launchIntent(cfg, game.name);
        return game.appId == 0 ? intent : intent.putExtra("AppId", String.valueOf(game.appId));
    }

    /** Runs on a background thread each time a cover lands; null to stop listening. */
    static void setCoverListener(Runnable listener) {
        coverListener = listener;
    }

    /**
     * Blocking; call off the main thread. Syncs with the host at most every 15 minutes, quietly; in between, fetches
     * covers that are still missing.
     */
    static void refresh(Context ctx, Config cfg) {
        if (!configured(cfg)) return;
        Cache cached = readCache(ctx, cfg);
        long now = System.currentTimeMillis();
        File cache = cacheFile(ctx);
        boolean fresh = !syncNow && cache.isFile() && now - cache.lastModified() < REFRESH_MS;
        if (fresh || now - lastAttempt < REFRESH_MS || !RUNNING.compareAndSet(false, true)) {
            if (cached != null && !RUNNING.get()) retryCovers(ctx, cfg, cached.apps);
            return;
        }
        lastAttempt = now;
        syncNow = false;
        try {
            sync(ctx, cfg);
        } catch (IOException | JSONException | java.security.GeneralSecurityException e) {
            Log.i(TAG, "Could not sync with " + cfg.gamestreamHost + ": " + e);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void sync(Context ctx, Config cfg)
            throws IOException, JSONException, java.security.GeneralSecurityException {
        File dir = identityDir(ctx);
        GameStreamIdentity identity = GameStreamIdentity.load(dir);
        // Pairing is the Games tab's and Settings' job, while the PIN is on screen.
        if (!identity.paired()) return;
        GameStreamClient client = new GameStreamClient(cfg.gamestreamHost, identity);
        GameStreamClient.ServerInfo info = client.serverInfo();
        List<GameStreamClient.App> apps;
        try {
            apps = client.appList();
        } catch (GameStreamClient.AuthException e) {
            identity.clearPaired();
            throw e;
        }
        store(ctx, client, info, apps);
    }

    private static void store(Context ctx, GameStreamClient client, GameStreamClient.ServerInfo info,
                              List<GameStreamClient.App> apps) throws IOException, JSONException {
        Cache fresh = new Cache(info.uuid, info.hostname, apps);
        write(cacheFile(ctx), toJson(fresh).getBytes(StandardCharsets.UTF_8));
        hostUuid = fresh.uuid;
        hostName = fresh.name;
        writeLaunches(ctx, prune(readLaunches(ctx), apps));
        blankPlaceholders(ctx);
        fetchCovers(ctx, client, apps);
    }

    /**
     * Blocking; call off the main thread. Asks the host for its running app and a fresh app list, quits another app
     * if one runs, and refreshes the cache on the way. Starting Moonlight is left to the caller.
     */
    static GameStart.Result prepareStart(Context ctx, Config cfg, SteamGame game) throws InterruptedException {
        GameStreamIdentity identity;
        try {
            identity = GameStreamIdentity.load(identityDir(ctx));
        } catch (IOException | java.security.GeneralSecurityException e) {
            Log.w(TAG, "Could not load the pairing: " + e);
            return new GameStart.Result(GameStart.Outcome.UNPAIRED, null, null, null, null);
        }
        if (!identity.paired()) return new GameStart.Result(GameStart.Outcome.UNPAIRED, null, null, null, null);
        GameStreamClient client = new GameStreamClient(cfg.gamestreamHost, identity);
        GameStart.Result result;
        try {
            result = GameStart.run(host(client), game);
        } catch (RuntimeException e) {
            Log.w(TAG, "Start " + game.name + " failed", e);
            return new GameStart.Result(GameStart.Outcome.UNREACHABLE, null, null, null, null);
        }
        Log.i(TAG, "Start " + game.name + ": " + result.outcome);
        if (result.outcome == GameStart.Outcome.UNPAIRED) identity.clearPaired();
        if (result.apps != null) {
            try {
                store(ctx, client, result.info, result.apps);
                lastAttempt = System.currentTimeMillis();
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Could not save the app list: " + e);
            }
        }
        return result;
    }

    private static GameStart.Host host(GameStreamClient client) {
        return new GameStart.Host() {
            @Override public GameStreamClient.ServerInfo serverInfo() throws IOException {
                return client.serverInfo(GameStart.SERVERINFO_TIMEOUT_MS);
            }

            @Override public List<GameStreamClient.App> appList() throws IOException {
                return client.appList();
            }

            @Override public boolean quitApp() throws IOException {
                return client.quitApp();
            }

            @Override public long now() {
                return android.os.SystemClock.elapsedRealtime();
            }

            @Override public void sleep(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        };
    }

    /** The host's name for messages: the Moonlight override, else what the host calls itself, else its address. */
    static String hostLabel(Config cfg) {
        if (!cfg.moonlightHostName.isEmpty()) return cfg.moonlightHostName;
        return hostName.isEmpty() ? cfg.gamestreamHost : hostName;
    }

    /** Missing covers from the cached list, at most every COVER_RETRY_MS; needs the pairing. */
    private static void retryCovers(Context ctx, Config cfg, List<GameStreamClient.App> apps) {
        // Covers saved by an older version can still hold the stock picture.
        if (!placeholdersChecked) {
            placeholdersChecked = true;
            blankPlaceholders(ctx);
            Runnable listener = coverListener;
            if (listener != null) listener.run();
        }
        long now = System.currentTimeMillis();
        if (missingCovers(ctx, apps).isEmpty() || now - lastCoverAttempt < COVER_RETRY_MS) return;
        try {
            GameStreamIdentity identity = GameStreamIdentity.load(identityDir(ctx));
            if (!identity.paired()) return;
            GameStreamClient client = new GameStreamClient(cfg.gamestreamHost, identity);
            client.serverInfo();
            fetchCovers(ctx, client, apps);
        } catch (IOException | java.security.GeneralSecurityException e) {
            lastCoverAttempt = now;
            Log.i(TAG, "Could not fetch covers from " + cfg.gamestreamHost + ": " + e);
        }
    }

    /** Apps without a cover file; an empty file marks one the host had no art for, until NO_ART_MS passes. */
    private static List<Integer> missingCovers(Context ctx, List<GameStreamClient.App> apps) {
        List<Integer> missing = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (GameStreamClient.App app : apps) {
            if (app.id == 0) continue;
            File file = new File(coverDir(ctx), app.id + ".png");
            if (!file.isFile() || (file.length() == 0 && now - file.lastModified() > NO_ART_MS)) missing.add(app.id);
        }
        return missing;
    }

    /** Names whose cover is the host's stock picture: its hash is shared by 3+ apps or already known as stock. */
    static Set<String> placeholders(Map<String, String> hashByName, Set<String> knownHashes) {
        Map<String, Integer> counts = new HashMap<>();
        for (String hash : hashByName.values()) counts.merge(hash, 1, Integer::sum);
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : hashByName.entrySet()) {
            if (counts.get(e.getValue()) >= PLACEHOLDER_SHARERS || knownHashes.contains(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** Empties covers that are the host's stock picture, so those games show their name instead. */
    private static synchronized void blankPlaceholders(Context ctx) {
        File dir = coverDir(ctx);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (files == null) return;
        File version = new File(dir, "marks.version");
        File known = new File(dir, "placeholder.hashes");
        try {
            // Marks from before the 3-app rule may have blanked real shared covers.
            if (!version.isFile() || !MARKS_VERSION.equals(new String(Files.readAllBytes(version.toPath()), StandardCharsets.UTF_8))) {
                for (File f : files) if (f.length() == 0) f.delete();
                known.delete();
                write(version, MARKS_VERSION.getBytes(StandardCharsets.UTF_8));
                files = dir.listFiles((d, name) -> name.endsWith(".png"));
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not reset cover marks: " + e);
        }
        Set<String> knownHashes = new HashSet<>();
        try {
            if (known.isFile()) {
                knownHashes.addAll(Arrays.asList(new String(Files.readAllBytes(known.toPath()), StandardCharsets.UTF_8).split("\\s+")));
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read stock hashes: " + e);
        }
        Map<String, String> hashes = new HashMap<>();
        try {
            for (File f : files) {
                if (f.length() == 0) continue;
                byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(f.toPath()));
                hashes.put(f.getName(), GameStreamCrypto.hex(digest));
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            Log.w(TAG, "Could not compare covers: " + e);
            return;
        }
        Set<String> blank = placeholders(hashes, knownHashes);
        for (String name : blank) {
            knownHashes.add(hashes.get(name));
            try {
                write(new File(dir, name), new byte[0]);
            } catch (IOException e) {
                Log.w(TAG, "Could not blank " + name + ": " + e);
            }
        }
        if (!blank.isEmpty()) {
            try {
                write(known, String.join("\n", knownHashes).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "Could not save stock hashes: " + e);
            }
        }
    }

    /** Downloads the missing covers on a small pool and returns at once; one failing does not stop the others. */
    private static void fetchCovers(Context ctx, GameStreamClient client, List<GameStreamClient.App> apps) {
        List<Integer> missing = missingCovers(ctx, apps);
        if (missing.isEmpty() || !COVERS_RUNNING.compareAndSet(false, true)) return;
        lastCoverAttempt = System.currentTimeMillis();
        Log.i(TAG, "Fetching " + missing.size() + " covers");
        ExecutorService pool = Executors.newFixedThreadPool(COVER_THREADS);
        AtomicInteger left = new AtomicInteger(missing.size()), failed = new AtomicInteger();
        for (int appId : missing) {
            pool.execute(() -> {
                try {
                    if (downloadCover(ctx, client, appId)) {
                        Runnable listener = coverListener;
                        if (listener != null) listener.run();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    failed.incrementAndGet();
                    Log.w(TAG, "Cover " + appId + " failed: " + e);
                } finally {
                    if (left.decrementAndGet() == 0) {
                        blankPlaceholders(ctx);
                        Log.i(TAG, "Covers done, " + failed.get() + " failed");
                        COVERS_RUNNING.set(false);
                        Runnable listener = coverListener;
                        if (listener != null) listener.run();
                    }
                }
            });
        }
        pool.shutdown();
    }

    private static Cache readCache(Context ctx, Config cfg) {
        if (!configured(cfg)) return null;
        try {
            File file = cacheFile(ctx);
            if (!file.isFile()) return null;
            Cache cache = parseCache(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            hostUuid = cache.uuid;
            hostName = cache.name;
            return cache;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Could not read the cache: " + e);
            return null;
        }
    }

    private static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), "gamestream_apps.json");
    }

    static boolean paired(Context ctx) {
        return new File(identityDir(ctx), "server.crt").isFile();
    }

    /** Drops the pairing and what was read from the host, for a new host or "Forget this PC". */
    static synchronized void forget(Context ctx) {
        new File(identityDir(ctx), "server.crt").delete();
        cacheFile(ctx).delete();
        File[] covers = coverDir(ctx).listFiles();
        if (covers != null) for (File f : covers) f.delete();
        hostUuid = "";
        hostName = "";
        lastAttempt = 0;
        lastCoverAttempt = 0;
    }

    /** Lets the next refresh sync at once even with a fresh cache, after pairing. */
    static void syncSoon() {
        lastAttempt = 0;
        syncNow = true;
    }

    static File identityDir(Context ctx) {
        return new File(ctx.getFilesDir(), "gamestream");
    }

    private static File coverDir(Context ctx) {
        return new File(ctx.getFilesDir(), "steam");
    }

    /** True when the cover is now on disk. */
    private static boolean downloadCover(Context ctx, GameStreamClient client, int appId) {
        File dir = coverDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File part = new File(dir, appId + ".part");
        try {
            byte[] bytes = client.boxArt(appId);
            Bitmap bitmap = decodeScaled(bytes, COVER_WIDTH);
            if (bitmap == null) {
                Log.i(TAG, "Cover " + appId + " did not decode (" + bytes.length + " bytes)");
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(part)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                bitmap.recycle();
            }
            // Renamed into place whole, so the tiles never read a half-written file.
            return part.renameTo(new File(dir, appId + ".png"));
        } catch (IOException e) {
            Log.i(TAG, "No cover for " + appId + ": " + e);
            part.delete();
            return false;
        }
    }

    private static Bitmap decodeScaled(byte[] bytes, int maxWidth) {
        Bitmap decoded = ImageCache.sampled(bytes, maxWidth, Bitmap.Config.ARGB_8888);
        if (decoded == null || decoded.getWidth() <= maxWidth) return decoded;
        int height = Math.max(1, Math.round(decoded.getHeight() * (float) maxWidth / decoded.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, maxWidth, height, true);
        decoded.recycle();
        return scaled;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
