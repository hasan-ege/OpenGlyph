package org.openglyph;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.nio.ByteBuffer;

import org.openglyph.core.GlyphText;

/**
 * Text to Glyph on the phone: the system font (condensed, bold: narrow letters that stay
 * readable as dots) drawn with Android's own text engine, so every script and accent works.
 */
final class PaintTextRasterizer implements GlyphText.Rasterizer {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    PaintTextRasterizer() {
        mPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        mPaint.setColor(0xFFFFFFFF);
    }

    @Override
    public synchronized GlyphText.Line rasterize(String text, int heightPx) {
        // Size the font so ascent + descent is the line height.
        mPaint.setTextSize(100);
        Paint.FontMetrics m = mPaint.getFontMetrics();
        mPaint.setTextSize(100f * heightPx / (m.descent - m.ascent));
        m = mPaint.getFontMetrics();
        int w = Math.max(1, (int) Math.ceil(mPaint.measureText(text)) + 2);
        Bitmap b = Bitmap.createBitmap(w, heightPx, Bitmap.Config.ALPHA_8);
        try {
            new Canvas(b).drawText(text, 1, -m.ascent, mPaint);
            ByteBuffer buf = ByteBuffer.allocate(b.getRowBytes() * heightPx);
            b.copyPixelsToBuffer(buf);
            float[] a = new float[w * heightPx];
            int stride = b.getRowBytes();
            for (int y = 0; y < heightPx; y++) {
                for (int x = 0; x < w; x++) a[y * w + x] = (buf.get(y * stride + x) & 0xFF) / 255f;
            }
            return new GlyphText.Line(w, heightPx, a);
        } finally {
            b.recycle();
        }
    }
}
