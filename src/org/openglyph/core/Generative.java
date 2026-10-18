package org.openglyph.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Generative notifications: an engine turns a seed (this phone's seed + the app's name) into
 * a short sound and a Glyph Matrix animation that belong together, after the idea Nothing
 * describes for the Phone (3)'s generative ringtones (engines "Diode" and "Chemical"). Our own
 * implementation, designed against frames and audio of Nothing's published video.
 *
 * This is a 1:1 port of tools/generator/generator.html (the browser generator used to tune it): the
 * same integer hash and PRNG, the same order of random draws and the same synthesis, so a
 * seed and name heard in the browser sound and look the same on the phone.
 *
 * - DIODE: circuit-like parts (bars, blocks, dot pairs) mirrored on X and Y, flashed for a few
 *   frames per hit, dark in between; each pattern has its own voice (bip, bop, blip, click,
 *   zap) playing a short melody on a seeded scale.
 * - CHEMICAL: a flowing noise texture with four-fold symmetry; the swell envelope sets how
 *   much of it lights. Sound: fizz, rising bubbles, gloops and thumps riding the swells.
 */
public final class Generative {
    private Generative() {}

    public static final int SR = 44100;
    static final int FRAME_MS = 33;

    public enum Engine {
        DIODE, CHEMICAL;

        public static Engine parse(String s) {
            try {
                return s == null ? DIODE : valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DIODE;
            }
        }

        /** The name used in the hash, as in the browser generator. */
        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Diode settings (defaults tuned by ear in the browser generator). */
    public static final class DiodeConfig {
        public double stepMin = 40, stepMax = 110, phrases = 2, notesMax = 5, gapMax = 3;
        public double pitchLow = 1580, pitchHigh = 1710;
        public double bip = 1, bop = 0.7, blip = 0.4, click = 0.1, zap = 0.1;
        public double toneLen = 30, shape = 0.15, bopDrop = 1.25, vibrato = 0.4;
        public double crush = 0.1, reverb = 0.06, volume = 0.7;
    }

    /** Chemical settings (defaults tuned by ear in the browser generator). */
    public static final class ChemicalConfig {
        public double hitsMin = 2, hitsMax = 5, spacingMin = 100, spacingMax = 250;
        public double fizz = 0, fizzTone = 800, fizzWidth = 0, grain = 0.35;
        public double bubbles = 0.75, bubbleRate = 16, bubbleLow = 100, bubbleHigh = 6000;
        public double gloop = 0, thump = 0.25, swellAttack = 70, swellRelease = 150;
        public double reverb = 0.12, volume = 0.85;
    }

    // ---------------- deterministic hash and PRNG (same as the JS) ----------------

    /** xmur3-style string hash (the unsigned 32-bit value, as an int). */
    static int hashStr(String s) {
        int h = 1779033703 ^ s.length();
        for (int i = 0; i < s.length(); i++) {
            h = (h ^ s.charAt(i)) * -862048943; // 3432918353
            h = (h << 13) | (h >>> 19);
        }
        h = (h ^ (h >>> 16)) * -2048144789;    // 2246822507
        h = (h ^ (h >>> 13)) * -1028477387;    // 3266489909
        return h ^ (h >>> 16);
    }

    /** mulberry32: doubles in [0, 1). */
    static final class Rng {
        private int a;

        Rng(int seed) {
            a = seed;
        }

        double next() {
            a = a + 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
        }

        int rint(int lo, int hi) {
            return lo + (int) Math.floor(next() * (hi - lo + 1));
        }

        int seed() {
            return (int) Math.floor(next() * 1e9);
        }
    }

    static final boolean[] EXISTS = new boolean[625];

    static {
        for (int i = 0; i < 625; i++) EXISTS[i] = MatrixLayout.exists(i % 25, i / 25);
    }

    // ---------------- DIODE ----------------

    static final class Part {
        char t; // h, v, b(lock), p(air), d(ot)
        int len = 1, w, h, gap, x, y;
    }

