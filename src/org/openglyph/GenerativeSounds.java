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
 * app's package name (not its display name) (see {@link Generative}); the same theme drives its Glyph animation.
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
    static final int VERSION = 3; // 3: seeded by package instead of app name
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
        // Seeded by the package, not the app's name: a rename keeps the app's sound and light,
        // and two apps with the same name still differ.
        return mThemes.computeIfAbsent(seed + ":" + engine + ":" + pkg,
                k -> Generative.theme(seed, pkg, engine));
    }

    /**
     * The theme of a caller: this phone's seed + "call:" + a hash of the caller's number (the
     * number itself never reaches us, see RingtoneProvider).
     */
    synchronized Generative.Theme callerTheme(String callerKey) {
        Generative.Engine engine = engine();
        String seed = deviceSeed(mContext.getContentResolver());
        return mThemes.computeIfAbsent(seed + ":" + engine + ":call:" + callerKey,
                k -> Generative.ringTheme(seed, "call:" + callerKey, engine));
    }

    /**
     * One ring of a caller's ringtone, which the ringer loops: the melody cut where it dies
     * away, so the next ring follows with no silence (as trimTail in the browser generator).
     */
    static short[] ringLoop(Generative.Theme theme) {
        short[] pcm = theme.synth();
        int peak = 1;
        for (short v : pcm) peak = Math.max(peak, Math.abs(v));
        int end = pcm.length;
        while (end > 0 && Math.abs(pcm[end - 1]) < peak * 0.02) end--;
        end = Math.min(pcm.length, end + SAMPLE_RATE * 40 / 1000);
        short[] out = java.util.Arrays.copyOf(pcm, end);
        int fade = Math.min(end, SAMPLE_RATE * 30 / 1000);
        for (int i = 0; i < fade; i++) out[end - 1 - i] = (short) (out[end - 1 - i] * i / fade);
        return out;
    }

    /** Deletes caller ringtones made by another version or settings (other key prefix). */
    private void dropStaleRingtones(String prefix) {
        SharedPreferences.Editor e = mPrefs.edit();
        for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("ring") || entry.getKey().startsWith(prefix)) continue;
            try {
                mContext.getContentResolver().delete(Uri.parse((String) entry.getValue()), null, null);
            } catch (Exception ignored) {
            }
            e.remove(entry.getKey());
        }
        e.apply();
    }

    private final java.util.Map<Generative.Theme, Long> mRingPeriods = new java.util.WeakHashMap<>();

    /** The length of one ring, for looping the caller's animation in step with the sound. */
    synchronized long ringPeriodMs(Generative.Theme theme) {
        return mRingPeriods.computeIfAbsent(theme,
                t -> ringLoop(t).length * 1000L / SAMPLE_RATE);
    }

    private static final String RING_FOLDER = Environment.DIRECTORY_RINGTONES + "/OpenGlyph/";

    /**
     * The caller's ringtone in MediaStore (Ringtones/OpenGlyph), made on first use: Telecom
     * (system_server) and SystemUI's ringtone player can both read media files, while a file in
     * our own storage is out of their reach (SELinux). Null before the first unlock.
     */
    synchronized Uri ringtoneUri(String callerKey) {
        if (!unlocked()) return null;
        String key = "ring2:" + stamp() + ":" + callerKey;
        String saved = mPrefs.getString(key, null);
        if (saved != null && exists(Uri.parse(saved))) return Uri.parse(saved);
        dropStaleRingtones("ring2:" + stamp() + ":");
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, "OpenGlyph caller " + callerKey.substring(0, 12) + ".wav");
        v.put(MediaStore.Audio.Media.TITLE, "OpenGlyph caller");
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, RING_FOLDER);
        v.put(MediaStore.Audio.Media.IS_RINGTONE, 1);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        ContentResolver cr = mContext.getContentResolver();
        Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return null;
        try (OutputStream out = cr.openOutputStream(uri)) {
            Generative.Theme theme = callerTheme(callerKey);
            short[] ring = ringLoop(theme);
            mRingPeriods.put(theme, ring.length * 1000L / SAMPLE_RATE);
            out.write(Generative.wav(ring, SAMPLE_RATE));
        } catch (Exception e) {
            Log.w(TAG, "writing ringtone failed", e);
            cr.delete(uri, null, null);
            return null;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        mPrefs.edit().putString(key, uri.toString()).commit();
        return uri;
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

    /** Generator version + seed + engine: sounds made under another stamp are out of date. */
    private String stamp() {
        return VERSION + ":" + deviceSeed(mContext.getContentResolver()) + ":" + engine();
    }

    /** The app's sound in MediaStore for the current stamp, written on first use. */
    synchronized Uri soundUri(String pkg) {
        String key = "uri:" + stamp() + ":" + pkg;
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
        mPrefs.edit().putString(key, uri.toString()).commit();
        return uri;
    }

    private boolean exists(Uri uri) {
        try (Cursor c = mContext.getContentResolver().query(uri, new String[] {"_id"}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every sound file in our folder (any seed, engine or generator version), as content URIs.
     * A channel playing one of them was set by us, whatever our own records say.
     */
    private Set<String> ourFiles() {
        Set<String> out = new HashSet<>();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        try (Cursor c = mContext.getContentResolver().query(base,
                new String[] {MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.RELATIVE_PATH + "=?", new String[] {FOLDER}, null)) {
            while (c != null && c.moveToNext()) {
                out.add(android.content.ContentUris.withAppendedId(base, c.getLong(0)).toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "listing our sounds failed", e);
        }
        return out;
    }

    private static INotificationManager nm() {
        return INotificationManager.Stub.asInterface(ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    private static boolean isDefaultSound(Uri sound) {
        return sound != null && (sound.equals(Settings.System.DEFAULT_NOTIFICATION_URI)
                || sound.toString().startsWith(Settings.System.DEFAULT_NOTIFICATION_URI.toString()));
    }

    private boolean unlocked() {
        return mContext.getSystemService(android.os.UserManager.class).isUserUnlocked();
    }

    @SuppressWarnings("unchecked")
    private List<NotificationChannel> channels(String pkg, int uid) throws Exception {
        return nm().getNotificationChannelsForPackage(pkg, uid, false).getList();
    }

    /**
     * Gives the app's channels its current generative sound: channels on the default sound,
     * and channels still playing an older sound of ours. Sounds the user picked are left alone.
     * Background thread only; does nothing until the phone is unlocked (no storage before).
     */
    synchronized void apply(String pkg) {
        apply(pkg, null);
    }

    private void apply(String pkg, Set<String> ours) {
        if (!unlocked()) return;
        try {
            ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
            Uri current = null;
            int changed = 0;
            for (NotificationChannel ch : channels(pkg, ai.uid)) {
                Uri sound = ch.getSound();
                boolean ourOld = sound != null && !isDefaultSound(sound);
                if (ourOld) {
                    if (ours == null) ours = ourFiles();
                    // Ours, or a media sound whose file is gone (nothing would play): replace.
                    ourOld = ours.contains(sound.toString()) || isMissingMedia(sound);
                }
                if (!isDefaultSound(sound) && !ourOld) continue;
                if (current == null) current = soundUri(pkg);
                if (current == null) return;
                if (current.equals(sound)) continue;
                ch.setSound(current, ch.getAudioAttributes());
                nm().updateNotificationChannelForPackage(pkg, ai.uid, ch);
                changed++;
            }
            if (changed > 0) Log.i(TAG, "generative sound for " + pkg + " on " + changed + " channels");
        } catch (Exception e) {
            Log.w(TAG, "apply " + pkg + " failed", e);
        }
    }

    /** A media-store sound whose row no longer exists: the channel would fall back to silence. */
    private boolean isMissingMedia(Uri sound) {
        if (!sound.toString().startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())) {
            return false;
        }
        // Only a clean "no such row" counts; a failed query (media store not up yet) does not.
        try (Cursor c = mContext.getContentResolver().query(sound, new String[] {"_id"}, null, null, null)) {
            return c != null && !c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every installed package with channels, not just apps with a launcher icon: a channel
     * we gave a sound must never be left pointing at a file we deleted.
     */
    private List<String> packages() {
        List<String> out = new java.util.ArrayList<>();
        for (ApplicationInfo ai : mContext.getPackageManager().getInstalledApplications(0)) {
            out.add(ai.packageName);
        }
        return out;
    }

    /** Sounds any channel still plays, so a file in use is never deleted. */
    private Set<String> inUse(List<String> pkgs) {
        Set<String> out = new HashSet<>();
        for (String pkg : pkgs) {
            try {
                ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
                for (NotificationChannel ch : channels(pkg, ai.uid)) {
                    if (ch.getSound() != null) out.add(ch.getSound().toString());
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Every app gets its current sound; files no channel needs any more go. */
    synchronized void syncAll() {
        if (!unlocked()) return;
        Set<String> ours = ourFiles();
        List<String> pkgs = packages();
        for (String pkg : pkgs) apply(pkg, ours);
        // Keep only this stamp's files.
        String prefix = "uri:" + stamp() + ":";
        Set<String> keep = new HashSet<>();
        SharedPreferences.Editor e = mPrefs.edit();
        for (Map.Entry<String, ?> entry : mPrefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("uri:")) continue;
            if (entry.getKey().startsWith(prefix)) keep.add((String) entry.getValue());
            else e.remove(entry.getKey());
        }
        e.commit();
        Set<String> used = inUse(pkgs);
        for (String uri : ourFiles()) {
            if (keep.contains(uri) || used.contains(uri)) continue;
            try {
                mContext.getContentResolver().delete(Uri.parse(uri), null, null);
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, "generative sounds in sync (" + keep.size() + " sounds)");
    }

    /** Puts the default sound back on every channel playing one of our sounds; deletes them. */
    synchronized void revertAll() {
        if (!unlocked()) return;
        Set<String> ours = ourFiles();
        for (String pkg : packages()) {
            try {
                ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
                for (NotificationChannel ch : channels(pkg, ai.uid)) {
                    if (ch.getSound() == null || !ours.contains(ch.getSound().toString())) continue;
                    ch.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, ch.getAudioAttributes());
                    nm().updateNotificationChannelForPackage(pkg, ai.uid, ch);
                }
            } catch (Exception e) {
                Log.w(TAG, "revert " + pkg + " failed", e);
            }
        }
        for (String uri : ours) {
            try {
                mContext.getContentResolver().delete(Uri.parse(uri), null, null);
            } catch (Exception ignored) {
            }
        }
        mPrefs.edit().clear().apply();
        mThemes.clear();
        Log.i(TAG, "generative sounds removed");
    }
}
