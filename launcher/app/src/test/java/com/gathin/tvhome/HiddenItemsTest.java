package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class HiddenItemsTest {
    private static WatchItem item(String pkg, String title) {
        return new WatchItem(pkg, title, null, null, null, 1);
    }

    @Test public void keysForEachKind() {
        assertEquals("app:com.example.a", HiddenItems.app("com.example.a"));
        assertEquals("watch:com.example.a/Show", HiddenItems.watch("com.example.a", "Show"));
        assertEquals("watch:com.example.a/", HiddenItems.watch("com.example.a", null));
        assertEquals("watchapp:com.example.a", HiddenItems.watchApp("com.example.a"));
        assertEquals("game:12", HiddenItems.game(new SteamGame(12, "Hades")));
        assertEquals("game:Steam Big Picture", HiddenItems.game(new SteamGame(0, "Steam Big Picture")));
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

    private static WatchItem entry(String pkg, String id, String title, String subtitle) {
        return new WatchItem(pkg, id, title, subtitle, null, null, 1, -1f);
    }

    @Test public void filtersWatchByEntryByAppRowsAndByApp() {
        WatchItem hiddenEpisode = entry("a.b", "row-1", "Show", "S1E2");
        Set<String> hidden = new HashSet<>(Arrays.asList(HiddenItems.watchEntry(hiddenEpisode),
                HiddenItems.watchApp("c.d"), HiddenItems.app("e.f")));
        List<WatchItem> rows = Arrays.asList(hiddenEpisode, entry("a.b", "row-1", "Show", "S1E3"),
                entry("a.b", "row-2", "Other", null), entry("c.d", null, "X", null), entry("e.f", null, "Y", null),
                entry("g.h", null, "Z", null));
        List<String> titles = new ArrayList<>();
        for (WatchItem w : rows) if (!HiddenItems.watchHidden(hidden, w)) titles.add(w.pkg + "/" + w.title + "/" + w.subtitle);
        assertEquals(Arrays.asList("a.b/Show/S1E3", "a.b/Other/null", "g.h/Z/null"), titles);
    }

    @Test public void entryKeyIgnoresCaseAndSpacingButNotTheEpisodeOrTheId() {
        String key = HiddenItems.watchEntry(entry("a.b", "42", "The Show", "S1E2 Pilot"));
        assertEquals(key, HiddenItems.watchEntry(entry("a.b", "42", " the  show", "s1e2 pilot ")));
        assertNotEquals(key, HiddenItems.watchEntry(entry("a.b", "42", "The Show", "S1E3")));
        assertNotEquals(key, HiddenItems.watchEntry(entry("a.b", "43", "The Show", "S1E2 Pilot")));
        assertNotEquals(key, HiddenItems.watchEntry(entry("x.y", "42", "The Show", "S1E2 Pilot")));
        assertTrue(key.startsWith(HiddenItems.ENTRY));
        assertEquals("The Show \u00B7 S1E2 Pilot", HiddenItems.watchLabel(entry("a.b", "42", "The Show", "S1E2 Pilot")));
        assertEquals("Film", HiddenItems.watchLabel(entry("a.b", null, "Film", null)));
    }

    @Test public void watchAppDoesNotHideTheAppTile() {
        Set<String> hidden = new HashSet<>(Collections.singletonList(HiddenItems.watchApp("a.b")));
        assertTrue(HiddenItems.apps(hidden).isEmpty());
        assertTrue(HiddenItems.watchHidden(hidden, entry("a.b", null, "Show", null)));
    }

    @Test public void reconcileMigratesOldShowKeysToTheCurrentEntries() {
        long now = 100 * Usage.DAY_MS;
        WatchItem ep = entry("a.b", "7", "Show", "S2E1");
        List<HiddenItems.Item> items = Arrays.asList(new HiddenItems.Item("app:x.y", "X"),
                new HiddenItems.Item(HiddenItems.watch("a.b", "Show"), "Show \u00B7 App"),
                new HiddenItems.Item(HiddenItems.watch("a.b", "Gone"), "Gone \u00B7 App"));
        List<HiddenItems.Item> out = HiddenItems.reconcile(items, Arrays.asList(ep, entry("a.b", "8", "Other", null)), now);
        assertEquals(Arrays.asList("app:x.y", HiddenItems.watchEntry(ep)), new ArrayList<>(HiddenItems.keys(out)));
        assertEquals("Show \u00B7 S2E1", out.get(1).label);
        assertEquals(now, out.get(1).seen);
    }

    @Test public void reconcileMarksSeenDailyAndPrunesAfterThirtyDaysGone() {
        long day = Usage.DAY_MS, now = 100 * day;
        WatchItem ep = entry("a.b", "7", "Show", "S2E1");
        String present = HiddenItems.watchEntry(ep);
        String gone = HiddenItems.watchEntry(entry("a.b", "7", "Show", "S1E9"));
        String old = HiddenItems.watchEntry(entry("c.d", null, "Film", null));
        List<HiddenItems.Item> items = Arrays.asList(new HiddenItems.Item(present, "P", now - day / 2),
                new HiddenItems.Item(gone, "G", now - 10 * day), new HiddenItems.Item(old, "O", now - 31 * day),
                new HiddenItems.Item("game:1", "Game"));
        List<HiddenItems.Item> rows = HiddenItems.reconcile(items, Collections.singletonList(ep), now);
        assertEquals(Arrays.asList(present, gone, "game:1"), new ArrayList<>(HiddenItems.keys(rows)));
        assertEquals(now - day / 2, rows.get(0).seen);
        assertEquals(now - 10 * day, rows.get(1).seen);
        assertTrue(HiddenItems.same(rows, HiddenItems.reconcile(rows, Collections.singletonList(ep), now)));
        List<HiddenItems.Item> later = HiddenItems.reconcile(rows, Collections.singletonList(ep), now + day);
        assertEquals(now + day, later.get(0).seen);
        assertFalse(HiddenItems.same(rows, later));
        List<HiddenItems.Item> unknown = HiddenItems.reconcile(
                Collections.singletonList(new HiddenItems.Item(old, "O")), Collections.<WatchItem>emptyList(), now);
        assertEquals(now, unknown.get(0).seen);
    }

    @Test public void hidingAnEntryStampsItAndSeenSurvivesJson() throws Exception {
        List<HiddenItems.Item> items = HiddenItems.add(new ArrayList<>(), "watchnext:a.b//show/", "Show", 5);
        items = HiddenItems.add(items, "app:a.b", "A", 5);
        assertEquals(5, items.get(0).seen);
        assertEquals(0, items.get(1).seen);
        List<HiddenItems.Item> back = HiddenItems.decode(HiddenItems.encode(items));
        assertTrue(HiddenItems.same(items, back));
    }

    @Test public void filtersGamesAndListsApps() {
        Set<String> hidden = new HashSet<>(Arrays.asList("game:2", "app:a.b", "watch:c.d/T"));
        List<SteamGame> games = HiddenItems.visible(Arrays.asList(new SteamGame(1, "One"), new SteamGame(2, "Two")),
                g -> hidden.contains(HiddenItems.game(g)), 10);
        assertEquals(1, games.size());
        assertEquals("One", games.get(0).name);
        assertEquals(new HashSet<>(Arrays.asList("a.b")), HiddenItems.apps(hidden));
    }

    @Test public void visibleKeepsOrderSkipsHiddenAndFillsUpToTheLimit() {
        List<String> items = Arrays.asList("a", "b", "c", "d", "e");
        assertEquals(Arrays.asList("a", "c", "d"), HiddenItems.visible(items, s -> s.equals("b"), 3));
        assertEquals(Arrays.asList("a", "c", "d", "e"), HiddenItems.visible(items, s -> s.equals("b"), 10));
        assertTrue(HiddenItems.visible(items, s -> true, 10).isEmpty());
    }
}
