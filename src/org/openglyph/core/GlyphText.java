package org.openglyph.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text to Glyph: any text, in any script, drawn on the matrix with a real font instead of
 * hand-made letter bitmaps. The platform's font engine (Android's Paint on the phone, AWT on a
 * computer) draws the line several times larger than the matrix; it is boxed down to LEDs, so
 * every stroke keeps its weight as a grey level, and sharpened so letters stay crisp.
 *
 * Without a rasterizer (plain unit tests) {@link #line} returns null and callers fall back to
 * their bitmap font.
 */
public final class GlyphText {
    private GlyphText() {}

    /** Draws text white on black; the platform's part. */
    public interface Rasterizer {
        /**
         * {@code text} in one line whose font height (ascent + descent) is {@code heightPx},
         * as coverage 0..1, {@code width * heightPx} row-major. Width is what the text needs.
         */
        Line rasterize(String text, int heightPx);
    }

    /** One line of text as LED coverage 0..1, row-major. */
    public static final class Line {
        public final int width, height;
        public final float[] alpha;

        public Line(int width, int height, float[] alpha) {
            this.width = width;
            this.height = height;
            this.alpha = alpha;
        }
    }

    /** How much larger the font engine draws than the matrix (then boxed down). */
    static final int OVERSAMPLE = 4;

    private static volatile Rasterizer sRasterizer;

    private static final Map<String, Line> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Line> e) {
            return size() > 24;
        }
    };

    public static void setRasterizer(Rasterizer r) {
        sRasterizer = r;
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    public static boolean available() {
        return sRasterizer != null;
    }

    /** {@code text} as a line {@code heightPx} LEDs tall, or null without a rasterizer. */
    public static Line line(String text, int heightPx) {
        Rasterizer r = sRasterizer;
        if (r == null || text == null || text.isEmpty()) return null;
        String key = heightPx + "|" + text;
        synchronized (CACHE) {
            Line cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Line big = r.rasterize(text, heightPx * OVERSAMPLE);
        if (big == null) return null;
        Line l = downsample(big, OVERSAMPLE);
        synchronized (CACHE) {
            CACHE.put(key, l);
        }
        return l;
    }

    /** Box filter: each LED is the average of its s x s samples. */
    static Line downsample(Line big, int s) {
        int w = (big.width + s - 1) / s, h = (big.height + s - 1) / s;
        float[] a = new float[w * h];
        for (int y = 0; y < big.height; y++) {
            for (int x = 0; x < big.width; x++) {
                a[(y / s) * w + x / s] += big.alpha[y * big.width + x];
            }
        }
        for (int i = 0; i < a.length; i++) a[i] /= s * s;
        // Trim empty columns on both sides, so the width is the ink's.
        int left = 0, right = w - 1;
        while (left < w && columnEmpty(a, w, h, left)) left++;
        while (right > left && columnEmpty(a, w, h, right)) right--;
        if (left > right) return new Line(0, h, new float[0]);
        int tw = right - left + 1;
        float[] t = new float[tw * h];
        for (int y = 0; y < h; y++) System.arraycopy(a, y * w + left, t, y * tw, tw);
        return new Line(tw, h, t);
    }

    private static boolean columnEmpty(float[] a, int w, int h, int x) {
        for (int y = 0; y < h; y++) if (a[y * w + x] > 0.04f) return false;
        return true;
    }

    /**
     * Lights the line with its top-left at (x, y). Coverage goes through a curve: strokes that
     * cover most of an LED light it fully, slivers fade out, so letters read at 25 x 25.
     */
    public static void draw(Frame f, Line l, int x, int y, int value) {
        for (int j = 0; j < l.height; j++) {
            for (int i = 0; i < l.width; i++) {
                float a = l.alpha[j * l.width + i];
                if (a < 0.06f) continue;
                double t = Math.min(1, Math.max(0, (a - 0.1) / 0.5));
                t = t * t * (3 - 2 * t);
                if (t > 0) f.max(x + i, y + j, (int) Math.round(value * t));
            }
        }
    }
}
