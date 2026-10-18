package org.openglyph;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SelectorWithWidgetPreference;

import java.util.ArrayList;
import java.util.List;

/** Always-on Glyph Toy: on/off, when it shows, and which toy (clock or an SDK AOD toy). */
public final class AodToyFragment extends GlyphFragment {
    /** The mode last used, restored when always-on is switched back on. */
    static final String LAST_MODE = "open_glyph_aod_last";

    private final List<SelectorWithWidgetPreference> mChoices = new ArrayList<>();

    @Override
    int titleRes() {
        return R.string.settings_aod_toy_tile;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context c = requireContext();
        ContentResolver cr = cr();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        String mode = GlyphSettings.string(cr, GlyphSettings.AOD, "OFF");
        MainSwitchPreference main = new MainSwitchPreference(c);
        main.setKey("aod_main");
        main.setPersistent(false);
        main.setTitle(R.string.settings_aod_toy_tile);
        main.setChecked(!"OFF".equals(mode));
        screen.addPreference(main);

        ListPreference when = new ListPreference(c);
        when.setKey("aod_when");
        when.setPersistent(false);
        when.setTitle(R.string.settings_aod_when);
        when.setDialogTitle(R.string.settings_aod_when);
        when.setEntries(R.array.aod_when_entries);
        when.setEntryValues(R.array.aod_when_values);
        when.setValue("OFF".equals(mode) ? GlyphSettings.string(cr, LAST_MODE, "ALWAYS") : mode);
        when.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        when.setOnPreferenceChangeListener((p, value) -> {
            Settings.Secure.putString(cr, LAST_MODE, (String) value);
            if (main.isChecked()) Settings.Secure.putString(cr, GlyphSettings.AOD, (String) value);
            return true;
        });
        screen.addPreference(when);

        main.addOnSwitchChangeListener((button, on) -> {
            Settings.Secure.putString(cr, GlyphSettings.AOD, on ? when.getValue() : "OFF");
            if (!on) Settings.Secure.putString(cr, LAST_MODE, when.getValue());
        });

        PreferenceCategory toys = new PreferenceCategory(c);
        toys.setKey("aod_toys");
        toys.setTitle(R.string.settings_aod_choose);
        screen.addPreference(toys);
        String current = GlyphSettings.string(cr, GlyphSettings.AOD_TOY, "");
        int size = getResources().getDimensionPixelSize(R.dimen.og_list_icon);
        for (ToyCatalog.Entry e : ToyCatalog.all(c)) {
            if (!e.aod) continue;
            String value = "clock".equals(e.id) ? "" : e.id;
            SelectorWithWidgetPreference p = new SelectorWithWidgetPreference(c);
            p.setKey("aod_toy_" + e.id);
            p.setPersistent(false);
            p.setTitle(e.label);
            p.setSummary(e.summary);
            p.setIcon(ToyCatalog.icon(e, size));
            p.setChecked(value.equals(current));
            p.setOnClickListener(clicked -> {
                Settings.Secure.putString(cr, GlyphSettings.AOD_TOY, value);
                for (SelectorWithWidgetPreference o : mChoices) o.setChecked(o == clicked);
            });
            mChoices.add(p);
            toys.addPreference(p);
        }
        if (mChoices.stream().noneMatch(SelectorWithWidgetPreference::isChecked) && !mChoices.isEmpty()) {
            mChoices.get(0).setChecked(true);
        }

        FooterPreference footer = new FooterPreference(c);
        footer.setKey("aod_footer");
        footer.setTitle(R.string.settings_aod_footer);
        screen.addPreference(footer);
    }

    /** "Always · Digital clock" */
    static String summary(Context c) {
        ContentResolver cr = c.getContentResolver();
        String mode = GlyphSettings.string(cr, GlyphSettings.AOD, "OFF");
        String[] values = c.getResources().getStringArray(R.array.aod_when_values);
        String[] labels = c.getResources().getStringArray(R.array.aod_when_entries);
        String when = labels[0];
        for (int i = 0; i < values.length; i++) if (values[i].equals(mode)) when = labels[i];
        String id = GlyphSettings.string(cr, GlyphSettings.AOD_TOY, "");
        ToyCatalog.Entry toy = ToyCatalog.byId(ToyCatalog.all(c), id.isEmpty() ? "clock" : id);
        CharSequence name = toy != null ? toy.label : c.getString(R.string.toy_clock);
        return c.getString(R.string.settings_aod_summary, when, name);
    }
}
