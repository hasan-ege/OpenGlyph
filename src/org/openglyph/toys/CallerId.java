package org.openglyph.toys;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.GlyphText;

/**
 * Caller ID: the caller's name (or number, when not in contacts) scrolling across the matrix,
 * set in a real font (Text to Glyph: any name, any script), over the incoming-call animation. Shown on a short Glyph Button press while
 * the phone rings.
 */
public final class CallerId implements Effect {
    private static final double PX_PER_S = 28;
    private static final int PASSES = 2;
    private volatile String text;
    private volatile long startMs;

    private static final int TEXT_H = 13;

    public void show(String caller, long nowMs) {
        String t = caller == null ? "" : caller.trim();
        // The bitmap fallback (no font engine) only knows capitals and digits.
        if (!GlyphText.available()) t = Font.plain(t).replaceAll("[^A-Z0-9+]+", " ").trim();
        text = t.isEmpty() ? null : t;
        startMs = nowMs;
    }

    public void hide() {
        text = null;
    }

    private static int width(String t) {
        GlyphText.Line l = GlyphText.line(t, TEXT_H);
        return l != null ? l.width : t.length() * 8 - 2;
    }

    /** One pass: from just off the right edge until the last letter leaves on the left. */
    private static long passMs(String t) {
        return (long) ((25 + width(t)) * 1000 / PX_PER_S);
    }

    @Override public int priority() { return PRIORITY_CALL + 5; }

    @Override public boolean isActive(long now) {
        String t = text;
        return t != null && now - startMs < PASSES * passMs(t);
    }

    @Override public long render(Frame f, long now) {
        String t = text;
        if (t == null) return -1;
        long inPass = (now - startMs) % passMs(t);
        int x = 25 - (int) (inPass * PX_PER_S / 1000);
        GlyphText.Line l = GlyphText.line(t, TEXT_H);
        if (l != null) GlyphText.draw(f, l, x, (25 - TEXT_H) / 2, Frame.MAX);
        else Font.bigText(f, t, x, 7, Frame.MAX);
        return 33;
    }
}
