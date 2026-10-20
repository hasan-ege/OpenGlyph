package org.openglyph.effects;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * Visualiser, "Fixed" mode: every column is one frequency band. The bands most present in what
 * is playing sit in the middle and the niche ones towards the edges (the centre first, then left
 * and right by turns); the order is re-ranked every few seconds so columns do not jump about.
 * Each bar grows up and down from the middle line, weakens towards the edges, and is as bright
 * as it is tall.
 *
 * The constants are the "Fixed" preset tuned in tools/generator/visualizer.html.
 */
public final class SpectrumBars implements Effect {
    public static final int BANDS = 25;
    public static final double F_MIN = 75, F_MAX = 16000;
    static final int CENTER = 12;
    static final int MAX_H = 11;
    static final int TAPER_END = 12;
    static final double FADE = 1;
    /** Treble lift, dB per octave around F_CENTER (music has far less energy up there). */
    static final double TILT_DB = 3, F_CENTER = 760;
    static final double ATTACK = 1, RELEASE = 0.5;
    static final float GAIN_TARGET = 0.52f, GAIN_SPEED = 0.05f, GAIN_MAX = 7f;
    static final double CURVE = 1.5;
    static final double MIN_BRIGHT = 0.22, BRIGHT_CURVE = 1.15;
    /** Running presence of each band, and how often the order follows it. */
    static final double AVG = 0.995;
    static final long RANK_MS = 3000;
    static final long STALE_MS = 400;

    private final int priority;
    private final float[] level = new float[BANDS];
    private final double[] presence = new double[BANDS];
    private final double[] tilt = new double[BANDS];
    /** order[rank] = band; rank 0 is the centre column. */
    private final int[] order = new int[BANDS];
    private final int[] slot = new int[BANDS];
    private float gain = 4f;
    private long rankedMs = Long.MIN_VALUE;
    private volatile long updatedMs = Long.MIN_VALUE;

    public SpectrumBars(int priority) {
        this.priority = priority;
        for (int b = 0; b < BANDS; b++) {
            double fc = F_MIN * Math.pow(F_MAX / F_MIN, (b + 0.5) / BANDS);
            tilt[b] = Math.pow(10, TILT_DB / 20 * Math.log(fc / F_CENTER) / Math.log(2));
            order[b] = b;
        }
        // Slots: the centre, then left and right of it, one step further out each pair.
        slot[0] = CENTER;
        for (int r = 1, k = 1; r < BANDS; k++) {
            slot[r++] = CENTER - k;
            if (r < BANDS) slot[r++] = CENTER + k;
        }
    }

    /** Band peak amplitudes (0..1, {@link #BANDS} log-spaced from F_MIN to F_MAX). */
    public synchronized void push(float[] raw, long nowMs) {
        double peak = 0;
        double[] t = new double[BANDS];
        for (int b = 0; b < BANDS; b++) {
            t[b] = raw[b] * tilt[b];
            peak = Math.max(peak, t[b]);
        }
        if (peak > 0) gain += (Math.min(GAIN_MAX, GAIN_TARGET / peak) - gain) * GAIN_SPEED;
        for (int b = 0; b < BANDS; b++) {
            double v = t[b] <= 0 ? 0 : Math.min(1, Math.pow(t[b] * gain, CURVE));
            level[b] += (float) ((v - level[b]) * (v > level[b] ? ATTACK : RELEASE));
            presence[b] = AVG * presence[b] + (1 - AVG) * t[b];
        }
        if (rankedMs == Long.MIN_VALUE || nowMs - rankedMs > RANK_MS) {
            rank();
            rankedMs = nowMs;
        }
        updatedMs = nowMs;
    }

    /** Most present band first (stable for ties). */
    private void rank() {
        Integer[] idx = new Integer[BANDS];
        for (int b = 0; b < BANDS; b++) idx[b] = b;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(presence[b], presence[a]));
        for (int r = 0; r < BANDS; r++) order[r] = idx[r];
    }

    public synchronized void stop() {
        updatedMs = Long.MIN_VALUE;
        java.util.Arrays.fill(level, 0);
    }

    @Override public int priority() { return priority; }

    @Override public boolean isActive(long now) {
        return updatedMs != Long.MIN_VALUE && now - updatedMs < STALE_MS;
    }

    static double taper(int k) {
        if (k >= TAPER_END) return 0;
        return Math.pow(Math.cos(Math.PI / 2 * k / TAPER_END), FADE);
    }

    @Override public synchronized long render(Frame f, long now) {
        for (int r = 0; r < BANDS; r++) {
            int x = slot[r];
            double lv = level[order[r]] * taper(Math.abs(x - CENTER));
            int v = (int) Math.round(Frame.MAX * VoiceWave.brightness(lv, MIN_BRIGHT, BRIGHT_CURVE));
            int h = (int) Math.round(lv * MAX_H);
            for (int dy = -h; dy <= h; dy++) f.set(x, CENTER + dy, v);
        }
        return 16;
    }
}
