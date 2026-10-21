package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Things hidden from Home on the TV, kept in {@link UserSettings}: an app ({@link #app}), one Continue watching
 * entry ({@link #watchEntry}), or all of an app's Continue watching ({@link #watchApp}).
 * The label is what Settings shows in its list to unhide. The Apps tab still lists everything.
 */
final class HiddenItems {
    /** WATCH is the old per-show key, migrated to entries by {@link #reconcile}. */
    static final String APP = "app:", WATCH = "watch:", ENTRY = "watchnext:", WATCH_APP = "watchapp:";
    static final int MAX = 500;
    /** A hidden entry that has left Watch Next this long ago is forgotten. */
    static final long PRUNE_MS = 30L * Usage.DAY_MS;

    private HiddenItems() { }

    static final class Item {
        final String key, label;
        /** When a hidden entry was last seen in Watch Next; 0 when unknown or not an entry. */
        final long seen;

        Item(String key, String label) {
            this(key, label, 0);
        }

        Item(String key, String label, long seen) {
            this.key = key;
            this.label = label;
            this.seen = seen;
        }
    }

    static String app(String pkg) {
        return APP + pkg;
    }

    /** The old per-show key, only read now to migrate it. */
    static String watch(String pkg, String title) {
        return WATCH + pkg + "/" + (title == null ? "" : title);
    }

    /**
     * One Watch Next row: its id plus the episode, so the app moving the same row on to the next episode shows it
     * again while the hidden episode stays hidden.
     */
    static String watchEntry(WatchItem item) {
        return ENTRY + item.pkg + "/" + norm(item.id) + "/" + norm(item.title) + "/" + norm(item.subtitle);
    }

    static String watchApp(String pkg) {
        return WATCH_APP + pkg;
    }

    /** "Show · S1E2 Episode", for Settings. */
    static String watchLabel(WatchItem item) {
        return item.subtitle == null ? item.title : item.title + " \u00B7 " + item.subtitle;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static boolean hide(Context ctx, String key, String label) {
        long now = System.currentTimeMillis();
        return UserSettings.edit(ctx, root -> root.put(UserSettings.HIDDEN,
                encode(add(decode(root.getJSONArray(UserSettings.HIDDEN)), key, label, now))));
    }

    /** Brings the hidden entries up to date with Watch Next and returns the hidden keys; saves only on a change. */
    static Set<String> reconcile(Context ctx, List<WatchItem> rows) {
        long now = System.currentTimeMillis();
        List<Item> items = list(ctx);
        if (!same(items, reconcile(items, rows, now))) {
            UserSettings.edit(ctx, root -> root.put(UserSettings.HIDDEN,
                    encode(reconcile(decode(root.optJSONArray(UserSettings.HIDDEN)), rows, now))));
        }
        return keys(ctx);
    }

    static boolean unhide(Context ctx, String key) {
        return UserSettings.edit(ctx, root -> root.put(UserSettings.HIDDEN,
                encode(remove(decode(root.getJSONArray(UserSettings.HIDDEN)), key))));
    }

    /** Oldest first. */
    static List<Item> list(Context ctx) {
        return decode(UserSettings.read(ctx).optJSONArray(UserSettings.HIDDEN));
    }

    static Set<String> keys(Context ctx) {
        return keys(list(ctx));
    }

    /**
     * Old per-show keys become entries for what that show has in Watch Next now; a hidden entry still in Watch Next
     * is marked seen (at most daily, to spare writes) and one gone for {@link #PRUNE_MS} is dropped.
     */
    static List<Item> reconcile(List<Item> items, List<WatchItem> rows, long now) {
        Map<String, WatchItem> current = new LinkedHashMap<>();
        for (WatchItem w : rows) current.put(watchEntry(w), w);
        List<Item> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (Item item : items) {
            if (item.key.startsWith(WATCH)) {
                for (Map.Entry<String, WatchItem> e : current.entrySet()) {
                    WatchItem w = e.getValue();
                    if (item.key.equals(watch(w.pkg, w.title)) && added.add(e.getKey())) {
                        out.add(new Item(e.getKey(), watchLabel(w), now));
                    }
                }
            } else if (item.key.startsWith(ENTRY)) {
                boolean present = current.containsKey(item.key);
                if (!present && item.seen > 0 && now - item.seen > PRUNE_MS) continue;
                long seen = item.seen <= 0 || (present && now - item.seen >= Usage.DAY_MS) ? now : item.seen;
                if (added.add(item.key)) out.add(new Item(item.key, item.label, seen));
            } else if (added.add(item.key)) {
                out.add(item);
            }
        }
        return out;
    }

    static boolean same(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Item x = a.get(i), y = b.get(i);
            if (!x.key.equals(y.key) || !x.label.equals(y.label) || x.seen != y.seen) return false;
        }
        return true;
    }

    /** Packages of hidden apps. */
    static Set<String> apps(Set<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : keys) if (key.startsWith(APP)) out.add(key.substring(APP.length()));
        return out;
    }

    static Set<String> keys(List<Item> items) {
        Set<String> out = new LinkedHashSet<>();
        for (Item item : items) out.add(item.key);
        return out;
    }

    /** A Continue watching entry is hidden by itself, with all of its app's Continue watching, or with the app. */
    static boolean watchHidden(Set<String> hidden, WatchItem item) {
        return hidden.contains(watchEntry(item)) || hidden.contains(watchApp(item.pkg)) || hidden.contains(app(item.pkg));
    }

    /** The first {@code limit} items that are not hidden, in order. */
    static <T> List<T> visible(List<T> items, Predicate<T> hidden, int limit) {
        List<T> out = new ArrayList<>();
        for (T item : items) {
            if (out.size() >= limit) break;
            if (!hidden.test(item)) out.add(item);
        }
        return out;
    }

    static List<Item> add(List<Item> items, String key, String label) {
        return add(items, key, label, 0);
    }

    /** {@code now} stamps an entry as seen, so pruning counts from when it was hidden. */
    static List<Item> add(List<Item> items, String key, String label, long now) {
        List<Item> out = remove(items, key);
        out.add(new Item(key, label == null || label.trim().isEmpty() ? key : label.trim(),
                key.startsWith(ENTRY) ? now : 0));
        return out.size() > MAX ? new ArrayList<>(out.subList(out.size() - MAX, out.size())) : out;
    }

    static List<Item> remove(List<Item> items, String key) {
        List<Item> out = new ArrayList<>();
        for (Item item : items) if (!item.key.equals(key)) out.add(item);
        return out;
    }

    static JSONArray encode(List<Item> items) throws JSONException {
        JSONArray a = new JSONArray();
        for (Item item : items) {
            JSONObject o = new JSONObject().put("key", item.key).put("label", item.label);
            if (item.seen > 0) o.put("seen", item.seen);
            a.put(o);
        }
        return a;
    }

    /** Skips malformed entries rather than dropping the whole list. */
    static List<Item> decode(JSONArray a) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String key = o == null ? null : o.optString("key", null);
            if (key == null || key.isEmpty() || !seen.add(key)) continue;
            out.add(new Item(key, o.optString("label", key), o.optLong("seen", 0)));
        }
        return out;
    }
}
