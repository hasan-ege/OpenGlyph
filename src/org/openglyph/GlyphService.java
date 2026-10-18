package org.openglyph;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openglyph.core.MatrixLayout;
import org.openglyph.core.Renderer;
import org.openglyph.core.SysfsMatrixSink;
import org.openglyph.effects.Effects;
import org.openglyph.core.AppPatterns;
import org.openglyph.core.EssentialRule;
import org.openglyph.core.GlyphIcons;
import org.openglyph.core.Frame;
import org.openglyph.toys.IdleLayer;
import org.openglyph.toys.RemoteToy;
import org.openglyph.toys.Toy;
import org.openglyph.toys.ToyHost;
import org.openglyph.toys.Toys;

/**
 * Owns the Glyph Matrix: one renderer, the built-in effects, and the binder API for apps.
 *
 * Settings: see {@link GlyphSettings} (Settings.Secure, also editable with
 * `adb shell settings put secure ...`).
 */
public final class GlyphService extends Service {
    static final String TAG = "OpenGlyph";
    static final String PERMISSION = "org.openglyph.permission.CONTROL_MATRIX";
    static final int MAX_CLIENT_PRIORITY = 80;
    /** Must match device/nothing/metroid KeyHandler. */
    static final String ACTION_GLYPH_BUTTON = "org.openglyph.action.GLYPH_BUTTON";
    static final String EXTRA_BUTTON_EVENT = "event";

    private static volatile GlyphService sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<IBinder, Effects.ClientFrame> mClients = new HashMap<>();
    private final Effects.NotificationPulse mPulse = new Effects.NotificationPulse();
    private final Effects.ChargingLevel mCharging = new Effects.ChargingLevel();
    private final Effects.IncomingCall mCall = new Effects.IncomingCall();
    private Renderer mRenderer;
    private TelephonyManager mTelephony;
    private RecordingLedController mRecordLed;
    private final ToyHost mToys = new ToyHost(this::toyContext,
            Toys.all().toArray(new Toy[0]));
    private static final int BUILT_IN_TOYS = Toys.all().size();
    private final Effects.AudioBars mMusicBars = new Effects.AudioBars();
    private MusicVisualizer mMusic;
    private ToySensors mToySensors;
    private MirrorCamera mMirror;
    private GenerativeSounds mSounds;
    private final java.util.concurrent.ExecutorService mBackground =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Boolean mGenSoundsOn;
    private String mEngine;
    private NothingToyBridge mToyBridge;
    /** App frames from Nothing SDK apps (OpenGlyphCompat), keyed by package. */
    private final Map<String, IBinder> mPackageTokens = new HashMap<>();
    private final IdleLayer mIdle = new IdleLayer(this::toyContext);
    private IdleController mIdleController;
    private volatile Map<String, String> mAppPatterns = new HashMap<>();
    private volatile List<EssentialRule> mEssentialRules = new ArrayList<>();
    private volatile boolean mBedtime;
    /** Package and rule of the Essential Notification on the matrix, to skip redundant redraws. */
    private String mEssentialShown = "";

    static GlyphService get() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mRenderer = new Renderer(new SysfsMatrixSink(SysfsMatrixSink.findDir()),
                SystemClock::uptimeMillis);
        mRenderer.add(mPulse);
        mRenderer.add(mCharging);
        mRenderer.add(mCall);
        mRenderer.add(mToys);
        mRenderer.add(mIdle);
        mRenderer.add(mMusicBars);
        mToySensors = new ToySensors(this, mHandler);
        mMirror = new MirrorCamera(this);
        mSounds = new GenerativeSounds(this);
        mMusic = new MusicVisualizer(this, mHandler, mMusicBars, () -> mRenderer.invalidate());
        mIdleController = new IdleController(this, mHandler, mIdle, mRenderer::invalidate);
        applySettings();
        for (String key : GlyphSettings.ALL) {
            getContentResolver().registerContentObserver(
                    GlyphSettings.uri(key), false, mSettingsObserver);
        }

