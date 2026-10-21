package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The hero on Home: the focused card's title, app and badge over a full-screen backdrop, composed off the main thread
 * (Backdrop) and crossfaded in. A show or game without a large picture looks one up online (Artwork).
 */
final class Hero {
    /** Fast scrolling does not restart the backdrop on every card it passes. */
    private static final long DELAY_MS = 120;
    private static final long CROSSFADE_MS = 350;
    /** The backdrop dims while a strip below the first is focused. */
    private static final float DIM = 0.3f;
    /** Keeps the header readable over a bright picture. */
    private static final int HEADER_SCRIM_DP = 180;

    /** What the hero shows for one card. */
    static final class Item {
        final String key;
        final String title;
        final String subtitle;
        final String appName;
        final Drawable badge;
        /** The best picture: artwork or a game's cover; null when there is none. */
        final String art;
        /** The app's banner or icon (a private copy): its colours tint the backdrop when there is no large picture. */
        final Drawable fallback;
        /** A show's or game's name to look up online when art is not a large landscape picture; null for none. */
        String showTitle, gameName;
        /** Already decoded (Now playing's cover); takes the place of art. */
        Bitmap picture;

        Item(String key, String title, String subtitle, String appName, Drawable badge, String art, Drawable fallback) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.appName = appName;
            this.badge = badge;
            this.art = art;
            this.fallback = fallback;
        }
    }

    private final View root;
    private final ImageCache images;
    private final boolean animations, reducedMotion, rtl;
    private final float density;
    private final Runnable shown;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Online lookups wait on the network; their own thread keeps them from holding up anything else. */
    private final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final View backdrop, text;
    private ImageView front, back;
    private final TextView title, subtitle, app;
    private final ImageView badge;
    private Item pending;
    /** Read on the lookup thread. */
    private volatile String shownKey;
    /** The hero text fades out while a strip below the first is focused. */
    private boolean visible = true;
    /** The last composed backdrops, a screen each: the current one, its neighbours and the one before. */
    private final LruCache<String, Bitmap> backdrops = new LruCache<>(4);
    /** Items whose backdrop is a full-screen picture already, so no online lookup is needed. */
    private final Set<String> fullBleed = ConcurrentHashMap.newKeySet();
    private final Runnable update = this::showPending;

    /** {@code shown} runs after each new item is on screen. */
    Hero(Activity activity, View root, ImageCache images, Config cfg, boolean rtl, Runnable shown) {
        this.root = root;
        this.images = images;
        this.animations = cfg.animations;
        this.reducedMotion = cfg.reducedMotion;
        this.rtl = rtl;
        this.shown = shown;
        density = activity.getResources().getDisplayMetrics().density;
        backdrop = activity.findViewById(R.id.backdrop);
        front = activity.findViewById(R.id.backdropA);
        back = activity.findViewById(R.id.backdropB);
        // Dimming fades each picture rather than the frame through an offscreen layer.
        backdrop.forceHasOverlappingRendering(false);
        text = activity.findViewById(R.id.hero);
        title = activity.findViewById(R.id.heroTitle);
        subtitle = activity.findViewById(R.id.heroSubtitle);
        app = activity.findViewById(R.id.heroApp);
        badge = activity.findViewById(R.id.heroBadge);
        text.setAlpha(0f);
    }

    void schedule(Item h) {
        handler.removeCallbacks(update);
        if (h == null || h.key.equals(shownKey)) {
            pending = null;
            return;
        }
        pending = h;
        handler.postDelayed(update, DELAY_MS);
    }

    /** Composes a backdrop ahead, so moving along a strip crossfades without waiting. */
    void prefetch(Item h) {
        compose(h, null);
    }

    /** Fades the hero text, and {@code with}, in or out. */
    void setVisible(boolean show, View... with) {
        if (show == visible) return;
        visible = show;
        for (View v : with) fadeTo(v, show ? 1f : 0f);
        fadeTo(text, show ? 1f : 0f);
    }

    private void fadeTo(View v, float to) {
        v.animate().cancel();
        if (animations) v.animate().alpha(to).setDuration(CROSSFADE_MS).start();
        else v.setAlpha(to);
    }

    /** The strips below the first see the picture dimmed. */
    void dim(boolean dim) {
        fade(dim ? DIM : 1f);
    }

    /** The other tabs show only the page colour. */
    void fade(float to) {
        if (backdrop.getAlpha() == to) return;
        if (animations) backdrop.animate().alpha(to).setDuration(CROSSFADE_MS).start();
        else backdrop.setAlpha(to);
    }

    /** The text gives the Now playing card room beside it. */
    void narrow(boolean narrow) {
        int full = text.getResources().getDimensionPixelSize(R.dimen.hero_text_width);
        int width = full;
        if (narrow) {
            int screen = text.getResources().getDisplayMetrics().widthPixels;
            int card = text.getResources().getDimensionPixelSize(R.dimen.now_playing_width);
            width = Math.min(full, screen - Math.round(96 * density) - card - Math.round(24 * density));
        }
        for (View v : new View[]{(View) app.getParent(), title, subtitle}) {
            if (v.getLayoutParams().width == width) continue;
            v.getLayoutParams().width = width;
            v.requestLayout();
        }
    }

    /** The decoded pictures come back from the disk cache on demand. */
    void trim() {
        backdrops.evictAll();
    }

    void pause() {
        handler.removeCallbacks(update);
    }

    void close() {
        lookups.shutdownNow();
    }

    private void showPending() {
        if (pending == null) return;
        show(pending);
        shown.run();
    }

    private void show(Item h) {
        pending = null;
        shownKey = h.key;
        title.setText(h.title);
        subtitle.setText(h.subtitle);
        subtitle.setVisibility(h.subtitle != null ? View.VISIBLE : View.INVISIBLE);
        app.setText(h.appName);
        app.setVisibility(h.appName != null ? View.VISIBLE : View.INVISIBLE);
        badge.setImageDrawable(h.badge);
        badge.setVisibility(h.badge != null ? View.VISIBLE : View.INVISIBLE);
        float to = visible ? 1f : 0f;
        if (animations && visible) {
            text.setAlpha(0f);
            text.setTranslationY(6 * density);
            text.animate().alpha(to).translationY(0f).setDuration(reducedMotion ? 120 : 220).start();
        } else {
            text.animate().cancel();
            text.setAlpha(to);
        }
        // The previous backdrop stays until the new one is composed, then dissolves into it.
        compose(h, bitmap -> {
            if (h.key.equals(shownKey)) crossfade(bitmap);
        });
    }

    private int headerScrim() {
        return Math.round(HEADER_SCRIM_DP * density);
    }

    /** Composes the backdrop on the image thread (or takes it from the cache) and hands it over on the main thread. */
    private void compose(Item h, Consumer<Bitmap> done) {
        int w = root.getWidth() > 0 ? root.getWidth() : root.getResources().getDisplayMetrics().widthPixels;
        int ht = root.getHeight() > 0 ? root.getHeight() : root.getResources().getDisplayMetrics().heightPixels;
        Bitmap cached = backdrops.get(h.key);
        if (cached != null) {
            if (done != null) {
                done.accept(cached);
                if (!fullBleed.contains(h.key)) lookUp(h, w, ht);
            }
            return;
        }
        images.run(() -> {
            if (backdrops.get(h.key) == null) {
                Bitmap picture = h.picture != null ? h.picture : h.art != null ? images.decodeLarge(h.art, w) : null;
                try {
                    backdrops.put(h.key, Backdrop.compose(picture, h.fallback, h.title, w, ht, headerScrim(), rtl));
                } catch (RuntimeException | OutOfMemoryError e) {
                    return;
                }
                if (picture != null && Backdrop.fillsScreen(picture, w, ht)) fullBleed.add(h.key);
                else if (done != null) lookUp(h, w, ht);
            }
            Bitmap ready = backdrops.get(h.key);
            if (done != null && ready != null) handler.post(() -> done.accept(ready));
        });
    }

    /**
     * A show or game without a large landscape picture: asks TVmaze or Steam (Artwork, cached on disk) on its own
     * thread, so the cards keep loading, and crossfades to it while the hero still shows that card.
     */
    private void lookUp(Item h, int w, int ht) {
        if (h.showTitle == null && h.gameName == null) return;
        Context ctx = root.getContext().getApplicationContext();
        lookups.execute(() -> {
            // Fast scrolling queues many cards; only the one still in the hero is worth a request.
            if (!h.key.equals(shownKey)) return;
            File file = h.showTitle != null ? Artwork.backdropForShow(ctx, h.showTitle)
                    : Artwork.backdropForGame(ctx, h.gameName);
            if (file == null) return;
            images.run(() -> {
                Bitmap picture = images.decodeLarge(file.getPath(), w);
                if (picture == null || !Backdrop.fillsScreen(picture, w, ht)) return;
                Bitmap composed;
                try {
                    composed = Backdrop.compose(picture, h.fallback, h.title, w, ht, headerScrim(), rtl);
                } catch (RuntimeException | OutOfMemoryError e) {
                    return;
                }
                backdrops.put(h.key, composed);
                fullBleed.add(h.key);
                handler.post(() -> {
                    if (h.key.equals(shownKey)) crossfade(composed);
                });
            });
        });
    }

    /** Dissolves the old backdrop into the new one; the page colour under it follows. */
    private void crossfade(Bitmap composed) {
        ImageView incoming = back, outgoing = front;
        if (outgoing.getTag() == composed) return;
        front = incoming;
        back = outgoing;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.setImageBitmap(composed);
        incoming.setTag(composed);
        root.setBackgroundColor(composed.getPixel(composed.getWidth() / 2, composed.getHeight() - 1));
        if (animations) {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(CROSSFADE_MS).start();
            outgoing.animate().alpha(0f).setDuration(CROSSFADE_MS).withEndAction(() -> {
                if (back == outgoing) {
                    outgoing.setImageDrawable(null);
                    outgoing.setTag(null);
                }
            }).start();
        } else {
            incoming.setAlpha(1f);
            outgoing.setAlpha(0f);
        }
    }
}
