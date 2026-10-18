package org.openglyph;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.GroupSectionDividerMixin;
import com.android.settingslib.widget.NormalPaddingMixin;

import java.util.ArrayList;
import java.util.List;

/**
 * A Glyph Interface tile: a rounded card with an icon (or a strip of toy previews) and an
 * optional switch on top, title and summary below. Half-width tiles sit two per row (see
 * {@link GlyphFragment}). Clicking the card runs the click listener; the switch is separate.
 * The card draws its own background, so the expressive adapter leaves it alone.
 */
public class TilePreference extends Preference implements GroupSectionDividerMixin,
        NormalPaddingMixin {
    public interface OnSwitchListener {
        void onSwitch(TilePreference tile, boolean on);
    }

    private boolean mHalf;
    private boolean mHasSwitch;
    private boolean mChecked;
    private OnSwitchListener mSwitchListener;
    private final List<Drawable> mStrip = new ArrayList<>();

    public TilePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TilePreference);
        mHalf = a.getBoolean(R.styleable.TilePreference_tileHalf, false);
        mHasSwitch = a.getBoolean(R.styleable.TilePreference_tileSwitch, false);
        a.recycle();
        setLayoutResource(R.layout.og_tile);
    }

    public TilePreference(Context context) {
        super(context);
        setLayoutResource(R.layout.og_tile);
    }

    public boolean isHalf() {
        return mHalf;
    }

    public void setHalf(boolean half) {
        mHalf = half;
    }

    public void setSwitch(boolean checked, OnSwitchListener listener) {
        mHasSwitch = true;
        mChecked = checked;
        mSwitchListener = listener;
        notifyChanged();
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setChecked(boolean checked) {
        if (mChecked == checked) return;
        mChecked = checked;
        notifyChanged();
    }

    /** Small round pictures next to the icon (the toys strip). */
    public void setStrip(List<Drawable> pictures) {
        mStrip.clear();
        mStrip.addAll(pictures);
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
        icon.setVisibility(getIcon() == null ? View.GONE : View.VISIBLE);

        LinearLayout strip = (LinearLayout) holder.findViewById(R.id.tile_strip);
        strip.removeAllViews();
        int size = getContext().getResources().getDimensionPixelSize(R.dimen.og_toy_strip_icon);
        for (Drawable d : mStrip) {
            ImageView iv = new ImageView(getContext());
            iv.setImageDrawable(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(size / 8);
            strip.addView(iv, lp);
        }

        Switch sw = (Switch) holder.findViewById(R.id.tile_switch);
        sw.setVisibility(mHasSwitch ? View.VISIBLE : View.GONE);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(mChecked);
        sw.setEnabled(isEnabled());
        sw.setContentDescription(getTitle());
        sw.setOnCheckedChangeListener((button, on) -> {
            mChecked = on;
            if (mSwitchListener != null) mSwitchListener.onSwitch(this, on);
        });
        holder.itemView.setAlpha(isEnabled() ? 1f : 0.5f);
    }

    @Override
    protected void onClick() {
        // A tile with a switch and nowhere to go toggles, like a switch preference.
        if (mHasSwitch && getOnPreferenceClickListener() == null && getFragment() == null
                && getIntent() == null) {
            mChecked = !mChecked;
            notifyChanged();
            if (mSwitchListener != null) mSwitchListener.onSwitch(this, mChecked);
        }
    }
}
