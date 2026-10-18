package org.openglyph.testnotify;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;

/**
 * Posts one notification as this package (built under several package names), to try
 * OpenGlyph's per-app generative notifications:
 *   adb shell am start -n <pkg>/org.openglyph.testnotify.Post --es text "hello"
 */
public final class Post extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("test", "Test",
                NotificationManager.IMPORTANCE_DEFAULT));
        String text = getIntent().getStringExtra("text");
        nm.notify((int) (System.currentTimeMillis() & 0xffff), new Notification.Builder(this, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getPackageName())
                .setContentText(text == null ? "Test" : text)
                .build());
        finish();
    }
}
