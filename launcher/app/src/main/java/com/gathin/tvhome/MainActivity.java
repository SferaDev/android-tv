package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google TV style home: a header with the Home and Apps tabs; on Home a hero for the focused card over its
 * full-bleed backdrop, then strips of cards (recent apps); every app on the Apps tab. All D-pad moves between cards
 * are decided here, with HomeNav.
 */
public class MainActivity extends Activity implements CardRow.Listener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static final long MINUTE_MS = 60_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock and date stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private static final long STATS_MS = 10_000L;
    /** Home strips, top to bottom; a fixed order so the screen stays predictable. */
    static final int ROW_RECENT = 0, ROWS = 1;
    /** The Apps tab's grid, as a row id for CardRow. */
    static final int APPS_GRID = 100;
    private static final int TAB_HOME = 0, TAB_APPS = 1;
    private static final float RECENT_WIDTH_DP = 130f;
    private static final int STRIP_LIMIT = 10;
    /** Left and Right across the tabs show each at once; the content follows when the focus rests. */
    private static final long TAB_SETTLE_MS = 150;
    private String liveTvPkg;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    /** Back on Home opens ClockActivity (config.xml, back_opens_clock). */
    private boolean backOpensClock;
    /** Between onResume and onPause: a key's ACTION_UP can still reach Home after onPause, when that key opened
     *  another app, and must not start the idle clock over the app. */
    private boolean resumed;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String liveTvInputId;
    /** The app library is on (config.xml, app_library): only then can the tile menu take an app off Home. */
    private boolean appLibraryEnabled;
    /** The same opt-in menu is available from either hold-OK or the remote's Menu key. */
    private boolean tileMenuEnabled;
    private String inputAction;
    private List<String> inputSources;
    /** open_on_start_choice: the app picked in Launcher settings may open on the first Home of each boot. */
    private boolean openOnStartChoice;
    /** open_on_start_network_wait, in ms; the boot's app while it waits for the network, and until when. */
    private long openOnStartWaitMs;
    private volatile Intent startPending;
    private long startDeadline;
    /** Opens the boot's app once the network is validated (networkCallback posts this) or the wait is over. */
    private final Runnable openWhenOnline = new Runnable() {
        @Override
        public void run() {
            if (startPending == null) return;
            if (!OpenOnStart.opensNow(OpenOnStart.online(MainActivity.this), SystemClock.elapsedRealtime(),
                    startDeadline)) return;
            handler.removeCallbacks(this);   // the deadline's run, when the network came first
            Intent i = startPending;
            startPending = null;
            open(i);
        }
    };
    private String inputUriScheme;
    private String sourcePanelService;
    private String sourcePanelAction;
    private String sourcePanelExtra;
    private boolean coocaaSources;

    private Config cfg;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private boolean animations;
    private boolean reducedMotion;
    /** rtl_app_grid (Hebrew): the strips and the grids start at the right. */
    private boolean rtl;
    private int columns;
    private TextView tabHome;
    private TextView tabApps;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network button's icon as last set, so a refresh that changes nothing does not lay the header out again. */
    private int wifiIcon;
    /** The header buttons have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private View btnSettings;
    private NotificationBell notificationBell;
    /** The Wi-Fi button's full label (network name, VPN badge); shown only while the button is focused. */
    private CharSequence wifiLabel = "";
    /** The VPN badge alone, shown on the round button while it is not focused; empty without a VPN state. */
    private CharSequence wifiBadge = "";
    private TextView shamsiDate;
    private View clockBlock;
    private TextView stats;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    /** config.xml inputs_connected_only: the Inputs menu lists only connected inputs, the rest behind "More inputs". */
    private boolean inputsConnectedOnly;
    private boolean shamsi;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private float density;

    private RowsScrollView homeRows;
    private RecyclerView appsGrid;
    private final View[] sections = new View[ROWS];
    private final RecyclerView[] rows = new RecyclerView[ROWS];
    private final int[] rowLengths = new int[ROWS];
    private AppAdapter recentAdapter;
    private AppAdapter appsAdapter;
    private ImageCache images;
    private RecyclerView.RecycledViewPool pool;
    /** Apps, strips and banners load here, one job at a time, and post their results to the main thread. */
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    /** The last app list, for strip-only reloads; written on the loader thread. */
    private volatile List<AppAdapter.Entry> loadedApps = Collections.emptyList();
    /** The strips' cards before hiding, newest first, so a hide or show applies without a reload. */
    private List<AppAdapter.Entry> allRecent = Collections.emptyList();
    /** Items hidden from the Home strips (HiddenItems keys). */
    private Set<String> hidden = new HashSet<>();
    private int pendingTab = -1;
    private final Runnable tabSettled = () -> showTab(pendingTab);

    private Hero hero;
    private int heroRow = -1, heroPosition;

    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int tab = TAB_HOME;
    private static int rememberedRow = ROW_RECENT;
    private static final int[] rememberedColumns = new int[ROWS];
    private static int rememberedTile;
    /** Put focus back on the remembered card once the data arrives, unless the remote was used meanwhile. */
    private boolean restoreFocusPending;
    private boolean keyedSinceResume;

    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** The network pill's festive light: the diya, or the jack-o'-lantern for Halloween. */
    private FestiveLamp lamp;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the grid sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The apps have been loaded since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Reload on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs, Settings or card dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    private AppLibraryDialog appLibrary;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network button when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            // open_on_start_network_wait: the boot's app opens on the first validated network.
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && startPending != null) {
                handler.post(openWhenOnline);
            }
        }
    };
    /** Shows the Inputs button once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reload(true);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateDate();
            updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    private java.util.Map<Integer, String> homeAppKeys = java.util.Collections.emptyMap();
    private java.util.Map<Integer, String> homeHoldAppKeys = java.util.Collections.emptyMap();

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // The root paints the background; the window's own black under it would be one more full-screen fill.
        getWindow().setBackgroundDrawable(null);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        cfg = Config.load(this);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        Grants.start(this);
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted by build-and-install with RESTORE_KEY_SERVICES=1).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        columns = cfg.columns;
        animations = cfg.animations;
        reducedMotion = cfg.reducedMotion;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        backOpensClock = cfg.backOpensClock;
        bootToLiveTv = cfg.bootToLiveTv;
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        appLibraryEnabled = cfg.appLibrary;
        tileMenuEnabled = cfg.tileMenu;
        inputAction = cfg.inputAction;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;
        inputSources = cfg.inputSources;
        homeAppKeys = RemoteKeys.parseAppKeys(cfg.appKeys);
        homeHoldAppKeys = RemoteKeys.parseHoldAppKeys(cfg.appKeys);
        openOnStartChoice = cfg.openOnStartChoice;
        openOnStartWaitMs = cfg.openOnStartNetworkWait * 1000L;
        rtl = getResources().getBoolean(R.bool.rtl_app_grid);

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        clockBlock = findViewById(R.id.clockBlock);
        stats = findViewById(R.id.stats);
        showStats = cfg.showStats;
        inputsConnectedOnly = cfg.inputsConnectedOnly;
        shamsi = cfg.shamsiDate;
        if (shamsi) shamsiDate.setVisibility(View.VISIBLE);
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        hidden = HiddenItems.keys(this);
        images = new ImageCache(this);

        hero = new Hero(this, root, images, cfg, rtl, () -> {
            if (heroRow >= 0) prefetchAround(heroRow, heroPosition);
        });
        setUpRows();
        setUpGrids();
        setUpHeader();

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Focus moving to the header puts the hero back on the most recent item.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null && tab == TAB_HOME && !isInContent(newFocus)) {
                heroRow = -1;
                hero.schedule(defaultHero());
            }
        });
        showTab(tab);
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent())) openInputsIfAsked(getIntent());
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            // Android 9 delivers a new intent to a paused activity with a brief resume and pause around it, and
            // onPause() closes popups, so open the menu once that has run (HiSmartTV A4, Android TV 9).
            anchor.post(() -> {
                if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                anchor.requestFocus();
                showInputs();
            });
            return true;
        }
        // Cold start: the popup anchors to the pill, so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    private void setUpRows() {
        homeRows = findViewById(R.id.homeRows);
        int[] sectionIds = {R.id.sectionRecent};
        int[] rowIds = {R.id.rowRecent};
        int[] titleIds = {R.id.titleRecent};
        recentAdapter = new AppAdapter(this, ROW_RECENT, this, Math.round(RECENT_WIDTH_DP * density), false, cfg);
        recentAdapter.setInputOpener(this::openInput);
        RecyclerView.Adapter<?>[] adapters = {recentAdapter};
        // One pool for the strips and the grids; each kind of card has its own view type.
        pool = new RecyclerView.RecycledViewPool();
        for (int r = 0; r < ROWS; r++) {
            sections[r] = findViewById(sectionIds[r]);
            if (rtl) ((TextView) findViewById(titleIds[r])).setGravity(Gravity.RIGHT);
            RecyclerView row = findViewById(rowIds[r]);
            LinearLayoutManager layout = stripLayout();
            layout.setInitialPrefetchItemCount(4);
            row.setLayoutManager(layout);
            row.setItemAnimator(null);
            row.setHasFixedSize(true);
            row.setRecycledViewPool(pool);
            row.setAdapter(adapters[r]);
            rows[r] = row;
        }
    }

    private void setUpGrids() {
        // The grids span the screen less their 40dp paddings; each card keeps 8dp on either side within its column.
        int inner = getResources().getDisplayMetrics().widthPixels - Math.round(80 * density);
        appsGrid = findViewById(R.id.appsGrid);
        appsGrid.setLayoutManager(gridLayout(columns));
        appsAdapter = new AppAdapter(this, APPS_GRID, this, inner / columns - Math.round(16 * density), true, cfg);
        appsAdapter.setInputOpener(this::openInput);
        appsGrid.setAdapter(appsAdapter);
        appsGrid.setItemAnimator(null);
        appsGrid.setHasFixedSize(true);
        appsGrid.setRecycledViewPool(pool);
    }

    private LinearLayoutManager stripLayout() {
        return new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) {
            @Override
            protected boolean isLayoutRTL() {
                return rtl;
            }
        };
    }

    private GridLayoutManager gridLayout(int spans) {
        return new GridLayoutManager(this, spans) {
            @Override
            protected boolean isLayoutRTL() {
                return rtl;
            }
        };
    }

    private void setUpHeader() {
        tabHome = findViewById(R.id.tabHome);
        tabApps = findViewById(R.id.tabApps);
        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        notificationBell = new NotificationBell(this, findViewById(R.id.btnBell));
        HeaderSearch.bind(this, findViewById(R.id.btnSearch));
        tabHome.setOnClickListener(v -> showTab(TAB_HOME));
        tabApps.setOnClickListener(v -> showTab(TAB_APPS));
        TextView[] tabs = {tabHome, tabApps};
        for (int i = 0; i < tabs.length; i++) {
            int which = i;
            tabs[i].setOnFocusChangeListener((v, focused) -> {
                if (focused) focusTab(which);
            });
        }
        btnInputs.setOnClickListener(v -> showInputs());
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnWifi.setOnFocusChangeListener((v, focused) -> applyWifiLabel());
        btnSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        View.OnKeyListener headerKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (pendingTab >= 0) showTab(pendingTab);
                    focusContent();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveInHeader(v, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                default:
                    return false;
            }
        };
        for (View v : headerViews()) v.setOnKeyListener(headerKeys);
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);
    }

    private View[] headerViews() {
        return new View[]{tabHome, tabApps, btnInputs, btnWifi, findViewById(R.id.btnSearch), findViewById(R.id.btnBell), btnSettings};
    }

    /** Left and Right along the header by where the buttons are on screen, so the Hebrew mirror needs nothing else. */
    private void moveInHeader(View from, boolean right) {
        List<View> order = new ArrayList<>();
        for (View v : headerViews()) {
            if (v.getVisibility() == View.VISIBLE) order.add(v);
        }
        int[] at = new int[2];
        Map<View, Integer> x = new HashMap<>();
        for (View v : order) {
            v.getLocationOnScreen(at);
            x.put(v, at[0]);
        }
        Collections.sort(order, (a, b) -> Integer.compare(x.get(a), x.get(b)));
        int i = order.indexOf(from);
        int next = HomeNav.nextColumn(i, order.size(), right);
        if (i >= 0 && next != i) {
            order.get(next).requestFocus();
            from.playSoundEffect(right ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
        }
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the Home tab with the first card focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the card you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        rememberedRow = ROW_RECENT;
        Arrays.fill(rememberedColumns, 0);
        for (RecyclerView row : rows) row.scrollToPosition(0);
        homeRows.scrollTo(0, 0);
        showTab(TAB_HOME);
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        keyedSinceResume = false;
        notificationBell.start();
        if (rebuildOnResume) {
            hidden = HiddenItems.keys(this);
            tilesReady = false;
            restoreFocusPending = true;
            reload(true);
        }
        rebuildOnResume = false;
        if (openLiveTvAfterBoot()) return;
        updateDate();
        // Callbacks keep the network and Inputs buttons current in the background; this refresh only catches anything
        // missed, and its binder calls would hold up the first frame back on Home, so later resumes run it after that frame.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        sky.update();
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && lamp != null) {
            lamp.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    // ---- data ------------------------------------------------------------------------------------

    /**
     * Loads the strips (and with {@code apps} the app list and module cards) on the loader thread, then shows them.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary ones and cards
     * stay off for this process.
     */
    private void reload(boolean apps) {
        final List<Cards.Card> none = Collections.emptyList();
        final List<Cards.Card> shown;
        if (apps) {
            List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
            cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                    () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
            shown = cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none);
        } else {
            shown = null;
        }
        Context app = getApplicationContext();
        Config config = cfg;
        loader.execute(() -> {
            List<AppAdapter.Entry> entries = apps ? AppAdapter.load(app, config, shown) : loadedApps;
            loadedApps = entries;
            Map<String, AppAdapter.Entry> byPkg = new HashMap<>();
            for (AppAdapter.Entry e : entries) if (!byPkg.containsKey(e.pkg)) byPkg.put(e.pkg, e);
            List<AppAdapter.Entry> recent = new ArrayList<>();
            // The configured favourites (config.xml dock_apps).
            for (String pkg : config.dockApps) {
                AppAdapter.Entry e = entryFor(app, pkg, byPkg);
                if (e != null) recent.add(e);
            }
            handler.post(() -> {
                if (isDestroyed()) return;
                if (apps) loadedAppsShown(entries);
                allRecent = recent;
                showLists();
                if (apps) {
                    tilesReady = true;
                    showCardPrompt();
                }
            });
        });
    }

    private void loadedAppsShown(List<AppAdapter.Entry> entries) {
        appsAdapter.setItems(entries, e -> hidden.contains(HiddenItems.app(e.pkg)));
    }

    /** The grid's entry for a package, or a freshly loaded one for an app outside it; null for a hidden app. */
    private AppAdapter.Entry entryFor(Context app, String pkg, Map<String, AppAdapter.Entry> byPkg) {
        AppAdapter.Entry e = byPkg.get(pkg);
        if (e != null || hiddenApps.contains(pkg)) return e;
        e = AppAdapter.loadEntry(app, pkg);
        if (e != null) byPkg.put(pkg, e);
        return e;
    }

    /** Fills the strips from the last load, leaving out what the owner hid from Home. */
    private void showLists() {
        View focused = getCurrentFocus();
        boolean hadContentFocus = focused != null && isInContent(focused);
        List<AppAdapter.Entry> recent = HiddenItems.visible(allRecent, e -> hidden.contains(HiddenItems.app(e.pkg)),
                STRIP_LIMIT);
        recentAdapter.setItems(recent, e -> false);
        appsAdapter.notifyDataSetChanged();
        // A strip switched off in Settings, Home counts as empty, so it hides and focus skips it.
        rowLengths[ROW_RECENT] = cfg.rowShown(HomeRows.RECENT_APPS) ? recent.size() : 0;
        for (int r = 0; r < ROWS; r++) sections[r].setVisibility(rowLengths[r] > 0 ? View.VISIBLE : View.GONE);

        boolean restore = restoreFocusPending && !keyedSinceResume && openPopup == null && cardPrompt == null;
        // A card that vanished in the update (the list changed under it) takes focus with it: put it back.
        if (restore || (hadContentFocus && (getCurrentFocus() == null || !getCurrentFocus().isAttachedToWindow()))) {
            restoreFocusPending = false;
            homeRows.post(this::focusContent);
        }
        View now = getCurrentFocus();
        if (now == null || !isInContent(now)) {
            heroRow = -1;
            hero.schedule(defaultHero());
        }
    }

    private boolean isInContent(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == homeRows || p == appsGrid) return true;
        }
        return false;
    }

    // ---- hero and backdrop -----------------------------------------------------------------------

    private Hero.Item heroFor(int row, int position) {
        if (row == ROW_RECENT && position < recentAdapter.getItemCount()) {
            AppAdapter.Entry e = recentAdapter.entryAt(position);
            return new Hero.Item("a" + e.pkg, e.label, null, null, e.icon, null, copy(e.banner != null ? e.banner : e.icon));
        }
        return null;
    }

    /** The first app: shown while no card is focused. */
    private Hero.Item defaultHero() {
        int row = HomeNav.restoreRow(rowLengths, ROW_RECENT);
        return row == HomeNav.HEADER ? null : heroFor(row, 0);
    }

    /** A drawable of its own, so the backdrop never shares bounds or state with the card showing the same banner. */
    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState state = d.getConstantState();
        return state != null ? state.newDrawable().mutate() : null;
    }

    /** Composes the neighbours' backdrops ahead, so moving along a strip crossfades without waiting. */
    private void prefetchAround(int row, int position) {
        for (int p : new int[]{position + 1, position - 1}) {
            if (p < 0) continue;
            Hero.Item h = heroFor(row, p);
            if (h != null) hero.prefetch(h);
        }
    }

    // ---- tabs and focus --------------------------------------------------------------------------

    /** A tab took focus: it looks selected at once, and its content shows once Left and Right stop. */
    private void focusTab(int which) {
        handler.removeCallbacks(tabSettled);
        if (which == tab) {
            pendingTab = -1;
            selectTab(which);
            return;
        }
        pendingTab = which;
        selectTab(which);
        handler.postDelayed(tabSettled, TAB_SETTLE_MS);
    }

    private void selectTab(int which) {
        tabHome.setSelected(which == TAB_HOME);
        tabApps.setSelected(which == TAB_APPS);
    }

    private void showTab(int which) {
        handler.removeCallbacks(tabSettled);
        pendingTab = -1;
        boolean entering = which != tab;
        tab = which;
        // TalkBack does not otherwise say that the page changed.
        if (entering) selectedTab().announceForAccessibility(selectedTab().getText());
        homeRows.setVisibility(which == TAB_HOME ? View.VISIBLE : View.GONE);
        appsGrid.setVisibility(which == TAB_APPS ? View.VISIBLE : View.GONE);
        selectTab(which);
        if (which != TAB_HOME) hero.fade(0f);
        else hero.dim(homeRows.getScrollY() > 0);
    }

    private TextView selectedTab() {
        return tab == TAB_APPS ? tabApps : tabHome;
    }

    /** Down from the header, or back from an app: the remembered card of the open tab, or the tab when there is none. */
    private void focusContent() {
        if (tab == TAB_APPS) {
            int count = appsAdapter.getItemCount();
            if (count == 0) selectedTab().requestFocus();
            else focusIn(appsGrid, Math.max(0, Math.min(rememberedTile, count - 1)));
            return;
        }
        int row = HomeNav.restoreRow(rowLengths, rememberedRow);
        if (row == HomeNav.HEADER) tabHome.requestFocus();
        else focusCard(row, HomeNav.column(rememberedColumns[row], rowLengths[row]));
    }

    private void focusCard(int row, int column) {
        focusIn(rows[row], column);
    }

    /** Focuses the card at {@code position}, first bringing it on screen when it is not laid out. */
    private void focusIn(RecyclerView list, int position) {
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(position);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so place the strips by hand.
            if (card.isFocused()) onCardFocused(rowOf(list), position);
            else card.requestFocus();
            return;
        }
        list.scrollToPosition(position);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(position);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    private int rowOf(RecyclerView list) {
        if (list == appsGrid) return APPS_GRID;
        for (int r = 0; r < ROWS; r++) if (rows[r] == list) return r;
        return ROW_RECENT;
    }

    @Override
    public void onCardFocused(int row, int position) {
        if (row == APPS_GRID) {
            rememberedTile = position;
            return;
        }
        rememberedRow = row;
        rememberedColumns[row] = position;
        scrollToRow(row);
        heroRow = row;
        heroPosition = position;
        hero.schedule(heroFor(row, position));
    }

    /**
     * The first strip sits under the hero; a strip further down scrolls to the top, past the hero, and the backdrop
     * dims. The list clamps the scroll, so the last strips stop at the bottom.
     */
    private void scrollToRow(int row) {
        int first = HomeNav.restoreRow(rowLengths, 0);
        if (first == HomeNav.HEADER) return;
        int y = row == first ? 0 : sections[row].getTop();
        if (animations && !reducedMotion) homeRows.smoothScrollTo(0, y);
        else homeRows.scrollTo(0, y);
        hero.dim(row != first);
        hero.setVisible(row == first);
    }

    @Override
    public boolean onCardKey(int row, int position, int keyCode) {
        boolean left = keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean horizontal = left || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        if (row == APPS_GRID) {
            int count = appsAdapter.getItemCount();
            int target;
            if (horizontal) {
                if (GridNavigation.atHorizontalEdge(position, count, columns, rtl, left)) return true;
                target = left != rtl ? position - 1 : position + 1;
            } else {
                target = HomeNav.gridVertical(position, count, columns, down);
            }
            if (target == HomeNav.HEADER) selectedTab().requestFocus();
            else if (target != position) focusIn(appsGrid, target);
            else return true;
            click(keyCode);
            return true;
        }
        if (horizontal) {
            // Reading order runs right to left in Hebrew: Left moves forward there.
            int target = HomeNav.nextColumn(position, rowLengths[row], left == rtl);
            if (target == position) return true;
            focusCard(row, target);
            click(keyCode);
            return true;
        }
        int next = HomeNav.nextRow(rowLengths, row, down);
        if (next == HomeNav.HEADER) tabHome.requestFocus();
        else if (next != row) focusCard(next, HomeNav.column(rememberedColumns[next], rowLengths[next]));
        else return true;
        click(keyCode);
        return true;
    }

    /** The focus search clicks on its own; moves made here click too. */
    private void click(int keyCode) {
        int effect;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: effect = SoundEffectConstants.NAVIGATION_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: effect = SoundEffectConstants.NAVIGATION_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: effect = SoundEffectConstants.NAVIGATION_LEFT; break;
            default: effect = SoundEffectConstants.NAVIGATION_RIGHT;
        }
        root.playSoundEffect(effect);
    }

    // ---- hide from Home ---------------------------------------------------------------------------

    /** Hold OK or Menu on a card: hide it from Home (or show it again from the Apps tab). */
    @Override
    public void onCardMenu(int row, int position, View card) {
        if (openPopup != null) return;   // View's own long click got there first
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout items = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        AppAdapter.Entry e = (row == ROW_RECENT ? recentAdapter : appsAdapter).entryAt(position);
        addHideToggle(items, popup, HiddenItems.app(e.pkg), e.label);
        // Some firmware has no App info page at all; a row that only toasts "can't open" helps nobody.
        Intent info = SystemSettings.appInfo(this, e.pkg);
        if (info != null) addSettingsMenuRow(items, popup, getString(R.string.library_app_info), () -> open(info));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * density), panel.getMeasuredWidth()));
        dropIn(popup, panel, card, rtl ? Gravity.END : Gravity.START);
        items.getChildAt(0).requestFocus();
    }

    /** label is what Settings, Home lists for the hidden item. */
    private void addHideToggle(LinearLayout items, PopupWindow popup, String key, String label) {
        boolean isHidden = hidden.contains(key);
        addSettingsMenuRow(items, popup, getString(isHidden ? R.string.show_on_home : R.string.hide_from_home),
                () -> setHidden(key, label, !isHidden));
    }

    private void setHidden(String key, String label, boolean hide) {
        boolean saved = hide ? HiddenItems.hide(this, key, label) : HiddenItems.unhide(this, key);
        hiddenChanged(saved, !hide);
    }

    private void hiddenChanged(boolean saved, boolean shownAgain) {
        if (!saved) Toast.makeText(this, R.string.settings_not_saved, Toast.LENGTH_SHORT).show();
        hidden = HiddenItems.keys(this);
        showLists();
        // The data sources leave hidden apps and titles out, so one shown again needs a reload to come back.
        if (shownAgain) reload(false);
        // Back to the card the menu came from; when it was hidden, its neighbour or the next strip takes over.
        handler.post(this::focusContent);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Apps goes back to Home; Home itself goes nowhere, unless back_opens_clock is on.
        if (tab == TAB_HOME) {
            if (backOpensClock) startActivity(new Intent(this, ClockActivity.class));
            return;
        }
        View focused = getCurrentFocus();
        boolean inHeader = focused != null && !isInContent(focused);
        showTab(TAB_HOME);
        if (inHeader) tabHome.requestFocus();
        else focusContent();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Vendor keys (TCL Netflix button, 4062) never reach the key services, only the focused window, so their
        // "hold:" entries run here too or nowhere. Only while resumed: onPause releases Home's holds, and a DOWN
        // taken after that would arm one nothing releases.
        if (resumed && event.getKeyCode() >= 1000 && RemoteKeys.handle(this, event, homeAppKeys.get(event.getKeyCode()),
                homeHoldAppKeys.get(event.getKeyCode()))) return true;
        if (handleSettingsKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN) keyedSinceResume = true;
        return super.dispatchKeyEvent(event);
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (resumed && idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // No UP reaches a paused Home, so a vendor-key hold it saw go down would still open its app later.
        // Only Home's own share is dropped: a key service that saw the same DOWN keeps its hold.
        RemoteKeys.release(this);
        root.removeCallbacks(showIdleClock);
        handler.removeCallbacks(openWhenOnline);
        startPending = null;   // leaving Home before the network came back: the boot's app stays closed
        notificationBell.stop();
        wallpaperPlayer.stop();
        handler.removeCallbacks(minuteTick);
        hero.pause();
        stopStats();
        if (lamp != null) lamp.stop();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) showCardPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RemoteKeys.release(this);
        wallpaperPlayer.close();
        loader.shutdownNow();
        hero.close();
        images.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    private void showAppLibrary() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, cfg.appLibrarySelection, cfg.libraryOkOpens, this::inAppsTab, () -> reload(true), () -> {
            appLibrary = null;
            openPopup = null;
            btnSettings.requestFocus();
        }, new HashSet<>(cfg.libraryExcludedApps));
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    private boolean inAppsTab(String pkg) {
        for (AppAdapter.Entry e : loadedApps) if (e.pkg.equals(pkg)) return true;
        return false;
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused: keep the last line on screen rather than move the header under it.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return StatsText.forLocale(line.toString(), getResources().getConfiguration().getLocales().get(0));
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over
     *  free so the line reads as a load against a known whole; null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        long available = -1, total = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            String[] t;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) available = Long.parseLong(t[1]) / 1024;
                } else if (line.startsWith("MemTotal:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) total = Long.parseLong(t[1]) / 1024;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        if (available < 0 || total <= 0) return null;
        return new long[]{Math.max(0, total - available), total};
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateDate();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateDate() {
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** The network name when we can read it, otherwise the connection type; the button shows it while focused. */
    @SuppressWarnings("deprecation")
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The festive light (diya flame or jack-o'-lantern candle): 0 is out (offline), 1 to 4 follow the Wi-Fi
        // signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The button has a maximum width and ellipsizes at the end, which would cut the badge off after a long
            // network name, so shorten the name to whatever room the badge leaves beside the icon and paddings.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - WIFI_CHROME_DP * density
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            // On Ethernet the icon already names the link (#961).
            boolean stub = name.length() < text.length() && name.length() < 5;
            String shown = stub || icon == R.drawable.ic_ethernet ? "" : name;
            SpannableString label = new SpannableString(shown + " " + badge);
            label.setSpan(span, shown.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            wifiLabel = label;
            SpannableString badgeOnly = new SpannableString(badge);
            badgeOnly.setSpan(span, 0, badge.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            wifiBadge = badgeOnly;
        } else {
            wifiLabel = text;
            wifiBadge = "";
        }
        btnWifi.setContentDescription(text);
        applyWifiLabel();
        if (festive && lamp != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            lamp.setSteady(steady);
            lamp.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
        }
    }

    /** Icon, its padding and the paddings of the widened Wi-Fi button (HeaderIcon), around the label. */
    private static final float WIFI_CHROME_DP = 8 + 20 + 8 + 16;

    /** Round icon button while unfocused (with the VPN badge, if any); the network name joins it on focus. */
    private void applyWifiLabel() {
        CharSequence shown = btnWifi.isFocused() ? wifiLabel : wifiBadge;
        boolean wide = shown.length() > 0;
        btnWifi.setCompoundDrawablePadding(wide ? (int) (8 * density) : 0);
        btnWifi.setPaddingRelative((int) (8 * density), 0, (int) ((wide ? 16 : 8) * density), 0);
        btnWifi.setText(shown);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused. Halloween
     * has its own: a bat for Inputs that flaps, a jack-o'-lantern whose face glows while online, and a ghost for
     * Settings that floats up.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        if (festival.isHalloween()) {
            SpookyIconDrawable bat = new SpookyIconDrawable(getResources(), density, SpookyIconDrawable.BAT);
            SpookyIconDrawable ghost = new SpookyIconDrawable(getResources(), density, SpookyIconDrawable.GHOST);
            JackOLanternDrawable pumpkin = new JackOLanternDrawable(getResources(), density);
            bat.setReducedMotion(reducedMotion);
            ghost.setReducedMotion(reducedMotion);
            pumpkin.setReducedMotion(reducedMotion);
            lamp = pumpkin;
            setFestiveIcon(inputs, bat);
            setFestiveIcon(btnWifi, pumpkin);
            setFestiveIcon(settings, ghost);
            addFocusAction(inputs, bat::nudge);
            addFocusAction(settings, ghost::nudge);
            return;
        }
        BellDrawable bell = new BellDrawable(density);
        MarigoldDrawable marigold = new MarigoldDrawable(density);
        DiyaDrawable diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        lamp = diya;
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        if (festival.isHalloween()) {
            BatFlightView bats = new BatFlightView(this);
            root.addView(bats, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bats.play(16, 2800, () -> root.removeView(bats));
            return;
        }
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnAppIntent();
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }

    /** vpn_app's launch intent, or null when none is set or it is not installed. */
    private Intent vpnAppIntent() {
        if (vpnApp.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(vpnApp);
    }

    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty()
                || (coocaaSources && !CoocaaSources.list(this).isEmpty());
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv, or the Open on start app: the first time Home comes up after the TV starts, open that app
     *  instead. Once per boot, keyed on Settings.Global.BOOT_COUNT and committed to disk before the app starts: on a
     *  low-memory TV the app can push the launcher out of memory, and an apply() still in flight was lost then, so
     *  every Home press opened the app again. */
    private boolean openLiveTvAfterBoot() {
        String app = openOnStartChoice ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "") : "";
        if (!bootToLiveTv && app.isEmpty()) return false;
        int boot = OpenOnStart.boot(this);
        SharedPreferences prefs = getSharedPreferences(OpenOnStart.BOOT_PREFS, MODE_PRIVATE);
        String target = bootToLiveTv ? liveTvPkg : app;
        if (!OpenOnStart.due(boot, prefs.getInt(OpenOnStart.BOOT_KEY, -1), target, getPackageName())) return false;
        if (!prefs.edit().putInt(OpenOnStart.BOOT_KEY, boot).commit()) return false;
        Intent i = bootToLiveTv ? liveTvIntent() : OpenOnStart.intent(getPackageManager(), app);
        if (i == null) return false;
        // open_on_start_network_wait: Home stays on screen until the network is back or the wait is over.
        if (openOnStartWaitMs > 0 && !OpenOnStart.online(this)) {
            startPending = i;
            startDeadline = SystemClock.elapsedRealtime() + openOnStartWaitMs;
            handler.postDelayed(openWhenOnline, openOnStartWaitMs);
            // Validated between the check above and startPending: networkCallback may have skipped it.
            if (OpenOnStart.online(this)) handler.post(openWhenOnline);
            return false;
        }
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** `input_sources` in config.xml or a theme: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    private Integer mappedSource(String inputId) {
        return InputSources.mapped(inputSources, inputId);
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). An `input_action` written as "package/.Activity" starts that activity
     *  itself, ahead of the standard way, for firmware that answers the channel URI with "unsupported"
     *  (Redmi X65, MiTV-NSOP0: com.xiaomi.mitv.tvplayer/.ExternalSourceActivity). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        // A CEC device is a child input of its HDMI port; the vendor source number belongs to the port
        // (MiTV-MFFU0). Only that lookup uses the parent: the generic path below still opens the child.
        String sourceId = inputId;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            try {
                TvInputInfo info = tim.getTvInputInfo(inputId);
                if (info != null && info.getParentId() != null) sourceId = info.getParentId();
            } catch (RuntimeException ignored) {
            }
        }
        Integer source = mappedSource(sourceId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                startActivity(inputActionIntent().putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        } else if (source == null && InputSources.isComponent(inputAction)) {
            // An activity named in input_action is the vendor's own source picker (Xiaomi: tvplayer's
            // ExternalSourceActivity). Its firmware answers the standard channel URI with "unsupported", so the
            // row goes straight there; the picker is the overlay the remote's Source button opens.
            try {
                startActivity(inputActionIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Aim it at the TV app first: apps installed later (a streaming app, a file manager) can claim the same
        // channel URI too, and with two or more handlers Android shows a chooser instead of the input (WhaleOS).
        if (!liveTvPkg.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(inputActionIntent().setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** `input_action` as an intent: an explicit component when it reads "package/.Activity", else an action. */
    private Intent inputActionIntent() {
        ComponentName component = InputSources.isComponent(inputAction)
                ? ComponentName.unflattenFromString(inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(inputAction);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
        if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        int width = (int) (180 * density);
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        // inputs_connected_only: ports without a connected device and the AV jacks fold behind a "More inputs" row.
        boolean connectedOnly = inputsConnectedOnly;
        List<View> folded = new ArrayList<>();
        boolean sawNonHdmi = false;
        // An explicit root URI asks the Live TV app to leave any HDMI source selected by its own UI.
        boolean rootChannel = TvContract.Channels.CONTENT_URI.toString().equals(liveTvInputId);
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (liveTvInputId.isEmpty() || rootChannel || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
                if (Integer.valueOf(-1).equals(mappedSource(info.getId()))) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = InputNames.tidy(info.loadCustomLabel(this));
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) {
                    View line = addSeparator(rows);
                    if (connectedOnly) fold(line, folded);
                }
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = InputNames.tidy(device.loadCustomLabel(this));
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : tim.getInputState(deviceId);
                        View row = addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                        if (connectedOnly && InputFold.folds(true, state)) fold(row, folded);
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                View row = addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
                if (connectedOnly && InputFold.folds(hdmi, state)) fold(row, folded);
            }
        }
        if (!folded.isEmpty()) {
            View more = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) more.findViewById(R.id.icon)).setImageResource(R.drawable.ic_input);
            ((TextView) more.findViewById(R.id.title)).setText(R.string.more_inputs);
            more.findViewById(R.id.status).setVisibility(View.GONE);
            more.findViewById(R.id.dot).setVisibility(View.GONE);
            more.setOnClickListener(v -> {
                for (View f : folded) f.setVisibility(View.VISIBLE);
                rows.removeView(more);
                for (View f : folded) {
                    if (f.isFocusable()) { f.requestFocus(); break; }
                }
            });
            rows.addView(more);
        }
        // Coocaa OS registers nothing with TvInputManager; com.skyworth.tv lists its sources instead.
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(this, s)) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty() && !rootChannel;
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            ComponentName handler = tuned.resolveActivity(getPackageManager());
            if (handler != null) {
                // On some MediaTek sets the tuner passthrough URI leaves the app's HDMI source selected.
                // The stock launcher addresses the same activity with the root channel URI instead (#1941).
                liveTv = rootChannel
                        ? new Intent(Intent.ACTION_VIEW).setData(TvContract.Channels.CONTENT_URI).setComponent(handler)
                        : tuned;
            }
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        // The buttons sit at the right of the header (the left in Hebrew): hang the menu inward from them.
        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END;
        dropIn(popup, panel, anchor, alignment);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private static void fold(View view, List<View> folded) {
        view.setVisibility(View.GONE);
        folded.add(view);
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private View addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
        return line;
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, getString(R.string.launcher_settings), () -> LauncherSettingsActivity.open(this));
        if (moduleCards) addSettingsMenuRow(rows, popup, getString(R.string.module_cards), this::showModuleCards);
        if (cfg.appLibrary) addSettingsMenuRow(rows, popup, getString(R.string.app_library), this::showAppLibrary);
        // Firmware that ships its own settings app next to the AOSP one (Xiaomi China is one): the
        // vendor app holds pages the AOSP settings has no row for, such as Account & Security with
        // the ADB switch. Offered here only while one of them is installed.
        if (SystemSettings.vendorIntent(this) != null) {
            addSettingsMenuRow(rows, popup, getString(R.string.native_settings), () -> {
                Intent vendor = SystemSettings.vendorIntent(this);
                if (vendor != null) open(vendor);
            });
        }
        // A launcher may not restart the TV itself; the Home key helper can open the system power menu instead.
        if (HomeKeyService.canOpenPowerMenu()) addSettingsMenuRow(rows, popup, getString(R.string.power_menu), HomeKeyService::openPowerMenu);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, CharSequence title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) {
            return;
        }
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        if (rtl) panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);
        if (rtl) {
            LinearLayout buttons = (LinearLayout) keep.getParent();
            buttons.removeAllViews();
            buttons.addView(show);
            buttons.addView(keep);
            keep.setNextFocusLeftId(R.id.btnShow);
            keep.setNextFocusRightId(R.id.btnKeep);
            show.setNextFocusLeftId(R.id.btnShow);
            show.setNextFocusRightId(R.id.btnKeep);
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the reload chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                return v.getId() == (rtl ? R.id.btnShow : R.id.btnKeep);
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && v.getId() == (rtl ? R.id.btnKeep : R.id.btnShow);
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reload(true);
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            panel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
                LinearLayout heading = (LinearLayout) row.findViewById(R.id.title).getParent();
                View status = row.findViewById(R.id.state);
                heading.removeView(status);
                heading.addView(status, 0);
            }
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        // Hebrew places Settings on the left: align the outer panel inward, like its parent menu.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
