package org.openglyph.core;

import java.util.Arrays;

/**
 * A 25x25 canvas of normalised brightness values (0..MAX). Pixels outside the round
 * matrix are kept but never reach the hardware.
 */
public final class Frame {
    /** Same normalisation the driver uses for stream frames (MAX_NORMALIZATION_BRIGHTNESS). */
    public static final int MAX = 4095;

    final int[] px = new int[MatrixLayout.GRID_SIZE];

    public void clear() {
        Arrays.fill(px, 0);
    }

    public void fill(int value) {
        Arrays.fill(px, clamp(value));
    }

    public void set(int x, int y, int value) {
        if (x < 0 || x >= MatrixLayout.WIDTH || y < 0 || y >= MatrixLayout.HEIGHT) return;
        px[y * MatrixLayout.WIDTH + x] = clamp(value);
    }

    /** Keeps the brighter of the existing and new value, for layering shapes. */
    public void max(int x, int y, int value) {
        if (x < 0 || x >= MatrixLayout.WIDTH || y < 0 || y >= MatrixLayout.HEIGHT) return;
        int i = y * MatrixLayout.WIDTH + x;
        px[i] = Math.max(px[i], clamp(value));
    }

    public int get(int x, int y) {
        return px[y * MatrixLayout.WIDTH + x];
    }

    /** Copies a 625-entry row-major grid, clamping each value. */
    /** Divides every pixel by {@code divisor} (to draw something readable on top). */
    public void dim(int divisor) {
        for (int i = 0; i < px.length; i++) px[i] /= divisor;
    }

    public void setGrid(int[] grid) {
        if (grid.length != MatrixLayout.GRID_SIZE) {
            throw new IllegalArgumentException("expected " + MatrixLayout.GRID_SIZE + " pixels");
        }
        for (int i = 0; i < grid.length; i++) px[i] = clamp(grid[i]);
    }

    public void copyFrom(Frame other) {
        System.arraycopy(other.px, 0, px, 0, px.length);
    }

    public boolean isDark() {
        for (int i = 0; i < MatrixLayout.LED_COUNT; i++) {
            if (px[MatrixLayout.gridIndex(i)] != 0) return false;
        }
        return true;
    }

    /** Debug view: '#' bright, '+' mid, '.' dim, ' ' off or no LED. */
    public String toAscii() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                int v = MatrixLayout.exists(x, y) ? get(x, y) : 0;
                sb.append(v == 0 ? ' ' : v < MAX / 3 ? '.' : v < 2 * MAX / 3 ? '+' : '#');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, MAX);
    }
}
