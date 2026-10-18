package org.openglyph.tools;

import org.openglyph.core.Effect;
import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;
import org.openglyph.core.Renderer;
import org.openglyph.core.SysfsMatrixSink;
import org.openglyph.effects.Effects;

/**
 * Runs the real renderer and effects against the real driver, without the Android service.
 * Started as root through app_process by tools/device-demo.sh.
 *
 *   pulse | charge <percent> | call <seconds> | smiley <seconds> | priority | all
 */
public final class DeviceDemo {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        Renderer r = new Renderer(new SysfsMatrixSink(SysfsMatrixSink.findDir()), DeviceDemo::now);
        Effects.NotificationPulse pulse = new Effects.NotificationPulse();
        Effects.ChargingLevel charge = new Effects.ChargingLevel();
        Effects.IncomingCall call = new Effects.IncomingCall();
        Effects.ClientFrame smiley = new Effects.ClientFrame(Effect.PRIORITY_TOY);
        r.add(pulse);
        r.add(charge);
        r.add(call);
        r.add(smiley);
        r.setBrightness(args.length > 2 ? Integer.parseInt(args[2]) : 120);
        r.start();
        try {
            switch (mode) {
                case "pulse" -> runPulse(r, pulse);
                case "charge" -> runCharge(r, charge, args.length > 1 ? Integer.parseInt(args[1]) : 65);
                case "call" -> runCall(r, call, args.length > 1 ? Integer.parseInt(args[1]) : 4);
                case "smiley" -> runSmiley(r, smiley, args.length > 1 ? Integer.parseInt(args[1]) : 3);
                case "priority" -> runPriority(r, smiley, pulse, call);
                default -> {
                    runPulse(r, pulse);
                    runCharge(r, charge, 65);
                    runCall(r, call, 4);
                    runSmiley(r, smiley, 3);
                    runPriority(r, smiley, pulse, call);
                }
            }
        } finally {
            r.stop(); // blanks the matrix and cuts power
        }
        System.out.println("done");
    }

    static long now() {
        return System.nanoTime() / 1_000_000;
    }

    static void step(String what) {
        System.out.println(">> " + what);
    }

    static void runPulse(Renderer r, Effects.NotificationPulse pulse) throws InterruptedException {
        step("notification pulse x2");
        for (int i = 0; i < 2; i++) {
            pulse.trigger(now());
            r.invalidate();
            Thread.sleep(2000);
        }
    }

    static void runCharge(Renderer r, Effects.ChargingLevel charge, int pct) throws InterruptedException {
        step("charging level " + pct + "%");
        charge.trigger(now(), pct);
        r.invalidate();
        Thread.sleep(3500);
    }

    static void runCall(Renderer r, Effects.IncomingCall call, int seconds) throws InterruptedException {
        step("incoming call " + seconds + " s");
        call.setRinging(true, now());
        r.invalidate();
        Thread.sleep(seconds * 1000L);
        call.setRinging(false, now());
        r.invalidate();
        Thread.sleep(500);
    }

    static void runSmiley(Renderer r, Effects.ClientFrame smiley, int seconds) throws InterruptedException {
        step("client frame (smiley) " + seconds + " s");
        smiley.show(smileyGrid());
        r.invalidate();
        Thread.sleep(seconds * 1000L);
        smiley.clear();
        r.invalidate();
        Thread.sleep(500);
    }

    /** Smiley stays up; a notification and then a call must cover it and give it back. */
    static void runPriority(Renderer r, Effects.ClientFrame smiley, Effects.NotificationPulse pulse,
            Effects.IncomingCall call) throws InterruptedException {
        step("priority: smiley -> pulse on top -> call on top -> smiley back");
        smiley.show(smileyGrid());
        r.invalidate();
        Thread.sleep(1500);
        pulse.trigger(now());
        r.invalidate();
        Thread.sleep(2000);
        call.setRinging(true, now());
        r.invalidate();
        Thread.sleep(2500);
        call.setRinging(false, now());
        r.invalidate();
        Thread.sleep(1500);
        smiley.clear();
        r.invalidate();
    }

    static int[] smileyGrid() {
        Frame f = new Frame();
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) {
                double d = Math.hypot(x - 12, y - 12);
                boolean ring = d > 10.2 && d < 11.6;
                boolean eye = Math.hypot(x - 8, y - 8) < 1.6 || Math.hypot(x - 16, y - 8) < 1.6;
                boolean mouth = y >= 14 && Math.abs(Math.hypot(x - 12, y - 11) - 6) < 0.8;
                if (ring || eye || mouth) f.set(x, y, Frame.MAX);
            }
        }
        int[] grid = new int[MatrixLayout.GRID_SIZE];
        for (int y = 0; y < MatrixLayout.HEIGHT; y++) {
            for (int x = 0; x < MatrixLayout.WIDTH; x++) grid[y * MatrixLayout.WIDTH + x] = f.get(x, y);
        }
        return grid;
    }
}
