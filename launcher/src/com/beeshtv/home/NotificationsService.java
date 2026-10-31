package com.beeshtv.home;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lets the launcher list and clear notifications. Android only connects it once notification access
 * is granted (install.sh does that with `cmd notification allow_listener`).
 */
public class NotificationsService extends NotificationListenerService {
    private static NotificationsService instance;
    static Runnable onChange;

    @Override public void onListenerConnected() { instance = this; changed(); }
    @Override public void onListenerDisconnected() { instance = null; changed(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { changed(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { changed(); }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) new Handler(Looper.getMainLooper()).post(r);
    }

    static boolean connected() { return instance != null; }

    /** Notifications a person would want to see: clearable, not ongoing, not group summaries. Newest first. */
    static List<StatusBarNotification> visible() {
        List<StatusBarNotification> out = new ArrayList<>();
        NotificationsService s = instance;
        if (s == null) return out;
        StatusBarNotification[] all;
        try { all = s.getActiveNotifications(); } catch (RuntimeException e) { return out; }
        if (all == null) return out;
        for (StatusBarNotification n : all) {
            int flags = n.getNotification().flags;
            if (!n.isClearable() || n.isOngoing() || (flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            out.add(n);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        return out;
    }

    static void clearAll() {
        NotificationsService s = instance;
        if (s != null) s.cancelAllNotifications();
    }

    static void dismiss(StatusBarNotification n) {
        NotificationsService s = instance;
        if (s != null) s.cancelNotification(n.getKey());
    }
}
