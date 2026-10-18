package org.openglyph;

import android.content.ContentResolver;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

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

        Preference test = findPreference("test");
        test.setOnPreferenceClickListener(p -> {
            GlyphService service = GlyphService.get();
            if (service != null) service.testPulse();
            return true;
        });

        updateEnabledState(main.isChecked());
    }

    private void updateEnabledState(boolean on) {
        for (String key : new String[] {GlyphSettings.BRIGHTNESS, "effects", "toys"}) {
            Preference p = findPreference(key);
            if (p != null) p.setEnabled(on);
        }
    }
}
