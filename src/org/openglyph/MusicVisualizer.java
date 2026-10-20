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

import org.openglyph.core.Spectrum;
import org.openglyph.effects.SpectrumBars;
import org.openglyph.effects.VoiceWave;

/**
 * Music visualisation: while media plays through the phone's own speaker, the output mix is
 * captured (audio effect "Visualizer" on session 0, normalised waveform) and drawn in one of two
 * modes: Moving ({@link VoiceWave}, every {@link #FRAME_MS} the latest slice's peaks become one
 * stroke) or Fixed ({@link SpectrumBars}, every {@link #FIXED_FRAME_MS} a Hann-windowed FFT of the
 * waveform, bands from {@link Spectrum#bands}).
 * Headphones, Bluetooth and calls leave the matrix alone (calls have their own source).
 */
final class MusicVisualizer {
    private static final String TAG = "OpenGlyphMusic";
    /** One stroke per slice; the same rate as tools/generator/visualizer.html. */
    static final long FRAME_MS = 40;
    static final long FIXED_FRAME_MS = 16;

    private final Context mContext;
    private final Handler mHandler;
    private final AudioManager mAudio;
    private final VoiceWave mWave;
    private final SpectrumBars mBars;
    private volatile boolean mFixed = true;
    private float[] mSamples, mMag;
    private final float[] mBands = new float[SpectrumBars.BANDS];
    private final Runnable mInvalidate;
    private boolean mEnabled;
    private Visualizer mVisualizer;
    private byte[] mWaveform;

    MusicVisualizer(Context context, Handler handler, VoiceWave wave, SpectrumBars bars,
            Runnable invalidate) {
        mContext = context;
        mHandler = handler;
        mAudio = context.getSystemService(AudioManager.class);
        mWave = wave;
        mBars = bars;
        mInvalidate = invalidate;
    }

    /** Fixed (frequency columns) or Moving (scrolling wave). */
    void setFixed(boolean fixed) {
        if (fixed == mFixed) return;
        mFixed = fixed;
        if (fixed) mWave.stop(); else mBars.stop();
        mInvalidate.run();
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
        mBars.stop();
        mInvalidate.run();
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            Visualizer v = mVisualizer;
            if (v == null) return;
            boolean fixed = mFixed;
            if (v.getWaveForm(mWaveform) == Visualizer.SUCCESS) {
                if (fixed) fixedFrame(v); else movingFrame(v);
                mInvalidate.run();
            }
            mHandler.postDelayed(this, fixed ? FIXED_FRAME_MS : FRAME_MS);
        }
    };

    /** The last WINDOW_MS: positive and negative peaks (8-bit unsigned, 128 = silence). */
    private void movingFrame(Visualizer v) {
        int max = 128, min = 128;
        int n = (int) Math.min(mWaveform.length,
                v.getSamplingRate() / 1000.0 * VoiceWave.WINDOW_MS / 1000);
        for (int i = mWaveform.length - Math.max(8, n); i < mWaveform.length; i++) {
            int s = mWaveform[i] & 0xff;
            if (s > max) max = s;
            if (s < min) min = s;
        }
        mWave.push((max - 128) / 127f, (128 - min) / 128f, SystemClock.uptimeMillis());
    }

    private void fixedFrame(Visualizer v) {
        int n = Integer.highestOneBit(mWaveform.length);
        if (mSamples == null || mSamples.length != n) {
            mSamples = new float[n];
            mMag = new float[n / 2 + 1];
        }
        int off = mWaveform.length - n;
        for (int i = 0; i < n; i++) mSamples[i] = ((mWaveform[off + i] & 0xff) - 128) / 128f;
        Spectrum.magnitudes(mSamples, 0, n, mMag);
        double binHz = v.getSamplingRate() / 1000.0 / n;
        Spectrum.bands(mMag, n / 2 + 1, binHz, SpectrumBars.F_MIN, SpectrumBars.F_MAX, mBands);
        mBars.push(mBands, SystemClock.uptimeMillis());
    }

    void stop() {
        setEnabled(false);
    }
}
