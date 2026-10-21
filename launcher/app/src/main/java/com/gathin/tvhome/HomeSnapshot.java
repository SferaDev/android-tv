package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The last data of each Home strip, kept in filesDir so a cold start can draw the strips before the live loads
 * finish. Now playing is a live session and is never stored.
 */
final class HomeSnapshot {
    private static final String TAG = "TvHome.Snapshot";
    static final String FILE_NAME = "home_snapshot.json";
    static final int VERSION = 1;
    static final int MAX_ITEMS = 40;
    private static final int MAX_BYTES = 256 * 1024;

    final List<String> recent;
    final List<WatchItem> watching;
    final List<SteamGame> games;
    final List<TvCards.Tile> tv;

    HomeSnapshot(List<String> recent, List<WatchItem> watching, List<SteamGame> games, List<TvCards.Tile> tv) {
        this.recent = capped(recent);
        this.watching = capped(watching);
        this.games = capped(games);
        this.tv = capped(tv);
    }

    boolean isEmpty() {
        return recent.isEmpty() && watching.isEmpty() && games.isEmpty() && tv.isEmpty();
    }

    private static <T> List<T> capped(List<T> list) {
        return list.size() > MAX_ITEMS ? new ArrayList<>(list.subList(0, MAX_ITEMS)) : list;
    }

    String encode() throws JSONException {
        JSONArray recentArray = new JSONArray();
        for (String pkg : recent) recentArray.put(pkg);
        JSONArray watchingArray = new JSONArray();
        for (WatchItem w : watching) {
            watchingArray.put(new JSONObject().put("pkg", w.pkg).put("title", orNull(w.title))
                    .put("subtitle", orNull(w.subtitle)).put("artwork", orNull(w.artwork))
                    .put("link", orNull(w.deepLink)).put("last", w.lastWatched).put("progress", (double) w.progress));
        }
        JSONArray gamesArray = new JSONArray();
        for (SteamGame g : games) gamesArray.put(new JSONObject().put("id", g.appId).put("name", g.name));
        JSONArray tvArray = new JSONArray();
        for (TvCards.Tile t : tv) {
            JSONObject o = new JSONObject().put("subtitle", orNull(t.subtitle)).put("current", t.current);
            if (t.input != null) {
                o.put("input", new JSONObject().put("id", t.input.id).put("label", t.input.label)
                        .put("type", t.input.type));
            } else {
                o.put("channel", new JSONObject().put("id", t.channel.id).put("input", t.channel.inputId)
                        .put("number", t.channel.number).put("name", t.channel.name));
            }
            tvArray.put(o);
        }
        return new JSONObject().put("version", VERSION).put("recent", recentArray).put("watching", watchingArray)
                .put("games", gamesArray).put("tv", tvArray).toString();
    }

    private static Object orNull(String s) {
        return s == null ? JSONObject.NULL : s;
    }

    private static String optString(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    /** Throws on a malformed file or another version; entries that do not parse are skipped one by one. */
    static HomeSnapshot decode(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        if (root.optInt("version") != VERSION) throw new JSONException("snapshot version");
        List<String> recent = new ArrayList<>();
        JSONArray recentArray = root.optJSONArray("recent");
        for (int i = 0; recentArray != null && i < recentArray.length(); i++) {
            String pkg = recentArray.optString(i, "");
            if (!pkg.isEmpty()) recent.add(pkg);
        }
        List<WatchItem> watching = new ArrayList<>();
        JSONArray watchingArray = root.optJSONArray("watching");
        for (int i = 0; watchingArray != null && i < watchingArray.length(); i++) {
            JSONObject o = watchingArray.optJSONObject(i);
            if (o == null || o.optString("pkg", "").isEmpty()) continue;
            watching.add(new WatchItem(o.getString("pkg"), optString(o, "title"), optString(o, "subtitle"),
                    optString(o, "artwork"), optString(o, "link"), o.optLong("last"),
                    (float) o.optDouble("progress", -1)));
        }
        List<SteamGame> games = new ArrayList<>();
        JSONArray gamesArray = root.optJSONArray("games");
        for (int i = 0; gamesArray != null && i < gamesArray.length(); i++) {
            JSONObject o = gamesArray.optJSONObject(i);
            if (o != null && !o.optString("name", "").isEmpty()) games.add(new SteamGame(o.optInt("id"), o.getString("name")));
        }
        List<TvCards.Tile> tv = new ArrayList<>();
        JSONArray tvArray = root.optJSONArray("tv");
        for (int i = 0; tvArray != null && i < tvArray.length(); i++) {
            JSONObject o = tvArray.optJSONObject(i);
            if (o == null) continue;
            JSONObject in = o.optJSONObject("input"), ch = o.optJSONObject("channel");
            if (in != null && !in.optString("id", "").isEmpty()) {
                tv.add(new TvCards.Tile(new TvInputs.Input(in.getString("id"), in.optString("label"), in.optInt("type")),
                        optString(o, "subtitle"), o.optBoolean("current")));
            } else if (ch != null && ch.has("id")) {
                tv.add(new TvCards.Tile(new TvChannels.Channel(ch.getLong("id"), ch.optString("input"),
                        ch.optString("number"), ch.optString("name")), optString(o, "subtitle")));
            }
        }
        return new HomeSnapshot(recent, watching, games, tv);
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** Null when there is none yet or it cannot be read. */
    static HomeSnapshot load(Context ctx) {
        try {
            String text = StoredText.read(file(ctx), MAX_BYTES);
            return text == null ? null : decode(text);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not read the snapshot", e);
            return null;
        }
    }

    static synchronized void save(Context ctx, String text) {
        try {
            StoredText.write(file(ctx), text);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not save the snapshot", e);
        }
    }
}
