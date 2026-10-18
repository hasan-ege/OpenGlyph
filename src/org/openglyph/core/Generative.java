package org.openglyph.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generative notifications, after the idea Nothing describes for the Phone (3)'s generative
 * ringtones: an "engine" turns a seed (here: this phone's random seed plus the app's name)
 * into a sound and a light sequence that belong together, so every app gets its own and you
 * learn to recognise them. Our own implementation; nothing of Nothing's is used.
 *
 * Two engines, as on the stock phone:
 * - DIODE: a few sharp pixels in a kaleidoscope (mirror / 4-fold / 8-fold symmetry) that jump
 *   and fade with each hit of a crisp, sometimes bit-crushed sample set (bleeps, ticks, chirps).
 * - CHEMICAL: a Gray-Scott reaction-diffusion field; each hit drops a "droplet" that grows
 *   into blobs and worms, mirrored left to right, over a softer set (drips, plucks, metal).
 *
 * Sound: a small bank of micro samples synthesised from the seed (each a tiny instrument:
 * FM bleep, resonant drip, Karplus-Strong pluck, ring-mod metal, chirp, tick, square bleep,
 * swell), played by a 12-16 step sequencer with seeded rhythm and pitches, then a short
 * Schroeder reverb. Light: precomputed 30 fps frames quantised to 3 bits. Pure Java (tested
 * off-device) and deterministic for a seed.
 */
public final class Generative {
    private Generative() {}

    public enum Engine {
        DIODE, CHEMICAL;

        public static Engine parse(String s) {
            try {
                return s == null ? DIODE : valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DIODE;
            }
        }
    }

    static final long FRAME_MS = 33;
    static final long TAIL_MS = 600;
    static final int LEVELS = 8; // 3-bit brightness

    /** splitmix64 */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A stable 64-bit seed for one name on one device. */
    public static long seedFor(long deviceSeed, String key) {
        long h = mix(deviceSeed);
        for (int i = 0; i < key.length(); i++) h = mix(h ^ key.charAt(i));
        return h;
    }

    public static Theme theme(long deviceSeed, String name, Engine engine) {
        return new Theme(seedFor(deviceSeed, name), engine);
    }

    /** A hit of the sequencer: when, which sample, how loud, which pitch. */
    public static final class Hit {
        public final long timeMs;
        public final int sample;
        public final double velocity;
        public final double freq;

        Hit(long timeMs, int sample, double velocity, double freq) {
            this.timeMs = timeMs;
            this.sample = sample;
            this.velocity = velocity;
            this.freq = freq;
        }
    }

    /** One app's sound and light. */
    public static final class Theme {
        public final long seed;
        public final Engine engine;
        public final List<Hit> hits = new ArrayList<>();
        final Sample[] bank;
        /**
         * Diode: 0 mirror-x, 1 mirror-xy, 2 eight-fold, 3 four-fold rotation. Chemical:
         * 0 and 3 mirror-x, 1 mirror-xy, 2 half-turn.
         */
        public final int symmetry;
        final double reverbMix;
        final boolean crush;
        private final long duration;
        private final List<int[]> frames = new ArrayList<>();

