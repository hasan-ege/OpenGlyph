package org.openglyph;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/** Draws a 625-entry Glyph Matrix grid the way the matrix looks: dots in a black disc. */
final class MatrixArt {
    static final int DISC = 0xff000000;
    static final int LED_OFF = 0xff1f1f1f;
    static final int LED_ON = 0xffffffff;

    private MatrixArt() {}

    static void draw(Canvas c, Rect bounds, int[] grid, Paint paint) {
        float size = Math.min(bounds.width(), bounds.height());
        float cx = bounds.exactCenterX(), cy = bounds.exactCenterY();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(DISC);
        c.drawCircle(cx, cy, size / 2f, paint);
        // 25 LEDs across 88% of the disc, so the round edge keeps a small margin.
        float cell = size * 0.88f / MatrixLayout.WIDTH;
        float left = cx - cell * MatrixLayout.WIDTH / 2f, top = cy - cell * MatrixLayout.HEIGHT / 2f;
        float r = cell * 0.36f;
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                if (!MatrixLayout.exists(x, y)) continue;
                int v = grid == null ? 0 : grid[y * MatrixLayout.WIDTH + x];
                paint.setColor(blend(Math.max(0, Math.min(Frame.MAX, v)) / (float) Frame.MAX));
                c.drawCircle(left + (x + 0.5f) * cell, top + (y + 0.5f) * cell, r, paint);
            }
        }
    }

    /** Off grey to white; gamma so dim values (always-on, 20%) stay visible on screen. */
    private static int blend(float t) {
        if (t <= 0) return LED_OFF;
        t = (float) Math.pow(t, 0.6);
        int off = Color.red(LED_OFF);
        int v = (int) (off + (255 - off) * t);
        return Color.rgb(v, v, v);
    }

    /** A grid as a Drawable, for preference icons and lists. */
    static final class GridDrawable extends Drawable {
        private final int[] grid;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridDrawable(int[] grid, int sizePx) {
            this.grid = grid;
            this.size = sizePx;
        }

        @Override public void draw(Canvas canvas) {
            MatrixArt.draw(canvas, getBounds(), grid, paint);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** An SDK toy's own preview image, cut to the round matrix shape. */
    static final class RoundDrawable extends Drawable {
        private final Drawable inner;
        private final int size;
        private final android.graphics.Path clip = new android.graphics.Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RoundDrawable(Drawable inner, int sizePx) {
            this.inner = inner;
            this.size = sizePx;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setColor(DISC);
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()) / 2f, paint);
            clip.reset();
            clip.addCircle(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()) / 2f,
                    android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            inner.setBounds(b);
            inner.draw(canvas);
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { inner.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
