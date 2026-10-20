package org.openglyph.effects;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * A voice waveform, as in Nothing's Phone (3) call shot: a one-pixel line across the matrix,
 * with jagged vertical strokes in the middle that settle into the flat line towards both ends.
 *
 * Each {@link #push} is one short slice of sound: the positive and negative peaks of its last
 * few milliseconds ({@link #WINDOW_MS}, a couple of voice periods, which keeps the strokes
 * jagged like the original; a whole slice's peak barely changes from slice to slice). The newest
 * slice is drawn in the centre column and older ones drift outwards, alternating between the
 * left and the right half: the shape is symmetric, but the two sides are not mirror copies.
 * Every lit pixel is at full brightness. An automatic gain keeps loud speech near full height
 * and lets quiet passages fall back to the line.
 */
public final class VoiceWave implements Effect {
    static final int CENTER = 12;
    /** Columns on each side of the centre (2..22 are lit). */
    static final int SPAN = 10;
    /** Rows above / below the line at full level. */
    static final int MAX_H = 7;
    /** Columns from the centre at full height; the strokes shrink to the line by {@link #TAPER_END}. */
    static final int FLAT = 5, TAPER_END = 9;
    static final long STALE_MS = 400;
    /** How much of each slice is measured (tools/generator/visualizer.html "windowMs"). */
    public static final int WINDOW_MS = 6;
    /** Peaks (0..1 of full scale) below this are silence: just the line. */
    static final float GATE = 0.015f;
    static final float GAIN_TARGET = 0.6f, GAIN_SPEED = 0.06f, GAIN_MAX = 40f;
    /** Above 1 expands: loud moments stand out as spikes instead of a solid block. */
    static final double CURVE = 1.5;

    private final int priority;
    private final float[] leftUp = new float[SPAN + 1], leftDown = new float[SPAN + 1];
    private final float[] rightUp = new float[SPAN + 1], rightDown = new float[SPAN + 1];
    private float centerUp, centerDown;
    private boolean nextLeft = true;
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
        float peak = Math.max(pos, neg);
        if (peak > GATE) {
            gain += (Math.min(GAIN_MAX, GAIN_TARGET / peak) - gain) * GAIN_SPEED;
        }
        float up = level(pos), down = level(neg);
        // The previous centre slice moves out to one side; the new one takes the centre.
        float[] u = nextLeft ? leftUp : rightUp, d = nextLeft ? leftDown : rightDown;
        System.arraycopy(u, 1, u, 2, SPAN - 1);
        System.arraycopy(d, 1, d, 2, SPAN - 1);
        u[1] = centerUp;
        d[1] = centerDown;
        centerUp = up;
        centerDown = down;
        nextLeft = !nextLeft;
        updatedMs = nowMs;
    }

    private float level(float v) {
        if (v <= GATE) return 0;
        return (float) Math.min(1, Math.pow(v * gain, CURVE));
    }

    public synchronized void stop() {
        updatedMs = Long.MIN_VALUE;
        java.util.Arrays.fill(leftUp, 0);
        java.util.Arrays.fill(leftDown, 0);
        java.util.Arrays.fill(rightUp, 0);
        java.util.Arrays.fill(rightDown, 0);
        centerUp = centerDown = 0;
    }

    @Override public int priority() { return priority; }

    @Override public boolean isActive(long now) {
        return updatedMs != Long.MIN_VALUE && now - updatedMs < STALE_MS;
    }

    /** 1 near the centre, falling to 0 (the bare line) towards the ends. */
    static double taper(int k) {
        if (k <= FLAT) return 1;
        if (k >= TAPER_END) return 0;
        double t = (k - FLAT) / (double) (TAPER_END - FLAT);
        return 0.5 + 0.5 * Math.cos(Math.PI * t);
    }

    @Override public synchronized long render(Frame f, long now) {
        for (int k = 0; k <= SPAN; k++) {
            double w = taper(k);
            if (k == 0) {
                stroke(f, CENTER, centerUp, centerDown, w);
            } else {
                stroke(f, CENTER - k, leftUp[k], leftDown[k], w);
                stroke(f, CENTER + k, rightUp[k], rightDown[k], w);
            }
        }
        return 33;
    }

    private static void stroke(Frame f, int x, float up, float down, double w) {
        f.set(x, CENTER, Frame.MAX);
        int hu = (int) Math.round(up * w * MAX_H), hd = (int) Math.round(down * w * MAX_H);
        for (int dy = 1; dy <= hu; dy++) f.set(x, CENTER - dy, Frame.MAX);
        for (int dy = 1; dy <= hd; dy++) f.set(x, CENTER + dy, Frame.MAX);
    }
}
