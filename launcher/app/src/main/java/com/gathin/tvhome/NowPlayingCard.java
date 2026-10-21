package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The Now playing card in the hero: the media session's cover, track, progress and transport controls. */
final class NowPlayingCard {
    private final Activity activity;
    private final ImageCache images;
    private final Hero hero;
    private final int row;
    private final CardRow.Listener listener;
    /** Called after each change with whether the card took the focus away with it. */
    private final Consumer<Boolean> changed;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View card;
    private final ImageView cover, previous, play, next, stop;
    private final TextView app, title, subtitle;
    private final View progress, progressFill;
    /** The app whose session Stop hid, and when (elapsedRealtime); null while nothing is hidden. */
    private String stoppedPkg;
    private long stoppedAt;
    /** The session shown, or null; with its app's name and icon. */
    private NowPlayingItem playing;
    private String label;
    private Drawable icon;
    private Runnable stopObserving;
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (playing != null && playing.playing()) handler.postDelayed(this, 1000);
        }
    };

    NowPlayingCard(Activity activity, ImageCache images, Hero hero, boolean rtl, int row, CardRow.Listener listener,
                   Consumer<Boolean> changed) {
        this.activity = activity;
        this.images = images;
        this.hero = hero;
        this.row = row;
        this.listener = listener;
        this.changed = changed;
        density = activity.getResources().getDisplayMetrics().density;
        card = activity.findViewById(R.id.nowPlaying);
        cover = activity.findViewById(R.id.nowPlayingCover);
        app = activity.findViewById(R.id.nowPlayingApp);
        title = activity.findViewById(R.id.nowPlayingTitle);
        subtitle = activity.findViewById(R.id.nowPlayingSubtitle);
        previous = activity.findViewById(R.id.nowPlayingPrevious);
        play = activity.findViewById(R.id.nowPlayingPlay);
        next = activity.findViewById(R.id.nowPlayingNext);
        stop = activity.findViewById(R.id.nowPlayingStop);
        progress = activity.findViewById(R.id.nowPlayingProgress);
        progressFill = activity.findViewById(R.id.nowPlayingProgressFill);
        roundCorners(card, CardRow.RADIUS_DP);
        roundCorners(cover, 8f);
        if (rtl) {
            mirror(activity.findViewById(R.id.nowPlayingInner));
            LinearLayout controls = activity.findViewById(R.id.nowPlayingControls);
            mirror(controls);
            ((LinearLayout.LayoutParams) controls.getLayoutParams()).gravity = Gravity.RIGHT;
            View text = activity.findViewById(R.id.nowPlayingText);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) text.getLayoutParams();
            lp.rightMargin = lp.leftMargin;
            lp.leftMargin = 0;
            for (TextView t : new TextView[]{app, title, subtitle}) t.setGravity(Gravity.RIGHT);
        }
        cover.setOnClickListener(v -> {
            if (playing != null && !playing.open(activity)) {
                Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        previous.setOnClickListener(v -> {
            if (playing != null) playing.previous();
        });
        play.setOnClickListener(v -> {
            if (playing != null) playing.playPause();
        });
        next.setOnClickListener(v -> {
            if (playing != null) playing.next();
        });
        stop.setOnClickListener(v -> {
            if (playing == null) return;
            playing.stop();
            stoppedPkg = playing.pkg;
            stoppedAt = SystemClock.elapsedRealtime();
            show(null);
        });
        for (View target : controls()) {
            target.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int i = targets().indexOf(v);
                return i < 0 || listener.onCardKey(row, i, keyCode);
            });
            target.setOnFocusChangeListener((v, focused) -> {
                int i = targets().indexOf(v);
                if (focused && i >= 0) listener.onCardFocused(row, i);
            });
        }
    }

    void start() {
        stopObserving = NowPlaying.observe(activity, this::show);
    }

    void stop() {
        if (stopObserving != null) stopObserving.run();
        stopObserving = null;
        handler.removeCallbacks(progressTick);
    }

    View view() {
        return card;
    }

    /** The session shown, or null. */
    NowPlayingItem item() {
        return playing;
    }

    String appLabel() {
        return label;
    }

    Drawable appIcon() {
        return icon;
    }

    /** The cover, then the controls the session supports, in reading order. */
    List<View> targets() {
        List<View> out = new ArrayList<>();
        if (playing == null) return out;
        for (View v : controls()) {
            if (v.getVisibility() == View.VISIBLE) out.add(v);
        }
        return out;
    }

    /** Focuses the target at {@code column}; one already focused reports it again, as no focus change fires. */
    void focus(int column) {
        List<View> targets = targets();
        if (targets.isEmpty()) return;
        View target = targets.get(HomeNav.column(column, targets.size()));
        if (target.isFocused()) listener.onCardFocused(row, targets.indexOf(target));
        else target.requestFocus();
    }

    private View[] controls() {
        return new View[]{cover, previous, play, next, stop};
    }

    private void show(NowPlayingItem item) {
        if (item != null && NowPlaying.hiddenAfterStop(stoppedPkg, stoppedAt, item.pkg, item.playing(),
                SystemClock.elapsedRealtime())) {
            item = null;
        } else if (item != null) {
            stoppedPkg = null;
        }
        View focused = activity.getCurrentFocus();
        boolean hadFocus = focused != null && targets().contains(focused);
        boolean samePackage = playing != null && item != null && playing.pkg.equals(item.pkg);
        playing = item;
        handler.removeCallbacks(progressTick);
        if (item == null) {
            card.setVisibility(View.GONE);
            hero.narrow(false);
            changed.accept(hadFocus);
            return;
        }
        if (!samePackage) {
            label = null;
            icon = null;
            try {
                PackageManager pm = activity.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(item.pkg, 0);
                label = pm.getApplicationLabel(info).toString();
                icon = pm.getApplicationIcon(info);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        app.setText(label);
        title.setText(item.title);
        subtitle.setText(item.subtitle);
        subtitle.setVisibility(item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        cover.setContentDescription(item.title);
        ImageCache.clear(cover);
        int[] tones = null;
        if (item.artwork != null) {
            cover.setImageBitmap(item.artwork);
            tones = Backdrop.tonesOf(item.artwork);
        } else {
            cover.setImageDrawable(icon);
            if (item.artUri != null) images.load(item.artUri, Math.round(128 * density), cover, () -> { });
        }
        if (tones == null) tones = HomeCards.gradientFor(item.pkg);
        card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, tones));
        previous.setVisibility(item.canPrevious() ? View.VISIBLE : View.GONE);
        play.setVisibility(item.canPlayPause() ? View.VISIBLE : View.GONE);
        play.setImageResource(item.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
        next.setVisibility(item.canNext() ? View.VISIBLE : View.GONE);
        boolean wasShown = card.getVisibility() == View.VISIBLE;
        card.setVisibility(View.VISIBLE);
        hero.narrow(true);
        if (!wasShown) card.post(this::updateProgress);
        else updateProgress();
        if (item.playing()) handler.postDelayed(progressTick, 1000);
        // A control the new track does not support lost its focus: keep it on the card.
        if (hadFocus && (focused.getVisibility() != View.VISIBLE)) cover.requestFocus();
        changed.accept(false);
    }

    private void updateProgress() {
        float at = playing == null ? -1f : playing.progressAt(SystemClock.elapsedRealtime());
        progress.setVisibility(at >= 0f ? View.VISIBLE : View.GONE);
        if (at < 0f || card.getWidth() == 0) return;
        progressFill.getLayoutParams().width = Math.round(card.getWidth() * Math.min(1f, at));
        progressFill.requestLayout();
    }

    private void roundCorners(View v, float radiusDp) {
        float radius = radiusDp * density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** Hebrew reads right to left: the same children, in the opposite order on screen. */
    private static void mirror(LinearLayout row) {
        List<View> children = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) children.add(row.getChildAt(i));
        row.removeAllViews();
        for (int i = children.size() - 1; i >= 0; i--) row.addView(children.get(i));
    }
}
