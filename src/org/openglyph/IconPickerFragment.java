package org.openglyph;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import org.openglyph.core.EssentialRule;
import org.openglyph.core.GlyphIcons;

/** Rule editor > Icon selection: the app's own icon or one of the built-in pixel icons. */
public final class IconPickerFragment extends Fragment {
    private final List<String> mIcons = new ArrayList<>();

    static String label(Context c, String icon) {
        if (EssentialRule.ICON_APP.equals(icon)) return c.getString(R.string.icon_app);
        int id = c.getResources().getIdentifier("icon_" + icon, "string", c.getPackageName());
        return id != 0 ? c.getString(id) : icon;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        RecyclerView list = (RecyclerView) inflater.inflate(R.layout.og_list, container, false);
        list.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        mIcons.clear();
        mIcons.add(EssentialRule.ICON_APP);
        mIcons.addAll(GlyphIcons.names());
        list.setAdapter(mAdapter);
        return list;
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.rule_icon_choose);
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter =
            new RecyclerView.Adapter<>() {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.og_toy_bubble, parent, false);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.setMarginEnd(0);
            v.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            RuleEditFragment.Draft draft = RuleEditFragment.draft(IconPickerFragment.this);
            EssentialRule rule = draft.rule;
            String icon = mIcons.get(position);
            Context c = holder.itemView.getContext();
            EssentialRule look = new EssentialRule();
            look.icon = icon;
            String pkg = rule == null || rule.apps.isEmpty() ? null : rule.apps.iterator().next();
            ((ImageView) holder.itemView.findViewById(R.id.bubble_icon))
                    .setImageDrawable(EssentialFragment.ruleIcon(c, look, pkg));
            boolean chosen = rule != null && icon.equals(rule.icon);
            holder.itemView.findViewById(R.id.bubble_ring)
                    .setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setContentDescription(label(c, icon));
            holder.itemView.setOnClickListener(v -> {
                if (rule == null) return;
                rule.icon = icon;
                getParentFragmentManager().popBackStack();
            });
        }

        @Override
        public int getItemCount() {
            return mIcons.size();
        }
    };
}
