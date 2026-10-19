package org.openglyph.toys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * Records every built-in toy through a short scripted scene (time running, long presses,
 * simulated tilt / heading / camera) with the real toy code, and writes the frames into an
 * HTML page that plays them on a round matrix: what the phone would show, frame for frame.
 *
 *     sh tools/toy-reel/run.sh   (writes ../previews/toys.html)
 */
public final class ToyReel {
    static final int FPS = 20, STEP = 1000 / FPS;

    /** One toy's scene: what happens at each moment. */
    interface Scene {
        /** Sets the context for time t (ms since the start); presses happen through press(). */
        void at(long t, Toy.Context ctx, Toy toy, Presses p);
    }

    static final class Presses {
        final List<Long> times = new ArrayList<>();
        Presses at(long... ts) {
            for (long t : ts) times.add(t);
            return this;
        }
    }

    record Reel(String id, String title, String note, long lengthMs, Presses presses, Scene scene) {}

    // 2026-10-19 09:41:07 local (+03:00), a fixed start so the reel is the same every run.
    static final long EPOCH0 = 1792392067000L;

    static void clockAt(Toy.Context c, long epoch) {
        long local = epoch + (long) (c.utcOffsetHours * 3600000);
        long s = Math.floorMod(local / 1000, 86400);
        c.hour = (int) (s / 3600);
        c.minute = (int) (s / 60 % 60);
        c.second = (int) (s % 60);
        c.epochMs = epoch;
    }

    static List<Reel> reels() {
        List<Reel> r = new ArrayList<>();
        r.add(new Reel("clock", "Saat", "Uzun basış: kadranı değiştirir", 16000,
                new Presses().at(2000, 4000, 6000, 8000, 10000, 12000, 14000),
                (t, c, toy, p) -> clockAt(c, EPOCH0 + t)));
        r.add(new Reel("battery", "Pil", "Şarjda nefes alır", 6000, new Presses(),
                (t, c, toy, p) -> { c.batteryPercent = 76; c.charging = t > 3000; clockAt(c, EPOCH0 + t); }));
        r.add(new Reel("solar", "Güneş yolu", "Günün 24 saati 6 saniyede", 6000, new Presses(),
                (t, c, toy, p) -> { c.latitude = 41; c.longitude = 29; clockAt(c, EPOCH0 + t * 14400); }));
        r.add(new Reel("mirror", "Glyph Mirror", "Uzun basış: 3-2-1 ve fotoğraf", 6000,
                new Presses().at(1500), (t, c, toy, p) -> { clockAt(c, EPOCH0 + t); c.camera = fakeCamera(t); }));
        r.add(new Reel("bottle", "Şişe çevirme", "Uzun basış: çevirir", 9000, new Presses().at(1000, 5000),
                (t, c, toy, p) -> clockAt(c, EPOCH0 + t)));
        r.add(new Reel("rps", "Taş kağıt makas", "Uzun basış: oynar", 9000, new Presses().at(2800),
                (t, c, toy, p) -> { c.hasGravity = true; c.gz = 9.8f; clockAt(c, EPOCH0 + t); }));
        r.add(new Reel("magic8", "Sihirli 8", "Uzun basış veya sallama: cevap", 10000, new Presses().at(1000),
                (t, c, toy, p) -> { c.hasGravity = true; c.gz = 9.8f; clockAt(c, EPOCH0 + t); }));
        r.add(new Reel("stopwatch", "Kronometre", "Uzun basış: başlat / durdur / sıfırla", 9000,
                new Presses().at(500, 7000, 8500), (t, c, toy, p) -> clockAt(c, EPOCH0 + t)));
        r.add(new Reel("leveller", "Su terazisi", "Eğim", 6000, new Presses(),
                (t, c, toy, p) -> {
                    double a = t / 900.0, k = t > 4500 ? 0 : 3.5 * (1 - t / 4500.0);
                    c.hasGravity = true; c.gx = (float) (k * Math.cos(a)); c.gy = (float) (k * Math.sin(a)); c.gz = 9.3f;
                }));
        r.add(new Reel("compass", "Pusula", "Telefon döner", 6000, new Presses(),
                (t, c, toy, p) -> { c.hasHeading = true; c.heading = (float) (t * 0.09 % 360); }));
        r.add(new Reel("moon", "Ay evresi", "Bir ay 6 saniyede", 6000, new Presses(),
                (t, c, toy, p) -> clockAt(c, EPOCH0 + t * 432000L)));
        r.add(new Reel("fly", "Sineği besle", "Uzun basış: eğerek oyna", 10000, new Presses().at(800),
                (t, c, toy, p) -> {
                    double a = t / 700.0;
                    c.hasGravity = true; c.gx = (float) (6 * Math.cos(a)); c.gy = (float) (6 * Math.sin(a * 1.3)); c.gz = 7;
                }));
        return r;
    }

