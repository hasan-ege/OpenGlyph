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
        pattern.setValue(GlyphSettings.string(cr, GlyphSettings.DEFAULT_PATTERN, "GENERATIVE"));
        pattern.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putString(cr, GlyphSettings.DEFAULT_PATTERN, (String) value);
            return true;
        });

        link("app_patterns", AppPatternsFragment::new);

        ListPreference engine = findPreference(GlyphSettings.ENGINE);
        engine.setValue(GlyphSettings.string(cr, GlyphSettings.ENGINE, "DIODE"));
        engine.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putString(cr, GlyphSettings.ENGINE, (String) value);
            return true;
        });

        androidx.preference.TwoStatePreference sounds = findPreference(GlyphSettings.GEN_SOUNDS);
        sounds.setChecked(GlyphSettings.flagDefaultOff(cr, GlyphSettings.GEN_SOUNDS));
        sounds.setOnPreferenceChangeListener((p, value) -> {
            GlyphSettings.setFlag(cr, GlyphSettings.GEN_SOUNDS, (Boolean) value);
            return true;
        });

        ListPreference preview = findPreference("generative_preview");
        java.util.List<AppList.App> apps = AppList.launchable(requireContext());
        CharSequence[] labels = new CharSequence[apps.size()];
        CharSequence[] pkgs = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            pkgs[i] = apps.get(i).pkg;
        }
        preview.setEntries(labels);
        preview.setEntryValues(pkgs);
        preview.setOnPreferenceChangeListener((p, value) -> {
            GlyphService running = GlyphService.get();
            if (running != null) running.previewGenerative((String) value);
            return false; // a one-shot action, nothing to keep selected
        });
        findPreference("test").setOnPreferenceClickListener(p -> {
            GlyphService running = GlyphService.get();
            if (running != null) running.testPulse();
            return true;
        });
    }
}