        Theme(long seed, Engine engine) {
            this.seed = seed;
            this.engine = engine;
            Random r = new Random(seed);

            // ---- sound bank: 3-4 micro samples from the engine's palette ----
            int[] palette = engine == Engine.DIODE ? new int[] {0, 4, 5, 6, 3}
                    : new int[] {1, 2, 3, 7, 4};
            int n = 3 + r.nextInt(2);
            bank = new Sample[n];
            for (int i = 0; i < n; i++) bank[i] = new Sample(palette[r.nextInt(palette.length)], r);
            symmetry = r.nextInt(4);
            reverbMix = engine == Engine.CHEMICAL ? 0.25 + r.nextDouble() * 0.2
                    : 0.1 + r.nextDouble() * 0.15;
            crush = engine == Engine.DIODE && r.nextBoolean();

            // ---- sequencer: 12-16 steps, seeded rhythm and pitches ----
            int[][] scales = {
                {0, 2, 4, 7, 9}, {0, 3, 5, 7, 10}, {0, 2, 4, 6, 8, 10}, {0, 1, 5, 7, 8},
                {0, 2, 3, 7, 8}, {0, 5, 7, 10, 14}, {0, 1, 3, 6, 7, 10},
            };
            int[] scale = scales[r.nextInt(scales.length)];
            double root = 220 * Math.pow(2, r.nextInt(12) / 12.0) * (engine == Engine.DIODE ? 2 : 1);
            int steps = 12 + r.nextInt(5);
            long stepMs = 70 + r.nextInt(55);
            double density = 0.35 + r.nextDouble() * 0.3;
            int degree = r.nextInt(scale.length);
            for (int s = 0; s < steps; s++) {
                boolean on = s == 0 || r.nextDouble() < density * (s % 4 == 0 ? 1.4 : 0.8);
                if (!on) continue;
                degree = Math.max(-2, Math.min(scale.length + 3, degree + r.nextInt(5) - 2));
                int oct = Math.floorDiv(degree, scale.length);
                int semis = scale[Math.floorMod(degree, scale.length)] + 12 * oct;
                double f = root * Math.pow(2, semis / 12.0);
                int sample = s == 0 ? 0 : r.nextInt(bank.length);
                double vel = (s % 4 == 0 ? 1.0 : 0.65) * (0.8 + 0.2 * r.nextDouble());
                hits.add(new Hit(s * stepMs, sample, vel, f));
            }
            // A last accent closes the phrase.
            hits.add(new Hit(steps * stepMs, 0, 1.0, root * (r.nextBoolean() ? 1 : 1.5)));
            duration = steps * stepMs + TAIL_MS;

            // ---- light ----
            if (engine == Engine.DIODE) buildDiode(new Random(seed ^ 0x51ED));
            else buildChemical(new Random(seed ^ 0xC4E));
        }

        public long durationMs() {
            return duration;
        }

        /** Draws the frame at {@code t} ms after the notification. */
        public void render(Frame f, long t) {
            int i = (int) (t / FRAME_MS);
            if (t < 0 || i >= frames.size()) return;
            int[] g = frames.get(i);
            for (int k = 0; k < g.length; k++) {
                if (g[k] > 0) f.max(k % MatrixLayout.WIDTH, k / MatrixLayout.WIDTH, g[k]);
            }
        }

        /** Overall envelope: quick fade in, fade out over the tail. */
        private double envelope(long t) {
            double in = Math.min(1, t / 60.0);
            double out = Math.min(1, (duration - t) / (double) TAIL_MS);
            return Math.max(0, Math.min(in, out));
        }

        private static int quantise(double v) {
            if (v <= 0.02) return 0;
            int level = (int) Math.ceil(Math.min(1, v) * (LEVELS - 1));
            return Frame.MAX * level / (LEVELS - 1);
        }

        // ---------------- DIODE: kaleidoscopic sparks ----------------

        private static final class Spark {
            double x, y, vx, vy;
            long born;
            long life;
            double bright;
        }

        private void buildDiode(Random r) {
            List<Spark> sparks = new ArrayList<>();
            // A faint frame of the symmetry, the same for this seed throughout.
            List<int[]> skeleton = new ArrayList<>();
            int skel = 2 + r.nextInt(4);
            for (int i = 0; i < skel; i++) skeleton.add(new int[] {r.nextInt(12), r.nextInt(12)});
            for (Hit h : hits) {
                int count = 1 + r.nextInt(3) + (h.velocity > 0.9 ? 1 : 0);
                for (int i = 0; i < count; i++) {
                    Spark s = new Spark();
                    // Fundamental domain: one quadrant; the symmetry makes the rest.
                    double radius = 2 + r.nextDouble() * 9.5 * (0.4 + 0.6 * Math.min(1, h.freq / 1200));
                    double ang = r.nextDouble() * Math.PI / 2;
                    s.x = radius * Math.cos(ang);
                    s.y = radius * Math.sin(ang);
                    double speed = (r.nextDouble() - 0.3) * 18; // px/s, mostly outwards
                    s.vx = Math.cos(ang) * speed;
                    s.vy = Math.sin(ang) * speed;
                    s.born = h.timeMs;
                    s.life = 180 + r.nextInt(320);
                    s.bright = h.velocity;
                    sparks.add(s);
                }
            }
            for (long t = 0; t < duration; t += FRAME_MS) {
                double[] v = new double[MatrixLayout.GRID_SIZE];
                double env = envelope(t);
                for (int[] p : skeleton) plotSym(v, p[0], p[1], 0.12 * env);
                for (Spark s : sparks) {
                    long age = t - s.born;
                    if (age < 0 || age > s.life) continue;
                    double u = age / (double) s.life;
                    double x = s.x + s.vx * age / 1000.0, y = s.y + s.vy * age / 1000.0;
                    // Sharp on, quick decay, a little flicker like a struck LED.
                    double b = s.bright * Math.pow(1 - u, 1.6)
                            * (age < 40 ? 1.0 : 0.85 + 0.15 * Math.cos(age / 23.0));
                    plotSym(v, (int) Math.round(x), (int) Math.round(y), Math.min(1, b * 1.4) * env);
                }
                frames.add(toFrame(v));
            }
        }

