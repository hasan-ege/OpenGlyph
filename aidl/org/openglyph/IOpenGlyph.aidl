package org.openglyph;

/**
 * Public API of the OpenGlyph service. Bind with action "org.openglyph.BIND" on package
 * "org.openglyph"; callers need org.openglyph.permission.CONTROL_MATRIX.
 */
interface IOpenGlyph {
    /** Grid size: 25 x 25. Only the round area has LEDs, see hasLed(). */
    int getWidth();
    int getHeight();
    boolean hasLed(int x, int y);

    /**
     * Shows a picture until clearFrame() or the caller dies.
     *
     * @param token any binder owned by the caller; identifies its layer.
     * @param grid  625 row-major brightness values, 0..4095.
     * @param priority 0..80. System effects: charging 30, notification 50, call 90.
     */
    void showFrame(IBinder token, in int[] grid, int priority);

    void clearFrame(IBinder token);

    /**
     * Used by OpenGlyphCompat (com.nothing.thirdparty) for apps built with Nothing's Glyph Matrix
     * SDK. Frames from a package that provides an SDK toy go to that toy; others are shown as an
     * app frame until clearPackageFrame().
     *
     * @param grid 625 row-major values, 0..4095
     */
    void showPackageFrame(String pkg, in int[] grid);

    void clearPackageFrame(String pkg);

    /** setGlyphMatrixTimeout() from an SDK toy: false keeps the toy on the matrix. */
    void setPackageTimeout(String pkg, boolean timeout);
}
