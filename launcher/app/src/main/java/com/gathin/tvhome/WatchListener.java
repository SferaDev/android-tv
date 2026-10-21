package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Enabled through Grants so the launcher may read the notifications (header bell). */
public class WatchListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        Notifications.attach(this);
        refresh();
    }

    @Override
    public void onListenerDisconnected() {
        Notifications.detach(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refresh();
    }

    private void refresh() {
        List<Notifications.Item> list = new ArrayList<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            for (int i = 0; active != null && i < active.length; i++) {
                Notifications.Item item = Notifications.summarize(this, active[i]);
                if (item != null) list.add(item);
            }
        } catch (RuntimeException e) {
            // The system may drop the binding mid-call; the next callback refreshes.
            return;
        }
        Notifications.publish(list);
    }
}