        mRecordLed = new RecordingLedController(this, mHandler);
        mRecordLed.start();
        mIdleController.start();
        mToyBridge = new NothingToyBridge(this, mHandler, this::onSdkToysChanged);
        mToyBridge.start();
        mIdleController.setAodListener(this::onAod);
        mIdleController.setBedtimeListener(this::onBedtime);
        mIdleController.update();

        registerReceiver(mPowerReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        // Sent by the device KeyHandler in system_server; same uid, so PERMISSION holds for it.
        registerReceiver(mButtonReceiver, new IntentFilter(ACTION_GLYPH_BUTTON), PERMISSION,
                mHandler, Context.RECEIVER_EXPORTED);

        mTelephony = getSystemService(TelephonyManager.class);
        if (mTelephony != null) {
            mTelephony.registerTelephonyCallback(getMainExecutor(), mCallState);
        }

        // We run as the system uid, so we may grant ourselves notification access instead of
        // sending the user to Settings.
        getSystemService(NotificationManager.class).setNotificationListenerAccessGranted(
                new ComponentName(this, GlyphNotificationListener.class), true, true);

        mRenderer.start();
        sInstance = this;
        Log.i(TAG, "started");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mTelephony != null) mTelephony.unregisterTelephonyCallback(mCallState);
        unregisterReceiver(mPowerReceiver);
        unregisterReceiver(mButtonReceiver);
        getContentResolver().unregisterContentObserver(mSettingsObserver);
        mRecordLed.stop();
        mIdleController.stop();
        mToyBridge.stop();
        mMusic.stop();
        mToySensors.stop();
        mMirror.setWanted(false);
        mBackground.shutdown();
        mRenderer.stop();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /** Called by the notification listener for each alert-worthy notification. */
    void onNotification(String pkg) {
        if (!GlyphSettings.flag(getContentResolver(), GlyphSettings.NOTIFICATIONS)) return;
        Effects.Pattern fallback = Effects.Pattern.parse(GlyphSettings.string(getContentResolver(),
                GlyphSettings.DEFAULT_PATTERN, "GENERATIVE"), Effects.Pattern.GENERATIVE);
        Effects.Pattern pattern = Effects.Pattern.parse(mAppPatterns.get(pkg), fallback);
        if (pattern == Effects.Pattern.GENERATIVE) {
            mPulse.trigger(SystemClock.uptimeMillis(), mSounds.theme(pkg));
        } else {
            mPulse.trigger(SystemClock.uptimeMillis(), pattern);
        }
        mRenderer.invalidate();
        if (GlyphSettings.flagDefaultOff(getContentResolver(), GlyphSettings.GEN_SOUNDS)) {
            mBackground.execute(() -> mSounds.apply(pkg));
        }
    }

    /** Settings preview: an app's generative sound and animation, now. */
    void previewGenerative(String pkg) {
        mPulse.trigger(SystemClock.uptimeMillis(), mSounds.theme(pkg));
        mRenderer.invalidate();
        mBackground.execute(() -> mSounds.play(pkg));
    }

    List<EssentialRule> essentialRules() {
        return mEssentialRules;
    }

    /**
     * Called by the listener with the newest waiting notification that matches an Essential
     * Notifications rule (pkg and rule), or nulls when none is waiting.
     */
    void setEssential(String pkg, EssentialRule rule) {
        String key = rule == null ? "" : pkg + "\n" + rule.icon;
        if (key.equals(mEssentialShown)) return;
        mEssentialShown = key;
        mIdle.setEssential(rule == null ? null : essentialIcon(pkg, rule.icon));
        mRenderer.invalidate();
    }

    int[] essentialIcon(String pkg, String icon) {
        int[] grid = EssentialRule.ICON_APP.equals(icon) ? AppIconMatrix.render(this, pkg) : null;
        if (grid == null) grid = GlyphIcons.render(icon, Frame.MAX);
        return grid != null ? grid : GlyphIcons.render("bell", Frame.MAX);
    }

    /** Bedtime schedule: every Glyph light stays off while it is on. */
    private void onBedtime(boolean on) {
        if (on == mBedtime) return;
        mBedtime = on;
        Log.i(TAG, on ? "bedtime: lights off" : "bedtime over");
        applyBrightness();
    }

