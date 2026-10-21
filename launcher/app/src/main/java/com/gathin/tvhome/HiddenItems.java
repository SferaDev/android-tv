package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Things hidden from Home on the TV, kept in {@link UserSettings}: an app ({@link #app}). The label is what Settings
 * shows in its list to unhide.
 */
final class HiddenItems {
    static final String APP = "app:";
    static final int MAX = 500;

    private HiddenItems() { }

    static final class Item {
        final String key, label;

        Item(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static String app(String pkg) {
        return APP + pkg;
    }

    static boolean hide(Context ctx, String key, String label) {
        return UserSettings.edit(ctx, root -> root.put(UserSettings.HIDDEN,
                encode(add(decode(root.getJSONArray(UserSettings.HIDDEN)), key, label))));
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

    static List<Item> add(List<Item> items, String key, String label) {
        List<Item> out = remove(items, key);
        out.add(new Item(key, label == null || label.trim().isEmpty() ? key : label.trim()));
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
            a.put(new JSONObject().put("key", item.key).put("label", item.label));
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
            out.add(new Item(key, o.optString("label", key)));
        }
        return out;
    }
}
