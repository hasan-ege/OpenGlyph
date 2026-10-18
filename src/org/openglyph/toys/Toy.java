package org.openglyph.toys;

import org.openglyph.core.Frame;

/** One picture the Glyph Button can cycle to. */
public interface Toy {
    /** {@link #sensors()} flags: what the host must feed into {@link Context} while shown. */
    int SENSOR_GRAVITY = 1;
    int SENSOR_HEADING = 2;
    int SENSOR_CAMERA = 4;

    /** Values the Android side fills in; toys never call Android APIs themselves. */
    final class Context {
        public int batteryPercent;
        public boolean charging;
        public int hour;   // 0..23
        public int minute; // 0..59
        public int second; // 0..59
        /** Wall clock, for the sun and the moon. */
        public long epochMs;
        /** Local time offset from UTC in hours (e.g. 3 for Istanbul). */
        public double utcOffsetHours;
        /** Last known location in degrees, or NaN when unknown. */
        public double latitude = Double.NaN;
        public double longitude = Double.NaN;
        /** Gravity in m/s^2 in device axes (x right, y up, z out of the screen). */
        public boolean hasGravity;
        public float gx, gy, gz;
        /** Compass heading of the top of the phone, degrees clockwise from north. */
        public boolean hasHeading;
        public float heading;
        /** Rear camera picture as a 625 grid (0..Frame.MAX), or null. */
        public int[] camera;
    }

    /** Stable id, used for the toy order setting. */
    String id();

    void render(Frame frame, long nowMs, Context ctx);

    /** Long press while this toy is shown. */
    default void onLongPress(long nowMs) {}

    /** Glyph Button pressed / released while this toy is shown. */
    default void onButtonDown(long nowMs) {}

    default void onButtonUp(long nowMs) {}

    /** Selected / deselected on the Glyph Button (SDK toys bind / unbind their service here). */
    default void onSelected() {}

    default void onDeselected() {}

    /** While true the host keeps the toy on screen past its idle timeout. */
    default boolean keepsScreenOn(long nowMs) {
        return false;
    }

    /** How long the toy stays on screen after the last Glyph Button press. */
    default long timeoutMs() {
        return ToyHost.IDLE_MS;
    }

    /** Milliseconds until the picture changes on its own (-1: only on new data). */
    default long frameIntervalMs() {
        return 1000;
    }

    /** {@link #SENSOR_GRAVITY} etc.: data the toy needs while it is on screen. */
    default int sensors() {
        return 0;
    }

    /** Can be the always-on Glyph Toy (drawn dimmed while the screen is off). */
    default boolean aodSupported() {
        return false;
    }
}
