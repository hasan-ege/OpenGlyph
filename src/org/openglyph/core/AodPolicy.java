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

    /**
     * @param nightStart hour the quiet period starts (0..23), or -1 for no quiet period
     * @param nightEnd   hour it ends (0..23); may be before nightStart (crosses midnight)
     */
    public static boolean shouldShow(Mode mode, boolean screenOff, boolean faceDown,
            boolean charging, int hour, int nightStart, int nightEnd) {
        if (!screenOff) return false;
        if (isNight(hour, nightStart, nightEnd)) return false;
        switch (mode) {
            case ALWAYS: return true;
            case FACE_DOWN: return faceDown;
            case CHARGING: return charging;
            default: return false;
        }
    }

    public static boolean isNight(int hour, int start, int end) {
        if (start < 0 || end < 0 || start == end) return false;
        return start < end ? hour >= start && hour < end : hour >= start || hour < end;
    }

    /** Face down = gravity mostly along -z (screen towards the table). */
    public static boolean isFaceDown(float ax, float ay, float az) {
        return az < -8.0f && Math.abs(ax) < 3.0f && Math.abs(ay) < 3.0f;
    }

    /** Face up again, with hysteresis so a wobble at the threshold does not flip back and forth. */
    public static boolean isFaceUp(float ax, float ay, float az) {
        return az > -5.0f;
    }
}
