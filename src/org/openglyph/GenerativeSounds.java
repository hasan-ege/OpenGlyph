package org.openglyph;

import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Environment;
import android.os.ServiceManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openglyph.core.Generative;

/**
 * Generative notification sounds. Each app gets a melody made from this phone's seed and the
 * app's package name (see {@link Generative}); the same theme drives its Glyph animation.
 *
 * With the setting on, each app's notification channels that use the default sound are
 * switched to its own sound (saved under Notifications/OpenGlyph, so it also shows up in the
 * sound picker); channels with a sound the user picked are left alone. Switching it off puts
 * back the default sound on every channel we changed and that still has our sound.
 */
final class GenerativeSounds {
    private static final String TAG = "OpenGlyphSounds";
    static final int SAMPLE_RATE = Generative.SR;
    private static final String PREFS = "generative_sounds";
    /** Bump when the generator changes, so the sounds already set are made again. */
    static final int VERSION = 2;
    private static final String FOLDER = Environment.DIRECTORY_NOTIFICATIONS + "/OpenGlyph/";

    private final Context mContext;
    private final Map<String, Generative.Theme> mThemes = new HashMap<>();
    private final SharedPreferences mPrefs;

    GenerativeSounds(Context context) {
        mContext = context;
        deviceSeed(context.getContentResolver()); // make sure there is one
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * This phone's seed (text, as typed in the browser generator): random on first use, then
     * fixed until the user rerolls or edits it in Settings.
     */
    static String deviceSeed(ContentResolver cr) {
        String s = Settings.Secure.getString(cr, GlyphSettings.SEED);
        if (s != null && !s.trim().isEmpty()) return s.trim();
        return reroll(cr);
    }

    /** A new random seed: every app's sound and light change. */
    static String reroll(ContentResolver cr) {
        String seed = Long.toHexString(new SecureRandom().nextLong() >>> 16);
        Settings.Secure.putString(cr, GlyphSettings.SEED, seed);
        return seed;
    }

    Generative.Engine engine() {
        return Generative.Engine.parse(GlyphSettings.string(mContext.getContentResolver(),
                GlyphSettings.ENGINE, "DIODE"));
    }

    /** The app's theme from the current engine; the seed is this phone's seed + app name. */
    synchronized Generative.Theme theme(String pkg) {
        Generative.Engine engine = engine();
        String seed = deviceSeed(mContext.getContentResolver());
        return mThemes.computeIfAbsent(seed + ":" + engine + ":" + pkg,
                k -> Generative.theme(seed, label(pkg), engine));
    }

    /** Plays an app's sound now (Settings preview). */
    void play(String pkg) {
        short[] pcm = theme(pkg).synth();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        track.setNotificationMarkerPosition(pcm.length);
        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override public void onMarkerReached(AudioTrack t) { t.release(); }
            @Override public void onPeriodicNotification(AudioTrack t) {}
        });
        track.play();
    }

    private String label(String pkg) {
        PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The app's sound in MediaStore, written on first use. */
    synchronized Uri soundUri(String pkg) {
        String key = "uri:" + deviceSeed(mContext.getContentResolver()) + ":" + engine() + ":" + pkg;
        String saved = mPrefs.getString(key, null);
        if (saved != null && exists(Uri.parse(saved))) return Uri.parse(saved);
        String name = "OpenGlyph " + label(pkg).replaceAll("[\\\\/:*?\"<>|]", "_") + " ("
                + engine().name().toLowerCase(java.util.Locale.ROOT) + ").wav";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Audio.Media.TITLE, label(pkg));
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Audio.Media.IS_NOTIFICATION, 1);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        ContentResolver cr = mContext.getContentResolver();
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (OutputStream out = cr.openOutputStream(uri)) {
            out.write(Generative.wav(theme(pkg).synth(), SAMPLE_RATE));
        } catch (Exception e) {
            Log.w(TAG, "writing sound for " + pkg + " failed", e);
            cr.delete(uri, null, null);
            return null;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        mPrefs.edit().putString(key, uri.toString()).apply();
        return uri;
    }

    private boolean exists(Uri uri) {
        try (Cursor c = mContext.getContentResolver().query(uri, new String[] {"_id"}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    private static INotificationManager nm() {
        return INotificationManager.Stub.asInterface(ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    private static boolean isDefaultSound(Uri sound) {
        return sound != null && (sound.equals(Settings.System.DEFAULT_NOTIFICATION_URI)
                || sound.toString().startsWith(Settings.System.DEFAULT_NOTIFICATION_URI.toString()));
    }

    /** Gives an app's default-sound channels its generative sound. Background thread only. */
    synchronized void apply(String pkg) {
        if (mPrefs.getBoolean("done:" + pkg, false)) return;
        try {
            ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
            @SuppressWarnings("unchecked")
            List<NotificationChannel> channels =
                    nm().getNotificationChannelsForPackage(pkg, ai.uid, false).getList();
            Set<String> changed = new HashSet<>(mPrefs.getStringSet("channels:" + pkg, Set.of()));
            Uri ours = null;
            for (NotificationChannel ch : channels) {
                if (!isDefaultSound(ch.getSound())) continue;
                if (ours == null) ours = soundUri(pkg);
                if (ours == null) return;
                ch.setSound(ours, ch.getAudioAttributes());
                nm().updateNotificationChannelForPackage(pkg, ai.uid, ch);
                changed.add(ch.getId());
            }
            if (ours != null) mPrefs.edit().putString("applied:" + pkg, ours.toString()).apply();
            mPrefs.edit().putStringSet("channels:" + pkg, changed)
                    .putBoolean("done:" + pkg, !channels.isEmpty()).apply();
            if (ours != null) Log.i(TAG, "generative sound for " + pkg + " on " + changed.size() + " channels");
        } catch (Exception e) {
            Log.w(TAG, "apply " + pkg + " failed", e);
        }
    }

    /** Puts the default sound back wherever we set ours. Background thread only. */
    synchronized void revertAll() {
        // Every sound we ever made (any seed, engine or generator version): a channel playing
        // one of them was set by us, so it goes back to the default sound.
        Set<String> ours = new HashSet<>();
        for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
            if ((entry.getKey().startsWith("uri:") || entry.getKey().startsWith("applied:"))
                    && entry.getValue() instanceof String v) {
                ours.add(v);
            }
        }
        for (String key : mPrefs.getAll().keySet()) {
            if (!key.startsWith("channels:")) continue;
            String pkg = key.substring("channels:".length());
            try {
                ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
                Set<String> ids = mPrefs.getStringSet(key, Set.of());
                @SuppressWarnings("unchecked")
                List<NotificationChannel> channels =
                        nm().getNotificationChannelsForPackage(pkg, ai.uid, false).getList();
                for (NotificationChannel ch : channels) {
                    if (!ids.contains(ch.getId()) || ch.getSound() == null
                            || !ours.contains(ch.getSound().toString())) continue;
                    ch.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, ch.getAudioAttributes());
                    nm().updateNotificationChannelForPackage(pkg, ai.uid, ch);
                }
            } catch (Exception e) {
                Log.w(TAG, "revert " + pkg + " failed", e);
            }
            mPrefs.edit().remove(key).remove("done:" + pkg).remove("applied:" + pkg).apply();
        }
        // Drop the generated files too; they are made again on demand.
        SharedPreferences.Editor e = mPrefs.edit();
        for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("uri:")) continue;
            try {
                mContext.getContentResolver().delete(Uri.parse((String) entry.getValue()), null, null);
            } catch (Exception ignored) {
            }
            e.remove(entry.getKey());
        }
        e.apply();
        mThemes.clear();
    }

    /** True when the sounds set on channels came from an older generator. */
    boolean outdated() {
        return mPrefs.getInt("version", 1) != VERSION;
    }

    void markCurrent() {
        mPrefs.edit().putInt("version", VERSION).apply();
    }

    /** A new channel may have appeared: look at the app again next time. */
    void forget(String pkg) {
        mPrefs.edit().remove("done:" + pkg).apply();
    }
}
