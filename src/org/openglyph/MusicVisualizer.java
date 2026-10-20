package org.openglyph;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

import org.openglyph.effects.VoiceWave;

/**
 * Music visualisation: while media plays through the phone's own speaker, the output mix is
 * captured (audio effect "Visualizer" on session 0) and drawn as a waveform ({@link VoiceWave}):
 * every {@link #FRAME_MS} the latest slice's positive and negative peaks become one stroke.
 * Headphones, Bluetooth and calls leave the matrix alone (calls have their own source).
 */
final class MusicVisualizer {
    private static final String TAG = "OpenGlyphMusic";
    /** One stroke per slice; the same rate as tools/generator/visualizer.html. */
    static final long FRAME_MS = 40;

    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudio;
    private final VoiceWave mWave;
    private final Runnable mInvalidate;
    private boolean mEnabled;
    private Visualizer mVisualizer;
    private byte[] mWaveform;

    MusicVisualizer(Context context, Handler handler, VoiceWave wave, Runnable invalidate) {
        mContext = context;
        mHandler = handler;
        mAudio = context.getSystemService(AudioManager.class);
        mWave = wave;
        mInvalidate = invalidate;
    }

    void setEnabled(boolean on) {
        if (on == mEnabled) return;
        mEnabled = on;
        if (on) {
            mAudio.registerAudioPlaybackCallback(mPlayback, mHandler);
            update();
        } else {
            mAudio.unregisterAudioPlaybackCallback(mPlayback);
            stopCapture();
        }
    }

    private final AudioManager.AudioPlaybackCallback mPlayback =
            new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            update();
        }
    };

    private boolean mediaOnSpeaker() {
        boolean playing = false;
        for (AudioPlaybackConfiguration c : mAudio.getActivePlaybackConfigurations()) {
            int usage = c.getAudioAttributes().getUsage();
            if ((usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME)
                    && c.isActive()) {
                playing = true;
            }
        }
        if (!playing || mAudio.getMode() != AudioManager.MODE_NORMAL) return false;
        try {
            AudioAttributes media = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).build();
            for (AudioDeviceAttributes d : mAudio.getDevicesForAttributes(media)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return true;
            }
            return false;
        } catch (SecurityException e) {
            return !mAudio.isWiredHeadsetOn() && !mAudio.isBluetoothA2dpOn();
        }
    }

    private void update() {
        if (mEnabled && mediaOnSpeaker()) startCapture(); else stopCapture();
    }

    private void startCapture() {
        if (mVisualizer != null) return;
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.min(range[1], 1024));
            // Normalised: the wave keeps its size at any volume (8-bit samples are coarse when quiet).
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setEnabled(true);
            mVisualizer = v;
            mWaveform = new byte[v.getCaptureSize()];
            mHandler.post(mPoll);
            Log.i(TAG, "music visualisation on");
        } catch (RuntimeException e) {
            Log.w(TAG, "visualizer unavailable", e);
            mVisualizer = null;
        }
    }

    private void stopCapture() {
        mHandler.removeCallbacks(mPoll);
        if (mVisualizer != null) {
            mVisualizer.release();
            mVisualizer = null;
            Log.i(TAG, "music visualisation off");
        }
        mWave.stop();
        mInvalidate.run();
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            Visualizer v = mVisualizer;
            if (v == null) return;
            if (v.getWaveForm(mWaveform) == Visualizer.SUCCESS) {
                // 8-bit unsigned samples, 128 = silence.
                int max = 128, min = 128;
                int n = (int) Math.min(mWaveform.length,
                        (long) v.getSamplingRate() / 1000 * VoiceWave.WINDOW_MS / 1000);
                for (int i = mWaveform.length - Math.max(8, n); i < mWaveform.length; i++) {
                    int s = mWaveform[i] & 0xff;
                    if (s > max) max = s;
                    if (s < min) min = s;
                }
                mWave.push((max - 128) / 127f, (128 - min) / 128f, SystemClock.uptimeMillis());
                mInvalidate.run();
            }
            mHandler.postDelayed(this, FRAME_MS);
        }
    };

    void stop() {
        setEnabled(false);
    }
}
