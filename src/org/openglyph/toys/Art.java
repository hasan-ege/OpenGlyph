package org.openglyph.toys;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * Drawing with grey levels: shapes are described as functions over the matrix plane and
 * sampled several times per LED, so edges and small details come out anti-aliased instead of
 * stair-stepped. Coordinates are continuous: LED (x, y) covers [x, x+1) x [y, y+1), the matrix
 * centre is (12.5, 12.5).
 */
final class Art {
    private Art() {}

    static final double MID = 12.5;

    /** Brightness 0..1 at a point of the matrix plane. */
    interface Shader {
        double at(double x, double y);
    }

    /** Samples {@code s} ss x ss times per LED and lights it with the average (max-blend). */
    static void paint(Frame f, Shader s, int ss, int value) {
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                if (!MatrixLayout.exists(x, y)) continue;
                double sum = 0;
                for (int j = 0; j < ss; j++) {
                    for (int i = 0; i < ss; i++) {
                        sum += clamp(s.at(x + (i + 0.5) / ss, y + (j + 0.5) / ss));
                    }
                }
                double v = sum / (ss * ss);
                if (v > 0.02) f.max(x, y, (int) Math.round(v * value));
            }
        }
    }

    static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static double smooth(double e0, double e1, double v) {
        double t = clamp((v - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    // ---------------- the moon ----------------

    /**
     * The near side's albedo on the unit disc (x right, y down, as seen from the northern
     * hemisphere): the dark maria as soft ellipses on bright highlands, plus Tycho and
     * Copernicus. Rough, but recognisable at 25 x 25 (the "man in the moon").
     */
    private static final double[][] MARIA = { // cx, cy, rx, ry, darkness
        {-0.30, -0.45, 0.28, 0.21, 0.55}, // Imbrium
        {0.18, -0.40, 0.17, 0.16, 0.55},  // Serenitatis
        {0.36, -0.12, 0.22, 0.16, 0.55},  // Tranquillitatis
        {0.68, -0.30, 0.11, 0.10, 0.60},  // Crisium
        {0.60, 0.10, 0.13, 0.18, 0.50},   // Fecunditatis
        {0.38, 0.30, 0.09, 0.09, 0.45},   // Nectaris
        {-0.62, -0.05, 0.26, 0.45, 0.50}, // Oceanus Procellarum
        {-0.18, 0.36, 0.18, 0.13, 0.45},  // Nubium
        {-0.48, 0.36, 0.10, 0.10, 0.50},  // Humorum
        {0.00, -0.74, 0.46, 0.07, 0.35},  // Frigoris
        {0.02, -0.17, 0.09, 0.08, 0.45},  // Vaporum
        {-0.32, -0.02, 0.12, 0.10, 0.35}, // Insularum
    };

    static double moonAlbedo(double x, double y) {
        double a = 1;
        for (double[] m : MARIA) {
            double dx = (x - m[0]) / m[2], dy = (y - m[1]) / m[3];
            double d = Math.sqrt(dx * dx + dy * dy);
            a -= m[4] * (1 - smooth(0.7, 1.15, d)) * (a > 0.4 ? 1 : 0.3);
        }
        a = Math.max(0.42, a);
        // Bright young craters with a halo.
        double tycho = Math.hypot(x + 0.15, y - 0.68), cop = Math.hypot(x + 0.28, y + 0.12);
        a += 0.35 * (1 - smooth(0.0, 0.09, tycho)) + 0.25 * (1 - smooth(0.0, 0.07, cop));
        return a;
    }

    /**
     * The moon at {@code phase} (0 new, 0.25 first quarter, 0.5 full): a lit sphere with the
     * maria on it, a soft terminator and a little earthshine on the dark side.
     */
    static void moon(Frame f, double phase, double radius, int value) {
        double th = 2 * Math.PI * phase;
        double sx = Math.sin(th), sz = -Math.cos(th);
        paint(f, (px, py) -> {
            double nx = (px - MID) / radius, ny = (py - MID) / radius;
            double rr = nx * nx + ny * ny;
            if (rr > 1) return 0;
            double nz = Math.sqrt(1 - rr);
            double lit = nx * sx + nz * sz;
            // The moon is nearly as bright at the limb as at the centre (not Lambertian).
            double light = smooth(-0.06, 0.12, lit) * (0.82 + 0.18 * Math.max(0, lit));
            double alb = moonAlbedo(nx, ny);
            // A curve on top: the LEDs show low levels brighter than they are, which washes the
            // maria out; this keeps them visibly darker than the highlands.
            return Math.max(Math.pow(light * alb, 1.5), 0.05 * alb); // earthshine
        }, 3, value);
    }

    // ---------------- the bottle ----------------

    /**
     * Half width of the bottle at u along its axis (u = 0 at the spin centre, neck towards +u),
     * or -1 outside it: a rounded body, shoulders, a long neck and a lip.
     */
    static double bottleHalfWidth(double u) {
        if (u < -9.2 || u > 10.6) return -1;
        if (u < -8.2) return 3.4 - (-8.2 - u) * 0.9;            // rounded base
        if (u < 1.6) return 3.4;                                // body
        if (u < 4.6) return 3.4 - (u - 1.6) / 3.0 * 2.2;        // shoulder
        if (u < 9.6) return 1.2;                                // neck
        return 1.6;                                             // lip
    }

    /** Brightness of the bottle sprite at (u, v): bright outline, dim glass, a label, a glint. */
    static double bottle(double u, double v) {
        double hw = bottleHalfWidth(u);
        if (hw < 0) return 0;
        double av = Math.abs(v);
        if (av > hw) return 0;
        // Distance to the outline (sides, base, lip end).
        double edge = Math.min(hw - av, Math.min(u + 9.2, 10.6 - u));
        if (edge < 0.85) return 1;
        if (u > -5.6 && u < -0.6) return u > -5.0 && u < -1.2 ? 0.62 : 0.15;  // label
        if (v < -1.4 && v > -2.4 && u > -7.6 && u < 4.0) return 0.42;           // glint
        return 0.12;                                                            // glass
    }

    /** The bottle turned to {@code angle} (0 = neck up, clockwise), centred on the matrix. */
    static void bottle(Frame f, double angle, int value) {
        double ca = Math.cos(angle), sa = Math.sin(angle);
        paint(f, (x, y) -> {
            double dx = x - MID, dy = y - MID;
            double u = dx * sa - dy * ca;   // along the neck
            double v = dx * ca + dy * sa;   // across
            return bottle(u, v);
        }, 4, value);
    }
}
