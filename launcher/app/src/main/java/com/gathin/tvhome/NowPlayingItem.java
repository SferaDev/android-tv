package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.util.Log;

/** What a media session is playing or has just paused, plus the transport controls it supports. */
final class NowPlayingItem {
    private static final String TAG = "TvHome.NowPlaying";

    final String pkg;
    final String title;
    final String subtitle;
    /** Null when the session gave only a URI or nothing. */
    final Bitmap artwork;
    final String artUri;
    /** Zero or less when unknown (live streams). */
    final long durationMs;
    final long positionMs;
    /** SystemClock.elapsedRealtime() at which positionMs was true. */
    final long positionUpdateTime;
    final float playbackSpeed;
    /** PlaybackState.STATE_*. */
    final int state;
    /** PlaybackState.ACTION_* bits. */
    final long actions;
    private final MediaController controller;

    NowPlayingItem(String pkg, String title, String subtitle, Bitmap artwork, String artUri, long durationMs,
                   long positionMs, long positionUpdateTime, float playbackSpeed, int state, long actions,
                   MediaController controller) {
        this.pkg = pkg;
        this.title = title;
        this.subtitle = subtitle;
        this.artwork = artwork;
        this.artUri = artUri;
        this.durationMs = durationMs;
        this.positionMs = positionMs;
        this.positionUpdateTime = positionUpdateTime;
        this.playbackSpeed = playbackSpeed;
        this.state = state;
        this.actions = actions;
        this.controller = controller;
    }

    boolean playing() {
        return NowPlaying.isPlaying(state);
    }

    /** The position at elapsedRealtime now, for a progress line that moves between callbacks. */
    long positionAt(long now) {
        return NowPlaying.positionAt(state, positionMs, positionUpdateTime, playbackSpeed, durationMs, now);
    }

    /** Progress in 0..1 at now, or -1 when the duration is unknown. */
    float progressAt(long now) {
        return durationMs > 0 ? positionAt(now) / (float) durationMs : -1f;
    }

    boolean canPlayPause() {
        return NowPlaying.canPlayPause(state, actions);
    }

    boolean canNext() {
        return NowPlaying.supports(actions, PlaybackState.ACTION_SKIP_TO_NEXT);
    }

    boolean canPrevious() {
        return NowPlaying.supports(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS);
    }

    /** Pauses or resumes; false when the session does not support it. */
    boolean playPause() {
        if (!canPlayPause()) return false;
        return send(() -> {
            if (playing()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        });
    }

    boolean next() {
        return canNext() && send(() -> controller.getTransportControls().skipToNext());
    }

    boolean previous() {
        return canPrevious() && send(() -> controller.getTransportControls().skipToPrevious());
    }

    /** Stops the session, or pauses it when it has no stop; false when it can do neither or the call failed. */
    boolean stop() {
        if (NowPlaying.supports(actions, PlaybackState.ACTION_STOP)) {
            return send(() -> controller.getTransportControls().stop());
        }
        return NowPlaying.stopPauses(state, actions) && send(() -> controller.getTransportControls().pause());
    }

    /** Opens the session's own screen, else the app's launch entry; false when neither can start. */
    boolean open(Context ctx) {
        try {
            PendingIntent session = controller.getSessionActivity();
            if (session != null) {
                session.send();
                return true;
            }
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Log.w(TAG, "Session activity of " + pkg + " failed: " + e);
        }
        Intent launch = AppEntries.launch(ctx.getPackageManager(), pkg);
        if (launch == null) return false;
        try {
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open " + pkg + ": " + e);
            return false;
        }
    }

    private boolean send(Runnable command) {
        try {
            command.run();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Control of " + pkg + " failed: " + e);
            return false;
        }
    }
}
