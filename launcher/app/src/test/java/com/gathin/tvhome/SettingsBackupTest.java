package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class SettingsBackupTest {
    private static JSONObject root() throws Exception {
        JSONObject root = UserSettings.decode("");
        root.getJSONObject("config").put("gamestream_host", "192.0.2.10").put("columns", 6)
                .put(WatchHistory.MAX_AGE_KEY, 14)
                .put("app_keys", UserSettings.json(Arrays.asList("190:com.example.tube")))
                .put("settings_key_keycodes", UserSettings.json(new int[]{176}))
                .put("adb_private_key", "SECRET").put("client_cert", "SECRET");
        root.getJSONArray("hidden").put(new JSONObject().put("key", "app:com.example.a").put("label", "A"));
        return root;
    }

    @Test public void exportKeepsKnownKeysAndNothingElse() throws Exception {
        String text = SettingsBackup.export(root());
        assertFalse(text.contains("SECRET"));
        assertFalse(text.contains("adb_private_key"));
        JSONObject out = new JSONObject(text);
        assertEquals(SettingsBackup.KIND, out.getString("kind"));
        assertEquals(6, out.getJSONObject("config").getInt("columns"));
        assertEquals(14, out.getJSONObject("config").getInt(WatchHistory.MAX_AGE_KEY));
        assertEquals("190:com.example.tube", out.getJSONObject("config").getJSONArray("app_keys").getString(0));
        assertEquals("app:com.example.a", out.getJSONArray("hidden").getJSONObject(0).getString("key"));
    }

    @Test public void exportThenParseRoundTrips() throws Exception {
        SettingsBackup.Parsed parsed = SettingsBackup.parse(SettingsBackup.export(root()));
        assertTrue(parsed.skipped.isEmpty());
        assertEquals("192.0.2.10", parsed.config.getString("gamestream_host"));
        assertEquals(176, parsed.config.getJSONArray("settings_key_keycodes").getInt(0));
        assertEquals(1, parsed.hidden.size());
    }

    @Test public void parseRejectsOtherFilesAndNewerVersions() {
        for (String bad : new String[]{"nope", "{}", "{\"kind\":\"other\",\"version\":1}",
                "{\"kind\":\"tvhome-settings\"}"}) {
            try {
                SettingsBackup.parse(bad);
                fail(bad);
            } catch (SettingsBackup.Invalid e) {
                assertFalse(e.newer);
            }
        }
        try {
            SettingsBackup.parse("{\"kind\":\"tvhome-settings\",\"version\":2}");
            fail();
        } catch (SettingsBackup.Invalid e) {
            assertTrue(e.newer);
        }
    }

    @Test public void parseSkipsUnknownAndBadlyTypedKeys() throws Exception {
        SettingsBackup.Parsed parsed = SettingsBackup.parse("{\"kind\":\"tvhome-settings\",\"version\":1,\"config\":"
                + "{\"columns\":\"six\",\"adb_private_key\":\"x\",\"settings_key_keycodes\":[1,\"a\"],\"animations\":false}}");
        assertEquals(1, parsed.config.length());
        assertFalse(parsed.config.getBoolean("animations"));
        assertEquals(3, parsed.skipped.size());
    }

    @Test public void previewListsOnlyWhatDiffers() throws Exception {
        SettingsBackup.Parsed parsed = SettingsBackup.parse("{\"kind\":\"tvhome-settings\",\"version\":1,\"config\":"
                + "{\"columns\":6,\"animations\":false,\"app_keys\":[\"190:com.example.tube\"]},\"hidden\":["
                + "{\"key\":\"app:com.example.a\"},{\"key\":\"game:4\",\"label\":\"G\"}]}");
        SettingsBackup.Preview preview = SettingsBackup.preview(root(), parsed);
        assertEquals(Arrays.asList("animations"), preview.changedKeys);
        assertEquals(1, preview.newHidden);
        assertFalse(preview.isEmpty());
        JSONObject same = root();
        SettingsBackup.apply(same, parsed);
        assertTrue(SettingsBackup.preview(same, parsed).isEmpty());
    }

    @Test public void applyMergesIntoWhatTheTvHas() throws Exception {
        SettingsBackup.Parsed parsed = SettingsBackup.parse("{\"kind\":\"tvhome-settings\",\"version\":1,\"config\":"
                + "{\"columns\":4},\"hidden\":[{\"key\":\"game:4\",\"label\":\"G\"}]}");
        JSONObject target = root();
        SettingsBackup.apply(target, parsed);
        JSONObject config = target.getJSONObject("config");
        assertEquals(4, config.getInt("columns"));
        assertEquals("192.0.2.10", config.getString("gamestream_host"));
        JSONArray hidden = target.getJSONArray("hidden");
        assertEquals(2, hidden.length());
        assertEquals("game:4", hidden.getJSONObject(1).getString("key"));
    }
}
