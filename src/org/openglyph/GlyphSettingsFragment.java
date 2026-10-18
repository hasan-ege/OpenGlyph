package org.openglyph;

import android.content.ContentResolver;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openglyph.core.AppPatterns;

/**
 * Every switch is non-persistent and mirrors a Settings.Secure key of the same name, which the
 * service observes; nothing is stored in SharedPreferences.
 */
public final class GlyphSettingsFragment extends SettingsBasePreferenceFragment {
    private static final String[] SWITCHES = {
        GlyphSettings.NOTIFICATIONS, GlyphSettings.CALLS, GlyphSettings.CHARGING,
        GlyphSettings.RECORD_LED, GlyphSettings.TOYS,
        GlyphSettings.toyKey("clock"), GlyphSettings.toyKey("battery"),
        GlyphSettings.toyKey("stopwatch"),
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.glyph_settings, rootKey);
        ContentResolver cr = requireContext().getContentResolver();

        MainSwitchPreference main = findPreference(GlyphSettings.ENABLED);
        main.setChecked(GlyphSettings.flag(cr, GlyphSettings.ENABLED));
        main.addOnSwitchChangeListener((button, on) -> {
            GlyphSettings.setFlag(cr, GlyphSettings.ENABLED, on);
            updateEnabledState(on);
        });

        SeekBarPreference brightness = findPreference(GlyphSettings.BRIGHTNESS);
        brightness.setValue(GlyphSettings.brightness(cr));
        brightness.setOnPreferenceChangeListener((p, value) -> {
            android.provider.Settings.Secure.putInt(cr, GlyphSettings.BRIGHTNESS, (Integer) value);
            return true;
        });

        for (String key : SWITCHES) {
            TwoStatePreference sw = findPreference(key);
            sw.setChecked(GlyphSettings.flag(cr, key));
            sw.setOnPreferenceChangeListener((p, value) -> {
                GlyphSettings.setFlag(cr, key, (Boolean) value);
                return true;
            });
        }

        TwoStatePreference flip = findPreference(GlyphSettings.FLIP);
        flip.setChecked(GlyphSettings.flagDefaultOff(cr, GlyphSettings.FLIP));
        flip.setOnPreferenceChangeListener((p, value) -> {
            GlyphSettings.setFlag(cr, GlyphSettings.FLIP, (Boolean) value);
            return true;
        });

        ListPreference aod = findPreference(GlyphSettings.AOD);
        aod.setValue(GlyphSettings.string(cr, GlyphSettings.AOD, "OFF"));
        aod.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putString(cr, GlyphSettings.AOD, (String) value);
            return true;
        });

        ListPreference quiet = findPreference("quiet_hours");
        int start = GlyphSettings.integer(cr, GlyphSettings.NIGHT_START, 23);
        int end = GlyphSettings.integer(cr, GlyphSettings.NIGHT_END, 7);
        String current = start < 0 ? "off" : start + "-" + end;
        quiet.setValue(quiet.findIndexOfValue(current) >= 0 ? current : "23-7");
        quiet.setOnPreferenceChangeListener((p, value) -> {
            String v = (String) value;
            if ("off".equals(v)) {
                Settings.Secure.putInt(cr, GlyphSettings.NIGHT_START, -1);
            } else {
                String[] se = v.split("-");
                Settings.Secure.putInt(cr, GlyphSettings.NIGHT_START, Integer.parseInt(se[0]));
                Settings.Secure.putInt(cr, GlyphSettings.NIGHT_END, Integer.parseInt(se[1]));
            }
            return true;
        });

        MultiSelectListPreference essential = findPreference(GlyphSettings.ESSENTIAL_APPS);
        List<AppList.App> apps = AppList.launchable(requireContext());
        CharSequence[] labels = new CharSequence[apps.size()];
        CharSequence[] pkgs = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            pkgs[i] = apps.get(i).pkg;
        }
        essential.setEntries(labels);
        essential.setEntryValues(pkgs);
        essential.setValues(AppPatterns.parseSet(
                GlyphSettings.string(cr, GlyphSettings.ESSENTIAL_APPS, "")));
        essential.setOnPreferenceChangeListener((p, value) -> {
            @SuppressWarnings("unchecked")
            Set<String> chosen = new HashSet<>((Set<String>) value);
            Settings.Secure.putString(cr, GlyphSettings.ESSENTIAL_APPS, AppPatterns.formatSet(chosen));
            return true;
        });

        findPreference("app_patterns").setOnPreferenceClickListener(p -> {
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(getId(), new AppPatternsFragment())
                    .addToBackStack(null)
                    .commit();
            return true;
        });

        Preference test = findPreference("test");
        test.setOnPreferenceClickListener(p -> {
            GlyphService service = GlyphService.get();
            if (service != null) service.testPulse();
            return true;
        });

        updateEnabledState(main.isChecked());
    }

    private void updateEnabledState(boolean on) {
        for (String key : new String[] {GlyphSettings.BRIGHTNESS, "effects", "nothing_style",
                "notification_style", "toys"}) {
            Preference p = findPreference(key);
            if (p != null) p.setEnabled(on);
        }
    }
}
