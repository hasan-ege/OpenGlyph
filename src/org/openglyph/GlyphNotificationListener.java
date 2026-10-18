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
