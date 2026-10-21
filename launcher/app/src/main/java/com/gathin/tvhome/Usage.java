package com.gathin.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Per-package last use from UsageStatsManager; empty until usage access is granted. */
final class Usage {
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    private Usage() { }

    static Map<String, Long> lastUsed(Context ctx, int days) {
        UsageStatsManager manager = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return Collections.emptyMap();
        long now = System.currentTimeMillis();
        Map<String, UsageStats> stats = manager.queryAndAggregateUsageStats(now - days * DAY_MS, now);
        Map<String, Long> result = new HashMap<>();
        if (stats == null) return result;
        for (Map.Entry<String, UsageStats> e : stats.entrySet()) result.put(e.getKey(), e.getValue().getLastTimeUsed());
        return result;
    }
}
