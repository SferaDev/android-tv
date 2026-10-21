package com.gathin.tvhome;

import android.content.Context;

import java.util.List;

/** The data behind the Home strips. */
final class HomeData {
    static final int ROW_LIMIT = 10;

    private HomeData() { }

    static final class Rows {
        final List<String> recent;
        final List<WatchItem> watching;

        Rows(List<String> recent, List<WatchItem> watching) {
            this.recent = recent;
            this.watching = watching;
        }
    }

    /** Blocking: usage stats and the Watch Next rows. Call off the main thread. */
    static Rows load(Context ctx) {
        // Twice the strip length, so hiding a few items still leaves a full Continue watching strip.
        return new Rows(PopularApps.recent(ctx), WatchHistory.items(ctx, 2 * ROW_LIMIT));
    }
}
