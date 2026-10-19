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

    /**
     * Clock, with faces to choose from: long press goes to the next one (the host keeps the
     * choice). Can be the always-on toy.
     *   0 seven-segment HH over MM
     *   1 stacked HH / MM / SS in square digits
     *   2 analogue: hands, hour ticks, a second dot running round the rim
     *   3 12-hour "9:41" in bold digits, AM / PM under it, blinking colon
     *   4 the hour in the middle, the minutes as dots filling the rim
     */
    public static final class Clock implements Toy {
        public static final int FACES = 5;
        private volatile int face;

        @Override public String id() { return "clock"; }

        public int face() { return face; }

        public void setFace(int face) { this.face = Math.floorMod(face, FACES); }

        @Override public void onLongPress(long now) { setFace(face + 1); }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            switch (face) {
                case 1 -> stacked(f, ctx);
                case 2 -> analogue(f, ctx);
                case 3 -> twelveHour(f, ctx);
                case 4 -> minuteDots(f, ctx);
                default -> {
                    Font.segPairCentred(f, ctx.hour, 2, ON);
                    Font.segPairCentred(f, ctx.minute, 14, ON);
                }
            }
        }

        private static void pair(Frame f, int n, int y, int value) {
            Font.squareDigit(f, n / 10, 7, y, value);
            Font.squareDigit(f, n % 10, 13, y, value);
        }

        static void stacked(Frame f, Toy.Context ctx) {
            pair(f, ctx.hour, 1, ON);
            pair(f, ctx.minute, 9, ON);
            pair(f, ctx.second, 17, ON * 2 / 5);
        }

        static void analogue(Frame f, Toy.Context ctx) {
            double sec = ctx.second + Math.floorMod(ctx.epochMs, 1000) / 1000.0;
            double min = ctx.minute + sec / 60, hr = ctx.hour % 12 + min / 60;
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                double r0 = i % 3 == 0 ? 9.6 : 10.4;
                double sx = Math.sin(a), sy = -Math.cos(a);
                hand(f, a, r0, 11.6, i % 3 == 0 ? 0.55 : 0.45, i % 3 == 0 ? ON * 3 / 5 : ON / 4);
            }
            hand(f, hr / 12 * 2 * Math.PI, -1.2, 6.2, 0.95, ON);
            hand(f, min / 60 * 2 * Math.PI, -1.6, 9.4, 0.62, ON);
            double sa = sec / 60 * 2 * Math.PI;
            double sx = Art.MID + Math.sin(sa) * 11.1, sy = Art.MID - Math.cos(sa) * 11.1;
            Art.paint(f, (x, y) -> 1.25 - Math.hypot(x - sx, y - sy) / 0.9, 3, ON);
            Art.paint(f, (x, y) -> 1.3 - Math.hypot(x - Art.MID, y - Art.MID) / 1.1, 3, ON);
        }

        /** A hand (or tick) from radius r0 to r1 at angle a, half width hw, anti-aliased. */
        static void hand(Frame f, double a, double r0, double r1, double hw, int value) {
            double dx = Math.sin(a), dy = -Math.cos(a);
            Art.paint(f, (x, y) -> {
                double px = x - Art.MID, py = y - Art.MID;
                double along = px * dx + py * dy, across = Math.abs(px * dy - py * dx);
                if (along < r0 || along > r1) return 0;
                return 1 - Art.smooth(hw - 0.35, hw + 0.35, across);
            }, 4, value);
        }

        static void twelveHour(Frame f, Toy.Context ctx) {
            int h = ctx.hour % 12 == 0 ? 12 : ctx.hour % 12;
            String hh = Integer.toString(h);
            int colonOn = ctx.second % 2 == 0 ? ON : ON / 5;
            int w = hh.length() * 6 + 2 + 12 - 1; // digits, colon, minutes
            int x = (25 - w) / 2, y = 8;
            for (char c : hh.toCharArray()) {
                Font.boldDigit(f, c - '0', x, y, ON);
                x += 6;
            }
            f.set(x, y + 2, colonOn);
            f.set(x, y + 4, colonOn);
            x += 2;
            Font.boldDigit(f, ctx.minute / 10, x, y, ON);
            Font.boldDigit(f, ctx.minute % 10, x + 6, y, ON);
            Font.textCentred(f, ctx.hour < 12 ? "AM" : "PM", 17, ON * 2 / 5);
        }

        static void minuteDots(Frame f, Toy.Context ctx) {
            Font.bigNumberCentred(f, String.format("%02d", ctx.hour), 9, ON);
            for (int m = 0; m < 60; m++) {
                double a = m * Math.PI / 30;
                int x = (int) Math.round(C + Math.sin(a) * 11), y = (int) Math.round(C - Math.cos(a) * 11);
                int v = m < ctx.minute ? ON * 3 / 5 : m == ctx.minute ? ON : m % 5 == 0 ? ON / 6 : 0;
                if (v > 0) f.max(x, y, v);
            }
        }

        @Override public long frameIntervalMs() { return face == 2 ? 100 : 1000; }

        @Override public boolean aodSupported() { return true; }
    }

    /** Battery: percentage plus a ring that fills with the level; it breathes while charging. */
    public static final class Battery implements Toy {
        @Override public String id() { return "battery"; }

        static final String[] BOLT = {"..##", ".##.", "####", ".##.", "##.."};

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            int pct = Math.max(0, Math.min(100, ctx.batteryPercent));
            String digits = Integer.toString(pct);
            if (digits.length() == 3) {
                Font.smallNumberCentred(f, digits, 10, ON);
            } else {
                int w = digits.length() * 6 - 1, x = (25 - w) / 2;
                for (char c : digits.toCharArray()) {
                    Font.boldDigit(f, c - '0', x, 9, ON);
                    x += 6;
                }
            }
            double breathe = 1;
            if (ctx.charging) {
                breathe = 0.5 + 0.5 * Math.abs(Math.sin(Math.PI * (now % 2000) / 2000.0));
                for (int r = 0; r < BOLT.length; r++)
                    for (int c = 0; c < 4; c++)
                        if (BOLT[r].charAt(c) == '#') f.max(11 + c, 2 + r, (int) (ON * breathe));
            } else {
                Font.textCentred(f, "%", 18, ON / 3);
            }
            // The ring fills with the level; its leading end glows.
            double frac = pct / 100.0;
            double fb = breathe;
            Art.paint(f, (x, y) -> {
                double d = Math.hypot(x - Art.MID, y - Art.MID);
                double band = 1 - Art.smooth(0.55, 1.05, Math.abs(d - 11.4));
                if (band <= 0) return 0;
                double a = Math.atan2(x - Art.MID, Art.MID - y);
                if (a < 0) a += 2 * Math.PI;
                double t = a / (2 * Math.PI);
                if (t > frac) return band * 0.07;
                return band * (0.45 + 0.55 * Art.smooth(frac - 0.25, frac, t)) * fb;
            }, 3, ON);
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
            // The day's path, sampled finely: a soft line, bright above the horizon.
            double[] path = new double[101];
            for (int i = 0; i <= 100; i++) {
                double h = i / 100.0 * 24;
                long at = ctx.epochMs + (long) ((h - hoursNow) * 3600000);
                path[i] = yOf(Sky.sunElevation(lat, lon, at));
            }
            Art.paint(f, (x, y) -> {
                double py = path[(int) Math.round(Math.max(0, Math.min(25, x)) / 25 * 100)];
                double near = 1 - Art.smooth(0.25, 0.85, Math.abs(y - py));
                double horizon = 1 - Art.smooth(0.2, 0.6, Math.abs(y - (HORIZON + 0.5)));
                return Math.max(near * (py <= HORIZON + 0.5 ? 0.55 : 0.14), horizon * 0.22);
            }, 3, ON);
            double el = Sky.sunElevation(lat, lon, ctx.epochMs);
            double sx = hoursNow / 24.0 * 25, sy = yOf(el);
            double glow = el > 0 ? 1 : 0.45;
            Art.paint(f, (x, y) -> {
                double d = Math.hypot(x - sx, y - sy);
                return glow * Math.max(1.4 - d / 1.6, 0.35 * (1 - Art.smooth(1.5, 3.6, d)));
            }, 3, ON);
        }

        static double yOf(double elevation) {
            return HORIZON + 0.5 - elevation / 90.0 * 12;
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
        /** Long press: count down this long on the matrix, then take the photo. */
        public static final long COUNTDOWN_MS = 3000;
        private volatile boolean captureRequested;
        private volatile long shotAt = Long.MIN_VALUE;

        @Override public String id() { return "mirror"; }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            if (ctx.camera == null) {
                circle(f, C, C, 5, DIM);
                disc(f, C, C, 2, DIM);
            } else {
                f.setGrid(ctx.camera);
            }
            long left = shotAt - now;
            if (shotAt != Long.MIN_VALUE && left > 0) {
                // Dim the picture so the digits read, then count down over it.
                f.dim(3);
                Countdown.draw(f, left, COUNTDOWN_MS);
            } else if (shotAt != Long.MIN_VALUE && -left < IRIS_MS) {
                iris(f, -left / (double) IRIS_MS);
            }
        }

        /** The shutter: the lens's aperture blades close at 0 and open again. */
        public static final long IRIS_MS = 650;

        /** How far the aperture is closed (0 open .. 1 shut) at t (0..1 of the shutter). */
        static double closed(double t) {
            if (t < 0.4) return Art.smooth(0, 1, t / 0.4);
            if (t < 0.55) return 1;
            return 1 - Art.smooth(0, 1, (t - 0.55) / 0.45);
        }

        /**
         * Six blades over the picture: a turning hexagonal opening that shrinks to nothing,
         * the blades dim with bright edges and seams.
         */
        static void iris(Frame f, double t) {
            double c = closed(t);
            double radius = 14 * (1 - c), turn = c * 0.9;
            final int ss = 4;
            for (int y = 0; y < 25; y++) {
                for (int x = 0; x < 25; x++) {
                    if (!MatrixLayout.exists(x, y)) continue;
                    double open = 0, blade = 0;
                    for (int j = 0; j < ss; j++) {
                        for (int i = 0; i < ss; i++) {
                            double px = x + (i + 0.5) / ss - Art.MID, py = y + (j + 0.5) / ss - Art.MID;
                            double r = Math.hypot(px, py), th = Math.atan2(py, px) - turn;
                            double k = Math.PI / 3, sector = ((th % k) + k) % k;
                            double apothem = radius * Math.cos(Math.PI / 6) / Math.cos(sector - Math.PI / 6);
                            if (r < apothem) { open++; continue; }
                            double edge = r - apothem < 0.9 ? 0.75 : 0;
                            // Seams between blades: curves leaving each corner of the opening.
                            double sa = th - (r - apothem) * 0.11;
                            double toSeam = Math.abs(Math.IEEEremainder(sa, Math.PI / 3)) * r;
                            double seam = toSeam < 0.55 ? 0.45 : 0;
                            blade += Math.max(0.1, Math.max(edge, seam));
                        }
                    }
                    int n = ss * ss;
                    int picture = f.get(x, y);
                    f.set(x, y, (int) Math.round(picture * open / n + ON * blade / n));
                }
            }
        }

        @Override public void onLongPress(long now) {
            captureRequested = true;
            shotAt = now + COUNTDOWN_MS;
        }

        /** True once after a long press: the host takes a photo. */
        public boolean takeCaptureRequest() {
            boolean r = captureRequested;
            captureRequested = false;
            return r;
        }

        @Override public boolean keepsScreenOn(long now) {
            return shotAt != Long.MIN_VALUE && now - shotAt < IRIS_MS;
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
            angle = angleOf(now);
            return angle;
        }

        private double angleOf(long now) {
            double t = Math.min(1, Math.max(0, (now - spinStart) / (double) SPIN_MS));
            return from + turn * (1 - Math.pow(1 - t, 3));
        }

        static final long POINT_MS = 1600;

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            double a = angleAt(now);
            if (spinning(now)) {
                // Motion blur: where it was a moment ago, fainter, while it turns fast.
                for (int k = 3; k >= 1; k--) Art.bottle(f, angleOf(now - k * 22), ON / (1 + 2 * k));
            }
            Art.bottle(f, a, ON);
            long since = spinStart == Long.MIN_VALUE ? -1 : now - spinStart - SPIN_MS;
            if (since >= 0 && since < POINT_MS) {
                // It stopped: a light pulses on the rim where the neck points.
                double dx = Math.sin(a), dy = -Math.cos(a);
                double pulse = 0.5 + 0.5 * Math.cos(since / 160.0 * Math.PI);
                Art.paint(f, (x, y) -> 1.2 - Math.hypot(x - (Art.MID + dx * 11.3), y - (Art.MID + dy * 11.3)) / 1.3,
                        3, (int) (ON * pulse));
            }
        }

        @Override public boolean keepsScreenOn(long now) { return spinning(now); }
        @Override public long frameIntervalMs() {
            return spinStart == Long.MIN_VALUE ? -1 : 33;
        }
    }

    /**
     * Rock, Paper, Scissors. Idle: the three hands take turns. Long press (or three shakes):
     * the fist bobs three times while dots count "rock, paper, scissors", then the phone's hand
     * sweeps in behind a closing ring and stays, breathing, for a few seconds.
     * Hands: bright outline, dim fill (tools/generator/rps.html shows the animation).
     */
    public static final class RockPaperScissors implements Toy {
        // The hands, built from parts (back to front) so they come out smooth: wrist, palm,
        // fingers and thumb, each with its own outline.
        static final Art.Part[] ROCK = {
            Art.box(12.5, 19.6, 4.0, 2.2, 1.0),                 // wrist
            Art.box(12.5, 14.4, 6.6, 5.0, 2.6),                 // back of the fist
            Art.box(8.3, 9.6, 1.45, 2.6, 1.3), Art.box(11.1, 9.4, 1.45, 2.7, 1.3),
            Art.box(13.9, 9.4, 1.45, 2.7, 1.3), Art.box(16.7, 9.8, 1.45, 2.4, 1.3), // knuckles
            Art.box(8.3, 13.0, 1.45, 1.5, 1.2), Art.box(11.1, 13.0, 1.45, 1.5, 1.2),
            Art.box(13.9, 13.0, 1.45, 1.5, 1.2), Art.box(16.7, 13.0, 1.45, 1.5, 1.2), // curled tips
            Art.capsule(6.6, 16.4, 14.2, 15.8, 1.5),           // thumb across
        };
        static final Art.Part[] PAPER = {
            Art.box(12.8, 21.4, 3.8, 2.2, 1.0),                 // wrist
            Art.capsule(8.2, 16.6, 4.3, 11.0, 1.35),           // thumb
            Art.capsule(9.0, 11.5, 8.3, 4.9, 1.3),             // index
            Art.capsule(11.8, 11.0, 11.6, 3.0, 1.3),           // middle
            Art.capsule(14.6, 11.0, 14.9, 4.0, 1.3),           // ring
            Art.capsule(17.0, 12.0, 17.8, 6.9, 1.15),          // little
            Art.box(12.7, 15.4, 5.5, 4.6, 2.4),                 // palm
        };
        static final Art.Part[] SCISSORS = {
            Art.box(13.0, 21.4, 3.8, 2.2, 1.0),                 // wrist
            Art.capsule(10.8, 12.5, 6.6, 3.4, 1.3),            // index
            Art.capsule(13.8, 12.5, 18.0, 3.6, 1.3),           // middle
            Art.box(13.0, 15.8, 5.2, 4.4, 2.4),                 // palm
            Art.box(14.8, 12.6, 1.4, 1.6, 1.2), Art.box(17.3, 13.4, 1.3, 1.5, 1.1), // curled
            Art.capsule(8.4, 16.6, 14.6, 15.0, 1.4),           // thumb over them
        };
        static final Art.Part[][] HANDS = {ROCK, PAPER, SCISSORS};
        static final long BOB_MS = 330, SHAKE_MS = BOB_MS * 3, REVEAL_MS = 260, SHOW_MS = 4000;
        static final long IDLE_MS = 900;
        static final int FILL = ON * 2 / 7;
        private final Random random;
        private final Shake shake = new Shake();
        private int shakes;
        private long start = Long.MIN_VALUE;
        private int pick;

        public RockPaperScissors() { this(new Random()); }

        public RockPaperScissors(Random random) { this.random = random; }

        @Override public String id() { return "rps"; }

        @Override public void onLongPress(long now) { play(now); }

        void play(long now) {
            start = now;
            pick = random.nextInt(3);
            shakes = 0;
        }

        public int pick() { return pick; }

        /** Stamps hand {@code i} shifted by dy, at {@code scale} of full brightness. */
        public static void hand(Frame f, int i, int dy, double scale) {
            Art.figure(f, HANDS[i], dy, 0.3, (int) (ON * scale));
        }

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            if (shake.feed(ctx, now) && !active(now) && ++shakes >= 3) play(now);
            long t = start == Long.MIN_VALUE ? Long.MAX_VALUE : now - start;
            if (t < SHAKE_MS) {
                long k = t / BOB_MS;
                double u = (t % BOB_MS) / (double) BOB_MS;
                hand(f, 0, (int) Math.round(-3 * Math.sin(Math.PI * u)) - 1, 1);
                for (int i = 0; i < 3; i++) f.set(10 + i * 2, 22, i <= k ? ON : FILL);
            } else if (t < SHAKE_MS + REVEAL_MS + SHOW_MS) {
                long rt = t - SHAKE_MS;
                if (rt < REVEAL_MS) {
                    // A ring closes in from the rim; the hand is there behind it.
                    double r = 12.5 * (1 - rt / (double) REVEAL_MS);
                    Frame h = new Frame();
                    hand(h, pick, 0, 1);
                    for (int y = 0; y < 25; y++) {
                        for (int x = 0; x < 25; x++) {
                            double d = Math.hypot(x - C, y - C);
                            if (Math.abs(d - r) < 1.0) f.max(x, y, ON);
                            else if (d > r) f.max(x, y, h.get(x, y));
                        }
                    }
                } else {
                    hand(f, pick, 0, 0.82 + 0.18 * Math.cos((rt - REVEAL_MS) / 1000.0 * Math.PI * 1.6));
                }
            } else {
                // Idle: the hands take turns, fading out at the end of each turn.
                long phase = Math.floorMod(now, IDLE_MS * 3);
                int i = (int) (phase / IDLE_MS);
                double u = (phase % IDLE_MS) / (double) IDLE_MS;
                hand(f, i, 0, u < 0.8 ? 1 : 1 - (u - 0.8) / 0.2 * 0.7);
            }
        }

        boolean active(long now) {
            return start != Long.MIN_VALUE && now - start < SHAKE_MS + REVEAL_MS + SHOW_MS;
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
                ball(f, 2.5 * Math.sin(t / 55.0) * (1 - t / (double) SHAKE_MS), ON);
            } else if (t < SHAKE_MS + SHOW_MS) {
                // The die surfaces in the window: a triangle, the answer on it.
                long at = t - SHAKE_MS;
                double rise = Math.min(1, at / 700.0);
                int value = (int) (ON * rise);
                int lift = (int) Math.round(3 * (1 - rise));
                Art.paint(f, (x, y) -> {
                    double yy = y - lift;
                    if (yy < 4.5 || yy > 21.5) return 0;
                    double half = (yy - 4.5) * 0.72;
                    double inside = half - Math.abs(x - Art.MID);
                    if (inside < 0) return 0;
                    return inside < 0.8 || yy > 20.7 ? 0.35 : 0.06;
                }, 3, value);
                String[] lines = wrap(answer);
                int y0 = lines.length == 1 ? 13 : 11;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    int w = Font.textWidth(line);
                    int y = y0 + i * 6 + lift;
                    if (w <= 23) Font.text(f, line, (25 - w) / 2, y, value);
                    else Font.text(f, line, (int) (23 - (at / 70) % (w + 24)), y, value);
                }
            } else {
                ball(f, 0, ON);
            }
        }

        /** The black ball: its rim, a highlight, and the white "8" window. */
        static void ball(Frame f, double dx, int value) {
            double cx = Art.MID + dx;
            Art.paint(f, (x, y) -> {
                double d = Math.hypot(x - cx, y - Art.MID);
                if (d > 11.4) return 0;
                double rim = 1 - Art.smooth(0.3, 0.9, Math.abs(d - 10.9));
                double shine = Math.max(0, 1 - Math.hypot(x - cx + 5, y - Art.MID + 6) / 2.6) * 0.5;
                double window = d < 5.6 ? (d < 4.9 ? 0.9 : 0.9 * (5.6 - d) / 0.7) : 0;
                return Math.max(Math.max(rim * 0.45, shine), Math.max(window, 0.03));
            }, 3, value);
            Font.bigDigit(f, 8, (int) Math.round(cx - 2.5), 9, 0); // the 8, cut out of the window
        }

        /** The answer in lines that fit (one word per line when it is too wide). */
        static String[] wrap(String answer) {
            if (Font.textWidth(answer) <= 23) return new String[] {answer};
            return answer.split(" ");
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

        static final String[] CARDINALS = {"N", "E", "S", "W"};

        @Override public void render(Frame f, long now, Toy.Context ctx) {
            double north = ctx.hasHeading ? Math.toRadians(-ctx.heading) : 0;
            // A bezel of ticks turning with north, the cardinal letters upright on it.
            Art.paint(f, (x, y) -> {
                double px = x - Art.MID, py = y - Art.MID, d = Math.hypot(px, py);
                if (d < 10.3 || d > 12.2) return 0;
                double a = Math.atan2(px, -py) - north;
                double k = Math.abs(Math.IEEEremainder(a, Math.PI / 6)) * d;
                return k < 0.45 ? 0.3 : 0.05;
            }, 3, ON);
            for (int i = 0; i < 4; i++) {
                double a = north + i * Math.PI / 2;
                int x = (int) Math.round(Art.MID + Math.sin(a) * 7.6 - 1.5);
                int y = (int) Math.round(Art.MID - Math.cos(a) * 7.6 - 2.5);
                Font.text(f, CARDINALS[i], x, y, i == 0 ? ON : ON / 4);
            }
            if (!ctx.hasHeading) return;
            // The needle: a slim diamond, the north half bright.
            double dx = Math.sin(north), dy = -Math.cos(north);
            Art.paint(f, (x, y) -> {
                double px = x - Art.MID, py = y - Art.MID;
                double along = px * dx + py * dy, across = Math.abs(px * dy - py * dx);
                double len = along >= 0 ? 5.2 : 4.0;
                double half = 1.25 * (1 - Math.abs(along) / len);
                if (half <= 0) return 0;
                double cover = 1 - Art.smooth(half - 0.3, half + 0.3, across);
                return cover * (along >= 0 ? 1 : 0.22);
            }, 4, ON);
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
            Art.moon(f, phase, 11.4, ON);
        }

        static void drawMoonFlat(Frame f, double phase) {
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
                ring(f, 1 - (now - start) / (double) GAME_MS, ON / 4);
                drawFood(f, foodX, foodY, now);
                drawFly(f, fx, fy, now);
            } else if (start != Long.MIN_VALUE && now - start < GAME_MS + SCORE_MS) {
                String n = Integer.toString(Math.min(score, 99));
                int w = n.length() * 6 - 1, x = (25 - w) / 2;
                for (char c : n.toCharArray()) {
                    Font.boldDigit(f, c - '0', x, 9, ON);
                    x += 6;
                }
                Font.textCentred(f, "YUM", 18, ON / 3);
            } else {
                // Idle: the fly hovers round a sugar cube.
                double a = now / 900.0;
                drawFood(f, 15, 13, now);
                drawFly(f, 11 + 3.5 * Math.cos(a), 12 + 2.5 * Math.sin(2 * a), now);
            }
        }

        /** A sugar cube that glints. */
        static void drawFood(Frame f, int x, int y, long now) {
            int glint = (now / 300) % 4 == 0 ? ON : ON * 3 / 5;
            f.max(x, y, glint);
            f.max(x + 1, y, ON * 3 / 5);
            f.max(x, y + 1, ON * 3 / 5);
            f.max(x + 1, y + 1, ON * 2 / 5);
        }

        /** The fly: a dark-bright body, eyes, and wings that beat. */
        static void drawFly(Frame f, double x, double y, long now) {
            int cx = (int) Math.round(x), cy = (int) Math.round(y);
            f.max(cx, cy, ON);                 // thorax
            f.max(cx, cy + 1, ON * 3 / 4);     // abdomen
            f.max(cx, cy + 2, ON / 2);
            f.max(cx - 1, cy - 1, ON);         // eyes
            f.max(cx + 1, cy - 1, ON);
            boolean up = (now / 60) % 2 == 0;
            int wing = up ? ON / 2 : ON / 4;
            f.max(cx - 1, cy + (up ? 0 : 1), wing);
            f.max(cx + 1, cy + (up ? 0 : 1), wing);
            f.max(cx - 2, cy + (up ? -1 : 1), wing / 2);
            f.max(cx + 2, cy + (up ? -1 : 1), wing / 2);
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
