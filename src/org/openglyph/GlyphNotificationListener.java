package org.openglyph;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Set;

/** Pulses the matrix for notifications that would also make a sound or vibrate. */
public final class GlyphNotificationListener extends NotificationListenerService {
    private final Ranking mRanking = new Ranking();
    /** Keys currently showing, so updates to ONLY_ALERT_ONCE notifications stay quiet. */
    private final Set<String> mShowing = new HashSet<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean seen = !mShowing.add(sbn.getKey());
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
        if (service != null) service.onNotification();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        mShowing.remove(sbn.getKey());
    }
}
