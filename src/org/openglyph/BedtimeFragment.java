package org.openglyph;

import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.TopIntroPreference;

/** Bedtime schedule: Glyph lights off from a start to an end time on the chosen days. */
public final class BedtimeFragment extends GlyphFragment {
    @Override
    int titleRes() {
        return R.string.settings_bedtime;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context c = requireContext();
        ContentResolver cr = cr();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        TopIntroPreference intro = new TopIntroPreference(c);
        intro.setKey("intro");
        intro.setTitle(R.string.bedtime_intro);
        screen.addPreference(intro);

        MainSwitchPreference main = new MainSwitchPreference(c);
        main.setKey("bedtime_main");
        main.setPersistent(false);
        main.setTitle(R.string.settings_bedtime);
        main.setChecked(GlyphSettings.flagDefaultOff(cr, GlyphSettings.BEDTIME));
        main.addOnSwitchChangeListener((b, on) -> GlyphSettings.setFlag(cr, GlyphSettings.BEDTIME, on));
        screen.addPreference(main);

        PreferenceCategory time = new PreferenceCategory(c);
        time.setKey("time");
        time.setTitle(R.string.bedtime_time);
        screen.addPreference(time);
        time.addPreference(timePref(c, "start", R.string.bedtime_start, GlyphSettings.BEDTIME_START));
        time.addPreference(timePref(c, "end", R.string.bedtime_end, GlyphSettings.BEDTIME_END));

        PreferenceCategory repeat = new PreferenceCategory(c);
        repeat.setKey("repeat");
        repeat.setTitle(R.string.bedtime_repeat);
        screen.addPreference(repeat);
        DaysPreference days = new DaysPreference(c);
        days.setKey("days");
        days.setSummary(GlyphSettingsFragment.days(c, GlyphSettings.bedtimeDays(cr)));
        days.setDays(GlyphSettings.bedtimeDays(cr), mask -> {
            Settings.Secure.putInt(cr, GlyphSettings.BEDTIME_DAYS, mask);
            days.setSummary(GlyphSettingsFragment.days(c, mask));
        });
        repeat.addPreference(days);

        FooterPreference footer = new FooterPreference(c);
        footer.setKey("footer");
        footer.setTitle(R.string.bedtime_footer);
        screen.addPreference(footer);
    }

    private Preference timePref(Context c, String key, int title, String setting) {
        ContentResolver cr = cr();
        Preference p = new Preference(c);
        p.setKey(key);
        p.setTitle(title);
        int value = GlyphSettings.BEDTIME_START.equals(setting)
                ? GlyphSettings.bedtimeStart(cr) : GlyphSettings.bedtimeEnd(cr);
        p.setSummary(GlyphSettingsFragment.time(c, value));
        p.setOnPreferenceClickListener(pref -> {
            int now = GlyphSettings.BEDTIME_START.equals(setting)
                    ? GlyphSettings.bedtimeStart(cr) : GlyphSettings.bedtimeEnd(cr);
            new TimePickerDialog(c, (view, hour, minute) -> {
                int v = hour * 60 + minute;
                Settings.Secure.putInt(cr, setting, v);
                pref.setSummary(GlyphSettingsFragment.time(c, v));
            }, now / 60, now % 60, DateFormat.is24HourFormat(c)).show();
            return true;
        });
        return p;
    }
}
