package org.openglyph.core;

/**
 * Something that wants the matrix. Only the highest-priority active effect is drawn, so an
 * incoming call hides a notification pulse, which hides a toy.
 */
public interface Effect {
    int PRIORITY_IDLE = 5;  // always-on clock / essential-notification dot
    int PRIORITY_TOY = 10;
    int PRIORITY_CHARGING = 30;
    int PRIORITY_NOTIFICATION = 50;
    int PRIORITY_CALL = 90;

    int priority();

    boolean isActive(long nowMs);

    /**
     * Draws into a cleared frame.
     *
     * @return milliseconds until the next frame is needed, or -1 if the picture is static
     *     and should only be redrawn when {@link Renderer#invalidate()} is called.
     */
    long render(Frame frame, long nowMs);
}
