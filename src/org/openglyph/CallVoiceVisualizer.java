package org.openglyph;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.openglyph.core.Spectrum;
import org.openglyph.effects.SpectrumBars;
import org.openglyph.effects.VoiceWave;

/**
 * During a call, the other person's voice on the matrix (the Phone (3)'s call shot), in the same
 * two modes as the music visualiser: Moving ({@link VoiceWave}) or Fixed ({@link SpectrumBars}).
 *
 * Call audio never passes through the output mix the music visualiser listens to, so the
 * downlink is read directly (AudioSource.VOICE_DOWNLINK, falling back to VOICE_CALL), which
 * needs CAPTURE_AUDIO_OUTPUT (privileged). Samples are only measured, chunk by chunk; nothing is
 * stored or sent anywhere.
 */
final class CallVoiceVisualizer {
    private static final String TAG = "OpenGlyphCallVoice";
    private static final int RATE = 16000;
    /** 8 ms per read: Moving pushes every 5 chunks (40 ms), Fixed every 2 (16 ms). */
    private static final int CHUNK = 128;
    private static final int MOVING_EVERY = 5, FIXED_EVERY = 2;
    private static final int FFT_N = 1024;

    private final VoiceWave mWave;
    private final SpectrumBars mBars;
    private final Runnable mInvalidate;
    /** The reader thread; a thread that is no longer this one finishes on its own. */
    private volatile Thread mThread;
    private volatile boolean mFixed = true;

    CallVoiceVisualizer(VoiceWave wave, SpectrumBars bars, Runnable invalidate) {
        mWave = wave;
        mBars = bars;
        mInvalidate = invalidate;
    }

    void setFixed(boolean fixed) {
        if (fixed == mFixed) return;
        mFixed = fixed;
        if (fixed) mWave.stop(); else mBars.stop();
    }

    synchronized void setActive(boolean on) {
        if (on == (mThread != null)) return;
        if (on) {
            mThread = new Thread(this::run, "OpenGlyphCallVoice");
            mThread.start();
        } else {
            mThread.interrupt();
            mThread = null;
            mWave.stop();
            mBars.stop();
            mInvalidate.run();
        }
    }

    private static AudioRecord open() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        for (int source : new int[] {MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_CALL}) {
            try {
                AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2 * 8));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "call voice from source " + source);
                    return r;
                }
                r.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "source " + source + " unavailable: " + e);
            }
        }
        return null;
    }

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord rec = open();
        if (rec == null) {
            Log.w(TAG, "no call audio source: call voice stays off");
            return;
        }
        short[] buf = new short[CHUNK];
        float[] ring = new float[FFT_N], window = new float[FFT_N], mag = new float[FFT_N / 2 + 1];
        float[] bands = new float[SpectrumBars.BANDS];
        int ringPos = 0, chunks = 0;
        int peakSamples = Math.max(8, (int) (RATE * VoiceWave.WINDOW_MS / 1000));
        try {
            rec.startRecording();
            while (mThread == Thread.currentThread()) {
                int n = 0;
                while (n < CHUNK && mThread == Thread.currentThread()) {
                    int r = rec.read(buf, n, CHUNK - n);
                    if (r <= 0) throw new IllegalStateException("read " + r);
                    n += r;
                }
                for (int i = 0; i < n; i++) {
                    ring[ringPos] = buf[i] / 32768f;
                    ringPos = (ringPos + 1) % FFT_N;
                }
                chunks++;
                long now = SystemClock.uptimeMillis();
                if (mFixed) {
                    if (chunks % FIXED_EVERY != 0) continue;
                    for (int i = 0; i < FFT_N; i++) window[i] = ring[(ringPos + i) % FFT_N];
                    Spectrum.magnitudes(window, 0, FFT_N, mag);
                    Spectrum.bands(mag, FFT_N / 2 + 1, RATE / (double) FFT_N,
                            SpectrumBars.F_MIN, SpectrumBars.F_MAX, bands);
                    mBars.push(bands, now);
                } else {
                    if (chunks % MOVING_EVERY != 0) continue;
                    float max = 0, min = 0;
                    for (int i = 1; i <= peakSamples; i++) {
                        float s = ring[(ringPos - i + FFT_N) % FFT_N];
                        if (s > max) max = s;
                        if (s < min) min = s;
                    }
                    mWave.push(max, -min, now);
                }
                mInvalidate.run();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "call voice stopped", e);
        } finally {
            try {
                rec.stop();
            } catch (RuntimeException ignored) {
            }
            rec.release();
        }
    }
}
