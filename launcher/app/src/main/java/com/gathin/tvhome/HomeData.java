package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The data behind the Home strips, and the refresh of its slow sources. */
final class HomeData {
    private static final String TAG = "TvHomeData";
    static final int ROW_LIMIT = 10;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();

    private HomeData() { }

    static final class Rows {
        final List<String> recent;
        final List<WatchItem> watching;
        final List<SteamGame> games;

        Rows(List<String> recent, List<WatchItem> watching, List<SteamGame> games) {
            this.recent = recent;
            this.watching = watching;
            this.games = games;
        }
    }

    /** Blocking: usage stats, the Watch Next rows and the games cache. Call off the main thread. */
    static Rows load(Context ctx, Config cfg) {
        // Twice the strip length, so hiding a few items still leaves a full Continue watching strip.
        return new Rows(PopularApps.recent(ctx), WatchHistory.items(ctx, 2 * ROW_LIMIT),
                SteamGames.items(ctx, cfg));
    }

    static boolean refreshing() {
        return REFRESHING.get();
    }

    /**
     * Refreshes the slow sources (recents, games) on their own thread, then runs {@code done} on the main thread so
     * the rows can reload. A refresh already running is not started twice.
     */
    static void refresh(Context ctx, Runnable done) {
        if (!REFRESHING.compareAndSet(false, true)) return;
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Config cfg = Config.load(app);
                SteamGames.refresh(app, cfg);
                main.post(done);
            } catch (RuntimeException e) {
                Log.w(TAG, "refresh failed: " + e);
            } finally {
                REFRESHING.set(false);
            }
        }, "home-data").start();
    }
}
