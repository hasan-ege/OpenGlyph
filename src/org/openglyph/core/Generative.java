package org.openglyph.core;

import java.util.Random;

/**
 * Generative notifications: from a per-device seed and an app's package name, a short melody
 * and a matching Glyph Matrix animation. The same device and app always give the same result;
 * another app, or the same app on another phone, gives a different one.
 *
 * The melody is a few notes from a scale, played by a small FM voice with an echo. The
 * animation is one of several motifs (rings, spokes, spirals, particles, ripples, a blossom,
 * sweeping blades, sparkles); every note fires the motif again, so light and sound move
 * together. Pure Java: no Android, so it is tested off-device.
 */
public final class Generative {
    private Generative() {}

    static final int[][] SCALES = {
        {0, 2, 4, 7, 9},           // major pentatonic
        {0, 3, 5, 7, 10},          // minor pentatonic
        {0, 2, 4, 6, 7, 9, 11},    // lydian
        {0, 2, 3, 5, 7, 9, 10},    // dorian
        {0, 2, 3, 7, 8},           // hirajoshi
        {0, 4, 7, 11, 14},         // major seventh arpeggio
    };
    static final double[] FM_RATIOS = {1, 2, 3, 0.5, 1.5, 3.5, 4};
    public static final int MOTIFS = 8;
    static final long NOTE_LIFE_MS = 700;
    static final long TAIL_MS = 800;

    /** splitmix64 */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A stable 64-bit seed for one app on one device. */
    public static long seedFor(long deviceSeed, String key) {
        long h = mix(deviceSeed);
        for (int i = 0; i < key.length(); i++) h = mix(h ^ key.charAt(i));
        return h;
    }

    public static Theme theme(long deviceSeed, String pkg) {
        return new Theme(seedFor(deviceSeed, pkg));
    }

    /** One app's sound and animation. */
    public static final class Theme {
        public final long seed;
        public final int motif;
        final int symmetry;
        final double spin;      // rad/s, signed
        final double baseAngle;
        final double twist;
        final double density;
        // music
        public final int[] notesMidi;
        public final long[] onsetsMs;
        final double fmRatio, fmIndex, decayS, echoS, echoFeedback;

        Theme(long seed) {
            this.seed = seed;
            Random r = new Random(seed);
            motif = r.nextInt(MOTIFS);
            symmetry = 2 + r.nextInt(7);
            spin = (0.6 + r.nextDouble() * 2.4) * (r.nextBoolean() ? 1 : -1);
            baseAngle = r.nextDouble() * 2 * Math.PI;
            twist = 0.15 + r.nextDouble() * 0.5;
            density = 0.05 + r.nextDouble() * 0.07;

            int[] scale = SCALES[r.nextInt(SCALES.length)];
            int root = 67 + r.nextInt(12);
            int count = 3 + r.nextInt(3);
            long step = 90 + r.nextInt(71);
            notesMidi = new int[count];
            onsetsMs = new long[count];
            int degree = r.nextInt(scale.length);
            long t = 0;
            boolean arpeggio = r.nextInt(3) == 0;
            for (int i = 0; i < count; i++) {
                int octave = Math.floorDiv(degree, scale.length);
                int idx = Math.floorMod(degree, scale.length);
                notesMidi[i] = root + 12 * octave + scale[idx];
                onsetsMs[i] = t;
                t += step * (r.nextInt(4) == 0 ? 2 : 1);
                degree += arpeggio ? 1 + r.nextInt(2) : r.nextInt(7) - 3;
                degree = Math.max(-scale.length, Math.min(2 * scale.length - 1, degree));
            }
            fmRatio = FM_RATIOS[r.nextInt(FM_RATIOS.length)];
            fmIndex = 0.6 + r.nextDouble() * 2.9;
            decayS = 0.12 + r.nextDouble() * 0.33;
            echoS = 0.11 + r.nextDouble() * 0.11;
            echoFeedback = 0.22 + r.nextDouble() * 0.18;
        }

        public long durationMs() {
            return onsetsMs[onsetsMs.length - 1] + TAIL_MS;
        }

        /** Pitch of note i mapped to 0..1 over the playable range. */
        double pitch01(int i) {
            return Math.max(0, Math.min(1, (notesMidi[i] - 55) / 36.0));
        }

        /** Draws the animation at {@code t} ms after the notification. */
        public void render(Frame f, long t) {
            for (int i = 0; i < onsetsMs.length; i++) {
                long lt = t - onsetsMs[i];
                if (lt < 0 || lt >= NOTE_LIFE_MS) continue;
                double u = lt / (double) NOTE_LIFE_MS;
                double e = Math.pow(1 - u, 1.3);
                drawMotif(f, i, u, e, t / 1000.0);
            }
        }

