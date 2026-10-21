package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inputs and channels opened from the launcher, newest first. The system's watched-programs table needs a
 *  privileged permission, so the launcher keeps its own list in filesDir. */
final class RecentTv {
    private static final String TAG = "TvHome.RecentTv";
    static final String INPUT = "input";
    static final String CHANNEL = "channel";
    static final int MAX_ENTRIES = 20;
    static final long MAX_AGE_MS = 60L * Usage.DAY_MS;
    private static final int MAX_BYTES = 64 * 1024;

    private RecentTv() { }

    static final class Entry {
        final String kind;
        final String id;
        final long timestamp;

        Entry(String kind, String id, long timestamp) {
            this.kind = kind;
            this.id = id;
            this.timestamp = timestamp;
        }

        String key() { return RecentTv.key(kind, id); }
    }

    /** Hidden-item key, "tv:input:<id>" or "tv:channel:<id>". */
    static String key(String kind, String id) {
        return "tv:" + kind + ":" + id;
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "recent_tv.json");
    }

    static synchronized void record(Context ctx, String kind, String id) {
        write(ctx, merge(read(ctx), new Entry(kind, id, System.currentTimeMillis()), System.currentTimeMillis()));
    }

    /** Newest first, without entries whose input or channel is gone. Channels are kept when they cannot be
     *  checked (no listings permission). Blocking: reads the channel table. */
    static synchronized List<Entry> list(Context ctx) {
        List<Entry> entries = read(ctx);
        Set<String> inputs = new HashSet<>();
        for (TvInputs.Input i : TvInputs.list(ctx)) inputs.add(i.id);
        Set<String> channels = null;
        if (!WatchNext.missingPermission(ctx)) {
            channels = new HashSet<>();
            for (TvChannels.Channel c : TvChannels.list(ctx)) channels.add(String.valueOf(c.id));
        }
        List<Entry> kept = prune(entries, inputs, channels);
        if (kept.size() != entries.size()) write(ctx, kept);
        return kept;
    }

    static synchronized String lastInput(Context ctx) {
        for (Entry e : read(ctx)) if (INPUT.equals(e.kind)) return e.id;
        return null;
    }

    /** Puts the entry first, replaces an older one for the same target, drops stale ones, caps the list. */
    static List<Entry> merge(List<Entry> entries, Entry entry, long now) {
        List<Entry> next = new ArrayList<>();
        next.add(entry);
        for (Entry e : entries) {
            if (!e.key().equals(entry.key()) && now - e.timestamp <= MAX_AGE_MS) next.add(e);
        }
        next.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return next.size() > MAX_ENTRIES ? new ArrayList<>(next.subList(0, MAX_ENTRIES)) : next;
    }

    /** A null id set means that kind cannot be checked, so its entries stay. */
    static List<Entry> prune(List<Entry> entries, Set<String> inputIds, Set<String> channelIds) {
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            Set<String> known = INPUT.equals(e.kind) ? inputIds : CHANNEL.equals(e.kind) ? channelIds : Set.of();
            if (known == null || known.contains(e.id)) kept.add(e);
        }
        kept.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return kept;
    }

    static String encode(List<Entry> entries) throws JSONException {
        JSONArray array = new JSONArray();
        for (Entry e : entries) {
            array.put(new JSONObject().put("kind", e.kind).put("id", e.id).put("ts", e.timestamp));
        }
        return new JSONObject().put("entries", array).toString();
    }

    static List<Entry> decode(String text) throws JSONException {
        List<Entry> entries = new ArrayList<>();
        JSONArray array = new JSONObject(text).optJSONArray("entries");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            entries.add(new Entry(o.getString("kind"), o.getString("id"), o.optLong("ts")));
        }
        return entries;
    }

    private static List<Entry> read(Context ctx) {
        try {
            String text = StoredText.read(file(ctx), MAX_BYTES);
            return text == null ? new ArrayList<>() : decode(text);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not read recent TV", e);
            return new ArrayList<>();
        }
    }

    private static void write(Context ctx, List<Entry> entries) {
        try {
            StoredText.write(file(ctx), encode(entries));
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not save recent TV", e);
        }
    }
}
