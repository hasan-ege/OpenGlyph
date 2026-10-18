package org.openglyph.toys;

import org.openglyph.core.Frame;

/** 3x5 and 5x7 digit fonts for the 25x25 matrix. */
final class Font {
    private Font() {}

    // 5x7 digits, rows top to bottom, '#' = lit.
    private static final String[][] BIG = {
        {".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."}, // 0
        {"..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."}, // 1
        {".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"}, // 2
        {"#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."}, // 3
        {"...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."}, // 4
        {"#####", "#....", "####.", "....#", "....#", "#...#", ".###."}, // 5
        {"..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."}, // 6
        {"#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."}, // 7
        {".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."}, // 8
        {".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."}, // 9
    };

    // 3x5 digits.
    private static final String[][] SMALL = {
        {"###", "#.#", "#.#", "#.#", "###"}, {".#.", "##.", ".#.", ".#.", "###"},
        {"###", "..#", "###", "#..", "###"}, {"###", "..#", "###", "..#", "###"},
        {"#.#", "#.#", "###", "..#", "..#"}, {"###", "#..", "###", "..#", "###"},
        {"###", "#..", "###", "#.#", "###"}, {"###", "..#", ".#.", ".#.", ".#."},
        {"###", "#.#", "###", "#.#", "###"}, {"###", "#.#", "###", "..#", "###"},
    };

    static final int BIG_W = 5, BIG_H = 7, SMALL_W = 3, SMALL_H = 5;

    static void bigDigit(Frame f, int d, int x, int y, int value) {
        glyph(f, BIG[d], x, y, value);
    }

    static void smallDigit(Frame f, int d, int x, int y, int value) {
        glyph(f, SMALL[d], x, y, value);
    }

    /** Draws a number with big digits, centred horizontally on the matrix. */
    static void bigNumberCentred(Frame f, String digits, int y, int value) {
        int w = digits.length() * (BIG_W + 1) - 1;
        int x = (25 - w) / 2;
        for (int i = 0; i < digits.length(); i++) {
            bigDigit(f, digits.charAt(i) - '0', x + i * (BIG_W + 1), y, value);
        }
    }

    static void smallNumberCentred(Frame f, String digits, int y, int value) {
        int w = digits.length() * (SMALL_W + 1) - 1;
        int x = (25 - w) / 2;
        for (int i = 0; i < digits.length(); i++) {
            smallDigit(f, digits.charAt(i) - '0', x + i * (SMALL_W + 1), y, value);
        }
    }

    private static void glyph(Frame f, String[] rows, int x, int y, int value) {
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                if (rows[r].charAt(c) == '#') f.set(x + c, y + r, value);
            }
        }
    }
}
