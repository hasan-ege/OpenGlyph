package org.openglyph;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * An app's launcher icon as a Glyph Matrix picture: the themed (monochrome) layer when the app
 * has one, else the adaptive foreground, else the whole icon; brightness from luminance x alpha.
 */
final class AppIconMatrix {
    private static final int SCALE = 4; // render at 100x100, then average 4x4 blocks

    private AppIconMatrix() {}

    static int[] render(Context context, String pkg) {
        Drawable icon;
        try {
            icon = context.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        return render(icon);
    }

    static int[] render(Drawable icon) {
        int size = MatrixLayout.WIDTH * SCALE;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Drawable layer = icon;
        boolean adaptive = false;
        if (icon instanceof AdaptiveIconDrawable a) {
            layer = a.getMonochrome() != null ? a.getMonochrome() : a.getForeground();
            adaptive = layer != null;
            if (layer == null) layer = icon;
        }
        if (adaptive) {
            // Adaptive layers are 108dp with the visible part in the middle 72dp.
            int pad = size / 4;
            layer.setBounds(-pad, -pad, size + pad, size + pad);
        } else {
            int inset = size / 10;
            layer.setBounds(inset, inset, size - inset, size - inset);
        }
        layer.draw(canvas);

        double[] lum = new double[MatrixLayout.GRID_SIZE];
        double max = 0;
        int[] px = new int[size * size];
        bmp.getPixels(px, 0, size, 0, 0, size, size);
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                if (!MatrixLayout.exists(x, y)) continue;
                double sum = 0;
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        int c = px[(y * SCALE + dy) * size + x * SCALE + dx];
                        double l = (0.2126 * Color.red(c) + 0.7152 * Color.green(c)
                                + 0.0722 * Color.blue(c)) / 255.0;
                        // Monochrome layers are drawn black on transparent: alpha is the shape.
                        sum += Color.alpha(c) / 255.0 * (adaptive ? 1.0 : Math.max(l, 0.15));
                    }
                }
                lum[y * MatrixLayout.WIDTH + x] = sum / (SCALE * SCALE);
                max = Math.max(max, lum[y * MatrixLayout.WIDTH + x]);
            }
        }
        bmp.recycle();
        if (max <= 0.05) return null;
        int[] grid = new int[MatrixLayout.GRID_SIZE];
        for (int i = 0; i < grid.length; i++) {
            double v = lum[i] / max;
            grid[i] = v < 0.25 ? 0 : (int) (Frame.MAX * v);
        }
        return grid;
    }
}
