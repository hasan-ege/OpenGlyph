package org.openglyph.toys;

import java.util.function.Supplier;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * A toy that draws a live effect: the visualiser, the call voice, Glyph Progress. It can be picked
 * with the Glyph Button like any toy, or put on the matrix by an automation. Its source (audio
 * capture and the like) runs only while it is on the matrix: {@code onShow} / {@code onHide}.
 */
public final class EffectToy implements Toy {
    private final String id;
    private final Supplier<Effect> effect;
    private final Runnable onShow, onHide;
    private volatile long interval = 33;

    public EffectToy(String id, Supplier<Effect> effect, Runnable onShow, Runnable onHide) {
        this.id = id;
        this.effect = effect;
        this.onShow = onShow;
        this.onHide = onHide;
    }

    @Override public String id() { return id; }

    @Override public void render(Frame frame, long nowMs, Context ctx) {
        long next = effect.get().render(frame, nowMs);
        interval = next < 0 ? 1000 : next;
    }

    @Override public long frameIntervalMs() {
        return interval;
    }

    @Override public void onSelected() {
        if (onShow != null) onShow.run();
    }

    @Override public void onDeselected() {
        if (onHide != null) onHide.run();
    }
}
