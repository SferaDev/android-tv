package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-checks after boot and app updates that an OTA did not hand Home back to the stock launcher. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) CrashGuard.clearFallback(context);
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Reliability.check(app);
            } finally {
                result.finish();
            }
        }, "boot-check").start();
    }
}
