package org.openglyph;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

/**
 * OpenGlyph's settings, stored in Settings.Secure so the service, the Settings screen and
 * `adb shell settings put secure ...` all see the same values.
 */
final class GlyphSettings {
    static final String ENABLED = "open_glyph_enabled";              // 1/0, default 1
    static final String BRIGHTNESS = "open_glyph_brightness";        // 0..255, default 255
    static final String NOTIFICATIONS = "open_glyph_notifications";  // 1/0, default 1
    static final String CHARGING = "open_glyph_charging";            // 1/0, default 1
    static final String CALLS = "open_glyph_calls";                  // 1/0, default 1
    static final String RECORD_LED = "open_glyph_record_led";        // 1/0, default 1
    static final String TOYS = "open_glyph_toys";                    // 1/0, default 1
    static final String TOY_PREFIX = "open_glyph_toy_";              // + toy id, 1/0, default 1
    static final String AOD = "open_glyph_aod";                      // OFF|ALWAYS|FACE_DOWN|CHARGING
    static final String NIGHT_START = "open_glyph_night_start";      // hour, -1 = none, default 23
    static final String NIGHT_END = "open_glyph_night_end";          // hour, default 7
    static final String FLIP = "open_glyph_flip";                    // 1/0, default 0
    static final String ESSENTIAL_APPS = "open_glyph_essential_apps"; // "pkg,pkg"
    static final String APP_PATTERNS = "open_glyph_app_patterns";    // "pkg=RING;pkg=SWEEP"

    static final String[] ALL = {
        ENABLED, BRIGHTNESS, NOTIFICATIONS, CHARGING, CALLS, RECORD_LED, TOYS,
        AOD, NIGHT_START, NIGHT_END, FLIP, ESSENTIAL_APPS, APP_PATTERNS,
    };

    private GlyphSettings() {}

    static boolean flag(ContentResolver cr, String key) {
        return Settings.Secure.getInt(cr, key, 1) != 0;
    }

    static void setFlag(ContentResolver cr, String key, boolean on) {
        Settings.Secure.putInt(cr, key, on ? 1 : 0);
    }

    static boolean flagDefaultOff(ContentResolver cr, String key) {
        return Settings.Secure.getInt(cr, key, 0) != 0;
    }

    static String string(ContentResolver cr, String key, String fallback) {
        String v = Settings.Secure.getString(cr, key);
        return v == null ? fallback : v;
    }

    static int integer(ContentResolver cr, String key, int fallback) {
        return Settings.Secure.getInt(cr, key, fallback);
    }

    static int brightness(ContentResolver cr) {
        return Math.max(0, Math.min(255, Settings.Secure.getInt(cr, BRIGHTNESS, 255)));
    }

    static String toyKey(String toyId) {
        return TOY_PREFIX + toyId;
    }

    static Set<String> enabledToys(ContentResolver cr, Iterable<String> toyIds) {
        Set<String> out = new HashSet<>();
        for (String id : toyIds) if (flag(cr, toyKey(id))) out.add(id);
        return out;
    }

    static Uri uri(String key) {
        return Settings.Secure.getUriFor(key);
    }
}
