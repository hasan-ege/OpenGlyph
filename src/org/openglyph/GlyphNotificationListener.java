package org.openglyph;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openglyph.core.EssentialRule;

/**
 * The notification engine:
 * - pulses the matrix for notifications that would also make a sound or vibrate, with the
 *   app's own pattern;
 * - Essential Notifications: while a notification matching one of the user's rules (apps,
 *   people, keywords) is waiting, the rule's icon stays on the matrix.
 */
public final class GlyphNotificationListener extends NotificationListenerService {
    private static volatile GlyphNotificationListener sInstance;

    private final Ranking mRanking = new Ranking();
    /** Keys currently showing, so updates to ONLY_ALERT_ONCE notifications stay quiet. */
    private final Set<String> mShowing = new HashSet<>();

    /**
     * The caller of the ringing call as the Phone app's incoming-call notification shows it:
     * the contact's name, or the number when they are not in contacts; null if none.
     */
    static String callerName() {
        GlyphNotificationListener l = sInstance;
        if (l == null) return null;
        StatusBarNotification[] active = l.getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification sbn : active) {
            Notification n = sbn.getNotification();
            if (!Notification.CATEGORY_CALL.equals(n.category)) continue;
            android.app.Person p = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON,
                    android.app.Person.class);
            if (p != null && p.getName() != null && p.getName().length() > 0) {
                return p.getName().toString();
            }
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null && title.length() > 0) return title.toString();
        }
        return null;
    }

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
        updateProgress();
    }

    @Override
    public void onListenerDisconnected() {
        sInstance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        boolean seen = !mShowing.add(sbn.getKey());
        updateEssential();
        updateProgress();
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
        updateProgress();
    }

    /** The Clock app's countdown timer notification (one or more timers). */
    private static boolean isClockTimer(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return "com.android.deskclock".equals(sbn.getPackageName())
                && n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
                && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    /**
     * Glyph Progress: the newest Live Update (Android 16 promoted ongoing notification, e.g.
     * Notification.ProgressStyle) that carries progress goes to the matrix; none clears it.
     */
    private void updateProgress() {
        GlyphService service = GlyphService.get();
        if (service == null) return;
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException e) {
            return;
        }
        StatusBarNotification newest = null;
        StatusBarNotification timer = null;
        if (active != null) {
            for (StatusBarNotification sbn : active) {
                Notification n = sbn.getNotification();
                if (isClockTimer(sbn)) {
                    timer = sbn; // the hourglass shows it, not the slider
                    continue;
                }
                boolean live = (n.flags & Notification.FLAG_PROMOTED_ONGOING) != 0
                        || n.isStyle(Notification.ProgressStyle.class);
                boolean hasProgress = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
                        || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE);
                if (!live || !hasProgress) continue;
                if (newest == null || sbn.getPostTime() > newest.getPostTime()) newest = sbn;
            }
        }
        if (timer == null) {
            org.openglyph.toys.Hourglass.timerGone();
        } else {
            Notification n = timer.getNotification();
            if (n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
                org.openglyph.toys.Hourglass.timerRunning(n.when, System.currentTimeMillis());
            } else {
                org.openglyph.toys.Hourglass.timerPaused(n.when - timer.getPostTime());
            }
        }
        service.onTimerChanged();
        if (newest == null) {
            service.onLiveProgress(-1, false);
            return;
        }
        android.os.Bundle ex = newest.getNotification().extras;
        int max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        boolean indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) || max <= 0;
        int pct = indeterminate ? 0
                : Math.max(0, Math.min(100, Math.round(100f * ex.getInt(Notification.EXTRA_PROGRESS, 0) / max)));
        service.onLiveProgress(pct, indeterminate);
    }

    private void updateEssential() {
        GlyphService service = GlyphService.get();
        if (service == null) return;
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException e) {
            return; // not connected yet
        }
        List<EssentialRule> rules = service.essentialRules();
        StatusBarNotification newest = null;
        EssentialRule newestRule = null;
        if (active != null && !rules.isEmpty()) {
            for (StatusBarNotification sbn : active) {
                if (!sbn.isClearable() || sbn.isOngoing()) continue;
                if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                EssentialRule rule = EssentialRule.firstMatch(rules, notice(sbn));
                if (rule != null && (newest == null || sbn.getPostTime() > newest.getPostTime())) {
                    newest = sbn;
                    newestRule = rule;
                }
            }
        }
        service.setEssential(newest == null ? null : newest.getPackageName(), newestRule);
    }

    /** What the rules look at: app, title, text and the people a notification is from. */
    static EssentialRule.Notice notice(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        StringBuilder title = new StringBuilder(text(extras, Notification.EXTRA_TITLE));
        String conversation = text(extras, Notification.EXTRA_CONVERSATION_TITLE);
        if (!conversation.isEmpty()) title.append('\n').append(conversation);
        StringBuilder body = new StringBuilder(text(extras, Notification.EXTRA_TEXT));
        String big = text(extras, Notification.EXTRA_BIG_TEXT);
        if (!big.isEmpty()) body.append('\n').append(big);

        List<String> people = new ArrayList<>();
        ArrayList<Person> list = extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                Person.class);
        if (list != null) for (Person p : list) if (p.getName() != null) people.add(p.getName().toString());
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null) {
            for (Notification.MessagingStyle.Message m
                    : Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)) {
                Person sender = m.getSenderPerson();
                if (sender != null && sender.getName() != null) people.add(sender.getName().toString());
                if (m.getText() != null) body.append('\n').append(m.getText());
            }
        }
        return new EssentialRule.Notice(sbn.getPackageName(), title.toString(), body.toString(), people);
    }

    private static String text(Bundle extras, String key) {
        CharSequence cs = extras.getCharSequence(key);
        return cs == null ? "" : cs.toString();
    }
}