    private void applyBrightness() {
        boolean enabled = GlyphSettings.flag(getContentResolver(), GlyphSettings.ENABLED);
        mRenderer.setBrightness(enabled && !mBedtime
                ? GlyphSettings.brightness(getContentResolver()) : 0);
    }

    private void applySettings() {
        applyBrightness();
        List<String> order = new ArrayList<>(GlyphSettings.activeToys(getContentResolver()));
        order.add(GlyphSettings.OFF);
        mToys.setOrder(order);
        if (!GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS)) mToys.deselect();
        mAppPatterns = AppPatterns.parse(
                GlyphSettings.string(getContentResolver(), GlyphSettings.APP_PATTERNS, ""));
        mEssentialRules = GlyphSettings.essentialRules(getContentResolver());
        mEssentialShown = "\u0000"; // icons may have changed: redraw
        GlyphNotificationListener.refreshEssential();
        if (mRecordLed != null) mRecordLed.update();
        if (mMusic != null) mMusic.setEnabled(GlyphSettings.flagDefaultOff(getContentResolver(), GlyphSettings.MUSIC));
        boolean genSounds = GlyphSettings.flagDefaultOff(getContentResolver(), GlyphSettings.GEN_SOUNDS);
        String engine = GlyphSettings.string(getContentResolver(), GlyphSettings.ENGINE, "DIODE")
                + "|" + GlyphSettings.string(getContentResolver(), GlyphSettings.SEED, "");
        if (mSounds != null && mEngine != null && !mEngine.equals(engine) && genSounds) {
            // Another engine or seed: every app's sound changes; swap the ones we set.
            mBackground.execute(() -> {
                mSounds.revertAll();
                for (AppList.App app : AppList.launchable(this)) mSounds.apply(app.pkg);
            });
        }
        mEngine = engine;
        if (mSounds != null && (mGenSoundsOn == null || mGenSoundsOn != genSounds)) {
            boolean first = mGenSoundsOn == null;
            mGenSoundsOn = genSounds;
            if (genSounds) {
                mBackground.execute(() -> {
                    for (AppList.App app : AppList.launchable(this)) mSounds.apply(app.pkg);
                });
            } else if (!first) {
                mBackground.execute(mSounds::revertAll);
            }
        }
        if (mIdleController != null) mIdleController.update();
        mRenderer.invalidate();
    }

    /** Nothing SDK toys appeared or disappeared (package installed / removed). */
    private void onSdkToysChanged(List<RemoteToy> toys) {
        mToys.setExtraToys(new ArrayList<>(toys), BUILT_IN_TOYS);
        applySettings();
    }

    /** Always-on shown/hidden (and every minute while shown): drive the always-on SDK toy. */
    private void onAod(boolean shown) {
        String id = GlyphSettings.string(getContentResolver(), GlyphSettings.AOD_TOY, "");
        Toy builtIn = mToys.byId(id.isEmpty() ? "clock" : id);
        mIdle.setBuiltInToy(builtIn != null && !(builtIn instanceof RemoteToy)
                && builtIn.aodSupported() ? builtIn : null);
        RemoteToy toy = shown && !id.isEmpty() ? mToyBridge.byId(id) : null;
        if (toy != null && toy.aodSupported()) {
            mIdle.setRemoteToy(toy);
            mToyBridge.aodTick(toy);
        } else {
            mIdle.setRemoteToy(null);
            mToyBridge.stopAod();
        }
    }

    private void setPackageTimeout(String pkg, boolean timeout) {
        RemoteToy toy = mToyBridge.boundToyOf(pkg);
        if (toy != null) {
            toy.setNoTimeout(!timeout);
            mRenderer.invalidate();
        }
    }

    NothingToyBridge toyBridge() {
        return mToyBridge;
    }

    List<RemoteToy> sdkToys() {
        return mToyBridge == null ? List.of() : mToyBridge.toys();
    }

    /** From OpenGlyphCompat: a frame drawn by an app built with Nothing's SDK. */
    private void showPackageFrame(String pkg, int[] grid) {
        RemoteToy toy = mToyBridge.boundToyOf(pkg);
        if (toy != null) {
            toy.setFrame(grid);
        } else {
            IBinder token;
            synchronized (mPackageTokens) {
                token = mPackageTokens.computeIfAbsent(pkg, p -> new android.os.Binder());
            }
            showClientFrame(token, grid, APP_FRAME_PRIORITY);
        }
        mRenderer.invalidate();
    }

    private void clearPackageFrame(String pkg) {
        IBinder token;
        synchronized (mPackageTokens) {
            token = mPackageTokens.remove(pkg);
        }
        if (token != null) dropClient(token);
    }

    /** Apps (not toys) drawing through the SDK sit just above toys, below notifications. */
    static final int APP_FRAME_PRIORITY = 20;

    /** All toys, built-in first, for Settings. */
    List<Toy> allToys() {
        return mToys.allToys();
    }

    /** Settings "try it": show one toy on the matrix now. */
    void showToy(String id) {
        mToys.show(id, SystemClock.uptimeMillis());
        mRenderer.invalidate();
        updateToySensors();
    }

    /** What the matrix shows right now, for the Settings preview. */
    void snapshot(int[] grid) {
        mRenderer.snapshot(grid);
    }

    boolean isBedtime() {
        return mBedtime;
    }

    /** Called from the render thread for every toy frame (and by Settings previews). */
    Toy.Context toyContext() {
        Toy.Context ctx = new Toy.Context();
        BatteryManager bm = getSystemService(BatteryManager.class);
        ctx.batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        ctx.charging = bm.isCharging();
        Calendar now = Calendar.getInstance();
        ctx.hour = now.get(Calendar.HOUR_OF_DAY);
        ctx.minute = now.get(Calendar.MINUTE);
        ctx.second = now.get(Calendar.SECOND);
        ctx.epochMs = now.getTimeInMillis();
        ctx.utcOffsetHours = now.getTimeZone().getOffset(ctx.epochMs) / 3600000.0;
        if (mToySensors != null) mToySensors.fill(ctx);
        if (mMirror != null) ctx.camera = mMirror.grid();
        return ctx;
    }

    private final IBinder mPreviewToken = new android.os.Binder();

    /** Settings preview: shows a picture for a few seconds, over toys and the idle layer. */
    void previewFrame(int[] grid) {
        showClientFrame(mPreviewToken, grid, 60);
        mHandler.removeCallbacks(mEndPreview);
        mHandler.postDelayed(mEndPreview, 3000);
    }

    private final Runnable mEndPreview = () -> dropClient(mPreviewToken);

    /** Settings screen "test" button: one notification pulse, regardless of the switch. */
    void testPulse() {
        mPulse.trigger(SystemClock.uptimeMillis());
        mRenderer.invalidate();
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            applySettings();
        }
    };

    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GlyphSettings.flag(getContentResolver(), GlyphSettings.CHARGING)) {
                showBatteryLevel();
            }
        }
    };

    private void showBatteryLevel() {
        BatteryManager bm = getSystemService(BatteryManager.class);
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        mCharging.trigger(SystemClock.uptimeMillis(), level);
        mRenderer.invalidate();
    }

    /**
     * Glyph Button (the pressure spot under the matrix), forwarded by the device KeyHandler.
     * Events: down, up, tap, long_press. With toys on, tap shows / cycles toys and long press
     * goes to the toy (stopwatch start/stop/reset); with toys off, tap shows the battery level.
     */
    private final BroadcastReceiver mButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String event = intent.getStringExtra(EXTRA_BUTTON_EVENT);
            Log.d(TAG, "Glyph Button " + event);
            boolean toys = GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS);
            long now = SystemClock.uptimeMillis();
            if ("tap".equals(event)) {
                if (toys) mToys.onTap(now); else showBatteryLevel();
            } else if ("long_press".equals(event) && toys) {
                mToys.onLongPress(now);
                if (mToys.selected() instanceof Toys.Mirror m && m.takeCaptureRequest()) {
                    mMirror.capture();
                }
            } else if ("down".equals(event) && toys) {
                mToys.onButtonDown(now);
            } else if ("up".equals(event) && toys) {
                mToys.onButtonUp(now);
            }
            mRenderer.invalidate();
            updateToySensors();
        }
    };

    /**
     * Runs the sensors / camera the toy on screen needs, and checks again every second while
     * any is running, so they stop when the toy times out.
     */
    private void updateToySensors() {
        mHandler.removeCallbacks(mSensorCheck);
        int needed = GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS)
                ? mToys.neededSensors(SystemClock.uptimeMillis()) : 0;
        mToySensors.setWanted(needed);
        mMirror.setWanted((needed & Toy.SENSOR_CAMERA) != 0);
        if (needed != 0) mHandler.postDelayed(mSensorCheck, 1000);
        mToySensors.refreshLocation(SystemClock.uptimeMillis());
    }

    private final Runnable mSensorCheck = this::updateToySensors;

    private final class CallState extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            boolean show = GlyphSettings.flag(getContentResolver(), GlyphSettings.CALLS);
            mCall.setRinging(show && state == TelephonyManager.CALL_STATE_RINGING,
                    SystemClock.uptimeMillis());
            mRenderer.invalidate();
        }
    }

    private final CallState mCallState = new CallState();

    private final IOpenGlyph.Stub mBinder = new IOpenGlyph.Stub() {
        @Override
        public int getWidth() {
            return MatrixLayout.WIDTH;
        }

        @Override
        public int getHeight() {
            return MatrixLayout.HEIGHT;
        }

        @Override
        public boolean hasLed(int x, int y) {
            return MatrixLayout.exists(x, y);
        }

        @Override
        public void showFrame(IBinder token, int[] grid, int priority) throws RemoteException {
            enforceCallingPermission(PERMISSION, "showFrame");
            if (token == null || grid == null || grid.length != MatrixLayout.GRID_SIZE) {
                throw new IllegalArgumentException("need a token and " + MatrixLayout.GRID_SIZE
                        + " pixels");
            }
            showClientFrame(token, grid, Math.max(0, Math.min(MAX_CLIENT_PRIORITY, priority)));
        }

        @Override
        public void showPackageFrame(String pkg, int[] grid) {
            enforceCallingPermission(PERMISSION, "showPackageFrame");
            if (pkg == null || grid == null || grid.length != MatrixLayout.GRID_SIZE) {
                throw new IllegalArgumentException("need a package and "
                        + MatrixLayout.GRID_SIZE + " pixels");
            }
            GlyphService.this.showPackageFrame(pkg, grid);
        }

        @Override
        public void clearPackageFrame(String pkg) {
            enforceCallingPermission(PERMISSION, "clearPackageFrame");
            if (pkg != null) GlyphService.this.clearPackageFrame(pkg);
        }

        @Override
        public void setPackageTimeout(String pkg, boolean timeout) {
            enforceCallingPermission(PERMISSION, "setPackageTimeout");
            if (pkg != null) mHandler.post(() -> GlyphService.this.setPackageTimeout(pkg, timeout));
        }

        @Override
        public void clearFrame(IBinder token) {
            enforceCallingPermission(PERMISSION, "clearFrame");
            Effects.ClientFrame layer;
            synchronized (mClients) {
                layer = mClients.get(token);
            }
            if (layer != null) {
                layer.clear();
                mRenderer.invalidate();
            }
        }
    };

    private void showClientFrame(IBinder token, int[] grid, int priority) {
        Effects.ClientFrame layer;
        synchronized (mClients) {
            layer = mClients.get(token);
            if (layer == null || layer.priority() != priority) {
                if (layer != null) {
                    mRenderer.remove(layer);
                } else {
                    try {
                        token.linkToDeath(() -> dropClient(token), 0);
                    } catch (RemoteException e) {
                        return; // the client is already gone
                    }
                }
                layer = new Effects.ClientFrame(priority);
                mClients.put(token, layer);
                mRenderer.add(layer);
            }
        }
        layer.show(grid);
        mRenderer.invalidate();
    }

    private void dropClient(IBinder token) {
        Effects.ClientFrame layer;
        synchronized (mClients) {
            layer = mClients.remove(token);
        }
        if (layer != null) mRenderer.remove(layer);
    }
}
