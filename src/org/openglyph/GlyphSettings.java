package org.openglyph;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openglyph.core.AppPatterns;
import org.openglyph.core.Bedtime;
import org.openglyph.core.EssentialRule;

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
    static final String BEDTIME = "open_glyph_bedtime";              // 1/0, default 0
    static final String BEDTIME_START = "open_glyph_bedtime_start";  // minute of day, default 23:00
    static final String BEDTIME_END = "open_glyph_bedtime_end";      // minute of day, default 07:00
    static final String BEDTIME_DAYS = "open_glyph_bedtime_days";    // bitmask, Monday = bit 0
    static final String FLIP = "open_glyph_flip";                    // 1/0, default 0
    /** Old essential-apps setting ("pkg,pkg"); read once to seed the first rule. */
    static final String ESSENTIAL_APPS = "open_glyph_essential_apps";
    static final String ESSENTIAL_RULES = "open_glyph_essential_rules"; // EssentialRule.format
    static final String AOD_TOY = "open_glyph_aod_toy";              // "" = clock, else SDK toy id
    /** Active toy ids in Glyph Button order, "a,b,c" ("off" is implied last). v2: stock set. */
    static final String TOY_ORDER = "open_glyph_toy_order_v2";
    static final String MUSIC = "open_glyph_music";                  // 1/0, default 0
    static final String GEN_SOUNDS = "open_glyph_generative_sounds"; // 1/0, default 0
    static final String FLIP_MODE = "open_glyph_flip_mode";          // SILENT|VIBRATE, default SILENT
    static final String POCKET = "open_glyph_pocket";                // 1/0, default 0
    static final String PROGRESS = "open_glyph_progress";            // 1/0, default 1
    static final String AUTO_BRIGHTNESS = "open_glyph_auto_brightness"; // 1/0, default 0
    /** Read by Telecom (RingtoneFactory) too: each caller rings with their own generative sound. */
    static final String GEN_RINGTONE = "open_glyph_generative_ringtone"; // 1/0, default 0
    static final String SEED = "open_glyph_seed";                    // text, random per device
    static final String ENGINE = "open_glyph_engine";                // DIODE|CHEMICAL
    static final String CLOCK_FACE = "open_glyph_clock_face";        // 0..Clock.FACES-1, default 0
    /** Glyph Toys timeout in ms, one of ToyHost.TIMEOUTS_MS (the stock choices). */
    static final String TOY_TIMEOUT = "open_glyph_toy_timeout";      // default ToyHost.IDLE_MS

    static final String[] ALL = {
        ENABLED, BRIGHTNESS, NOTIFICATIONS, CHARGING, CALLS, RECORD_LED, TOYS,
        AOD, BEDTIME, BEDTIME_START, BEDTIME_END, BEDTIME_DAYS, FLIP, ESSENTIAL_RULES,
        AOD_TOY, TOY_ORDER, MUSIC, GEN_SOUNDS, GEN_RINGTONE, FLIP_MODE, POCKET, AUTO_BRIGHTNESS, PROGRESS, ENGINE, SEED,
        CLOCK_FACE, TOY_TIMEOUT,
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

    static long toyTimeoutMs(ContentResolver cr) {
        long ms = Settings.Secure.getLong(cr, TOY_TIMEOUT, org.openglyph.toys.ToyHost.IDLE_MS);
        for (long choice : org.openglyph.toys.ToyHost.TIMEOUTS_MS) if (choice == ms) return ms;
        return org.openglyph.toys.ToyHost.IDLE_MS;
    }

    static String toyKey(String toyId) {
        return TOY_PREFIX + toyId;
    }

    /**
     * Active toys in Glyph Button order, without "off". Until the user arranges them in
     * Settings: every built-in toy, in the default order (SDK toys are added by the user).
     */
    static List<String> activeToys(ContentResolver cr) {
        String order = Settings.Secure.getString(cr, TOY_ORDER);
        List<String> out = new ArrayList<>();
        if (order == null) {
            for (org.openglyph.toys.Toy t : org.openglyph.toys.Toys.all()) {
                if (!OFF.equals(t.id())) out.add(t.id());
            }
            return out;
        }
        for (String id : order.split(",")) {
            if (!id.isEmpty() && !OFF.equals(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    static final String OFF = "off";

    static void setActiveToys(ContentResolver cr, List<String> ids) {
        Settings.Secure.putString(cr, TOY_ORDER, String.join(",", ids));
    }

    static List<EssentialRule> essentialRules(ContentResolver cr) {
        String v = Settings.Secure.getString(cr, ESSENTIAL_RULES);
        if (v != null) return EssentialRule.parse(v);
        // First run after the update: the old essential apps become the first rule.
        List<EssentialRule> rules = new ArrayList<>();
        Set<String> apps = AppPatterns.parseSet(string(cr, ESSENTIAL_APPS, ""));
        if (!apps.isEmpty()) {
            EssentialRule r = new EssentialRule();
            r.name = "Default";
            r.apps.addAll(apps);
            r.icon = EssentialRule.ICON_APP;
            rules.add(r);
        }
        return rules;
    }

    static void setEssentialRules(ContentResolver cr, List<EssentialRule> rules) {
        Settings.Secure.putString(cr, ESSENTIAL_RULES, EssentialRule.format(rules));
    }

    static int bedtimeStart(ContentResolver cr) {
        return integer(cr, BEDTIME_START, 23 * 60);
    }

    static int bedtimeEnd(ContentResolver cr) {
        return integer(cr, BEDTIME_END, 7 * 60);
    }

    static int bedtimeDays(ContentResolver cr) {
        return integer(cr, BEDTIME_DAYS, Bedtime.EVERY_DAY);
    }

    static Uri uri(String key) {
        return Settings.Secure.getUriFor(key);
    }
}
