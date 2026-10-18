package org.openglyph.core;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.openglyph.effects.Effects;

/**
 * Plain-JDK tests for everything that does not need a phone. Run with tests/run.sh.
 * Package-private access is why this lives in org.openglyph.core.
 */
public final class HostTests {
    static int failures;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        } else {
            System.out.println("ok   " + what);
        }
    }

    static final class FakeSink implements MatrixSink {
        final List<int[]> shown = new ArrayList<>();
        int offs;

        @Override public void show(int[] leds) { shown.add(leds.clone()); }
        @Override public void off() { offs++; }
    }

    public static void main(String[] args) throws Exception {
        layout();
        sysfsSink();
        renderer();
        effects();
        timerLed();
        recordingDetector();
        toys();
        nothingStyleFeatures();
        remoteToys();
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static boolean litOutsideMatrix(Frame f) {
        for (int y = 0; y < 25; y++)
            for (int x = 0; x < 25; x++)
                if (f.get(x, y) != 0 && !MatrixLayout.exists(x, y)) return true;
        return false;
    }

    static void toys() {
        org.openglyph.toys.Toy.Context ctx = new org.openglyph.toys.Toy.Context();
        ctx.hour = 8; ctx.minute = 47; ctx.batteryPercent = 100; ctx.charging = true;
        org.openglyph.toys.Toys.Clock clock = new org.openglyph.toys.Toys.Clock();
        org.openglyph.toys.Toys.Battery battery = new org.openglyph.toys.Toys.Battery();
        org.openglyph.toys.Toys.Stopwatch sw = new org.openglyph.toys.Toys.Stopwatch();

        Frame f = new Frame();
        clock.render(f, 0, ctx);
        check(!f.isDark() && !litOutsideMatrix(f), "clock fits inside the round matrix");
        System.out.println("\nClock 08:47:\n" + f.toAscii());
        f.clear();
        battery.render(f, 500, ctx);
        check(!litOutsideMatrix(f), "battery 100% (3 small digits + ring) fits");
        ctx.batteryPercent = 7;
        f.clear();
        battery.render(f, 500, ctx);
        check(!f.isDark() && !litOutsideMatrix(f), "battery 7% fits");

        sw.onLongPress(1000);
        check(sw.isRunning() && sw.keepsScreenOn(), "long press starts the stopwatch");
        check(sw.elapsedMs(3500) == 2500, "stopwatch counts");
        sw.onLongPress(4000);
        check(!sw.isRunning() && sw.elapsedMs(9000) == 3000, "second long press stops it");
        sw.onLongPress(9000);
        check(sw.elapsedMs(9000) == 0 && !sw.isRunning(), "third long press resets it");

        org.openglyph.toys.ToyHost host =
                new org.openglyph.toys.ToyHost(() -> ctx, clock, battery, sw);
        check(!host.isActive(0), "host hidden until the button is pressed");
        host.onTap(100);
        check(host.isActive(100) && host.current() == clock, "first tap shows the first toy");
        host.onTap(200);
        check(host.current() == battery, "tap while visible moves to the next toy");
        check(!host.isActive(200 + org.openglyph.toys.ToyHost.IDLE_MS), "hides after the idle timeout");
        host.onTap(20000);
        check(host.current() == battery, "tap after hiding shows the same toy again");
        host.setEnabled(java.util.Set.of("clock", "stopwatch"));
        check(host.current() == clock, "disabling the current toy falls back to the first enabled");
        host.onTap(20100);
        host.onLongPress(20200);
        check(host.current() == sw && sw.isRunning(), "long press reaches the current toy");
        check(host.isActive(20200 + 60000), "running stopwatch keeps the toy on screen");
        host.setEnabled(java.util.Set.of());
        host.onTap(90000);
        check(!host.isActive(90000), "no enabled toys: nothing shows");
    }

    static void remoteToys() {
        List<String> log = new ArrayList<>();
        org.openglyph.toys.RemoteToy.Listener l = new org.openglyph.toys.RemoteToy.Listener() {
            @Override public void onShow(org.openglyph.toys.RemoteToy t) { log.add("show " + t.label()); }
            @Override public void onHide(org.openglyph.toys.RemoteToy t) { log.add("hide " + t.label()); }
            @Override public void onEvent(org.openglyph.toys.RemoteToy t, String e) { log.add(e + " " + t.label()); }
        };
        org.openglyph.toys.RemoteToy dice = new org.openglyph.toys.RemoteToy(
                "com.example.Dice", "com.example.dice.DiceToy", "Dice", true, l);
        org.openglyph.toys.RemoteToy noLong = new org.openglyph.toys.RemoteToy(
                "org.other", "org.other.T", "Other", false, l);
        check(dice.id().equals("nt_com.example.dice_dicetoy"), "remote toy id is settings-safe");

        org.openglyph.toys.Toy.Context ctx = new org.openglyph.toys.Toy.Context();
        org.openglyph.toys.Toys.Clock clock = new org.openglyph.toys.Toys.Clock();
        org.openglyph.toys.ToyHost host = new org.openglyph.toys.ToyHost(() -> ctx, clock);
        host.setExtraToys(List.of(dice, noLong), 1);
        check(host.allToys().size() == 3, "SDK toys are added after the built-in ones");

        host.onTap(0);          // clock
        host.onTap(100);        // dice
        check(log.equals(List.of("show Dice")), "the SDK toy is bound when it comes on screen");
        Frame f = new Frame();
        host.render(f, 100);
        check(f.isDark(), "nothing is drawn until the app sends a frame");
        int[] grid = new int[625];
        grid[12 * 25 + 12] = 4095;
        dice.setFrame(grid);
        f.clear();
        host.render(f, 150);
        check(f.get(12, 12) == 4095, "the app's frame is shown");

        host.onButtonDown(200);
        host.onLongPress(300);
        host.onButtonUp(310);
        check(log.subList(1, 4).equals(List.of("action_down Dice", "change Dice", "action_up Dice")),
                "button events reach the toy as action_down / change / action_up");

        host.onTap(400);        // other
        check(log.get(4).equals("hide Dice") && log.get(5).equals("show Other"),
                "switching unbinds the old toy before binding the next");
        int before = log.size();
        host.onLongPress(500);
        check(log.size() == before, "no change event for a toy without long-press support");

        check(!host.isActive(500 + org.openglyph.toys.ToyHost.IDLE_MS + 1)
                && log.get(log.size() - 1).equals("hide Other"), "timing out unbinds the toy");

        host.setExtraToys(List.of(), 1);
        check(host.allToys().size() == 1, "uninstalled SDK toys disappear");
    }

    static void nothingStyleFeatures() {
        // Per-app notification patterns
        for (Effects.Pattern p : Effects.Pattern.values()) {
            Effects.NotificationPulse pulse = new Effects.NotificationPulse();
            pulse.trigger(0, p);
            Frame f = new Frame();
            pulse.render(f, 200);
            check(!f.isDark(), "pattern " + p + " draws something");
        }
        check(Effects.Pattern.parse("sweep", Effects.Pattern.RING) == Effects.Pattern.SWEEP
                && Effects.Pattern.parse("bogus", Effects.Pattern.RING) == Effects.Pattern.RING,
                "pattern parsing with fallback");
        java.util.Map<String, String> m = AppPatterns.parse("com.whatsapp=SWEEP;bad;org.tg=DOT;=X");
        check(m.size() == 2 && m.get("com.whatsapp").equals("SWEEP"), "app pattern setting parses");
        check(AppPatterns.parse(AppPatterns.format(m)).equals(m), "app pattern setting round-trips");
        check(AppPatterns.parseSet("a, b,,c").equals(new java.util.LinkedHashSet<>(java.util.List.of("a", "b", "c"))),
                "essential app set parses");

        // Always-on policy
        check(AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, true, false, false, 14, 23, 7),
                "ALWAYS shows with the screen off");
        check(!AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, false, false, false, 14, 23, 7),
                "never while the screen is on");
        check(!AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, true, false, false, 2, 23, 7),
                "quiet period across midnight hides it");
        check(AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, true, false, false, 7, 23, 7),
                "quiet period ends at its end hour");
        check(AodPolicy.shouldShow(AodPolicy.Mode.FACE_DOWN, true, true, false, 14, -1, -1)
                && !AodPolicy.shouldShow(AodPolicy.Mode.FACE_DOWN, true, false, false, 14, -1, -1),
                "FACE_DOWN follows orientation");
        check(AodPolicy.shouldShow(AodPolicy.Mode.CHARGING, true, false, true, 14, -1, -1),
                "CHARGING follows the charger");
        check(AodPolicy.isFaceDown(0.3f, -0.5f, -9.7f) && !AodPolicy.isFaceDown(0, 0, 9.8f)
                && !AodPolicy.isFaceDown(6f, 0, -7f), "face-down detection");
        check(!AodPolicy.isFaceUp(0, 0, -6f) && AodPolicy.isFaceUp(0, 0, 2f), "face-up hysteresis");

        // Idle layer: dim clock + essential dot
        org.openglyph.toys.Toy.Context ctx = new org.openglyph.toys.Toy.Context();
        ctx.hour = 12; ctx.minute = 34;
        org.openglyph.toys.IdleLayer idle = new org.openglyph.toys.IdleLayer(() -> ctx);
        check(!idle.isActive(0), "idle layer off by default");
        idle.setAlwaysOn(true);
        Frame f = new Frame();
        idle.render(f, 0);
        int max = 0;
        for (int y = 0; y < 25; y++) for (int x = 0; x < 25; x++) max = Math.max(max, f.get(x, y));
        check(max > 0 && max < Frame.MAX / 3, "always-on clock is dimmed");
        idle.setAlwaysOn(false);
        idle.setEssential(true);
        f.clear();
        idle.render(f, 0);
        check(idle.isActive(0) && f.get(12, 22) > 0 && f.get(12, 4) == 0, "essential dot alone");
        check(new Effects.NotificationPulse().priority() > idle.priority(), "notifications draw over idle");
    }

    static void timerLed() throws Exception {
        File dir = Files.createTempDirectory("leds-red").toFile();
        TimerLed led = new TimerLed(dir.getPath());
        led.blink(500, 500);
        check(Files.readString(new File(dir, "trigger").toPath()).equals("timer"), "blink selects the timer trigger");
        check(Files.readString(new File(dir, "delay_on").toPath()).equals("500")
                && Files.readString(new File(dir, "delay_off").toPath()).equals("500"), "blink sets the period");
        check(led.isBlinking(), "led reports blinking");
        led.off();
        check(Files.readString(new File(dir, "trigger").toPath()).equals("none") && !led.isBlinking(),
                "off removes the trigger");
        check(!new File(dir, "brightness").exists(), "brightness (root-owned) is never written");
    }

    static void recordingDetector() {
        RecordingDetector d = new RecordingDetector();
        check(!d.isRecordingVideo(), "idle: not recording");
        d.cameraOpened("0", 10100);
        check(!d.isRecordingVideo(), "camera open without audio (photo) is not recording");
        d.setRecordingUids(java.util.Set.of(10200));
        check(!d.isRecordingVideo(), "another app recording audio does not count");
        d.setRecordingUids(java.util.Set.of(10100, 10200));
        check(d.isRecordingVideo(), "camera + audio from the same app is a video recording");
        d.setRecordingUids(java.util.Set.of());
        check(!d.isRecordingVideo(), "stops when audio capture ends");
        d.setRecordingUids(java.util.Set.of(10100));
        d.cameraClosed("0");
        check(!d.isRecordingVideo(), "stops when the camera closes");
        d.cameraOpened("1", -1);
        d.setRecordingUids(java.util.Set.of(-1));
        check(!d.isRecordingVideo(), "unknown uid (-1) never matches");
    }

    static void layout() {
        check(MatrixLayout.LED_COUNT == 489, "489 LEDs, matching LEDS_PIXEL_TOTAL_COUNT");
        check(!MatrixLayout.exists(0, 0) && !MatrixLayout.exists(24, 24), "corners have no LED");
        check(MatrixLayout.exists(9, 0) && !MatrixLayout.exists(8, 0), "row 0 starts at x=9");
        check(MatrixLayout.ledIndex(9, 0) == 0, "first LED is top-left of row 0");
        check(MatrixLayout.ledIndex(15, 24) == 488, "last LED is end of row 24");
        check(MatrixLayout.ledIndex(0, 12) == 7 + 11 + 15 + 17 + 19 + 21 + 21 + 23 + 23 + 25 + 25 + 25,
                "middle row starts after 12 rows");
        boolean roundTrip = true;
        for (int i = 0; i < MatrixLayout.LED_COUNT; i++) {
            int g = MatrixLayout.gridIndex(i);
            roundTrip &= MatrixLayout.ledIndex(g % 25, g / 25) == i;
        }
        check(roundTrip, "grid <-> LED mapping round-trips");
        boolean symmetric = true;
        for (int y = 0; y < 25; y++) {
            for (int x = 0; x < 25; x++) {
                symmetric &= MatrixLayout.exists(x, y) == MatrixLayout.exists(24 - x, y)
                        && MatrixLayout.exists(x, y) == MatrixLayout.exists(y, x);
            }
        }
        check(symmetric, "outline is symmetric in both axes and the diagonal");
    }

    static void sysfsSink() throws Exception {
        File dir = Files.createTempDirectory("matrix-leds").toFile();
        SysfsMatrixSink sink = new SysfsMatrixSink(dir.getPath());
        int[] leds = new int[MatrixLayout.LED_COUNT];
        leds[0] = 5000;
        leds[1] = -3;
        leds[488] = 100;
        sink.show(leds);
        String frame = Files.readString(new File(dir, "frame_brightness").toPath());
        String[] tokens = frame.split(" ");
        check(tokens.length == 489, "frame_brightness gets exactly 489 values (driver has no bounds check)");
        check(tokens[0].equals("2047") && tokens[1].equals("0") && tokens[488].equals("100"),
                "values clamped to 0..2047");
        check(!frame.endsWith(" ") && !frame.contains("\n"), "no trailing separator");
        check(Files.readString(new File(dir, "led_power_on").toPath()).equals("1"), "powers on before first frame");
        sink.off();
        check(Files.readString(new File(dir, "all_brightness").toPath()).equals("0"), "off writes all_brightness=0");
        boolean threw = false;
        try {
            sink.show(new int[490]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "refuses frames that are not 489 LEDs");
    }

    static void renderer() throws Exception {
        FakeSink sink = new FakeSink();
        Renderer r = new Renderer(sink, () -> 0);
        check(r.tick(0) == -1 && sink.shown.isEmpty(), "idle renderer writes nothing");

        Effects.ClientFrame low = new Effects.ClientFrame(Effect.PRIORITY_TOY);
        Effects.ClientFrame high = new Effects.ClientFrame(Effect.PRIORITY_CALL);
        int[] a = new int[625];
        int[] b = new int[625];
        java.util.Arrays.fill(a, Frame.MAX);
        java.util.Arrays.fill(b, Frame.MAX / 2);
        low.show(a);
        high.show(b);
        r.add(low);
        r.add(high);
        r.tick(0);
        check(sink.shown.size() == 1 && sink.shown.get(0)[0] == 1023, "highest priority wins (half of 2047)");
        r.tick(10);
        check(sink.shown.size() == 1, "unchanged frame is not rewritten");
        high.clear();
        r.tick(20);
        check(sink.shown.size() == 2 && sink.shown.get(1)[0] == 2047, "falls back to lower priority");
        r.setBrightness(128);
        r.tick(30);
        check(sink.shown.get(2)[0] == 2047 * 128 / 255, "global brightness scales output");
        low.clear();
        r.tick(40);
        check(sink.offs == 1, "powers off when nothing is active");
    }

    static void effects() {
        Effects.NotificationPulse pulse = new Effects.NotificationPulse();
        check(!pulse.isActive(0), "pulse idle before trigger");
        pulse.trigger(1000);
        check(pulse.isActive(1000) && pulse.isActive(2599) && !pulse.isActive(2600), "pulse lasts 1.6 s");
        Frame f = new Frame();
        pulse.render(f, 1000 + 400);
        check(!f.isDark(), "pulse draws something mid-animation");

        Effects.ChargingLevel charge = new Effects.ChargingLevel();
        charge.trigger(0, 50);
        f.clear();
        charge.render(f, 1000);
        check(f.get(12, 24) > 0 && f.get(12, 0) == 0, "50% fills the bottom, not the top");

        Effects.IncomingCall call = new Effects.IncomingCall();
        call.setRinging(true, 0);
        check(call.isActive(99999), "call stays active while ringing");
        call.setRinging(false, 5);
        check(!call.isActive(6), "call stops when ringing ends");

        f.clear();
        pulse.render(f, 1400);
        System.out.println("\nPulse frame at 400 ms:\n" + f.toAscii());
    }
}
