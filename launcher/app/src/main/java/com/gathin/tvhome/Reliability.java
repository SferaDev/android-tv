package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the takeover in place across reboots and OTAs: stock launcher disabled, this launcher as Home. */
final class Reliability {
    private static final String TAG = "TvHomeBoot";
    private static final String PREFS = "reliability", INTENDED = "intended_disabled", RECORDED = "recorded";
    private static final int PROMPT_TIMEOUT_MS = 10_000;

    private Reliability() { }

    /** Records that the user turned pkg off (disabled) or back on, so boot checks follow the user's choice. */
    static void intend(Context ctx, String pkg, boolean disabled) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet(INTENDED, new HashSet<>()));
        if (disabled) set.add(pkg); else set.remove(pkg);
        prefs.edit().putStringSet(INTENDED, set).putBoolean(RECORDED, true).commit();
    }

    /** Drops pkg from the intended state and re-arms the first-time record. */
    static void forget(Context ctx, String pkg) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(prefs.getStringSet(INTENDED, new HashSet<>()));
        set.remove(pkg);
        prefs.edit().putStringSet(INTENDED, set).putBoolean(RECORDED, false).commit();
    }

    static void check(Context app) {
        if (CrashGuard.inFallback(app)) {
            Log.i(TAG, "skipped: crash fallback active");
            return;
        }
        try (SelfAdb adb = SelfAdb.connect(app, PROMPT_TIMEOUT_MS, null)) {
            Log.i(TAG, run(app, adb));
        } catch (Exception e) {
            Log.w(TAG, "check failed: " + e);
        }
    }

    private static String run(Context app, SelfAdb adb) throws Exception {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ours = app.getPackageName();
        String stock = StockLauncher.pick(CrashGuard.homeCandidates(app), ours);
        Set<String> disabled = StockLauncher.parsePackages(adb.shell("pm list packages -d"));
        Set<String> enabled = StockLauncher.parsePackages(adb.shell("pm list packages -e"));
        Set<String> intended = new HashSet<>(prefs.getStringSet(INTENDED, new HashSet<>()));
        boolean weAreHome = ours.equals(homePackage(app));

        if (!prefs.getBoolean(RECORDED, false) && weAreHome) {
            for (String pkg : new String[] {stock, StockLauncher.WIZARD}) {
                if (pkg != null && disabled.contains(pkg)) intended.add(pkg);
            }
            if (!intended.isEmpty()) prefs.edit().putStringSet(INTENDED, intended).putBoolean(RECORDED, true).commit();
        }

        List<String> redisabled = new ArrayList<>(), skipped = new ArrayList<>();
        for (String pkg : StockLauncher.toDisable(intended, enabled)) {
            if (pkg.equals(StockLauncher.WIZARD)
                    && StockLauncher.ownsNetwork(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"), pkg)) {
                skipped.add(pkg);
                continue;
            }
            adb.shell("pm disable-user --user 0 " + pkg);
            redisabled.add(pkg);
        }

        boolean homeFixed = false;
        if (!weAreHome || !redisabled.isEmpty()) {
            adb.shell("cmd package set-home-activity --user 0 " + ours + "/" + MainActivity.class.getName());
            homeFixed = true;
        }
        boolean granted = Grants.apply(app, adb);
        return "intended=" + intended + " redisabled=" + redisabled + " skippedWifiOwner=" + skipped
                + " homeFixed=" + homeFixed + " grantsApplied=" + granted;
    }

    private static String homePackage(Context app) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = app.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
    }
}
