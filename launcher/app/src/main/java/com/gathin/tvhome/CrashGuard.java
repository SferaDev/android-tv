package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands Home back to the stock launcher when this one keeps crashing, so the TV is never left without a working
 * Home. Crash times and the fallback marker live in filesDir; the handler writes them synchronously.
 */
final class CrashGuard {
    private static final String TAG = "TvHomeCrash";
    static final int CRASH_LIMIT = 3;
    static final long WINDOW_MS = 2 * 60_000L;
    private static final String CRASHES = "crashes", MARKER = "fallback";
    private static final int PROMPT_TIMEOUT_MS = 3_000;
    private static final long HANDLER_WAIT_MS = 10_000;

    private CrashGuard() { }

    /** The crash times still inside the window, plus now. */
    static List<Long> record(List<Long> prior, long now) {
        List<Long> out = new ArrayList<>();
        for (long t : prior) {
            if (t <= now && now - t < WINDOW_MS) out.add(t);
        }
        out.add(now);
        return out;
    }

    static boolean tripped(List<Long> recent) {
        return recent.size() >= CRASH_LIMIT;
    }

    static List<Long> parse(String text) {
        List<Long> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            try {
                out.add(Long.parseLong(line.trim()));
            } catch (NumberFormatException ignored) {
                // skip a torn line
            }
        }
        return out;
    }

    static String format(List<Long> times) {
        StringBuilder sb = new StringBuilder();
        for (long t : times) sb.append(t).append('\n');
        return sb.toString();
    }

    static void install(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                onCrash(app);
            } catch (Throwable ignored) {
                // never let the guard hide the original crash
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        if ("pending".equals(read(new File(app.getFilesDir(), MARKER)))) restoreStock(app);
    }

    static boolean inFallback(Context ctx) {
        return new File(ctx.getFilesDir(), MARKER).exists();
    }

    /** The user chose a launcher again (togglehome) or the app was updated: stop standing aside. */
    static void clearFallback(Context ctx) {
        new File(ctx.getFilesDir(), MARKER).delete();
        new File(ctx.getFilesDir(), CRASHES).delete();
    }

    private static void onCrash(Context app) {
        File crashes = new File(app.getFilesDir(), CRASHES);
        List<Long> recent = record(parse(read(crashes)), System.currentTimeMillis());
        write(crashes, format(recent));
        if (!tripped(recent) || inFallback(app)) return;
        write(new File(app.getFilesDir(), MARKER), "pending");
        crashes.delete();
        Thread t = restoreStock(app);
        try {
            t.join(HANDLER_WAIT_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread restoreStock(Context app) {
        Thread t = new Thread(() -> {
            try (SelfAdb adb = SelfAdb.connect(app, PROMPT_TIMEOUT_MS, null)) {
                String stock = StockLauncher.pick(homeCandidates(app), app.getPackageName());
                if (stock == null) stock = StockLauncher.KNOWN.get(0);
                adb.shell("pm enable --user 0 " + stock);
                Reliability.forget(app, stock);
                write(new File(app.getFilesDir(), MARKER), "done");
                Log.w(TAG, "Crash loop: enabled " + stock);
            } catch (Exception e) {
                Log.w(TAG, "Could not enable the stock launcher: " + e);
            }
        }, "crash-guard");
        t.start();
        return t;
    }

    static List<StockLauncher.Candidate> homeCandidates(Context app) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<StockLauncher.Candidate> out = new ArrayList<>();
        for (ResolveInfo r : app.getPackageManager().queryIntentActivities(home,
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_SYSTEM_ONLY)) {
            if (r.activityInfo != null) {
                out.add(new StockLauncher.Candidate(r.activityInfo.packageName, r.activityInfo.name, true));
            }
        }
        return out;
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String text) {
        try {
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nothing more the handler can do
        }
    }
}
