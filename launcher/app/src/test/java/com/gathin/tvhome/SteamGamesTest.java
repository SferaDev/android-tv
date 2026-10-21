package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SteamGamesTest {
    private static GameStreamClient.App app(int id, String title) {
        return new GameStreamClient.App(id, title, false);
    }

    private static List<String> names(List<SteamGame> games) {
        List<String> names = new ArrayList<>();
        for (SteamGame g : games) names.add(g.name);
        return names;
    }

    private static SteamGames.Launches launches() {
        return new SteamGames.Launches();
    }

    @Test public void ordersAlphabeticallyWithDesktopThenBigPictureLast() {
        List<SteamGame> games = SteamGames.order(Arrays.asList(app(1, "Desktop"), app(2, "Steam Big Picture"),
                app(3, "hades"), app(4, "ELDEN RING"), app(5, "Balatro")), launches());
        assertEquals(Arrays.asList("Balatro", "ELDEN RING", "hades", "Desktop", "Steam Big Picture"), names(games));
        assertEquals(2, games.get(4).appId);
    }

    @Test public void launchedGamesComeFirstMostRecentFirst() {
        SteamGames.Launches launches = launches();
        launches.byId.put(4, 100L);
        launches.byId.put(3, 200L);
        List<SteamGame> games = SteamGames.order(Arrays.asList(app(1, "Desktop"), app(3, "Hades"),
                app(4, "ELDEN RING"), app(5, "Balatro")), launches);
        assertEquals(Arrays.asList("Hades", "ELDEN RING", "Balatro", "Desktop", "Steam Big Picture"), names(games));
    }

    @Test public void aLaunchRecordedByNameStillCountsWhenTheIdChanged() {
        SteamGames.Launches launches = launches();
        launches.byName.put("Hades", 50L);
        List<SteamGame> games = SteamGames.order(Arrays.asList(app(9, "Balatro"), app(77, "Hades")), launches);
        assertEquals("Hades", games.get(0).name);
    }

    @Test public void launchingDesktopOrBigPictureDoesNotMoveThem() {
        SteamGames.Launches launches = launches();
        launches.byName.put("Desktop", 900L);
        launches.byName.put("Steam Big Picture", 999L);
        List<SteamGame> games = SteamGames.order(Arrays.asList(app(1, "Steam Big Picture"), app(2, "Desktop"),
                app(3, "Hades")), launches);
        assertEquals(Arrays.asList("Hades", "Desktop", "Steam Big Picture"), names(games));
    }

    @Test public void addsBigPictureByNameWhenTheHostHasNone() {
        List<SteamGame> games = SteamGames.order(Arrays.asList(app(3, "Hades")), launches());
        assertEquals(Arrays.asList("Hades", "Steam Big Picture"), names(games));
        assertEquals(0, games.get(1).appId);
    }

    @Test public void keepsEveryGameWithTheTrailingItemsLast() {
        List<GameStreamClient.App> apps = new ArrayList<>();
        for (int i = 1; i <= 15; i++) apps.add(app(i, "Game " + (char) ('A' + i)));
        apps.add(app(99, "desktop"));
        SteamGames.Launches launches = launches();
        launches.byId.put(15, 10L);
        List<SteamGame> games = SteamGames.order(apps, launches);
        assertEquals(15 + 2, games.size());
        assertEquals("Game P", games.get(0).name);
        assertEquals("Game B", games.get(1).name);
        assertEquals("desktop", games.get(15).name);
        assertEquals(SteamGames.BIG_PICTURE, games.get(16).name);
    }

    @Test public void homeStripKeepsEveryVisibleGameInLaunchOrder() {
        List<GameStreamClient.App> apps = new ArrayList<>();
        for (int i = 1; i <= 30; i++) apps.add(app(i, String.format("Game %02d", i)));
        apps.add(app(99, "Desktop"));
        SteamGames.Launches launches = launches();
        launches.byId.put(20, 5L);
        launches.byId.put(7, 9L);
        List<SteamGame> strip = HiddenItems.visible(SteamGames.order(apps, launches),
                g -> g.appId == 3, Integer.MAX_VALUE);
        assertEquals(30 + 1, strip.size());
        assertEquals(Arrays.asList("Game 07", "Game 20", "Game 01", "Game 02", "Game 04"), names(strip.subList(0, 5)));
        assertEquals(Arrays.asList("Desktop", SteamGames.BIG_PICTURE), names(strip.subList(29, 31)));
    }

    @Test public void coversSharedByThreeAppsAreTheHostsPlaceholder() {
        java.util.Map<String, String> hashes = new java.util.HashMap<>();
        hashes.put("1.png", "aa");
        hashes.put("2.png", "bb");
        hashes.put("3.png", "aa");
        hashes.put("4.png", "cc");
        hashes.put("5.png", "aa");
        hashes.put("6.png", "bb");
        assertEquals(new java.util.HashSet<>(Arrays.asList("1.png", "3.png", "5.png")),
                SteamGames.placeholders(hashes, java.util.Collections.emptySet()));
    }

    @Test public void coversSharedByTwoAppsStayUnlessTheHashIsKnownStock() {
        java.util.Map<String, String> hashes = new java.util.HashMap<>();
        hashes.put("1.png", "aa");
        hashes.put("2.png", "bb");
        hashes.put("3.png", "aa");
        assertEquals(java.util.Collections.emptySet(), SteamGames.placeholders(hashes, java.util.Collections.emptySet()));
        assertEquals(new java.util.HashSet<>(Arrays.asList("1.png", "3.png")),
                SteamGames.placeholders(hashes, java.util.Collections.singleton("aa")));
    }

    @Test public void pruneDropsAppsTheHostNoLongerHas() {
        SteamGames.Launches launches = launches();
        launches.byId.put(3, 1L);
        launches.byId.put(4, 2L);
        launches.byName.put("Hades", 1L);
        launches.byName.put("Gone", 2L);
        SteamGames.Launches kept = SteamGames.prune(launches, Arrays.asList(app(3, "Hades"), app(5, "Other")));
        assertEquals(1, kept.byId.size());
        assertTrue(kept.byId.containsKey(3));
        assertEquals(1, kept.byName.size());
        assertTrue(kept.byName.containsKey("Hades"));
    }

    @Test public void launchesRoundTripAndTolerateJunkKeys() throws Exception {
        SteamGames.Launches launches = launches();
        launches.byId.put(3, 123456789012L);
        launches.byName.put("Hades", 5L);
        SteamGames.Launches back = SteamGames.parseLaunches(SteamGames.launchesToJson(launches));
        assertEquals(Long.valueOf(123456789012L), back.byId.get(3));
        assertEquals(Long.valueOf(5L), back.byName.get("Hades"));
        assertTrue(SteamGames.parseLaunches("{\"ids\":{\"x\":1}}").byId.isEmpty());
    }

    @Test public void cacheRoundTrips() throws Exception {
        List<GameStreamClient.App> apps = Arrays.asList(app(5, "Hades"),
                new GameStreamClient.App(6, "Desktop", true));
        SteamGames.Cache cache = SteamGames.parseCache(SteamGames.toJson(
                new SteamGames.Cache("00000000-0000-4000-8000-000000000001", "testhost", apps)));
        assertEquals("00000000-0000-4000-8000-000000000001", cache.uuid);
        assertEquals("testhost", cache.name);
        assertEquals(2, cache.apps.size());
        assertEquals("Hades", cache.apps.get(0).title);
        assertTrue(cache.apps.get(1).hdr);
    }

    @Test(expected = org.json.JSONException.class)
    public void malformedCacheThrows() throws Exception {
        SteamGames.parseCache("{\"nope\":1}");
    }
}