    static final class DiodeCfg {
        final List<Part> parts = new ArrayList<>();
        boolean grow, diag;
    }

    static Part diodePart(Rng r) {
        Part p = new Part();
        double k = r.next();
        if (k < 0.30) { p.t = 'h'; p.len = r.rint(2, 6); }
        else if (k < 0.50) { p.t = 'v'; p.len = r.rint(2, 5); }
        else if (k < 0.65) { p.t = 'b'; p.w = r.rint(2, 3); p.h = 2; }
        else if (k < 0.80) { p.t = 'p'; p.gap = r.rint(1, 2); }
        else p.t = 'd';
        return p;
    }

    static DiodeCfg diodeConfig(Rng r) {
        DiodeCfg c = new DiodeCfg();
        int n = r.rint(2, 5);
        for (int i = 0; i < n; i++) {
            Part p = diodePart(r);
            double zone = r.next();
            if (zone < 0.35) { p.x = r.rint(0, 9); p.y = r.rint(1, 3); }
            else if (zone < 0.55) { p.x = r.rint(1, 3); p.y = r.rint(0, 9); }
            else if (zone < 0.8) {
                double a = r.next() * Math.PI / 2, rad = 4 + r.next() * 5;
                p.x = (int) Math.round(rad * Math.cos(a));
                p.y = (int) Math.round(rad * Math.sin(a));
            } else {
                double a = r.next() * Math.PI / 2, rad = 10 + r.next() * 2;
                p.x = (int) Math.round(rad * Math.cos(a));
                p.y = (int) Math.round(rad * Math.sin(a));
            }
            c.parts.add(p);
        }
        c.grow = r.next() < 0.6;
        c.diag = r.next() < 0.25;
        return c;
    }

    static void drawDiodeConfig(int[] g, DiodeCfg cfg, double phase, int level) {
        for (Part p : cfg.parts) {
            int l = cfg.grow ? Math.max(1, (int) Math.ceil(p.len * Math.min(1, phase * 2.2))) : p.len;
            switch (p.t) {
                case 'h' -> { for (int i = 0; i < l; i++) put(g, cfg, p.x + i, p.y, level); }
                case 'v' -> { for (int i = 0; i < l; i++) put(g, cfg, p.x, p.y + i, level); }
                case 'b' -> {
                    for (int i = 0; i < p.w; i++) for (int j = 0; j < p.h; j++) put(g, cfg, p.x + i, p.y + j, level);
                }
                case 'p' -> { put(g, cfg, p.x, p.y, level); put(g, cfg, p.x + p.gap + 1, p.y, level); }
                default -> put(g, cfg, p.x, p.y, level);
            }
        }
    }

    private static void put(int[] g, DiodeCfg cfg, int dx, int dy, int level) {
        int[][] pts = cfg.diag
                ? new int[][] {{dx, dy}, {-dx, dy}, {dx, -dy}, {-dx, -dy},
                        {dy, dx}, {-dy, dx}, {dy, -dx}, {-dy, -dx}}
                : new int[][] {{dx, dy}, {-dx, dy}, {dx, -dy}, {-dx, -dy}};
        for (int[] q : pts) {
            int gx = 12 + q[0], gy = 12 + q[1];
            if (gx >= 0 && gx < 25 && gy >= 0 && gy < 25 && EXISTS[gy * 25 + gx]) {
                g[gy * 25 + gx] = Math.max(g[gy * 25 + gx], level);
            }
        }
    }

    // ---------------- CHEMICAL ----------------

    static int ihash(int x, int y, int z, int s) {
        int h = (x * 374761393) ^ (y * 668265263) ^ (z * 1274126177) ^ s;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    static final int[][] G3 = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1},
        {1, 0, -1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
    };

