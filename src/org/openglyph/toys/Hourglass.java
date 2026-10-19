package org.openglyph.toys;

import java.util.Random;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * Hourglass: a falling-sand hourglass driven by the Clock app's timer. The top bulb holds the
 * sand; one grain passes the neck every (timer length / grains), falls and piles up in the
 * bottom bulb with sand rules (straight down, else diagonally). With no timer it stands full.
 *
 * The same simulation is the "hourglass" toy and, while a timer runs, an effect on its own
 * ({@link #effect()}), so the matrix shows the timer without a button press.
 */
public final class Hourglass implements Toy {
    // ---- the timer, as the notification listener reports it ----

    private static volatile long sTotalMs;      // length, 0 = no timer
    private static volatile long sEndEpochMs;   // when it reaches 0:00, while running
    private static volatile long sPausedLeftMs; // time left, while paused
    private static volatile boolean sRunning;

    /** A running timer: it ends at {@code endEpochMs}; the length grows with "+1 min". */
    public static synchronized void timerRunning(long endEpochMs, long nowEpochMs) {
        long left = Math.max(0, endEpochMs - nowEpochMs);
        if (sTotalMs == 0) {
            sTotalMs = left;
        } else {
            long before = sRunning ? Math.max(0, sEndEpochMs - nowEpochMs) : sPausedLeftMs;
            if (left > before + 1500) sTotalMs += left - before; // time was added
        }
        sTotalMs = Math.max(sTotalMs, left);
        sEndEpochMs = endEpochMs;
        sRunning = true;
    }

    public static synchronized void timerPaused(long leftMs) {
        if (sTotalMs == 0) sTotalMs = leftMs;
        sPausedLeftMs = Math.max(0, leftMs);
        sRunning = false;
    }

    public static synchronized void timerGone() {
        sTotalMs = 0;
        sRunning = false;
    }

    public static boolean hasTimer() {
        return sTotalMs > 0;
    }

    /** 0 (full top) .. 1 (all sand below). */
    static double fraction(long nowEpochMs) {
        long total = sTotalMs;
        if (total <= 0) return 0;
        long left = sRunning ? Math.max(0, sEndEpochMs - nowEpochMs) : sPausedLeftMs;
        return Math.max(0, Math.min(1, 1 - left / (double) total));
    }

    // ---- shape ----

    private static final int CX = 12, NECK = 12;
    private static final int TOP0 = 3, BOT1 = 21;
    private static final boolean[] INSIDE = new boolean[625];
    private static final boolean[] WALL = new boolean[625];
    private static final int GRAINS;

    /** Half width of the bulb at row y (the neck is the single cell (12, 12)). */
    private static int halfWidth(int y) {
        if (y == NECK) return 0;
        int d = y < NECK ? NECK - 1 - y : y - NECK - 1; // 0 next to the neck
        return 1 + d * 7 / 8;
    }

    static {
        for (int y = TOP0; y <= BOT1; y++) {
            int hw = halfWidth(y);
            for (int x = CX - hw; x <= CX + hw; x++) INSIDE[y * 25 + x] = true;
        }
        for (int y = TOP0 - 1; y <= BOT1 + 1; y++) {
            for (int x = 0; x < 25; x++) {
                if (INSIDE[y * 25 + x]) continue;
                boolean edge = false;
                for (int dy = -1; dy <= 1 && !edge; dy++) {
                    for (int dx = -1; dx <= 1 && !edge; dx++) {
                        int nx = x + dx, ny = y + dy;
                        edge = nx >= 0 && nx < 25 && ny >= 0 && ny < 25 && INSIDE[ny * 25 + nx];
                    }
                }
                WALL[y * 25 + x] = edge;
            }
        }
        // Sand: the top bulb from its bottom up to two rows below the cap.
        int n = 0;
        for (int y = TOP0 + 2; y < NECK; y++) n += 2 * halfWidth(y) + 1;
        GRAINS = n;
    }

    // ---- simulation (shared by the toy and the effect) ----

    private static final long STEP_MS = 40;
    private static final Object LOCK = new Object();
    private static final boolean[] sSand = new boolean[625];
    private static final Random sRandom = new Random();
    private static long sSimMs = Long.MIN_VALUE;
    private static long sSimTotal = -1;
    private static int sReleased;
    private static int sTokens;

    /** Lays the sand out for {@code released} grains already through: settled, no animation. */
    private static void layOut(int released) {
        java.util.Arrays.fill(sSand, false);
        int top = GRAINS - released;
        for (int y = NECK - 1; y >= TOP0 && top > 0; y--) {
            int hw = halfWidth(y);
            for (int i = 0; i <= 2 * hw && top > 0; i++) {
                int x = CX + ((i & 1) == 0 ? i / 2 : -(i + 1) / 2); // centre outwards
                sSand[y * 25 + x] = true;
                top--;
            }
        }
        sTokens = 0;
        for (int i = 0; i < released; i++) {
            sSand[(NECK + 1) * 25 + CX] = true;
            for (int k = 0; k < 40 && step(true); k++) {}
        }
        sReleased = released;
    }

    /** One sand step; {@code bottomOnly} settles the lower bulb. True if anything moved. */
    private static boolean step(boolean bottomOnly) {
        boolean moved = false;
        boolean leftFirst = sRandom.nextBoolean();
        for (int y = BOT1 - 1; y >= (bottomOnly ? NECK : TOP0); y--) {
            for (int i = 0; i < 25; i++) {
                int x = leftFirst ? i : 24 - i;
                int k = y * 25 + x;
                if (!sSand[k]) continue;
                if (y == NECK - 1 && !bottomOnly) {
                    // Entering the neck needs a token: that is the timer's pace.
                    if (x != CX || sTokens <= 0 || sSand[NECK * 25 + CX]) {
                        if (x != CX) moved |= slide(x, y);
                        continue;
                    }
                    sTokens--;
                    sSand[k] = false;
                    sSand[NECK * 25 + CX] = true;
                    moved = true;
                    continue;
                }
                moved |= fall(x, y);
            }
        }
        return moved;
    }

    private static boolean free(int x, int y) {
        return x >= 0 && x < 25 && y >= 0 && y < 25 && INSIDE[y * 25 + x] && !sSand[y * 25 + x];
    }

    private static boolean move(int x, int y, int nx, int ny) {
        sSand[y * 25 + x] = false;
        sSand[ny * 25 + nx] = true;
        return true;
    }

    private static boolean fall(int x, int y) {
        if (free(x, y + 1)) return move(x, y, x, y + 1);
        int d = sRandom.nextBoolean() ? 1 : -1;
        if (free(x + d, y + 1)) return move(x, y, x + d, y + 1);
        if (free(x - d, y + 1)) return move(x, y, x - d, y + 1);
        return false;
    }

    /** In the row above the neck: drift towards the centre, as sand funnels in. */
    private static boolean slide(int x, int y) {
        int toward = x < CX ? 1 : -1;
        if (free(x + toward, y)) return move(x, y, x + toward, y);
        return fall(x, y);
    }

    /** Advances the shared simulation to now and draws it. */
    static void draw(Frame f, long nowMs, long nowEpochMs) {
        synchronized (LOCK) {
            long total = sTotalMs;
            int target = (int) Math.floor(GRAINS * fraction(nowEpochMs));
            if (total != sSimTotal || sSimMs == Long.MIN_VALUE || target < sReleased
                    || target - sReleased > 3 || nowMs - sSimMs > 2000) {
                layOut(target);
                sSimTotal = total;
                sSimMs = nowMs;
            }
            if (target > sReleased) {
                sTokens += target - sReleased;
                sReleased = target;
            }
            int steps = 0;
            while (sSimMs + STEP_MS <= nowMs && steps++ < 25) {
                step(false);
                sSimMs += STEP_MS;
            }
            int dim = Frame.MAX / 6;
            for (int k = 0; k < 625; k++) {
                if (sSand[k]) f.set(k % 25, k / 25, Frame.MAX);
                else if (WALL[k]) f.set(k % 25, k / 25, dim);
            }
        }
    }

    // ---- the toy ----

    @Override public String id() { return "hourglass"; }

    @Override public void render(Frame frame, long nowMs, Context ctx) {
        draw(frame, nowMs, ctx.epochMs);
    }

    @Override public long frameIntervalMs() {
        return STEP_MS;
    }

    @Override public boolean keepsScreenOn(long nowMs) {
        return false;
    }

    // ---- the effect while a timer runs ----

    private static final Effect EFFECT = new Effect() {
        @Override public int priority() { return PRIORITY_TOY - 1; }

        @Override public boolean isActive(long now) {
            return hasTimer();
        }

        @Override public long render(Frame f, long now) {
            draw(f, now, System.currentTimeMillis());
            return sRunning ? STEP_MS : 1000;
        }
    };

    /** Shows the hourglass by itself while a timer exists (below toys, above Glyph Progress). */
    public static Effect effect() {
        return EFFECT;
    }
}
