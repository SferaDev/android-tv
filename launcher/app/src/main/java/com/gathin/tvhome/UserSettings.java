package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Choices made on the TV in the launcher's Settings, kept in the app's own data (never in the repo or a theme):
 * {@code {"version": 1, "config": {...}, "hidden": [{"key": ..., "label": ...}]}}. "config" takes the same keys as a
 * theme's config.json and {@link Config#load} applies it last, over config.xml and the theme.
 */
final class UserSettings {
    private static final String TAG = "TvHome.UserSettings";
    static final String FILE_NAME = "user_settings.json";
    static final int VERSION = 1;
    static final String CONFIG = "config";
    static final String HIDDEN = "hidden";
    private static final int MAX_BYTES = 256 * 1024;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    interface Listener {
        /** On the main thread, after any change to the store. */
        void onUserSettingsChanged();
    }

    interface Change {
        void apply(JSONObject root) throws JSONException;
    }

    private UserSettings() { }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** The "config" overlay; empty when nothing was chosen on the TV. */
    static JSONObject config(Context ctx) {
        return read(ctx).optJSONObject(CONFIG);
    }

    static boolean getBoolean(Context ctx, String key, boolean fallback) {
        Object v = config(ctx).opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    static int getInt(Context ctx, String key, int fallback) {
        Object v = config(ctx).opt(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    /** Sets one config key: a String, Boolean, Number, List of strings or int[]; null removes it. */
    static boolean put(Context ctx, String key, Object value) {
        return edit(ctx, root -> {
            JSONObject config = root.getJSONObject(CONFIG);
            if (value == null) config.remove(key);
            else config.put(key, json(value));
        });
    }

    static boolean remove(Context ctx, String... keys) {
        return edit(ctx, root -> {
            for (String key : keys) root.getJSONObject(CONFIG).remove(key);
        });
    }

    /** Forgets every choice made on the TV. */
    static boolean clear(Context ctx) {
        return edit(ctx, root -> {
            root.put(CONFIG, new JSONObject());
            root.put(HIDDEN, new JSONArray());
        });
    }

    /** Read, change and save in one step; listeners hear about it when the save worked. */
    static boolean edit(Context ctx, Change change) {
        boolean saved;
        synchronized (UserSettings.class) {
            JSONObject root;
            try {
                // Never replace an unreadable file with an empty one on the next edit.
                root = readStrict(ctx);
                change.apply(root);
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "Could not change settings", e);
                return false;
            }
            saved = write(ctx, root);
        }
        if (saved) notifyChanged();
        return saved;
    }

    private static void notifyChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Listener l : LISTENERS) l.onUserSettingsChanged();
        });
    }

    static Object json(Object value) {
        if (value instanceof List) {
            JSONArray a = new JSONArray();
            for (Object o : (List<?>) value) a.put(o);
            return a;
        }
        if (value instanceof int[]) {
            JSONArray a = new JSONArray();
            for (int i : (int[]) value) a.put(i);
            return a;
        }
        return value;
    }

    /** Brings any older or damaged root to the current shape, always with "version", "config" and "hidden". */
    static JSONObject migrate(JSONObject root) throws JSONException {
        JSONObject out = new JSONObject();
        int version = root.optInt("version", 0);
        if (version < 1) {
            // Version 0 kept the config keys at the top level.
            JSONObject config = new JSONObject();
            for (Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                if (!key.equals("version") && !key.equals(HIDDEN)) config.put(key, root.get(key));
            }
            root = new JSONObject().put(CONFIG, config).put(HIDDEN, root.opt(HIDDEN));
        }
        JSONObject config = root.optJSONObject(CONFIG);
        JSONArray hidden = root.optJSONArray(HIDDEN);
        return out.put("version", VERSION).put(CONFIG, config == null ? new JSONObject() : config)
                .put(HIDDEN, hidden == null ? new JSONArray() : hidden);
    }

    static JSONObject decode(String text) throws JSONException {
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
        return migrate(text.trim().isEmpty() ? new JSONObject() : new JSONObject(text));
    }

    static String encode(JSONObject root) throws JSONException {
        return migrate(root).toString(2);
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    static synchronized JSONObject read(Context ctx) {
        try {
            return readStrict(ctx);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not read settings", e);
            return empty();
        }
    }

    static JSONObject empty() {
        try {
            return migrate(new JSONObject());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static synchronized JSONObject readStrict(Context ctx) throws IOException, JSONException {
        String text = StoredText.read(file(ctx), MAX_BYTES);
        return text == null ? empty() : decode(text);
    }

    private static boolean write(Context ctx, JSONObject root) {
        try {
            StoredText.write(file(ctx), encode(root));
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "Could not save settings", e);
            return false;
        }
    }
}