    /** A stand-in camera picture: a face-like blob drifting, so the mirror has something. */
    static int[] fakeCamera(long t) {
        int[] g = new int[625];
        double ox = 12 + 2 * Math.sin(t / 900.0), oy = 12 + Math.cos(t / 1300.0);
        for (int y = 0; y < 25; y++) {
            for (int x = 0; x < 25; x++) {
                double d = Math.hypot((x - ox) / 0.8, y - oy);
                double v = d < 8 ? 0.75 - d / 20 : 0.12 + 0.05 * Math.sin(x + t / 400.0);
                if (d < 8 && Math.abs(y - oy + 2) < 1 && Math.abs(Math.abs(x - ox) - 3) < 1.2) v = 0.1; // eyes
                if (d < 8 && Math.abs(y - oy - 3.5) < 0.7 && Math.abs(x - ox) < 2.6) v = 0.2;           // mouth
                g[y * 25 + x] = (int) (Math.max(0, Math.min(1, v)) * Frame.MAX);
            }
        }
        return g;
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "toys.html");
        Path template = Path.of(args.length > 1 ? args[1] : "template.html");
        org.openglyph.core.GlyphText.setRasterizer(new AwtTextRasterizer());
        Toy.Context ctx = new Toy.Context();
        ctx.utcOffsetHours = 3;
        StringBuilder json = new StringBuilder("[");
        for (Reel reel : reels()) {
            Toy toy = null;
            for (Toy t : Toys.all()) if (t.id().equals(reel.id())) toy = t;
            if (toy == null) throw new IllegalStateException("no toy " + reel.id());
            seed(toy);
            toy.onSelected();
            byte[] frames = new byte[(int) (reel.lengthMs() / STEP) * 625];
            int n = 0;
            int pi = 0;
            long base = 1_000_000; // uptime ms
            for (long t = 0; t < reel.lengthMs(); t += STEP) {
                reel.scene().at(t, ctx, toy, reel.presses());
                while (pi < reel.presses().times.size() && reel.presses().times.get(pi) <= t) {
                    toy.onButtonDown(base + t);
                    toy.onLongPress(base + t);
                    toy.onButtonUp(base + t);
                    pi++;
                }
                Frame f = new Frame();
                toy.render(f, base + t, ctx);
                for (int i = 0; i < 625; i++) {
                    int x = i % 25, y = i / 25;
                    int v = MatrixLayout.exists(x, y) ? f.get(x, y) : 0;
                    frames[n * 625 + i] = (byte) Math.min(255, Math.round(v * 255.0 / Frame.MAX));
                }
                n++;
            }
            toy.onDeselected();
            if (json.length() > 1) json.append(',');
            json.append("{\"id\":\"").append(reel.id()).append("\",\"title\":\"").append(reel.title())
                    .append("\",\"note\":\"").append(reel.note()).append("\",\"fps\":").append(FPS)
                    .append(",\"presses\":").append(reel.presses().times)
                    .append(",\"frames\":\"").append(Base64.getEncoder().encodeToString(
                            java.util.Arrays.copyOf(frames, n * 625))).append("\"}");
        }
        json.append(']');
        String html = Files.readString(template, StandardCharsets.UTF_8)
                .replace("/*REELS*/[]", json.toString());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        System.out.println("wrote " + out);
    }

    /** Fixed randomness, so every run records the same spins and answers. */
    static void seed(Toy toy) {
        Random r = new Random(7);
        try {
            for (java.lang.reflect.Field f : toy.getClass().getDeclaredFields()) {
                if (f.getType() == Random.class) {
                    f.setAccessible(true);
                    if (!java.lang.reflect.Modifier.isFinal(f.getModifiers())) f.set(toy, r);
                    else {
                        // final: replace its state instead
                        ((Random) f.get(toy)).setSeed(7);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
