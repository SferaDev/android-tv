package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

public class ArtworkTest {
    private static final String IMAGES = "[" +
            "{\"id\":1,\"type\":\"poster\",\"main\":true,\"resolutions\":{\"original\":{\"url\":\"https://x/poster.jpg\",\"width\":680,\"height\":1000}}}," +
            "{\"id\":2,\"type\":\"background\",\"main\":false,\"resolutions\":{\"original\":{\"url\":\"https://x/bg-small.jpg\",\"width\":1280,\"height\":720}}}," +
            "{\"id\":3,\"type\":\"background\",\"main\":false,\"resolutions\":{\"original\":{\"url\":\"https://x/bg-large.jpg\",\"width\":3840,\"height\":2160}}}," +
            "{\"id\":4,\"type\":\"background\",\"main\":false,\"resolutions\":{\"original\":{\"url\":\"https://x/bg-nodims.jpg\"}}}," +
            "{\"id\":5,\"type\":\"banner\",\"main\":false,\"resolutions\":{\"original\":{\"url\":\"https://x/banner.jpg\",\"width\":758,\"height\":140}}}]";

    @Test public void normaliseStripsMarksPunctuationAndCase() {
        assertEquals("the witcher 3 wild hunt", ArtworkPick.normalise("The Witcher® 3: Wild Hunt"));
        assertEquals("portal 2", ArtworkPick.normalise("  PORTAL™   2 "));
        assertEquals("", ArtworkPick.normalise(null));
        assertEquals("", ArtworkPick.normalise("™!"));
    }

    @Test public void desktopAndBigPictureAreNotGames() {
        assertFalse(ArtworkPick.isGame("Desktop"));
        assertFalse(ArtworkPick.isGame("Steam Big Picture"));
        assertFalse(ArtworkPick.isGame(" "));
        assertTrue(ArtworkPick.isGame("Portal 2"));
    }

    @Test public void backdropIsTheLargestBackground() {
        assertEquals("https://x/bg-large.jpg", ArtworkPick.backdropUrl(IMAGES));
    }

    @Test public void backdropFallsBackToLandscapeBanner() {
        String json = "[" +
                "{\"type\":\"poster\",\"resolutions\":{\"original\":{\"url\":\"https://x/p.jpg\",\"width\":680,\"height\":1000}}}," +
                "{\"type\":\"banner\",\"resolutions\":{\"original\":{\"url\":\"https://x/b.jpg\",\"width\":758,\"height\":140}}}]";
        assertEquals("https://x/b.jpg", ArtworkPick.backdropUrl(json));
    }

    @Test public void postersAndBrokenJsonGiveNothing() {
        String posters = "[{\"type\":\"poster\",\"resolutions\":{\"original\":{\"url\":\"https://x/p.jpg\",\"width\":680,\"height\":1000}}}]";
        assertNull(ArtworkPick.backdropUrl(posters));
        assertNull(ArtworkPick.backdropUrl("[]"));
        assertNull(ArtworkPick.backdropUrl("not json"));
    }

    private static final String SEARCH = "{\"total\":4,\"items\":[" +
            "{\"type\":\"app\",\"name\":\"Portal 2 Soundtrack\",\"id\":323180}," +
            "{\"type\":\"dlc\",\"name\":\"Portal 2\",\"id\":999}," +
            "{\"type\":\"app\",\"name\":\"Portal™ 2\",\"id\":620}," +
            "{\"type\":\"app\",\"name\":\"Portal 2 Sixense Perceptual Pack\",\"id\":1}]}";

    @Test public void steamPicksTheExactNormalisedAppNotDlcOrSoundtrack() {
        assertEquals(620, ArtworkPick.steamAppId(SEARCH, "portal 2"));
    }

    @Test public void steamFallsBackToShortestNameStartingWithTheQuery() {
        assertEquals(620, ArtworkPick.steamAppId(SEARCH, "Portal"));
    }

    @Test public void steamNoMatchGivesZero() {
        assertEquals(0, ArtworkPick.steamAppId(SEARCH, "Half-Life"));
        assertEquals(0, ArtworkPick.steamAppId("{\"total\":0,\"items\":[]}", "Portal 2"));
        assertEquals(0, ArtworkPick.steamAppId("garbage", "Portal 2"));
    }

    @Test public void hitsLast60DaysAndMissesSeven() {
        long t0 = 1_000_000L;
        long day = 24L * 60 * 60 * 1000;
        ArtworkIndex.Entry hit = new ArtworkIndex.Entry("u", "f.jpg", t0, false);
        ArtworkIndex.Entry miss = new ArtworkIndex.Entry("", null, t0, true);
        assertTrue(hit.fresh(t0 + 59 * day));
        assertFalse(hit.fresh(t0 + 61 * day));
        assertTrue(miss.fresh(t0 + 6 * day));
        assertFalse(miss.fresh(t0 + 8 * day));
    }

