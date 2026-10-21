package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class UserSettingsTest {
    @Test public void emptyFileBecomesTheCurrentShape() throws Exception {
        JSONObject root = UserSettings.decode("");
        assertEquals(UserSettings.VERSION, root.getInt("version"));
        assertEquals(0, root.getJSONObject("config").length());
        assertEquals(0, root.getJSONArray("hidden").length());
    }

    @Test public void roundTripKeepsConfigAndHidden() throws Exception {
        JSONObject root = UserSettings.decode("");
        root.getJSONObject("config").put("gamestream_host", "192.0.2.10")
                .put("app_keys", UserSettings.json(Arrays.asList("190:com.example.tube")))
                .put("settings_key_keycodes", UserSettings.json(new int[]{176}));
        root.getJSONArray("hidden").put(new JSONObject().put("key", "app:com.example.a").put("label", "A"));
        JSONObject back = UserSettings.decode(UserSettings.encode(root));
        assertEquals("192.0.2.10", back.getJSONObject("config").getString("gamestream_host"));
        assertEquals("190:com.example.tube", back.getJSONObject("config").getJSONArray("app_keys").getString(0));
        assertEquals(176, back.getJSONObject("config").getJSONArray("settings_key_keycodes").getInt(0));
        assertEquals("app:com.example.a", back.getJSONArray("hidden").getJSONObject(0).getString("key"));
    }

    @Test public void versionZeroKeptConfigKeysAtTheTop() throws Exception {
        JSONObject root = UserSettings.decode("{\"background_color\":\"#101010\",\"hidden\":[{\"key\":\"game:4\"}]}");
        assertEquals(1, root.getInt("version"));
        assertEquals("#101010", root.getJSONObject("config").getString("background_color"));
        assertFalse(root.getJSONObject("config").has("hidden"));
        assertEquals("game:4", root.getJSONArray("hidden").getJSONObject(0).getString("key"));
    }

    @Test public void currentVersionIsLeftAsItIs() throws Exception {
        JSONObject root = UserSettings.decode("{\"version\":1,\"config\":{\"wallpaper\":false},\"hidden\":[]}");
        assertFalse(root.getJSONObject("config").getBoolean("wallpaper"));
    }

    @Test public void byteOrderMarkIsIgnored() throws Exception {
        assertEquals(1, UserSettings.decode("\uFEFF{\"version\":1}").getInt("version"));
    }

    @Test public void tvChoicesGoOverTheTheme() throws Exception {
        JSONObject theme = new JSONObject().put("background_color", "#222222").put("columns", 6);
        JSONObject user = new JSONObject().put("background_color", "#000000")
                .put("home_rows", new JSONArray());
        JSONObject merged = Config.overlay(theme, user);
        assertEquals("#000000", merged.getString("background_color"));
        assertEquals(6, merged.getInt("columns"));
        assertEquals(0, merged.getJSONArray("home_rows").length());
        assertEquals("#222222", theme.getString("background_color"));
    }

    @Test public void overlayWithoutThemeOrChoices() throws Exception {
        assertNull(Config.overlay(null, null));
        assertNull(Config.overlay(null, new JSONObject()));
        assertEquals(1, Config.overlay(null, new JSONObject().put("wallpaper", false)).length());
        JSONObject theme = new JSONObject().put("columns", 5);
        assertEquals(5, Config.overlay(theme, new JSONObject()).getInt("columns"));
    }
}
