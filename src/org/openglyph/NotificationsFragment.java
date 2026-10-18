package org.openglyph;

import android.content.ContentResolver;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.ListPreference;

import com.android.settingslib.widget.MainSwitchPreference;

/** Glyph Interface > Notifications: on/off, the default pattern, per-app patterns, test. */
public final class NotificationsFragment extends GlyphFragment {
    @Override
    int titleRes() {
        return R.string.settings_notifications;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.glyph_notifications, rootKey);
        ContentResolver cr = cr();

        MainSwitchPreference main = findPreference(GlyphSettings.NOTIFICATIONS);
        main.setChecked(GlyphSettings.flag(cr, GlyphSettings.NOTIFICATIONS));
        main.addOnSwitchChangeListener((b, on) -> {
            GlyphSettings.setFlag(cr, GlyphSettings.NOTIFICATIONS, on);
            findPreference("patterns").setEnabled(on);
        });
        findPreference("patterns").setEnabled(main.isChecked());

        ListPreference pattern = findPreference(GlyphSettings.DEFAULT_PATTERN);
        pattern.setValue(GlyphSettings.string(cr, GlyphSettings.DEFAULT_PATTERN, "RING"));
        pattern.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putString(cr, GlyphSettings.DEFAULT_PATTERN, (String) value);
            return true;
        });

        link("app_patterns", AppPatternsFragment::new);
        findPreference("test").setOnPreferenceClickListener(p -> {
            GlyphService running = GlyphService.get();
            if (running != null) running.testPulse();
            return true;
        });
    }
}
