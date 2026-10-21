package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * App banner cards, for the Recent apps row and the Apps tab's grid. {@link #load} builds the list off the main
 * thread, alphabetically; a module card the owner chose to show stands in for its app's tile.
 */
public class AppAdapter extends CardRow.Adapter<AppAdapter.Holder> {

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the focused tile in the grid: the label, plus the caption for a card. */
        final String name;
        /** Launches with the leanback category, like the stock launcher, so a running app resumes. */
        final boolean leanback;
        /** The action the tile sends instead of the start screen (config.xml, launch_actions); null for none. */
        final String launchAction;

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption,
              boolean leanback) {
            this(kind, label, component, banner, icon, caption, leanback, null);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption,
              boolean leanback, String launchAction) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " · " + caption;
            this.leanback = leanback;
            this.launchAction = launchAction;
        }
    }

    private final Context ctx;
    private final int tileWidth;
    /** Grid tiles fill their column and show the name under the focused one; row cards do neither. */
    private final boolean grid;
    private final boolean advancedEffects;
    private List<Entry> items = new ArrayList<>();
    /** Grid tiles of apps hidden from Home get an eye-off badge. */
    private Predicate<Entry> hiddenFromHome = e -> false;
    /** Switches to a TV input for a tile whose launch_actions entry is "input:<id>". */
    private Consumer<String> inputOpener;

    AppAdapter(Context ctx, int row, CardRow.Listener listener, int tileWidthPx, boolean grid, Config cfg) {
        super(row, listener, cfg);
        this.ctx = ctx;
        this.tileWidth = tileWidthPx;
        this.grid = grid;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
    }

    void setInputOpener(Consumer<String> opener) {
        inputOpener = opener;
    }

    void setItems(List<Entry> entries, Predicate<Entry> hidden) {
        items = entries;
        hiddenFromHome = hidden;
        notifyDataSetChanged();
    }

    Entry entryAt(int position) {
        return items.get(position);
    }

    /**
     * Every app for the grid. Blocking (PackageManager and banner loads); call off the main thread. A shown module
     * card stands in for every tile of its package; hidden_apps wins over a card.
     */
    static List<Entry> load(Context ctx, Config cfg, List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        Set<String> hidden = new HashSet<>(cfg.hiddenApps);
        Set<String> added = LibraryApps.load(ctx);
        Set<String> removed = LibraryApps.loadRemoved(ctx);
        if (!added.isEmpty()) {
            Set<String> seen = new HashSet<>(tvPackages);
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                if (ri.activityInfo != null && added.contains(ri.activityInfo.packageName)
                        && seen.add(ri.activityInfo.packageName)) found.add(ri);
            }
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        Set<String> iconTiles = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.icon_tiles)));
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !ri.activityInfo.exported
                    || !LibraryApps.visible(tvPackages.contains(pkg), hidden.contains(pkg), added.contains(pkg), removed.contains(pkg))) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
            // redraw the tile name as something else (#117).
            String label = card != null ? card.label : Cards.cleanLabel(
                    cfg.appLabels.containsKey(pkg) ? cfg.appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = bundledBanner(ctx, cfg.appBanners.get(pkg));
            if (banner == null && !iconTiles.contains(pkg)) banner = loadBanner(ctx, pm, ri);
            fresh.add(new Entry(card != null ? Entry.CARD : Entry.APP, label, component, banner,
                    ri.loadIcon(pm), card != null ? card.caption : null, tvPackages.contains(pkg),
                    cfg.launchActions.get(pkg)));
        }
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        fresh.sort((a, b) -> collator.compare(a.label, b.label));
        if (cfg.showHomeSettingsShortcut
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, false));
        }
        return fresh;
    }

    /** One app that is not in the grid (a phone app, say), for a row; null when it has no launcher entry. Blocking. */
    static Entry loadEntry(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg), 0);
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
                boolean iconTile = Arrays.asList(ctx.getResources().getStringArray(R.array.icon_tiles)).contains(pkg);
                return new Entry(Entry.APP, label, new ComponentName(pkg, ri.activityInfo.name),
                        iconTile ? null : loadBanner(ctx, pm, ri), ri.loadIcon(pm), null,
                        Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category));
            }
        }
        return null;
    }

    /** A banner_* drawable bundled with the launcher, or null for none or a name that is not one. */
    private static Drawable bundledBanner(Context ctx, String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private static Drawable loadBanner(Context ctx, PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        int h = Math.round(tileWidth * 9f / 16f);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = tileWidth;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        if (grid) {
            name.getLayoutParams().width = tileWidth;
        } else {
            name.setVisibility(View.GONE);
            v.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        Holder holder = new Holder(v);
        wire(holder, holder.card, holder.name);
        return holder;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        sync(h.card, h.name);
        // A fully opaque banner covers the whole card (centerCrop), so the card's own fill under it is drawn for
        // nothing on every frame; keep it under banners with transparent parts and under icon tiles.
        h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setContentDescription(e.label);
        h.card.setOnClickListener(v -> launch(ctx, e, advancedEffects, inputOpener));
        h.hidden.setVisibility(grid && hiddenFromHome.test(e) ? View.VISIBLE : View.GONE);
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    @SuppressWarnings("deprecation")
    static void launch(Context ctx, Entry e, boolean advancedEffects, Consumer<String> inputOpener) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        // launch_actions "input:<id>": switch to that TV input instead of opening an app.
        String inputId = LaunchActions.inputId(e.launchAction);
        if (inputId != null && inputOpener != null) {
            inputOpener.accept(inputId);
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (e.leanback) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (e.launchAction != null) {
            Intent byAction = LaunchActions.intent(e.pkg, e.launchAction)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            ctx.startActivity(i);
            if (advancedEffects && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Distinct per kind of card, so the strips and grids can share one view pool. */
    @Override
    public int getItemViewType(int position) {
        return grid ? R.layout.item_app : -R.layout.item_app;
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: the dark card behind an icon tile, or behind a banner that has transparent parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final View hidden;

        Holder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            hidden = v.findViewById(R.id.hiddenBadge);
        }
    }
}
