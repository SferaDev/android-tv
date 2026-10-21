package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class NowPlayingTest {
    private static final long NOW = 10_000_000L;
    private static final long MINUTE = 60_000L;

    private static NowPlaying.Candidate c(int state, long updateTime, boolean eligible) {
        return new NowPlaying.Candidate(state, updateTime, eligible);
    }

    @Test
    public void playingBeatsMoreRecentPause() {
        int pick = NowPlaying.select(Arrays.asList(
                c(PlaybackState.STATE_PAUSED, NOW - MINUTE, true),
                c(PlaybackState.STATE_PLAYING, NOW - 20 * MINUTE, true)), NOW);
        assertEquals(1, pick);
    }

    @Test
    public void mostRecentPauseWins() {
        int pick = NowPlaying.select(Arrays.asList(
                c(PlaybackState.STATE_PAUSED, NOW - 10 * MINUTE, true),
                c(PlaybackState.STATE_PAUSED, NOW - 2 * MINUTE, true),
                c(PlaybackState.STATE_PAUSED, NOW - 5 * MINUTE, true)), NOW);
        assertEquals(1, pick);
    }

    @Test
    public void pauseOlderThanThirtyMinutesIsDropped() {
        assertEquals(-1, NowPlaying.select(Collections.singletonList(
                c(PlaybackState.STATE_PAUSED, NOW - 31 * MINUTE, true)), NOW));
        assertEquals(0, NowPlaying.select(Collections.singletonList(
                c(PlaybackState.STATE_PAUSED, NOW - 30 * MINUTE, true)), NOW));
    }

    @Test
    public void ineligibleAndIdleSessionsAreSkipped() {
        int pick = NowPlaying.select(Arrays.asList(
                c(PlaybackState.STATE_PLAYING, NOW, false),
                c(PlaybackState.STATE_STOPPED, NOW, true),
                c(PlaybackState.STATE_ERROR, NOW, true),
                c(PlaybackState.STATE_PAUSED, 0, true),
                c(PlaybackState.STATE_PAUSED, NOW - MINUTE, true)), NOW);
        assertEquals(4, pick);
        assertEquals(-1, NowPlaying.select(Collections.<NowPlaying.Candidate>emptyList(), NOW));
    }

    @Test
    public void firstPlayingSessionWins() {
        int pick = NowPlaying.select(Arrays.asList(
                c(PlaybackState.STATE_PLAYING, NOW, true),
                c(PlaybackState.STATE_PLAYING, NOW, true)), NOW);
        assertEquals(0, pick);
    }

    @Test
    public void playingPositionAdvancesWithSpeed() {
        assertEquals(15_000, NowPlaying.positionAt(PlaybackState.STATE_PLAYING, 10_000, 1_000, 1f, 100_000, 6_000));
        assertEquals(20_000, NowPlaying.positionAt(PlaybackState.STATE_PLAYING, 10_000, 1_000, 2f, 100_000, 6_000));
    }

    @Test
    public void pausedPositionStaysPut() {
        assertEquals(10_000, NowPlaying.positionAt(PlaybackState.STATE_PAUSED, 10_000, 1_000, 0f, 100_000, 6_000));
    }

    @Test
    public void positionClampsToDurationAndZero() {
        assertEquals(100_000, NowPlaying.positionAt(PlaybackState.STATE_PLAYING, 99_000, 1_000, 1f, 100_000, 9_000));
        assertEquals(0, NowPlaying.positionAt(PlaybackState.STATE_PLAYING, 0, 5_000, -1f, 100_000, 9_000));
        assertEquals(500_000, NowPlaying.positionAt(PlaybackState.STATE_PLAYING, 100_000, 1_000, 1f, 0, 401_000));
    }

    @Test
    public void controlsFollowSupportedActions() {
        assertTrue(NowPlaying.canPlayPause(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PAUSE));
        assertFalse(NowPlaying.canPlayPause(PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PLAY));
        assertTrue(NowPlaying.canPlayPause(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY));
        assertTrue(NowPlaying.canPlayPause(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY_PAUSE));
        assertFalse(NowPlaying.canPlayPause(PlaybackState.STATE_PAUSED, PlaybackState.ACTION_SEEK_TO));
        assertTrue(NowPlaying.supports(PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT,
                PlaybackState.ACTION_SKIP_TO_NEXT));
        assertFalse(NowPlaying.supports(PlaybackState.ACTION_SEEK_TO, PlaybackState.ACTION_SKIP_TO_NEXT));
    }

    @Test
    public void stopPausesOnlyWithoutAStopOfItsOwn() {
        long pause = PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY;
        assertTrue(NowPlaying.stopPauses(PlaybackState.STATE_PLAYING, pause));
        assertFalse(NowPlaying.stopPauses(PlaybackState.STATE_PLAYING, pause | PlaybackState.ACTION_STOP));
        assertFalse(NowPlaying.stopPauses(PlaybackState.STATE_PAUSED, pause));
    }

    @Test
    public void cardStaysHiddenAfterStopUntilSomethingPlays() {
        assertFalse(NowPlaying.hiddenAfterStop(null, 0, "a", false, 10_000));
        assertTrue(NowPlaying.hiddenAfterStop("a", 10_000, "a", false, 99_000));
        assertTrue(NowPlaying.hiddenAfterStop("a", 10_000, "b", false, 10_500));
        assertTrue(NowPlaying.hiddenAfterStop("a", 10_000, "a", true, 10_500));
        assertFalse(NowPlaying.hiddenAfterStop("a", 10_000, "a", true, 10_000 + NowPlaying.STOP_GRACE_MS));
        assertFalse(NowPlaying.hiddenAfterStop("a", 10_000, "b", true, 10_500));
    }

    @Test
    public void artworkFitKeepsAspectRatio() {
        assertArrayEquals(new int[] {512, 512}, NowPlaying.fit(1024, 1024, 512));
        assertArrayEquals(new int[] {512, 288}, NowPlaying.fit(1920, 1080, 512));
        assertArrayEquals(new int[] {288, 512}, NowPlaying.fit(1080, 1920, 512));
        assertArrayEquals(new int[] {300, 200}, NowPlaying.fit(300, 200, 512));
    }
}
