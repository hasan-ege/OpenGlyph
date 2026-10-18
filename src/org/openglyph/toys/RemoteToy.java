package org.openglyph.toys;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * A toy that lives in another app (a Glyph Matrix SDK toy service). Per the SDK documentation the
 * host binds the toy's service while it is selected and unbinds it when another toy is chosen;
 * the toy draws through the compatibility service, which ends up in {@link #setFrame}. Button
 * events go to the toy as Messenger messages via the {@link Listener}.
 */
public final class RemoteToy implements Toy {
    /** Event names the SDK's toys understand. */
    public static final String EVENT_CHANGE = "change";
    public static final String EVENT_ACTION_DOWN = "action_down";
    public static final String EVENT_ACTION_UP = "action_up";
    public static final String EVENT_AOD = "aod";

    public interface Listener {
        /** Bind the toy's service (it starts working in its onBind). */
        void onSelect(RemoteToy toy);

        /** Unbind it (it stops in its onUnbind). */
        void onDeselect(RemoteToy toy);

        void onEvent(RemoteToy toy, String event);
    }

    private final String id;
    private final String packageName;
    private final String className;
    private final String label;
    private final String summary;
    private final boolean longPressSupported;
    private final boolean aodSupported;
    private final Listener listener;
    private final int[] latest = new int[MatrixLayout.GRID_SIZE];
    private volatile boolean hasFrame;
    /** setGlyphMatrixTimeout(false) from the toy: keep it on the matrix. */
    private volatile boolean noTimeout;

    public RemoteToy(String packageName, String className, String label, String summary,
            boolean longPressSupported, boolean aodSupported, Listener listener) {
        this.packageName = packageName;
        this.className = className;
        this.label = label;
        this.summary = summary;
        this.longPressSupported = longPressSupported;
        this.aodSupported = aodSupported;
        this.listener = listener;
        // Settings keys allow [a-z0-9_.]; package and class names fit after lowercasing.
        this.id = "nt_" + (packageName + "_" + simpleName(className))
                .toLowerCase(java.util.Locale.ROOT);
    }

    static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    public String packageName() { return packageName; }
    public String className() { return className; }
    public String label() { return label; }
    public String summary() { return summary; }
    public boolean aodSupported() { return aodSupported; }
    public boolean longPressSupported() { return longPressSupported; }

    @Override public String id() { return id; }

    /** A frame from the app (625 values, 0..4095). */
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

    /** Copies the last frame into {@code out}; false if the toy has not drawn yet. */
    public boolean copyFrame(int[] out) {
        if (!hasFrame) return false;
        synchronized (latest) {
            System.arraycopy(latest, 0, out, 0, latest.length);
        }
        return true;
    }

    public void setNoTimeout(boolean noTimeout) {
        this.noTimeout = noTimeout;
    }

    @Override public boolean keepsScreenOn(long now) {
        return noTimeout;
    }

    @Override public void render(Frame f, long now, Context ctx) {
        if (!hasFrame) return; // the app has not drawn yet
        synchronized (latest) {
            f.setGrid(latest);
        }
    }

    // A remote toy repaints on every frame it sends; the service invalidates the renderer.
    @Override public long frameIntervalMs() { return -1; }

    @Override public void onSelected() {
        hasFrame = false; // never flash a stale picture from the last time
        noTimeout = false;
        listener.onSelect(this);
    }

    @Override public void onDeselected() {
        noTimeout = false;
        listener.onDeselect(this);
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
