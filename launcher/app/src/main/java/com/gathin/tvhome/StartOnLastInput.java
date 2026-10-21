package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.List;

/** Opt-in: on Home's first start after boot, go back to the input or channel the TV was last on. */
final class StartOnLastInput {
    static final String KEY = "start_on_last_input";
    /** A Home start later than this after boot is a launcher restart, not the TV starting. */
    static final long BOOT_WINDOW_MS = 5 * 60_000L;
    private static final String PREFS = "start_on_last_input", HANDLED_BOOT = "handled_boot";

    /** What opening the last entry came to. */
    enum Result { OPENED, NOTHING, NOT_LISTED_YET }

    private StartOnLastInput() { }

    static boolean enabled(Context ctx) {
        return UserSettings.getBoolean(ctx, KEY, false);
    }

    /** True for a start inside the boot window of a boot not handled yet. */
    static boolean due(long sinceBootMs, int boot, int handledBoot) {
        return sinceBootMs <= BOOT_WINDOW_MS && boot >= 0 && boot != handledBoot;
    }

    /** True once per boot; a crash or a kill that restarts Home in the window does not switch input again. */
    static boolean claimBoot(Context ctx) {
        int boot = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!due(SystemClock.elapsedRealtime(), boot, prefs.getInt(HANDLED_BOOT, -1))) return false;
        prefs.edit().putInt(HANDLED_BOOT, boot).apply();
        return true;
    }

    /** The newest entry when it is an input or a channel, else null. */
    static RecentTv.Entry pick(List<RecentTv.Entry> newestFirst) {
        if (newestFirst.isEmpty()) return null;
        RecentTv.Entry e = newestFirst.get(0);
        return RecentTv.INPUT.equals(e.kind) || RecentTv.CHANNEL.equals(e.kind) ? e : null;
    }

    /** Blocking: reads the input and channel tables. Inputs register a little after boot, hence NOT_LISTED_YET. */
    static Result open(Context ctx) {
        RecentTv.Entry last = pick(RecentTv.list(ctx));
        if (last == null) return Result.NOTHING;
        if (RecentTv.INPUT.equals(last.kind)) {
            for (TvInputs.Input i : TvInputs.list(ctx)) {
                if (i.id.equals(last.id)) return TvInputs.open(ctx, i) ? Result.OPENED : Result.NOTHING;
            }
        } else {
            if (WatchNext.missingPermission(ctx)) return Result.NOTHING;
            for (TvChannels.Channel c : TvChannels.list(ctx)) {
                if (String.valueOf(c.id).equals(last.id)) {
                    return TvChannels.open(ctx, c) ? Result.OPENED : Result.NOTHING;
                }
            }
        }
        return Result.NOT_LISTED_YET;
    }
}
