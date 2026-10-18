package org.openglyph;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;
import org.openglyph.toys.RemoteToy;
import org.openglyph.toys.Toy;
import org.openglyph.toys.Toys;

/** Every toy (built-in, then Glyph Matrix SDK toys) with what Settings shows for it. */
final class ToyCatalog {
    static final class Entry {
        final String id;
        final CharSequence label;
        final CharSequence summary;
        final boolean aod;
        /** Built-in toys: a fresh instance for previews, so the live one keeps its state. */
        final Toy preview;
        final RemoteToy remote;

        Entry(String id, CharSequence label, CharSequence summary, boolean aod, Toy preview,
                RemoteToy remote) {
            this.id = id;
            this.label = label;
            this.summary = summary;
            this.aod = aod;
            this.preview = preview;
            this.remote = remote;
        }
    }

    private ToyCatalog() {}

    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        for (Toy t : Toys.all()) {
            out.add(new Entry(t.id(), text(c, "toy_" + t.id()), text(c, "toy_" + t.id() + "_summary"),
                    t.aodSupported(), t, null));
        }
        GlyphService service = GlyphService.get();
        if (service != null) {
            for (RemoteToy t : service.sdkToys()) {
                out.add(new Entry(t.id(), t.label(), t.summary().isEmpty()
                        ? c.getString(R.string.toy_sdk_summary) : t.summary(),
                        t.aodSupported(), null, t));
            }
        }
        return out;
    }

    private static CharSequence text(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        return id == 0 ? name : c.getText(id);
    }

    static Entry byId(List<Entry> entries, String id) {
        for (Entry e : entries) if (e.id.equals(id)) return e;
        return null;
    }

    static Toy.Context context() {
        GlyphService service = GlyphService.get();
        if (service != null) return service.toyContext();
        Toy.Context ctx = new Toy.Context();
        Calendar now = Calendar.getInstance();
        ctx.hour = now.get(Calendar.HOUR_OF_DAY);
        ctx.minute = now.get(Calendar.MINUTE);
        ctx.batteryPercent = 100;
        return ctx;
    }

    /** One frame of a built-in toy. */
    static long render(Toy toy, int[] grid) {
        Frame f = new Frame();
        toy.render(f, SystemClock.uptimeMillis(), context());
        for (int y = 0; y < MatrixLayout.HEIGHT; y++)
            for (int x = 0; x < MatrixLayout.WIDTH; x++)
                grid[y * MatrixLayout.WIDTH + x] = f.get(x, y);
        return toy.frameIntervalMs();
    }

    /** Shows the toy in a view: animated for built-in toys, the app's image for SDK toys. */
    static void show(MatrixView view, Entry e) {
        if (e.preview != null) {
            view.setSource(grid -> render(e.preview, grid));
        } else {
            Drawable d = previewDrawable(e);
            if (d != null) view.setDrawable(d); else view.setGrid(appIconGrid(e));
        }
    }

    /** A round icon of the toy, {@code sizePx} across. */
    static Drawable icon(Entry e, int sizePx) {
        if (e.preview != null) {
            int[] grid = new int[MatrixLayout.GRID_SIZE];
            render(e.preview, grid);
            return new MatrixArt.GridDrawable(grid, sizePx);
        }
        Drawable d = previewDrawable(e);
        return d != null ? new MatrixArt.RoundDrawable(d, sizePx)
                : new MatrixArt.GridDrawable(appIconGrid(e), sizePx);
    }

    /** SDK toys without a preview image: their app's icon on the matrix. */
    private static int[] appIconGrid(Entry e) {
        GlyphService service = GlyphService.get();
        return service == null || e.remote == null ? null
                : AppIconMatrix.render(service, e.remote.packageName());
    }

    private static Drawable previewDrawable(Entry e) {
        GlyphService service = GlyphService.get();
        return service == null || e.remote == null ? null : service.toyBridge().preview(e.remote);
    }
}
