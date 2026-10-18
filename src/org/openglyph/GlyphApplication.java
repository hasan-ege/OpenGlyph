package org.openglyph;

import android.app.Application;
import android.content.Intent;

/** The app is persistent, so the system starts this process at boot; bring the service up. */
public final class GlyphApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, GlyphService.class));
    }
}
