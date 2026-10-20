package org.openglyph.core;

/** Small FFT helpers for the visualiser: no Android, so the host tests cover them. */
public final class Spectrum {
    private Spectrum() {}

    /**
     * Peak amplitude per FFT bin (a full-scale sine reads about 1 in its bin), from the last
     * {@code n} samples (n a power of two) under a Hann window.
     *
     * @param out at least n / 2 + 1 values
     */
    public static void magnitudes(float[] samples, int from, int n, float[] out) {
        double[] re = new double[n], im = new double[n];
        double wsum = 0;
        for (int i = 0; i < n; i++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            re[i] = samples[from + i] * w;
            wsum += w;
        }
        fft(re, im);
        for (int k = 0; k <= n / 2; k++) out[k] = (float) (2 * Math.hypot(re[k], im[k]) / wsum);
    }

    /** In-place radix-2 FFT; the length must be a power of two. */
    public static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double a = -2 * Math.PI / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < len / 2; k++) {
                    double c = Math.cos(a * k), s = Math.sin(a * k);
                    int p = i + k, q = i + k + len / 2;
                    double xr = re[q] * c - im[q] * s, xi = re[q] * s + im[q] * c;
                    re[q] = re[p] - xr;
                    im[q] = im[p] - xi;
                    re[p] += xr;
                    im[p] += xi;
                }
            }
        }
    }

    /**
     * Log-spaced bands from fmin to fmax: the loudest bin in each, or the nearest bin when a band
     * is narrower than one bin.
     */
    public static void bands(float[] mag, int bins, double binHz, double fmin, double fmax,
            float[] out) {
        int n = out.length;
        for (int b = 0; b < n; b++) {
            double lo = fmin * Math.pow(fmax / fmin, b / (double) n);
            double hi = fmin * Math.pow(fmax / fmin, (b + 1) / (double) n);
            float m = -1;
            for (int i = (int) Math.ceil(lo / binHz); i < bins && i * binHz < hi; i++) {
                m = Math.max(m, mag[i]);
            }
            if (m < 0) {
                int i = (int) Math.round(Math.sqrt(lo * hi) / binHz);
                m = i < bins ? mag[i] : 0;
            }
            out[b] = Math.min(1, m);
        }
    }
}
