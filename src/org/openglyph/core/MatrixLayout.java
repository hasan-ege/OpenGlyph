package org.openglyph.core;

/**
 * Physical layout of the Nothing Phone (3) Glyph Matrix.
 *
 * The matrix is addressed as a 25x25 grid, but only 489 LEDs exist: each row is centred
 * and its length is taken from the factory test patterns in the matrix-leds driver
 * (drivers/leds/matrix-leds/matrix-leds.h, fac_test_pic). The driver expects the LEDs in
 * row-major order, top-left first.
 */
public final class MatrixLayout {
    public static final int WIDTH = 25;
    public static final int HEIGHT = 25;
    public static final int GRID_SIZE = WIDTH * HEIGHT;

    static final int[] ROW_LENGTHS = {
        7, 11, 15, 17, 19, 21, 21, 23, 23, 25, 25, 25, 25,
        25, 25, 25, 23, 23, 21, 21, 19, 17, 15, 11, 7,
    };

    public static final int LED_COUNT;

    /** grid index (y * WIDTH + x) -> LED index, or -1 where there is no LED. */
    private static final int[] GRID_TO_LED = new int[GRID_SIZE];
    /** LED index -> grid index. */
    private static final int[] LED_TO_GRID;

    static {
        java.util.Arrays.fill(GRID_TO_LED, -1);
        int count = 0;
        for (int len : ROW_LENGTHS) count += len;
        LED_COUNT = count;
        LED_TO_GRID = new int[count];

        int led = 0;
        for (int y = 0; y < HEIGHT; y++) {
            int len = ROW_LENGTHS[y];
            int start = (WIDTH - len) / 2;
            for (int x = start; x < start + len; x++) {
                GRID_TO_LED[y * WIDTH + x] = led;
                LED_TO_GRID[led] = y * WIDTH + x;
                led++;
            }
        }
    }

    private MatrixLayout() {}

    /** LEDs in column x (the matrix is round, so columns match rows). */
    public static int columnLength(int x) {
        return ROW_LENGTHS[x];
    }

    public static boolean exists(int x, int y) {
        return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && GRID_TO_LED[y * WIDTH + x] >= 0;
    }

    public static int ledIndex(int x, int y) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return -1;
        return GRID_TO_LED[y * WIDTH + x];
    }

    public static int gridIndex(int led) {
        return LED_TO_GRID[led];
    }

    /** Converts a 625-entry grid to the 489-entry LED order the driver expects. */
    public static void gridToLeds(int[] grid, int[] leds) {
        for (int i = 0; i < LED_COUNT; i++) leds[i] = grid[LED_TO_GRID[i]];
    }
}
