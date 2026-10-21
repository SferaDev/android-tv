package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** The Continue watching list: titled Watch Next rows from any app, newest first. */
final class WatchHistory {
    static final int PER_APP = 3;
    /** Continue watching leaves out what was last watched longer ago than this; the user setting overrides it. */
    static final int DEFAULT_MAX_AGE_DAYS = 30;
    static final String MAX_AGE_KEY = "watch_max_age_days";

    private WatchHistory() { }

    /** Blocking (reads the TV provider); call off the main thread. */
    static List<WatchItem> items(Context ctx, int limit) {
        List<WatchItem> rows = WatchNext.items(ctx);
        // Without the permission there are no rows, which must not count as every hidden entry having left.
        Set<String> hidden = WatchNext.missingPermission(ctx) ? HiddenItems.keys(ctx) : HiddenItems.reconcile(ctx, rows);
        // Reconciled against every row: an entry past the age limit has not left the app.
        List<WatchItem> recent = recent(rows, System.currentTimeMillis(), maxAgeDays(ctx));
        return merge(recent, w -> HiddenItems.watchHidden(hidden, w), limit);
    }

    /** The age limit chosen in Settings, in days; 0 is no limit. */
    static int maxAgeDays(Context ctx) {
        return UserSettings.getInt(ctx, MAX_AGE_KEY, DEFAULT_MAX_AGE_DAYS);
    }

    /** Rows last watched within maxAgeDays, or every row when it is 0; a row without a time counts as old. */
    static List<WatchItem> recent(List<WatchItem> rows, long now, int maxAgeDays) {
        if (maxAgeDays <= 0) return rows;
        long cutoff = now - maxAgeDays * Usage.DAY_MS;
        List<WatchItem> out = new ArrayList<>();
        for (WatchItem w : rows) {
            if (w.lastWatched > 0 && w.lastWatched >= cutoff) out.add(w);
        }
        return out;
    }

    private static boolean hasTitle(String title) {
        return title != null && !title.trim().isEmpty();
    }

    static String key(String pkg, String title) {
        return pkg + "\0" + (title == null ? "" : title.trim().toLowerCase(Locale.ROOT));
    }

    static List<WatchItem> merge(List<WatchItem> watchNext, int limit) {
        return merge(watchNext, w -> false, limit);
    }

    /** Hidden shows are dropped after duplicates collapse but before the per-app cap, so they take no app's slot. */
    static List<WatchItem> merge(List<WatchItem> watchNext, Predicate<WatchItem> hidden, int limit) {
        List<WatchItem> sorted = new ArrayList<>(watchNext);
        sorted.sort((a, b) -> Long.compare(b.lastWatched, a.lastWatched));
        // Newest first before collapsing, so a show keeps its latest episode and hiding that one hides the show.
        List<WatchItem> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WatchItem w : sorted) {
            if (hasTitle(w.title) && seen.add(key(w.pkg, w.title))) all.add(w);
        }
        List<WatchItem> result = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        for (WatchItem item : all) {
            if (result.size() >= limit) break;
            int count = perApp.containsKey(item.pkg) ? perApp.get(item.pkg) : 0;
            if (count >= PER_APP || hidden.test(item)) continue;
            perApp.put(item.pkg, count + 1);
            result.add(item);
        }
        return result;
    }

    /** Opens the deep link inside the app, or the app itself; null when neither can be launched. */
    static Intent launchIntent(Context ctx, WatchItem item) {
        if (item.deepLink != null && item.deepLink.startsWith("intent:")) {
            Intent parsed = WatchNext.parseIntent(item.deepLink, item.pkg);
            if (parsed != null) return parsed;
        } else if (item.deepLink != null) {
            return new Intent(Intent.ACTION_VIEW, Uri.parse(item.deepLink)).setPackage(item.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Intent launch = AppEntries.launch(ctx.getPackageManager(), item.pkg);
        return launch == null ? null : launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
