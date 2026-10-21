package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Games tab while it has no games: find a PC on the network, pair with it over its PIN, or wait for its list. */
final class GamesSetup {
    /** Right after pairing the host can take a moment to list its apps: the first syncs come quickly. */
    private static final long[] PAIRED_SYNC_MS = {2_000, 5_000, 10_000};
    private static final long POLL_MS = 500;

    interface Host {
        /** The Games tab is open. */
        boolean shown();

        boolean hasGames();

        boolean resumed();

        Config config();

        /** The games PC was chosen or forgotten: read the config again. */
        void hostChanged();

        /** Show the games as the last load left them. */
        void reload();

        void focusContent();
    }

    private final Activity activity;
    private final Host host;
    private final View tab;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GameHosts hosts;
    private final View panel;
    private final TextView title, pin, body;
    private final LinearLayout rows;
    private final Runnable poll = this::poll;
    private final Runnable pairedSync = this::syncAfterPairing;
    private int pairedSyncs = PAIRED_SYNC_MS.length;
    /** What the pairing poll last showed, so the panel redraws only on a change. */
    private String pairingState;

    /** Up from the first row goes to {@code tab}. */
    GamesSetup(Activity activity, View tab, Host host) {
        this.activity = activity;
        this.tab = tab;
        this.host = host;
        density = activity.getResources().getDisplayMetrics().density;
        hosts = new GameHosts(activity, this::refresh);
        panel = activity.findViewById(R.id.gamesSetup);
        title = activity.findViewById(R.id.gamesSetupTitle);
        pin = activity.findViewById(R.id.gamesSetupPin);
        body = activity.findViewById(R.id.gamesSetupBody);
        rows = activity.findViewById(R.id.gamesSetupRows);
    }

    View view() {
        return panel;
    }

    /** The PC games stream from, or null before one is chosen. */
    private String gamesHost() {
        String address = host.config().gamestreamHost;
        return address.isEmpty() ? null : address;
    }

    /** The tab opened: pair while it shows the PIN, or look for covers still missing. */
    void enter() {
        if (!SteamGames.paired(activity)) startPairing();
        else HomeData.refresh(activity, host::reload);
    }

    /** Only while the tab shows the PIN; leaving the tab abandons it. */
    void startPairing() {
        String address = gamesHost();
        if (address == null || SteamGames.paired(activity)) return;
        GameStreamPairing.start(SteamGames.identityDir(activity), address);
        pairingState = null;
        handler.removeCallbacks(poll);
        poll();
    }

    void stopPairing() {
        handler.removeCallbacks(poll);
        GameStreamPairing.stop();
    }

    void pause() {
        handler.removeCallbacks(poll);
        hosts.stop();
    }

    void destroy() {
        handler.removeCallbacks(pairedSync);
    }

    private void chooseHost(String address) {
        if (!GameHosts.choose(activity, address)) {
            Toast.makeText(activity, R.string.settings_not_saved, Toast.LENGTH_SHORT).show();
            return;
        }
        host.hostChanged();
        if (SteamGames.paired(activity)) refreshGames();
        else startPairing();
    }

    /** Redraws the panel while pairing changes state; once paired, reads the PC's games. */
    private void poll() {
        String now = GameStreamPairing.state(SteamGames.paired(activity));
        if (!now.equals(pairingState)) {
            pairingState = now;
            if (SteamGames.paired(activity)) {
                pairedSyncs = 0;
                handler.removeCallbacks(pairedSync);
                refreshGames();
                handler.postDelayed(pairedSync, PAIRED_SYNC_MS[0]);
                return;
            }
            refresh();
        }
        if (GameStreamPairing.running()) handler.postDelayed(poll, POLL_MS);
    }

    /** Syncs again on the PAIRED_SYNC_MS steps until the games are in. */
    private void syncAfterPairing() {
        if (activity.isDestroyed() || pairedSyncs >= PAIRED_SYNC_MS.length) return;
        if (host.hasGames()) {
            pairedSyncs = PAIRED_SYNC_MS.length;
            refresh();
            return;
        }
        refreshGames();
        pairedSyncs++;
        if (pairedSyncs < PAIRED_SYNC_MS.length) {
            handler.postDelayed(pairedSync, PAIRED_SYNC_MS[pairedSyncs] - PAIRED_SYNC_MS[pairedSyncs - 1]);
        } else {
            refresh();
        }
    }

    private void refreshGames() {
        SteamGames.syncSoon();
        HomeData.refresh(activity, host::reload);
        refresh();
    }

