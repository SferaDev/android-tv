package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The current notifications WatchListener hears about, for the header bell. Ongoing, media and group summaries stay out. */
final class Notifications {
    static final int MAX_BADGE = 9;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile List<Item> items = Collections.emptyList();
    private static volatile WatchListener service;

    interface Listener {
        /** On the main thread, after the list changed. */
        void onNotificationsChanged();
    }

    static final class Item {
        final String key;
        final String app;
        final String title;
        final String text;
        final long time;
        final PendingIntent contentIntent;

        Item(String key, String app, String title, String text, long time, PendingIntent contentIntent) {
            this.key = key;
            this.app = app;
            this.title = title;
            this.text = text;
            this.time = time;
            this.contentIntent = contentIntent;
        }
    }

    private Notifications() { }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static List<Item> items() {
        return items;
    }

    /** Whether a notification belongs in the list, from its flags and category. */
    static boolean keep(int flags, String category, boolean media) {
        if (media || Notification.CATEGORY_TRANSPORT.equals(category)) return false;
        int hidden = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY;
        return (flags & hidden) == 0;
    }

    /** The count on the bell: empty for none, capped so the pill stays small. */
    static String badge(int count) {
        if (count <= 0) return "";
        return count > MAX_BADGE ? MAX_BADGE + "+" : String.valueOf(count);
    }

    static List<Item> newestFirst(List<Item> list) {
        List<Item> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Long.compare(b.time, a.time));
        return sorted;
    }

    /** Null when the notification is not for the list or has nothing to show. */
    static Item summarize(Context ctx, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return null;
        Bundle extras = n.extras == null ? new Bundle() : n.extras;
        boolean media = extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
        if (!keep(n.flags, n.category, media)) return null;
        String title = chars(extras, Notification.EXTRA_TITLE);
        String text = chars(extras, Notification.EXTRA_TEXT);
        if (text.isEmpty()) text = chars(extras, Notification.EXTRA_BIG_TEXT);
        if (title.isEmpty() && text.isEmpty()) return null;
        return new Item(sbn.getKey(), appLabel(ctx, sbn.getPackageName()), title, text, sbn.getPostTime(),
                n.contentIntent);
    }

    private static String chars(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static String appLabel(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static void attach(WatchListener listener) {
        service = listener;
    }

    static void detach(WatchListener listener) {
        if (service == listener) service = null;
        publish(Collections.emptyList());
    }

    static void publish(List<Item> next) {
        items = Collections.unmodifiableList(newestFirst(next));
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Listener l : LISTENERS) l.onNotificationsChanged();
        });
    }

    static void dismiss(String key) {
        WatchListener s = service;
        if (s != null) s.cancelNotification(key);
    }

    static void dismissAll() {
        WatchListener s = service;
        if (s == null) return;
        List<String> keys = new ArrayList<>();
        for (Item i : items) keys.add(i.key);
        s.cancelNotifications(keys.toArray(new String[0]));
    }
}
