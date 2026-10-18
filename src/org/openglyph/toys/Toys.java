package org.openglyph.toys;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/** The built-in toys. */
public final class Toys {
    private Toys() {}

    static final int ON = Frame.MAX;
    static final int DIM = Frame.MAX / 5;

    /** Lights the outer ring of the matrix clockwise from 12 o'clock, for a fraction 0..1. */
    static void ring(Frame f, double fraction, int value) {
        double cx = 12, cy = 12;
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                double d = Math.hypot(x - cx, y - cy);
                if (d < 10.6 || d > 12.6 || !MatrixLayout.exists(x, y)) continue;
                double angle = Math.atan2(x - cx, cy - y); // 0 at top, clockwise
                if (angle < 0) angle += 2 * Math.PI;
                f.max(x, y, angle / (2 * Math.PI) < fraction ? value : DIM / 3);
            }
        }
    }

    /** HH over MM in big digits. */
    public static final class Clock implements Toy {
        @Override public String id() { return "clock"; }

        @Override public void render(Frame f, long now, Context ctx) {
            Font.bigNumberCentred(f, String.format("%02d", ctx.hour), 4, ON);
            Font.bigNumberCentred(f, String.format("%02d", ctx.minute), 14, ON);
        }
    }

    /** Battery percentage plus a ring that fills with the level; the ring breathes while charging. */
    public static final class Battery implements Toy {
        @Override public String id() { return "battery"; }

        @Override public void render(Frame f, long now, Context ctx) {
            int pct = Math.max(0, Math.min(100, ctx.batteryPercent));
            String digits = Integer.toString(pct);
            if (digits.length() == 3) Font.smallNumberCentred(f, digits, 10, ON);
            else Font.bigNumberCentred(f, digits, 9, ON);
            int ringValue = ON;
            if (ctx.charging) {
                double phase = (now % 2000) / 2000.0;
                ringValue = (int) (ON * (0.45 + 0.55 * Math.abs(Math.sin(Math.PI * phase))));
            }
            ring(f, pct / 100.0, ringValue);
        }

        @Override public long frameIntervalMs() { return 66; }
    }

    /**
     * Stopwatch. Long press: start, then stop, then reset. Shows elapsed seconds (wrapping at
     * 100) and a ring for the current minute.
     */
    public static final class Stopwatch implements Toy {
        private long startedAt = -1;
        private long accumulated;

        @Override public String id() { return "stopwatch"; }

        public boolean isRunning() { return startedAt >= 0; }

        public long elapsedMs(long now) {
            return accumulated + (isRunning() ? now - startedAt : 0);
        }

        @Override public void onLongPress(long now) {
            if (isRunning()) {
                accumulated += now - startedAt;
                startedAt = -1;
            } else if (accumulated > 0) {
                accumulated = 0;
            } else {
                startedAt = now;
            }
        }

        @Override public boolean keepsScreenOn() { return isRunning(); }

        @Override public void render(Frame f, long now, Context ctx) {
            long ms = elapsedMs(now);
            long seconds = ms / 1000;
            Font.bigNumberCentred(f, String.format("%02d", seconds % 100), 9, ON);
            ring(f, (ms % 60000) / 60000.0, isRunning() ? ON : ON / 2);
        }

        @Override public long frameIntervalMs() { return isRunning() ? 100 : -1; }
    }
}
