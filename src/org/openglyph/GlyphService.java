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
import org.openglyph.toys.IdleLayer;
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
            new Toys.Clock(), new Toys.Battery(), new Toys.Stopwatch());
    private final IdleLayer mIdle = new IdleLayer(this::toyContext);
    private IdleController mIdleController;
    private volatile Map<String, String> mAppPatterns = new HashMap<>();
    private volatile java.util.Set<String> mEssentialApps = new java.util.HashSet<>();

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
        mIdleController = new IdleController(this, mHandler, mIdle, mRenderer::invalidate);
        applySettings();
        List<String> keys = new ArrayList<>(List.of(GlyphSettings.ALL));
        for (Toy t : mToys.allToys()) keys.add(GlyphSettings.toyKey(t.id()));
        for (String key : keys) {
            getContentResolver().registerContentObserver(
                    GlyphSettings.uri(key), false, mSettingsObserver);
        }

        mRecordLed = new RecordingLedController(this, mHandler);
        mRecordLed.start();
        mIdleController.start();

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
        Effects.Pattern pattern = Effects.Pattern.parse(mAppPatterns.get(pkg), Effects.Pattern.RING);
        mPulse.trigger(SystemClock.uptimeMillis(), pattern);
        mRenderer.invalidate();
    }

    boolean isEssentialApp(String pkg) {
        return mEssentialApps.contains(pkg);
    }

    /** Called by the listener whenever the set of waiting essential notifications changes. */
    void setEssentialWaiting(boolean waiting) {
        mIdle.setEssential(waiting);
        mRenderer.invalidate();
    }

    private void applySettings() {
        boolean enabled = GlyphSettings.flag(getContentResolver(), GlyphSettings.ENABLED);
        mRenderer.setBrightness(enabled ? GlyphSettings.brightness(getContentResolver()) : 0);
        List<String> ids = new ArrayList<>();
        for (Toy t : mToys.allToys()) ids.add(t.id());
        mToys.setEnabled(GlyphSettings.enabledToys(getContentResolver(), ids));
        mAppPatterns = AppPatterns.parse(
                GlyphSettings.string(getContentResolver(), GlyphSettings.APP_PATTERNS, ""));
        mEssentialApps = AppPatterns.parseSet(
                GlyphSettings.string(getContentResolver(), GlyphSettings.ESSENTIAL_APPS, ""));
        GlyphNotificationListener.refreshEssential();
        if (mRecordLed != null) mRecordLed.update();
        if (mIdleController != null) mIdleController.update();
        mRenderer.invalidate();
    }

    /** Called from the render thread for every toy frame. */
    private Toy.Context toyContext() {
        Toy.Context ctx = new Toy.Context();
        BatteryManager bm = getSystemService(BatteryManager.class);
        ctx.batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        ctx.charging = bm.isCharging();
        Calendar now = Calendar.getInstance();
        ctx.hour = now.get(Calendar.HOUR_OF_DAY);
        ctx.minute = now.get(Calendar.MINUTE);
        return ctx;
    }

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
            }
            mRenderer.invalidate();
        }
    };

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
            int p = Math.max(0, Math.min(MAX_CLIENT_PRIORITY, priority));
            Effects.ClientFrame layer;
            synchronized (mClients) {
                layer = mClients.get(token);
                if (layer == null || layer.priority() != p) {
                    if (layer != null) mRenderer.remove(layer);
                    else token.linkToDeath(() -> dropClient(token), 0);
                    layer = new Effects.ClientFrame(p);
                    mClients.put(token, layer);
                    mRenderer.add(layer);
                }
            }
            layer.show(grid);
            mRenderer.invalidate();
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

    private void dropClient(IBinder token) {
        Effects.ClientFrame layer;
        synchronized (mClients) {
            layer = mClients.remove(token);
        }
        if (layer != null) mRenderer.remove(layer);
    }
}
