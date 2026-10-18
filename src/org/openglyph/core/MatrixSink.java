package org.openglyph.core;

import java.io.IOException;

/** Where finished frames go: the real sysfs driver, or a fake in tests. */
public interface MatrixSink {
    /** @param leds exactly {@link MatrixLayout#LED_COUNT} hardware values (0..2047). */
    void show(int[] leds) throws IOException;

    /** Blank the matrix and cut its power. */
    void off() throws IOException;
}
