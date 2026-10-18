package org.openglyph.toys;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * A toy that lives in another app (a Nothing Glyph Matrix SDK toy service). It draws whatever the
 * app last sent through the compatibility service, and forwards lifecycle and button events to
 * a {@link Listener}, which the Android side implements by binding the service and sending
 * Messenger messages.
 */
public final class RemoteToy implements Toy {
    /** Event names the SDK's toys understand. */
    public static final String EVENT_CHANGE = "change";
    public static final String EVENT_ACTION_DOWN = "action_down";
    public static final String EVENT_ACTION_UP = "action_up";
    public static final String EVENT_AOD = "aod";

    public interface Listener {
        void onShow(RemoteToy toy);

        void onHide(RemoteToy toy);

        void onEvent(RemoteToy toy, String event);
    }

    private final String id;
    private final String packageName;
    private final String className;
    private final String label;
    private final boolean longPressSupported;
    private final Listener listener;
    private final int[] latest = new int[MatrixLayout.GRID_SIZE];
    private volatile boolean hasFrame;

    public RemoteToy(String packageName, String className, String label,
            boolean longPressSupported, Listener listener) {
        this.packageName = packageName;
        this.className = className;
        this.label = label;
        this.longPressSupported = longPressSupported;
        this.listener = listener;
        // Settings keys allow [a-z0-9_.]; package and class names fit after lowercasing.
        this.id = "nt_" + (packageName + "_" + simpleName(className)).toLowerCase(java.util.Locale.ROOT);
    }

    static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    public String packageName() { return packageName; }
    public String className() { return className; }
    public String label() { return label; }

    @Override public String id() { return id; }

    /** A frame from the app (625 values, 0..4095); shown while this toy is on screen. */
    public void setFrame(int[] grid) {
        if (grid == null || grid.length != MatrixLayout.GRID_SIZE) return;
        synchronized (latest) {
            System.arraycopy(grid, 0, latest, 0, grid.length);
        }
        hasFrame = true;
    }

    public boolean hasFrame() {
        return hasFrame;
    }

    @Override public void render(Frame f, long now, Context ctx) {
        if (!hasFrame) return; // the app has not drawn yet
        synchronized (latest) {
            f.setGrid(latest);
        }
    }

    // A remote toy repaints on every frame it sends; the service invalidates the renderer.
    @Override public long frameIntervalMs() { return -1; }

    @Override public void onShown() {
        hasFrame = false; // never flash a stale picture from the last time
        listener.onShow(this);
    }

    @Override public void onHidden() {
        listener.onHide(this);
    }

    @Override public void onLongPress(long now) {
        if (longPressSupported) listener.onEvent(this, EVENT_CHANGE);
    }

    @Override public void onButtonDown(long now) {
        listener.onEvent(this, EVENT_ACTION_DOWN);
    }

    @Override public void onButtonUp(long now) {
        listener.onEvent(this, EVENT_ACTION_UP);
    }
}
