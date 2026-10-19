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
        stockToys();
        hourglass();
        generative();
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
        check(sw.isRunning() && sw.keepsScreenOn(2000), "long press starts the stopwatch");
        check(sw.elapsedMs(3500) == 2500, "stopwatch counts");
        sw.onLongPress(4000);
        check(!sw.isRunning() && sw.elapsedMs(9000) == 3000, "second long press stops it");
        sw.onLongPress(9000);
        check(sw.elapsedMs(9000) == 0 && !sw.isRunning(), "third long press resets it");

        org.openglyph.toys.ToyHost host =
                new org.openglyph.toys.ToyHost(() -> ctx, clock, battery, sw);
        check(!host.isActive(0) && host.selected() == null, "nothing selected or shown at first");
        host.onTap(100);
        check(host.isActive(100) && host.selected() == clock, "first press selects and shows the first toy");
        host.onTap(200);
        check(host.selected() == battery, "press while shown selects the next toy");
        check(!host.isActive(200 + org.openglyph.toys.ToyHost.IDLE_MS), "hides after the idle timeout");
        check(host.selected() == null, "hiding deselects the toy (nothing runs in the background)");
        host.onTap(20000);
        check(host.selected() == battery && host.isActive(20000), "press after hiding shows the same toy again");
        host.onTap(20050);
        host.onLongPress(20200);
        check(host.selected() == sw && sw.isRunning(), "long press reaches the selected toy");
        check(host.isActive(20200 + 60000), "running stopwatch keeps the toy on screen");
        host.setEnabled(java.util.Set.of("clock", "battery"));
        check(host.selected() == null, "disabling the selected toy deselects it");
        sw.onLongPress(30000); // stop
        sw.onLongPress(30000); // reset
        host.setOrder(java.util.List.of("stopwatch", "nope", "clock"));
        host.deselect();
        host.onTap(40000);
        check(host.selected() == clock, "reordering keeps the position on the same toy");
        host.onTap(40100);
        check(host.selected() == sw, "toy order from Manage toys is followed (unknown ids skipped)");
        host.show("battery", 40200);
        check(host.selected() == battery && host.isActive(40300), "Settings can show any toy");
        host.setEnabled(java.util.Set.of());
        host.onTap(90000);
        check(!host.isActive(90000), "no enabled toys: nothing shows");
    }

    static void remoteToys() {
        List<String> log = new ArrayList<>();
        org.openglyph.toys.RemoteToy.Listener l = new org.openglyph.toys.RemoteToy.Listener() {
            @Override public void onSelect(org.openglyph.toys.RemoteToy t) { log.add("bind " + t.label()); }
            @Override public void onDeselect(org.openglyph.toys.RemoteToy t) { log.add("unbind " + t.label()); }
            @Override public void onEvent(org.openglyph.toys.RemoteToy t, String e) { log.add(e + " " + t.label()); }
        };
        org.openglyph.toys.RemoteToy timer = new org.openglyph.toys.RemoteToy(
                "com.example.Timer", "com.example.timer.TimerToy", "Timer", "", true, true, l);
        org.openglyph.toys.RemoteToy noLong = new org.openglyph.toys.RemoteToy(
                "org.other", "org.other.T", "Other", "", false, false, l);
        check(timer.id().equals("nt_com.example.timer_timertoy"), "SDK toy id is settings-safe");

        org.openglyph.toys.Toy.Context ctx = new org.openglyph.toys.Toy.Context();
        org.openglyph.toys.Toys.Clock clock = new org.openglyph.toys.Toys.Clock();
        org.openglyph.toys.ToyHost host = new org.openglyph.toys.ToyHost(() -> ctx, clock);
        host.setExtraToys(List.of(timer, noLong), 1);
        check(host.allToys().size() == 3, "SDK toys are added after the built-in ones");

        host.onTap(0);          // clock
        host.onTap(100);        // timer
        check(log.equals(List.of("bind Timer")), "an SDK toy is bound when it is selected");
        Frame f = new Frame();
        host.render(f, 100);
        check(f.isDark(), "nothing is drawn until the toy sends a frame");
        int[] grid = new int[625];
        grid[12 * 25 + 12] = 4095;
        timer.setFrame(grid);
        f.clear();
        host.render(f, 150);
        check(f.get(12, 12) == 4095, "the toy's frame is shown");

        host.onButtonDown(200);
        host.onLongPress(300);
        host.onButtonUp(310);
        check(log.subList(1, 4).equals(List.of("action_down Timer", "change Timer", "action_up Timer")),
                "button events reach the toy as action_down / change / action_up");

        timer.setNoTimeout(true);
        check(host.isActive(999999) && log.size() == 4, "setGlyphMatrixTimeout(false) keeps the toy on screen");
        timer.setNoTimeout(false);
        check(!host.isActive(999999) && log.get(4).equals("unbind Timer"),
                "when the matrix times out the toy is unbound");
        host.onLongPress(1000000); // a long press while nothing shows: show the toy in turn
        check(log.get(5).equals("bind Timer") && host.isActive(1000001),
                "the next press starts the same toy afresh");
        host.onTap(1000100);     // other
        check(log.get(6).equals("unbind Timer") && log.get(7).equals("bind Other"),
                "selecting another toy unbinds the old one first");
        int before = log.size();
        host.onLongPress(1000200);
        check(log.size() == before, "no change event for a toy without long-press support");

        host.setExtraToys(List.of(timer), 1);
        check(log.get(log.size() - 1).equals("unbind Other") && host.allToys().size() == 2,
                "an uninstalled selected toy is unbound and removed");
        check(timer.aodSupported() && !noLong.aodSupported(), "aod_support is kept");
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
        check(AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, true, false, false, false),
                "ALWAYS shows with the screen off");
        check(!AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, false, false, false, false),
                "never while the screen is on");
        check(!AodPolicy.shouldShow(AodPolicy.Mode.ALWAYS, true, false, false, true),
                "bedtime hides it");
        check(AodPolicy.shouldShow(AodPolicy.Mode.FACE_DOWN, true, true, false, false)
                && !AodPolicy.shouldShow(AodPolicy.Mode.FACE_DOWN, true, false, false, false),
                "FACE_DOWN follows orientation");
        check(AodPolicy.shouldShow(AodPolicy.Mode.CHARGING, true, false, true, false),
                "CHARGING follows the charger");

        // Bedtime schedule (Monday = 0)
        int mon = 0, tue = 1, sun = 6;
        check(Bedtime.isActive(23 * 60, 7 * 60, Bedtime.EVERY_DAY, tue, 2 * 60), "overnight bedtime after midnight");
        check(!Bedtime.isActive(23 * 60, 7 * 60, Bedtime.EVERY_DAY, tue, 7 * 60), "bedtime ends at its end time");
        check(Bedtime.isActive(23 * 60, 7 * 60, Bedtime.EVERY_DAY, tue, 23 * 60 + 30), "bedtime starts at its start time");
        check(Bedtime.isActive(23 * 60, 7 * 60, 1 << sun, mon, 3 * 60)
                && !Bedtime.isActive(23 * 60, 7 * 60, 1 << sun, mon, 23 * 60 + 30),
                "an overnight bedtime belongs to the day it starts on");
        check(Bedtime.isActive(13 * 60, 14 * 60, Bedtime.WEEKDAYS, tue, 13 * 60 + 5)
                && !Bedtime.isActive(13 * 60, 14 * 60, Bedtime.WEEKDAYS, sun, 13 * 60 + 5), "daytime schedule on weekdays");
        check(!Bedtime.isActive(600, 600, Bedtime.EVERY_DAY, mon, 600) && !Bedtime.isActive(1, 600, 0, mon, 300),
                "empty schedule is never on");
        check(Bedtime.minutesToNextChange(23 * 60, 7 * 60, 22 * 60 + 59) == 1
                && Bedtime.minutesToNextChange(23 * 60, 7 * 60, 23 * 60) == 8 * 60, "next bedtime change");
        check(Bedtime.dayIndex(java.util.Calendar.MONDAY) == 0 && Bedtime.dayIndex(java.util.Calendar.SUNDAY) == 6,
                "calendar day mapping");

        // Essential Notifications rules
        EssentialRule rule = new EssentialRule();
        rule.name = "Work | chat, \"urgent\"\n";
        rule.apps.add("com.slack");
        rule.people.add("Ayşe");
        rule.keywords.add("help");
        rule.keywords.add("acil, hemen");
        rule.icon = "star";
        EssentialRule open = new EssentialRule();
        open.enabled = false;
        java.util.List<EssentialRule> rules = EssentialRule.parse(EssentialRule.format(java.util.List.of(rule, open)));
        check(rules.size() == 2 && rules.get(0).format().equals(rule.format()) && !rules.get(1).enabled
                && rules.get(0).name.equals(rule.name) && rules.get(0).keywords.get(1).equals("acil, hemen"),
                "rules round-trip with separators in their text");
        EssentialRule.Notice fromAyse = new EssentialRule.Notice("com.slack", "#general",
                "Can you HELP me?", java.util.List.of("Ayşe Yılmaz"));
        check(rule.matches(fromAyse), "rule matches app + person + keyword");
        check(!rule.matches(new EssentialRule.Notice("com.whatsapp", "Ayşe", "help", null)), "other app does not match");
        check(!rule.matches(new EssentialRule.Notice("com.slack", "Bob", "help", java.util.List.of("Bob"))),
                "other person does not match");
        check(!rule.matches(new EssentialRule.Notice("com.slack", "Ayşe", "hello", null)), "missing keyword does not match");
        check(rule.matches(new EssentialRule.Notice("com.slack", "Ayşe", "acil, hemen bak", null)),
                "person found in the title");
        EssentialRule any = new EssentialRule();
        check(any.matches(new EssentialRule.Notice("x", null, null, null)) && !open.matches(fromAyse),
                "empty rule matches everything, disabled rule nothing");
        check(EssentialRule.firstMatch(java.util.List.of(open, rule, any), fromAyse) == rule, "first enabled match wins");

        // Icons
        for (String name : GlyphIcons.names()) {
            int[] g = GlyphIcons.render(name, Frame.MAX);
            Frame icon = new Frame();
            icon.setGrid(g);
            boolean inside = true;
            for (int i = 0; i < g.length; i++) if (g[i] > 0 && !MatrixLayout.exists(i % 25, i / 25)) inside = false;
            check(!icon.isDark() && inside, "icon " + name + " is drawn on real LEDs");
        }
        check(GlyphIcons.render("nope", 1) == null, "unknown icon");
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
        idle.setEssential(GlyphIcons.render("bell", Frame.MAX));
        f.clear();
        idle.render(f, 0);
        check(idle.isActive(0) && f.get(12, 5) > 0 && f.get(12, 5) < Frame.MAX, "essential icon alone, dimmed");
        idle.setAlwaysOn(true);
        f.clear();
        idle.render(f, 0);
        check(f.get(12, 5) > 0, "essential icon replaces the always-on clock");
        idle.setAlwaysOn(false);
        idle.setEssential(null);
        check(!idle.isActive(0), "idle layer off again");
        check(new Effects.NotificationPulse().priority() > idle.priority(), "notifications draw over idle");
    }

    static void hourglass() {
        org.openglyph.toys.Hourglass toy = new org.openglyph.toys.Hourglass();
        org.openglyph.toys.Toy.Context ctx = new org.openglyph.toys.Toy.Context();
        long epoch = 1_800_000_000_000L;
        org.openglyph.toys.Hourglass.timerRunning(epoch + 60_000, epoch);
        int[] start = sandHalves(toy, ctx, epoch, 0);
        int[] half = sandHalves(toy, ctx, epoch + 30_000, 30_000);
        check(start[1] == 0 && start[0] > 30, "hourglass starts with all sand on top: " + start[0]);
        check(half[0] + half[1] == start[0], "hourglass keeps every grain: " + (half[0] + half[1]));
        check(Math.abs(half[1] - start[0] / 2) <= 2, "half way, half the sand is below: " + half[1]);
        org.openglyph.toys.Hourglass.timerGone();
    }

    /** {grains above the neck, grains below} as the toy draws them. */
    static int[] sandHalves(org.openglyph.toys.Toy toy, org.openglyph.toys.Toy.Context ctx,
            long epoch, long nowMs) {
        ctx.epochMs = epoch;
        Frame f = new Frame();
        toy.render(f, nowMs, ctx);
        int top = 0, bottom = 0;
        for (int k = 0; k < 625; k++) {
            if (f.px[k] != Frame.MAX) continue;
            if (k / 25 < 12) top++; else if (k / 25 > 12) bottom++;
        }
        return new int[] {top, bottom};
    }

    static void stockToys() {
        java.util.List<org.openglyph.toys.Toy> all = org.openglyph.toys.Toys.all();
        java.util.List<String> ids = new ArrayList<>();
        for (org.openglyph.toys.Toy t : all) ids.add(t.id());
        check(ids.equals(java.util.List.of("clock", "battery", "solar", "mirror", "bottle", "rps",
                "magic8", "stopwatch", "hourglass", "leveller", "compass", "moon", "fly", "off")),
                "the stock Phone (3) toy set (Nothing OS 4: + Hourglass), Off last: " + ids);
        org.openglyph.toys.Toy.Context ctx = new org.openglyph.toys.Toy.Context();
        ctx.hour = 21; ctx.minute = 58; ctx.batteryPercent = 64;
        ctx.epochMs = 1760781600000L; // 2025-10-18 10:00 UTC
        ctx.utcOffsetHours = 3;
        ctx.hasGravity = true; ctx.gx = 3; ctx.gy = -2; ctx.gz = -9;
        ctx.hasHeading = true; ctx.heading = 90;
        for (org.openglyph.toys.Toy t : all) {
            Frame f = new Frame();
            t.onLongPress(1000);
            t.render(f, 1500, ctx);
            t.render(f, 3000, ctx);
            check(t.id().equals("off") ? f.isDark() : !f.isDark(), "toy " + t.id() + " draws");
        }
        Frame clock = new Frame();
        new org.openglyph.toys.Toys.Clock().render(clock, 0, ctx);
        System.out.println("\n7-segment clock 21:58:\n" + clock.toAscii());

        // Sun and moon
        double noonIst = org.openglyph.toys.Sky.sunElevation(41.0, 29.0, 1760781600000L); // 13:00 local
        double midnightIst = org.openglyph.toys.Sky.sunElevation(41.0, 29.0, 1760734800000L); // 00:00 local
        check(noonIst > 30 && noonIst < 50 && midnightIst < -30,
                "sun elevation in Istanbul mid-October: noon " + (int) noonIst + ", midnight " + (int) midnightIst);
        double full = org.openglyph.toys.Sky.moonPhase(1759808820000L); // full moon 2025-10-07 03:47 UTC
        check(Math.abs(full - 0.5) < 0.03, "moon phase on a known full moon: " + full);
        check(Math.abs(org.openglyph.toys.Sky.moonPhase(1761049500000L)) < 0.03
                || org.openglyph.toys.Sky.moonPhase(1761049500000L) > 0.97, "moon phase on a known new moon");

        // Rock, Paper, Scissors: the shake counts with dots, then the picked hand stays.
        org.openglyph.toys.Toys.RockPaperScissors rps = new org.openglyph.toys.Toys.RockPaperScissors(new java.util.Random(3));
        org.openglyph.toys.Toy.Context still = new org.openglyph.toys.Toy.Context();
        rps.onLongPress(0);
        Frame shaking = new Frame();
        rps.render(shaking, 400, still);
        check(shaking.get(10, 22) == Frame.MAX && shaking.get(12, 22) == Frame.MAX && shaking.get(14, 22) < Frame.MAX,
                "rps: two of the three count dots lit on the second bob");
        Frame shown = new Frame(), expect = new Frame();
        rps.render(shown, 1500, still);
        org.openglyph.toys.Toys.RockPaperScissors.hand(expect, rps.pick(), 0, 0.82 + 0.18 * Math.cos((1500 - 990 - 260) / 1000.0 * Math.PI * 1.6));
        check(shown.toAscii().equals(expect.toAscii()) && !shown.isDark() && rps.keepsScreenOn(3000) && !rps.keepsScreenOn(6000),
                "rps: the picked hand stays for a few seconds");
        System.out.println("\nRPS reveal:\n" + shown.toAscii());

        // Bottle spins and stops; Feed The Fly eats and scores
        org.openglyph.toys.Toys.Bottle bottle = new org.openglyph.toys.Toys.Bottle();
        bottle.onLongPress(0);
        check(bottle.keepsScreenOn(1000) && !bottle.keepsScreenOn(4000), "bottle spins for a few seconds");
        org.openglyph.toys.Toys.Leveller lev = new org.openglyph.toys.Toys.Leveller();
        Frame lf = new Frame();
        ctx.gx = 0; ctx.gy = 0;
        lev.render(lf, 0, ctx);
        check(lf.get(12, 12) == Frame.MAX, "level: bubble bright in the centre");
        check(new org.openglyph.toys.Toys.Compass().sensors() == org.openglyph.toys.Toy.SENSOR_HEADING
                && new org.openglyph.toys.Toys.Mirror().sensors() == org.openglyph.toys.Toy.SENSOR_CAMERA,
                "sensor toys ask for their sensors");
        check(new org.openglyph.toys.Toys.Clock().aodSupported() && new org.openglyph.toys.Toys.MoonPhase().aodSupported()
                && !new org.openglyph.toys.Toys.Bottle().aodSupported(), "clock and moon can be always-on");
        org.openglyph.toys.ToyHost host = new org.openglyph.toys.ToyHost(() -> ctx,
                all.toArray(new org.openglyph.toys.Toy[0]));
        host.setOrder(java.util.List.of("leveller"));
        host.onTap(0);
        check(host.neededSensors(1000) == org.openglyph.toys.Toy.SENSOR_GRAVITY
                && host.isActive(30000) && !host.isActive(61000) && host.neededSensors(61000) == 0,
                "sensor toys stay a minute, then their sensors stop");
    }

    static void generative() {
        // Pinned against the browser generator (tools/generator/generator.html) for the same inputs.
        check(Integer.toUnsignedString(Generative.hashStr("5eed1234abcd|WhatsApp|diode")).equals("2981283352")
                && Integer.toUnsignedString(Generative.hashStr("ff00ff|\u00d6deme \u015eimdi|chemical")).equals("4086021704"),
                "hash matches the browser generator");
        Generative.Theme wa = Generative.theme("5eed1234abcd", "WhatsApp", Generative.Engine.DIODE);
        StringBuilder hs = new StringBuilder();
        for (Generative.Hit h : wa.hits) hs.append(h.t).append(':').append(h.voice).append(',');
        check(wa.duration == 1724 && hs.toString().equals("0:bop,136:blip,204:bip,340:bop,544:blip,612:bip,680:bop,816:bop,884:bip,"),
                "diode score matches the browser generator");
        float[] wf = wa.synthFloat();
        check(wf.length == 76028 && Math.abs(wf[2000] - 0.23735) < 1e-4, "diode sound matches the browser generator");
        Generative.Theme ch = Generative.theme("5eed1234abcd", "WhatsApp", Generative.Engine.CHEMICAL);
        check(ch.duration == 1396 && ch.litAt(1) == 104 && ch.litAt(6) == 220, "chemical light matches the browser generator");

        for (Generative.Engine eng : Generative.Engine.values()) {
            Generative.Theme a = Generative.theme("s1", "WhatsApp", eng);
            Generative.Theme b = Generative.theme("s1", "Telegram", eng);
            Generative.Theme c = Generative.theme("s2", "WhatsApp", eng);
            check(!java.util.Arrays.equals(a.synth(), b.synth()) && !java.util.Arrays.equals(a.synth(), c.synth()),
                    eng + ": another app or another seed sounds different");
            for (int i = 0; i < 12; i++) {
                Generative.Theme t = Generative.theme("seed" + i, "App " + i, eng);
                short[] pcm = t.synth();
                int peak = 0;
                for (short v : pcm) peak = Math.max(peak, Math.abs(v));
                int lit = 0;
                for (int f = 0; f < t.frameCount(); f++) lit += t.litAt(f);
                Frame after = new Frame();
                t.render(after, t.durationMs() + 50);
                check(pcm.length > 44100 / 2 && pcm.length < 44100 * 5 && peak > 15000 && lit > 30 && after.isDark(),
                        eng + " theme " + i + ": sound, light, then dark");
            }
        }
        // Diode flashes are mirrored on X and Y.
        Frame df = new Frame();
        wa.render(df, 20);
        boolean sym = true;
        for (int y = 0; y < 25; y++) for (int x = 0; x < 25; x++) if (df.get(x, y) != df.get(24 - x, y) || df.get(x, y) != df.get(x, 24 - y)) sym = false;
        check(sym && !df.isDark(), "diode: mirror-xy symmetry");

        byte[] wav = Generative.wav(new short[] {1, -1}, 44100);
        check(wav.length == 48 && wav[0] == 'R' && wav[8] == 'W' && wav[40] == 4, "WAV header");
        Effects.NotificationPulse pulse = new Effects.NotificationPulse();
        pulse.trigger(0, wa);
        check(pulse.isActive(wa.durationMs() - 1) && !pulse.isActive(wa.durationMs() + 1),
                "generative pulse lasts as long as its sound");
        Effects.AudioBars bars = new Effects.AudioBars();
        float[] lv = new float[25];
        for (int i = 0; i < 25; i++) lv[i] = i / 24f;
        bars.setLevels(lv, 0);
        Frame bf = new Frame();
        bars.render(bf, 10);
        check(bars.isActive(100) && !bars.isActive(1000) && bf.get(12, 12) > 0 && bf.get(24, 12) > 0
                && bf.get(0, 13) == 0 && bf.get(22, 7) > 0, "music bars: per-column height, quiet when stale");
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
