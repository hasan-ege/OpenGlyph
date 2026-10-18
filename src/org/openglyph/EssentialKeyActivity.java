package org.openglyph;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Settings > Essential Key: what the key on the side of the phone does. Swipe the key (or tap a
 * dot) to the action; some actions have options or need an app. The device KeyHandler reads
 * the same Settings.Secure keys (see {@link EssentialKey}).
 */
public final class EssentialKeyActivity extends Activity {
    private RecyclerView mCarousel;
    private LinearLayoutManager mLayout;
    private final PagerSnapHelper mSnap = new PagerSnapHelper();
    private int mSelected = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.og_essential);
        getWindow().setStatusBarColor(0xFF050505);
        getWindow().setNavigationBarColor(0xFF050505);
        findViewById(R.id.ek_back).setOnClickListener(v -> finish());

        mCarousel = findViewById(R.id.ek_carousel);
        mLayout = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        mCarousel.setLayoutManager(mLayout);
        mCarousel.setAdapter(mAdapter);
        mSnap.attachToRecyclerView(mCarousel);
        mCarousel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                styleChildren();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    View snapped = mSnap.findSnapView(mLayout);
                    if (snapped != null) select(rv.getChildAdapterPosition(snapped), true);
                }
            }
        });
        // Centre the first and last keys: pad by half the free width once we know it.
        mCarousel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int item = getResources().getDimensionPixelSize(R.dimen.og_ek_item);
            int pad = Math.max(0, (r - l - item) / 2);
            if (mCarousel.getPaddingStart() != pad) {
                mCarousel.setPaddingRelative(pad, 0, pad, 0);
                mCarousel.post(this::styleChildren);
            }
        });

        LinearLayout dots = findViewById(R.id.ek_dots);
        int size = (int) (7 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < EssentialKey.ACTIONS.length; i++) {
            final int index = i;
            View dot = new View(this);
            dot.setBackgroundResource(R.drawable.og_ek_dot);
            dot.setContentDescription(getString(EssentialKey.ACTIONS[i].title));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(size / 2, size, size / 2, size);
            dot.setOnClickListener(v -> mCarousel.smoothScrollToPosition(index));
            dots.addView(dot, lp);
        }

        segments(findViewById(R.id.ek_trigger), EssentialKey.TRIGGER,
                new String[] {EssentialKey.TRIGGER_HOLD, EssentialKey.TRIGGER_PRESS},
                new int[] {R.string.ek_trigger_hold, R.string.ek_trigger_press},
                EssentialKey.TRIGGER_HOLD);

        int current = EssentialKey.indexOf(EssentialKey.action(getContentResolver()));
        mLayout.scrollToPosition(current);
        select(current, false);
    }

    /** Fades keys away from the centre: the middle one is the lit key, the rest just icons. */
    private void styleChildren() {
        int centre = mCarousel.getWidth() / 2;
        for (int i = 0; i < mCarousel.getChildCount(); i++) {
            View child = mCarousel.getChildAt(i);
            float d = Math.abs((child.getLeft() + child.getRight()) / 2f - centre) / child.getWidth();
            float key = Math.max(0, 1 - d * 1.6f);
            View k = child.findViewById(R.id.ek_key);
            k.getBackground().setAlpha((int) (255 * key));
            ImageView icon = child.findViewById(R.id.ek_icon);
            icon.setAlpha(Math.max(0.28f, 1 - d * 0.75f));
            float scale = 1 - Math.min(0.35f, d * 0.35f);
            icon.setScaleX(scale);
            icon.setScaleY(scale);
        }
    }

    private void select(int position, boolean save) {
        if (position < 0 || position >= EssentialKey.ACTIONS.length) return;
        EssentialKey.Action a = EssentialKey.ACTIONS[position];
        if (save && position != mSelected) {
            Settings.Secure.putString(getContentResolver(), EssentialKey.ACTION, a.id);
        }
        mSelected = position;
        ((TextView) findViewById(R.id.ek_name)).setText(a.title);
        ((TextView) findViewById(R.id.ek_desc)).setText(a.desc);
        LinearLayout dots = findViewById(R.id.ek_dots);
        for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setSelected(i == position);

        LinearLayout options = findViewById(R.id.ek_options);
        options.removeAllViews();
        options.setVisibility(a.optionKey == null ? View.GONE : View.VISIBLE);
        if (a.optionKey != null) segments(options, a.optionKey, a.optionValues, a.optionLabels, a.optionValues[0]);

        TextView choose = findViewById(R.id.ek_choose);
        choose.setVisibility(a.needsApp ? View.VISIBLE : View.GONE);
        if (a.needsApp) {
            CharSequence app = appLabel();
            choose.setText(app == null ? getString(R.string.ek_choose_app) : app);
            choose.setOnClickListener(v -> chooseApp(choose));
        }
        styleChildren();
    }

    /** A row of segment buttons bound to one setting. */
    private void segments(LinearLayout row, String key, String[] values, int[] labels, String def) {
        row.removeAllViews();
        ContentResolver cr = getContentResolver();
        String current = Settings.Secure.getString(cr, key);
        if (current == null) current = def;
        float dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            TextView seg = new TextView(this);
            seg.setText(labels[i]);
            seg.setTextSize(14);
            seg.setTextColor(getColorStateList(R.color.og_ek_segment_text));
            seg.setBackgroundResource(R.drawable.og_ek_segment);
            seg.setPadding((int) (16 * dp), (int) (9 * dp), (int) (16 * dp), (int) (9 * dp));
            seg.setSelected(value.equals(current));
            seg.setOnClickListener(v -> {
                Settings.Secure.putString(cr, key, value);
                for (int c = 0; c < row.getChildCount(); c++) row.getChildAt(c).setSelected(row.getChildAt(c) == v);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (4 * dp), 0, (int) (4 * dp), 0);
            row.addView(seg, lp);
        }
    }

    private CharSequence appLabel() {
        String flat = Settings.Secure.getString(getContentResolver(), EssentialKey.APP);
        ComponentName cn = flat == null ? null : ComponentName.unflattenFromString(flat);
        if (cn == null) return null;
        try {
            return getPackageManager().getActivityInfo(cn, 0).loadLabel(getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void chooseApp(TextView button) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        apps.sort((x, y) -> x.loadLabel(pm).toString().compareToIgnoreCase(y.loadLabel(pm).toString()));
        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(pm);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.ek_choose_app)
                .setItems(labels, (d, which) -> {
                    ResolveInfo ri = apps.get(which);
                    ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                    Settings.Secure.putString(getContentResolver(), EssentialKey.APP, cn.flattenToString());
                    button.setText(labels[which]);
                })
                .show();
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter = new RecyclerView.Adapter<>() {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.og_ek_item, parent, false);
            v.findViewById(R.id.ek_key).setBackground(
                    getDrawable(R.drawable.og_ek_key_on).mutate());
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            EssentialKey.Action a = EssentialKey.ACTIONS[position];
            ((ImageView) h.itemView.findViewById(R.id.ek_icon)).setImageResource(a.icon);
            h.itemView.setContentDescription(getString(a.title));
            h.itemView.setOnClickListener(v -> mCarousel.smoothScrollToPosition(position));
        }

        @Override
        public int getItemCount() {
            return EssentialKey.ACTIONS.length;
        }
    };
}
