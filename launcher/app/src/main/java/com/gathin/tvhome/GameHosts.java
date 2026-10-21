package com.gathin.tvhome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GameStream hosts (Sunshine) on the local network, found over mDNS, and the choice of one; used by the Games tab
 * and the Games settings page. Start it while a screen lists hosts and stop it when the screen goes.
 */
final class GameHosts {
    private static final String TAG = "TvHome.GameHosts";
    static final String SERVICE_TYPE = "_nvstream._tcp";
    static final long TIMEOUT_MS = 10_000;
    private static final long RESOLVE_MS = 5_000;
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.:\\-\\[\\]]{1,253}$");

    private final Context ctx;
    private final Runnable onChange;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> found = new LinkedHashMap<>();
    private final Deque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private final Runnable timeout = this::onTimeout;
    private final Runnable giveUp = () -> resolved(null, null);
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock lock;
    private NsdManager.ServiceInfoCallback infoCallback;
    private boolean resolving;
    private boolean timedOut;

    /** onChange runs on the main thread whenever the list or the timeout state changes. */
    GameHosts(Context ctx, Runnable onChange) {
        this.ctx = ctx;
        this.onChange = onChange;
    }

    /** Host name to address, in the order they answered. */
    Map<String, String> found() {
        return found;
    }

    /** Nothing answered within {@link #TIMEOUT_MS} of the start. */
    boolean timedOut() {
        return timedOut && found.isEmpty();
    }

    /** No-op while running, and after a timeout or failure until {@link #stop} (Try again stops, then starts). */
    void start() {
        if (discovery != null || timedOut) return;
        Context app = ctx.getApplicationContext();
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            timedOut = true;
            main.post(onChange);
            return;
        }
        // Without it many TVs drop the multicast replies on Wi-Fi; harmless on Ethernet.
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            try {
                lock = wifi.createMulticastLock("tvhome-gamehosts");
                lock.setReferenceCounted(false);
                lock.acquire();
            } catch (RuntimeException e) {
                Log.w(TAG, "No multicast lock: " + e);
                lock = null;
            }
        }
        timedOut = false;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    if (discovery == null) return;
                    toResolve.add(info);
                    resolveNext();
                });
            }

            @Override public void onServiceLost(NsdServiceInfo info) {
                main.post(() -> {
                    if (found.remove(info.getServiceName()) != null) onChange.run();
                });
            }

            @Override public void onStartDiscoveryFailed(String type, int error) {
                Log.w(TAG, "Discovery failed to start: " + error);
                main.post(() -> {
                    discovery = null;
                    releaseLock();
                    timedOut = true;
                    onChange.run();
                });
            }

            @Override public void onStopDiscoveryFailed(String type, int error) {
                Log.w(TAG, "Discovery failed to stop: " + error);
            }

            @Override public void onDiscoveryStarted(String type) { }

            @Override public void onDiscoveryStopped(String type) { }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not discover: " + e);
            discovery = null;
            releaseLock();
            timedOut = true;
            main.post(onChange);
            return;
        }
        main.postDelayed(timeout, TIMEOUT_MS);
    }

    void stop() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(giveUp);
        if (discovery != null && nsd != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        discovery = null;
        unregisterInfoCallback();
        toResolve.clear();
        resolving = false;
        timedOut = false;
        releaseLock();
    }

    private void onTimeout() {
        timedOut = true;
        if (found.isEmpty()) onChange.run();
    }

    private void releaseLock() {
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
    }

    /** One at a time: before Android 14 a second resolve fails while the first runs. */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || toResolve.isEmpty() || nsd == null || discovery == null) return;
        resolving = true;
        NsdServiceInfo next = toResolve.poll();
        main.postDelayed(giveUp, RESOLVE_MS);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                infoCallback = new NsdManager.ServiceInfoCallback() {
                    @Override public void onServiceInfoCallbackRegistrationFailed(int error) {
                        main.post(() -> resolved(next.getServiceName(), null));
                    }

                    @Override public void onServiceUpdated(NsdServiceInfo info) {
                        List<InetAddress> hosts = info.getHostAddresses();
                        // An update can come before the addresses do; the give-up timer covers one that never does.
                        if (hosts.isEmpty()) return;
                        String address = pick(hosts);
                        main.post(() -> resolved(next.getServiceName(), address));
                    }

                    @Override public void onServiceLost() { }

                    @Override public void onServiceInfoCallbackUnregistered() { }
                };
                nsd.registerServiceInfoCallback(next, main::post, infoCallback);
            } else {
                nsd.resolveService(next, new NsdManager.ResolveListener() {
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        String address = info.getHost() == null ? null : info.getHost().getHostAddress();
                        main.post(() -> resolved(info.getServiceName(), address));
                    }

                    @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                        Log.w(TAG, "Resolve failed: " + error);
                        main.post(() -> resolved(info.getServiceName(), null));
                    }
                });
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not resolve: " + e);
            main.removeCallbacks(giveUp);
            resolving = false;
            infoCallback = null;
        }
    }

    /** IPv4 first: GameStream clients and the address field expect it. */
    static String pick(List<InetAddress> addresses) {
        for (InetAddress a : addresses) if (a.getAddress().length == 4) return a.getHostAddress();
        return addresses.get(0).getHostAddress();
    }

    private void resolved(String name, String address) {
        if (!resolving) return;
        main.removeCallbacks(giveUp);
        unregisterInfoCallback();
        resolving = false;
        if (name != null && address != null && discovery != null && !address.equals(found.put(name, address))) {
            onChange.run();
        }
        resolveNext();
    }

    private void unregisterInfoCallback() {
        if (infoCallback == null || nsd == null || Build.VERSION.SDK_INT < 34) return;
        try {
            nsd.unregisterServiceInfoCallback(infoCallback);
        } catch (RuntimeException ignored) {
        }
        infoCallback = null;
    }

    static boolean valid(String address) {
        return address != null && HOST.matcher(address).matches();
    }

    /** Makes address the games PC, forgetting the pairing with any other; false when it could not be saved. */
    static boolean choose(Context ctx, String address) {
        return address.equals(Config.load(ctx).gamestreamHost) || save(ctx, address);
    }

    static boolean forget(Context ctx) {
        return save(ctx, "");
    }

    private static boolean save(Context ctx, String address) {
        SteamGames.forget(ctx);
        return UserSettings.edit(ctx, root -> {
            JSONObject c = root.getJSONObject(UserSettings.CONFIG);
            c.put("gamestream_host", address);
            c.remove("moonlight_host_uuid");
            c.remove("moonlight_host_name");
        });
    }
}
