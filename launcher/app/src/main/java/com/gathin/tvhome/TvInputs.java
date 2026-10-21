package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The TV's hardware inputs (HDMI, AV, tuner) and how to switch to one. */
final class TvInputs {
    private static final String TAG = "TvHome.TvInputs";
    static final String CHANNEL_MIME = "vnd.android.cursor.item/channel";

    private TvInputs() { }

    static final class Input {
        final String id;
        final String label;
        final int type;

        Input(String id, String label, int type) {
            this.id = id;
            this.label = label;
            this.type = type;
        }

        boolean isHdmi() { return type == TvInputInfo.TYPE_HDMI; }

        boolean isTuner() { return type == TvInputInfo.TYPE_TUNER; }
    }

    /** Blocking-free: reads the platform list. Empty where there is no TvInputManager. */
    static List<Input> list(Context ctx) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return Collections.emptyList();
        List<Input> inputs = new ArrayList<>();
        List<String> sources = Config.load(ctx).inputSources;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isHidden(ctx)) continue;
                // input_sources "HW8=-": a port the board reports but the set does not have.
                if (Integer.valueOf(-1).equals(InputSources.mapped(sources, info.getId()))) continue;
                int type = info.getType();
                // Apps such as AirPlay register software tuners; only system tuners are the TV's own.
                if (type == TvInputInfo.TYPE_TUNER && !isSystem(info)) continue;
                if (!info.isPassthroughInput() && type != TvInputInfo.TYPE_TUNER) continue;
                inputs.add(new Input(info.getId(), label(ctx, info), type));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not list inputs", e);
            return Collections.emptyList();
        }
        return order(inputs);
    }

    /** Ids of every tuner input, system or not: the channel table is keyed by these. */
    static List<String> tunerIds(Context ctx) {
        List<String> ids = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return ids;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_TUNER) ids.add(info.getId());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not list tuners", e);
        }
        return ids;
    }

    private static boolean isSystem(TvInputInfo info) {
        return (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /** The user's rename from Settings > Inputs, else the platform label, else the id. */
    private static String label(Context ctx, TvInputInfo info) {
        CharSequence custom = InputNames.tidy(info.loadCustomLabel(ctx));
        CharSequence stock = info.loadLabel(ctx);
        return pickLabel(custom, stock, info.getId(), info.getType() == TvInputInfo.TYPE_HDMI);
    }

    static String pickLabel(CharSequence custom, CharSequence stock, String id, boolean hdmi) {
        if (custom != null && custom.toString().trim().length() > 0) return custom.toString().trim();
        String label = stock == null ? "" : stock.toString().trim();
        if (label.isEmpty()) return id;
        return hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI") ? "HDMI " + label : label;
    }

    /** HDMI first, then the rest, each group by label. */
    static List<Input> order(List<Input> inputs) {
        List<Input> sorted = new ArrayList<>(inputs);
        sorted.sort((a, b) -> {
            if (a.isHdmi() != b.isHdmi()) return a.isHdmi() ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return sorted;
    }

    static List<Intent> launchIntents(Config cfg, Input input) {
        return launchIntents(cfg, input, null);
    }

    /** Intents to try in order: the standard channel-URI VIEW, then the configured `input_action`.
     *  The Live TV package goes first when one is set: other apps can claim the same URI, which shows a chooser
     *  (WhaleOS), and for a tuner the plain intent can open a black player.
     *  An input mapped in `input_sources` goes first as `input_action` with its source number, as MainActivity's
     *  Inputs menu does upstream; `input_uri_scheme` swaps the VIEW's scheme and drops its MIME type.
     *  An unmapped input with `input_action` written as "package/.Activity" starts that activity first: the vendor's
     *  own source picker, on firmware that answers the channel URI with "unsupported" (Redmi X65). */
    static List<Intent> launchIntents(Config cfg, Input input, Integer source) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(input.id);
        List<Intent> out = new ArrayList<>();
        if (source != null && source >= 0 && !cfg.inputAction.isEmpty()) {
            out.add(actionIntent(cfg).putExtra("input", source.intValue())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (source == null && InputSources.isComponent(cfg.inputAction)) {
            out.add(actionIntent(cfg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        if (!cfg.liveTvPackage.isEmpty()) out.add(view(cfg, uri).setPackage(cfg.liveTvPackage));
        out.add(view(cfg, uri));
        if (!cfg.inputAction.isEmpty()) {
            out.add(actionIntent(cfg).setDataAndType(uri, CHANNEL_MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return out;
    }

    /** `input_action` as an intent: an explicit component when it reads "package/.Activity", else an action. */
    private static Intent actionIntent(Config cfg) {
        ComponentName component = InputSources.isComponent(cfg.inputAction)
                ? ComponentName.unflattenFromString(cfg.inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(cfg.inputAction);
    }

    static Intent view(Config cfg, Uri uri) {
        if (cfg.inputUriScheme.isEmpty()) return view(uri);
        return new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(cfg.inputUriScheme).build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent view(Uri uri) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, CHANNEL_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Switches to the input, remembering it for Recent TV; false when nothing could open it. */
    static boolean open(Context ctx, Input input) {
        Config cfg = Config.load(ctx);
        Integer source = InputSources.mapped(cfg.inputSources, sourceId(ctx, input.id));
        boolean started = start(ctx, launchIntents(cfg, input, source));
        if (started) RecentTv.record(ctx, RecentTv.INPUT, input.id);
        return started;
    }

    /** A CEC device is a child input of its HDMI port, and the vendor source number belongs to the port (MiTV-MFFU0). */
    private static String sourceId(Context ctx, String inputId) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return inputId;
        try {
            TvInputInfo info = tim.getTvInputInfo(inputId);
            if (info != null && info.getParentId() != null) return info.getParentId();
        } catch (RuntimeException ignored) {
        }
        return inputId;
    }

    /** Starts the first intent something handles. */
    static boolean start(Context ctx, List<Intent> candidates) {
        for (Intent i : candidates) {
            try {
                ctx.startActivity(i);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.d(TAG, "Not handled: " + i);
            }
        }
        return false;
    }

    /** Best effort: the input the launcher opened last. The platform exposes no current input to apps. */
    static String current(Context ctx) {
        return RecentTv.lastInput(ctx);
    }
}
