package com.gathin.tvhome;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The Continue watching cards, for the Home strips. Built off the main thread, then handed to these adapters. */
final class HomeCards {
    private HomeCards() { }

    static final class Watch {
        final WatchItem item;
        final String title;
        /** Second line: the episode or artist, or the app's name; null for none. */
        final String subtitle;
        final String appLabel;
        final Drawable banner;
        final Drawable icon;

        Watch(WatchItem item, String appLabel, Drawable banner, Drawable icon) {
            this.item = item;
            this.appLabel = appLabel;
            this.banner = banner;
            this.icon = icon;
            if (item.title != null) {
                title = item.title;
                subtitle = item.subtitle != null ? item.subtitle : appLabel;
            } else {
                title = appLabel;
                subtitle = null;
            }
        }
    }

    /** Two dark colours picked from the name, so each game without art keeps its own gradient. */
    static int[] gradientFor(String name) {
        float hue = Math.floorMod(name.hashCode(), 360);
        return new int[]{
                Color.HSVToColor(new float[]{hue, 0.6f, 0.5f}),
                Color.HSVToColor(new float[]{(hue + 40f) % 360f, 0.7f, 0.22f})};
    }

    static final class WatchAdapter extends CardRow.Adapter<WatchAdapter.Holder> {
        private final ImageCache images;
        private final Consumer<Watch> open;
        private List<Watch> items = new ArrayList<>();

        WatchAdapter(int row, CardRow.Listener listener, ImageCache images, Consumer<Watch> open, Config cfg) {
            super(row, listener, cfg);
            this.images = images;
            this.open = open;
        }

        void setItems(List<Watch> watches) {
            items = watches;
            notifyDataSetChanged();
        }

        Watch watchAt(int position) {
            return items.get(position);
        }

        @Override
        public int getItemViewType(int position) {
            return R.layout.item_watch;
        }

        @Override
        public long getItemId(int position) {
            WatchItem w = items.get(position).item;
            return (w.pkg + '\n' + w.title).hashCode();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_watch, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Watch w = items.get(position);
            sync(h.card);
            h.title.setText(w.title);
            h.subtitle.setText(w.subtitle);
            h.subtitle.setVisibility(w.subtitle != null ? View.VISIBLE : View.INVISIBLE);
            h.card.setContentDescription(A11y.cardLabel(w.title, w.appLabel));
            showFallback(h, w);
            h.badge.setImageDrawable(w.icon);
            h.badge.setVisibility(View.GONE);
            if (w.item.artwork != null) {
                images.load(w.item.artwork, h.card.getLayoutParams().width, h.art, () -> {
                    h.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    h.art.setPadding(0, 0, 0, 0);
                    h.badge.setVisibility(w.icon != null ? View.VISIBLE : View.GONE);
                });
            }
            float progress = w.item.progress;
            h.progress.setVisibility(progress >= 0f ? View.VISIBLE : View.GONE);
            if (progress >= 0f) {
                h.progressFill.getLayoutParams().width = Math.round(h.card.getLayoutParams().width * Math.min(1f, progress));
                h.progressFill.requestLayout();
            }
            h.card.setOnClickListener(v -> open.accept(w));
        }

        /** The app's banner, or its icon on the plain tile, until (or unless) artwork arrives. */
        private static void showFallback(Holder h, Watch w) {
            ImageCache.clear(h.art);
            if (w.banner != null) {
                h.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.art.setPadding(0, 0, 0, 0);
                h.art.setImageDrawable(w.banner);
            } else {
                int pad = Math.round(h.card.getLayoutParams().height * 0.3f);
                h.art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.art.setPadding(pad, pad, pad, pad);
                h.art.setImageDrawable(w.icon);
            }
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View card;
            final ImageView art;
            final ImageView badge;
            final TextView title;
            final TextView subtitle;
            final View progress;
            final View progressFill;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                art = v.findViewById(R.id.art);
                progress = v.findViewById(R.id.progress);
                progressFill = v.findViewById(R.id.progressFill);
                badge = v.findViewById(R.id.badge);
                title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle);
                wire(this, card);
            }
        }
    }
}
