package org.openglyph.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks the winning effect, renders it, scales it to hardware brightness and hands it to
 * the sink. {@link #tick(long)} is the whole pipeline and is what the tests drive; the
 * render thread just calls it in a loop.
 */
public final class Renderer {
    /** sysfs writes are a full SPI transfer each; 30 fps keeps the driver comfortable. */
    public static final long MIN_FRAME_MS = 33;

    public interface Clock {
        long nowMs();
    }

    private final MatrixSink sink;
    private final Clock clock;
    private final List<Effect> effects = new ArrayList<>();
    private final Frame frame = new Frame();
    private final int[] leds = new int[MatrixLayout.LED_COUNT];
    private final int[] lastLeds = new int[MatrixLayout.LED_COUNT];

    /** Last frame sent to the hardware (before brightness scaling), for the Settings preview. */
    private final int[] shown = new int[MatrixLayout.GRID_SIZE];
    private int brightness = 255; // user setting, 0..255
    private boolean lit;
    private boolean dirty = true;
    private Thread thread;
    private volatile boolean running;

    public Renderer(MatrixSink sink, Clock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    public synchronized void add(Effect effect) {
        if (!effects.contains(effect)) effects.add(effect);
        invalidate();
    }

    public synchronized void remove(Effect effect) {
        effects.remove(effect);
        invalidate();
    }

    public synchronized void setBrightness(int value) {
        brightness = Math.max(0, Math.min(255, value));
        invalidate();
    }

    /** Effects call this when their state changes (new notification, call ended, ...). */
    public synchronized void invalidate() {
        dirty = true;
        notifyAll();
    }

    synchronized Effect winner(long now) {
        Effect best = null;
        for (Effect e : effects) {
            if (e.isActive(now) && (best == null || e.priority() > best.priority())) best = e;
        }
        return best;
    }

    /**
     * Renders one frame if needed.
     *
     * @return how long the caller may sleep before the next tick (-1: until invalidated).
     */
    public long tick(long now) throws IOException {
        Effect effect;
        synchronized (this) {
            effect = winner(now);
            dirty = false;
        }
        if (effect == null || brightness == 0) {
            if (lit) {
                sink.off();
                lit = false;
                java.util.Arrays.fill(lastLeds, 0);
                synchronized (shown) {
                    java.util.Arrays.fill(shown, 0);
                }
            }
            return -1;
        }

        frame.clear();
        long next = effect.render(frame, now);
        synchronized (shown) {
            System.arraycopy(frame.px, 0, shown, 0, shown.length);
        }
        MatrixLayout.gridToLeds(frame.px, leds);
        for (int i = 0; i < leds.length; i++) {
            leds[i] = (int) ((long) leds[i] * SysfsMatrixSink.HW_MAX * brightness / (Frame.MAX * 255L));
        }
        if (!lit || !java.util.Arrays.equals(leds, lastLeds)) {
            sink.show(leds);
            System.arraycopy(leds, 0, lastLeds, 0, leds.length);
            lit = true;
        }
        // An effect that expires must be noticed even if it asked for a static frame.
        return next < 0 ? 250 : Math.max(next, MIN_FRAME_MS);
    }

    /** Copies what the matrix shows now (625 grid, 0..Frame.MAX) into {@code out}. */
    public void snapshot(int[] out) {
        synchronized (shown) {
            System.arraycopy(shown, 0, out, 0, shown.length);
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "OpenGlyphRenderer");
        thread.start();
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            notifyAll();
            t = thread;
        }
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            sink.off();
        } catch (IOException ignored) {
        }
    }

    private void loop() {
        while (running) {
            long wait;
            try {
                wait = tick(clock.nowMs());
            } catch (IOException e) {
                wait = 1000; // driver busy or node missing; retry rather than spin
            }
            synchronized (this) {
                if (!running) break;
                if (dirty) continue;
                try {
                    if (wait < 0) wait(); else wait(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
}
