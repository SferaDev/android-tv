package com.gathin.tvhome;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class WatchHistoryTest {
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR, NOW = 100 * DAY;

    private static WatchItem next(String pkg, String title, long ago) {
        return new WatchItem(pkg, title, "S1E2", null, "intent:#Intent;end", NOW - ago, 0.5f);
    }

    private static List<WatchItem> merge(List<WatchItem> next, int limit) {
        return WatchHistory.merge(next, limit);
    }

    @Test public void leavesOutRowsPastTheAgeLimitAndRowsWithoutATime() {
        WatchItem fresh = next("a.stream", "New", 29 * DAY), old = next("a.stream", "Old", 31 * DAY);
        WatchItem untimed = new WatchItem("a.stream", "Untimed", "S1E1", null, null, 0);
        List<WatchItem> rows = Arrays.asList(fresh, old, untimed);
        assertEquals(Collections.singletonList(fresh), WatchHistory.recent(rows, NOW, 30));
        assertEquals(Arrays.asList(fresh, old), WatchHistory.recent(rows, NOW, 60));
        assertEquals(rows, WatchHistory.recent(rows, NOW, 0));
    }

    @Test public void newestFirstAndCappedPerApp() {
        List<WatchItem> items = merge(Arrays.asList(next("a.stream", "1", 5 * HOUR), next("a.stream", "2", 4 * HOUR),
                next("a.stream", "3", 3 * HOUR), next("a.stream", "4", 2 * HOUR), next("b.stream", "x", 6 * HOUR)), 10);
        assertEquals(Arrays.asList("4", "3", "2", "x"), titles(items));
        assertEquals(Arrays.asList("4", "3"), titles(merge(Arrays.asList(next("a.stream", "3", 3 * HOUR),
                next("a.stream", "4", 2 * HOUR), next("b.stream", "x", 6 * HOUR)), 2)));
    }

    @Test public void hiddenShowsTakeNoSlotOfTheirApp() {
        List<WatchItem> rows = Arrays.asList(next("a.stream", "1", 5 * HOUR), next("a.stream", "2", 4 * HOUR),
                next("a.stream", "3", 3 * HOUR), next("a.stream", "4", 2 * HOUR), next("b.stream", "x", 6 * HOUR));
        Set<String> hidden = new HashSet<>(Arrays.asList(HiddenItems.watchEntry(rows.get(3)),
                HiddenItems.watchEntry(rows.get(2)), HiddenItems.watchEntry(rows.get(1))));
        assertEquals(Arrays.asList("1", "x"), titles(WatchHistory.merge(rows, w -> HiddenItems.watchHidden(hidden, w), 10)));
    }

    @Test public void aHiddenNewestEpisodeKeepsTheOlderOneHidden() {
        WatchItem older = new WatchItem("a.stream", "Show", "S1E1", null, null, NOW - 2 * HOUR, -1f);
        WatchItem newer = new WatchItem("a.stream", "Show", "S1E2", null, null, NOW - HOUR, -1f);
        Set<String> hidden = Collections.singleton(HiddenItems.watchEntry(newer));
        assertTrue(WatchHistory.merge(Arrays.asList(older, newer), w -> HiddenItems.watchHidden(hidden, w), 10).isEmpty());
    }

    @Test public void watchNextDuplicatesCollapseIgnoringCaseAndSpaces() {
        assertEquals(1, merge(Arrays.asList(next("a.stream", "Show", HOUR), next("a.stream", " show ", 2 * HOUR)), 10).size());
    }

    @Test public void aShowKeepsItsNewestEpisode() {
        WatchItem older = new WatchItem("a.stream", "Show", "S1E1", null, null, NOW - 2 * HOUR, -1f);
        WatchItem newer = new WatchItem("a.stream", "Show", "S1E2", null, null, NOW - HOUR, -1f);
        assertEquals("S1E2", merge(Arrays.asList(older, newer), 10).get(0).subtitle);
    }

    @Test public void watchNextIdPrefersContentIdThenProviderId() {
        assertEquals("c", WatchNext.id(" c ", "p"));
        assertEquals("p", WatchNext.id(" ", "p"));
        assertNull(WatchNext.id(null, ""));
        assertEquals("c", WatchNext.item("a.stream", "T", null, null, null, null, null, null, 0, 0, 0, "c").id);
    }

    @Test public void sameTitleInDifferentAppsStaysSeparate() {
        assertEquals(2, merge(Arrays.asList(next("a.stream", "Show", HOUR), next("b.stream", "Show", 2 * HOUR)), 10).size());
    }

    @Test public void watchNextSubtitleAndProgress() {
        assertEquals("S1E2 Pilot", WatchNext.subtitle("Pilot", "1", "2"));
        assertEquals("E7", WatchNext.subtitle(" ", null, "7"));
        assertEquals("Pilot", WatchNext.subtitle("Pilot", null, null));
        assertNull(WatchNext.subtitle(null, "", " "));
        assertEquals(0.25f, WatchNext.progress(25, 100), 0f);
        assertEquals(1f, WatchNext.progress(150, 100), 0f);
        assertEquals(-1f, WatchNext.progress(0, 100), 0f);
        assertEquals(-1f, WatchNext.progress(10, 0), 0f);
    }

    @Test public void watchNextRowNeedsAPackageAndATitle() {
        assertNull(WatchNext.item("a.stream", " ", null, null, null, null, null, null, 0, 0, 0));
        assertNull(WatchNext.item(null, "T", null, null, null, null, null, null, 0, 0, 0));
        WatchItem item = WatchNext.item("a.stream", "T", null, null, null, null, "https://a/t.jpg", "", 5, 10, 20);
        assertEquals("https://a/t.jpg", item.artwork);
        assertNull(item.deepLink);
        assertEquals(5, item.lastWatched);
        assertEquals(0.5f, item.progress, 0f);
    }

    private static List<String> titles(List<WatchItem> items) {
        List<String> titles = new ArrayList<>();
        for (WatchItem i : items) titles.add(i.title);
        return titles;
    }
}
