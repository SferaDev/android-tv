package com.gathin.tvhome;

import java.io.IOException;
import java.util.List;

/**
 * The checks before a game starts in Moonlight: the host answers, the app is still there under a fresh id, and whatever
 * else the host runs is quit first, since Moonlight's shortcut would resume that instead. No Android classes.
 */
final class GameStart {
    static final int SERVERINFO_TIMEOUT_MS = 4000;
    static final long POLL_MS = 500;
    static final long QUIT_WAIT_MS = 15_000;

    private GameStart() { }

    /** The host as the start sees it; GameStreamClient in the app, a fake in tests. */
    interface Host {
        GameStreamClient.ServerInfo serverInfo() throws IOException;
        List<GameStreamClient.App> appList() throws IOException;
        boolean quitApp() throws IOException;
        long now();
        void sleep(long ms) throws InterruptedException;
    }

    enum Outcome { START, UNREACHABLE, UNPAIRED, GONE, NOT_CLOSED }

    static final class Result {
        final Outcome outcome;
        /** What the host reported, null when it did not answer. */
        final GameStreamClient.ServerInfo info;
        /** The fresh app list, null when it could not be read. */
        final List<GameStreamClient.App> apps;
        /** The app to start, with the id the host uses now. */
        final GameStreamClient.App target;
        /** For NOT_CLOSED: the title of the app that kept running, null when the list does not name it. */
        final String running;

        Result(Outcome outcome, GameStreamClient.ServerInfo info, List<GameStreamClient.App> apps,
               GameStreamClient.App target, String running) {
            this.outcome = outcome;
            this.info = info;
            this.apps = apps;
            this.target = target;
            this.running = running;
        }
    }

    /** By exact title first, since the host renumbers its apps whenever the list changes; the id only as a fallback. */
    static GameStreamClient.App resolve(List<GameStreamClient.App> apps, SteamGame game) {
        for (GameStreamClient.App app : apps) {
            if (app.title.equals(game.name)) return app;
        }
        if (game.appId == 0) return null;
        for (GameStreamClient.App app : apps) {
            if (app.id == game.appId) return app;
        }
        return null;
    }

    /** The host's running app goes first unless it is the target, which Moonlight then resumes. */
    static boolean needsQuit(int currentGame, int targetId) {
        return currentGame != 0 && currentGame != targetId;
    }

    static String title(List<GameStreamClient.App> apps, int id) {
        for (GameStreamClient.App app : apps) {
            if (app.id == id) return app.title;
        }
        return null;
    }

    /** Polls until the host runs nothing; a poll the host misses counts as still running. */
    static boolean waitUntilFree(Host host, long timeoutMs, long pollMs) throws InterruptedException {
        long deadline = host.now() + timeoutMs;
        while (true) {
            try {
                if (host.serverInfo().currentGame == 0) return true;
            } catch (IOException ignored) {
                // The host can be busy tearing the app down; the next poll decides.
            }
            if (host.now() >= deadline) return false;
            host.sleep(pollMs);
        }
    }

    static Result run(Host host, SteamGame game) throws InterruptedException {
        GameStreamClient.ServerInfo info;
        List<GameStreamClient.App> apps;
        try {
            info = host.serverInfo();
        } catch (IOException e) {
            return new Result(Outcome.UNREACHABLE, null, null, null, null);
        }
        try {
            apps = host.appList();
        } catch (GameStreamClient.AuthException e) {
            return new Result(Outcome.UNPAIRED, info, null, null, null);
        } catch (IOException e) {
            return new Result(Outcome.UNREACHABLE, info, null, null, null);
        }
        GameStreamClient.App target = resolve(apps, game);
        if (target == null) return new Result(Outcome.GONE, info, apps, null, null);
        if (needsQuit(info.currentGame, target.id)) {
            boolean quit;
            try {
                quit = host.quitApp();
            } catch (IOException e) {
                quit = false;
            }
            if (!quit || !waitUntilFree(host, QUIT_WAIT_MS, POLL_MS)) {
                return new Result(Outcome.NOT_CLOSED, info, apps, target, title(apps, info.currentGame));
            }
        }
        return new Result(Outcome.START, info, apps, target, null);
    }
}
