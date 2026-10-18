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

import org.openglyph.effects.Effects;

/**
 * Music visualisation: while media plays through the phone's own speaker, the output mix is
 * analysed (audio effect "Visualizer" on session 0) and drawn as 13 bands mirrored left and
 * right (bass in the centre column, highs at both edges), each bar growing up and down. Headphones, Bluetooth and calls leave the matrix alone.
 */
final class MusicVisualizer {
    private static final String TAG = "OpenGlyphMusic";
    // Tuned in tools/generator/visualizer.html (the same analysis, in the browser).
    private static final long FRAME_MS = 16;
    private static final double F_MIN = 200, F_MAX = 2000;
    private static final float RELEASE = 0.48f;
    private static final float GAIN_TARGET = 0.47f, GAIN_SPEED = 0.05f;
    /** Magnitude scale the browser emulation was tuned with (byteScale). */
    private static final double MAG_SCALE = 4;
    private static final int BANDS = 13;
    private static final int COLUMNS = 25;

    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudio;
    private final Effects.AudioBars mBars;
    private final Runnable mInvalidate;
    private boolean mEnabled;
    private Visualizer mVisualizer;
    private byte[] mFft;
    private final float[] mLevels = new float[BANDS];
    private final float[] mOut = new float[COLUMNS];
    private float mGain = 1f / 40;

    MusicVisualizer(Context context, Handler handler, Effects.AudioBars bars, Runnable invalidate) {
        mContext = context;
        mHandler = handler;
        mAudio = context.getSystemService(AudioManager.class);
        mBars = bars;
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
            v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            v.setEnabled(true);
            mVisualizer = v;
            mFft = new byte[v.getCaptureSize()];
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
        java.util.Arrays.fill(mLevels, 0);
        mBars.stop();
        mInvalidate.run();
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            Visualizer v = mVisualizer;
            if (v == null) return;
            if (v.getFft(mFft) == Visualizer.SUCCESS) {
                analyse(mFft, v.getSamplingRate() / 1000);
                mBars.setLevels(mOut, SystemClock.uptimeMillis());
                mInvalidate.run();
            }
            mHandler.postDelayed(this, FRAME_MS);
        }
    };

    /**
     * FFT bytes (re/im pairs) to 13 log-spaced bands, 200 Hz .. 2 kHz, with instant attack,
     * quick release and an automatic gain that follows the loudest band.
     */
    private void analyse(byte[] fft, int sampleRateHz) {
        int bins = fft.length / 2;
        double binHz = sampleRateHz / 2.0 / bins;
        float loudest = 0;
        for (int b = 0; b < BANDS; b++) {
            double f0 = F_MIN * Math.pow(F_MAX / F_MIN, b / (double) BANDS);
            double f1 = F_MIN * Math.pow(F_MAX / F_MIN, (b + 1) / (double) BANDS);
            int i0 = Math.max(1, (int) (f0 / binHz)), i1 = Math.max(i0 + 1, (int) (f1 / binHz));
            double sum = 0;
            for (int i = i0; i < i1 && i < bins; i++) {
                double re = fft[2 * i], im = fft[2 * i + 1];
                sum = Math.max(sum, Math.hypot(re, im) * MAG_SCALE);
            }
            float level = (float) Math.log10(1 + sum);
            loudest = Math.max(loudest, level);
            float target = level * mGain * 40;
            mLevels[b] = target > mLevels[b] ? target : mLevels[b] * RELEASE + target * (1 - RELEASE);
        }
        for (int x = 0; x < COLUMNS; x++) mOut[x] = Math.min(1, mLevels[Math.abs(x - COLUMNS / 2)]);
        // Gain: aim the loudest band at GAIN_TARGET of the bar height, adapting over ~a second.
        if (loudest > 0) mGain += ((GAIN_TARGET / 40 / loudest) - mGain) * GAIN_SPEED;
    }

    void stop() {
        setEnabled(false);
    }
}
