package org.openglyph.toys;

import org.openglyph.core.Frame;

/** One picture the Glyph Button can cycle to. */
public interface Toy {
    /** Values the Android side fills in; toys never call Android APIs themselves. */
    final class Context {
        public int batteryPercent;
        public boolean charging;
        public int hour;   // 0..23
        public int minute; // 0..59
    }

    /** Stable id, used for the per-toy setting (open_glyph_toy_<id>). */
    String id();

    void render(Frame frame, long nowMs, Context ctx);

    /** Long press while this toy is shown. */
    default void onLongPress(long nowMs) {}

    /** While true the host keeps the toy on screen past its idle timeout. */
    default boolean keepsScreenOn() {
        return false;
    }

    /** Milliseconds until the picture changes on its own (-1: only on new data). */
    default long frameIntervalMs() {
        return 1000;
    }
}
