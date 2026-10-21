package com.gathin.tvhome;

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

/** Input and channel cards, for the Recent TV strip and the TV tab. Built off the main thread, then handed over. */
final class TvCards {
    private TvCards() { }

    static final class Tile {
        final TvInputs.Input input;
        final TvChannels.Channel channel;
        final String title;
        /** Second line: what is on, or that it was used last; null for none. */
        final String subtitle;
        /** The input the launcher switched to last: the platform does not say which one is on. */
        final boolean current;

        Tile(TvInputs.Input input, String subtitle, boolean current) {
            this.input = input;
            this.channel = null;
            this.title = input.label;
            this.subtitle = subtitle;
            this.current = current;
        }

        Tile(TvChannels.Channel channel, String subtitle) {
            this.input = null;
            this.channel = channel;
            this.title = title(channel);
            this.subtitle = subtitle;
            this.current = false;
        }

        /** HiddenItems key, shared with Recent TV. */
        String key() {
            return input != null ? RecentTv.key(RecentTv.INPUT, input.id)
                    : RecentTv.key(RecentTv.CHANNEL, String.valueOf(channel.id));
        }

        int icon() {
            if (channel != null || input.isTuner()) return R.drawable.ic_livetv;
            return input.isHdmi() ? R.drawable.ic_input : R.drawable.ic_av;
        }
    }

    /** "7 Channel name"; the number alone or the name alone when the other is blank. */
    static String title(TvChannels.Channel channel) {
        String number = channel.number.trim(), name = channel.name.trim();
        if (number.isEmpty()) return name;
        return name.isEmpty() ? number : number + "  " + name;
    }

    static final class Adapter extends CardRow.Adapter<Adapter.Holder> {
        private final Consumer<Tile> open;
        private final boolean grid;
        private List<Tile> items = new ArrayList<>();

        Adapter(int row, CardRow.Listener listener, Consumer<Tile> open, boolean grid, Config cfg) {
            super(row, listener, cfg);
            this.open = open;
            this.grid = grid;
        }

        void setItems(List<Tile> tiles) {
            items = tiles;
            notifyDataSetChanged();
        }

        Tile tileAt(int position) {
            return items.get(position);
        }

        @Override
        public int getItemViewType(int position) {
            return grid ? R.layout.item_tv : -R.layout.item_tv;
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).key().hashCode();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tv, parent, false);
            if (grid) v.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Tile t = items.get(position);
            sync(h.card);
            h.card.setContentDescription(A11y.cardLabel(t.title, t.subtitle));
            h.title.setText(t.title);
            h.subtitle.setText(t.subtitle);
            h.subtitle.setVisibility(t.subtitle != null ? View.VISIBLE : View.GONE);
            h.icon.setImageResource(t.icon());
            h.current.setVisibility(t.current ? View.VISIBLE : View.GONE);
            h.card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, HomeCards.gradientFor(t.title)));
            h.card.setOnClickListener(v -> open.accept(t));
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View card;
            final ImageView icon;
            final View current;
            final TextView title;
            final TextView subtitle;

            Holder(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                icon = v.findViewById(R.id.icon);
                current = v.findViewById(R.id.current);
                title = v.findViewById(R.id.title);
                subtitle = v.findViewById(R.id.subtitle);
                wire(this, card);
            }
        }
    }
}
