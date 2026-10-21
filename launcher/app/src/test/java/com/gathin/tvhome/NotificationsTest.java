package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class NotificationsTest {
    @Test public void ordinaryNotificationIsKept() {
        assertTrue(Notifications.keep(0, Notification.CATEGORY_MESSAGE, false));
    }

    @Test public void ongoingForegroundSummaryAndMediaAreDropped() {
        assertFalse(Notifications.keep(Notification.FLAG_ONGOING_EVENT, null, false));
        assertFalse(Notifications.keep(Notification.FLAG_FOREGROUND_SERVICE, null, false));
        assertFalse(Notifications.keep(Notification.FLAG_GROUP_SUMMARY, null, false));
        assertFalse(Notifications.keep(0, null, true));
        assertFalse(Notifications.keep(0, Notification.CATEGORY_TRANSPORT, false));
    }

    @Test public void badgeIsEmptyForNoneAndCapped() {
        assertEquals("", Notifications.badge(0));
        assertEquals("3", Notifications.badge(3));
        assertEquals("9", Notifications.badge(9));
        assertEquals("9+", Notifications.badge(10));
    }

    @Test public void newestComesFirst() {
        Notifications.Item old = new Notifications.Item("a", "App", "Old", "", 100, null);
        Notifications.Item fresh = new Notifications.Item("b", "App", "New", "", 200, null);
        List<Notifications.Item> sorted = Notifications.newestFirst(Arrays.asList(old, fresh));
        assertEquals("b", sorted.get(0).key);
        assertEquals("a", sorted.get(1).key);
    }
}
