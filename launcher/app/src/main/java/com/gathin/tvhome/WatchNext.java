package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Other apps' Watch Next rows (TvProvider), as Continue watching items. */
final class WatchNext {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String TAG = "TvHome.WatchNext";
    /** TvContract.WatchNextPrograms: continue, next episode, new; the watchlist type (3) is not a resume point. */
    private static final int TYPE_WATCHLIST = 3;
    private static final String[] BASE_COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    };
    /** TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, which only exists from Android 9. */
    private static final String CONTENT_ID = "content_id";

    private WatchNext() { }

    static boolean missingPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    /** Calls onChange on the main thread whenever any app edits Watch Next; run the returned Runnable to stop. */
    static Runnable observe(Context ctx, Runnable onChange) {
        ContentResolver resolver = ctx.getApplicationContext().getContentResolver();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                onChange.run();
            }
        };
        resolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
        return () -> resolver.unregisterContentObserver(observer);
    }

    /** Blocking; call off the main thread. Empty without the permission. */
    static List<WatchItem> items(Context ctx) {
        List<WatchItem> items = new ArrayList<>();
        if (missingPermission(ctx)) return items;
        ContentResolver resolver = ctx.getContentResolver();
        Map<String, Boolean> installed = new HashMap<>();
        String[] columns = BASE_COLUMNS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            columns = Arrays.copyOf(BASE_COLUMNS, BASE_COLUMNS.length + 1);
            columns[BASE_COLUMNS.length] = CONTENT_ID;
        }
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(8) == TYPE_WATCHLIST) continue;
                String pkg = c.getString(0);
                if (pkg == null) continue;
                Boolean ok = installed.get(pkg);
                if (ok == null) {
                    ok = AppEntries.launchable(ctx.getPackageManager(), pkg);
                    installed.put(pkg, ok);
                }
                if (!ok) continue;
                WatchItem item = item(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        usable(resolver, c.getString(5)), usable(resolver, c.getString(6)), c.getString(7),
                        c.getLong(9), c.getLong(10), c.getLong(11),
                        id(columns.length > 13 ? c.getString(13) : null, c.getString(12)));
                if (item != null) items.add(item);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read Watch Next: " + e);
        }
        return items;
    }

    static WatchItem item(String pkg, String title, String episodeTitle, String season, String episode,
                          String poster, String thumbnail, String intentUri, long engaged, long position,
                          long duration) {
        return item(pkg, title, episodeTitle, season, episode, poster, thumbnail, intentUri, engaged, position,
                duration, null);
    }

    static WatchItem item(String pkg, String title, String episodeTitle, String season, String episode,
                          String poster, String thumbnail, String intentUri, long engaged, long position,
                          long duration, String id) {
        if (pkg == null || isBlank(title)) return null;
        return new WatchItem(pkg, id, title.trim(), subtitle(episodeTitle, season, episode),
                poster != null ? poster : thumbnail, isBlank(intentUri) ? null : intentUri, engaged,
                progress(position, duration));
    }

    /** The row's own id: content_id, else internal_provider_id; null when both are blank. */
    static String id(String contentId, String internalId) {
        if (!isBlank(contentId)) return contentId.trim();
        return isBlank(internalId) ? null : internalId.trim();
    }

    /** "S1E2 Episode title", or whichever parts exist; null when none. */
    static String subtitle(String episodeTitle, String season, String episode) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(season)) sb.append('S').append(season.trim());
        if (!isBlank(episode)) sb.append('E').append(episode.trim());
        if (!isBlank(episodeTitle)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(episodeTitle.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static float progress(long position, long duration) {
        if (duration <= 0 || position <= 0) return -1f;
        return Math.min(1f, (float) position / duration);
    }

    /** An intent: URI scoped to the publishing app; null when malformed or aimed at another package. */
    static Intent parseIntent(String uri, String pkg) {
        try {
            Intent intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            if (intent.getPackage() == null) intent.setPackage(pkg);
            if (!pkg.equals(intent.getPackage())) return null;
            intent.setSelector(null);
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Remote URIs pass through; a content:// URI the launcher cannot open becomes null. */
    private static String usable(ContentResolver resolver, String uri) {
        if (isBlank(uri)) return null;
        if (!uri.startsWith("content:")) return uri;
        try (InputStream in = resolver.openInputStream(Uri.parse(uri))) {
            return in == null ? null : uri;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
