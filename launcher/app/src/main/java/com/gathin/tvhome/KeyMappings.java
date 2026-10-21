package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The remote buttons Settings edits, as one list: app_keys, settings_key_keycodes and input_key_keycodes read
 * together and written back as those three keys. One action per keycode.
 */
final class KeyMappings {
    enum Action { APP, TOGGLE_HOME, INPUTS, SETTINGS }

    static final class Mapping {
        final int keyCode;
        final Action action;
        /** The app to open or the stock launcher to toggle; null for INPUTS and SETTINGS. */
        final String pkg;

        Mapping(int keyCode, Action action, String pkg) {
            this.keyCode = keyCode;
            this.action = action;
            this.pkg = pkg;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Mapping)) return false;
            Mapping m = (Mapping) o;
            return m.keyCode == keyCode && m.action == action && (pkg == null ? m.pkg == null : pkg.equals(m.pkg));
        }

        @Override public int hashCode() {
            return keyCode * 31 + action.hashCode();
        }

        @Override public String toString() {
            return keyCode + ":" + action + (pkg == null ? "" : ":" + pkg);
        }
    }

    // Keys the TV needs for itself: arrows, OK, Back, Home, volume and power.
    private static final int[] RESERVED = {0, 3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 160, 164};

    private KeyMappings() { }

    /** Whether a captured key may be given an action. */
    static boolean capturable(int keyCode) {
        if (keyCode <= 0) return false;
        for (int code : RESERVED) if (code == keyCode) return false;
        return true;
    }

    /** Settings buttons first (HomeKeyService checks them first), then input buttons, then apps, by keycode. */
    static List<Mapping> from(Iterable<String> appKeys, int[] settingsKeycodes, int[] inputKeycodes,
                              ToIntFunction<String> names) {
        List<Mapping> out = new ArrayList<>();
        for (int code : settingsKeycodes) add(out, new Mapping(code, Action.SETTINGS, null));
        for (int code : inputKeycodes) add(out, new Mapping(code, Action.INPUTS, null));
        Map<Integer, String> apps = RemoteKeys.parseAppKeys(appKeys, names);
        Integer[] codes = apps.keySet().toArray(new Integer[0]);
        Arrays.sort(codes);
        for (int code : codes) {
            String target = apps.get(code);
            String toggled = RemoteKeys.toggleHomePackage(target);
            add(out, toggled != null ? new Mapping(code, Action.TOGGLE_HOME, toggled)
                    : new Mapping(code, Action.APP, target));
        }
        return out;
    }

    private static void add(List<Mapping> list, Mapping m) {
        for (Mapping existing : list) if (existing.keyCode == m.keyCode) return;
        list.add(m);
    }

    /** Gives the key this action, replacing whatever it did before; a new key goes last. */
    static List<Mapping> put(List<Mapping> mappings, Mapping mapping) {
        List<Mapping> out = new ArrayList<>();
        boolean replaced = false;
        for (Mapping m : mappings) {
            if (m.keyCode != mapping.keyCode) {
                out.add(m);
            } else if (!replaced) {
                out.add(mapping);
                replaced = true;
            }
        }
        if (!replaced) out.add(mapping);
        return out;
    }

    static List<Mapping> remove(List<Mapping> mappings, int keyCode) {
        List<Mapping> out = new ArrayList<>();
        for (Mapping m : mappings) if (m.keyCode != keyCode) out.add(m);
        return out;
    }

    static Mapping find(List<Mapping> mappings, int keyCode) {
        for (Mapping m : mappings) if (m.keyCode == keyCode) return m;
        return null;
    }

    static List<String> appKeys(List<Mapping> mappings) {
        List<String> out = new ArrayList<>();
        for (Mapping m : mappings) {
            if (m.action == Action.APP) out.add(m.keyCode + ":" + m.pkg);
            else if (m.action == Action.TOGGLE_HOME) out.add(m.keyCode + ":togglehome:" + m.pkg);
        }
        return out;
    }

    static int[] settingsKeycodes(List<Mapping> mappings) {
        return codes(mappings, Action.SETTINGS);
    }

    static int[] inputKeycodes(List<Mapping> mappings) {
        return codes(mappings, Action.INPUTS);
    }

    private static int[] codes(List<Mapping> mappings, Action action) {
        List<Integer> codes = new ArrayList<>();
        for (Mapping m : mappings) if (m.action == action) codes.add(m.keyCode);
        int[] out = new int[codes.size()];
        for (int i = 0; i < out.length; i++) out[i] = codes.get(i);
        return out;
    }

    /** "BUTTON 3 (190)" from Android's KEYCODE_BUTTON_3; the bare number when Android has no name for it. */
    static String label(String keyCodeName, int keyCode) {
        if (keyCodeName == null || !keyCodeName.startsWith("KEYCODE_")) return String.valueOf(keyCode);
        return keyCodeName.substring("KEYCODE_".length()).replace('_', ' ') + " (" + keyCode + ")";
    }
}
