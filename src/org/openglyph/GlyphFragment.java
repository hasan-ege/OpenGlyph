package org.openglyph;

import android.content.ContentResolver;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/**
 * Base for the Glyph Interface screens: sets the toolbar title, lays half-width
 * {@link TilePreference}s out two per row, and opens sub-screens in place.
 */
abstract class GlyphFragment extends SettingsBasePreferenceFragment {
    abstract int titleRes();

    ContentResolver cr() {
        return requireContext().getContentResolver();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(titleRes());
    }

    void open(Fragment f) {
        open(this, f);
    }

    static void open(Fragment from, Fragment to) {
        from.getParentFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .replace(from.getId(), to)
                .addToBackStack(null)
                .commit();
    }

    /** Click on {@code key} opens the fragment made by {@code to}. */
    void link(String key, java.util.function.Supplier<Fragment> to) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setOnPreferenceClickListener(pref -> {
                open(to.get());
                return true;
            });
        }
    }

    @Override
    public RecyclerView.LayoutManager onCreateLayoutManager() {
        GridLayoutManager glm = new GridLayoutManager(requireContext(), 2);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return isHalfTile(position) ? 1 : 2;
            }
        });
        return glm;
    }

    private boolean isHalfTile(int position) {
        RecyclerView list = getListView();
        if (list == null || !(list.getAdapter() instanceof PreferenceGroupAdapter a)) return false;
        if (position < 0 || position >= a.getItemCount()) return false;
        return a.getItem(position) instanceof TilePreference t && t.isHalf();
    }

    private boolean isTile(int position) {
        RecyclerView list = getListView();
        if (list == null || !(list.getAdapter() instanceof PreferenceGroupAdapter a)) return false;
        if (position < 0 || position >= a.getItemCount()) return false;
        return a.getItem(position) instanceof TilePreference;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.listPreferredItemPaddingStart, tv, true);
        int edge = tv.type == TypedValue.TYPE_DIMENSION
                ? (int) tv.getDimension(getResources().getDisplayMetrics())
                : getResources().getDimensionPixelSize(R.dimen.og_tile_gap) * 4;
        int gap = getResources().getDimensionPixelSize(R.dimen.og_tile_gap);
        getListView().addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View child,
                    @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(child);
                if (!isTile(pos)) return;
                out.bottom += gap;
                if (!isHalfTile(pos)
                        || !(child.getLayoutParams() instanceof GridLayoutManager.LayoutParams lp)) {
                    out.left = edge;
                    out.right = edge;
                } else if (lp.getSpanIndex() == 0) {
                    out.left = edge;
                    out.right = gap / 2;
                } else {
                    out.left = gap / 2;
                    out.right = edge;
                }
            }
        });
    }
}
