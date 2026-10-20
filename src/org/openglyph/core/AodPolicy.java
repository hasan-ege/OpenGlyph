package org.openglyph.core;

/** When the always-on Glyph clock is shown. Pure logic, so it can be tested off-device. */
public final class AodPolicy {
    public enum Mode {
        OFF, ALWAYS, FACE_DOWN, CHARGING;

        public static Mode parse(String s) {
            if (s == null) return OFF;
            try {
                return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OFF;
            }
        }
    }

    private AodPolicy() {}

    /** @param bedtime the bedtime schedule ({@link Bedtime}) is on right now */
    public static boolean shouldShow(Mode mode, boolean screenOff, boolean faceDown,
            boolean charging, boolean bedtime) {
        return shouldShow(mode, screenOff, faceDown, charging, bedtime, false);
    }

    /**
     * @param batteryLow the battery is low (Android's ACTION_BATTERY_LOW): as on the stock
     *     phone, always-on goes off until the battery recovers, unless it is charging
     */
    public static boolean shouldShow(Mode mode, boolean screenOff, boolean faceDown,
            boolean charging, boolean bedtime, boolean batteryLow) {
        if (!screenOff || bedtime) return false;
        if (batteryLow && !charging) return false;
        switch (mode) {
            case ALWAYS: return true;
            case FACE_DOWN: return faceDown;
            case CHARGING: return charging;
            default: return false;
        }
    }

    /** Face down = gravity mostly along -z (screen towards the table). */
    public static boolean isFaceDown(float ax, float ay, float az) {
        return az < -8.0f && Math.abs(ax) < 3.0f && Math.abs(ay) < 3.0f;
    }

    /**
     * The matrix is face down: the phone lies on its back, screen up. Stock toys go off then
     * (nobody can see them).
     */
    public static boolean isMatrixDown(float ax, float ay, float az) {
        return az > 8.0f && Math.abs(ax) < 3.0f && Math.abs(ay) < 3.0f;
    }

    /** Face up again, with hysteresis so a wobble at the threshold does not flip back and forth. */
    public static boolean isFaceUp(float ax, float ay, float az) {
        return az > -5.0f;
    }
}
