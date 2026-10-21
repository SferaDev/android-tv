package com.gathin.tvhome;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Switches a stock launcher on and off from a remote button. An app can't enable or disable packages, so this runs
 * the pm commands through the TV's own adbd (SelfAdb); Google TV routes Home to the stock launcher whenever it is
 * enabled.
 */
final class HomeToggle {
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private HomeToggle() { }

    static boolean validPackage(String pkg) {
        return PACKAGE.matcher(pkg).matches();
    }

    static void start(Context ctx, String pkg) {
        if (!validPackage(pkg) || !RUNNING.compareAndSet(false, true)) return;
        Context app = ctx.getApplicationContext();
        SelfAdb.inBackground(app, "home-toggle", false, adb -> toggle(adb, app, pkg), () -> RUNNING.set(false));
    }

    private static void toggle(SelfAdb adb, Context app, String pkg) throws java.io.IOException {
        // One command per shell(): chained pm commands can silently stop after the first.
        CrashGuard.clearFallback(app);
        boolean disabled = false;
        for (String line : adb.shell("pm list packages -d " + pkg).split("\n")) {
            if (line.trim().equals("package:" + pkg)) disabled = true;
        }
        if (disabled) {
            adb.shell("pm enable --user 0 " + pkg);
            Reliability.intend(app, pkg, false);
            SelfAdb.toast(app, R.string.home_toggle_stock_on);
        } else {
            adb.shell("pm disable-user --user 0 " + pkg);
            Reliability.intend(app, pkg, true);
            adb.shell("cmd package set-home-activity --user 0 " + app.getPackageName() + "/"
                    + MainActivity.class.getName());
            SelfAdb.toast(app, R.string.home_toggle_stock_off);
        }
        adb.shell("input keyevent KEYCODE_HOME");
    }
}
