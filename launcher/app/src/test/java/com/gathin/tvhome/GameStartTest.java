package com.gathin.tvhome;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.*;

public class GameStartTest {
    private static GameStreamClient.App app(int id, String title) {
        return new GameStreamClient.App(id, title, false);
    }

    private static final List<GameStreamClient.App> APPS = Arrays.asList(
            app(101, "Desktop"), app(202, "Space Racer"), app(303, "Puzzle Garden"), app(404, "Steam Big Picture"));

    @Test public void resolvesByExactNameEvenWhenTheIdMovedOn() {
        assertEquals(303, GameStart.resolve(APPS, new SteamGame(202, "Puzzle Garden")).id);
    }

    @Test public void fallsBackToTheIdWhenTheNameIsGone() {
        assertEquals("Space Racer", GameStart.resolve(APPS, new SteamGame(202, "Space Racer (old)")).title);
    }

    @Test public void nameMatchIsExact() {
        assertNull(GameStart.resolve(APPS, new SteamGame(999, "puzzle garden")));
    }

    @Test public void goneWhenNeitherNameNorIdMatches() {
        assertNull(GameStart.resolve(APPS, new SteamGame(999, "Old Game")));
        assertNull(GameStart.resolve(APPS, new SteamGame(0, "Old Game")));
    }

    @Test public void quitsOnlyAnotherRunningApp() {
        assertFalse(GameStart.needsQuit(0, 202));
        assertFalse(GameStart.needsQuit(202, 202));
        assertTrue(GameStart.needsQuit(101, 202));
    }

    @Test public void startsAtOnceWhenTheHostIsFree() throws Exception {
        FakeHost host = new FakeHost(0);
        GameStart.Result r = GameStart.run(host, new SteamGame(1, "Space Racer"));
        assertEquals(GameStart.Outcome.START, r.outcome);
        assertEquals(202, r.target.id);
        assertEquals(0, host.quits);
    }

    @Test public void resumesTheTargetWithoutQuitting() throws Exception {
        FakeHost host = new FakeHost(202);
        assertEquals(GameStart.Outcome.START, GameStart.run(host, new SteamGame(202, "Space Racer")).outcome);
        assertEquals(0, host.quits);
    }

    @Test public void quitsAnotherAppThenStartsOnceTheHostIsFree() throws Exception {
        FakeHost host = new FakeHost(101, 101, 101, 0);
        GameStart.Result r = GameStart.run(host, new SteamGame(202, "Space Racer"));
        assertEquals(GameStart.Outcome.START, r.outcome);
        assertEquals(1, host.quits);
        assertEquals(2 * GameStart.POLL_MS, host.clock);
    }

    @Test public void reportsTheAppThatWouldNotClose() throws Exception {
        FakeHost host = new FakeHost(101);
        GameStart.Result r = GameStart.run(host, new SteamGame(202, "Space Racer"));
        assertEquals(GameStart.Outcome.NOT_CLOSED, r.outcome);
        assertEquals("Desktop", r.running);
        assertTrue(host.clock >= GameStart.QUIT_WAIT_MS);
        assertTrue(host.clock < GameStart.QUIT_WAIT_MS + 2 * GameStart.POLL_MS);
    }

    @Test public void refusedQuitStopsWithoutPolling() throws Exception {
        FakeHost host = new FakeHost(101);
        host.quitAnswer = false;
        assertEquals(GameStart.Outcome.NOT_CLOSED, GameStart.run(host, new SteamGame(202, "Space Racer")).outcome);
        assertEquals(0, host.clock);
    }

    @Test public void unknownRunningAppHasNoTitle() throws Exception {
        FakeHost host = new FakeHost(777);
        host.quitAnswer = false;
        assertNull(GameStart.run(host, new SteamGame(202, "Space Racer")).running);
    }

    @Test public void missedPollsDoNotEndTheWait() throws Exception {
        FakeHost host = new FakeHost(101, -1, -1, 0);
        assertEquals(GameStart.Outcome.START, GameStart.run(host, new SteamGame(202, "Space Racer")).outcome);
    }

    @Test public void silentHostIsUnreachable() throws Exception {
        FakeHost host = new FakeHost(-1);
        GameStart.Result r = GameStart.run(host, new SteamGame(202, "Space Racer"));
        assertEquals(GameStart.Outcome.UNREACHABLE, r.outcome);
        assertNull(r.apps);
    }

    @Test public void rejectedAppListIsUnpaired() throws Exception {
        FakeHost host = new FakeHost(0);
        host.appListError = new GameStreamClient.AuthException("HTTP 401");
        assertEquals(GameStart.Outcome.UNPAIRED, GameStart.run(host, new SteamGame(202, "Space Racer")).outcome);
    }

    @Test public void missingGameStopsBeforeQuitting() throws Exception {
        FakeHost host = new FakeHost(101);
        GameStart.Result r = GameStart.run(host, new SteamGame(999, "Old Game"));
        assertEquals(GameStart.Outcome.GONE, r.outcome);
        assertEquals(APPS, r.apps);
        assertEquals(0, host.quits);
    }

    /** Answers serverinfo with the queued currentgame values, repeating the last; -1 is a timeout. */
    private static final class FakeHost implements GameStart.Host {
        final Deque<Integer> games = new ArrayDeque<>();
        boolean quitAnswer = true;
        IOException appListError;
        int quits;
        long clock;

        FakeHost(Integer... games) {
            this.games.addAll(Arrays.asList(games));
        }

        @Override public GameStreamClient.ServerInfo serverInfo() throws IOException {
            int game = games.size() > 1 ? games.poll() : games.peek();
            if (game < 0) throw new IOException("timeout");
            return new GameStreamClient.ServerInfo("u", "testhost", 47984, 7, game);
        }

        @Override public List<GameStreamClient.App> appList() throws IOException {
            if (appListError != null) throw appListError;
            return APPS;
        }

        @Override public boolean quitApp() {
            quits++;
            return quitAnswer;
        }

        @Override public long now() {
            return clock;
        }

        @Override public void sleep(long ms) {
            clock += ms;
        }
    }
}
