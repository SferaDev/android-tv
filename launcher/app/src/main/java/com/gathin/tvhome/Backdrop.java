package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The hero's backdrop, composed off the main thread into one screen-sized bitmap: the picture, its scrims and the
 * page colour drawn once, so a frame draws a single texture. Only a large landscape picture is shown, full screen;
 * anything smaller (a cover, an app banner or icon) gives its colours to a dark gradient instead of being stretched.
 */
final class Backdrop {
    /** Upscaling a full-bleed picture beyond this would soften it; below it the designed layout is used. */
    private static final float MAX_UPSCALE = 1.25f;
    /** Where the crop window sits across a wider picture: left of centre, so the subject lands right of the text. */
    private static final float CROP_BIAS = 0.3f;
    /** A slight zoom even when the shape matches, so the bias has room to move the subject right. */
    private static final float ZOOM = 1.06f;

    private Backdrop() { }

    /**
     * @param picture decoded artwork or cover, or null
     * @param fallback the app's banner or icon (a private copy), whose colours stand in when there is no picture
     * @param name for the gradient when there is neither
     * @param header how far down the header's scrim reaches, in pixels
     */
    static Bitmap compose(Bitmap picture, Drawable fallback, String name, int w, int h, int header, boolean rtl) {
        int[] tones = picture != null ? tones(sample(picture)) : fallback != null ? tones(sample(fallback))
                : HomeCards.gradientFor(name);
        int page = page(tones[0]);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (picture != null && fillsScreen(picture, w, h)) {
            drawCropped(canvas, picture, w, h, rtl, paint);
        } else {
            designed(canvas, tones, w, h, rtl, paint);
        }
        scrims(canvas, page, w, h, header, rtl, paint);
        return out;
    }

    /** The dark, slightly tinted colour the page fades into under the strips. */
    static int page(int tone) {
        float[] hsv = new float[3];
        Color.colorToHSV(tone, hsv);
        return Color.HSVToColor(new float[]{hsv[0], Math.min(0.5f, hsv[1]), Math.min(0.1f, hsv[2])});
    }

    static boolean fillsScreen(Bitmap picture, int w, int h) {
        float aspect = picture.getWidth() / (float) picture.getHeight();
        return aspect >= 1.3f && picture.getWidth() * MAX_UPSCALE >= w;
    }

    private static void drawCropped(Canvas canvas, Bitmap picture, int w, int h, boolean rtl, Paint paint) {
        float scale = Math.max(w / (float) picture.getWidth(), h / (float) picture.getHeight()) * ZOOM;
        float sw = w / scale, sh = h / scale;
        float bias = rtl ? 1f - CROP_BIAS : CROP_BIAS;
        float left = (picture.getWidth() - sw) * bias, top = (picture.getHeight() - sh) * 0.4f;
        Rect src = new Rect(Math.round(left), Math.round(top), Math.round(left + sw), Math.round(top + sh));
        canvas.drawBitmap(picture, src, new Rect(0, 0, w, h), paint);
    }

    /** A deep gradient of the picture's colours with a soft vignette; nothing is drawn of a picture too small to fill the screen. */
    private static void designed(Canvas canvas, int[] tones, int w, int h, boolean rtl, Paint paint) {
        paint.setShader(new LinearGradient(rtl ? 0 : w, 0, rtl ? w : 0, h, tones[0], tones[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * (rtl ? 0.3f : 0.7f), h * 0.4f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x99000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    /**
     * Text side: about 85% to clear over the first 60% of the width. Bottom 40%: up into the page colour. Top: 70% to
     * clear under the header, eased so no band shows.
     */
    private static void scrims(Canvas canvas, int page, int w, int h, int header, boolean rtl, Paint paint) {
        int rgb = page & 0xFFFFFF;
        paint.setShader(new LinearGradient(0, 0, 0, header,
                new int[]{0xB3000000 | rgb, 0x8C000000 | rgb, 0x4D000000 | rgb, 0x1A000000 | rgb, rgb},
                new float[]{0f, 0.25f, 0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, header, paint);
        float edge = rtl ? w : 0, end = rtl ? w * 0.4f : w * 0.6f;
        paint.setShader(new LinearGradient(edge, 0, end, 0,
                new int[]{0xD9000000 | rgb, 0xA6000000 | rgb, 0x59000000 | rgb, 0x1A000000 | rgb, rgb},
                new float[]{0f, 0.3f, 0.6f, 0.85f, 1f}, Shader.TileMode.CLAMP));
        if (rtl) canvas.drawRect(w * 0.4f, 0, w, h, paint);
        else canvas.drawRect(0, 0, w * 0.6f, h, paint);
        float top = h * 0.6f;
        paint.setShader(new LinearGradient(0, top, 0, h,
                new int[]{rgb, 0x40000000 | rgb, 0xA6000000 | rgb, 0xE6000000 | rgb, 0xFF000000 | rgb},
                new float[]{0f, 0.25f, 0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, top, w, h, paint);
        paint.setShader(null);
    }

    private static int[] sample(Bitmap picture) {
        Bitmap small = Bitmap.createScaledBitmap(picture, 12, 12, true);
        int[] px = new int[144];
        small.getPixels(px, 0, 12, 0, 0, 12, 12);
        if (small != picture) small.recycle();
        return px;
    }

    private static int[] sample(Drawable d) {
        Bitmap small = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
        Rect saved = new Rect(d.getBounds());
        d.setBounds(0, 0, 12, 12);
        d.draw(new Canvas(small));
        d.setBounds(saved);
        int[] px = new int[144];
        small.getPixels(px, 0, 12, 0, 0, 12, 12);
        small.recycle();
        return px;
    }

    /** The most vivid colour of the sample as a rich tone, and a deep shade of it. */
    static int[] tones(int[] px) {
        float[] hsv = new float[3];
        float best = -1f, hue = 220f, sat = 0f;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            float score = hsv[1] * Math.min(1f, hsv[2] * 1.5f);
            if (score > best) {
                best = score;
                hue = hsv[0];
                sat = hsv[1];
            }
        }
        if (best < 0.15f) sat = 0.08f;   // a grey picture: a neutral, barely tinted gradient
        return new int[]{
                Color.HSVToColor(new float[]{hue, Math.min(0.75f, sat), 0.42f}),
                Color.HSVToColor(new float[]{(hue + 25f) % 360f, Math.min(0.8f, sat), 0.1f})};
    }
}
