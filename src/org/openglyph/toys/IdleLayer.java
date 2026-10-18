package org.openglyph.toys;

import java.util.function.Supplier;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * What the matrix shows when nothing else wants it: the always-on clock (dimmed) and/or a
 * small dot while an "essential" notification is waiting. Lowest priority, so notifications,
 * calls and the Glyph Button toys all draw over it.
 */
public final class IdleLayer implements Effect {
    /** Always-on clock brightness relative to full. */
    static final double AOD_LEVEL = 0.22;

    private final Toy clock = new Toys.Clock();
    private final Supplier<Toy.Context> context;
    private volatile boolean alwaysOn;
    private volatile boolean essential;

    public IdleLayer(Supplier<Toy.Context> context) {
        this.context = context;
    }

    public void setAlwaysOn(boolean on) {
        alwaysOn = on;
    }

    public void setEssential(boolean on) {
        essential = on;
    }

    public boolean alwaysOn() {
        return alwaysOn;
    }

    @Override public int priority() { return PRIORITY_IDLE; }

    @Override public boolean isActive(long now) {
        return alwaysOn || essential;
    }

    @Override public long render(Frame f, long now) {
        if (alwaysOn) {
            Frame lit = new Frame();
            clock.render(lit, now, context.get());
            for (int y = 0; y < MatrixLayout.HEIGHT; y++)
                for (int x = 0; x < MatrixLayout.WIDTH; x++)
                    if (lit.get(x, y) > 0) f.set(x, y, (int) (lit.get(x, y) * AOD_LEVEL));
        }
        if (essential) {
            // 3x3 dot at the bottom of the matrix, under the clock's minutes.
            for (int y = 21; y <= 23; y++)
                for (int x = 11; x <= 13; x++) f.set(x, y, Frame.MAX / 2);
        }
        // The clock changes once a minute; the service also invalidates on its minute alarm.
        return alwaysOn ? 1000 : -1;
    }
}
