package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class HiddenItemsTest {
    @Test public void keysForEachKind() {
        assertEquals("app:com.example.a", HiddenItems.app("com.example.a"));
    }

    @Test public void addingAgainMovesToTheEndOnce() {
        List<HiddenItems.Item> items = HiddenItems.add(new ArrayList<>(), "app:a.b", "A");
        items = HiddenItems.add(items, "game:1", "Game");
        items = HiddenItems.add(items, "app:a.b", "A again");
        assertEquals(Arrays.asList("game:1", "app:a.b"), new ArrayList<>(HiddenItems.keys(items)));
        assertEquals("A again", items.get(1).label);
        assertEquals(Arrays.asList("game:1"), new ArrayList<>(HiddenItems.keys(HiddenItems.remove(items, "app:a.b"))));
    }

    @Test public void blankLabelFallsBackToTheKey() {
        assertEquals("game:3", HiddenItems.add(new ArrayList<>(), "game:3", " ").get(0).label);
    }

    @Test public void jsonRoundTripSkipsBadEntries() throws Exception {
        JSONArray a = HiddenItems.encode(HiddenItems.add(new ArrayList<>(), "app:a.b", "A"));
        a.put("not an object").put(new JSONObject().put("label", "no key")).put(new JSONObject().put("key", "app:a.b"));
        List<HiddenItems.Item> back = HiddenItems.decode(a);
        assertEquals(1, back.size());
        assertEquals("A", back.get(0).label);
        assertTrue(HiddenItems.decode(null).isEmpty());
    }

    @Test public void listsApps() {
        Set<String> hidden = new HashSet<>(Arrays.asList("game:2", "app:a.b", "watch:c.d/T"));
        assertEquals(new HashSet<>(Arrays.asList("a.b")), HiddenItems.apps(hidden));
    }

    @Test public void visibleKeepsOrderSkipsHiddenAndFillsUpToTheLimit() {
        List<String> items = Arrays.asList("a", "b", "c", "d", "e");
        assertEquals(Arrays.asList("a", "c", "d"), HiddenItems.visible(items, s -> s.equals("b"), 3));
        assertEquals(Arrays.asList("a", "c", "d", "e"), HiddenItems.visible(items, s -> s.equals("b"), 10));
        assertTrue(HiddenItems.visible(items, s -> true, 10).isEmpty());
    }
}
