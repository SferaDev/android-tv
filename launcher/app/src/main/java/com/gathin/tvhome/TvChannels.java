package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tuner (broadcast) channels from the TV provider and their now/next programmes. Everything is empty without
 *  READ_TV_LISTINGS or without a tuner. */
final class TvChannels {
    private static final String TAG = "TvHome.TvChannels";
    static final long GUIDE_BEFORE_MS = 60L * 60_000;
    static final long GUIDE_AFTER_MS = 4L * 60 * 60_000;
    private static final String[] CHANNEL_COLUMNS = {
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_BROWSABLE,
    };
    private static final String[] PROGRAM_COLUMNS = {
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
    };

    private TvChannels() { }

    static final class Channel {
        final long id;
        final String inputId;
        final String number;
        final String name;

        Channel(long id, String inputId, String number, String name) {
            this.id = id;
            this.inputId = inputId;
            this.number = number;
            this.name = name;
        }
    }

    static final class Programme {
        final String title;
        final long startMs;
        final long endMs;

        Programme(String title, long startMs, long endMs) {
            this.title = title;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    static final class NowNext {
        /** Either may be null. */
        final Programme now;
        final Programme next;

        NowNext(Programme now, Programme next) {
            this.now = now;
            this.next = next;
        }
    }

    /** Blocking; call off the main thread. Sorted by display number. */
    static List<Channel> list(Context ctx) {
        if (WatchNext.missingPermission(ctx)) return Collections.emptyList();
        Collection<String> tuners = TvInputs.tunerIds(ctx);
        List<Channel> channels = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI, CHANNEL_COLUMNS,
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String inputId = c.getString(1);
                if (!keep(c.getString(4), c.getInt(5) == 1, inputId, tuners)) continue;
                String name = c.getString(3);
                channels.add(new Channel(c.getLong(0), inputId, orEmpty(c.getString(2)), name == null ? "" : name));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read channels", e);
            return Collections.emptyList();
        }
        return sort(channels);
    }

    /** Broadcast channels only: not previews or app rows, browsable, from a tuner when tuners are known. */
    static boolean keep(String type, boolean browsable, String inputId, Collection<String> tunerIds) {
        if (!browsable || type == null || type.isEmpty()) return false;
        if (TvContract.Channels.TYPE_PREVIEW.equals(type) || TvContract.Channels.TYPE_OTHER.equals(type)) return false;
        return tunerIds.isEmpty() || tunerIds.contains(inputId);
    }

    static List<Channel> sort(List<Channel> channels) {
        List<Channel> sorted = new ArrayList<>(channels);
        sorted.sort((a, b) -> {
            int n = compareNumbers(a.number, b.number);
            return n != 0 ? n : a.name.compareToIgnoreCase(b.name);
        });
        return sorted;
    }

    /** Numeric-aware: "2" < "10" < "10.1" < "10.2"; blank numbers last; non-numeric parts compare as text. */
    static int compareNumbers(String a, String b) {
        boolean ea = a == null || a.trim().isEmpty(), eb = b == null || b.trim().isEmpty();
        if (ea || eb) return ea == eb ? 0 : ea ? 1 : -1;
        String[] pa = a.trim().split("[.\\-_ ]+"), pb = b.trim().split("[.\\-_ ]+");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            boolean na = isDigits(pa[i]), nb = isDigits(pb[i]);
            int c;
            if (na && nb) {
                c = new java.math.BigInteger(pa[i]).compareTo(new java.math.BigInteger(pb[i]));
            } else if (na != nb) {
                c = na ? -1 : 1;
            } else {
                c = pa[i].compareToIgnoreCase(pb[i]);
            }
            if (c != 0) return c;
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Now and next per channel id, from the guide around the current time. Blocking; channels without data are
     *  absent, and a denied provider gives an empty map. */
    static Map<Long, NowNext> guide(Context ctx, List<Channel> channels, long nowMs) {
        Map<Long, NowNext> out = new HashMap<>();
        if (WatchNext.missingPermission(ctx)) return out;
        ContentResolver resolver = ctx.getContentResolver();
        for (Channel channel : channels) {
            Uri uri = TvContract.buildProgramsUriForChannel(channel.id, nowMs - GUIDE_BEFORE_MS,
                    nowMs + GUIDE_AFTER_MS);
            List<Programme> programmes = new ArrayList<>();
            try (Cursor c = resolver.query(uri, PROGRAM_COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String title = c.getString(0);
                    if (title == null || title.trim().isEmpty()) continue;
                    programmes.add(new Programme(title.trim(), c.getLong(1), c.getLong(2)));
                }
            } catch (RuntimeException e) {
                Log.d(TAG, "No guide for a channel: " + e);
                continue;
            }
            NowNext nn = select(programmes, nowMs);
            if (nn.now != null || nn.next != null) out.put(channel.id, nn);
        }
        return out;
    }

    /** The programme airing at nowMs and the first one starting after it ends (or after nowMs when none airs). */
    static NowNext select(List<Programme> programmes, long nowMs) {
        Programme now = null;
        for (Programme p : programmes) {
            if (p.startMs <= nowMs && nowMs < p.endMs && (now == null || p.startMs > now.startMs)) now = p;
        }
        long after = now == null ? nowMs : now.endMs;
        Programme next = null;
        for (Programme p : programmes) {
            if (p == now || p.startMs < after || p.startMs <= nowMs) continue;
            if (next == null || p.startMs < next.startMs) next = p;
        }
        return new NowNext(now, next);
    }

    /** Opens the channel in the Live TV app, remembering it for Recent TV; false when nothing handles it. */
    static boolean open(Context ctx, Channel channel) {
        boolean started = TvInputs.start(ctx, launchIntents(Config.load(ctx), channel));
        if (started) RecentTv.record(ctx, RecentTv.CHANNEL, String.valueOf(channel.id));
        return started;
    }

    /** The Live TV package first when one is set (the plain intent can open a black player), then any handler. */
    static List<Intent> launchIntents(Config cfg, Channel channel) {
        Uri uri = TvContract.buildChannelUri(channel.id);
        List<Intent> out = new ArrayList<>();
        if (!cfg.liveTvPackage.isEmpty()) out.add(TvInputs.view(uri).setPackage(cfg.liveTvPackage));
        out.add(TvInputs.view(uri));
        return out;
    }
}
