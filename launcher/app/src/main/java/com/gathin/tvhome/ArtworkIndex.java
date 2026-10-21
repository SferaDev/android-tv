package com.gathin.tvhome;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The artwork cache index: normalised query to {url, file, fetchedAt, miss}, with expiry and eviction decisions. */
final class ArtworkIndex {
    static final long HIT_TTL_MS = 60L * 24 * 60 * 60 * 1000;
    static final long MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    static final class Entry {
        final String url;
        final String file;
        final long fetchedAt;
        final boolean miss;

        Entry(String url, String file, long fetchedAt, boolean miss) {
            this.url = url;
            this.file = file;
            this.fetchedAt = fetchedAt;
            this.miss = miss;
        }

        boolean fresh(long now) {
            return now - fetchedAt < (miss ? MISS_TTL_MS : HIT_TTL_MS);
        }
    }

    /** A file in the cache folder, as far as eviction cares. */
    static final class CacheFile {
        final String name;
        final long size;
        final long modified;

        CacheFile(String name, long size, long modified) {
            this.name = name;
            this.size = size;
            this.modified = modified;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    Entry get(String key) {
        return entries.get(key);
    }

    void put(String key, Entry entry) {
        entries.put(key, entry);
    }

    /** Drops expired entries; returns the files the dropped hits pointed at. */
    List<String> prune(long now) {
        List<String> files = new ArrayList<>();
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            if (e.fresh(now)) continue;
            if (e.file != null) files.add(e.file);
            it.remove();
        }
        return files;
    }

    /** Drops entries whose file is not in the cache folder any more (evicted); misses stay. */
    void forgetMissing(java.util.Set<String> present) {
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            if (!e.miss && (e.file == null || !present.contains(e.file))) it.remove();
        }
    }

    /** The oldest files to delete so the rest fit in budget bytes; {@code keep} is never chosen. */
    static List<String> evict(List<CacheFile> files, long budget, String keep) {
        long total = 0;
        for (CacheFile f : files) total += f.size;
        List<CacheFile> oldestFirst = new ArrayList<>(files);
        Collections.sort(oldestFirst, (a, b) -> Long.compare(a.modified, b.modified));
        List<String> drop = new ArrayList<>();
        for (CacheFile f : oldestFirst) {
            if (total <= budget) break;
            if (f.name.equals(keep)) continue;
            drop.add(f.name);
            total -= f.size;
        }
        return drop;
    }

    String toJson() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                Entry v = e.getValue();
                JSONObject o = new JSONObject();
                o.put("url", v.url == null ? "" : v.url);
                o.put("file", v.file == null ? "" : v.file);
                o.put("fetchedAt", v.fetchedAt);
                o.put("miss", v.miss);
                root.put(e.getKey(), o);
            }
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    static ArtworkIndex fromJson(String json) {
        ArtworkIndex index = new ArtworkIndex();
        if (json == null) return index;
        try {
            JSONObject root = new JSONObject(json);
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject o = root.optJSONObject(key);
                if (o == null) continue;
                String file = o.optString("file", "");
                index.entries.put(key, new Entry(o.optString("url", ""), file.isEmpty() ? null : file,
                        o.optLong("fetchedAt", 0), o.optBoolean("miss", false)));
            }
        } catch (Exception ignored) {
        }
        return index;
    }
}
