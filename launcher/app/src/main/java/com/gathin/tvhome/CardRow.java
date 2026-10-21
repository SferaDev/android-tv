package com.gathin.tvhome;

import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

/** Focus, D-pad and menu wiring shared by every card on the home screen: the strips and the tab grids. */
final class CardRow {
    interface Listener {
        void onCardFocused(int row, int position);

        /** A D-pad direction on a card. Return true when handled; the activity handles them all. */
        boolean onCardKey(int row, int position, int keyCode);

        /** Hold OK or the Menu key on a card. */
        void onCardMenu(int row, int position, View card);
    }

    static final float FOCUS_SCALE = 1.08f;
    static final float RADIUS_DP = 12f;
    private static final float FOCUS_LIFT_DP = 6f;

    private CardRow() { }

    /** Rounds the card, springs it up a little and shows {@code focusOnly} views while focused, and routes its keys. */
    static void wire(RecyclerView.ViewHolder holder, View card, int row, Listener listener, boolean animations,
                     boolean reducedMotion, View... focusOnly) {
        float density = card.getResources().getDisplayMetrics().density;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), RADIUS_DP * density);
            }
        });
        card.setClipToOutline(true);
        SpringForce force = new SpringForce(1f)
                .setStiffness(reducedMotion ? 1500f : 700f)
                .setDampingRatio(reducedMotion ? 1f : 0.72f);
        SpringAnimation sx = new SpringAnimation(card, DynamicAnimation.SCALE_X).setSpring(force);
        SpringAnimation sy = new SpringAnimation(card, DynamicAnimation.SCALE_Y).setSpring(force);
        sx.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        sy.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            // The strip's position, not the part's: a See all card follows the cards in a ConcatAdapter.
            int pos = holder.getAbsoluteAdapterPosition();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (pos != RecyclerView.NO_POSITION && event.getRepeatCount() == 0) listener.onCardMenu(row, pos, v);
                return true;
            }
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                    && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            return pos == RecyclerView.NO_POSITION || listener.onCardKey(row, pos, keyCode);
        });
        card.setOnLongClickListener(v -> {
            int pos = holder.getAbsoluteAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onCardMenu(row, pos, v);
            return true;
        });
        card.setOnFocusChangeListener((v, focused) -> {
            // Above its neighbours while it grows over them; the lift deepens the shadow.
            holder.itemView.setTranslationZ(focused ? 1f : 0f);
            float s = focused && animations ? FOCUS_SCALE : 1f;
            if (!animations) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            } else {
                sx.animateToFinalPosition(s);
                sy.animateToFinalPosition(s);
            }
            v.setTranslationZ(focused ? FOCUS_LIFT_DP * density : 0f);
            for (View label : focusOnly) label.setAlpha(focused ? 1f : 0f);
            int pos = holder.getAbsoluteAdapterPosition();
            if (focused && pos != RecyclerView.NO_POSITION) listener.onCardFocused(row, pos);
        });
    }

    /** For a rebind: a recycled or rebound view takes the focus state of the card it now shows. */
    static void syncFocus(View card, boolean animations, View... focusOnly) {
        A11y.strengthenFocus(card);
        boolean focused = card.isFocused();
        float s = focused && animations ? FOCUS_SCALE : 1f;
        card.setScaleX(s);
        card.setScaleY(s);
        for (View label : focusOnly) label.setAlpha(focused ? 1f : 0f);
    }

    /** What every card adapter shares: its row and listener, the motion settings and stable ids. */
    abstract static class Adapter<H extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<H> {
        final int row;
        final Listener listener;
        final boolean animations, reducedMotion;

        Adapter(int row, Listener listener, Config cfg) {
            this.row = row;
            this.listener = listener;
            this.animations = cfg.animations;
            this.reducedMotion = cfg.reducedMotion;
            setHasStableIds(true);
        }

        void wire(RecyclerView.ViewHolder holder, View card, View... focusOnly) {
            CardRow.wire(holder, card, row, listener, animations, reducedMotion, focusOnly);
        }

        void sync(View card, View... focusOnly) {
            syncFocus(card, animations, focusOnly);
        }
    }
}
