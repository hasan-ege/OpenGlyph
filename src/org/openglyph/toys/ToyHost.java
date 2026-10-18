package org.openglyph.toys;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;

/**
 * The Glyph Button toy queue, following the Glyph Matrix SDK's documented behaviour:
 *
 * - The first press selects the current toy and shows it; a press while it is shown selects and
 *   shows the next enabled toy. Long press goes to the selected toy ("change").
 * - Selection and visibility are separate. The selected toy stays selected (an SDK toy stays
 *   bound, so a timer keeps counting) until another toy is selected or it is disabled. It is
 *   shown for its {@link Toy#timeoutMs()} after the last press (default {@link #IDLE_MS}), or for
 *   as long as it keeps the screen on.
 */
public final class ToyHost implements Effect {
    public static final long IDLE_MS = 5000;

    private final List<Toy> all = new ArrayList<>();
    private final List<Toy> enabled = new ArrayList<>();
    private final Supplier<Toy.Context> context;
    private int index;
    private Toy selected;
    private long visibleUntil = Long.MIN_VALUE;

    public ToyHost(Supplier<Toy.Context> context, Toy... toys) {
        this.context = context;
        for (Toy t : toys) all.add(t);
        enabled.addAll(all);
    }

    public synchronized List<Toy> allToys() {
        return new ArrayList<>(all);
    }

    /** Replaces the toys after the built-in ones (e.g. Glyph Matrix SDK toys on the device). */
    public synchronized void setExtraToys(List<? extends Toy> extra, int builtInCount) {
        Toy current = current();
        while (all.size() > builtInCount) enabled.remove(all.remove(all.size() - 1));
        all.addAll(extra);
        for (Toy t : extra) if (!enabled.contains(t)) enabled.add(t);
        reconcile(current);
    }

    /** Enables exactly the toys whose ids are given, keeping the built-in order. */
    public synchronized void setEnabled(java.util.Set<String> ids) {
        Toy current = current();
        enabled.clear();
        for (Toy t : all) if (ids.contains(t.id())) enabled.add(t);
        reconcile(current);
    }

    /** Enables exactly the toys whose ids are given, in that order (Settings > Manage toys). */
    public synchronized void setOrder(List<String> ids) {
        Toy current = current();
        enabled.clear();
        for (String id : ids) {
            Toy t = byId(id);
            if (t != null && !enabled.contains(t)) enabled.add(t);
        }
        reconcile(current);
    }

    public synchronized Toy byId(String id) {
        for (Toy t : all) if (t.id().equals(id)) return t;
        return null;
    }

    /** Selects and shows one toy now (Settings "try it"), even one that is not enabled. */
    public synchronized void show(String id, long now) {
        Toy t = byId(id);
        if (t == null) return;
        int i = enabled.indexOf(t);
        if (i >= 0) index = i;
        select(t);
        visibleUntil = now + selected.timeoutMs();
    }

    /** Keeps the position on the same toy where possible; drops a selection that went away. */
    private void reconcile(Toy current) {
        index = Math.max(0, enabled.indexOf(current));
        if (selected != null && !enabled.contains(selected)) select(null);
    }

    public synchronized Toy current() {
        return enabled.isEmpty() ? null : enabled.get(Math.min(index, enabled.size() - 1));
    }

    public synchronized Toy selected() {
        return selected;
    }

    public synchronized void onTap(long now) {
        if (enabled.isEmpty()) return;
        if (selected != null && isVisible(now)) index = (index + 1) % enabled.size();
        select(current());
        visibleUntil = now + selected.timeoutMs();
    }

    public synchronized void onLongPress(long now) {
        if (selected == null) return;
        selected.onLongPress(now);
        visibleUntil = now + selected.timeoutMs();
    }

    public synchronized void onButtonDown(long now) {
        if (selected != null) selected.onButtonDown(now);
    }

    public synchronized void onButtonUp(long now) {
        if (selected != null) selected.onButtonUp(now);
    }

    /** Takes the toy off the matrix; it stays selected. */
    public synchronized void hide() {
        visibleUntil = Long.MIN_VALUE;
    }

    /** Deselects (e.g. toys switched off in Settings). */
    public synchronized void deselect() {
        select(null);
        hide();
    }

    private void select(Toy t) {
        if (t == selected) return;
        Toy old = selected;
        selected = t;
        if (old != null) old.onDeselected();
        if (t != null) t.onSelected();
    }

    private boolean isVisible(long now) {
        if (selected == null) return false;
        return (visibleUntil != Long.MIN_VALUE && now < visibleUntil) || selected.keepsScreenOn(now);
    }

    /** Toy.SENSOR_* flags the toy on screen needs now; 0 when nothing is shown. */
    public synchronized int neededSensors(long now) {
        return isVisible(now) ? selected.sensors() : 0;
    }

    @Override public int priority() { return PRIORITY_TOY; }

    @Override public synchronized boolean isActive(long now) {
        return isVisible(now);
    }

    @Override public synchronized long render(Frame f, long now) {
        if (selected == null) return -1;
        selected.render(f, now, context.get());
        long interval = selected.frameIntervalMs();
        long untilIdle = visibleUntil - now;
        // Wake up for the idle timeout even when the picture is static.
        if (!selected.keepsScreenOn(now) && untilIdle > 0) {
            return interval < 0 ? untilIdle : Math.min(interval, untilIdle);
        }
        return interval;
    }
}
