package org.openglyph;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * Lets the camera app show the recording light switch in its own settings. Only call() is
 * served: "get" returns {enabled}, "set" takes {enabled}. Callers must be a preinstalled
 * (system) camera app on the allow list; everyone else gets a SecurityException.
 */
public final class RecordLightProvider extends ContentProvider {
    static final String AUTHORITY = "org.openglyph.recordlight";
    static final String KEY_ENABLED = "enabled";
    private static final String[] CAMERA_APPS = {"org.lineageos.aperture"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        final long id = Binder.clearCallingIdentity();
        try {
            if ("set".equals(method) && extras != null) {
                GlyphSettings.setFlag(getContext().getContentResolver(), GlyphSettings.RECORD_LED,
                        extras.getBoolean(KEY_ENABLED));
            } else if (!"get".equals(method)) {
                return null;
            }
            Bundle out = new Bundle();
            out.putBoolean(KEY_ENABLED,
                    GlyphSettings.flag(getContext().getContentResolver(), GlyphSettings.RECORD_LED));
            return out;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    private void checkCaller() {
        int uid = Binder.getCallingUid();
        PackageManager pm = getContext().getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs != null) {
            for (String pkg : pkgs) {
                for (String allowed : CAMERA_APPS) {
                    if (!allowed.equals(pkg)) continue;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return;
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
            }
        }
        throw new SecurityException("uid " + uid + " may not change the recording light");
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
