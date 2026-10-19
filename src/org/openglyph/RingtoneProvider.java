package org.openglyph;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

/**
 * Generative ringtones for Telecom. Telecom calls {@code call("ringtone", <key>)}, where key is
 * the SHA-256 (hex) of the caller's number as its RingtoneFactory normalises it, so the number
 * itself never reaches us. We answer with the caller's ringtone as a MediaStore URI (readable by
 * Telecom and by SystemUI's ringtone player alike) and start looping the same caller's Glyph
 * animation. Only the system may ask.
 */
public final class RingtoneProvider extends ContentProvider {
    static final String AUTHORITY = "org.openglyph.ringtone";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (UserHandle.getAppId(uid) != Process.SYSTEM_UID) {
            throw new SecurityException("only the system may ask for generative ringtones (uid " + uid + ")");
        }
        if (!"ringtone".equals(method) || arg == null || !arg.matches("[0-9a-f]{64}")) return null;
        GlyphService service = GlyphService.get();
        if (service == null) return null;
        final long id = Binder.clearCallingIdentity();
        try {
            Uri uri = service.sounds().ringtoneUri(arg);
            if (uri == null) return null;
            service.onCallerRingtone(arg);
            Bundle out = new Bundle();
            out.putString("uri", uri.toString());
            return out;
        } catch (Exception e) {
            Log.w(GlyphService.TAG, "generative ringtone failed", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
