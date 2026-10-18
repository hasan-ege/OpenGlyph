package org.openglyph;

import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.FooterPreference;

import org.openglyph.core.EssentialRule;

/**
 * Rule editor > Any of the following people. Names are matched against the notification's
 * title and its sender / people list, so a contact pick only needs the display name (the
 * picker grants access to that one contact; no contacts permission is needed).
 */
public final class PeopleFragment extends GlyphFragment {
    private ActivityResultLauncher<Void> mPick;

    @Override
    int titleRes() {
        return R.string.rule_people;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mPick = registerForActivityResult(new ActivityResultContracts.PickContact(), this::onPicked);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        build();
    }

    private EssentialRule rule() {
        return RuleEditFragment.draft(this).rule;
    }

    private void build() {
        Context c = requireContext();
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        EssentialRule rule = rule();
        if (rule == null) return;

        PreferenceCategory list = new PreferenceCategory(c);
        list.setKey("people");
        list.setTitle(R.string.rule_from);
        screen.addPreference(list);
        for (String name : rule.people) {
            Preference p = new Preference(c);
            p.setKey("person_" + name);
            p.setTitle(name);
            p.setIcon(R.drawable.ic_og_person);
            p.setSummary(R.string.people_tap_to_remove);
            p.setOnPreferenceClickListener(pref -> {
                new AlertDialog.Builder(c)
                        .setTitle(getString(R.string.people_remove_title, name))
                        .setPositiveButton(R.string.people_remove, (d, w) -> {
                            rule.people.remove(name);
                            build();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            list.addPreference(p);
        }

        Preference contacts = new Preference(c);
        contacts.setKey("add_contact");
        contacts.setTitle(R.string.people_add_contact);
        contacts.setIcon(R.drawable.ic_og_person_add);
        contacts.setOnPreferenceClickListener(p -> {
            try {
                mPick.launch(null);
            } catch (android.content.ActivityNotFoundException e) {
                typeName();
            }
            return true;
        });
        list.addPreference(contacts);

        Preference type = new Preference(c);
        type.setKey("type_name");
        type.setTitle(R.string.people_type_name);
        type.setIcon(R.drawable.ic_og_edit);
        type.setOnPreferenceClickListener(p -> {
            typeName();
            return true;
        });
        list.addPreference(type);

        FooterPreference footer = new FooterPreference(c);
        footer.setKey("footer");
        footer.setTitle(R.string.people_footer);
        screen.addPreference(footer);
    }

    private void typeName() {
        Context c = requireContext();
        EditText input = new EditText(c);
        input.setSingleLine();
        input.setHint(R.string.people_name_hint);
        int pad = getResources().getDimensionPixelSize(R.dimen.og_tile_gap) * 4;
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(c)
                .setTitle(R.string.people_type_name)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> add(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onPicked(Uri contact) {
        if (contact == null) return;
        try (Cursor cursor = requireContext().getContentResolver().query(contact,
                new String[] {ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) add(cursor.getString(0));
        } catch (SecurityException e) {
            typeName();
        }
    }

    private void add(String name) {
        EssentialRule rule = rule();
        if (rule == null || name == null || name.trim().isEmpty()) return;
        if (!rule.people.contains(name.trim())) rule.people.add(name.trim());
        build();
    }
}
