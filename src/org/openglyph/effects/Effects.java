package org.openglyph.effects;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/** Built-in effects. They only know time and state; Android wiring lives in the service. */
public final class Effects {
    private Effects() {}

    static final double CX = (MatrixLayout.WIDTH - 1) / 2.0;
    static final double CY = (MatrixLayout.HEIGHT - 1) / 2.0;

    static double dist(int x, int y) {
        return Math.hypot(x - CX, y - CY);
    }

    /** Two outward rings, then gone. Triggered for each new notification. */
    public static final class NotificationPulse implements Effect {
        static final long DURATION_MS = 1600;
        private volatile long startMs = Long.MIN_VALUE;

        public void trigger(long nowMs) {
            startMs = nowMs;
        }

        @Override public int priority() { return PRIORITY_NOTIFICATION; }

        @Override public boolean isActive(long now) {
            return startMs != Long.MIN_VALUE && now >= startMs && now - startMs < DURATION_MS;
        }

        @Override public long render(Frame f, long now) {
            double t = ((now - startMs) % 800) / 800.0; // two 800 ms rings
            double radius = t * 13.0;
            int peak = (int) (Frame.MAX * (1.0 - t));
            for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    double d = Math.abs(dist(x, y) - radius);
                    if (d < 1.5) f.set(x, y, (int) (peak * (1.0 - d / 1.5)));
                }
            }
            return 33;
        }
    }

    /** Battery level as a fill from the bottom, shown for a few seconds after plugging in. */
    public static final class ChargingLevel implements Effect {
        static final long DURATION_MS = 3000;
        private volatile long startMs = Long.MIN_VALUE;
        private volatile int percent;

        public void trigger(long nowMs, int percent) {
            this.percent = Math.max(0, Math.min(100, percent));
            startMs = nowMs;
        }

        @Override public int priority() { return PRIORITY_CHARGING; }

        @Override public boolean isActive(long now) {
            return startMs != Long.MIN_VALUE && now >= startMs && now - startMs < DURATION_MS;
        }

        @Override public long render(Frame f, long now) {
            double elapsed = now - startMs;
            // Fill animates up for the first 600 ms, holds, then fades out over the last 500 ms.
            double grow = Math.min(1.0, elapsed / 600.0);
            double fade = Math.min(1.0, (DURATION_MS - elapsed) / 500.0);
            double level = MatrixLayout.HEIGHT * percent / 100.0 * grow;
            for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
                double fromBottom = MatrixLayout.HEIGHT - y;
                double cover = Math.max(0, Math.min(1, level - (fromBottom - 1)));
                if (cover <= 0) continue;
                int v = (int) (Frame.MAX * 0.6 * cover * fade);
                for (int x = 0; x < MatrixLayout.WIDTH; x++) f.set(x, y, v);
            }
            return 33;
        }
    }

    /** Rings moving outward for as long as the phone rings. */
    public static final class IncomingCall implements Effect {
        private volatile boolean ringing;
        private volatile long startMs;

        public void setRinging(boolean ringing, long nowMs) {
            if (ringing && !this.ringing) startMs = nowMs;
            this.ringing = ringing;
        }

        @Override public int priority() { return PRIORITY_CALL; }

        @Override public boolean isActive(long now) {
            return ringing;
        }

        @Override public long render(Frame f, long now) {
            double phase = ((now - startMs) % 1200) / 1200.0;
            for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    // Rings 4 LEDs apart, scrolling outward once per period.
                    double w = (dist(x, y) / 4.0 - phase) % 1.0;
                    if (w < 0) w += 1.0;
                    double v = Math.max(0, 1.0 - Math.abs(w - 0.5) * 4);
                    f.set(x, y, (int) (Frame.MAX * v));
                }
            }
            return 33;
        }
    }

    /** A picture pushed by a client app through the service API. */
    public static final class ClientFrame implements Effect {
        private final Frame frame = new Frame();
        private final int priority;
        private volatile boolean active;

        public ClientFrame(int priority) {
            this.priority = priority;
        }

        public void show(int[] grid) {
            synchronized (frame) {
                frame.setGrid(grid);
            }
            active = true;
        }

        public void clear() {
            active = false;
        }

        @Override public int priority() { return priority; }

        @Override public boolean isActive(long now) {
            return active;
        }

        @Override public long render(Frame f, long now) {
            synchronized (frame) {
                f.copyFrom(frame);
            }
            return -1;
        }
    }
}
