package com.gathin.tvhome;

import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Plain JVM tests for the TV tab data: channel filtering and ordering, now/next and the recent list. */
public class TvDataTest {
    private static final long MIN = 60_000L, DAY = 24 * 60 * MIN, NOW = 100 * DAY;
    private static final List<String> TUNERS = Arrays.asList("fake.pkg/.Tuner/HW0");

    private static TvChannels.Channel ch(long id, String number, String name) {
        return new TvChannels.Channel(id, "fake.pkg/.Tuner/HW0", number, name);
    }

    private static TvChannels.Programme prog(String title, long start, long end) {
        return new TvChannels.Programme(title, start, end);
    }

    private static List<String> numbers(List<TvChannels.Channel> channels) {
        List<String> out = new ArrayList<>();
        for (TvChannels.Channel c : channels) out.add(c.number);
        return out;
    }

    @Test public void keepsBroadcastChannelsOnly() {
        String in = TUNERS.get(0);
        assertTrue(TvChannels.keep(TvContract.Channels.TYPE_DVB_T, true, in, TUNERS));
        assertFalse(TvChannels.keep(TvContract.Channels.TYPE_PREVIEW, true, in, TUNERS));
        assertFalse(TvChannels.keep(TvContract.Channels.TYPE_OTHER, true, in, TUNERS));
        assertFalse(TvChannels.keep(TvContract.Channels.TYPE_DVB_T, false, in, TUNERS));
        assertFalse(TvChannels.keep(null, true, in, TUNERS));
        assertFalse(TvChannels.keep(TvContract.Channels.TYPE_DVB_T, true, "other.pkg/.Svc", TUNERS));
    }

    @Test public void anyInputPassesWhenTunersAreUnknown() {
        assertTrue(TvChannels.keep(TvContract.Channels.TYPE_DVB_T, true, "x/.Y", Collections.emptyList()));
    }

    @Test public void sortsDisplayNumbersNumerically() {
        List<TvChannels.Channel> sorted = TvChannels.sort(Arrays.asList(
                ch(1, "10.1", "d"), ch(2, "10", "c"), ch(3, "2", "b"), ch(4, "1", "a"), ch(5, "", "z"),
                ch(6, "10.2", "e"), ch(7, "9-1", "f")));
        assertEquals(Arrays.asList("1", "2", "9-1", "10", "10.1", "10.2", ""), numbers(sorted));
    }

    @Test public void equalNumbersFallBackToName() {
        List<TvChannels.Channel> sorted = TvChannels.sort(Arrays.asList(ch(1, "5", "Beta"), ch(2, "5", "alpha")));
        assertEquals("alpha", sorted.get(0).name);
    }

    @Test public void selectsNowAndNext() {
        List<TvChannels.Programme> list = Arrays.asList(
                prog("Later", NOW + 90 * MIN, NOW + 120 * MIN),
                prog("Before", NOW - 90 * MIN, NOW - 30 * MIN),
                prog("Airing", NOW - 30 * MIN, NOW + 30 * MIN),
                prog("Soon", NOW + 30 * MIN, NOW + 90 * MIN));
        TvChannels.NowNext nn = TvChannels.select(list, NOW);
        assertEquals("Airing", nn.now.title);
        assertEquals("Soon", nn.next.title);
    }

    @Test public void nextWithoutCurrentProgramme() {
        TvChannels.NowNext nn = TvChannels.select(Arrays.asList(
                prog("Before", NOW - 90 * MIN, NOW - 30 * MIN), prog("Soon", NOW + 10 * MIN, NOW + 40 * MIN)), NOW);
        assertNull(nn.now);
        assertEquals("Soon", nn.next.title);
    }

    @Test public void emptyGuideSelectsNothing() {
        TvChannels.NowNext nn = TvChannels.select(Collections.emptyList(), NOW);
        assertNull(nn.now);
        assertNull(nn.next);
    }

    @Test public void inputLabelPrefersTheUsersRename() {
        assertEquals("Console", TvInputs.pickLabel("Console", "HDMI 2", "id", true));
        assertEquals("HDMI 2", TvInputs.pickLabel(" ", "HDMI 2", "id", true));
        assertEquals("HDMI 3", TvInputs.pickLabel(null, "3", "id", true));
        assertEquals("AV", TvInputs.pickLabel(null, "AV", "id", false));
        assertEquals("id", TvInputs.pickLabel(null, null, "id", false));
    }

