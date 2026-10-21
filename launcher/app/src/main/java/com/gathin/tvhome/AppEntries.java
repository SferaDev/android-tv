package com.gathin.tvhome;

import android.content.Intent;
import android.content.pm.PackageManager;

/** Launch entries of installed packages. */
final class AppEntries {
    private AppEntries() { }

    static boolean leanback(PackageManager pm, String pkg) {
        return pm.getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    static boolean launchable(PackageManager pm, String pkg) {
        return leanback(pm, pkg) || pm.getLaunchIntentForPackage(pkg) != null;
    }

    /** The intent that opens the app, leanback entry first; null when it has none. */
    static Intent launch(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
