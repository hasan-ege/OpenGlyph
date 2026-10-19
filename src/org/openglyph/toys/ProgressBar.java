package org.openglyph.toys;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * Glyph Progress: a Live Update's progress (delivery, ride, download, timer...) as its
 * percentage over a slider that fills left to right. Below toys, so a Glyph Button press still
 * shows them; an indeterminate one shows a light moving along the empty slider.
 */
public final class ProgressBar implements Effect {
    private static final int X0 = 3, X1 = 21, Y0 = 13, Y1 = 16;
    private volatile int percent = -1;
    private volatile boolean indeterminate;

    /** 0..100, or -1 for none. */
    public void set(int percent, boolean indeterminate) {
        this.percent = percent;
        this.indeterminate = indeterminate;
    }

    @Override public int priority() { return PRIORITY_TOY - 2; }

    @Override public boolean isActive(long now) {
        return percent >= 0;
    }

    @Override public long render(Frame f, long now) {
        int p = percent;
        if (p < 0) return -1;
        int dim = Frame.MAX / 6;
        int w = X1 - X0 + 1;
        int fill = indeterminate ? 0 : Math.round(w * p / 100f);
        int sweep = indeterminate ? (int) ((now / 60) % (w + 4)) - 2 : Integer.MIN_VALUE;
        for (int y = Y0; y <= Y1; y++) {
            for (int x = X0; x <= X1; x++) {
                if (!MatrixLayout.exists(x, y)) continue;
                boolean lit = x - X0 < fill || Math.abs(x - X0 - sweep) <= 1;
                f.set(x, y, lit ? Frame.MAX : dim);
            }
        }
        if (!indeterminate) Font.smallNumberCentred(f, Integer.toString(p), 5, Frame.MAX);
        return indeterminate ? 60 : 1000;
    }
}
