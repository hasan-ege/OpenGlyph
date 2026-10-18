package org.openglyph;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.MainSwitchPreference;

import java.util.ArrayList;
import java.util.List;

import org.openglyph.core.EssentialRule;

/** Rule editor > Any of the following apps: "All apps", or a checked list. */
public final class AppPickerFragment extends GlyphFragment {
    private final List<CheckBoxPreference> mBoxes = new ArrayList<>();

    @Override
    int titleRes() {
        return R.string.rule_apps;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context c = requireContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);
        EssentialRule rule = RuleEditFragment.draft(this).rule;
        if (rule == null) return; // process restarted mid-edit

        MainSwitchPreference all = new MainSwitchPreference(c);
        all.setKey("all_apps");
        all.setPersistent(false);
        all.setTitle(R.string.rule_all_apps);
        all.setChecked(rule.apps.isEmpty());
        screen.addPreference(all);

        PreferenceCategory list = new PreferenceCategory(c);
        list.setKey("apps");
        list.setTitle(R.string.rule_pick_apps);
        screen.addPreference(list);
        for (AppList.App app : AppList.launchable(c)) {
            CheckBoxPreference box = new CheckBoxPreference(c);
            box.setKey("app_" + app.pkg);
            box.setPersistent(false);
            box.setTitle(app.label);
            box.setIcon(app.icon);
            box.setChecked(rule.apps.contains(app.pkg));
            box.setOnPreferenceChangeListener((p, value) -> {
                if ((Boolean) value) rule.apps.add(app.pkg); else rule.apps.remove(app.pkg);
                return true;
            });
            mBoxes.add(box);
            list.addPreference(box);
        }
        list.setVisible(!all.isChecked());
        all.addOnSwitchChangeListener((b, on) -> {
            if (on) {
                rule.apps.clear();
                for (CheckBoxPreference box : mBoxes) box.setChecked(false);
            }
            list.setVisible(!on);
        });
    }
}
