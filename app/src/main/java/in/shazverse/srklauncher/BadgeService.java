package in.shazverse.srklauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/** Counts unread notifications per app for red iOS-style badges (and enables media controls). */
public class BadgeService extends NotificationListenerService {
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile Runnable onChange;
    static volatile boolean connected;

    static int count(String pkg) {
        synchronized (COUNTS) {
            Integer i = COUNTS.get(pkg);
            return i == null ? 0 : i;
        }
    }

    @Override
    public void onListenerConnected() {
        connected = true;
        recount();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        recount();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        recount();
    }

    private void recount() {
        StatusBarNotification[] arr;
        try {
            arr = getActiveNotifications();
        } catch (Exception e) {
            return;
        }
        if (arr == null) return;
        Map<String, Integer> m = new HashMap<>();
        for (StatusBarNotification sbn : arr) {
            Notification n = sbn.getNotification();
            if (n == null || !sbn.isClearable()) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            int c = n.number > 0 ? n.number : 1;
            Integer old = m.get(sbn.getPackageName());
            m.put(sbn.getPackageName(), old == null ? c : old + c);
        }
        synchronized (COUNTS) {
            COUNTS.clear();
            COUNTS.putAll(m);
        }
        Runnable r = onChange;
        if (r != null) MAIN.post(r);
    }
}
