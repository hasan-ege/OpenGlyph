package org.openglyph;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.openglyph.effects.VoiceWave;

/**
 * During a call, the other person's voice as a waveform on the matrix (the Phone (3)'s call shot).
 *
 * Call audio never passes through the output mix the music visualiser listens to, so the
 * downlink is read directly (AudioSource.VOICE_DOWNLINK, falling back to VOICE_CALL), which
 * needs CAPTURE_AUDIO_OUTPUT (privileged). Samples are only measured, slice by slice; nothing is
 * stored or sent anywhere.
 */
final class CallVoiceVisualizer {
    private static final String TAG = "OpenGlyphCallVoice";
    private static final int RATE = 16000;
    /** Samples per stroke: MusicVisualizer.FRAME_MS of audio. */
    private static final int SLICE = (int) (RATE * MusicVisualizer.FRAME_MS / 1000);

    private final VoiceWave mWave;
    private final Runnable mInvalidate;
    /** The reader thread; a thread that is no longer this one finishes on its own. */
    private volatile Thread mThread;

    CallVoiceVisualizer(VoiceWave wave, Runnable invalidate) {
        mWave = wave;
        mInvalidate = invalidate;
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
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SLICE * 2 * 4));
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
        short[] buf = new short[SLICE];
        try {
            rec.startRecording();
            while (mThread == Thread.currentThread()) {
                int n = 0;
                while (n < SLICE && mThread == Thread.currentThread()) {
                    int r = rec.read(buf, n, SLICE - n);
                    if (r <= 0) throw new IllegalStateException("read " + r);
                    n += r;
                }
                int max = 0, min = 0;
                for (int i = Math.max(0, n - RATE * VoiceWave.WINDOW_MS / 1000); i < n; i++) {
                    if (buf[i] > max) max = buf[i];
                    if (buf[i] < min) min = buf[i];
                }
                mWave.push(max / 32767f, -min / 32768f, SystemClock.uptimeMillis());
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
