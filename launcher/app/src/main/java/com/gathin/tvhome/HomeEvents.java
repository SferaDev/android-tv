package com.gathin.tvhome;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** System events Home reacts to for as long as the process lives: the TV waking up and apps coming or going. */
final class HomeEvents {
    static final long PACKAGES_SETTLE_MS = 1_000;

    interface Listener {
        void onWake();

        /** After a burst of package changes has settled. */
        void onPackagesChanged();
    }

    private static Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable PACKAGES = () -> {
        if (listener != null) listener.onPackagesChanged();
    };

    private HomeEvents() { }

    static void setListener(Listener l) {
        listener = l;
    }

    static void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    static void register(Application app) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (listener != null) listener.onWake();
                    return;
                }
                // An update sends REMOVED then ADDED; the settle delay covers both.
                if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                        && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                MAIN.removeCallbacks(PACKAGES);
                MAIN.postDelayed(PACKAGES, PACKAGES_SETTLE_MS);
            }
        };
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        for (IntentFilter filter : new IntentFilter[]{screen, packages}) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        }
    }
}
