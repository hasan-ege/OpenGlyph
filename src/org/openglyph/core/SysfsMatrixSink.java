package org.openglyph.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Drives the matrix-leds kernel driver through its sysfs nodes.
 *
 * - frame_brightness: 489 space-separated values (0..2047) show one picture. The driver
 *   parses into a fixed 489-entry stack array without a bounds check, so this class must
 *   never write more than 489 values.
 * - led_power_on: 1/0 switches the LED supply. frame_brightness does not power on by itself.
 * - all_brightness: 0 blanks every LED and powers off.
 */
public final class SysfsMatrixSink implements MatrixSink {
    /** Real node, as labelled in the device tree's genfs_contexts. */
    public static final String DEVICE_DIR = "/sys/devices/platform/soc/8c0000.qcom,qupv3_2_geni_se/"
            + "888000.spi/spi_master/spi10/spi10.0/leds/matrix-leds";
    /** Class symlink; needs extra sepolicy to follow, so it is only the fallback. */
    public static final String CLASS_DIR = "/sys/class/leds/matrix-leds";
    public static final int HW_MAX = 2047;

    public static String findDir() {
        return new File(DEVICE_DIR).isDirectory() ? DEVICE_DIR : CLASS_DIR;
    }

    private final File frameNode;
    private final File powerNode;
    private final File allNode;
    private boolean powered;
    private final StringBuilder sb = new StringBuilder(MatrixLayout.LED_COUNT * 5);

    public SysfsMatrixSink(String dir) {
        frameNode = new File(dir, "frame_brightness");
        powerNode = new File(dir, "led_power_on");
        allNode = new File(dir, "all_brightness");
    }

    @Override
    public void show(int[] leds) throws IOException {
        if (leds.length != MatrixLayout.LED_COUNT) {
            throw new IllegalArgumentException("expected " + MatrixLayout.LED_COUNT + " LEDs");
        }
        if (!powered) {
            write(powerNode, "1");
            powered = true;
        }
        write(frameNode, format(leds));
    }

    @Override
    public void off() throws IOException {
        write(allNode, "0");
        powered = false;
    }

    String format(int[] leds) {
        sb.setLength(0);
        for (int i = 0; i < leds.length; i++) {
            if (i > 0) sb.append(' ');
            int v = leds[i];
            sb.append(v < 0 ? 0 : Math.min(v, HW_MAX));
        }
        return sb.toString();
    }

    private static void write(File node, String value) throws IOException {
        // One write() call per value: sysfs store handlers see exactly one buffer.
        try (FileOutputStream out = new FileOutputStream(node)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
