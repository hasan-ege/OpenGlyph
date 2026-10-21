package org.openglyph;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.FooterPreference;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openglyph.core.Automation;

/**
 * Glyph Toys > Automations: "if these conditions hold, show this toy", top rule first. A toy picked
 * with the Glyph Button always comes first; the automations fill the matrix when none is shown.
 */
public final class AutomationFragment extends GlyphFragment {
    private List<Automation.Rule> mRules = new ArrayList<>();

    @Override
    int titleRes() {
        return R.string.settings_automations;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
    }

    @Override
    public void onResume() {
        super.onResume();
        mRules = Automation.parse(GlyphSettings.string(cr(), GlyphSettings.AUTOMATIONS, null));
        build();
    }

    private void save() {
        Settings.Secure.putString(cr(), GlyphSettings.AUTOMATIONS, Automation.format(mRules));
        build();
    }

    private void build() {
        Context c = requireContext();
        ContentResolver cr = cr();
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();

        Preference mode = new Preference(c);
        mode.setKey("vis_mode");
        mode.setTitle(R.string.settings_vis_mode);
        mode.setSummary(GlyphSettings.visualizerFixed(cr)
                ? R.string.settings_vis_fixed_short : R.string.settings_vis_moving_short);
        mode.setIcon(R.drawable.ic_og_music);
        mode.setOnPreferenceClickListener(p -> {
            String[] values = {"FIXED", "MOVING"};
            new AlertDialog.Builder(c)
                    .setTitle(R.string.settings_vis_mode)
                    .setSingleChoiceItems(R.array.vis_mode_entries,
                            GlyphSettings.visualizerFixed(cr) ? 0 : 1, (d, which) -> {
                                Settings.Secure.putString(cr, GlyphSettings.VIS_MODE, values[which]);
                                d.dismiss();
                                build();
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        screen.addPreference(mode);

        PreferenceCategory list = new PreferenceCategory(c);
        list.setKey("rules");
        list.setTitle(R.string.automation_rules_title);
        screen.addPreference(list);
        List<ToyCatalog.Entry> toys = ToyCatalog.all(c);
        int size = getResources().getDimensionPixelSize(R.dimen.og_list_icon);
        for (int i = 0; i < mRules.size(); i++) {
            Automation.Rule r = mRules.get(i);
            final int index = i;
            TilePreference p = new TilePreference(c, null);
            p.setKey("rule_" + i);
            p.setTitle(describe(c, r, toys));
            p.setSummary(detail(c, r));
            ToyCatalog.Entry e = ToyCatalog.byId(toys, r.toy);
            if (e != null) p.setIcon(ToyCatalog.icon(e, size));
            p.setSwitch(r.enabled, (t, on) -> {
                r.enabled = on;
                save();
            });
            p.setOnPreferenceClickListener(pref -> {
                edit(index);
                return true;
            });
            list.addPreference(p);
        }

        Preference add = new Preference(c);
        add.setKey("add");
        add.setTitle(R.string.automation_add);
        add.setIcon(R.drawable.ic_og_add);
        add.setOnPreferenceClickListener(p -> {
            mRules.add(new Automation.Rule(GlyphSettings.VISUALIZER, Automation.Cond.SOUND));
            save();
            edit(mRules.size() - 1);
            return true;
        });
        screen.addPreference(add);

        Preference reset = new Preference(c);
        reset.setKey("reset");
        reset.setTitle(R.string.automation_reset);
        reset.setOnPreferenceClickListener(p -> {
            mRules = Automation.defaults();
            save();
            return true;
        });
        screen.addPreference(reset);

        FooterPreference footer = new FooterPreference(c);
        footer.setKey("footer");
        footer.setTitle(R.string.automation_footer);
        screen.addPreference(footer);
    }

    /** "Sound is playing → Visualiser" */
    static String describe(Context c, Automation.Rule r, List<ToyCatalog.Entry> toys) {
        List<String> parts = new ArrayList<>();
        String[] names = c.getResources().getStringArray(R.array.automation_cond_entries);
        for (Automation.Cond cond : r.conds) parts.add(names[cond.ordinal()]);
        if (r.hasTime()) parts.add(time(r.start) + "–" + time(r.end));
        String when = parts.isEmpty() ? c.getString(R.string.automation_no_cond)
                : String.join(" + ", parts);
        ToyCatalog.Entry e = ToyCatalog.byId(toys, r.toy);
        return c.getString(R.string.automation_rule, when, e != null ? e.label : r.toy);
    }

    private String detail(Context c, Automation.Rule r) {
        if (!r.conds.contains(Automation.Cond.SOUND)) return null;
        if (r.apps.isEmpty()) return c.getString(R.string.automation_any_app);
        List<String> labels = new ArrayList<>();
        for (AppList.App a : AppList.launchable(c)) if (r.apps.contains(a.pkg)) labels.add(a.label.toString());
        return c.getString(R.string.automation_only_apps, String.join(", ", labels));
    }

    private static String time(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    /** The rule's menu: toy, conditions, apps, time, move, delete. */
    private void edit(int index) {
        Context c = requireContext();
        Automation.Rule r = mRules.get(index);
        String[] items = {
                getString(R.string.automation_edit_toy),
                getString(R.string.automation_edit_conds),
                getString(R.string.automation_edit_apps),
                getString(R.string.automation_edit_time),
                getString(R.string.automation_move_up),
                getString(R.string.automation_move_down),
                getString(R.string.automation_delete),
        };
        new AlertDialog.Builder(c)
                .setTitle(describe(c, r, ToyCatalog.all(c)))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> pickToy(r);
                        case 1 -> pickConds(r);
                        case 2 -> pickApps(r);
                        case 3 -> pickTime(r);
                        case 4 -> move(index, -1);
                        case 5 -> move(index, 1);
                        default -> {
                            mRules.remove(index);
                            save();
                        }
                    }
                })
                .show();
    }

    private void move(int index, int by) {
        int to = index + by;
        if (to < 0 || to >= mRules.size()) return;
        mRules.add(to, mRules.remove(index));
        save();
    }

    private void pickToy(Automation.Rule r) {
        List<ToyCatalog.Entry> toys = new ArrayList<>();
        for (ToyCatalog.Entry e : ToyCatalog.all(requireContext())) {
            if (!GlyphSettings.OFF.equals(e.id)) toys.add(e);
        }
        CharSequence[] labels = new CharSequence[toys.size()];
        int checked = -1;
        for (int i = 0; i < toys.size(); i++) {
            labels[i] = toys.get(i).label;
            if (toys.get(i).id.equals(r.toy)) checked = i;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.automation_edit_toy)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    r.toy = toys.get(which).id;
                    d.dismiss();
                    save();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickConds(Automation.Rule r) {
        Automation.Cond[] all = Automation.Cond.values();
        boolean[] checked = new boolean[all.length];
        for (int i = 0; i < all.length; i++) checked[i] = r.conds.contains(all[i]);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.automation_edit_conds)
                .setMultiChoiceItems(R.array.automation_cond_entries, checked,
                        (d, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    r.conds.clear();
                    for (int i = 0; i < all.length; i++) if (checked[i]) r.conds.add(all[i]);
                    save();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Sound is playing" from these apps only; nothing ticked means any app. */
    private void pickApps(Automation.Rule r) {
        List<AppList.App> apps = AppList.launchable(requireContext());
        CharSequence[] labels = new CharSequence[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = r.apps.contains(apps.get(i).pkg);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.automation_edit_apps)
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    r.apps.clear();
                    for (int i = 0; i < apps.size(); i++) if (checked[i]) r.apps.add(apps.get(i).pkg);
                    if (!r.apps.isEmpty()) r.conds.add(Automation.Cond.SOUND);
                    save();
                })
                .setNeutralButton(R.string.automation_any_app, (d, w) -> {
                    r.apps.clear();
                    save();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Start, then end; "no time range" clears it. */
    private void pickTime(Automation.Rule r) {
        Context c = requireContext();
        new AlertDialog.Builder(c)
                .setTitle(R.string.automation_edit_time)
                .setItems(new CharSequence[] {getString(R.string.automation_set_time),
                        getString(R.string.automation_no_time)}, (d, which) -> {
                    if (which == 1) {
                        r.start = r.end = -1;
                        save();
                        return;
                    }
                    int s0 = r.hasTime() ? r.start : 8 * 60, e0 = r.hasTime() ? r.end : 23 * 60;
                    new TimePickerDialog(c, (v, h, m) -> {
                        int start = h * 60 + m;
                        new TimePickerDialog(c, (v2, h2, m2) -> {
                            r.start = start;
                            r.end = h2 * 60 + m2;
                            save();
                        }, e0 / 60, e0 % 60, true).show();
                    }, s0 / 60, s0 % 60, true).show();
                })
                .show();
    }
}
