package org.openglyph.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A Linux LED class device blinked by the kernel's "timer" trigger, so no thread of ours has
 * to toggle it. Used for the red camera recording LED (/sys/class/leds/red).
 *
 * Only the trigger is required: ueventd hands it to the system user. delay_on/delay_off are
 * created by the kernel only when the timer trigger is chosen, after ueventd ran, so they are
 * root-owned and writing them may fail; the kernel's default period (500/500 ms) is used then.
 * Brightness stays root-owned and is not needed: the timer trigger blinks at max_brightness
 * when brightness is 0, and removing the trigger switches the LED off.
 */
public final class TimerLed {
    public static final String RECORD_LED_DIR = "/sys/class/leds/red";

    private final File trigger;
    private final File delayOn;
    private final File delayOff;
    private boolean blinking;

    public TimerLed(String dir) {
        trigger = new File(dir, "trigger");
        delayOn = new File(dir, "delay_on");
        delayOff = new File(dir, "delay_off");
    }

    public boolean isBlinking() {
        return blinking;
    }

    public void blink(int onMs, int offMs) throws IOException {
        // Selecting the trigger creates delay_on/delay_off and starts with the default period,
        // so it has to come first.
        write(trigger, "timer");
        blinking = true; // the kernel blinks from here on, whatever the period writes do
        try {
            write(delayOn, Integer.toString(Math.max(1, onMs)));
            write(delayOff, Integer.toString(Math.max(1, offMs)));
        } catch (IOException e) {
            // Root-owned (see above): keep the default period.
        }
    }

    public void off() throws IOException {
        blinking = false;
        write(trigger, "none");
    }

    private static void write(File node, String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(node)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