    static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    static double grad(int xi, int yi, int zi, int s, double xf, double yf, double zf) {
        int[] d = G3[Integer.remainderUnsigned(ihash(xi, yi, zi, s), 12)];
        return d[0] * xf + d[1] * yf + d[2] * zf;
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double noise3(double x, double y, double z, int s) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        double u = fade(xf), v = fade(yf), w = fade(zf);
        return lerp(
                lerp(lerp(grad(xi, yi, zi, s, xf, yf, zf), grad(xi + 1, yi, zi, s, xf - 1, yf, zf), u),
                        lerp(grad(xi, yi + 1, zi, s, xf, yf - 1, zf),
                                grad(xi + 1, yi + 1, zi, s, xf - 1, yf - 1, zf), u), v),
                lerp(lerp(grad(xi, yi, zi + 1, s, xf, yf, zf - 1),
                                grad(xi + 1, yi, zi + 1, s, xf - 1, yf, zf - 1), u),
                        lerp(grad(xi, yi + 1, zi + 1, s, xf, yf - 1, zf - 1),
                                grad(xi + 1, yi + 1, zi + 1, s, xf - 1, yf - 1, zf - 1), u), v),
                w);
    }

    static final class ChemField {
        final int s;
        final boolean ridge, diag;
        final double scale, sym, oct2;

        ChemField(Rng r) {
            s = r.seed();
            ridge = r.next() < 0.3;
            scale = ridge ? 0.12 + r.next() * 0.05 : 0.18 + r.next() * 0.1;
            sym = 0.6 + r.next() * 0.3;
            diag = r.next() < 0.4;
            oct2 = 0.35 + r.next() * 0.3;
        }

        double raw(double x, double y, double z) {
            return noise3(x * scale, y * scale, z, s)
                    + oct2 * noise3(x * scale * 2.1, y * scale * 2.1, z * 1.7, s ^ 0x5bd1e995);
        }

        double value(int x, int y, double z) {
            int px = x - 12, py = y - 12;
            double a = (raw(px, py, z) + raw(-py, px, z) + raw(-px, -py, z) + raw(py, -px, z)) / 4;
            if (diag) a = (a + (raw(py, px, z) + raw(-px, py, z) + raw(-py, -px, z) + raw(px, -py, z)) / 4) / 2;
            double n = sym * a + (1 - sym) * raw(px, py, z + 7.3);
            return ridge ? 1 - Math.abs(n) * 2 : n;
        }
    }

    // ---------------- the score ----------------

    static final int[][] SCALES = {
        {0, 2, 4, 7, 9}, {0, 3, 5, 7, 10}, {0, 2, 4, 6, 9}, {0, 2, 3, 7, 8}, {0, 4, 7, 9, 11},
        {0, 5, 7, 10, 12},
    };

