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
 * - A toy is selected (an SDK toy bound) only while it is on screen; when it goes off screen it
 *   is deselected, so the next press starts it afresh. It is
 *   shown for the Glyph Toys timeout after the last press (as the stock host: one user setting
 *   for every toy, 15 s to 30 min), or for as long as it keeps the screen on.
 * - As the stock host, toys also go off on unlock and when the matrix is laid face down
 *   ({@link #hide()}); a short press always brings them back.
 * - Automations: while no toy picked with the button is on screen, the automation toy
 *   ({@link #setAuto}, from the first matching rule) is shown, for as long as its rule holds.
 *   The toy on the matrix, picked or automatic, is the one told onSelected / onDeselected.
 */
public final class ToyHost implements Effect {
    /** Default Glyph Toys timeout. */
    public static final long IDLE_MS = 30000;
    /** The stock "Glyph Toys timeout" choices. */
    public static final long[] TIMEOUTS_MS = {15000, 30000, 60000, 120000, 300000, 600000, 1800000};

    private final List<Toy> all = new ArrayList<>();
    private final List<Toy> enabled = new ArrayList<>();
    private final Supplier<Toy.Context> context;
    private int index;
    private Toy selected;
    private long visibleUntil = Long.MIN_VALUE;
    private long timeoutMs = IDLE_MS;
    /** The automation's toy (null: none), and the toy on the matrix whose onSelected ran. */
    private Toy auto, live;
    private long lastNow;

    public ToyHost(Supplier<Toy.Context> context, Toy... toys) {
        this.context = context;
        for (Toy t : toys) all.add(t);
        enabled.addAll(all);
    }

    /** Settings > Glyph Toys timeout; applies from the next press. */
    public synchronized void setTimeoutMs(long ms) {
        timeoutMs = ms > 0 ? ms : IDLE_MS;
    }

    public synchronized long timeoutMs() {
        return timeoutMs;
    }

    private long timeoutOf(Toy t) {
        long own = t.timeoutMs();
        return own > 0 ? own : timeoutMs;
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
        visibleUntil = now + timeoutOf(selected);
        sync(now);
    }

    /** Keeps the position on the same toy where possible; drops a selection that went away. */
    private void reconcile(Toy current) {
        index = Math.max(0, enabled.indexOf(current));
        if (selected != null && !enabled.contains(selected)) select(null);
        if (auto != null && !all.contains(auto)) auto = null;
        sync(lastNow);
    }

    /** The automation's toy by id (any toy, enabled for the button or not), or null for none. */
    public synchronized void setAuto(String id, long now) {
        auto = id == null ? null : byId(id);
        sync(now);
    }

    public synchronized Toy auto() {
        return auto;
    }

    /** The toy on the matrix now: the picked one while it is visible, else the automation's. */
    private Toy shown(long now) {
        return isVisible(now) ? selected : auto;
    }

    /** Tells the toy that left the matrix and the one that arrived. */
    private void sync(long now) {
        lastNow = now;
        Toy s = shown(now);
        if (s == live) return;
        Toy old = live;
        live = s;
        if (old != null) old.onDeselected();
        if (s != null) s.onSelected();
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
        visibleUntil = now + timeoutOf(selected);
        sync(now);
    }

    public synchronized void onLongPress(long now) {
        if (selected == null || !isVisible(now)) {
            // Long press on an automation toy goes to it; with nothing shown it means "a toy".
            if (auto != null) auto.onLongPress(now); else onTap(now);
            return;
        }
        selected.onLongPress(now);
        visibleUntil = now + timeoutOf(selected);
    }

    public synchronized void onButtonDown(long now) {
        Toy s = shown(now);
        if (s != null) s.onButtonDown(now);
    }

    public synchronized void onButtonUp(long now) {
        Toy s = shown(now);
        if (s != null) s.onButtonUp(now);
    }

    /** Takes the picked toy off the matrix (an automation toy stays). */
    public synchronized void hide() {
        visibleUntil = Long.MIN_VALUE;
        selected = null;
        sync(lastNow);
    }

    /** Deselects everything (e.g. toys switched off in Settings). */
    public synchronized void deselect() {
        selected = null;
        auto = null;
        visibleUntil = Long.MIN_VALUE;
        sync(lastNow);
    }

    /** The picked toy; who hears onSelected is decided by {@link #sync}. */
    private void select(Toy t) {
        selected = t;
    }

    private boolean isVisible(long now) {
        if (selected == null) return false;
        return (visibleUntil != Long.MIN_VALUE && now < visibleUntil) || selected.keepsScreenOn(now);
    }

    /** A toy is on the matrix now. */
    public synchronized boolean isShowing(long now) {
        return isVisible(now);
    }

    /** Toy.SENSOR_* flags the toy on screen needs now; 0 when nothing is shown. */
    public synchronized int neededSensors(long now) {
        Toy s = shown(now);
        return s == null ? 0 : s.sensors();
    }

    @Override public int priority() { return PRIORITY_TOY; }

    @Override public synchronized boolean isActive(long now) {
        boolean visible = isVisible(now);
        // Nothing runs in the background: once off screen the picked toy is deselected (an SDK
        // toy is unbound), and the next press starts the toy in turn afresh.
        if (!visible && selected != null) selected = null;
        sync(now);
        return live != null;
    }

    @Override public synchronized long render(Frame f, long now) {
        sync(now);
        Toy s = live;
        if (s == null) return -1;
        s.render(f, now, context.get());
        long interval = s.frameIntervalMs();
        if (s != selected) return interval; // an automation toy: no timeout
        long untilIdle = visibleUntil - now;
        // Wake up for the idle timeout even when the picture is static.
        if (!s.keepsScreenOn(now) && untilIdle > 0) {
            return interval < 0 ? untilIdle : Math.min(interval, untilIdle);
        }
        return interval;
    }
}
