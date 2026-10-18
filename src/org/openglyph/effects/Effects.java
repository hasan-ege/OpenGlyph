package org.openglyph.effects;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.Generative;
import org.openglyph.core.MatrixLayout;

/** Built-in effects. They only know time and state; Android wiring lives in the service. */
public final class Effects {
    private Effects() {}

    static final double CX = (MatrixLayout.WIDTH - 1) / 2.0;
    static final double CY = (MatrixLayout.HEIGHT - 1) / 2.0;

    static double dist(int x, int y) {
        return Math.hypot(x - CX, y - CY);
    }

    /** How a notification looks; chosen per app (see the service's pattern setting). */
    public enum Pattern {
        GENERATIVE, // the app's own animation from the device seed (default)
        RING,  // two outward rings
        SWEEP, // a radius sweeping round once
        DOT,   // centre dot blinking twice
        FLASH; // whole matrix flashing twice

        public static Pattern parse(String s, Pattern fallback) {
            if (s == null) return fallback;
            try {
                return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** One notification, drawn with its app's pattern, then gone. */
    public static final class NotificationPulse implements Effect {
        static final long DURATION_MS = 1600;
        private volatile long startMs = Long.MIN_VALUE;
        private volatile Pattern pattern = Pattern.RING;
        private volatile Generative.Theme theme;

        public void trigger(long nowMs) {
            trigger(nowMs, Pattern.RING);
        }

        public void trigger(long nowMs, Pattern pattern) {
            this.pattern = pattern == Pattern.GENERATIVE ? Pattern.RING : pattern;
            theme = null;
            startMs = nowMs;
        }

        /** A generative notification: the app's own animation, as long as its melody. */
        public void trigger(long nowMs, Generative.Theme theme) {
            this.pattern = Pattern.GENERATIVE;
            this.theme = theme;
            startMs = nowMs;
        }

        private long duration() {
            Generative.Theme th = theme;
            return pattern == Pattern.GENERATIVE && th != null ? th.durationMs() : DURATION_MS;
        }

        @Override public int priority() { return PRIORITY_NOTIFICATION; }

        @Override public boolean isActive(long now) {
            return startMs != Long.MIN_VALUE && now >= startMs && now - startMs < duration();
        }

        @Override public long render(Frame f, long now) {
            long t = now - startMs;
            Generative.Theme th = theme;
            if (pattern == Pattern.GENERATIVE && th != null) {
                th.render(f, t);
                return 33;
            }
            switch (pattern) {
                case SWEEP -> sweep(f, t / (double) DURATION_MS);
                case DOT -> dot(f, t);
                case FLASH -> flash(f, t);
                default -> rings(f, t);
            }
            return 33;
        }

        private static void rings(Frame f, long t) {
            double phase = (t % 800) / 800.0; // two 800 ms rings
            double radius = phase * 13.0;
            int peak = (int) (Frame.MAX * (1.0 - phase));
            for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    double d = Math.abs(dist(x, y) - radius);
                    if (d < 1.5) f.set(x, y, (int) (peak * (1.0 - d / 1.5)));
                }
            }
        }

        private static void sweep(Frame f, double phase) {
            double head = phase * 2 * Math.PI; // clockwise from 12 o'clock
            for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    if (dist(x, y) > 12.6) continue;
                    double a = Math.atan2(x - CX, CY - y);
                    if (a < 0) a += 2 * Math.PI;
                    double behind = head - a;
                    if (behind < 0) behind += 2 * Math.PI;
                    if (behind < 1.2) f.set(x, y, (int) (Frame.MAX * (1.0 - behind / 1.2)));
                }
            }
        }

        private static void dot(Frame f, long t) {
            if ((t / 400) % 2 == 1) return; // on 400 ms, off 400 ms, twice
            for (int y = 9; y <= 15; y++)
                for (int x = 9; x <= 15; x++)
                    if (dist(x, y) <= 3.2) f.set(x, y, Frame.MAX);
        }

        private static void flash(Frame f, long t) {
            double phase = (t % 800) / 800.0;
            f.fill((int) (Frame.MAX * 0.8 * Math.max(0, 1.0 - phase * 1.6)));
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

    /**
     * Music visualisation: 25 bars, one per column, mirrored about the middle row like a
     * waveform. Levels come from the speaker output; the layer goes quiet when they stop.
     */
    public static final class AudioBars implements Effect {
        static final long STALE_MS = 400;
        private final float[] levels = new float[MatrixLayout.WIDTH];
        private volatile long updatedMs = Long.MIN_VALUE;

        /** Levels 0..1, one per column (left to right as seen on the matrix). */
        public void setLevels(float[] in, long nowMs) {
            synchronized (levels) {
                for (int i = 0; i < levels.length && i < in.length; i++) {
                    levels[i] = Math.max(0, Math.min(1, in[i]));
                }
            }
            updatedMs = nowMs;
        }

        public void stop() {
            updatedMs = Long.MIN_VALUE;
        }

        @Override public int priority() { return PRIORITY_MUSIC; }

        @Override public boolean isActive(long now) {
            return updatedMs != Long.MIN_VALUE && now - updatedMs < STALE_MS;
        }

        @Override public long render(Frame f, long now) {
            synchronized (levels) {
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    int half = (MatrixLayout.columnLength(x) - 1) / 2;
                    int h = Math.round(levels[x] * half);
                    for (int dy = -h; dy <= h; dy++) {
                        // Brightest at the centre line, dimmer towards the tips.
                        double fall = h == 0 ? 1 : 1 - 0.24 * Math.abs(dy) / (double) h;
                        f.set(x, 12 + dy, (int) (Frame.MAX * fall));
                    }
                }
            }
            return 16;
        }
    }
}
