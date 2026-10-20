package org.openglyph.effects;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * Visualiser, "Moving" mode: a voice waveform, as in Nothing's Phone (3) call shot. A line across
 * the matrix with jagged strokes that are tallest in the middle and weaken column by column into
 * the line towards both ends.
 *
 * Each {@link #push} is one short slice of sound: the positive and negative peaks of its last
 * {@link #WINDOW_MS}. The newest slice is the centre column and older ones drift outwards, the
 * same on both sides (a mirror). Each column goes one way only, towards the stronger of its two
 * peaks, and is as bright as it is tall, so silence is a barely lit line.
 *
 * The constants are the "Moving" preset tuned in tools/generator/visualizer.html.
 */
public final class VoiceWave implements Effect {
    static final int CENTER = 12;
    /** Columns on each side of the centre (the whole middle row). */
    static final int SPAN = 12;
    /** Rows above / below the line at full level. */
    static final int MAX_H = 10;
    /** The strokes weaken away from the centre (cos^FADE) and meet the line at TAPER_END. */
    static final int TAPER_END = 12;
    static final double FADE = 0.5;
    static final long STALE_MS = 400;
    /** How much of each slice is measured: short windows keep the strokes jagged. */
    public static final double WINDOW_MS = 1;
    /** Input level (the simulator's three band gains, all 0.4). */
    static final float INPUT_GAIN = 0.4f;
    static final float GAIN_TARGET = 0.65f, GAIN_SPEED = 0.005f, GAIN_MAX = 3f;
    /** Above 1 expands: loud moments stand out as spikes instead of a solid block. */
    static final double CURVE = 1.5;
    /** Brightness for silence, and how brightness follows the stroke's height. */
    static final double MIN_BRIGHT = 0.01, BRIGHT_CURVE = 1;

    private final int priority;
    /** Per column distance from the centre (0 = centre): the newest slice first. */
    private final float[] up = new float[SPAN + 1], down = new float[SPAN + 1];
    private float gain = 4f;
    private volatile long updatedMs = Long.MIN_VALUE;

    public VoiceWave(int priority) {
        this.priority = priority;
    }

    /**
     * One slice of sound.
     *
     * @param pos largest positive sample, 0..1 of full scale
     * @param neg largest negative sample (as a positive number), 0..1 of full scale
     */
    public synchronized void push(float pos, float neg, long nowMs) {
        pos *= INPUT_GAIN;
        neg *= INPUT_GAIN;
        float peak = Math.max(pos, neg);
        if (peak > 0) gain += (Math.min(GAIN_MAX, GAIN_TARGET / peak) - gain) * GAIN_SPEED;
        System.arraycopy(up, 0, up, 1, SPAN);
        System.arraycopy(down, 0, down, 1, SPAN);
        up[0] = level(pos);
        down[0] = level(neg);
        updatedMs = nowMs;
    }

    private float level(float v) {
        if (v <= 0) return 0;
        return (float) Math.min(1, Math.pow(v * gain, CURVE));
    }

    public synchronized void stop() {
        updatedMs = Long.MIN_VALUE;
        java.util.Arrays.fill(up, 0);
        java.util.Arrays.fill(down, 0);
    }

    @Override public int priority() { return priority; }

    @Override public boolean isActive(long now) {
        return updatedMs != Long.MIN_VALUE && now - updatedMs < STALE_MS;
    }

    /** 1 at the centre, weakening with every column out, 0 (the bare line) from TAPER_END. */
    static double taper(int k) {
        if (k >= TAPER_END) return 0;
        return Math.pow(Math.cos(Math.PI / 2 * k / TAPER_END), FADE);
    }

    /** 0..1 brightness for a stroke at {@code level} (its height after the edge fade). */
    static double brightness(double level, double min, double curve) {
        return min + (1 - min) * Math.pow(Math.max(0, Math.min(1, level)), curve);
    }

    @Override public synchronized long render(Frame f, long now) {
        for (int k = 0; k <= SPAN; k++) {
            double w = taper(k);
            stroke(f, CENTER - k, up[k], down[k], w);
            if (k > 0) stroke(f, CENTER + k, up[k], down[k], w);
        }
        return 33;
    }

    private static void stroke(Frame f, int x, float u, float d, double w) {
        // One way per column: towards the stronger peak.
        if (u >= d) d = 0; else u = 0;
        int v = (int) Math.round(Frame.MAX * brightness(Math.max(u, d) * w, MIN_BRIGHT, BRIGHT_CURVE));
        f.set(x, CENTER, v);
        int hu = (int) Math.round(u * w * MAX_H), hd = (int) Math.round(d * w * MAX_H);
        for (int dy = 1; dy <= hu; dy++) f.set(x, CENTER - dy, v);
        for (int dy = 1; dy <= hd; dy++) f.set(x, CENTER + dy, v);
    }
}
