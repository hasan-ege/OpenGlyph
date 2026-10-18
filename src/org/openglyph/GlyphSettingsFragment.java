package org.openglyph;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;

import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;

import com.android.settingslib.widget.MainSwitchPreference;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.openglyph.core.Bedtime;

/**
 * Glyph Interface (Settings > Glyph Matrix). Every value lives in a Settings.Secure key that
 * the service observes; nothing is stored in SharedPreferences.
 */
public final class GlyphSettingsFragment extends GlyphFragment {
    /** Tile switches that map 1:1 to a flag (default on). */
    private static final String[] FLAGS = {
        GlyphSettings.TOYS, GlyphSettings.NOTIFICATIONS, GlyphSettings.CALLS,
        GlyphSettings.CHARGING, GlyphSettings.RECORD_LED,
    };

    @Override
    int titleRes() {
        return R.string.app_name;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.glyph_settings, rootKey);
        ContentResolver cr = cr();

        MainSwitchPreference main = findPreference(GlyphSettings.ENABLED);
        main.addOnSwitchChangeListener((button, on) -> {
            GlyphSettings.setFlag(cr, GlyphSettings.ENABLED, on);
            updateEnabledState(on);
        });

        SeekBarPreference brightness = findPreference(GlyphSettings.BRIGHTNESS);
        brightness.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putInt(cr, GlyphSettings.BRIGHTNESS, (Integer) value);
            return true;
        });

        for (String key : FLAGS) {
            tile(key).setSwitch(GlyphSettings.flag(cr, key),
                    (t, on) -> GlyphSettings.setFlag(cr, key, on));
        }
        tile(GlyphSettings.FLIP).setSwitch(GlyphSettings.flagDefaultOff(cr, GlyphSettings.FLIP),
                (t, on) -> GlyphSettings.setFlag(cr, GlyphSettings.FLIP, on));
        tile(GlyphSettings.AOD).setSwitch(false, (t, on) -> {
            String mode = GlyphSettings.string(cr, GlyphSettings.AOD, "OFF");
            if (on && "OFF".equals(mode)) mode = "ALWAYS";
            if (!on) {
                // Remember the chosen mode for the next time it is switched on.
                if (!"OFF".equals(mode)) Settings.Secure.putString(cr, AodToyFragment.LAST_MODE, mode);
                mode = "OFF";
            } else if ("ALWAYS".equals(mode)) {
                mode = GlyphSettings.string(cr, AodToyFragment.LAST_MODE, "ALWAYS");
            }
            Settings.Secure.putString(cr, GlyphSettings.AOD, mode);
            refresh();
        });
        tile(GlyphSettings.BEDTIME).setSwitch(false, (t, on) -> {
            GlyphSettings.setFlag(cr, GlyphSettings.BEDTIME, on);
            refresh();
        });

        link(GlyphSettings.TOYS, ToysFragment::new);
        link(GlyphSettings.NOTIFICATIONS, NotificationsFragment::new);
        link("essential", EssentialFragment::new);
        link(GlyphSettings.AOD, AodToyFragment::new);
        link(GlyphSettings.BEDTIME, BedtimeFragment::new);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private TilePreference tile(String key) {
        return findPreference(key);
    }

    /** Re-reads everything: sub-screens and adb may have changed values meanwhile. */
    private void refresh() {
        Context c = requireContext();
        ContentResolver cr = cr();
        MainSwitchPreference main = findPreference(GlyphSettings.ENABLED);
        main.setChecked(GlyphSettings.flag(cr, GlyphSettings.ENABLED));
        ((SeekBarPreference) findPreference(GlyphSettings.BRIGHTNESS))
                .setValue(GlyphSettings.brightness(cr));
        for (String key : FLAGS) tile(key).setChecked(GlyphSettings.flag(cr, key));
        tile(GlyphSettings.FLIP).setChecked(GlyphSettings.flagDefaultOff(cr, GlyphSettings.FLIP));

        // Toys: a strip of the active toys, like the stock tile.
        List<ToyCatalog.Entry> toys = ToyCatalog.all(c);
        List<String> ids = new ArrayList<>();
        for (ToyCatalog.Entry e : toys) ids.add(e.id);
        List<Drawable> strip = new ArrayList<>();
        int size = getResources().getDimensionPixelSize(R.dimen.og_toy_strip_icon);
        for (String id : GlyphSettings.activeToys(cr, ids)) {
            ToyCatalog.Entry e = ToyCatalog.byId(toys, id);
            if (e != null && strip.size() < 5) strip.add(ToyCatalog.icon(e, size));
        }
        tile(GlyphSettings.TOYS).setStrip(strip);

        tile(GlyphSettings.NOTIFICATIONS).setSummary(R.string.settings_notifications_short);
        int rules = 0;
        for (org.openglyph.core.EssentialRule r : GlyphSettings.essentialRules(cr)) if (r.enabled) rules++;
        tile("essential").setSummary(rules == 0 ? c.getString(R.string.essential_none)
                : c.getResources().getQuantityString(R.plurals.essential_rules, rules, rules));

        String mode = GlyphSettings.string(cr, GlyphSettings.AOD, "OFF");
        TilePreference aod = tile(GlyphSettings.AOD);
        aod.setChecked(!"OFF".equals(mode));
        aod.setSummary("OFF".equals(mode) ? c.getString(R.string.aod_off) : AodToyFragment.summary(c));

        TilePreference bedtime = tile(GlyphSettings.BEDTIME);
        bedtime.setChecked(GlyphSettings.flagDefaultOff(cr, GlyphSettings.BEDTIME));
        bedtime.setSummary(bedtimeSummary(c));

        updateEnabledState(main.isChecked());
    }

    /** "23:00 – 07:00, Every day" */
    static String bedtimeSummary(Context c) {
        ContentResolver cr = c.getContentResolver();
        return c.getString(R.string.bedtime_summary,
                time(c, GlyphSettings.bedtimeStart(cr)), time(c, GlyphSettings.bedtimeEnd(cr)),
                days(c, GlyphSettings.bedtimeDays(cr)));
    }

    static String time(Context c, int minuteOfDay) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        return DateFormat.getTimeFormat(c).format(cal.getTime());
    }

    static String days(Context c, int mask) {
        if (mask == Bedtime.EVERY_DAY) return c.getString(R.string.days_every_day);
        if (mask == Bedtime.WEEKDAYS) return c.getString(R.string.days_weekdays);
        if (mask == Bedtime.WEEKEND) return c.getString(R.string.days_weekend);
        if (mask == 0) return c.getString(R.string.days_never);
        String[] names = c.getResources().getStringArray(R.array.day_short_names);
        List<String> on = new ArrayList<>();
        for (int d = 0; d < 7; d++) if ((mask & (1 << d)) != 0) on.add(names[d]);
        return String.join(", ", on);
    }

    private void updateEnabledState(boolean on) {
        for (String key : new String[] {"hero", GlyphSettings.BRIGHTNESS, "toys_category",
                "notifications_category", "features_category", "auto_off_category"}) {
            Preference p = findPreference(key);
            if (p != null) p.setEnabled(on);
        }
    }
}