        private void drawMotif(Frame f, int note, double u, double e, double ts) {
            double p = pitch01(note);
            double rot = baseAngle + spin * ts + note * Math.PI / symmetry;
            for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    if (!MatrixLayout.exists(x, y)) continue;
                    double dx = x - 12, dy = y - 12;
                    double d = Math.hypot(dx, dy);
                    double a = Math.atan2(dx, -dy);
                    double v = 0;
                    switch (motif) {
                        case 0 -> { // ring with beads
                            double rr = u * 12.5;
                            v = Math.max(0, 1 - Math.abs(d - rr) / 0.9);
                            double bead = Math.cos(symmetry * (a - rot));
                            v *= 0.55 + 0.45 * Math.max(0, bead);
                        }
                        case 1 -> { // spokes
                            double len = (0.45 + 0.55 * p) * 12.5 * Math.sqrt(u);
                            double ang = angDist(a, rot, symmetry);
                            v = d <= len ? Math.max(0, 1 - ang * d / 1.1) : 0;
                        }
                        case 2 -> { // spiral arms
                            double ang = angDist(a, rot + d * twist, symmetry);
                            double front = Math.max(0, 1 - Math.abs(d - u * 13) / 5);
                            v = Math.max(0, 1 - ang * Math.max(d, 1) / 1.2) * front;
                        }
                        case 3 -> { // particles from the centre
                            v = 0;
                        }
                        case 4 -> { // ripples
                            double band = d - u * 13;
                            v = band > 0 ? 0 : Math.max(0, Math.cos(band * (1.0 + p))) * Math.exp(band / 6);
                        }
                        case 5 -> { // blossom (polar rose)
                            double rr = (3 + 9.5 * u) * Math.abs(Math.cos(symmetry / 2.0 * (a - rot)));
                            v = Math.max(0, 1 - Math.abs(d - rr) / 1.2);
                        }
                        case 6 -> { // sweeping blades
                            double ang = angDist(a, rot * 2, symmetry);
                            v = d <= 12.5 * (0.3 + 0.7 * u) && ang < 0.35 ? 1 - ang / 0.35 : 0;
                        }
                        default -> { // sparkles
                            long h = mix(seed ^ (note * 7919L) ^ (y * 31L + x));
                            double chance = (h >>> 11) / (double) (1L << 53);
                            v = chance < density * (1.5 - u) ? 1 : 0;
                        }
                    }
                    if (v > 0) f.max(x, y, (int) (Frame.MAX * Math.min(1, v) * e));
                }
            }
            if (motif == 3) drawParticles(f, note, u, e, p);
        }

        private void drawParticles(Frame f, int note, double u, double e, double p) {
            Random r = new Random(seed ^ (note + 1) * 0x5DEECE66DL);
            int count = 6 + symmetry;
            for (int j = 0; j < count; j++) {
                double ang = r.nextDouble() * 2 * Math.PI;
                double speed = 0.5 + r.nextDouble() * 0.5;
                double rr = u * 12.5 * speed * (0.7 + 0.3 * p);
                int x = (int) Math.round(12 + Math.sin(ang) * rr);
                int y = (int) Math.round(12 - Math.cos(ang) * rr);
                f.max(x, y, (int) (Frame.MAX * e));
            }
        }

        /** Distance in radians from angle a to the nearest of n spokes starting at base. */
        static double angDist(double a, double base, int n) {
            double sector = 2 * Math.PI / n;
            double m = ((a - base) % sector + sector) % sector;
            return Math.min(m, sector - m);
        }

        /** The melody as 16-bit mono PCM. */
        public short[] synth(int sampleRate) {
            int total = (int) (durationMs() / 1000.0 * sampleRate);
            double[] out = new double[total];
            for (int i = 0; i < notesMidi.length; i++) {
                double freq = 440 * Math.pow(2, (notesMidi[i] - 69) / 12.0);
                int start = (int) (onsetsMs[i] / 1000.0 * sampleRate);
                double vel = i == notesMidi.length - 1 ? 1.0 : 0.8;
                for (int k = 0; start + k < total; k++) {
                    double t = k / (double) sampleRate;
                    double attack = Math.min(1, t / 0.004);
                    double env = attack * Math.exp(-t / decayS);
                    if (env < 1e-4 && t > 0.01) break;
                    double modEnv = Math.exp(-t / (decayS * 0.6));
                    double mod = fmIndex * modEnv * Math.sin(2 * Math.PI * freq * fmRatio * t);
                    out[start + k] += vel * env * Math.sin(2 * Math.PI * freq * t + mod);
                }
            }
            int delay = (int) (echoS * sampleRate);
            for (int k = delay; k < total; k++) out[k] += out[k - delay] * echoFeedback;
            // Fade out the last 30 ms so the clip ends cleanly.
            int fade = Math.min(total, sampleRate * 30 / 1000);
            for (int k = 0; k < fade; k++) out[total - 1 - k] *= k / (double) fade;
            double peak = 1e-9;
            for (double v : out) peak = Math.max(peak, Math.abs(v));
            short[] pcm = new short[total];
            double gain = 0.7 * Short.MAX_VALUE / peak;
            for (int k = 0; k < total; k++) pcm[k] = (short) Math.round(out[k] * gain);
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
