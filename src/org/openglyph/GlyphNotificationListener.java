package org.openglyph;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Set;

/**
 * Pulses the matrix for notifications that would also make a sound or vibrate, with the app's
 * own pattern, and keeps the "essential" dot lit while a notification from an essential app
 * is still there.
 */
public final class GlyphNotificationListener extends NotificationListenerService {
    private static volatile GlyphNotificationListener sInstance;

    private final Ranking mRanking = new Ranking();
    /** Keys currently showing, so updates to ONLY_ALERT_ONCE notifications stay quiet. */
    private final Set<String> mShowing = new HashSet<>();

    /** Re-scans after the essential-apps setting changed. */
    static void refreshEssential() {
        GlyphNotificationListener l = sInstance;
        if (l != null) l.updateEssential();
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
        StatusBarNotification[] active = getActiveNotifications();
        if (active != null) for (StatusBarNotification sbn : active) mShowing.add(sbn.getKey());
        updateEssential();
    }

    @Override
    public void onListenerDisconnected() {
        sInstance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean seen = !mShowing.add(sbn.getKey());
        updateEssential();
        Notification n = sbn.getNotification();
        if (sbn.isOngoing() || !sbn.isClearable()) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (seen && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) return;
        if (rankingMap != null && rankingMap.getRanking(sbn.getKey(), mRanking)) {
            if (mRanking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return;
            if (mRanking.isSuspended()) return;
        }
        if (getCurrentInterruptionFilter() > INTERRUPTION_FILTER_ALL) return; // Do Not Disturb

        GlyphService service = GlyphService.get();
        if (service != null) service.onNotification(sbn.getPackageName());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        mShowing.remove(sbn.getKey());
        updateEssential();
    }

    private void updateEssential() {
        GlyphService service = GlyphService.get();
        if (service == null) return;
        boolean waiting = false;
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException e) {
            return; // not connected yet
        }
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                if (sbn.isClearable() && !sbn.isOngoing()
                        && service.isEssentialApp(sbn.getPackageName())) {
                    waiting = true;
                    break;
                }
            }
        }
        service.setEssentialWaiting(waiting);
    }
}