    private void forgetHost() {
        stopPairing();
        if (!GameHosts.forget(activity)) Toast.makeText(activity, R.string.settings_not_saved, Toast.LENGTH_SHORT).show();
        host.hostChanged();
        refresh();
    }

    /** Shown on the Games tab while it has no games; finds PCs only while it shows and none is chosen. */
    void refresh() {
        boolean shown = host.shown() && !host.hasGames();
        panel.setVisibility(shown ? View.VISIBLE : View.GONE);
        String address = gamesHost();
        if (shown && address == null && host.resumed()) hosts.start();
        else hosts.stop();
        if (host.hasGames()) return;
        View focused = activity.getCurrentFocus();
        boolean hadFocus = focused != null && focused.getParent() == rows;
        rows.removeAllViews();
        pin.setVisibility(View.GONE);
        if (address == null) {
            title.setText(R.string.games_setup_title);
            body.setText(R.string.games_setup_body);
            Map<String, String> found = hosts.found();
            if (found.isEmpty() && hosts.timedOut()) {
                // Nothing answered: typing the address is the way forward, so it comes first.
                body.setText(R.string.games_none_found);
                addRow(activity.getString(R.string.games_enter_address), this::enterAddress);
                addRow(activity.getString(R.string.games_try_again), () -> {
                    hosts.stop();
                    refresh();
                });
            } else {
                if (found.isEmpty()) addSearchingRow(R.string.games_searching);
                for (Map.Entry<String, String> pc : found.entrySet()) {
                    addRow(pc.getKey() + "  ·  " + pc.getValue(), () -> chooseHost(pc.getValue()));
                }
                addRow(activity.getString(R.string.games_enter_address), this::enterAddress);
            }
        } else if (!SteamGames.paired(activity)) {
            String code = GameStreamPairing.pin();
            title.setText(R.string.games_pair_title);
            body.setText(activity.getString(R.string.games_pin_steps, address));
            if (code != null) {
                pin.setText(code);
                pin.setVisibility(View.VISIBLE);
            } else if (!GameStreamPairing.running() && GameStreamPairing.lastResult() != null) {
                body.setText(GameStreamPairing.lastResult() == GameStreamClient.PairResult.PIN_WRONG
                        ? R.string.settings_pair_wrong_pin : R.string.settings_pair_failed);
            }
            if (!GameStreamPairing.running()) addRow(activity.getString(R.string.games_try_again), this::startPairing);
            addRow(activity.getString(R.string.games_other_pc), this::forgetHost);
        } else {
            boolean loading = HomeData.refreshing() || pairedSyncs < PAIRED_SYNC_MS.length;
            title.setText(loading ? R.string.games_loading : R.string.games_none);
            body.setText(loading ? "" : activity.getString(R.string.games_none_hint));
            if (loading) addSearchingRow(R.string.games_loading);
        }
        if (hadFocus) host.focusContent();
    }

    private void addSearchingRow(int text) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(16 * density);
        row.setPadding(pad, Math.round(12 * density), pad, Math.round(12 * density));
        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xCCFFFFFF));
        int size = Math.round(24 * density);
        row.addView(spinner, new LinearLayout.LayoutParams(size, size));
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(0xCCFFFFFF);
        label.setTextSize(15);
        label.setTypeface(activity.getResources().getFont(R.font.outfit_regular));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(12 * density));
        row.addView(label, lp);
        rows.addView(row);
    }

    /** A focusable row; Up from the first goes to the tab, the ends hold. */
    private void addRow(CharSequence text, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(text);
        row.setOnClickListener(v -> action.run());
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            List<View> focusable = focusableRows();
            int i = focusable.indexOf(v);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (i <= 0) tab.requestFocus();
                    else focusable.get(i - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (i >= 0 && i < focusable.size() - 1) focusable.get(i + 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return false;
            }
        });
        rows.addView(row);
    }

    /** Focuses the first row; false when there is none. */
    boolean focusFirst() {
        List<View> focusable = focusableRows();
        if (focusable.isEmpty()) return false;
        focusable.get(0).requestFocus();
        return true;
    }

    private List<View> focusableRows() {
        List<View> focusable = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (v.isFocusable()) focusable.add(v);
        }
        return focusable;
    }

    /** A PC by name or IP address, typed with the TV's keyboard. */
    private void enterAddress() {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setHint(R.string.games_address_hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        int pad = Math.round(24 * density);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        box.addView(field);
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.games_enter_address)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String address = field.getText().toString().trim();
                    if (GameHosts.valid(address)) chooseHost(address);
                    else if (!address.isEmpty()) {
                        Toast.makeText(activity, R.string.settings_games_address_bad, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        field.requestFocus();
    }
}
