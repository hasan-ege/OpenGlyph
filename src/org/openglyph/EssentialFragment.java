package org.openglyph;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.ButtonPreference;
import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.TopIntroPreference;

import java.util.ArrayList;
import java.util.List;

import org.openglyph.core.EssentialRule;

/**
 * Essential Notifications: one card per rule (switch = on/off, tap = edit) and "+ New rule".
 * While a notification matching an enabled rule is waiting, its icon stays on the matrix.
 */
public final class EssentialFragment extends GlyphFragment {
    @Override
    int titleRes() {
        return R.string.settings_essential;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        Context c = requireContext();
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();

        TopIntroPreference intro = new TopIntroPreference(c);
        intro.setKey("intro");
        intro.setTitle(R.string.essential_intro);
        screen.addPreference(intro);

        List<EssentialRule> rules = GlyphSettings.essentialRules(cr());
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            EssentialRule r = rules.get(i);
            TilePreference tile = new TilePreference(c);
            tile.setKey("rule_" + i);
            tile.setTitle(TextUtils.isEmpty(r.name) ? c.getString(R.string.rule_unnamed) : r.name);
            tile.setSummary(describe(c, r));
            tile.setIcon(ruleIcon(c, r, r.apps.isEmpty() ? null : r.apps.iterator().next()));
            tile.setSwitch(r.enabled, (t, on) -> {
                List<EssentialRule> now = GlyphSettings.essentialRules(cr());
                if (index < now.size()) {
                    now.get(index).enabled = on;
                    GlyphSettings.setEssentialRules(cr(), now);
                }
            });
            tile.setOnPreferenceClickListener(p -> {
                open(RuleEditFragment.edit(index));
                return true;
            });
            screen.addPreference(tile);
        }

        ButtonPreference add = new ButtonPreference(c);
        add.setKey("new_rule");
        add.setTitle(c.getString(R.string.rule_new));
        add.setIcon(c.getDrawable(R.drawable.ic_og_add));
        add.setOnClickListener(v -> open(RuleEditFragment.edit(-1)));
        screen.addPreference(add);

        FooterPreference footer = new FooterPreference(c);
        footer.setKey("footer");
        footer.setTitle(R.string.essential_footer);
        screen.addPreference(footer);
    }

    /** "From Ayşe, Mehmet in WhatsApp · help, urgent" */
    static String describe(Context c, EssentialRule r) {
        String people = r.people.isEmpty() ? c.getString(R.string.rule_anyone)
                : TextUtils.join(", ", r.people);
        String apps = r.apps.isEmpty() ? c.getString(R.string.rule_all_apps)
                : TextUtils.join(", ", appLabels(c, r));
        String s = c.getString(R.string.rule_describe, people, apps);
        if (!r.keywords.isEmpty()) {
            s += c.getString(R.string.rule_describe_keywords, TextUtils.join(", ", r.keywords));
        }
        return s;
    }

    static List<CharSequence> appLabels(Context c, EssentialRule r) {
        List<CharSequence> out = new ArrayList<>();
        for (String pkg : r.apps) {
            try {
                out.add(c.getPackageManager().getApplicationLabel(
                        c.getPackageManager().getApplicationInfo(pkg, 0)));
            } catch (Exception e) {
                out.add(pkg);
            }
        }
        return out;
    }

    /** The rule's matrix icon as a round preview; "app" uses the given app, if any. */
    static Drawable ruleIcon(Context c, EssentialRule r, String pkg) {
        int size = c.getResources().getDimensionPixelSize(R.dimen.og_list_icon);
        int[] grid = null;
        if (EssentialRule.ICON_APP.equals(r.icon)) {
            grid = pkg != null ? AppIconMatrix.render(c, pkg)
                    : AppIconMatrix.render(c.getDrawable(android.R.drawable.sym_def_app_icon));
        }
        if (grid == null) {
            grid = org.openglyph.core.GlyphIcons.render(r.icon, org.openglyph.core.Frame.MAX);
        }
        return new MatrixArt.GridDrawable(grid, size);
    }
}