        /** Plots (x, y) relative to the centre with this theme's symmetry. */
        private void plotSym(double[] v, int x, int y, double b) {
            int[][] pts = switch (symmetry) {
                case 0 -> new int[][] {{x, y}, {-x, y}};
                case 1 -> new int[][] {{x, y}, {-x, y}, {x, -y}, {-x, -y}};
                case 2 -> new int[][] {{x, y}, {-x, y}, {x, -y}, {-x, -y},
                        {y, x}, {-y, x}, {y, -x}, {-y, -x}};
                default -> new int[][] {{x, y}, {-y, x}, {-x, -y}, {y, -x}};
            };
            for (int[] p : pts) {
                int gx = 12 + p[0], gy = 12 + p[1];
                if (!MatrixLayout.exists(gx, gy)) continue;
                int k = gy * MatrixLayout.WIDTH + gx;
                v[k] = Math.max(v[k], b);
            }
        }

        // ---------------- CHEMICAL: reaction-diffusion blobs ----------------

        private void buildChemical(Random r) {
            int w = MatrixLayout.WIDTH, h = MatrixLayout.HEIGHT;
            // Gray-Scott in the spot / worm forming range.
            // Small diffusion so the features are a few pixels across on a 25x25 grid.
            double feed = 0.026 + r.nextDouble() * 0.014;
            double kill = 0.060 + r.nextDouble() * 0.004;
            double du = 0.09 + r.nextDouble() * 0.03, dv = du / 2;
            int iterPerFrame = 4 + r.nextInt(3);
            double[] u = new double[w * h], vv = new double[w * h];
            double[] nu = new double[w * h], nv = new double[w * h];
            java.util.Arrays.fill(u, 1);
            int hitIndex = 0;
            for (long t = 0; t < duration; t += FRAME_MS) {
                // Each hit drops a mirrored droplet; louder hits drop bigger ones.
                while (hitIndex < hits.size() && hits.get(hitIndex).timeMs <= t) {
                    Hit hit = hits.get(hitIndex++);
                    int cx = 3 + r.nextInt(9), cy = 3 + r.nextInt(19);
                    double rad = 1.0 + hit.velocity * 0.8;
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            // Droplets mirrored left-right, and per seed also up-down or
                            // turned half way round.
                            double d1 = Math.hypot(x - cx, y - cy);
                            double d2 = Math.hypot(x - (w - 1 - cx), y - cy);
                            if (symmetry == 1) {
                                d1 = Math.min(d1, Math.hypot(x - cx, y - (h - 1 - cy)));
                                d2 = Math.min(d2, Math.hypot(x - (w - 1 - cx), y - (h - 1 - cy)));
                            } else if (symmetry == 2) {
                                d2 = Math.hypot(x - (w - 1 - cx), y - (h - 1 - cy));
                            }
                            if (Math.min(d1, d2) <= rad) {
                                u[y * w + x] = 0.5;
                                vv[y * w + x] = 0.25 + 0.25 * hit.velocity;
                            }
                        }
                    }
                }
                for (int it = 0; it < iterPerFrame; it++) {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int k = y * w + x;
                            double lu = lap(u, x, y, w, h), lv = lap(vv, x, y, w, h);
                            double uvv = u[k] * vv[k] * vv[k];
                            nu[k] = u[k] + du * lu - uvv + feed * (1 - u[k]);
                            nv[k] = vv[k] + dv * lv + uvv - (feed + kill) * vv[k];
                        }
                    }
                    double[] tu = u; u = nu; nu = tu;
                    double[] tv = vv; vv = nv; nv = tv;
                }
                double env = envelope(t);
                double[] out = new double[w * h];
                for (int k = 0; k < out.length; k++) {
                    // The activator's own texture, from a faint rim to bright cores.
                    out[k] = Math.max(0, Math.min(1, (vv[k] - 0.12) / 0.2)) * env;
                }
                frames.add(toFrame(out));
            }
        }

        private static double lap(double[] a, int x, int y, int w, int h) {
            int xm = Math.max(0, x - 1), xp = Math.min(w - 1, x + 1);
            int ym = Math.max(0, y - 1), yp = Math.min(h - 1, y + 1);
            return a[y * w + xm] + a[y * w + xp] + a[ym * w + x] + a[yp * w + x] - 4 * a[y * w + x];
        }

        private static int[] toFrame(double[] v) {
            int[] g = new int[MatrixLayout.GRID_SIZE];
            for (int k = 0; k < g.length; k++) {
                if (MatrixLayout.exists(k % MatrixLayout.WIDTH, k / MatrixLayout.WIDTH)) {
                    g[k] = quantise(v[k]);
                }
            }
            return g;
        }

        // ---------------- sound ----------------

        /** The phrase as 16-bit mono PCM. */
        public short[] synth(int sampleRate) {
            int total = (int) (duration / 1000.0 * sampleRate);
            double[] out = new double[total];
            for (Hit hit : hits) {
                double[] s = bank[hit.sample].render(hit.freq, sampleRate, seed ^ hit.timeMs);
                int start = (int) (hit.timeMs / 1000.0 * sampleRate);
                for (int i = 0; i < s.length && start + i < total; i++) out[start + i] += s[i] * hit.velocity;
            }
            if (crush) {
                // 5-bit, quarter-rate sample-and-hold: the "diode" grit.
                double peak = 1e-9;
                for (double v : out) peak = Math.max(peak, Math.abs(v));
                double held = 0;
                for (int i = 0; i < total; i++) {
                    if (i % 4 == 0) held = Math.round(out[i] / peak * 16) / 16.0 * peak;
                    out[i] = held;
                }
            }
            out = reverb(out, sampleRate, reverbMix);
            int fade = Math.min(total, sampleRate * 30 / 1000);
            for (int k = 0; k < fade; k++) out[total - 1 - k] *= k / (double) fade;
            double peak = 1e-9;
            for (double v : out) peak = Math.max(peak, Math.abs(v));
            short[] pcm = new short[total];
            double gain = 0.7 * Short.MAX_VALUE / peak;
            for (int k = 0; k < total; k++) pcm[k] = (short) Math.round(out[k] * gain);
            return pcm;
        }

        /** Small Schroeder reverb: four combs into two allpasses. */
        static double[] reverb(double[] in, int sr, double mix) {
            int[] combs = {(int) (0.0297 * sr), (int) (0.0371 * sr), (int) (0.0411 * sr), (int) (0.0437 * sr)};
            double[] wet = new double[in.length];
            for (int c : combs) {
                double[] buf = new double[c];
                int idx = 0;
                for (int i = 0; i < in.length; i++) {
                    double y = buf[idx];
                    buf[idx] = in[i] + y * 0.72;
                    idx = (idx + 1) % c;
                    wet[i] += y * 0.25;
                }
            }
            for (int a : new int[] {(int) (0.005 * sr), (int) (0.0017 * sr)}) {
                double[] buf = new double[a];
                int idx = 0;
                for (int i = 0; i < wet.length; i++) {
                    double b = buf[idx];
                    double y = -wet[i] + b;
                    buf[idx] = wet[i] + b * 0.5;
                    idx = (idx + 1) % a;
                    wet[i] = y;
                }
            }
            double[] out = new double[in.length];
            for (int i = 0; i < in.length; i++) out[i] = in[i] * (1 - mix) + wet[i] * mix * 2;
            return out;
        }
    }

    /**
     * One micro sample: a tiny instrument with seeded character, rendered at a pitch.
     * Types: 0 FM bleep, 1 resonant drip, 2 pluck, 3 ring-mod metal, 4 chirp, 5 tick,
     * 6 square bleep, 7 swell.
     */
    static final class Sample {
        final int type;
        final double a, b, c; // type-specific character
        final double lengthS;

        Sample(int type, Random r) {
            this.type = type;
            a = r.nextDouble();
            b = r.nextDouble();
            c = r.nextDouble();
            lengthS = switch (type) {
                case 5 -> 0.03 + 0.03 * a;
                case 7 -> 0.35 + 0.3 * a;
                case 2, 3 -> 0.25 + 0.3 * a;
                default -> 0.08 + 0.14 * a;
            };
        }

        double[] render(double freq, int sr, long noiseSeed) {
            int n = (int) (lengthS * sr);
            double[] o = new double[n];
            Random noise = new Random(noiseSeed);
            switch (type) {
                case 0 -> { // FM bleep
                    double ratio = new double[] {1, 2, 3.5, 0.5, 1.41}[(int) (b * 5)];
                    double index = 1 + 5 * c;
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr, env = Math.exp(-t / (lengthS * 0.35));
                        o[i] = env * Math.sin(2 * Math.PI * freq * t
                                + index * env * Math.sin(2 * Math.PI * freq * ratio * t));
                    }
                }
                case 1 -> { // resonant drip: an impulse through a rising resonant band-pass
                    double y1 = 0, y2 = 0;
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr;
                        double f = freq * (1 + (0.5 + b) * t / lengthS);
                        double w = 2 * Math.PI * Math.min(f, sr * 0.45) / sr;
                        double rr = 0.995 - 0.01 * c;
                        double x = (noise.nextDouble() * 2 - 1) * (i < sr * 0.004 ? 1 : 0.02);
                        double y = x + 2 * rr * Math.cos(w) * y1 - rr * rr * y2;
                        y2 = y1;
                        y1 = y;
                        o[i] = y * 0.05 * Math.exp(-t / (lengthS * 0.5));
                    }
                }
                case 2 -> { // Karplus-Strong pluck
                    int period = Math.max(2, (int) (sr / freq));
                    double[] line = new double[period];
                    for (int i = 0; i < period; i++) line[i] = noise.nextDouble() * 2 - 1;
                    double damp = 0.994 + 0.005 * b;
                    for (int i = 0; i < n; i++) {
                        int k = i % period;
                        double v = line[k];
                        line[k] = damp * 0.5 * (v + line[(k + 1) % period]);
                        o[i] = v;
                    }
                }
                case 3 -> { // ring-mod metal
                    double f2 = freq * (1.37 + 2.1 * b);
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr;
                        o[i] = Math.exp(-t / (lengthS * 0.3)) * Math.sin(2 * Math.PI * freq * t)
                                * Math.sin(2 * Math.PI * f2 * t);
                    }
                }
                case 4 -> { // chirp, up or down
                    double span = (b < 0.5 ? 1 : -1) * (0.5 + 1.5 * c);
                    double phase = 0;
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr, u = t / lengthS;
                        phase += 2 * Math.PI * freq * Math.pow(2, span * u) / sr;
                        o[i] = Math.sin(phase) * Math.sin(Math.PI * Math.min(1, u * 8)) * (1 - u);
                    }
                }
                case 5 -> { // tick: a click and a short high ping
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr;
                        o[i] = (i < 12 ? (noise.nextDouble() * 2 - 1) : 0)
                                + 0.6 * Math.exp(-t / 0.008) * Math.sin(2 * Math.PI * freq * 4 * t);
                    }
                }
                case 6 -> { // square bleep with a pitch blip at the start
                    double phase = 0;
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr;
                        phase += freq * (1 + (t < 0.01 ? 1 : 0)) / sr;
                        double sq = (phase % 1) < 0.3 + 0.4 * b ? 1 : -1;
                        o[i] = 0.5 * sq * Math.exp(-t / (lengthS * 0.5));
                    }
                }
                default -> { // swell: detuned saws, slow attack
                    for (int i = 0; i < n; i++) {
                        double t = i / (double) sr, u = t / lengthS;
                        double s = 0;
                        for (int d = -1; d <= 1; d++) {
                            double ph = (freq * (1 + d * 0.006 * (1 + b)) * t) % 1;
                            s += 2 * ph - 1;
                        }
                        o[i] = s / 3 * Math.sin(Math.PI * u) * 0.6;
                    }
                }
            }
            // Click-free start and end.
            int edge = Math.min(n / 2, sr / 1000);
            for (int i = 0; i < edge; i++) {
                o[i] *= i / (double) edge;
                o[n - 1 - i] *= i / (double) edge;
            }
            return o;
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
