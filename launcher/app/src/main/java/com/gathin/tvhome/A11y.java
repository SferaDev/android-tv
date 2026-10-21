package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.provider.Settings;
import android.view.View;

/** TalkBack and high-contrast helpers shared by the cards and the header. */
final class A11y {
    private A11y() { }

    /** "title, app" for a card; the title alone when there is no app or it only repeats the title. */
    static String cardLabel(String title, String app) {
        String t = title == null ? "" : title;
        if (app == null || app.isEmpty() || app.equals(t)) return t;
        return t.isEmpty() ? app : t + ", " + app;
    }

    /** The system's high text contrast switch (Settings > Accessibility). */
    static boolean highContrast(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), "high_text_contrast_enabled", 0) == 1;
    }

    /** Swaps a card's thin focus ring for a thick one while high text contrast is on. */
    static void strengthenFocus(View card) {
        if (!highContrast(card.getContext()) || card.getForeground() instanceof Ring) return;
        card.setForeground(new Ring(card.getResources().getDisplayMetrics().density));
    }

    private static final class Ring extends StateListDrawable {
        Ring(float density) {
            GradientDrawable ring = new GradientDrawable();
            ring.setStroke(Math.round(6 * density), 0xFFFFFFFF);
            ring.setCornerRadius(12 * density);
            addState(new int[]{android.R.attr.state_focused}, ring);
            addState(new int[0], new GradientDrawable());
        }
    }
}
