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
 * Music visualisation: while media plays (speaker, headphones, USB or Bluetooth alike), the output mix is
 * captured (audio effect "Visualizer" on session 0, normalised waveform) and drawn in one of two
 * modes: Moving ({@link VoiceWave}, every {@link #FRAME_MS} the latest slice's peaks become one
 * stroke) or Fixed ({@link SpectrumBars}, every {@link #FIXED_FRAME_MS} a Hann-windowed FFT of the
 * waveform, bands from {@link Spectrum#bands}).
 *
 * The picture does not depend on the volume setting: the waveform is captured normalised and
 * scaled back to the content's own level (the measured output peak minus the media volume in
 * dB), so volume 10 and volume 100 look the same. At volume 0 or muted it stays off.
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
            mAudio.registerAudioDeviceCallback(mDevices, mHandler);
            mContext.registerReceiver(mVolumeReceiver,
                    new android.content.IntentFilter(VOLUME_CHANGED_ACTION), null, mHandler);
            update();
        } else {
            mAudio.unregisterAudioPlaybackCallback(mPlayback);
            mAudio.unregisterAudioDeviceCallback(mDevices);
            mContext.unregisterReceiver(mVolumeReceiver);
            stopCapture();
        }
    }

    /** AudioManager's (hidden) broadcast for any stream volume change. */
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";

    private final android.content.BroadcastReceiver mVolumeReceiver =
            new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            update();
        }
    };

    /** Headphones / Bluetooth coming and going change the output and its volume curve. */
    private final android.media.AudioDeviceCallback mDevices = new android.media.AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            update();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            update();
        }
    };

    /** Media volume of the current output in dB (0 = full scale, negative below). */
    private volatile float mVolumeDb;

    private final AudioManager.AudioPlaybackCallback mPlayback =
            new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            update();
        }
    };

    /** Media or game audio is playing, outside a call, at a volume above 0. */
    private boolean mediaAudible() {
        boolean playing = false;
        for (AudioPlaybackConfiguration c : mAudio.getActivePlaybackConfigurations()) {
            int usage = c.getAudioAttributes().getUsage();
            if ((usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME)
                    && c.isActive()) {
                playing = true;
            }
        }
        if (!playing || mAudio.getMode() != AudioManager.MODE_NORMAL) return false;
        int index = mAudio.getStreamVolume(AudioManager.STREAM_MUSIC);
        return index > 0 && !mAudio.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    private int outputType() {
        try {
            AudioAttributes media = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).build();
            for (AudioDeviceAttributes d : mAudio.getDevicesForAttributes(media)) return d.getType();
        } catch (RuntimeException ignored) {
        }
        return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    private void readVolume() {
        try {
            mVolumeDb = mAudio.getStreamVolumeDb(AudioManager.STREAM_MUSIC,
                    mAudio.getStreamVolume(AudioManager.STREAM_MUSIC), outputType());
        } catch (RuntimeException e) {
            mVolumeDb = 0; // unknown curve (e.g. absolute-volume Bluetooth): treat as full scale
        }
        if (Float.isNaN(mVolumeDb) || Float.isInfinite(mVolumeDb)) mVolumeDb = 0;
    }

    private void update() {
        readVolume();
        if (mEnabled && mediaAudible()) startCapture(); else stopCapture();
    }

    private void startCapture() {
        if (mVisualizer != null) return;
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.min(range[1], 1024));
            // Normalised: the wave keeps its size at any volume (8-bit samples are coarse when quiet).
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            // Peak level of the output, to scale the normalised wave back to the content's level.
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
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
                mScale = contentScale(v);
                if (fixed) fixedFrame(v); else movingFrame(v);
                mInvalidate.run();
            }
            mHandler.postDelayed(this, fixed ? FIXED_FRAME_MS : FRAME_MS);
        }
    };

    private final Visualizer.MeasurementPeakRms mPeakRms = new Visualizer.MeasurementPeakRms();
    /** Normalised samples times this = the content's own level, whatever the volume. */
    private float mScale = 1;

    /** Measured output peak (mB, after the volume) minus the volume (dB) = the content's peak. */
    private float contentScale(Visualizer v) {
        try {
            if (v.getMeasurementPeakRms(mPeakRms) != Visualizer.SUCCESS) return 1;
        } catch (RuntimeException e) {
            return 1;
        }
        double contentDb = mPeakRms.mPeak / 100.0 - mVolumeDb;
        return (float) Math.min(1, Math.pow(10, contentDb / 20));
    }

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
        mWave.push((max - 128) / 127f * mScale, (128 - min) / 128f * mScale,
                SystemClock.uptimeMillis());
    }

    private void fixedFrame(Visualizer v) {
        int n = Integer.highestOneBit(mWaveform.length);
        if (mSamples == null || mSamples.length != n) {
            mSamples = new float[n];
            mMag = new float[n / 2 + 1];
        }
        int off = mWaveform.length - n;
        for (int i = 0; i < n; i++) mSamples[i] = ((mWaveform[off + i] & 0xff) - 128) / 128f * mScale;
        Spectrum.magnitudes(mSamples, 0, n, mMag);
        double binHz = v.getSamplingRate() / 1000.0 / n;
        Spectrum.bands(mMag, n / 2 + 1, binHz, SpectrumBars.F_MIN, SpectrumBars.F_MAX, mBands);
        mBars.push(mBands, SystemClock.uptimeMillis());
    }

    void stop() {
        setEnabled(false);
    }
}
