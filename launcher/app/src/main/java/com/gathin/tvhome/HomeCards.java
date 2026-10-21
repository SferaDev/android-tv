package com.gathin.tvhome;

import android.graphics.Color;

/** What the Home cards and the hero share. */
final class HomeCards {
    private HomeCards() { }

    /** Two dark colours picked from the name, so each game without art keeps its own gradient. */
    static int[] gradientFor(String name) {
        float hue = Math.floorMod(name.hashCode(), 360);
        return new int[]{
                Color.HSVToColor(new float[]{hue, 0.6f, 0.5f}),
                Color.HSVToColor(new float[]{(hue + 40f) % 360f, 0.7f, 0.22f})};
    }
}
