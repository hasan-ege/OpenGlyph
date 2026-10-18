package org.openglyph;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ToggleButton;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.GroupSectionDividerMixin;
import com.android.settingslib.widget.NormalPaddingMixin;

/** Seven round day toggles (Monday first) for a bitmask with Monday as bit 0. */
public final class DaysPreference extends Preference implements GroupSectionDividerMixin,
        NormalPaddingMixin {
    public interface Listener {
        void onDays(int mask);
    }

    private int mMask;
    private Listener mListener;

    public DaysPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.og_days);
        setSelectable(false);
    }

    public void setDays(int mask, Listener listener) {
        mMask = mask;
        mListener = listener;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        LinearLayout row = (LinearLayout) holder.findViewById(R.id.days_row);
        row.removeAllViews();
        String[] letters = getContext().getResources().getStringArray(R.array.day_letters);
        String[] names = getContext().getResources().getStringArray(R.array.day_short_names);
        float dp = getContext().getResources().getDisplayMetrics().density;
        int size = (int) (40 * dp);
        for (int d = 0; d < 7; d++) {
            final int day = d;
            ToggleButton b = new ToggleButton(getContext());
            b.setBackgroundResource(R.drawable.og_day);
            b.setTextColor(getContext().getColorStateList(R.color.og_day_text));
            b.setTextOn(letters[d]);
            b.setTextOff(letters[d]);
            b.setChecked((mMask & (1 << d)) != 0);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinHeight(0);
            b.setMinimumWidth(0);
            b.setMinimumHeight(0);
            b.setContentDescription(names[d]);
            b.setOnCheckedChangeListener((btn, on) -> {
                mMask = on ? mMask | (1 << day) : mMask & ~(1 << day);
                if (mListener != null) mListener.onDays(mMask);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart((int) (3 * dp));
            lp.setMarginEnd((int) (3 * dp));
            row.addView(b, lp);
        }
    }
}
