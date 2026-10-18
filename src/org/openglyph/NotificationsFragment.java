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
            findPreference("generative").setEnabled(on);
        });
        findPreference("generative").setEnabled(main.isChecked());

        ListPreference engine = findPreference(GlyphSettings.ENGINE);
        engine.setValue(GlyphSettings.string(cr, GlyphSettings.ENGINE, "DIODE"));
        engine.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putString(cr, GlyphSettings.ENGINE, (String) value);
            return true;
        });

        androidx.preference.Preference seed = findPreference("device_seed");
        seed.setSummary(GenerativeSounds.deviceSeed(cr));
        seed.setOnPreferenceClickListener(p -> {
            editSeed(seed);
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
    }

    /** Edit the device seed by hand, or roll a new one; every app's sound and light change. */
    private void editSeed(androidx.preference.Preference pref) {
        ContentResolver cr = cr();
        android.content.Context c = requireContext();
        android.widget.EditText input = new android.widget.EditText(c);
        input.setSingleLine();
        input.setText(GenerativeSounds.deviceSeed(cr));
        input.setSelectAllOnFocus(true);
        int pad = getResources().getDimensionPixelSize(R.dimen.og_tile_gap) * 4;
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new android.app.AlertDialog.Builder(c)
                .setTitle(R.string.settings_seed)
                .setMessage(R.string.settings_seed_message)
                .setView(box)
                .setPositiveButton(R.string.settings_seed_save, (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.isEmpty()) Settings.Secure.putString(cr, GlyphSettings.SEED, v);
                    pref.setSummary(GenerativeSounds.deviceSeed(cr));
                })
                .setNeutralButton(R.string.settings_seed_reroll, (d, w) -> {
                    pref.setSummary(GenerativeSounds.reroll(cr));
                    GlyphService running = GlyphService.get();
                    if (running != null) running.previewGenerative("com.android.settings");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
