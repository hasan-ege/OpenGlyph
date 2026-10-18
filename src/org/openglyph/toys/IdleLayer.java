package org.openglyph.toys;

import java.util.function.Supplier;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * What the matrix shows when nothing else wants it: the always-on toy (dimmed), or the icon
 * of a waiting Essential Notification, which takes its place until it is dismissed. Lowest priority, so notifications,
 * calls and the Glyph Button toys all draw over it.
 */
public final class IdleLayer implements Effect {
    /** Always-on clock brightness relative to full. */
    static final double AOD_LEVEL = 0.22;

    private final Toy clock = new Toys.Clock();
    /** The built-in always-on toy (clock or moon phase). */
    private volatile Toy builtIn = clock;
    private final Supplier<Toy.Context> context;
    private volatile boolean alwaysOn;
    /** Essential notification icon (625 grid), or null. */
    private volatile int[] essential;
    static final double ESSENTIAL_LEVEL = 0.4;
    /** An SDK toy chosen as the always-on toy; its frames replace the clock. */
    private volatile RemoteToy remote;
    private final int[] remoteFrame = new int[MatrixLayout.GRID_SIZE];

    public IdleLayer(Supplier<Toy.Context> context) {
        this.context = context;
    }

    public void setAlwaysOn(boolean on) {
        alwaysOn = on;
    }

    public void setEssential(int[] icon) {
        essential = icon == null ? null : icon.clone();
    }

    /** A built-in toy for always-on (null: the clock). */
    public void setBuiltInToy(Toy toy) {
        builtIn = toy == null ? clock : toy;
    }

    public void setRemoteToy(RemoteToy toy) {
        remote = toy;
    }

    public boolean alwaysOn() {
        return alwaysOn;
    }

    @Override public int priority() { return PRIORITY_IDLE; }

    @Override public boolean isActive(long now) {
        return alwaysOn || essential != null;
    }

    @Override public long render(Frame f, long now) {
        int[] icon = essential;
        if (icon != null) {
            for (int y = 0; y < MatrixLayout.HEIGHT; y++)
                for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                    int v = icon[y * MatrixLayout.WIDTH + x];
                    if (v > 0) f.set(x, y, (int) (v * ESSENTIAL_LEVEL));
                }
            return alwaysOn ? 1000 : -1;
        }
        RemoteToy r = remote;
        if (alwaysOn && r != null && r.copyFrame(remoteFrame)) {
            // AOD toys draw for the always-on display already; dim them less than the clock.
            for (int i = 0; i < remoteFrame.length; i++) remoteFrame[i] = (int) (remoteFrame[i] * 0.5);
            f.setGrid(remoteFrame);
        } else if (alwaysOn) {
            Frame lit = new Frame();
            builtIn.render(lit, now, context.get());
            for (int y = 0; y < MatrixLayout.HEIGHT; y++)
                for (int x = 0; x < MatrixLayout.WIDTH; x++)
                    if (lit.get(x, y) > 0) f.set(x, y, (int) (lit.get(x, y) * AOD_LEVEL));
        }
        // The clock changes once a minute; the service also invalidates on its minute alarm.
        return alwaysOn ? 1000 : -1;
    }
}
