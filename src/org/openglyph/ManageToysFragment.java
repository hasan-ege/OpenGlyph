package org.openglyph;

import android.content.ContentResolver;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manage Glyph Toys: active toys in Glyph Button order (drag to reorder, minus to remove),
 * then the other installed toys (plus to add). Saved to {@link GlyphSettings#TOY_ORDER}.
 */
public final class ManageToysFragment extends Fragment {
    private static final int HEADER = 0, ACTIVE = 1, INACTIVE = 2;

    private final List<ToyCatalog.Entry> mActive = new ArrayList<>();
    private final List<ToyCatalog.Entry> mInactive = new ArrayList<>();
    private ItemTouchHelper mDrag;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        RecyclerView list = (RecyclerView) inflater.inflate(R.layout.og_list, container, false);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);
        mDrag = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getDragDirs(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                return vh.getItemViewType() == ACTIVE ? super.getDragDirs(rv, vh) : 0;
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false; // the handle starts the drag
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                    @NonNull RecyclerView.ViewHolder to) {
                if (to.getItemViewType() != ACTIVE) return false;
                int a = from.getBindingAdapterPosition() - 1, b = to.getBindingAdapterPosition() - 1;
                Collections.swap(mActive, a, b);
                mAdapter.notifyItemMoved(a + 1, b + 1);
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                save();
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}
        });
        mDrag.attachToRecyclerView(list);
        load();
        return list;
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.toys_manage_title);
    }

    private void load() {
        List<ToyCatalog.Entry> all = ToyCatalog.all(requireContext());
        List<String> ids = new ArrayList<>();
        for (ToyCatalog.Entry e : all) ids.add(e.id);
        mActive.clear();
        mInactive.clear();
        for (String id : GlyphSettings.activeToys(requireContext().getContentResolver(), ids)) {
            ToyCatalog.Entry e = ToyCatalog.byId(all, id);
            if (e != null) mActive.add(e);
        }
        for (ToyCatalog.Entry e : all) if (!mActive.contains(e)) mInactive.add(e);
        mAdapter.notifyDataSetChanged();
    }

    private void save() {
        ContentResolver cr = requireContext().getContentResolver();
        List<String> ids = new ArrayList<>();
        for (ToyCatalog.Entry e : mActive) ids.add(e.id);
        GlyphSettings.setActiveToys(cr, ids);
    }

    private void toggle(ToyCatalog.Entry e) {
        if (mActive.remove(e)) mInactive.add(0, e); else if (mInactive.remove(e)) mActive.add(e);
        save();
        mAdapter.notifyDataSetChanged();
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter =
            new RecyclerView.Adapter<>() {
        // Layout: [header active] active... [header more] inactive...
        @Override
        public int getItemCount() {
            return 1 + mActive.size() + (mInactive.isEmpty() ? 0 : 1 + mInactive.size());
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 || position == mActive.size() + 1) return HEADER;
            return position <= mActive.size() ? ACTIVE : INACTIVE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(
                    type == HEADER ? R.layout.og_header : R.layout.og_toy_row, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = getItemViewType(position);
            if (type == HEADER) {
                ((TextView) holder.itemView).setText(position == 0
                        ? R.string.toys_active : R.string.toys_more);
                return;
            }
            ToyCatalog.Entry e = type == ACTIVE ? mActive.get(position - 1)
                    : mInactive.get(position - mActive.size() - 2);
            View v = holder.itemView;
            int size = v.getResources().getDimensionPixelSize(R.dimen.og_list_icon);
            ((ImageView) v.findViewById(R.id.row_icon)).setImageDrawable(ToyCatalog.icon(e, size));
            ((TextView) v.findViewById(R.id.row_title)).setText(e.label);
            ((TextView) v.findViewById(R.id.row_summary)).setText(e.summary);
            View handle = v.findViewById(R.id.row_drag);
            handle.setVisibility(type == ACTIVE ? View.VISIBLE : View.INVISIBLE);
            handle.setOnTouchListener((h, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) mDrag.startDrag(holder);
                return false;
            });
            ImageButton action = v.findViewById(R.id.row_action);
            action.setImageResource(type == ACTIVE
                    ? R.drawable.ic_og_remove_circle : R.drawable.ic_og_add_circle);
            action.setContentDescription(v.getContext().getString(type == ACTIVE
                    ? R.string.toys_remove : R.string.toys_add, e.label));
            action.setOnClickListener(b -> toggle(e));
        }
    };
}
