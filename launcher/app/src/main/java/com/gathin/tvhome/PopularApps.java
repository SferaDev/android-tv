package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** The Recent apps row: every app, those used in the last year by last use, then the rest in the grid's order. */
final class PopularApps {
    static final int DAYS = 365;
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.android.tv.settings",
            "com.android.settings", "com.xiaomi.mitv.settings", "com.mitv.livetv"));

    private PopularApps() { }

    /** Packages by last use, newest first; never used, excluded and non-launchable ones dropped. */
    static List<String> rank(Map<String, Long> lastUsedMs, Set<String> excluded, Predicate<String> launchable,
                             int limit) {
        List<Map.Entry<String, Long>> sorted = new ArrayList<>();
        for (Map.Entry<String, Long> e : lastUsedMs.entrySet()) {
            if (e.getValue() > 0 && !excluded.contains(e.getKey())) sorted.add(e);
        }
        // Equal times fall back to the name so the order is stable between runs.
        sorted.sort((a, b) -> {
            int byTime = Long.compare(b.getValue(), a.getValue());
            return byTime != 0 ? byTime : a.getKey().compareTo(b.getKey());
        });
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Long> e : sorted) {
            if (result.size() >= limit) break;
            if (launchable.test(e.getKey())) result.add(e.getKey());
        }
        return result;
    }

    /** Used apps first as ranked, then every other app in the given (alphabetical) order; each once, none excluded. */
    static List<String> strip(List<String> used, List<String> everyApp, Set<String> excluded) {
        Set<String> seen = new LinkedHashSet<>();
        for (String pkg : used) if (!excluded.contains(pkg)) seen.add(pkg);
        for (String pkg : everyApp) if (!excluded.contains(pkg)) seen.add(pkg);
        return new ArrayList<>(seen);
    }

    /** The stock launchers and settings, and this launcher. */
    static Set<String> excluded(Context ctx) {
        Set<String> excluded = new HashSet<>(EXCLUDED);
        excluded.add(ctx.getPackageName());
        return excluded;
    }

    /** Hidden apps stay in, so one shown again takes its place without a reload. */
    static List<String> recent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        return rank(Usage.lastUsed(ctx, DAYS), excluded(ctx), pkg -> AppEntries.launchable(pm, pkg), Integer.MAX_VALUE);
    }
}