    @Test public void hdmiInputsOrderFirst() {
        List<TvInputs.Input> sorted = TvInputs.order(Arrays.asList(
                new TvInputs.Input("a", "AV", TvInputInfo.TYPE_COMPOSITE),
                new TvInputs.Input("b", "HDMI 2", TvInputInfo.TYPE_HDMI),
                new TvInputs.Input("c", "HDMI 1", TvInputInfo.TYPE_HDMI)));
        assertEquals("c", sorted.get(0).id);
        assertEquals("b", sorted.get(1).id);
        assertEquals("a", sorted.get(2).id);
    }

    private static RecentTv.Entry entry(String kind, String id, long ago) {
        return new RecentTv.Entry(kind, id, NOW - ago);
    }

    @Test public void recentMergeMovesRepeatToFrontAndDropsStale() {
        List<RecentTv.Entry> merged = RecentTv.merge(Arrays.asList(
                entry(RecentTv.INPUT, "i1", 10 * MIN), entry(RecentTv.CHANNEL, "7", 20 * MIN),
                entry(RecentTv.INPUT, "old", 61 * DAY)), entry(RecentTv.CHANNEL, "7", 0), NOW);
        assertEquals(2, merged.size());
        assertEquals("tv:channel:7", merged.get(0).key());
        assertEquals("tv:input:i1", merged.get(1).key());
    }

    @Test public void recentMergeCapsTheList() {
        List<RecentTv.Entry> entries = new ArrayList<>();
        for (int i = 0; i < RecentTv.MAX_ENTRIES + 5; i++) entries.add(entry(RecentTv.CHANNEL, "c" + i, i * MIN));
        assertEquals(RecentTv.MAX_ENTRIES, RecentTv.merge(entries, entry(RecentTv.INPUT, "i", 0), NOW).size());
    }

    @Test public void recentPruneDropsMissingTargets() {
        List<RecentTv.Entry> entries = Arrays.asList(entry(RecentTv.INPUT, "gone", 1), entry(RecentTv.INPUT, "i1", 2),
                entry(RecentTv.CHANNEL, "9", 3), entry(RecentTv.CHANNEL, "7", 4));
        Set<String> inputs = new HashSet<>(Collections.singletonList("i1"));
        Set<String> channels = new HashSet<>(Collections.singletonList("7"));
        List<RecentTv.Entry> kept = RecentTv.prune(entries, inputs, channels);
        assertEquals(Arrays.asList("tv:input:i1", "tv:channel:7"), Arrays.asList(kept.get(0).key(), kept.get(1).key()));
        assertEquals(2, kept.size());
    }

    @Test public void recentPruneKeepsChannelsItCannotCheck() {
        List<RecentTv.Entry> kept = RecentTv.prune(Arrays.asList(entry(RecentTv.CHANNEL, "9", 1)),
                Collections.emptySet(), null);
        assertEquals(1, kept.size());
    }

    @Test public void recentRoundTrips() throws Exception {
        List<RecentTv.Entry> back = RecentTv.decode(RecentTv.encode(Arrays.asList(
                entry(RecentTv.INPUT, "i1", 5), entry(RecentTv.CHANNEL, "7", 9))));
        assertEquals(2, back.size());
        assertEquals("tv:input:i1", back.get(0).key());
        assertEquals(NOW - 9, back.get(1).timestamp);
    }

    @Test public void inputSourcesMapsByTheIdsLastSegment() {
        String[] map = {"HW5=23", "HW8=-", "HW9=x"};
        assertEquals(Integer.valueOf(23), TvInputs.mappedSource(map, "com.example.tv/.Hdmi/HW5"));
        assertEquals(Integer.valueOf(-1), TvInputs.mappedSource(map, "com.example.tv/.Hdmi/HW8"));
        assertNull(TvInputs.mappedSource(map, "com.example.tv/.Hdmi/HW9"));
        assertNull(TvInputs.mappedSource(map, "com.example.tv/.Hdmi/HW6"));
        assertNull(TvInputs.mappedSource(map, null));
    }
}
