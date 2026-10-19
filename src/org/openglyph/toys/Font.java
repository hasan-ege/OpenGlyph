package org.openglyph.toys;

import org.openglyph.core.Frame;

/** Digit, 7-segment and 3x5 text fonts for the 25x25 matrix. */
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

    // 7-segment digits, 5 wide x 9 tall: segments a b c d e f g.
    private static final int[] SEG7 = {
        0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
        0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011,
    };
    static final int SEG_W = 5, SEG_H = 9;

    /** One 7-segment digit with its top-left corner at (x, y). */
    static void segDigit(Frame f, int d, int x, int y, int value) {
        int s = SEG7[d];
        if ((s & 0b1000000) != 0) for (int i = 1; i <= 3; i++) f.set(x + i, y, value);         // a
        if ((s & 0b0100000) != 0) for (int i = 1; i <= 3; i++) f.set(x + 4, y + i, value);     // b
        if ((s & 0b0010000) != 0) for (int i = 5; i <= 7; i++) f.set(x + 4, y + i, value);     // c
        if ((s & 0b0001000) != 0) for (int i = 1; i <= 3; i++) f.set(x + i, y + 8, value);     // d
        if ((s & 0b0000100) != 0) for (int i = 5; i <= 7; i++) f.set(x, y + i, value);         // e
        if ((s & 0b0000010) != 0) for (int i = 1; i <= 3; i++) f.set(x, y + i, value);         // f
        if ((s & 0b0000001) != 0) for (int i = 1; i <= 3; i++) f.set(x + i, y + 4, value);     // g
    }

    /** Two 7-segment digits centred horizontally, top at y. */
    static void segPairCentred(Frame f, int number, int y, int value) {
        int x = (25 - (2 * SEG_W + 1)) / 2;
        segDigit(f, (number / 10) % 10, x, y, value);
        segDigit(f, number % 10, x + SEG_W + 1, y, value);
    }

    // 3x5 upper-case text, for short messages (Magic 8 Ball, scores).
    private static final String TEXT_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.?!-'+";
    private static final String[][] TEXT_ROWS = {
        {".#.", "#.#", "###", "#.#", "#.#"}, {"##.", "#.#", "##.", "#.#", "##."},
        {".##", "#..", "#..", "#..", ".##"}, {"##.", "#.#", "#.#", "#.#", "##."},
        {"###", "#..", "##.", "#..", "###"}, {"###", "#..", "##.", "#..", "#.."},
        {".##", "#..", "#.#", "#.#", ".##"}, {"#.#", "#.#", "###", "#.#", "#.#"},
        {"###", ".#.", ".#.", ".#.", "###"}, {"..#", "..#", "..#", "#.#", ".#."},
        {"#.#", "#.#", "##.", "#.#", "#.#"}, {"#..", "#..", "#..", "#..", "###"},
        {"#.#", "###", "###", "#.#", "#.#"}, {"##.", "#.#", "#.#", "#.#", "#.#"},
        {".#.", "#.#", "#.#", "#.#", ".#."}, {"##.", "#.#", "##.", "#..", "#.."},
        {".#.", "#.#", "#.#", "##.", ".##"}, {"##.", "#.#", "##.", "#.#", "#.#"},
        {".##", "#..", ".#.", "..#", "##."}, {"###", ".#.", ".#.", ".#.", ".#."},
        {"#.#", "#.#", "#.#", "#.#", "###"}, {"#.#", "#.#", "#.#", "#.#", ".#."},
        {"#.#", "#.#", "###", "###", "#.#"}, {"#.#", "#.#", ".#.", "#.#", "#.#"},
        {"#.#", "#.#", ".#.", ".#.", ".#."}, {"###", "..#", ".#.", "#..", "###"},
        {"###", "#.#", "#.#", "#.#", "###"}, {".#.", "##.", ".#.", ".#.", "###"},
        {"###", "..#", "###", "#..", "###"}, {"###", "..#", "###", "..#", "###"},
        {"#.#", "#.#", "###", "..#", "..#"}, {"###", "#..", "###", "..#", "###"},
        {"###", "#..", "###", "#.#", "###"}, {"###", "..#", ".#.", ".#.", ".#."},
        {"###", "#.#", "###", "#.#", "###"}, {"###", "#.#", "###", "..#", "###"},
        {"...", "...", "...", "...", ".#."}, {"##.", "..#", ".#.", "...", ".#."},
        {".#.", ".#.", ".#.", "...", ".#."}, {"...", "...", "###", "...", "..."},
        {".#.", ".#.", "...", "...", "..."}, {"...", ".#.", "###", ".#.", "..."},
    };

    /** Letters the 3x5 font lacks, as their nearest plain letter (Turkish and Latin accents). */
    static String plain(String s) {
        String n = java.text.Normalizer.normalize(s.replace("ı", "i").replace("İ", "I"),
                java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** {@link #text} at twice the size (6x10 per letter, 8 px apart). */
    static void bigText(Frame f, String s, int x, int y, int value) {
        for (int i = 0; i < s.length(); i++) {
            int k = TEXT_CHARS.indexOf(Character.toUpperCase(s.charAt(i)));
            if (k < 0) continue;
            String[] rows = TEXT_ROWS[k];
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length(); c++) {
                    if (rows[r].charAt(c) != '#') continue;
                    int px = x + i * 8 + c * 2, py = y + r * 2;
                    for (int d = 0; d < 4; d++) f.set(px + (d & 1), py + (d >> 1), value);
                }
            }
        }
    }

    /** Width in pixels of text drawn with {@link #text}. */
    static int textWidth(String s) {
        return s.isEmpty() ? 0 : s.length() * 4 - 1;
    }

    /** 3x5 text with its top-left corner at (x, y); unknown characters are blank. */
    static void text(Frame f, String s, int x, int y, int value) {
        for (int i = 0; i < s.length(); i++) {
            int k = TEXT_CHARS.indexOf(Character.toUpperCase(s.charAt(i)));
            if (k >= 0) glyph(f, TEXT_ROWS[k], x + i * 4, y, value);
        }
    }

    static void textCentred(Frame f, String s, int y, int value) {
        text(f, s, (25 - textWidth(s)) / 2, y, value);
    }
}
