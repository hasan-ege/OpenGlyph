package org.openglyph.toys;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import org.openglyph.core.GlyphText;

/**
 * Text to Glyph on a computer (simulator, reel): a condensed bold sans drawn with AWT, the
 * closest match to the phone's sans-serif-condensed.
 */
final class AwtTextRasterizer implements GlyphText.Rasterizer {
    private final String family;

    AwtTextRasterizer() {
        List<String> have = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        String pick = "SansSerif";
        for (String f : List.of("Roboto Condensed", "DejaVu Sans Condensed", "Liberation Sans Narrow",
                "Noto Sans")) {
            if (have.contains(f)) {
                pick = f;
                break;
            }
        }
        family = pick;
    }

    @Override
    public synchronized GlyphText.Line rasterize(String text, int heightPx) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D pg = probe.createGraphics();
        Font base = new Font(family, Font.BOLD, 100);
        FontMetrics pm = pg.getFontMetrics(base);
        float size = 100f * heightPx / (pm.getAscent() + pm.getDescent());
        Font font = base.deriveFont(size);
        FontMetrics fm = pg.getFontMetrics(font);
        pg.dispose();
        int w = Math.max(1, fm.stringWidth(text) + 2);
        BufferedImage img = new BufferedImage(w, heightPx, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, 1, fm.getAscent());
        g.dispose();
        float[] a = new float[w * heightPx];
        for (int y = 0; y < heightPx; y++)
            for (int x = 0; x < w; x++) a[y * w + x] = (img.getRaster().getSample(x, y, 0)) / 255f;
        return new GlyphText.Line(w, heightPx, a);
    }

    @Override public String toString() { return family; }
}
