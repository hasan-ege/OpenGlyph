package org.openglyph;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.GroupSectionDividerMixin;
import com.android.settingslib.widget.NormalPaddingMixin;

/** Big round matrix at the top of Glyph Interface, mirroring what the real matrix shows. */
public final class HeroPreference extends Preference implements GroupSectionDividerMixin,
        NormalPaddingMixin {
    public HeroPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.og_hero);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        MatrixView view = (MatrixView) holder.findViewById(R.id.hero_matrix);
        view.setSource(grid -> {
            GlyphService service = GlyphService.get();
            if (service == null) {
                java.util.Arrays.fill(grid, 0);
                return 1000;
            }
            service.snapshot(grid);
            return 66;
        });
    }
}
