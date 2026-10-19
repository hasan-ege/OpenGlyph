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
     * The near side as seen from Earth (north up, east on the right), 64 x 64, 0..255: NASA's
     * LRO WAC albedo map (SVS CGI Moon Kit, public domain) projected onto the disc
     * (tools/toy-reel/moon_near_side.py). Maria, Tycho and the rest are where they really are.
     */
    static final int MOON_N = 64;
    private static final byte[] MOON = java.util.Base64.getDecoder().decode(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4+nx8PHx7+vv8Ozq5+bl5gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAOLs6uno7+/v8/n7+fHo5OXg4uPn6vMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AADm6+738Orh4eXj5ePp9e/i3d/c2t/X3+z0////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/i5erh18/H"
            + "wsPAtbHDysbL0M3R0MXI0drd8P/9/fr/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMvZ3tTMyMK3saidn6CcoJGb"
            + "qbq2sbi3vb/DxdP0+u3f4dUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAyKy9zLuyopueu7Oxpp6mtrexraijp62kpp2e"
            + "mZuqxODv6NbUvcIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAypucrZuYtLPEwcG0sLHKyZuqtdS4q6evo6SmpJyal6vFz9jP"
            + "3drV6wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAsZuVk3uAm8TGybKws5mFjZuaqbfB0MWyqavBvq6nqK6qxdLOz8nGz8zZAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAn4mIi5WescTIpZaMoH6Cf4abj4eYtrzDuqu2rLC9t6+lr7/Mz8/auL7R0NMAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAnHd7gn9/qrewqHmEfG5qd4F/hImFjpq2w8K7u8nRwLWjsLSwsLrC1MW4t8HT1gAAAAAAAAAAAAAAAAAAAAAA"
            + "pnZ0enp2jKOjsIxxb29ra3J6gIKAg4+XqrS/x8TBvriqp6S+tbGztb7Pvs29vdzgAAAAAAAAAAAAAAAAAAAAnYFvcnd3dZyh"
            + "oYN3a2ZnbHBveIF8fImTpaSqtcDMu7y6sZmWnaeporbAy8LAycnG4NcAAAAAAAAAAAAAAAAAonxtdHpxfISPjI1xdXJqZmpu"
            + "cHeBfoCKmKCfmLC+vKSQoq2fj5qcm4uiybzAxNDZyMPK2wAAAAAAAAAAAAAAvIJvc3ZvdXh7e4Zvc3p0cWtpcXV2e3hzjo2c"
            + "q5WcvKx8eniJmJZ9i6GOmK3N0sjG1drBwdLeAAAAAAAAAAAAAHl1dnNucHp3dHhzcn93b3BrbGt3cHV2b3eLmKyWlKmRcnl7"
            + "eXmEeou4pKi8xdHUyNDfx7/O2AAAAAAAAAAAAKZtb2Ztdnt9fXyCeXx5dHFpb29zcnV6cneJlKKrppKUf21xdG9zhHd4s7m2"
            + "yc3U29XP1cu8zNLgAAAAAAAAALWMiW9mand2joF4cn2Hd3Z1d3NueHN1enSLlZWZqaSWgXp5c3B0c311Z3CjvM7N1Nvg1tfH"
            + "zMnT2+QAAAAAAACLf3xraG53nYiFgHmAenNufnZybHV0mYqNnqSbkYSXqo9weHFxd3l6bmphgb3b2MvI2c/Ms8rHzdnrAAAA"
            + "AADFi4FpaWVyjK2QjHp2bG52bXh1emx6cnp8fpyko5mJoqOKaGVpb3B8d2xzZoeq09fPyLe5w726r8TN4tQAAAAAr450b2Zs"
            + "eKO1j35vb290k3V6gHR/fHN5eGx8jp6uwq+pd2BiZWxygGxlcGl6pbSzwMSst7m8nIqurtfaAAAAAKh2gGVhaHuCgod8end7"
            + "h4+EfnOIfnx2fXh6b3Gdtb6sk3tkZmpqeXttaW9rZqGunp/PvL/Em4mKfpjG1QAAAOCVaGtdYGpvd3d+hXdxcoCAgoeAeoOH"
            + "gH6JeXiPpKyhmod2dXZxZnJ1bGxuY3aWmoqPqM3U0KGEe3Bxsc++AADHj2ldWmNmaW5vfox9cHeIi5ebhomHk5OLj4mmoqmh"
            + "lIeKg255foSHb21eX3Jmc4x9coy13/S/gXdwaJjCqQAAwXtnaF1XY2Vucn6Jj2+El7KkqaWknqKjn4+GiZuWjXZ0cHWlnHOA"
            + "tZOKZ3RgYV5ucGp1rLXnxoR1bmqNwZ0Ay7GEaGZYVlxidm5/iIl2fpurq6W6uqykp5uShnlub4B5d2xthZR4kJSCX1NZWmBk"
            + "WlxiXKCyytuYd3FqqsOffsG1iWllW15jY2tyfpWZe3Obs7+uv9Ctp6SgjIV2XF5+c2ZlV29yg5aaZVZdVGVpY1dWVFRlfqvR"
            + "vIpxfL64uorTxptmYmBeX2JhdpCtpIWGlaewvOPks6Sjmo93dW+GmZ2GcGBrgKSklHNUU1ReZ1ddW1dkZIGXyMm7m6u3s8Sf"
            + "2tajbXNtaGhiZYGmvriQgImRp6uvr6CXiZeHa3OKpKGxnZeMkI+usp10WVlkZFhcW1xaXXSTiZK/xLSzqpS8tODNt3VoZmVk"
            + "Z2x+l6+pj46eoaqjopSTcn+GZk9qjbajqaq2nqeyvLejg2RwamBYX2JfZ2ZyfJeRlry8waqItM3PzcSCXV1jY25lc4iXk4OP"
            + "i5msqJ6TinJ8hm1ac32Zo7O0x7bI18e0po5xZmJeYmFpaHNsc4GQjXCLtLe2msG40NDMe15ZXmdiYWeFjICGh4COoJuvmJB3"
            + "fYZ9dHlwh42XtL/DzufZvqiwhWZjbmNrc31vZXCRhHVnY4GXpKmtrcjSwXVhYV9mZGdhcnp8eoOLjoqbmrSbgHl9e4iWkYKB"
            + "mbHH0NHf08e+ro50am5qa3a8noaAhm1ycGZhhIuarZ/E0rh8ZlxcZWBgWmlpcHp1e4iIh4mQnZCNhIqgsKiaiaa5xcrMzMnI"
            + "zcC5sJiBcHGczLyuq3tzcmdia36Znq6hwsK9o2BbZW9gXWNuYmp5ioCHf3N9iZyYkp6vs7q/sbCtusfXycPKyMvO2tXJqH13"
            + "sbaws6J8d3RpbXmCprKxoMvAjqt4W2BxZWZlY2NueHaGfH1xlpego4qTys+8vsS9vL3KycnKxsbO3fDhv5eGmrS6sa6nlnN1"
            + "b3OFhKKltrDOxXjPhGJbbWZlYWxubXl8opl1dYiVoKCHf6y7ucLHw729ytDj1MzLze711LOTmJKztsvBo4d2c253hJyorbSn"
            + "1si0yZ5yZV5qZmJocmp5i6yQaXR9iKSveXKIp7q4r7S8wsXY6NDLzMzY38+3qp+hsr3M1sCWhXBsgZjVy7i5utTIxMGxgWx2"
            + "bW5jcm1ncnmWe21ydnSMoIx0fKK4w6ipt7a3ysfGzc3q3O3j1Nq4rqSmu8LRtZ5/eo+m1sm8wsy7ysrHy6B7nnZ/epV9bWxr"
            + "gHBubXV0gJ6ifn+orrm+urW2tcq+xsnR6dXc3uXnxcSvi5Wz1baUkYiOm7S5ub/Hy8jKvdLEnZSEkJGemXdyZ3B5i5x+hZCI"
            + "knKEj4aws7y1wMS8wcjFydXM0+78486zmYaVrMu+s7OkiqWwt7S+4wDOy7q6u6+XkpSho6iCh3Brf5ivd4KBe4J0gJKXtrzB"
            + "ura+xc3U0MnExtXi6/vQo46Ol77Kv7q1rJCpobK6xgAAzMizz76tpJ6kpq6pfIl6eX2lm358iH99gm96r7bHyL/X0sbI2MXC"
            + "vtLR0+f/0qaSkoO8u6yynpqbqaCqwtoAANDOztfNuayuvsSOe2Vqhp6ZoomPgIF4bHh8lMbByMO5xcfCtcDFwcrLyNfU4Na+"
            + "pImGq7Wps42g0revsczRAAAA19rj4sSwtLipeWZmZ4Ksj5qUq4x/dHJ7cZS3zce3vLzEub3Nzc3MycHR1NbdwKm7wae9r7Oc"
            + "utW1tr3JAAAAAOnR4P/n07++qndvZ2Ntko2Alo+GdYl0cXOXpcLKwsrMvb/IztfZy8DDyt/v1sHHzL+0xsjIucnPu7nCwgAA"
            + "AADm2NH/9dbOvryQgm9raXqVjoaWiHZtb293g6HJv8Xn0cPR4d7bz8a6yuD14tPQ0Mi51tDZ28fP28u/w7sAAAAAAOPV0uPN"
            + "zd67nYZyc3eamaCAkJJ9dX13gJCx0Nbk5+XQ2+3czMHF19rb2NXd3c7FxdDc4ung4tzUu9oAAAAAAADy1snP0cjc18Oti4aM"
            + "p66Jl5+5mZqelrHA2unc6uzs5d/Vz8PLz+XYz9LP18zIy9LM3vP86eTYx8jEAAAAAAAAANbIx8vKzuLhv6qapbWcgqOjwtnG"
            + "wLXP5vDx7vPp7+je0snHzubc09vdx8bE49fN1OX//vrb3cLIAAAAAAAAAAAA0cjKxbXEzr+qta27n4ihlMLj68/Z4O/7///3"
            + "6vfm3tnc4ebg0NDS08jKx9LVz9ny/P/s3snBAAAAAAAAAAAAAOLOxb3TycbFoZu1tbS0rMXNyODo6vP//f//8+vj7N7o4NLO"
            + "zsnPzcfIxczV0dHh7/DhzMjKiwAAAAAAAAAAAAAAzMjI0cq4r7Kns8TCxMLK1dXX7O/7//////j27unf4dTR4dbLyc/Q1tHP"
            + "zdDK5uDmzM+6rAAAAAAAAAAAAAAAAADKy9TNurm/ycHFzcq+u8fZ5fL49/P5///+/PTp6Nnc4Nrb4OPayNXb1Nrh4dHCx76/"
            + "oQAAAAAAAAAAAAAAAAAAANDNzsCbwrqyxdbQzNbU2tzv8/j/8vz+9vPz8PPd3vDW2+DJyMzW3+Pk4M3Cwr2wqQAAAAAAAAAA"
            + "AAAAAAAAAAAA2MnKu7y2sLfGytDPy9fm6ff0/+/3/PP08O7p0cnMy9LPz9nf1d7z5tTFv8i+pwAAAAAAAAAAAAAAAAAAAAAA"
            + "AADJ08fMvrWtuMDP18rU2N359fr7/v/9+PLr29POzMjLzNfe5eTw38W/x8e3rAAAAAAAAAAAAAAAAAAAAAAAAAAAANPYxMO9"
            + "tbK+w9DOy9nf7/f///b/9fb76ebl3NnU1c/Z1djc1MzT086jmQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAzs/D0MqurcfOzMva"
            + "8OPy/Pv19vHw+OPe19Tf3Nva3t/e2cjFzbmepQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADPz87P1sC/ys/P2uTc9v3v6PLm"
            + "5eri19bc39rZ1OLVztrO1Mq1mgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADb5N7j5+vh0eDo5uz//P7u4uPq6+/o6NnZ"
            + "1+Da2tXW2Nu/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAN3n7fHr7+bm5e3u////8ezs5uft4+Tj4dri7+3n3NrO"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAO3p8PT57enn8fb///ry8ezv9O7q7uvt9PHq4MYAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADm7e/p7Orl7O3t7uzv8O7w7e/o6OLkAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA6ubp5+nr7O/09PTy8Ovs7gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==");

    /** Albedo 0..1 at (x, y) on the unit disc (x right, y down), bilinear. */
    static double moonAlbedo(double x, double y) {
        double u = (x + 1) / 2 * MOON_N - 0.5, v = (y + 1) / 2 * MOON_N - 0.5;
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        double fx = u - x0, fy = v - y0;
        double a = texel(x0, y0) * (1 - fx) + texel(x0 + 1, y0) * fx;
        double b = texel(x0, y0 + 1) * (1 - fx) + texel(x0 + 1, y0 + 1) * fx;
        return (a * (1 - fy) + b * fy) / 255.0;
    }

    private static int texel(int x, int y) {
        x = Math.max(0, Math.min(MOON_N - 1, x));
        y = Math.max(0, Math.min(MOON_N - 1, y));
        int v = MOON[y * MOON_N + x] & 0xFF;
        return v == 0 ? 200 : v; // just outside the disc: the limb's brightness
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
            // Contrast as in a photo of the full moon: maria near-dark, highlands bright.
            double alb = 0.1 + 0.9 * clamp((moonAlbedo(nx, ny) - 0.36) / 0.6);
            // A curve on top: the LEDs show low levels brighter than they are, which washes the
            // maria out; this keeps them visibly darker than the highlands.
            return Math.max(Math.pow(light * alb, 1.25), 0.05 * alb); // earthshine
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

    // ---------------- figures from parts ----------------

    /** Signed distance to a shape: negative inside. */
    interface Part {
        double sdf(double x, double y);
    }

    /** A rounded rectangle centred at (cx, cy) with half sizes hx, hy and corner radius r. */
    static Part box(double cx, double cy, double hx, double hy, double r) {
        return (x, y) -> {
            double qx = Math.abs(x - cx) - (hx - r), qy = Math.abs(y - cy) - (hy - r);
            double ox = Math.max(qx, 0), oy = Math.max(qy, 0);
            return Math.hypot(ox, oy) + Math.min(Math.max(qx, qy), 0) - r;
        };
    }

    /** A capsule (a thick line with round ends) from (ax, ay) to (bx, by), radius r. */
    static Part capsule(double ax, double ay, double bx, double by, double r) {
        return (x, y) -> {
            double px = x - ax, py = y - ay, vx = bx - ax, vy = by - ay;
            double t = clamp((px * vx + py * vy) / (vx * vx + vy * vy));
            return Math.hypot(px - vx * t, py - vy * t) - r;
        };
    }

    /**
     * Parts drawn back to front: each pixel takes the front-most part over it, bright on that
     * part's edge and dim inside, so overlapping parts (fingers on a palm) keep their seams.
     */
    static void figure(Frame f, Part[] backToFront, double dy, double fill, int value) {
        figure(f, backToFront, dy, fill, value, false);
    }

    /** As above; {@code turnRight}: the figure turned 90 degrees clockwise (dy still moves it up / down). */
    static void figure(Frame f, Part[] backToFront, double dy, double fill, int value,
            boolean turnRight) {
        paint(f, (x, y) -> {
            double sy = y - dy; // the shift is on screen, before turning
            double fx = turnRight ? sy : x, yy = turnRight ? 2 * MID - x : sy;
            for (int i = backToFront.length - 1; i >= 0; i--) {
                double d = backToFront[i].sdf(fx, yy);
                if (d < 0) return d > -0.9 ? 1 : fill;
            }
            return 0;
        }, 4, value);
    }
}
