package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class HomeSnapshotTest {
    private static HomeSnapshot sample() {
        List<WatchItem> watching = Arrays.asList(
                new WatchItem("com.example.video", "Show", "S1 E2", "https://example.test/a.jpg", "example://watch/1", 1700000000000L, 0.4f),
                new WatchItem("com.example.music", "Song", null, null, null, 5L));
        List<SteamGame> games = Arrays.asList(new SteamGame(7, "Some Game"), new SteamGame(0, "Big Picture"));
        List<TvCards.Tile> tv = Arrays.asList(
                new TvCards.Tile(new TvInputs.Input("input/1", "HDMI 1", 1007), "Last used", true),
                new TvCards.Tile(new TvChannels.Channel(42L, "input/tuner", "7", "News"), "Now: X"));
        return new HomeSnapshot(Arrays.asList("com.example.a", "com.example.b"), watching, games, tv);
    }

    @Test public void roundTripKeepsEveryField() throws Exception {
        HomeSnapshot back = HomeSnapshot.decode(sample().encode());
        assertEquals(Arrays.asList("com.example.a", "com.example.b"), back.recent);
        WatchItem w = back.watching.get(0);
        assertEquals("Show", w.title);
        assertEquals("S1 E2", w.subtitle);
        assertEquals("https://example.test/a.jpg", w.artwork);
        assertEquals("example://watch/1", w.deepLink);
        assertEquals(1700000000000L, w.lastWatched);
        assertEquals(0.4f, w.progress, 0.001f);
        assertNull(back.watching.get(1).subtitle);
        assertEquals(-1f, back.watching.get(1).progress, 0f);
        assertEquals(7, back.games.get(0).appId);
        assertEquals("Big Picture", back.games.get(1).name);
        TvCards.Tile input = back.tv.get(0);
        assertEquals("input/1", input.input.id);
        assertTrue(input.current);
        TvCards.Tile channel = back.tv.get(1);
        assertEquals(42L, channel.channel.id);
        assertEquals("News", channel.channel.name);
        assertEquals("Now: X", channel.subtitle);
    }

    @Test public void encodingIsStableForUnchangedData() throws Exception {
        assertEquals(sample().encode(), HomeSnapshot.decode(sample().encode()).encode());
    }

    @Test public void anotherVersionIsRejected() {
        try {
            HomeSnapshot.decode("{\"version\":2,\"recent\":[\"a\"]}");
            fail();
        } catch (org.json.JSONException expected) { }
    }

    @Test public void brokenEntriesAreSkippedOneByOne() throws Exception {
        HomeSnapshot s = HomeSnapshot.decode("{\"version\":1,\"recent\":[\"\",\"a\"],\"watching\":[{\"title\":\"x\"},"
                + "{\"pkg\":\"p\",\"title\":\"t\"}],\"games\":[{\"id\":1}],\"tv\":[{},{\"input\":{\"id\":\"i\"}}]}");
        assertEquals(Collections.singletonList("a"), s.recent);
        assertEquals(1, s.watching.size());
        assertTrue(s.games.isEmpty());
        assertEquals(1, s.tv.size());
    }

    @Test public void listsAreCapped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < HomeSnapshot.MAX_ITEMS + 10; i++) many.add("p" + i);
        HomeSnapshot s = new HomeSnapshot(many, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertEquals(HomeSnapshot.MAX_ITEMS, s.recent.size());
        assertFalse(s.isEmpty());
        assertTrue(new HomeSnapshot(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList()).isEmpty());
    }
}
