package org.openglyph;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Generative ringtones: content://org.openglyph.ringtone/caller/<key>, where key is the
 * SHA-256 (hex) of the caller's number as Telecom's RingtoneFactory normalises it. Only the
 * system (Telecom) may open one; the number itself never reaches us.
 *
 * Opening a caller's ringtone also tells the service who is ringing, so the matrix loops the
 * same caller's animation in step with the sound.
 */
public final class RingtoneProvider extends ContentProvider {
    static final String AUTHORITY = "org.openglyph.ringtone";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("only the system may play generative ringtones");
        }
        String key = callerKey(uri);
        GlyphService service = GlyphService.get();
        if (key == null || service == null) throw new FileNotFoundException(uri.toString());
        File file;
        try {
            file = service.sounds().ringtoneFile(key);
        } catch (Exception e) {
            Log.w(GlyphService.TAG, "generative ringtone failed", e);
            throw new FileNotFoundException(e.getMessage());
        }
        service.onCallerRingtone(key);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The 64-hex-digit key of caller/<key>, or null. */
    private static String callerKey(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"caller".equals(seg.get(0))) return null;
        String key = seg.get(1);
        return key.matches("[0-9a-f]{64}") ? key : null;
    }

    @Override
    public String getType(Uri uri) {
        return "audio/x-wav";
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
