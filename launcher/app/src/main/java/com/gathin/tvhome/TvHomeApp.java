package com.gathin.tvhome;

import android.app.Application;

public final class TvHomeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashGuard.install(this);
        HomeEvents.register(this);
    }
}
