package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The key services and the runtime permissions. The TV's own Settings screen is the normal path; SelfAdb
 * grants them only where that screen does not exist, or when the user asks for it from Settings.
 */
final class Grants {
    private static final Pattern COMPONENT = Pattern.compile("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$");
    private static final Pattern PERMISSION = Pattern.compile("^[A-Za-z0-9_.]+$");
    static final String ACCESSIBILITY = "enabled_accessibility_services";

    private Grants() { }

    /** Turns on both key services over SelfAdb, keeping any other accessibility service that is on. */
    static void enableKeyServicesNow(Context ctx, Runnable done) {
        Context app = ctx.getApplicationContext();
        SelfAdb.inBackground(app, "grants", false, adb -> {
            String current = adb.shell("settings get secure " + ACCESSIBILITY).trim();
            String next = withComponents(current, component(app, InputKeyService.class),
                    component(app, HomeKeyService.class));
            if (next != null) {
                adb.shell("settings put secure " + ACCESSIBILITY + " " + next);
                adb.shell("settings put secure accessibility_enabled 1");
            }
        }, done);
    }

    /** A runtime permission the user could not grant from the TV's prompt, over SelfAdb. */
    static void grantPermissionNow(Context ctx, String permission, Runnable done) {
        Context app = ctx.getApplicationContext();
        SelfAdb.inBackground(app, "grants", false, adb -> {
            if (PERMISSION.matcher(permission).matches()) adb.shell("pm grant " + app.getPackageName() + " " + permission);
        }, done);
    }

    static Intent accessibilityIntent() {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    static boolean resolves(Context ctx, Intent intent) {
        return intent.resolveActivity(ctx.getPackageManager()) != null;
    }

    static boolean serviceEnabled(Context app, Class<?> service) {
        return componentListed(Settings.Secure.getString(app.getContentResolver(), ACCESSIBILITY),
                app.getPackageName(), service.getName());
    }

    private static String component(Context app, Class<?> cls) {
        return new ComponentName(app, cls).flattenToString();
    }

    /** Whether the colon separated setting lists this component, also in the short "pkg/.Class" form adb users type. */
    static boolean componentListed(String setting, String pkg, String cls) {
        if (setting == null) return false;
        String shortName = cls.startsWith(pkg + ".") ? "." + cls.substring(pkg.length() + 1) : null;
        for (String entry : setting.split(":")) {
            if (entry.equals(pkg + "/" + cls) || (shortName != null && entry.equals(pkg + "/" + shortName))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The colon separated setting with the components added once each; "null" (what `settings get` prints for an
     * unset key) counts as empty. Null when an entry is not a plain component, so nothing odd reaches a shell.
     */
    static String withComponents(String setting, String... components) {
        List<String> entries = new ArrayList<>();
        if (setting != null && !setting.trim().isEmpty() && !setting.trim().equals("null")) {
            for (String entry : setting.trim().split(":")) if (!entry.isEmpty()) entries.add(entry);
        }
        for (String c : components) if (!entries.contains(c)) entries.add(c);
        for (String entry : entries) if (!COMPONENT.matcher(entry).matches()) return null;
        return String.join(":", entries);
    }
}
