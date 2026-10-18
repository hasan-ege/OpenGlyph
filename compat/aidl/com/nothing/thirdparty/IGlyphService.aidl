package com.nothing.thirdparty;

/**
 * Interface that apps built with Nothing's Glyph Matrix SDK talk to, re-declared from the
 * binder interface (descriptor and method order) for interoperability; see
 * docs/nothing-sdk-interface.md. The order of the methods defines the transaction codes
 * (1..10) and must not change.
 */
interface IGlyphService {
    void setFrameColors(in int[] colors);
    void openSession();
    void closeSession();
    boolean register(String target);
    boolean registerSDK(String target, String version);
    boolean registerMatrixSDK(String target);
    void setMatrixColors(in int[] colors);
    void setGlyphMatrixTimeout(boolean enable);
    void setAppMatrixColors(in int[] colors);
    void closeAppMatrix();
}