    static double[] snapToScale(Rng r, double low, double high) {
        int[] scale = SCALES[(int) Math.floor(r.next() * SCALES.length)];
        double root = low * Math.pow(2, r.rint(0, 11) / 12.0);
        List<Double> out = new ArrayList<>();
        for (int oct = -1; oct < 5; oct++) {
            for (int st : scale) {
                double f = root * Math.pow(2, oct + st / 12.0);
                if (f >= low && f <= high) out.add(f);
            }
        }
        if (out.isEmpty()) return new double[] {low};
        double[] a = new double[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static String weighted(Rng r, String[] keys, double[] weights) {
        double tot = 0;
        for (double w : weights) tot += Math.max(0, w);
        double x = r.next() * tot;
        for (int i = 0; i < keys.length; i++) {
            x -= Math.max(0, weights[i]);
            if (x <= 0) return keys[i];
        }
        return keys[0];
    }

    /** One hit: drives a flash (diode) or a swell (chemical) and a voice. */
    public static final class Hit {
        public final int t;
        int cfg, hold;
        String voice;
        double f, vel;
        int seed;

        Hit(int t) {
            this.t = t;
        }
    }

    public static Theme theme(String deviceSeed, String name, Engine engine) {
        return new Theme(deviceSeed, name, engine, new DiodeConfig(), new ChemicalConfig());
    }

    /** One app's sound and light. */
    public static final class Theme {
        public final Engine engine;
        public final List<Hit> hits = new ArrayList<>();
        public final int duration;
        final DiodeConfig dc;
        final ChemicalConfig cc;
        final List<DiodeCfg> pool = new ArrayList<>();
        ChemField field;
        int bubbleSeed;
        double[] bubbleNotes;
        private final List<int[]> frames;

        public Theme(String deviceSeed, String name, Engine engine, DiodeConfig dc, ChemicalConfig cc) {
            this.engine = engine;
            this.dc = dc;
            this.cc = cc;
            Rng r = new Rng(hashStr(deviceSeed + "|" + name + "|" + engine.key()));
            if (engine == Engine.DIODE) {
                int step = r.rint((int) Math.min(dc.stepMin, dc.stepMax), (int) Math.max(dc.stepMin, dc.stepMax));
                int nCfg = r.rint(3, 5);
                for (int i = 0; i < nCfg; i++) pool.add(diodeConfig(r));
                double[] notes = snapToScale(r, Math.min(dc.pitchLow, dc.pitchHigh),
                        Math.max(dc.pitchLow, dc.pitchHigh));
                String[] keys = {"bip", "bop", "blip", "click", "zap"};
                double[] weights = {dc.bip, dc.bop, dc.blip, dc.click, dc.zap};
                String[] voices = new String[nCfg];
                for (int i = 0; i < nCfg; i++) voices[i] = weighted(r, keys, weights);
                double[] melody = new double[16];
                int idx = r.rint(0, notes.length - 1);
                for (int i = 0; i < 16; i++) {
                    melody[i] = notes[idx];
                    idx = Math.max(0, Math.min(notes.length - 1, idx + r.rint(-2, 2)));
                }
                int[] order = new int[8];
                for (int i = 0; i < 8; i++) order[i] = r.rint(0, nCfg - 1);
                int s = 0, k = 0;
                int phrases = Math.max(1, (int) Math.round(dc.phrases));
                for (int p = 0; p < phrases; p++) {
                    int len = r.rint(2, Math.max(2, (int) Math.round(dc.notesMax)));
                    for (int i = 0; i < len; i++) {
                        int cfg = order[(p + i) % order.length];
                        Hit h = new Hit(s * step);
                        h.cfg = cfg;
                        h.voice = voices[cfg];
                        h.f = melody[k++ % melody.length];
                        h.vel = i == 0 ? 1 : 0.65 + 0.3 * r.next();
                        h.hold = r.rint(2, 4);
                        h.seed = r.seed();
                        hits.add(h);
                        s += r.next() < 0.25 ? 2 : 1;
                    }
                    s += r.rint(1, Math.max(1, (int) Math.round(dc.gapMax)));
                }
                duration = s * step + 500;
            } else {
                field = new ChemField(r);
                int t = 0;
                int n = r.rint((int) Math.min(cc.hitsMin, cc.hitsMax), (int) Math.max(cc.hitsMin, cc.hitsMax));
                String[] keys = {"gloop", "thump", "none"};
                double[] weights = {cc.gloop, cc.thump, Math.max(0, 1 - cc.gloop - cc.thump)};
                for (int i = 0; i < n; i++) {
                    Hit h = new Hit(t);
                    h.vel = i == 0 ? 1 : 0.55 + 0.45 * r.next();
                    h.voice = weighted(r, keys, weights);
                    h.seed = r.seed();
                    hits.add(h);
                    t += r.rint((int) Math.min(cc.spacingMin, cc.spacingMax),
                            (int) Math.max(cc.spacingMin, cc.spacingMax));
                }
                duration = t + 700;
                bubbleSeed = r.seed();
                bubbleNotes = snapToScale(r, Math.min(cc.bubbleLow, cc.bubbleHigh),
                        Math.max(cc.bubbleLow, cc.bubbleHigh));
            }
            frames = buildFrames();
        }

        public long durationMs() {
            return duration;
        }

        /** Swell envelope (chemical), shared by the light and the fizz. */
        double env(double tm) {
            double e = 0;
            for (Hit h : hits) {
                double a = tm - h.t;
                if (a < 0) continue;
                e += h.vel * (a < cc.swellAttack ? a / cc.swellAttack
                        : Math.exp(-(a - cc.swellAttack) / cc.swellRelease));
            }
            return Math.min(1, e);
        }

        // ---------------- light: 30 fps, levels 0..7 ----------------

        private List<int[]> buildFrames() {
            List<int[]> out = new ArrayList<>();
            int nF = (int) Math.ceil(duration / (double) FRAME_MS);
            if (engine == Engine.DIODE) {
                for (int f = 0; f < nF; f++) {
                    int t = f * FRAME_MS;
                    int[] g = new int[625];
                    for (Hit h : hits) {
                        int age = t - h.t, len = h.hold * FRAME_MS;
                        if (age < 0 || age >= len) continue;
                        int level = age < len - FRAME_MS ? 7 : 3;
                        drawDiodeConfig(g, pool.get(h.cfg), age / (double) len, level);
                    }
                    out.add(g);
                }
            } else {
                double z = 0;
                float[] v = new float[625];
                for (int f = 0; f < nF; f++) {
                    int t = f * FRAME_MS;
                    z += 0.012 + 0.05 * env(t);
                    for (int k = 0; k < 625; k++) if (EXISTS[k]) v[k] = (float) field.value(k % 25, k / 25, z);
                    int[] g = new int[625];
                    double e = env(t);
                    int want = (int) Math.round(e * 0.45 * 489);
                    if (want > 2) {
                        float[] sorted = new float[489];
                        int m = 0;
                        for (int k = 0; k < 625; k++) if (EXISTS[k]) sorted[m++] = v[k];
                        Arrays.sort(sorted, 0, m); // ascending; ranks count from the top
                        float thr = Math.max(sorted[m - 1 - Math.min(m - 1, want)], 1e-4f);
                        float core = sorted[m - 1 - (int) Math.floor(want * 0.4)];
                        for (int k = 0; k < 625; k++) if (EXISTS[k] && v[k] > thr) g[k] = v[k] >= core ? 7 : 4;
                    }
                    out.add(g);
                }
            }
            return out;
        }

        /** Draws the frame at {@code t} ms after the notification. */
        public void render(Frame fr, long t) {
            int i = (int) (t / FRAME_MS);
            if (t < 0 || i >= frames.size()) return;
            int[] g = frames.get(i);
            for (int k = 0; k < 625; k++) {
                if (g[k] > 0) fr.max(k % 25, k / 25, Frame.MAX * g[k] / 7);
            }
        }

        int frameCount() {
            return frames.size();
        }

        int litAt(int i) {
            int n = 0;
            for (int v : frames.get(i)) if (v > 0) n++;
            return n;
        }

        // ---------------- sound ----------------

        static double osc(double ph, double shape) {
            double sn = Math.sin(2 * Math.PI * ph);
            if (shape <= 0) return sn;
            double sq = Math.tanh(sn * (1 + shape * 12)) / Math.tanh(1 + shape * 12);
            return sn * (1 - shape) + sq * shape;
        }

        float[] diodeVoice(Hit h) {
            double len = dc.toneLen / 1000;
            int n = (int) Math.floor((len + 0.06) * SR);
            float[] o = new float[n];
            Rng nr = new Rng(h.seed);
            double ph = 0, y1 = 0, y2 = 0;
            for (int i = 0; i < n; i++) {
                double t = i / (double) SR, u = Math.min(1, t / len), v = 0;
                double vib = 1 + dc.vibrato * 0.03 * Math.sin(2 * Math.PI * 7 * t);
                switch (h.voice) {
                    case "bip" -> {
                        ph += h.f * vib / SR;
                        double env = t < 0.004 ? t / 0.004
                                : (t < len ? 1 - 0.3 * u : Math.exp(-(t - len) / 0.008) * 0.7);
                        v = osc(ph, dc.shape) * env * 0.5;
                    }
                    case "bop" -> {
                        double f = h.f * 0.5 * Math.pow(2, -dc.bopDrop * u);
                        ph += f * vib / SR;
                        double env = t < 0.004 ? t / 0.004 : (t < len ? 1 : Math.exp(-(t - len) / 0.012));
                        v = osc(ph, dc.shape * 0.7) * env * 0.6;
                    }
                    case "blip" -> {
                        ph += h.f * 2 / SR;
                        v = osc(ph, dc.shape) * Math.exp(-t / 0.012) * 0.45;
                    }
                    case "click" -> {
                        double x = i < SR * 0.003 ? (nr.next() * 2 - 1) : 0;
                        double w = 2 * Math.PI * Math.min(h.f * 3, SR * 0.45) / SR, rr = 0.95;
                        double y = x + 2 * rr * Math.cos(w) * y1 - rr * rr * y2;
                        y2 = y1;
                        y1 = y;
                        v = y * 0.3 * Math.exp(-t / 0.01);
                    }
                    case "zap" -> {
                        ph += h.f * 2 * Math.pow(0.1, u) / SR;
                        v = Math.sin(2 * Math.PI * ph) * Math.exp(-t / len) * 0.5;
                    }
                    default -> { }
                }
                o[i] = (float) (v * h.vel);
            }
            return o;
        }

        float[] chemVoice(Hit h) {
            int n = (int) Math.floor(0.25 * SR);
            float[] o = new float[n];
            Rng nr = new Rng(h.seed);
            double ph = 0, y1 = 0, y2 = 0;
            for (int i = 0; i < n; i++) {
                double t = i / (double) SR, v = 0;
                if (h.voice.equals("gloop")) {
                    double f = 180 + 260 * Math.sin(Math.PI * Math.min(1, t / 0.2));
                    double w = 2 * Math.PI * f / SR, rr = 0.99;
                    double x = (nr.next() * 2 - 1) * 0.2;
                    double y = x + 2 * rr * Math.cos(w) * y1 - rr * rr * y2;
                    y2 = y1;
                    y1 = y;
                    v = y * 0.04 * Math.sin(Math.PI * Math.min(1, t / 0.22));
                } else if (h.voice.equals("thump")) {
                    ph += (55 + 40 * Math.exp(-t / 0.02)) / SR;
                    v = Math.sin(2 * Math.PI * ph) * 0.7 * Math.exp(-t / 0.08);
                }
                o[i] = (float) (v * h.vel);
            }
            return o;
        }

        float[] chemFizz(int n) {
            float[] out = new float[n];
            Rng nr = new Rng(bubbleSeed);
            double w = 2 * Math.PI * Math.min(cc.fizzTone, SR * 0.45) / SR;
            double rr = 0.80 + 0.19 * (1 - cc.fizzWidth);
            double y1 = 0, y2 = 0, grainEnv = 1;
            int grainLeft = 0;
            for (int i = 0; i < n; i++) {
                double tm = i / (double) SR * 1000, e = env(tm);
                double x = nr.next() * 2 - 1;
                double y = x * (1 - rr) + 2 * rr * Math.cos(w) * y1 - rr * rr * y2;
                y2 = y1;
                y1 = y;
                if (--grainLeft <= 0) {
                    grainLeft = (int) Math.floor(SR * (0.002 + nr.next() * 0.012));
                    grainEnv = nr.next() < cc.grain ? nr.next() * 0.25 : 1;
                }
                out[i] = (float) (y * cc.fizz * e * (1 - cc.grain * 0.6 + cc.grain * 0.6 * grainEnv) * 2.2);
            }
            int hop = (int) Math.floor(SR * 0.005);
            for (int i = 0; i < n; i += hop) {
                double e = env(i / (double) SR * 1000);
                if (nr.next() < cc.bubbleRate * 0.005 * e * cc.bubbles * 1.5) {
                    double f0 = bubbleNotes[(int) Math.floor(nr.next() * bubbleNotes.length)];
                    int len = (int) Math.floor(SR * (0.02 + nr.next() * 0.04));
                    double amp = (0.3 + 0.4 * nr.next()) * cc.bubbles, ph = 0;
                    for (int k = 0; k < len && i + k < n; k++) {
                        double t = k / (double) SR;
                        ph += f0 * (1 + t / 0.03) / SR;
                        out[i + k] += (float) (Math.sin(2 * Math.PI * ph) * amp * Math.exp(-t / 0.012)
                                * (k < 40 ? k / 40.0 : 1));
                    }
                }
            }
            return out;
        }

        static void bitcrush(float[] x, double amount) {
            if (amount <= 0) return;
            double levels = Math.pow(2, 16 - amount * 12);
            int hold = 1 + (int) Math.floor(amount * 6);
            float held = 0;
            for (int i = 0; i < x.length; i++) {
                if (i % hold == 0) held = (float) (Math.round(x[i] * levels) / levels);
                x[i] = held;
            }
        }

        static float[] reverb(float[] x, double mix) {
            float[] out = new float[x.length], wet = new float[x.length];
            for (double d : new double[] {0.0297, 0.0371, 0.0411, 0.0437}) {
                int len = (int) Math.floor(d * SR);
                float[] b = new float[len];
                int k = 0;
                for (int i = 0; i < x.length; i++) {
                    float y = b[k];
                    b[k] = (float) (x[i] + y * 0.7);
                    k = (k + 1) % len;
                    wet[i] += (float) (y * 0.25);
                }
            }
            for (double d : new double[] {0.005, 0.0017}) {
                int len = (int) Math.floor(d * SR);
                float[] b = new float[len];
                int k = 0;
                for (int i = 0; i < x.length; i++) {
                    float bb = b[k];
                    float y = -wet[i] + bb;
                    b[k] = (float) (wet[i] + bb * 0.5);
                    k = (k + 1) % len;
                    wet[i] = y;
                }
            }
            for (int i = 0; i < x.length; i++) out[i] = (float) (x[i] * (1 - mix) + wet[i] * mix * 2);
            return out;
        }

        /** The sound as float samples at {@link #SR}, peaking at the configured volume. */
        public float[] synthFloat() {
            int n = (int) Math.floor(duration / 1000.0 * SR);
            float[] mix = new float[n];
            for (Hit h : hits) {
                float[] o = engine == Engine.DIODE ? diodeVoice(h) : chemVoice(h);
                int st = (int) Math.floor(h.t / 1000.0 * SR);
                for (int i = 0; i < o.length && st + i < n; i++) mix[st + i] += o[i];
            }
            if (engine == Engine.CHEMICAL) {
                float[] fz = chemFizz(n);
                for (int i = 0; i < n; i++) mix[i] += fz[i];
            } else {
                bitcrush(mix, dc.crush);
            }
            mix = reverb(mix, engine == Engine.DIODE ? dc.reverb : cc.reverb);
            double volume = engine == Engine.DIODE ? dc.volume : cc.volume;
            double peak = 1e-9;
            for (float v : mix) peak = Math.max(peak, Math.abs(v));
            for (int i = 0; i < n; i++) mix[i] = (float) (mix[i] * volume / peak);
            int fadeN = (int) Math.floor(0.03 * SR);
            for (int i = 0; i < fadeN && i < n; i++) {
                mix[n - 1 - i] = (float) (mix[n - 1 - i] * (i / (double) fadeN));
            }
            return mix;
        }

        /** The sound as 16-bit PCM at {@link #SR}. */
        public short[] synth() {
            float[] f = synthFloat();
            short[] pcm = new short[f.length];
            for (int i = 0; i < f.length; i++) pcm[i] = (short) (Math.max(-1, Math.min(1, f[i])) * 32767);
            return pcm;
        }
    }

    /** A complete WAV file (16-bit mono) for the PCM. */
    public static byte[] wav(short[] pcm, int sampleRate) {
        int dataLen = pcm.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + dataLen)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + dataLen)
                .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(sampleRate)
                .putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(dataLen);
        for (short s : pcm) b.putShort(s);
        return b.array();
    }
}
