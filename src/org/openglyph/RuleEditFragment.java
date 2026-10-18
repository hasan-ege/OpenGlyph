package org.openglyph;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import com.android.settingslib.widget.ButtonPreference;

import java.util.List;

import org.openglyph.core.EssentialRule;

/**
 * Create / edit one Essential Notifications rule. Edits go to a draft shared with the app,
 * people and icon pickers, and are stored only on "Save rule".
 */
public final class RuleEditFragment extends GlyphFragment {
    private static final String ARG_INDEX = "index";

    /** The rule being edited, shared by this screen and its pickers. */
    public static final class Draft extends ViewModel {
        int index = -1;
        EssentialRule rule;
    }

    static RuleEditFragment edit(int index) {
        RuleEditFragment f = new RuleEditFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INDEX, index);
        f.setArguments(args);
        return f;
    }

    static Draft draft(androidx.fragment.app.Fragment f) {
        return new ViewModelProvider(f.requireActivity()).get(Draft.class);
    }

    private Draft mDraft;

    @Override
    int titleRes() {
        return mDraft != null && mDraft.index >= 0 ? R.string.rule_edit_title : R.string.rule_new_title;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.glyph_rule, rootKey);
        int index = requireArguments().getInt(ARG_INDEX, -1);
        mDraft = draft(this);
        if (mDraft.rule == null || mDraft.index != index) {
            List<EssentialRule> rules = GlyphSettings.essentialRules(cr());
            mDraft.index = index;
            if (index >= 0 && index < rules.size()) {
                mDraft.rule = rules.get(index).copy();
            } else {
                mDraft.index = -1;
                mDraft.rule = new EssentialRule();
                mDraft.rule.name = getString(R.string.rule_default_name, rules.size() + 1);
            }
        }

        EditTextPreference name = findPreference("rule_name");
        name.setOnPreferenceChangeListener((p, value) -> {
            mDraft.rule.name = ((String) value).trim();
            refresh();
            return true;
        });
        EditTextPreference keywords = findPreference("rule_keywords");
        keywords.setOnPreferenceChangeListener((p, value) -> {
            mDraft.rule.keywords.clear();
            for (String k : ((String) value).split(",")) {
                if (!k.trim().isEmpty()) mDraft.rule.keywords.add(k.trim());
            }
            refresh();
            return true;
        });
        link("rule_apps", AppPickerFragment::new);
        link("rule_people", PeopleFragment::new);
        link("rule_icon", IconPickerFragment::new);
        findPreference("rule_preview").setOnPreferenceClickListener(p -> {
            GlyphService service = GlyphService.get();
            if (service != null) {
                String pkg = mDraft.rule.apps.isEmpty() ? null : mDraft.rule.apps.iterator().next();
                service.previewFrame(service.essentialIcon(pkg, mDraft.rule.icon));
            }
            return true;
        });

        ButtonPreference save = findPreference("rule_save");
        save.setOnClickListener(v -> {
            List<EssentialRule> rules = GlyphSettings.essentialRules(cr());
            if (mDraft.index >= 0 && mDraft.index < rules.size()) {
                rules.set(mDraft.index, mDraft.rule);
            } else {
                rules.add(mDraft.rule);
            }
            GlyphSettings.setEssentialRules(cr(), rules);
            finish();
        });
        ButtonPreference delete = findPreference("rule_delete");
        delete.setVisible(mDraft.index >= 0);
        delete.setOnClickListener(v -> {
            List<EssentialRule> rules = GlyphSettings.essentialRules(cr());
            if (mDraft.index >= 0 && mDraft.index < rules.size()) rules.remove(mDraft.index);
            GlyphSettings.setEssentialRules(cr(), rules);
            finish();
        });
    }

    private void finish() {
        mDraft.rule = null;
        mDraft.index = -1;
        getParentFragmentManager().popBackStack();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (mDraft.rule == null) return;
        Context c = requireContext();
        EssentialRule r = mDraft.rule;
        EditTextPreference name = findPreference("rule_name");
        name.setText(r.name);
        name.setSummary(TextUtils.isEmpty(r.name) ? c.getString(R.string.rule_unnamed) : r.name);

        findPreference("rule_apps").setSummary(r.apps.isEmpty() ? c.getString(R.string.rule_all_apps)
                : TextUtils.join(", ", EssentialFragment.appLabels(c, r)));
        findPreference("rule_people").setSummary(r.people.isEmpty()
                ? c.getString(R.string.rule_anyone_optional) : TextUtils.join(", ", r.people));
        EditTextPreference keywords = findPreference("rule_keywords");
        keywords.setText(TextUtils.join(", ", r.keywords));
        keywords.setSummary(r.keywords.isEmpty() ? c.getString(R.string.rule_no_keywords)
                : TextUtils.join(", ", r.keywords));

        Preference icon = findPreference("rule_icon");
        String pkg = r.apps.isEmpty() ? null : r.apps.iterator().next();
        icon.setIcon(EssentialFragment.ruleIcon(c, r, pkg));
        icon.setSummary(IconPickerFragment.label(c, r.icon));
    }

    @Override
    public void onDestroy() {
        // Leaving with back (no save) drops the draft, unless this is only a rotation.
        if (!requireActivity().isChangingConfigurations() && isRemoving() && mDraft != null) {
            mDraft.rule = null;
            mDraft.index = -1;
        }
        super.onDestroy();
    }
}
