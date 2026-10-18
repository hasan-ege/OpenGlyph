package org.openglyph;

import android.app.NotificationChannel;
import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Hears about new notification channels the moment an app creates them, so its generative
 * sound is set before its first notification (apps usually create channels at start-up).
 * Registered as a system listener (we run as the system uid), which is what makes NMS report
 * channel changes; it is never bound and posts nothing.
 */
final class ChannelWatcher extends NotificationListenerService {
    private final Consumer<String> mOnNewChannel;
    private boolean mRegistered;

    ChannelWatcher(Consumer<String> onNewChannel) {
        mOnNewChannel = onNewChannel;
    }

    void register(Context context) {
        try {
            registerAsSystemService(context, new ComponentName(context, ChannelWatcher.class),
                    UserHandle.USER_ALL);
            mRegistered = true;
        } catch (Exception e) {
            Log.w(GlyphService.TAG, "channel watcher unavailable", e);
        }
    }

    void unregister() {
        if (!mRegistered) return;
        try {
            unregisterAsSystemService();
        } catch (Exception ignored) {
        }
        mRegistered = false;
    }

    @Override
    public void onNotificationChannelModified(String pkg, UserHandle user,
            NotificationChannel channel, int modificationType) {
        if (modificationType == NOTIFICATION_CHANNEL_OR_GROUP_ADDED) mOnNewChannel.accept(pkg);
    }
}
