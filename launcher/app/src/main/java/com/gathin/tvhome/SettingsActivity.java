package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's own Settings pages, one per section (remote buttons, games, Home, appearance, permissions). Every choice goes to {@link UserSettings}; the result is RESULT_OK once anything changed, so Home
 * rebuilds. Steps inside a page (capture a button, pick an app, pair) are screens on a back stack: Back goes up one.
 */
public final class SettingsActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    static final String REMOTE = "remote", GAMES = "games", HOME = "home",
            APPEARANCE = "appearance", ADVANCED = "advanced";
    /** Section ids and titles, in the order Launcher settings lists them. */
    static final String[] PAGES = {REMOTE, GAMES, HOME, APPEARANCE, ADVANCED};
    static final int[] PAGE_TITLES = {R.string.settings_remote, R.string.settings_games, R.string.settings_home, R.string.settings_appearance, R.string.settings_advanced};

    private static final String[] BACKGROUNDS = {"#000000", "#14171C", "#0B1426", "#0E1A14", "#1A0F1E", "#1C140F"};
    private static final int[] BACKGROUND_NAMES = {R.string.settings_bg_black, R.string.settings_bg_graphite,
            R.string.settings_bg_midnight, R.string.settings_bg_forest, R.string.settings_bg_plum,
            R.string.settings_bg_espresso};
    /** Continue watching age limits in days; 0 is no limit. */
    private static final int[] WATCH_AGES = {7, 14, 30, 60, 90, 0};
    private static final int[] WATCH_AGE_NAMES = {R.string.settings_age_week, R.string.settings_age_2_weeks,
            R.string.settings_age_month, R.string.settings_age_2_months, R.string.settings_age_3_months,
            R.string.settings_age_none};
    private static final String CHECK = "✓";
    private static final int PERMISSION_REQUEST = 7;
    private static final int EXPORT_REQUEST = 8, IMPORT_REQUEST = 9;

    private TextView title, hint, big;
    private LinearLayout rows;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<Runnable> stack = new ArrayDeque<>();
    private Runnable screen;
    private Runnable rendered;
    /** Permissions whose own Settings screen was tried: from then on "Grant automatically" is offered too. */
    private final Set<String> tried = new HashSet<>();

    private final Runnable sections = this::sectionsPage;
    private final Runnable remote = this::remotePage;
    private final Runnable games = this::gamesPage;
    private final Runnable home = this::homePage;
    private final Runnable appearance = this::appearancePage;
    private final Runnable advanced = this::advancedPage;
    private final KeyCapture.Listener captured = code -> handler.post(() -> replace(() -> actionPage(code)));

    private final GameHosts hosts = new GameHosts(this, () -> {
        if (screen == games) render();
    });
    private final Runnable pairingPoll = this::pollPairing;
    private String pairingState;
    /** The page just rendered was the pairing one; any other abandons a pairing that waits for its PIN. */
    private boolean pairingShown;

    static Intent intent(Context ctx, String page) {
        return new Intent(ctx, SettingsActivity.class).putExtra(EXTRA_PAGE, page);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings_page);
        title = findViewById(R.id.pageTitle);
        hint = findViewById(R.id.pageHint);
        big = findViewById(R.id.pageBig);
        rows = findViewById(R.id.pageRows);
        screen = page(getIntent().getStringExtra(EXTRA_PAGE));
    }

    private Runnable page(String id) {
        if (REMOTE.equals(id)) return remote;
        if (GAMES.equals(id)) return games;
        if (HOME.equals(id)) return home;
        if (APPEARANCE.equals(id)) return appearance;
        if (ADVANCED.equals(id)) return advanced;
        return sections;
    }

    /** Also on return from the TV's Settings, so permission states are current. */
    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        KeyCapture.stop(captured);
        handler.removeCallbacks(pairingPoll);
    }

    @Override
    protected void onStop() {
        super.onStop();
        hosts.stop();
        if (pairingShown) GameStreamPairing.stop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return KeyCapture.offer(event) || super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (stack.isEmpty()) {
            super.onBackPressed();
            return;
        }
        screen = stack.pop();
        render();
    }

    private void go(Runnable next) {
        stack.push(screen);
        screen = next;
        render();
    }

    private void replace(Runnable next) {
        screen = next;
        render();
    }

    /** Back to a page further down the stack, or to it alone when it is not there. */
    private void backTo(Runnable target) {
        while (!stack.isEmpty() && screen != target) screen = stack.pop();
        screen = target;
        render();
    }

    private void render() {
        if (screen == null || rows == null) return;
        int focus = -1;
        if (screen == rendered) {
            View f = getCurrentFocus();
            for (int i = 0; f != null && i < rows.getChildCount(); i++) if (rows.getChildAt(i) == f) focus = i;
        }
        rendered = screen;
        KeyCapture.stop(captured);
        handler.removeCallbacks(pairingPoll);
        if (screen != games) hosts.stop();
        rows.removeAllViews();
        hint.setVisibility(View.GONE);
        big.setVisibility(View.GONE);
        pairingShown = false;
        screen.run();
        if (!pairingShown) GameStreamPairing.stop();
        rows.setVisibility(rows.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        // Same screen again: keep focus on the same place, or the nearest row after it.
        View target = null;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (!v.isFocusable()) continue;
            target = v;
            if (i >= focus) break;
        }
        if (target != null) target.requestFocus();
    }

    /** Home rebuilds on resume when Launcher settings' change count moved, however these pages were left. */
    private void markChanged() {
        setResult(RESULT_OK);
        SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void changed(boolean saved) {
        if (saved) markChanged();
        else Toast.makeText(this, R.string.settings_not_saved, Toast.LENGTH_LONG).show();
        render();
    }

    // ---- building blocks -----------------------------------------------------------------------------------

    private void header(int titleRes, int hintRes) {
        header(getString(titleRes), hintRes == 0 ? null : getString(hintRes));
    }

    private void header(String titleText, String hintText) {
        title.setText(titleText);
        if (hintText != null) {
            hint.setText(hintText);
            hint.setVisibility(View.VISIBLE);
        }
    }

    /** A row; with no click it is information only and is skipped by the D-pad. */
    private View row(CharSequence titleText, CharSequence summary, CharSequence value, Runnable click) {
        View v = getLayoutInflater().inflate(R.layout.item_setting, rows, false);
        ((TextView) v.findViewById(R.id.title)).setText(titleText);
        TextView s = v.findViewById(R.id.summary);
        if (summary != null && summary.length() > 0) {
            s.setText(summary);
            s.setVisibility(View.VISIBLE);
        }
        TextView val = v.findViewById(R.id.value);
        if (value != null && value.length() > 0) {
            val.setText(value);
            val.setVisibility(View.VISIBLE);
        }
        if (click == null) {
            v.setFocusable(false);
            v.setClickable(false);
        } else {
            v.setOnClickListener(x -> click.run());
        }
        rows.addView(v);
        return v;
    }

    private void label(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0x73FFFFFF);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        int pad = dp(16);
        t.setPadding(pad, dp(rows.getChildCount() == 0 ? 6 : 16), pad, dp(6));
        rows.addView(t);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.on : R.string.off);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Installed apps, A to Z; TV apps only when tvOnly, else also the phone apps the library lists. */
    private List<String> apps(boolean tvOnly) {
        PackageManager pm = getPackageManager();
        Set<String> pkgs = new LinkedHashSet<>();
        List<String> categories = new ArrayList<>();
        categories.add(Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (!tvOnly) categories.add(Intent.CATEGORY_LAUNCHER);
        for (String category : categories) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (!ri.activityInfo.packageName.equals(getPackageName())) pkgs.add(ri.activityInfo.packageName);
            }
        }
        List<String> sorted = new ArrayList<>(pkgs);
        Map<String, String> labels = new LinkedHashMap<>();
        for (String pkg : sorted) labels.put(pkg, appLabel(pkg));
        Collator collator = Collator.getInstance();
        sorted.sort((a, b) -> collator.compare(labels.get(a), labels.get(b)));
        return sorted;
    }

    // ---- sections ------------------------------------------------------------------------------------------

    private void sectionsPage() {
        header(R.string.launcher_settings, 0);
        for (int i = 0; i < PAGES.length; i++) {
            Runnable target = page(PAGES[i]);
            row(getString(PAGE_TITLES[i]), null, null, () -> go(target));
        }
    }

    // ---- remote buttons ------------------------------------------------------------------------------------

    private List<KeyMappings.Mapping> mappings() {
        Config cfg = Config.load(this);
        return KeyMappings.from(cfg.appKeys, cfg.settingsKeycodes, cfg.inputKeycodes, KeyEvent::keyCodeFromString);
    }

    private String keyLabel(int code) {
        return KeyMappings.label(KeyEvent.keyCodeToString(code), code);
    }

    private String describe(KeyMappings.Mapping m) {
        switch (m.action) {
            case APP: return getString(R.string.settings_action_app_named, appLabel(m.pkg));
            case TOGGLE_HOME: return getString(R.string.settings_action_toggle);
            case INPUTS: return getString(R.string.settings_action_inputs);
            default: return getString(R.string.settings_action_settings);
        }
    }

    private boolean keyServiceOn() {
        return Grants.serviceEnabled(this, InputKeyService.class) || Grants.serviceEnabled(this, HomeKeyService.class);
    }

    private void remotePage() {
        header(R.string.settings_remote, R.string.settings_remote_hint);
        if (!keyServiceOn()) {
            row(getString(R.string.settings_key_service), getString(R.string.settings_key_service_off),
                    onOff(false), () -> go(advanced));
        }
        for (KeyMappings.Mapping m : mappings()) {
            int code = m.keyCode;
            row(keyLabel(code), describe(m), null, () -> go(() -> mappingPage(code)));
        }
        row(getString(R.string.settings_add_button), null, null, () -> go(this::capturePage));
        Config cfg = Config.load(this);
        row(getString(R.string.settings_button_opens), getString(R.string.settings_button_opens_hint),
                getString(cfg.settingsKeyPanel ? R.string.settings_panel_quick : R.string.tv_settings),
                () -> changed(UserSettings.put(this, "settings_key_panel", !cfg.settingsKeyPanel)));
    }

    private void capturePage() {
        header(R.string.settings_capture, R.string.settings_capture_hint);
        if (!keyServiceOn()) row(getString(R.string.settings_capture_no_service), null, null, null);
        KeyCapture.start(captured);
    }

    private void mappingPage(int code) {
        KeyMappings.Mapping m = KeyMappings.find(mappings(), code);
        if (m == null) {
            backTo(remote);
            return;
        }
        header(keyLabel(code), describe(m));
        row(getString(R.string.settings_change_action), null, null, () -> go(() -> actionPage(code)));
        row(getString(R.string.settings_remove_button), null, null, () -> saveMappings(KeyMappings.remove(mappings(), code)));
    }

    private void actionPage(int code) {
        header(keyLabel(code), getString(R.string.settings_action_hint));
        KeyMappings.Mapping current = KeyMappings.find(mappings(), code);
        KeyMappings.Action now = current == null ? null : current.action;
        row(getString(R.string.settings_action_app), current != null && now == KeyMappings.Action.APP
                ? appLabel(current.pkg) : null, now == KeyMappings.Action.APP ? CHECK : null,
                () -> go(() -> appPickerPage(code)));
        String stock = stockLauncher();
        if (stock != null) {
            row(getString(R.string.settings_action_toggle), appLabel(stock),
                    now == KeyMappings.Action.TOGGLE_HOME ? CHECK : null,
                    () -> put(new KeyMappings.Mapping(code, KeyMappings.Action.TOGGLE_HOME, stock)));
        }
        row(getString(R.string.settings_action_inputs), null, now == KeyMappings.Action.INPUTS ? CHECK : null,
                () -> put(new KeyMappings.Mapping(code, KeyMappings.Action.INPUTS, null)));
        row(getString(R.string.settings_action_settings), null, now == KeyMappings.Action.SETTINGS ? CHECK : null,
                () -> put(new KeyMappings.Mapping(code, KeyMappings.Action.SETTINGS, null)));
    }

    private void appPickerPage(int code) {
        header(keyLabel(code), getString(R.string.settings_pick_app));
        KeyMappings.Mapping current = KeyMappings.find(mappings(), code);
        for (String pkg : apps(false)) {
            boolean chosen = current != null && current.action == KeyMappings.Action.APP && pkg.equals(current.pkg);
            row(appLabel(pkg), null, chosen ? CHECK : null,
                    () -> put(new KeyMappings.Mapping(code, KeyMappings.Action.APP, pkg)));
        }
    }

    private void put(KeyMappings.Mapping mapping) {
        saveMappings(KeyMappings.put(mappings(), mapping));
    }

    private void saveMappings(List<KeyMappings.Mapping> next) {
        List<String> appKeys = KeyMappings.appKeys(next);
        // Scancode entries have no row here; keep them as they are.
        for (String entry : Config.load(this).appKeys) if (entry.startsWith("scan:")) appKeys.add(entry);
        boolean saved = UserSettings.edit(this, root -> {
            JSONObject c = root.getJSONObject(UserSettings.CONFIG);
            c.put("app_keys", UserSettings.json(appKeys));
            c.put("settings_key_keycodes", UserSettings.json(KeyMappings.settingsKeycodes(next)));
            c.put("input_key_keycodes", UserSettings.json(KeyMappings.inputKeycodes(next)));
        });
        if (saved) markChanged();
        else Toast.makeText(this, R.string.settings_not_saved, Toast.LENGTH_LONG).show();
        backTo(remote);
    }

    /** The stock home this launcher replaced, also while it is switched off. */
    private String stockLauncher() {
        String stock = StockLauncher.pick(CrashGuard.homeCandidates(this), getPackageName());
        if (stock != null) return stock;
        // A launcher switched off with pm disable-user may be missing from the query.
        for (String known : StockLauncher.KNOWN) {
            try {
                getPackageManager().getPackageInfo(known, PackageManager.MATCH_DISABLED_COMPONENTS);
                return known;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    // ---- games ---------------------------------------------------------------------------------------------

    private void gamesPage() {
        header(R.string.settings_games, R.string.settings_games_hint);
        Config cfg = Config.load(this);
        if (!AppEntries.launchable(getPackageManager(), SteamGames.MOONLIGHT_PACKAGE)) {
            row(getString(R.string.settings_games_no_moonlight), null, null, null);
        }
        String host = cfg.gamestreamHost;
        if (host.isEmpty()) {
            row(getString(R.string.settings_games_no_pc), null, null, null);
        } else {
            boolean paired = SteamGames.paired(this);
            row(hostName(host), host, getString(paired ? R.string.settings_paired : R.string.settings_not_paired),
                    null);
            if (!paired) row(getString(R.string.settings_pair), null, null, () -> {
                pairingState = null;
                go(this::pairingPage);
            });
            row(getString(R.string.settings_forget_pc), null, null, () -> changed(GameHosts.forget(this)));
        }
        label(R.string.settings_games_found);
        hosts.start();
        if (hosts.found().isEmpty()) {
            row(getString(hosts.timedOut() ? R.string.games_none_found : R.string.settings_games_searching), null,
                    null, null);
        }
        for (Map.Entry<String, String> pc : hosts.found().entrySet()) {
            String address = pc.getValue();
            row(pc.getKey(), address, address.equals(host) ? CHECK : null, () -> chooseHost(address));
        }
        row(getString(R.string.settings_games_address), null, null, this::askAddress);
    }

    private String hostName(String address) {
        for (Map.Entry<String, String> pc : hosts.found().entrySet()) {
            if (pc.getValue().equals(address)) return pc.getKey();
        }
        return getString(R.string.settings_games_pc);
    }

    private void chooseHost(String address) {
        if (!address.equals(Config.load(this).gamestreamHost)) {
            if (!GameHosts.choose(this, address)) {
                changed(false);
                return;
            }
            setResult(RESULT_OK);
        }
        pairingState = null;
        if (SteamGames.paired(this)) render();
        else go(this::pairingPage);
    }

    private void askAddress() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(Config.load(this).gamestreamHost);
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_games_address)
                .setMessage(R.string.settings_games_address_hint)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String address = input.getText().toString().trim();
                    if (GameHosts.valid(address)) chooseHost(address);
                    else Toast.makeText(this, R.string.settings_games_address_bad, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pairingPage() {
        String host = Config.load(this).gamestreamHost;
        if (host.isEmpty()) {
            backTo(games);
            return;
        }
        header(getString(R.string.settings_pair_title, hostName(host)), getString(R.string.settings_pair_hint));
        pairingShown = true;
        boolean paired = SteamGames.paired(this);
        if (!paired && !GameStreamPairing.running() && pairingState == null) {
            GameStreamPairing.start(SteamGames.identityDir(this), host);
        }
        pairingState = GameStreamPairing.state(SteamGames.paired(this));
        big.setVisibility(View.VISIBLE);
        String pin = GameStreamPairing.pin();
        if (paired) {
            big.setText(CHECK);
            hint.setText(R.string.settings_paired);
            row(getString(R.string.settings_done), null, null, () -> backTo(games));
            refreshGames();
            return;
        }
        if (GameStreamPairing.running()) {
            big.setText(pin != null ? pin : "…");
        } else {
            big.setText("✕");
            hint.setText(GameStreamPairing.lastResult() == GameStreamClient.PairResult.PIN_WRONG
                    ? R.string.settings_pair_wrong_pin : R.string.settings_pair_failed);
            row(getString(R.string.settings_pair_again), null, null, () -> {
                GameStreamPairing.start(SteamGames.identityDir(this), host);
                render();
            });
        }
        handler.postDelayed(pairingPoll, 500);
    }

    private void pollPairing() {
        if (!GameStreamPairing.state(SteamGames.paired(this)).equals(pairingState)) render();
        else handler.postDelayed(pairingPoll, 500);
    }

    /** Reads the new host's games now, so the row fills before the next Home refresh would. */
    private void refreshGames() {
        SteamGames.syncSoon();
        Context app = getApplicationContext();
        new Thread(() -> SteamGames.refresh(app, Config.load(app)), "games-refresh").start();
    }

    // ---- home ----------------------------------------------------------------------------------------------

    private void homePage() {
        header(R.string.settings_home, R.string.settings_home_hint);
        Config cfg = Config.load(this);
        int[] names = {R.string.settings_row_watch, R.string.settings_row_recent, R.string.settings_row_games,
                R.string.row_tv};
        for (int i = 0; i < HomeRows.ALL.size(); i++) {
            String id = HomeRows.ALL.get(i);
            boolean shown = cfg.rowShown(id);
            row(getString(names[i]), null, onOff(shown),
                    () -> changed(UserSettings.put(this, "home_rows", HomeRows.toggle(Config.load(this).homeRows, id, !shown))));
        }
        row(getString(R.string.settings_watch_max_age), getString(R.string.settings_watch_max_age_hint),
                watchAgeLabel(WatchHistory.maxAgeDays(this)), () -> go(this::watchAgePage));
        boolean startOnLast = StartOnLastInput.enabled(this);
        row(getString(R.string.settings_start_on_input), getString(R.string.settings_start_on_input_hint),
                onOff(startOnLast), () -> changed(UserSettings.put(this, StartOnLastInput.KEY, !startOnLast)));
        label(R.string.settings_hidden);
        List<HiddenItems.Item> hidden = HiddenItems.list(this);
        if (hidden.isEmpty()) row(getString(R.string.settings_hidden_none), null, null, null);
        for (HiddenItems.Item item : hidden) {
            String label = item.key.startsWith(HiddenItems.APP)
                    ? appLabel(item.key.substring(HiddenItems.APP.length()))
                    : item.key.startsWith(HiddenItems.WATCH_APP)
                    ? getString(R.string.hidden_watch_app, appLabel(item.key.substring(HiddenItems.WATCH_APP.length())))
                    : item.label;
            row(label, getString(R.string.settings_unhide), null,
                    () -> changed(HiddenItems.unhide(this, item.key)));
        }
    }

    private void watchAgePage() {
        header(R.string.settings_watch_max_age, R.string.settings_watch_max_age_hint);
        int current = WatchHistory.maxAgeDays(this);
        for (int i = 0; i < WATCH_AGES.length; i++) {
            int days = WATCH_AGES[i];
            row(getString(WATCH_AGE_NAMES[i]), null, days == current ? CHECK : null,
                    () -> changed(UserSettings.put(this, WatchHistory.MAX_AGE_KEY, days)));
        }
    }

    private String watchAgeLabel(int days) {
        for (int i = 0; i < WATCH_AGES.length; i++) if (WATCH_AGES[i] == days) return getString(WATCH_AGE_NAMES[i]);
        return days <= 0 ? getString(R.string.settings_age_none) : String.valueOf(days);
    }

    // ---- appearance ----------------------------------------------------------------------------------------

    private void appearancePage() {
        header(R.string.settings_appearance, 0);
        Config cfg = Config.load(this);
        label(R.string.settings_background);
        for (int i = 0; i < BACKGROUNDS.length; i++) {
            String colour = BACKGROUNDS[i];
            int value = Color.parseColor(colour) | 0xFF000000;
            View row = row(getString(BACKGROUND_NAMES[i]), null, cfg.backgroundColor == value ? CHECK : null,
                    () -> changed(UserSettings.put(this, "background_color", colour)));
            GradientDrawable swatch = new GradientDrawable();
            swatch.setShape(GradientDrawable.OVAL);
            swatch.setColor(value);
            swatch.setStroke(dp(1), 0x66FFFFFF);
            View s = row.findViewById(R.id.swatch);
            s.setBackground(swatch);
            s.setVisibility(View.VISIBLE);
        }
        File dir = Config.dir(this);
        File wallpaper = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        boolean hasWallpaper = wallpaper != null && wallpaper.isFile() && wallpaper.length() > 0;
        row(getString(R.string.settings_wallpaper), hasWallpaper ? null : getString(R.string.settings_wallpaper_none),
                onOff(cfg.wallpaperEnabled && hasWallpaper),
                () -> changed(UserSettings.put(this, "wallpaper", !cfg.wallpaperEnabled)));
        // Backdrops come from TVmaze and Steam; the credit TVmaze's licence asks for sits on the switch.
        row(getString(R.string.settings_online_artwork), getString(R.string.settings_online_artwork_hint),
                onOff(cfg.onlineArtwork), () -> changed(UserSettings.put(this, "online_artwork", !cfg.onlineArtwork)));
        row(getString(R.string.settings_appearance_theme), getString(R.string.settings_appearance_theme_hint), null,
                () -> changed(UserSettings.remove(this, "background_color", "wallpaper")));
    }

    // ---- permissions ---------------------------------------------------------------------------------------

    private void advancedPage() {
        header(R.string.settings_advanced, R.string.settings_advanced_hint);
        boolean usage = Grants.usageGranted(this), listener = Grants.listenerGranted(this);
        boolean usageScreen = Grants.resolves(this, Grants.usageIntent());
        boolean listenerScreen = Grants.resolves(this, Grants.listenerIntent());
        access(R.string.settings_usage_access, R.string.settings_usage_access_hint, usage, "usage",
                usageScreen ? Grants.usageIntent() : null);
        access(R.string.settings_listener_access, R.string.settings_listener_access_hint, listener, "listener",
                listenerScreen ? Grants.listenerIntent() : null);
        if (!usage && (!usageScreen || tried.contains("usage")) || !listener && (!listenerScreen || tried.contains("listener"))) {
            row(getString(R.string.settings_grant_auto), getString(R.string.settings_grant_auto_hint), null,
                    () -> Grants.grantNow(this, this::render));
        }
        boolean keys = Grants.serviceEnabled(this, InputKeyService.class) && Grants.serviceEnabled(this, HomeKeyService.class);
        boolean keysScreen = Grants.resolves(this, Grants.accessibilityIntent());
        access(R.string.settings_key_service, R.string.settings_key_service_hint, keys, "keys",
                keysScreen ? Grants.accessibilityIntent() : null);
        if (!keys && (!keysScreen || tried.contains("keys"))) {
            row(getString(R.string.settings_grant_auto), getString(R.string.settings_grant_auto_hint), null,
                    () -> Grants.enableKeyServicesNow(this, this::render));
        }
        for (String permission : runtimePermissions()) {
            boolean granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            row(permissionLabel(permission), null,
                    getString(granted ? R.string.settings_granted : R.string.settings_not_granted),
                    granted ? null : () -> {
                        tried.add(permission);
                        requestPermissions(new String[]{permission}, PERMISSION_REQUEST);
                    });
            if (!granted && tried.contains(permission)) {
                row(getString(R.string.settings_grant_auto), getString(R.string.settings_grant_auto_hint), null,
                        () -> Grants.grantPermissionNow(this, permission, this::render));
            }
        }
        backupRows();
        label(R.string.settings_reset);
        row(getString(R.string.settings_reset), getString(R.string.settings_reset_hint), null, () ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_reset)
                        .setMessage(R.string.settings_reset_hint)
                        .setPositiveButton(R.string.settings_reset_confirm, (d, w) -> changed(UserSettings.clear(this)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
    }

    /** One permission: its state, and the TV's own screen for it when there is one; else the automatic grant. */
    private void access(int titleRes, int hintRes, boolean granted, String id, Intent screenIntent) {
        Runnable click;
        if (screenIntent != null) {
            click = () -> {
                tried.add(id);
                open(screenIntent);
            };
        } else if (granted) {
            click = null;
        } else {
            click = "keys".equals(id) ? () -> Grants.enableKeyServicesNow(this, this::render)
                    : () -> Grants.grantNow(this, this::render);
        }
        row(getString(titleRes), getString(hintRes),
                getString(granted ? R.string.settings_granted : R.string.settings_not_granted), click);
    }

    /** Dangerous permissions the manifest asks for, which Android grants at runtime. */
    @SuppressWarnings("deprecation")
    private List<String> runtimePermissions() {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            PackageInfo info = pm.getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return out;
            for (String p : info.requestedPermissions) {
                try {
                    PermissionInfo pi = pm.getPermissionInfo(p, 0);
                    if ((pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS) {
                        out.add(p);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return out;
    }

    private String permissionLabel(String permission) {
        try {
            PackageManager pm = getPackageManager();
            CharSequence label = pm.getPermissionInfo(permission, 0).loadLabel(pm);
            String text = label.toString();
            return text.isEmpty() ? permission : Character.toUpperCase(text.charAt(0)) + text.substring(1);
        } catch (PackageManager.NameNotFoundException e) {
            return permission;
        }
    }

    // ---- backup --------------------------------------------------------------------------------------------

    private Intent exportIntent() {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, SettingsBackup.FILE_NAME);
    }

    private Intent importIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
    }

    /** The file used where the TV has no document picker: the app's folder in Android/data, reachable over USB or adb. */
    private File backupFile() {
        File dir = getExternalFilesDir(null);
        return dir == null ? null : new File(dir, SettingsBackup.FILE_NAME);
    }

    private void backupRows() {
        label(R.string.settings_backup);
        row(getString(R.string.settings_export), getString(R.string.settings_export_hint), null, () -> {
            if (Grants.resolves(this, exportIntent())) pick(exportIntent(), EXPORT_REQUEST);
            else exportTo(null);
        });
        row(getString(R.string.settings_import), getString(R.string.settings_import_hint), null, () -> {
            if (Grants.resolves(this, importIntent())) pick(importIntent(), IMPORT_REQUEST);
            else importFrom(null);
        });
    }

    private void pick(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (request == EXPORT_REQUEST) exportTo(null);
            else importFrom(null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == EXPORT_REQUEST) exportTo(data.getData());
        else if (requestCode == IMPORT_REQUEST) importFrom(data.getData());
    }

    /** Writes to the chosen document, or, with none, to the app's folder, and says where it went. */
    private void exportTo(Uri uri) {
        File file = uri == null ? backupFile() : null;
        try (OutputStream out = uri != null ? getContentResolver().openOutputStream(uri, "wt")
                : file == null ? null : new java.io.FileOutputStream(file)) {
            if (out == null) throw new IOException("no destination");
            out.write(SettingsBackup.export(UserSettings.read(this)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException | RuntimeException e) {
            Toast.makeText(this, R.string.settings_export_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.settings_export_done,
                file != null ? file.getPath() : getString(R.string.settings_backup_chosen_file)), Toast.LENGTH_LONG).show();
    }

    private void importFrom(Uri uri) {
        File file = uri == null ? backupFile() : null;
        String text;
        try (InputStream in = uri != null ? getContentResolver().openInputStream(uri)
                : file != null && file.isFile() ? new java.io.FileInputStream(file) : null) {
            if (in == null) {
                Toast.makeText(this, getString(R.string.settings_import_missing,
                        file != null ? file.getPath() : SettingsBackup.FILE_NAME), Toast.LENGTH_LONG).show();
                return;
            }
            text = new String(StoredText.readAll(in, SettingsBackup.MAX_BYTES), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, R.string.settings_import_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            SettingsBackup.Parsed parsed = SettingsBackup.parse(text);
            SettingsBackup.Preview preview = SettingsBackup.preview(UserSettings.read(this), parsed);
            confirmImport(parsed, preview);
        } catch (SettingsBackup.Invalid e) {
            Toast.makeText(this, e.newer ? R.string.settings_import_newer : R.string.settings_import_invalid,
                    Toast.LENGTH_LONG).show();
        } catch (JSONException e) {
            Toast.makeText(this, R.string.settings_import_invalid, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmImport(SettingsBackup.Parsed parsed, SettingsBackup.Preview preview) {
        if (preview.isEmpty()) {
            Toast.makeText(this, R.string.settings_import_nothing, Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder message = new StringBuilder(getString(R.string.settings_import_changes, preview.changedKeys.size()));
        for (String key : preview.changedKeys) message.append("\n  ").append(key);
        message.append('\n').append(getString(R.string.settings_import_hidden, preview.newHidden));
        if (!parsed.skipped.isEmpty()) {
            message.append('\n').append(getString(R.string.settings_import_skipped, parsed.skipped.size()));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_import)
                .setMessage(message)
                .setPositiveButton(R.string.settings_import_apply, (d, w) -> changed(UserSettings.edit(this,
                        root -> SettingsBackup.apply(root, parsed))))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        render();
    }
}
