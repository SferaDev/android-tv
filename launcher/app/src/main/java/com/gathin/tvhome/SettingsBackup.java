package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A file with the choices made on the TV: {@code {"kind": "tvhome-settings", "version": 1, "config": {...},
 * "hidden": [...]}}. "config" carries the remote button mappings too (app_keys, settings_key_keycodes,
 * input_key_keycodes). Only the keys listed in {@link #KEYS} are written or read back, so nothing else the launcher
 * keeps (the adb key) can end up in a backup.
 */
final class SettingsBackup {
    static final String KIND = "tvhome-settings";
    static final int VERSION = 1;
    static final String FILE_NAME = "launcher-settings.json";
    static final int MAX_BYTES = 256 * 1024;

    private enum Type { STRING, BOOL, INT, STRINGS, INTS }

    private static final Map<String, Type> KEYS = new LinkedHashMap<>();

    static {
        for (String k : new String[]{"live_tv_package", "live_tv_action", "source_panel_service", "source_panel_action",
                "input_action", "vpn_app", "background_color"}) KEYS.put(k, Type.STRING);
        for (String k : new String[]{"animations", "advanced_effects", "shamsi_date", "module_cards", "reduced_motion",
                "online_artwork", "show_home_settings_shortcut", "show_stats", "app_library", "settings_key_panel",
                "wallpaper", "show_network_pill", "wallpaper_true_color"}) KEYS.put(k, Type.BOOL);
        for (String k : new String[]{"columns", "sky_intensity", "wallpaper_rotation_minutes", "wallpaper_dim",
                "home_idle_clock_minutes"}) {
            KEYS.put(k, Type.INT);
        }
        for (String k : new String[]{"dock_apps", "hidden_apps", "app_keys", "home_rows"}) KEYS.put(k, Type.STRINGS);
        for (String k : new String[]{"settings_key_keycodes", "input_key_keycodes"}) KEYS.put(k, Type.INTS);
    }

    private SettingsBackup() { }

    /** Why a file was refused. */
    static final class Invalid extends Exception {
        final boolean newer;

        Invalid(String message, boolean newer) {
            super(message);
            this.newer = newer;
        }
    }

    /** What a backup would set: the config keys it holds and the hidden items. */
    static final class Parsed {
        final JSONObject config = new JSONObject();
        final List<HiddenItems.Item> hidden = new ArrayList<>();
        /** Config keys in the file that were unknown or had the wrong type. */
        final List<String> skipped = new ArrayList<>();
    }

    /** What applying a backup changes on this TV. */
    static final class Preview {
        final List<String> changedKeys = new ArrayList<>();
        int newHidden;

        boolean isEmpty() {
            return changedKeys.isEmpty() && newHidden == 0;
        }
    }

    static String export(JSONObject userRoot) throws JSONException {
        JSONObject root = UserSettings.migrate(userRoot);
        JSONObject config = new JSONObject();
        JSONObject have = root.getJSONObject(UserSettings.CONFIG);
        for (Map.Entry<String, Type> key : KEYS.entrySet()) {
            Object value = have.opt(key.getKey());
            if (value != null && fits(key.getValue(), value)) config.put(key.getKey(), value);
        }
        return new JSONObject().put("kind", KIND).put("version", VERSION).put("config", config)
                .put("hidden", HiddenItems.encode(HiddenItems.decode(root.getJSONArray(UserSettings.HIDDEN))))
                .toString(2);
    }

    static Parsed parse(String text) throws Invalid {
        JSONObject root;
        try {
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new Invalid("not JSON", false);
        }
        if (!KIND.equals(root.optString("kind"))) throw new Invalid("not a settings backup", false);
        int version = root.optInt("version", 0);
        if (version > VERSION) throw new Invalid("newer version " + version, true);
        if (version < 1) throw new Invalid("no version", false);
        Parsed out = new Parsed();
        JSONObject config = root.optJSONObject("config");
        for (Iterator<String> it = config == null ? new JSONObject().keys() : config.keys(); it.hasNext(); ) {
            String key = it.next();
            Type type = KEYS.get(key);
            Object value = config.opt(key);
            if (type == null || !fits(type, value)) {
                out.skipped.add(key);
                continue;
            }
            try {
                out.config.put(key, value);
            } catch (JSONException e) {
                out.skipped.add(key);
            }
        }
        out.hidden.addAll(HiddenItems.decode(root.optJSONArray("hidden")));
        return out;
    }

    static Preview preview(JSONObject userRoot, Parsed backup) throws JSONException {
        JSONObject root = UserSettings.migrate(userRoot);
        JSONObject have = root.getJSONObject(UserSettings.CONFIG);
        Preview out = new Preview();
        for (Iterator<String> it = backup.config.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!String.valueOf(have.opt(key)).equals(String.valueOf(backup.config.opt(key)))) out.changedKeys.add(key);
        }
        Collections.sort(out.changedKeys);
        Set<String> known = HiddenItems.keys(HiddenItems.decode(root.getJSONArray(UserSettings.HIDDEN)));
        for (HiddenItems.Item item : backup.hidden) if (!known.contains(item.key)) out.newHidden++;
        return out;
    }

    /** The backup's keys win over this TV's; what the file does not mention stays, and hidden items are added. */
    static void apply(JSONObject userRoot, Parsed backup) throws JSONException {
        JSONObject config = userRoot.getJSONObject(UserSettings.CONFIG);
        for (Iterator<String> it = backup.config.keys(); it.hasNext(); ) {
            String key = it.next();
            config.put(key, backup.config.get(key));
        }
        List<HiddenItems.Item> hidden = HiddenItems.decode(userRoot.getJSONArray(UserSettings.HIDDEN));
        for (HiddenItems.Item item : backup.hidden) {
            boolean present = false;
            for (HiddenItems.Item h : hidden) present |= h.key.equals(item.key);
            if (!present) hidden = HiddenItems.add(hidden, item.key, item.label);
        }
        userRoot.put(UserSettings.HIDDEN, HiddenItems.encode(hidden));
    }

    private static boolean fits(Type type, Object v) {
        switch (type) {
            case STRING: return v instanceof String;
            case BOOL: return v instanceof Boolean;
            case INT: return v instanceof Integer || v instanceof Long;
            case STRINGS: return all((JSONArray) (v instanceof JSONArray ? v : null), String.class);
            default: return all((JSONArray) (v instanceof JSONArray ? v : null), Number.class);
        }
    }

    private static boolean all(JSONArray a, Class<?> type) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (!type.isInstance(a.opt(i))) return false;
        return true;
    }
}