    @Test public void pruneDropsExpiredAndReturnsTheirFiles() {
        long day = 24L * 60 * 60 * 1000;
        ArtworkIndex index = new ArtworkIndex();
        index.put("a", new ArtworkIndex.Entry("u", "a.jpg", 0, false));
        index.put("b", new ArtworkIndex.Entry("", null, 0, true));
        index.put("c", new ArtworkIndex.Entry("u", "c.jpg", 70 * day, false));
        assertEquals(Collections.singletonList("a.jpg"), index.prune(70 * day));
        assertNull(index.get("a"));
        assertNull(index.get("b"));
        assertNotNull(index.get("c"));
    }

    @Test public void evictionDropsOldestFirstUntilUnderBudgetAndSparesKeep() {
        List<ArtworkIndex.CacheFile> files = Arrays.asList(
                new ArtworkIndex.CacheFile("new", 10, 300),
                new ArtworkIndex.CacheFile("old", 10, 100),
                new ArtworkIndex.CacheFile("mid", 10, 200));
        assertEquals(Collections.emptyList(), ArtworkIndex.evict(files, 30, null));
        assertEquals(Collections.singletonList("old"), ArtworkIndex.evict(files, 20, null));
        assertEquals(Arrays.asList("old", "mid"), ArtworkIndex.evict(files, 10, null));
        assertEquals(Arrays.asList("mid", "new"), ArtworkIndex.evict(files, 10, "old"));
    }

    @Test public void forgetMissingKeepsMissesAndDropsEvictedHits() {
        ArtworkIndex index = new ArtworkIndex();
        index.put("gone", new ArtworkIndex.Entry("u", "gone.jpg", 1, false));
        index.put("here", new ArtworkIndex.Entry("u", "here.jpg", 1, false));
        index.put("miss", new ArtworkIndex.Entry("", null, 1, true));
        index.forgetMissing(new HashSet<>(Collections.singletonList("here.jpg")));
        assertNull(index.get("gone"));
        assertNotNull(index.get("here"));
        assertNotNull(index.get("miss"));
    }

    @Test public void indexSurvivesAJsonRoundTrip() {
        ArtworkIndex index = new ArtworkIndex();
        index.put("show:breaking bad", new ArtworkIndex.Entry("https://x/a.jpg", "a.jpg", 42, false));
        index.put("game:nothing", new ArtworkIndex.Entry("", null, 7, true));
        ArtworkIndex back = ArtworkIndex.fromJson(index.toJson());
        assertEquals("a.jpg", back.get("show:breaking bad").file);
        assertEquals(42, back.get("show:breaking bad").fetchedAt);
        assertTrue(back.get("game:nothing").miss);
        assertNull(back.get("game:nothing").file);
        assertNull(ArtworkIndex.fromJson("garbage").get("x"));
    }

    private static String details(String data) {
        return "{\"4242\":{\"success\":true,\"data\":" + data + "}}";
    }

    @Test public void appDetailsPrefersBackgroundRawThenScreenshotThenHeader() {
        String json = details("{\"header_image\":\"https://h/header.jpg?t=1\",\"background\":\"https://h/bg\"," +
                "\"background_raw\":\"https://h/page_bg_raw.jpg?t=1\"," +
                "\"screenshots\":[{\"id\":0,\"path_full\":\"https://h/ss1.jpg\"},{\"id\":1,\"path_full\":\"https://h/ss2.jpg\"}]}");
        assertEquals(Arrays.asList("https://h/page_bg_raw.jpg?t=1", "https://h/ss1.jpg", "https://h/header.jpg?t=1"),
                ArtworkPick.appDetailsUrls(json, 4242));
    }

    @Test public void appDetailsWithOnlyAHeaderUsesIt() {
        assertEquals(Collections.singletonList("https://h/header.jpg"),
                ArtworkPick.appDetailsUrls(details("{\"header_image\":\"https://h/header.jpg\"}"), 4242));
    }

    @Test public void appDetailsUnknownAppOrBadJsonIsEmpty() {
        assertTrue(ArtworkPick.appDetailsUrls("{\"4242\":{\"success\":false}}", 4242).isEmpty());
        assertTrue(ArtworkPick.appDetailsUrls(details("{}"), 99).isEmpty());
        assertTrue(ArtworkPick.appDetailsUrls("garbage", 4242).isEmpty());
    }
}
