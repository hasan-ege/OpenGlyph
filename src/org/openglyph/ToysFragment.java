package org.openglyph;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Glyph Toys: the active toys in Glyph Button order, a live preview of the one picked, a
 * button to show it on the real matrix, and the way to Manage toys.
 */
public final class ToysFragment extends Fragment {
    private final List<ToyCatalog.Entry> mActive = new ArrayList<>();
    private int mPicked;
    private MatrixView mPreview;
    private TextView mTitle;
    private TextView mSummary;
    private RecyclerView mCarousel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.og_toys, container, false);
        mPreview = v.findViewById(R.id.toy_preview);
        mTitle = v.findViewById(R.id.toy_title);
        mSummary = v.findViewById(R.id.toy_summary);
        mCarousel = v.findViewById(R.id.toy_carousel);
        mCarousel.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        mCarousel.setAdapter(mAdapter);
        v.findViewById(R.id.toy_manage).setOnClickListener(b ->
                GlyphFragment.open(this, new ManageToysFragment()));
        v.findViewById(R.id.toy_timeout).setOnClickListener(b -> pickTimeout());
        v.findViewById(R.id.toy_try).setOnClickListener(b -> {
            GlyphService service = GlyphService.get();
            if (service != null && mPicked < mActive.size()) service.showToy(mActive.get(mPicked).id);
        });
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.settings_toys_tile);
        List<ToyCatalog.Entry> all = ToyCatalog.all(requireContext());
        mActive.clear();
        for (String id : GlyphSettings.activeToys(requireContext().getContentResolver())) {
            ToyCatalog.Entry e = ToyCatalog.byId(all, id);
            if (e != null && !GlyphSettings.OFF.equals(e.id)) mActive.add(e);
        }
        mPicked = Math.min(mPicked, Math.max(0, mActive.size() - 1));
        View root = requireView();
        root.findViewById(R.id.toy_empty).setVisibility(mActive.isEmpty() ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.toy_try).setEnabled(!mActive.isEmpty());
        mAdapter.notifyDataSetChanged();
        pick(mPicked);
        showTimeout();
    }

    private int timeoutIndex() {
        long ms = GlyphSettings.toyTimeoutMs(requireContext().getContentResolver());
        long[] choices = org.openglyph.toys.ToyHost.TIMEOUTS_MS;
        for (int i = 0; i < choices.length; i++) if (choices[i] == ms) return i;
        return 0;
    }

    private void showTimeout() {
        String[] labels = getResources().getStringArray(R.array.toy_timeout_entries);
        ((TextView) requireView().findViewById(R.id.toy_timeout_value))
                .setText(labels[timeoutIndex()]);
    }

    /** Glyph Toys timeout: the stock choices, 15 seconds to 30 minutes. */
    private void pickTimeout() {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.toys_timeout)
                .setSingleChoiceItems(R.array.toy_timeout_entries, timeoutIndex(), (d, which) -> {
                    android.provider.Settings.Secure.putLong(
                            requireContext().getContentResolver(), GlyphSettings.TOY_TIMEOUT,
                            org.openglyph.toys.ToyHost.TIMEOUTS_MS[which]);
                    showTimeout();
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pick(int position) {
        mPicked = position;
        if (mActive.isEmpty()) {
            mPreview.setGrid(null);
            mTitle.setText(null);
            mSummary.setText(null);
            return;
        }
        ToyCatalog.Entry e = mActive.get(position);
        ToyCatalog.show(mPreview, e);
        mPreview.setContentDescription(e.label);
        mTitle.setText(e.label);
        mSummary.setText(e.summary);
        mAdapter.notifyDataSetChanged();
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter =
            new RecyclerView.Adapter<>() {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.og_toy_bubble, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ToyCatalog.Entry e = mActive.get(position);
            ImageView icon = holder.itemView.findViewById(R.id.bubble_icon);
            int size = holder.itemView.getResources().getDimensionPixelSize(R.dimen.og_list_icon);
            icon.setImageDrawable(ToyCatalog.icon(e, size));
            holder.itemView.findViewById(R.id.bubble_ring)
                    .setVisibility(position == mPicked ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setContentDescription(e.label);
            holder.itemView.setSelected(position == mPicked);
            holder.itemView.setOnClickListener(v -> pick(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return mActive.size();
        }
    };
}
