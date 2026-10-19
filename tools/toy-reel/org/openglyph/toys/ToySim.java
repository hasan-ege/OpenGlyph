package org.openglyph.toys;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * A live toy simulator: runs the real toy code on this computer and serves a page that shows
 * every toy at once, plus a "phone" driven by a simulated Glyph Button (tap cycles toys, long
 * press is the toy's action), with tilt, heading, battery and a Clock timer to play with.
 *
 *     sh tools/toy-reel/sim.sh     then open http://localhost:8766/
 *
 * GET  /frames          the phone's frame then every toy's, 625 bytes each (0..255)
 * GET  /toys            the toy ids and names, JSON
 * POST /press?toy=ID&kind=tap|long|down|up|shake   (toy=phone for the Glyph Button)
 * POST /ctx?gx=&gy=&heading=&battery=&charging=&timer=
 */
public final class ToySim {
    static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("clock", "Saat"), Map.entry("battery", "Pil"), Map.entry("solar", "Güneş yolu"),
            Map.entry("mirror", "Glyph Mirror"), Map.entry("bottle", "Şişe çevirme"),
            Map.entry("rps", "Taş kağıt makas"), Map.entry("magic8", "Sihirli 8"),
            Map.entry("stopwatch", "Kronometre"), Map.entry("hourglass", "Kum saati"),
            Map.entry("leveller", "Su terazisi"), Map.entry("compass", "Pusula"),
            Map.entry("moon", "Ay evresi"), Map.entry("fly", "Sineği besle"), Map.entry("off", "Kapalı"));

    private final List<Toy> toys = Toys.all();
    private final Toy.Context ctx = new Toy.Context();
    private final ToyHost phone;
    private final long t0 = System.nanoTime();
    private volatile float tiltX, tiltY, heading;
    private final Map<Object, Long> shakeUntil = new HashMap<>();
    private final CallerId callerId = new CallerId();

    ToySim() {
        ctx.batteryPercent = 76;
        ctx.latitude = 41.0;
        ctx.longitude = 29.0;
        ctx.hasGravity = true;
        ctx.hasHeading = true;
        phone = new ToyHost(() -> ctx, Toys.all().toArray(new Toy[0]));
        for (Toy t : toys) t.onSelected();
    }

    long now() {
        return (System.nanoTime() - t0) / 1_000_000 + 1_000_000;
    }

    /** The context for one toy at {@code now}: real local time, the sliders, a shake if asked. */
    private void prepare(Object who, long now) {
        ZonedDateTime z = ZonedDateTime.now();
        ctx.hour = z.getHour();
        ctx.minute = z.getMinute();
        ctx.second = z.getSecond();
        ctx.epochMs = System.currentTimeMillis();
        ctx.utcOffsetHours = z.getOffset().getTotalSeconds() / 3600.0;
        Long until = shakeUntil.get(who);
        boolean shaking = until != null && now < until;
        // A shake: hard jolts every other frame, well above gravity.
        float jolt = shaking && (now / 60) % 2 == 0 ? 22f : 0f;
        ctx.gx = tiltX + jolt;
        ctx.gy = tiltY;
        ctx.gz = (float) Math.sqrt(Math.max(0, 9.81 * 9.81 - tiltX * tiltX - tiltY * tiltY));
        ctx.heading = heading;
        ctx.camera = ToyReel.fakeCamera(now);
    }

    synchronized byte[] frames() {
        long now = now();
        byte[] out = new byte[(toys.size() + 1) * 625];
        Frame f = new Frame();
        prepare(phone, now);
        if (callerId.isActive(now)) callerId.render(f, now);          // Caller ID on top, as on the phone
        else if (phone.isActive(now)) phone.render(f, now);
        else if (Hourglass.effect().isActive(now)) Hourglass.effect().render(f, now); // a timer runs
        copy(f, out, 0);
        for (int i = 0; i < toys.size(); i++) {
            f.clear();
            prepare(toys.get(i), now);
            toys.get(i).render(f, now, ctx);
            copy(f, out, i + 1);
        }
        return out;
    }

    private static void copy(Frame f, byte[] out, int slot) {
        for (int i = 0; i < 625; i++) {
            int x = i % 25, y = i / 25;
            int v = MatrixLayout.exists(x, y) ? f.get(x, y) : 0;
            out[slot * 625 + i] = (byte) Math.min(255, Math.round(v * 255.0 / Frame.MAX));
        }
    }

    synchronized void press(String id, String kind) {
        long now = now();
        if ("phone".equals(id)) {
            switch (kind) {
                case "tap" -> phone.onTap(now);
                case "long" -> phone.onLongPress(now);
                case "down" -> phone.onButtonDown(now);
                case "up" -> phone.onButtonUp(now);
                case "shake" -> shakeUntil.put(phone, now + 1500);
                default -> {}
            }
            return;
        }
        for (Toy t : toys) {
            if (!t.id().equals(id)) continue;
            switch (kind) {
                case "long" -> { t.onButtonDown(now); t.onLongPress(now); t.onButtonUp(now); }
                case "down" -> t.onButtonDown(now);
                case "up" -> t.onButtonUp(now);
                case "shake" -> shakeUntil.put(t, now + 1500);
                default -> {}
            }
        }
    }

    synchronized void showCaller(String name) {
        callerId.show(name, now());
    }

    synchronized void setContext(Map<String, String> q) {
        if (q.containsKey("gx")) tiltX = Float.parseFloat(q.get("gx"));
        if (q.containsKey("gy")) tiltY = Float.parseFloat(q.get("gy"));
        if (q.containsKey("heading")) heading = Float.parseFloat(q.get("heading"));
        if (q.containsKey("battery")) ctx.batteryPercent = Integer.parseInt(q.get("battery"));
        if (q.containsKey("charging")) ctx.charging = "1".equals(q.get("charging"));
        if (q.containsKey("timer")) {
            long s = Long.parseLong(q.get("timer"));
            long nowEpoch = System.currentTimeMillis();
            if (s > 0) {
                Hourglass.timerGone();
                Hourglass.timerRunning(nowEpoch + s * 1000, nowEpoch);
            } else {
                Hourglass.timerGone();
            }
        }
    }

    String toysJson() {
        StringBuilder b = new StringBuilder("[");
        for (Toy t : toys) {
            if (b.length() > 1) b.append(',');
            boolean shakes = t.sensors() == Toy.SENSOR_GRAVITY;
            b.append("{\"id\":\"").append(t.id()).append("\",\"name\":\"")
                    .append(NAMES.getOrDefault(t.id(), t.id())).append("\",\"shake\":").append(shakes)
                    .append('}');
        }
        return b.append(']').toString();
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> m = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(body);
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8766;
        Path page = Path.of(args.length > 1 ? args[1] : "sim.html");
        org.openglyph.core.GlyphText.setRasterizer(new AwtTextRasterizer());
        ToySim sim = new ToySim();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                switch (path) {
                    case "/", "/index.html" -> send(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(page));
                    case "/frames" -> send(ex, 200, "application/octet-stream", sim.frames());
                    case "/toys" -> send(ex, 200, "application/json",
                            sim.toysJson().getBytes(StandardCharsets.UTF_8));
                    case "/press" -> {
                        Map<String, String> q = query(ex.getRequestURI());
                        sim.press(q.getOrDefault("toy", ""), q.getOrDefault("kind", ""));
                        send(ex, 204, "text/plain", new byte[0]);
                    }
                    case "/caller" -> {
                        sim.showCaller(query(ex.getRequestURI()).getOrDefault("name", ""));
                        send(ex, 204, "text/plain", new byte[0]);
                    }
                    case "/ctx" -> {
                        sim.setContext(query(ex.getRequestURI()));
                        send(ex, 204, "text/plain", new byte[0]);
                    }
                    default -> send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
                send(ex, 500, "text/plain", e.toString().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("toy simulator on http://localhost:" + port + "/");
    }
}
