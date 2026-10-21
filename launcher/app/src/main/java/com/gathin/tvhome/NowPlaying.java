package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Picks the media session worth showing on Home and reports it as a NowPlayingItem. */
final class NowPlaying {
    private static final String TAG = "TvHome.NowPlaying";
    static final long PAUSED_WINDOW_MS = 30 * 60 * 1000L;
    static final int MAX_ARTWORK = 512;
    /** A session can still report playing just after Stop; that echo does not bring the card back. */
    static final long STOP_GRACE_MS = 3000;

    private NowPlaying() { }

    /** One session as the selection sees it; updateTime is elapsedRealtime of its last playback state. */
    static final class Candidate {
        final int state;
        final long updateTime;
        final boolean eligible;

        Candidate(int state, long updateTime, boolean eligible) {
            this.state = state;
            this.updateTime = updateTime;
            this.eligible = eligible;
        }
    }

    static boolean isPlaying(int state) {
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
    }

    /** Index of the first playing candidate, else the most recently paused one inside the window; -1 for none. */
    static int select(List<Candidate> candidates, long now) {
        int paused = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (!c.eligible) continue;
            if (isPlaying(c.state)) return i;
            boolean recent = c.state == PlaybackState.STATE_PAUSED && c.updateTime > 0
                    && now - c.updateTime <= PAUSED_WINDOW_MS;
            if (recent && (paused < 0 || c.updateTime > candidates.get(paused).updateTime)) paused = i;
        }
        return paused;
    }

    static long positionAt(int state, long position, long updateTime, float speed, long duration, long now) {
        long at = position;
        if (state == PlaybackState.STATE_PLAYING && updateTime > 0 && now > updateTime) {
            at += Math.round((now - updateTime) * speed);
        }
        if (duration > 0) at = Math.min(at, duration);
        return Math.max(0, at);
    }

    static boolean supports(long actions, long action) {
        return (actions & action) != 0;
    }

    static boolean canPlayPause(int state, long actions) {
        long both = PlaybackState.ACTION_PLAY_PAUSE;
        if (isPlaying(state)) return supports(actions, PlaybackState.ACTION_PAUSE | both);
        return supports(actions, PlaybackState.ACTION_PLAY | both);
    }

    /** Stop pauses where the session has no stop of its own. */
    static boolean stopPauses(int state, long actions) {
        return !supports(actions, PlaybackState.ACTION_STOP) && isPlaying(state) && canPlayPause(state, actions);
    }

    /**
     * After Stop on {@code stoppedPkg} at {@code stoppedAt}, whether to keep the card hidden: until something plays,
     * the stopped app itself only once the grace has passed. A null stoppedPkg hides nothing.
     */
    static boolean hiddenAfterStop(String stoppedPkg, long stoppedAt, String pkg, boolean playing, long now) {
        if (stoppedPkg == null) return false;
        if (!playing) return true;
        return stoppedPkg.equals(pkg) && now - stoppedAt < STOP_GRACE_MS;
    }

    /** Calls onItem on the main thread with the item to show, or null when nothing qualifies; run the result to stop. */
    static Runnable observe(Context ctx, Consumer<NowPlayingItem> onItem) {
        Observer observer = new Observer(ctx.getApplicationContext(), onItem);
        observer.start();
        return observer::stop;
    }

    private static final class Observer extends MediaController.Callback {
        private final Context ctx;
        private final Consumer<NowPlayingItem> onItem;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ComponentName listener;
        private final MediaSessionManager sessions;
        private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::reselect;
        private final List<MediaController> watched = new ArrayList<>();
        private MediaController selected;
        private MediaMetadata artworkSource;
        private Bitmap artwork;
        private boolean stopped;

        Observer(Context ctx, Consumer<NowPlayingItem> onItem) {
            this.ctx = ctx;
            this.onItem = onItem;
            this.listener = new ComponentName(ctx, WatchListener.class);
            this.sessions = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        }

        void start() {
            try {
                sessions.addOnActiveSessionsChangedListener(changed, listener, main);
                reselect(sessions.getActiveSessions(listener));
            } catch (SecurityException e) {
                Log.w(TAG, "No access to media sessions: " + e);
                onItem.accept(null);
            }
        }

        void stop() {
            stopped = true;
            main.removeCallbacksAndMessages(null);
            sessions.removeOnActiveSessionsChangedListener(changed);
            for (MediaController c : watched) c.unregisterCallback(this);
            watched.clear();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            reselect(null);
        }

        @Override
        public void onSessionDestroyed() {
            reselect(null);
        }

        private void reselect(List<MediaController> controllers) {
            if (stopped) return;
            if (controllers == null) {
                try {
                    controllers = sessions.getActiveSessions(listener);
                } catch (SecurityException e) {
                    return;
                }
            }
            for (MediaController c : watched) c.unregisterCallback(this);
            watched.clear();
            List<Candidate> candidates = new ArrayList<>();
            for (MediaController c : controllers) {
                c.registerCallback(this, main);
                watched.add(c);
                candidates.add(candidate(c));
            }
            int index = select(candidates, SystemClock.elapsedRealtime());
            MediaController next = index < 0 ? null : controllers.get(index);
            if (next == null || !next.getSessionToken().equals(selected == null ? null : selected.getSessionToken())) {
                artworkSource = null;
                artwork = null;
            }
            selected = next;
            publish();
        }

        private Candidate candidate(MediaController c) {
            PlaybackState state = c.getPlaybackState();
            String pkg = c.getPackageName();
            boolean eligible = state != null && pkg != null && !pkg.equals(ctx.getPackageName())
                    && !title(c.getMetadata()).isEmpty() && AppEntries.launchable(ctx.getPackageManager(), pkg);
            return new Candidate(state == null ? PlaybackState.STATE_NONE : state.getState(),
                    state == null ? 0 : state.getLastPositionUpdateTime(), eligible);
        }

        private static String title(MediaMetadata m) {
            if (m == null) return "";
            return firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    m.getString(MediaMetadata.METADATA_KEY_TITLE));
        }

        private void publish() {
            if (stopped) return;
            MediaController c = selected;
            MediaMetadata m = c == null ? null : c.getMetadata();
            PlaybackState state = c == null ? null : c.getPlaybackState();
            if (m == null || state == null) {
                onItem.accept(null);
                return;
            }
            if (m != artworkSource) {
                artworkSource = m;
                artwork = firstBitmap(m, MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ART,
                        MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (artwork != null) artwork = downscale(artwork);
            }
            String uri = artwork != null ? null : firstNonEmpty(
                    m.getString(MediaMetadata.METADATA_KEY_ART_URI),
                    m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
                    m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            String subtitle = firstNonEmpty(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
            onItem.accept(new NowPlayingItem(c.getPackageName(), title(m), subtitle, artwork,
                    uri == null || uri.isEmpty() ? null : uri, m.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    state.getPosition(), state.getLastPositionUpdateTime(), state.getPlaybackSpeed(),
                    state.getState(), state.getActions(), c));
        }
    }

    static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static Bitmap firstBitmap(MediaMetadata m, String... keys) {
        for (String key : keys) {
            Bitmap b = m.getBitmap(key);
            if (b != null) return b;
        }
        return null;
    }

    static Bitmap downscale(Bitmap source) {
        int[] size = fit(source.getWidth(), source.getHeight(), MAX_ARTWORK);
        if (size[0] == source.getWidth() && size[1] == source.getHeight()) return source;
        return Bitmap.createScaledBitmap(source, size[0], size[1], true);
    }

    /** The size that keeps the aspect ratio with the longer side at most max. */
    static int[] fit(int width, int height, int max) {
        int longer = Math.max(width, height);
        if (longer <= max) return new int[] {width, height};
        float scale = max / (float) longer;
        return new int[] {Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale))};
    }
}
