package org.openglyph.toys;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * Camera countdown: the seconds left of the camera's self-timer in big digits, with a ring
 * that empties as they run out, so you can see it when taking a photo with the rear camera.
 */
public final class Countdown implements Effect {
    private volatile long endMs = Long.MIN_VALUE;
    private volatile long lengthMs;

    /** Starts (or, with seconds <= 0, cancels) a countdown of that many seconds. */
    public void start(int seconds, long nowMs) {
        if (seconds <= 0) {
            endMs = Long.MIN_VALUE;
            return;
        }
        lengthMs = seconds * 1000L;
        endMs = nowMs + lengthMs;
    }

    @Override public int priority() { return PRIORITY_NOTIFICATION + 10; }

    @Override public boolean isActive(long now) {
        return endMs != Long.MIN_VALUE && now < endMs;
    }

    @Override public long render(Frame f, long now) {
        long left = endMs - now;
        if (left <= 0) return -1;
        draw(f, left, lengthMs);
        return 33;
    }

    /** Big seconds-left digits inside an emptying ring (also used by the Mirror toy). */
    static void draw(Frame f, long leftMs, long lengthMs) {
        int secs = (int) Math.ceil(leftMs / 1000.0);
        Toys.ring(f, leftMs / (double) lengthMs, Frame.MAX);
        Font.bigNumberCentred(f, Integer.toString(secs), 9, Frame.MAX);
    }
}
