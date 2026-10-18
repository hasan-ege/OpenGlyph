package org.openglyph.toys;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * Shows toys on the Glyph Button. First tap shows the current toy, further taps (while it is on
 * screen) move to the next enabled one, long press goes to the toy. The toy disappears
 * {@link #IDLE_MS} after the last press unless it keeps the screen on (running stopwatch).
 */
public final class ToyHost implements Effect {
    public static final long IDLE_MS = 5000;

    private final List<Toy> all = new ArrayList<>();
    private final List<Toy> enabled = new ArrayList<>();
    private final Supplier<Toy.Context> context;
    private int index;
    private long visibleUntil = Long.MIN_VALUE;

    public ToyHost(Supplier<Toy.Context> context, Toy... toys) {
        this.context = context;
        for (Toy t : toys) all.add(t);
        enabled.addAll(all);
    }

    public synchronized List<Toy> allToys() {
        return new ArrayList<>(all);
    }

    /** Enables exactly the toys whose ids are given, keeping the built-in order. */
    public synchronized void setEnabled(java.util.Set<String> ids) {
        Toy current = current();
        enabled.clear();
        for (Toy t : all) if (ids.contains(t.id())) enabled.add(t);
        index = Math.max(0, enabled.indexOf(current));
    }

    public synchronized Toy current() {
        return enabled.isEmpty() ? null : enabled.get(Math.min(index, enabled.size() - 1));
    }

    public synchronized void onTap(long now) {
        if (enabled.isEmpty()) return;
        if (isVisible(now)) index = (index + 1) % enabled.size();
        visibleUntil = now + IDLE_MS;
    }

    public synchronized void onLongPress(long now) {
        Toy t = current();
        if (t == null) return;
        t.onLongPress(now);
        visibleUntil = now + IDLE_MS;
    }

    public synchronized void hide() {
        visibleUntil = Long.MIN_VALUE;
    }

    private boolean isVisible(long now) {
        Toy t = current();
        if (t == null) return false;
        return (visibleUntil != Long.MIN_VALUE && now < visibleUntil) || t.keepsScreenOn();
    }

    @Override public int priority() { return PRIORITY_TOY; }

    @Override public synchronized boolean isActive(long now) {
        return isVisible(now);
    }

    @Override public synchronized long render(Frame f, long now) {
        Toy t = current();
        if (t == null) return -1;
        t.render(f, now, context.get());
        long interval = t.frameIntervalMs();
        long untilIdle = visibleUntil - now;
        // Wake up for the idle timeout even when the picture is static.
        if (!t.keepsScreenOn() && untilIdle > 0) {
            return interval < 0 ? untilIdle : Math.min(interval, untilIdle);
        }
        return interval;
    }
}
