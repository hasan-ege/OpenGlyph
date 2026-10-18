package org.openglyph;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable apps (one entry per package), sorted by label, for the Settings pickers. */
final class AppList {
    static final class App {
        final String pkg;
        final CharSequence label;
        final Drawable icon;

        App(String pkg, CharSequence label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private AppList() {}

    static List<App> launchable(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, new App(pkg, ri.loadLabel(pm), ri.loadIcon(pm)));
        }
        List<App> apps = new ArrayList<>(byPkg.values());
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return apps;
    }
}
