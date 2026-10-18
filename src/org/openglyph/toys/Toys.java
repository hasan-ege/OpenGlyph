package org.openglyph.toys;

import java.util.List;
import java.util.Random;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * The built-in toys: our own take on the Phone (3)'s preinstalled set (same names and
 * behaviour, written from scratch). Long press is the toy's action, as on the stock phone.
 */
public final class Toys {
    private Toys() {}

    static final int ON = Frame.MAX;
    static final int DIM = Frame.MAX / 5;
    static final double C = 12; // matrix centre

    /** The built-in toys in their default Glyph Button order; "off" is always last. */
    public static List<Toy> all() {
        return List.of(new Clock(), new Battery(), new SolarPath(), new Mirror(), new Bottle(),
                new RockPaperScissors(), new Magic8(), new Stopwatch(), new Leveller(),
                new Compass(), new MoonPhase(), new FeedTheFly(), new Off());
    }

    // ---- drawing helpers ----

    /** Lights the outer ring of the matrix clockwise from 12 o'clock, for a fraction 0..1. */
    static void ring(Frame f, double fraction, int value) {
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                double d = Math.hypot(x - C, y - C);
                if (d < 10.6 || d > 12.6 || !MatrixLayout.exists(x, y)) continue;
                double angle = Math.atan2(x - C, C - y); // 0 at top, clockwise
                if (angle < 0) angle += 2 * Math.PI;
                f.max(x, y, angle / (2 * Math.PI) < fraction ? value : DIM / 3);
            }
        }
    }

    static void disc(Frame f, double cx, double cy, double r, int value) {
        for (int y = (int) Math.floor(cy - r); y <= Math.ceil(cy + r); y++)
            for (int x = (int) Math.floor(cx - r); x <= Math.ceil(cx + r); x++)
                if (Math.hypot(x - cx, y - cy) <= r) f.max(x, y, value);
    }

    static void circle(Frame f, double cx, double cy, double r, int value) {
        for (int y = 0; y < MatrixLayout.HEIGHT; y++)
            for (int x = 0; x < MatrixLayout.WIDTH; x++)
                if (Math.abs(Math.hypot(x - cx, y - cy) - r) < 0.5) f.max(x, y, value);
    }

    /** A line from (x0, y0) to (x1, y1), {@code width} pixels thick. */
    static void line(Frame f, double x0, double y0, double x1, double y1, double width,
            int value) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        int steps = Math.max(1, (int) Math.ceil(len * 3));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t;
            if (width <= 1) f.max((int) Math.round(x), (int) Math.round(y), value);
            else disc(f, x, y, width / 2, value);
        }
    }

    static void bitmap(Frame f, String[] rows, int value) {
        int w = 0;
        for (String r : rows) w = Math.max(w, r.length());
        int x0 = (25 - w) / 2, y0 = (25 - rows.length) / 2;
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < rows[y].length(); x++)
                if (rows[y].charAt(x) == '#') f.max(x0 + x, y0 + y, value);
    }

    /** Counts shakes: a jolt well above gravity, at most one every 250 ms. */
    static final class Shake {
        private long last = Long.MIN_VALUE;

        boolean feed(Toy.Context ctx, long now) {
            if (!ctx.hasGravity) return false;
            double g = Math.sqrt(ctx.gx * ctx.gx + ctx.gy * ctx.gy + ctx.gz * ctx.gz);
            if (g < 15 || (last != Long.MIN_VALUE && now - last < 250)) return false;
            last = now;
            return true;
        }
    }

    // ---- the toys ----

    /** Digital Clock: HH over MM in 7-segment digits. Can be the always-on toy. */
    public static final class Clock implements Toy {
        @Override public String id() { return "clock"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            Font.segPairCentred(f, ctx.hour, 2, ON);
            Font.segPairCentred(f, ctx.minute, 14, ON);
        }

        @Override public boolean aodSupported() { return true; }
    }

    /** Battery: percentage plus a ring that fills with the level; it breathes while charging. */
    public static final class Battery implements Toy {
        @Override public String id() { return "battery"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
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
     * Solar Path: the sun's track over today, noon in the middle and midnight at the edges,
     * above or below the horizon line, with the sun where it is now.
     */
    public static final class SolarPath implements Toy {
        static final int HORIZON = 15;

        @Override public String id() { return "solar"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            double lat = Double.isNaN(ctx.latitude) ? 41.0 : ctx.latitude;
            double lon = Double.isNaN(ctx.longitude) ? ctx.utcOffsetHours * 15 : ctx.longitude;
            double hoursNow = Sky.solarHours(lon, ctx.epochMs);
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                if (MatrixLayout.exists(x, HORIZON)) f.max(x, HORIZON, DIM);
                double h = x / 24.0 * 24;
                long at = ctx.epochMs + (long) ((h - hoursNow) * 3600000);
                int y = toY(Sky.sunElevation(lat, lon, at));
                if (MatrixLayout.exists(x, y)) f.max(x, y, y <= HORIZON ? ON / 3 : ON / 10);
            }
            double el = Sky.sunElevation(lat, lon, ctx.epochMs);
            disc(f, hoursNow / 24.0 * 24, toY(el), 1.6, el > 0 ? ON : ON / 2);
        }

        static int toY(double elevation) {
            return (int) Math.round(HORIZON - elevation / 90.0 * 12);
        }

        @Override public long frameIntervalMs() { return 60000; }
    }

    /**
     * Glyph Mirror: the rear camera on the matrix, to line up a selfie with the main camera.
     * Long press asks the host to take the picture.
     */
    public static final class Mirror implements Toy {
        private volatile boolean captureRequested;

        @Override public String id() { return "mirror"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            if (ctx.camera == null) {
                circle(f, C, C, 5, DIM);
                disc(f, C, C, 2, DIM);
                return;
            }
            f.setGrid(ctx.camera);
        }

        @Override public void onLongPress(long now) { captureRequested = true; }

        /** True once after a long press: the host takes a photo. */
        public boolean takeCaptureRequest() {
            boolean r = captureRequested;
            captureRequested = false;
            return r;
        }

        @Override public int sensors() { return SENSOR_CAMERA; }
        @Override public long timeoutMs() { return 60000; }
        @Override public long frameIntervalMs() { return 50; }
    }

    /** Spin the Bottle: long press spins it; it slows down and points at someone. */
    public static final class Bottle implements Toy {
        static final long SPIN_MS = 3200;
        private final Random random;
        private double angle = -Math.PI / 4;
        private double from, turn;
        private long spinStart = Long.MIN_VALUE;

        public Bottle() { this(new Random()); }

        Bottle(Random random) { this.random = random; }

        @Override public String id() { return "bottle"; }

        @Override public void onLongPress(long now) {
            from = angleAt(now);
            turn = 2 * Math.PI * (3 + random.nextInt(3)) + random.nextDouble() * 2 * Math.PI;
            spinStart = now;
        }

        boolean spinning(long now) {
            return spinStart != Long.MIN_VALUE && now - spinStart < SPIN_MS;
        }

        double angleAt(long now) {
            if (spinStart == Long.MIN_VALUE) return angle;
            double t = Math.min(1, (now - spinStart) / (double) SPIN_MS);
            angle = from + turn * (1 - Math.pow(1 - t, 3));
            return angle;
        }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            double a = angleAt(now);
            double dx = Math.sin(a), dy = -Math.cos(a);
            line(f, C, C, C + dx * 10.5, C + dy * 10.5, 1, ON);       // neck
            line(f, C, C, C - dx * 7, C - dy * 7, 4.2, ON * 3 / 5);   // body
            f.set((int) Math.round(C + dx * 10.5), (int) Math.round(C + dy * 10.5), ON);
        }

        @Override public boolean keepsScreenOn(long now) { return spinning(now); }
        @Override public long frameIntervalMs() { return spinStart == Long.MIN_VALUE ? -1 : 33; }
    }

    /** Rock, Paper, Scissors: long press (or three shakes) counts down and shows a hand. */
    public static final class RockPaperScissors implements Toy {
        static final String[] ROCK = {
            "...#####...", "..#######..", ".#########.", ".#########.", "###########",
            "###########", "###########", ".#########.", ".#########.", "..#######..",
            "...#####...",
        };
        static final String[] PAPER = {
            "..#.#.#.#..", "..#.#.#.#..", "..#.#.#.#..", "..#.#.#.#.#", "..#######.#",
            ".#########.", ".#########.", ".#########.", "..#######..", "...#####...",
            "...#####...",
        };
        static final String[] SCISSORS = {
            "#.......#", ".#.....#.", "..#...#..", "...#.#...", "....#....", "...###...",
            "..#####..", ".#######.", ".#######.", "..#####..",
        };
        static final long COUNTDOWN_MS = 1500, SHOW_MS = 5000;
        private final Random random;
        private final Shake shake = new Shake();
        private int shakes;
        private long start = Long.MIN_VALUE;
        private int pick;

        public RockPaperScissors() { this(new Random()); }

        RockPaperScissors(Random random) { this.random = random; }

        @Override public String id() { return "rps"; }

        @Override public void onLongPress(long now) { play(now); }

        void play(long now) {
            start = now;
            pick = random.nextInt(3);
            shakes = 0;
        }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            if (shake.feed(ctx, now) && !active(now) && ++shakes >= 3) play(now);
            if (start == Long.MIN_VALUE || now - start >= COUNTDOWN_MS + SHOW_MS) {
                Font.textCentred(f, "RPS", 10, ON);
                return;
            }
            long t = now - start;
            if (t < COUNTDOWN_MS) {
                // The fist bounces three times, like the hand before the reveal.
                double bounce = Math.abs(Math.sin(Math.PI * t / 500.0));
                Frame fist = new Frame();
                bitmap(fist, ROCK, ON * 2 / 3);
                int dy = (int) Math.round(-4 * bounce);
                for (int y = 0; y < 25; y++)
                    for (int x = 0; x < 25; x++)
                        if (fist.get(x, y) > 0) f.max(x, y + dy, fist.get(x, y));
            } else {
                bitmap(f, pick == 0 ? ROCK : pick == 1 ? PAPER : SCISSORS, ON);
            }
        }

        boolean active(long now) {
            return start != Long.MIN_VALUE && now - start < COUNTDOWN_MS + SHOW_MS;
        }

        @Override public int sensors() { return SENSOR_GRAVITY; }
        @Override public boolean keepsScreenOn(long now) { return active(now); }
        @Override public long frameIntervalMs() { return 33; }
    }

    /** Magic 8 Ball: long press or a shake, then an answer floats up. */
    public static final class Magic8 implements Toy {
        static final String[] ANSWERS = {
            "YES", "NO", "MAYBE", "LATER", "SURE", "NOPE", "ASK AGAIN", "NOT NOW", "LIKELY",
            "DOUBT IT", "OF COURSE", "NO WAY", "DEFINITELY", "UNCLEAR", "WHY NOT", "FORGET IT",
        };
        static final long SHAKE_MS = 1200, SHOW_MS = 7000;
        private final Random random;
        private final Shake shake = new Shake();
        private long start = Long.MIN_VALUE;
        private String answer = "";

        public Magic8() { this(new Random()); }

        Magic8(Random random) { this.random = random; }

        @Override public String id() { return "magic8"; }

        @Override public void onLongPress(long now) { ask(now); }

        void ask(long now) {
            start = now;
            answer = ANSWERS[random.nextInt(ANSWERS.length)];
        }

        String answer() { return answer; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            if (shake.feed(ctx, now) && !active(now)) ask(now);
            long t = start == Long.MIN_VALUE ? Long.MAX_VALUE : now - start;
            if (t < SHAKE_MS) {
                int dx = (int) Math.round(3 * Math.sin(t / 60.0));
                circle(f, C + dx, C, 9, ON / 2);
                Font.bigDigit(f, 8, 10 + dx, 9, ON);
            } else if (t < SHAKE_MS + SHOW_MS) {
                // Triangle with the answer, scrolling when it is too wide.
                for (int y = 4; y <= 8; y++) line(f, 12 - (8 - y) * 1.5, y, 12 + (8 - y) * 1.5, y, 1, ON / 6);
                int w = Font.textWidth(answer);
                int x = w <= 21 ? (25 - w) / 2
                        : (int) (23 - ((t - SHAKE_MS) / 70) % (w + 24));
                Font.text(f, answer, x, 11, ON);
            } else {
                circle(f, C, C, 9, ON / 2);
                Font.bigDigit(f, 8, 10, 9, ON);
            }
        }

        boolean active(long now) {
            return start != Long.MIN_VALUE && now - start < SHAKE_MS + SHOW_MS;
        }

        @Override public int sensors() { return SENSOR_GRAVITY; }
        @Override public boolean keepsScreenOn(long now) { return active(now); }
        @Override public long frameIntervalMs() { return 33; }
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

        @Override public boolean keepsScreenOn(long now) { return isRunning(); }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            long ms = elapsedMs(now);
            long seconds = ms / 1000;
            Font.bigNumberCentred(f, String.format("%02d", seconds % 100), 9, ON);
            ring(f, (ms % 60000) / 60000.0, isRunning() ? ON : ON / 2);
        }

        @Override public long frameIntervalMs() { return isRunning() ? 100 : -1; }
    }

    /** Leveller: a bubble that floats to the high side; centred and bright when level. */
    public static final class Leveller implements Toy {
        @Override public String id() { return "leveller"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            circle(f, C, C, 11, DIM / 2);
            circle(f, C, C, 3.5, DIM);
            if (!ctx.hasGravity) return;
            // Seen from the back the x axis is mirrored; the bubble goes uphill.
            double dx = Math.max(-8, Math.min(8, -ctx.gx * 1.6));
            double dy = Math.max(-8, Math.min(8, -ctx.gy * 1.6));
            boolean level = Math.abs(ctx.gx) < 0.35 && Math.abs(ctx.gy) < 0.35;
            disc(f, C + dx, C + dy, level ? 3.2 : 2.6, level ? ON : ON * 2 / 3);
        }

        @Override public int sensors() { return SENSOR_GRAVITY; }
        @Override public long timeoutMs() { return 60000; }
        @Override public long frameIntervalMs() { return 33; }
    }

    /** Compass: a needle pointing north, with a dot on the rim for north. */
    public static final class Compass implements Toy {
        @Override public String id() { return "compass"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            circle(f, C, C, 11, DIM / 3);
            if (!ctx.hasHeading) {
                Font.textCentred(f, "N", 10, DIM);
                return;
            }
            double a = Math.toRadians(-ctx.heading);
            double dx = Math.sin(a), dy = -Math.cos(a);
            line(f, C, C, C + dx * 9, C + dy * 9, 2, ON);
            line(f, C, C, C - dx * 6, C - dy * 6, 1, ON / 4);
            disc(f, C + dx * 11, C + dy * 11, 1, ON);
            disc(f, C, C, 1, ON / 2);
        }

        @Override public int sensors() { return SENSOR_HEADING; }
        @Override public long timeoutMs() { return 60000; }
        @Override public long frameIntervalMs() { return 50; }
    }

    /** Moon Phase: today's moon, lit part bright, the rest faint. Can be the always-on toy. */
    public static final class MoonPhase implements Toy {
        @Override public String id() { return "moon"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            drawMoon(f, Sky.moonPhase(ctx.epochMs));
        }

        static void drawMoon(Frame f, double phase) {
            double r = 10.5;
            double k = Math.cos(2 * Math.PI * phase);
            for (int y = 0; y < 25; y++) {
                for (int x = 0; x < 25; x++) {
                    double nx = (x - C) / r, ny = (y - C) / r;
                    if (nx * nx + ny * ny > 1) continue;
                    double w = Math.sqrt(1 - ny * ny);
                    double edge = w * k;
                    // Seen from the northern hemisphere the waxing moon is lit on the right.
                    boolean lit = phase < 0.5 ? nx > edge : nx < -edge;
                    f.max(x, y, lit ? ON : ON / 20);
                }
            }
        }

        @Override public boolean aodSupported() { return true; }
        @Override public long frameIntervalMs() { return 60000; }
    }

    /** Feed The Fly: long press, then tilt to eat as much as you can in 30 seconds. */
    public static final class FeedTheFly implements Toy {
        static final long GAME_MS = 30000, SCORE_MS = 6000;
        private final Random random;
        private long start = Long.MIN_VALUE;
        private long lastFrame;
        private double fx = C, fy = C;
        private int foodX, foodY, score;

        public FeedTheFly() { this(new Random()); }

        FeedTheFly(Random random) { this.random = random; }

        @Override public String id() { return "fly"; }

        @Override public void onLongPress(long now) {
            if (playing(now)) return;
            start = now;
            lastFrame = now;
            fx = fy = C;
            score = 0;
            placeFood();
        }

        int score() { return score; }

        boolean playing(long now) {
            return start != Long.MIN_VALUE && now - start < GAME_MS;
        }

        private void placeFood() {
            do {
                foodX = 3 + random.nextInt(19);
                foodY = 3 + random.nextInt(19);
            } while (Math.hypot(foodX - C, foodY - C) > 10 || Math.hypot(foodX - fx, foodY - fy) < 5);
        }

        /** Moves the fly by gravity (it rolls downhill) and eats food it reaches. */
        void step(Toy.Context ctx, long now) {
            double dt = Math.min(0.1, (now - lastFrame) / 1000.0);
            lastFrame = now;
            if (ctx.hasGravity) {
                fx += ctx.gx * 2.2 * dt;
                fy += ctx.gy * 2.2 * dt;
            }
            double d = Math.hypot(fx - C, fy - C);
            if (d > 10.5) {
                fx = C + (fx - C) * 10.5 / d;
                fy = C + (fy - C) * 10.5 / d;
            }
            if (Math.hypot(fx - foodX, fy - foodY) < 1.6) {
                score++;
                placeFood();
            }
        }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            if (playing(now)) {
                step(ctx, now);
                ring(f, 1 - (now - start) / (double) GAME_MS, ON / 3);
                f.max(foodX, foodY, (now / 150) % 2 == 0 ? ON : ON / 2);
                drawFly(f, fx, fy, now);
            } else if (start != Long.MIN_VALUE && now - start < GAME_MS + SCORE_MS) {
                Font.bigNumberCentred(f, Integer.toString(Math.min(score, 99)), 9, ON);
            } else {
                drawFly(f, C, C, now);
                f.max(17, 8, ON / 2);
            }
        }

        static void drawFly(Frame f, double x, double y, long now) {
            int cx = (int) Math.round(x), cy = (int) Math.round(y);
            f.max(cx, cy, ON);
            f.max(cx, cy + 1, ON);
            int wing = (now / 80) % 2 == 0 ? ON / 2 : ON / 4;
            f.max(cx - 1, cy - 1, wing);
            f.max(cx + 1, cy - 1, wing);
        }

        @Override public int sensors() { return SENSOR_GRAVITY; }
        @Override public boolean keepsScreenOn(long now) {
            return start != Long.MIN_VALUE && now - start < GAME_MS + SCORE_MS;
        }
        @Override public long frameIntervalMs() { return 33; }
    }

    /** Off: a dark matrix, the end of the queue. Cannot be removed or moved. */
    public static final class Off implements Toy {
        @Override public String id() { return "off"; }
        @Override public void render(Frame f, long now, Toy.Context ctx) {}
        @Override public long frameIntervalMs() { return -1; }
    }
}
