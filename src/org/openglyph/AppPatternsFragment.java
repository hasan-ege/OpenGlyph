package org.openglyph;

import android.content.ContentResolver;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.Map;

import org.openglyph.core.AppPatterns;

/** Settings > Glyph Matrix > Per-app patterns: one pattern list per launchable app. */
public final class AppPatternsFragment extends SettingsBasePreferenceFragment {
    private static final String DEFAULT = "DEFAULT";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        screen.setTitle(R.string.settings_patterns);
        setPreferenceScreen(screen);

        ContentResolver cr = requireContext().getContentResolver();
        Map<String, String> patterns = AppPatterns.parse(
                GlyphSettings.string(cr, GlyphSettings.APP_PATTERNS, ""));

        for (AppList.App app : AppList.launchable(requireContext())) {
            ListPreference p = new ListPreference(requireContext());
            p.setKey("pattern_" + app.pkg);
            p.setPersistent(false);
            p.setTitle(app.label);
            p.setDialogTitle(app.label);
            p.setIcon(app.icon);
            p.setEntries(R.array.pattern_entries);
            p.setEntryValues(R.array.pattern_values);
            p.setValue(patterns.getOrDefault(app.pkg, DEFAULT));
            p.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            p.setOnPreferenceChangeListener((pref, value) -> {
                Map<String, String> now = AppPatterns.parse(
                        GlyphSettings.string(cr, GlyphSettings.APP_PATTERNS, ""));
                if (DEFAULT.equals(value)) now.remove(app.pkg); else now.put(app.pkg, (String) value);
                Settings.Secure.putString(cr, GlyphSettings.APP_PATTERNS, AppPatterns.format(now));
                return true;
            });
            screen.addPreference(p);
        }
    }
}
