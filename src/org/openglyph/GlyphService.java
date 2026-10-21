package org.openglyph;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
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
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

import org.openglyph.core.Generative;
import org.openglyph.core.MatrixLayout;
import org.openglyph.core.Renderer;
import org.openglyph.core.SysfsMatrixSink;
import org.openglyph.effects.Effects;
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
    /** From the device KeyHandler: the Essential Key ran an action; show its icon. */
    static final String ACTION_ESSENTIAL_KEY = "org.openglyph.action.ESSENTIAL_KEY";
    static final String EXTRA_ICON = "icon";

    private static volatile GlyphService sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<IBinder, Effects.ClientFrame> mClients = new HashMap<>();
    private final Effects.NotificationPulse mPulse = new Effects.NotificationPulse();
    private final Effects.ChargingLevel mCharging = new Effects.ChargingLevel();
    private final Effects.IncomingCall mCall = new Effects.IncomingCall();
    private final org.openglyph.toys.CallerId mCallerId = new org.openglyph.toys.CallerId();
    /** TelephonyManager.CALL_STATE_*: the stock host ignores toy presses during a call. */
    private volatile int mCallStateNow = TelephonyManager.CALL_STATE_IDLE;
    private final org.openglyph.toys.ProgressBar mProgress = new org.openglyph.toys.ProgressBar();
    private final org.openglyph.toys.Countdown mCountdown = new org.openglyph.toys.Countdown();

    /** The camera app's self-timer started (seconds) or was cancelled (0). */
    void onCameraCountdown(int seconds) {
        mCountdown.start(seconds, SystemClock.uptimeMillis());
        mRenderer.invalidate();
    }

    /** The Clock timer changed (started, paused, +1 min, gone): redraw the hourglass. */
    void onTimerChanged() {
        mHandler.post(this::evaluateAutomations);
        mRenderer.invalidate();
    }

    /** From the notification listener: the newest Live Update's progress, or -1 for none. */
    void onLiveProgress(int percent, boolean indeterminate) {
        mProgress.set(percent, indeterminate);
        mHandler.post(this::evaluateAutomations);
        mRenderer.invalidate();
    }
    private Renderer mRenderer;
    private TelephonyManager mTelephony;
    private RecordingLedController mRecordLed;
    private AmbientController mAmbient;
    /**
     * Toys made from live effects: the visualiser, the call voice, the hourglass and Glyph
     * Progress. They are picked with the Glyph Button or put up by automations like any toy, and
     * their audio capture runs only while they are on the matrix.
     */
    private final List<Toy> mServiceToys = List.of(
            new org.openglyph.toys.EffectToy("visualizer",
                    () -> this.mVisFixed ? this.mMusicBars : this.mMusicWave,
                    () -> this.mHandler.post(() -> this.mMusic.setEnabled(true)),
                    () -> this.mHandler.post(() -> this.mMusic.setEnabled(false))),
            new org.openglyph.toys.EffectToy("callvoice",
                    () -> this.mVisFixed ? this.mCallBars : this.mCallWave,
                    () -> this.mHandler.post(() -> { this.mCallVoiceShown = true; updateCallVoice(); }),
                    () -> this.mHandler.post(() -> { this.mCallVoiceShown = false; updateCallVoice(); })),
            new org.openglyph.toys.Hourglass(),
            new org.openglyph.toys.EffectToy("progress", () -> this.mProgress, null, null));
    private volatile boolean mVisFixed = true, mCallVoiceShown;
    private final ToyHost mToys = new ToyHost(this::toyContext, builtInToys());
    private final int BUILT_IN_TOYS = Toys.all().size() + mServiceToys.size();

    private Toy[] builtInToys() {
        List<Toy> all = new ArrayList<>(Toys.all());
        all.addAll(mServiceToys);
        return all.toArray(new Toy[0]);
    }

    /** The service-made toys, for Settings (names, previews). */
    List<Toy> serviceToys() {
        return mServiceToys;
    }
    private final org.openglyph.effects.VoiceWave mMusicWave =
            new org.openglyph.effects.VoiceWave(org.openglyph.core.Effect.PRIORITY_MUSIC);
    /** The other person's voice during a call, just above music. */
    private final org.openglyph.effects.VoiceWave mCallWave =
            new org.openglyph.effects.VoiceWave(org.openglyph.core.Effect.PRIORITY_MUSIC + 1);
    private final org.openglyph.effects.SpectrumBars mMusicBars =
            new org.openglyph.effects.SpectrumBars(org.openglyph.core.Effect.PRIORITY_MUSIC);
    private final org.openglyph.effects.SpectrumBars mCallBars =
            new org.openglyph.effects.SpectrumBars(org.openglyph.core.Effect.PRIORITY_MUSIC + 1);
    private CallVoiceVisualizer mCallVoice;
    private MusicVisualizer mMusic;
    private ToySensors mToySensors;
    private MirrorCamera mMirror;
    private GenerativeSounds mSounds;
    private final java.util.concurrent.ExecutorService mBackground =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Boolean mGenSoundsOn;
    private ChannelWatcher mChannelWatcher;
    private String mEngine;
    private NothingToyBridge mToyBridge;
    /** App frames from Nothing SDK apps (OpenGlyphCompat), keyed by package. */
    private final Map<String, IBinder> mPackageTokens = new HashMap<>();
    private final IdleLayer mIdle = new IdleLayer(this::toyContext);
    private IdleController mIdleController;
    private volatile List<EssentialRule> mEssentialRules = new ArrayList<>();
    private volatile boolean mBedtime;
    /** Package and rule of the Essential Notification on the matrix, to skip redundant redraws. */
    private String mEssentialShown = "";

    GenerativeSounds sounds() {
        return mSounds;
    }

    /** RingtoneProvider: this caller's ringtone is about to play; loop their animation. */
    void onCallerRingtone(String callerKey) {
        Generative.Theme theme = mSounds.callerTheme(callerKey);
        mCall.setTheme(theme, mSounds.ringPeriodMs(theme), SystemClock.uptimeMillis());
        mRenderer.invalidate();
    }

    static GlyphService get() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Text to Glyph: names and answers are set with the system font.
        org.openglyph.core.GlyphText.setRasterizer(new PaintTextRasterizer());
        mRenderer = new Renderer(new SysfsMatrixSink(SysfsMatrixSink.findDir()),
                SystemClock::uptimeMillis);
        mRenderer.setFaultListener((source, error) ->
                Log.w(TAG, "glyph: " + source.getClass().getSimpleName() + " failed, carrying on", error));
        mRenderer.add(mPulse);
        mRenderer.add(mCharging);
        mRenderer.add(mCall);
        mRenderer.add(mCallerId);
        mRenderer.add(mCountdown);
        mRenderer.add(mToys);
        mRenderer.add(mIdle);
        mToySensors = new ToySensors(this, mHandler);
        mMirror = new MirrorCamera(this);
        mSounds = new GenerativeSounds(this);
        mMusic = new MusicVisualizer(this, mHandler, mMusicWave, mMusicBars,
                () -> mRenderer.invalidate());
        mCallVoice = new CallVoiceVisualizer(mCallWave, mCallBars, () -> mRenderer.invalidate());
        mIdleController = new IdleController(this, mHandler, mIdle, mRenderer::invalidate);
        mAmbient = new AmbientController(this, mHandler, () -> mHandler.post(this::applyBrightness));
        // Pocket / auto brightness sensors only run while the matrix has something to show.
        mRenderer.setActiveListener(active -> mHandler.post(() -> mAmbient.setMatrixActive(active)));
        GlyphSettings.migrateToysV3(getContentResolver());
        applySettings();
        for (String key : GlyphSettings.ALL) {
            getContentResolver().registerContentObserver(
                    GlyphSettings.uri(key), false, mSettingsObserver);
        }

        mRecordLed = new RecordingLedController(this, mHandler);
        mRecordLed.start();
        mIdleController.setStateListener(() -> mHandler.post(this::evaluateAutomations));
        mIdleController.start();
        mToyBridge = new NothingToyBridge(this, mHandler, this::onSdkToysChanged);
        mToyBridge.start();
        mIdleController.setAodListener(this::onAod);
        mIdleController.setBedtimeListener(this::onBedtime);
        mIdleController.update();

        registerReceiver(mPowerReceiver, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
        // A new channel gets its generative sound at once, before the app's first notification.
        mChannelWatcher = new ChannelWatcher(pkg -> {
            if (GlyphSettings.flagDefaultOff(getContentResolver(), GlyphSettings.GEN_SOUNDS)) {
                mBackground.execute(() -> mSounds.apply(pkg));
            }
        });
        mChannelWatcher.register(this);
        // Notification sounds live in shared storage, which opens when the user unlocks.
        registerReceiver(mUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // As the stock host: toys go off when the phone is unlocked.
        registerReceiver(mUserPresentReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT),
                null, mHandler);
        // Sent by the device KeyHandler in system_server; same uid, so PERMISSION holds for it.
        registerReceiver(mButtonReceiver, new IntentFilter(ACTION_GLYPH_BUTTON), PERMISSION,
                mHandler, Context.RECEIVER_EXPORTED);
        registerReceiver(mEssentialKeyReceiver, new IntentFilter(ACTION_ESSENTIAL_KEY), PERMISSION,
                mHandler, Context.RECEIVER_EXPORTED);

        mTelephony = getSystemService(TelephonyManager.class);
        if (mTelephony != null) {
            mTelephony.registerTelephonyCallback(getMainExecutor(), mCallState);
        }

        // We run as the system uid, so we may grant ourselves notification access instead of
        // sending the user to Settings.
        getSystemService(NotificationManager.class).setNotificationListenerAccessGranted(
                new ComponentName(this, GlyphNotificationListener.class), true, true);

        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) am.registerAudioPlaybackCallback(mPlaybackCallback, mHandler);
        if (am != null) am.registerAudioDeviceCallback(mAutomationDevices, mHandler);
        IntentFilter autoFilter = new IntentFilter();
        autoFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        autoFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        autoFilter.addAction("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(mAutomationReceiver, autoFilter, null, mHandler);

        mRenderer.start();
        sInstance = this;
        Log.i(TAG, "started");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (mTelephony != null) mTelephony.unregisterTelephonyCallback(mCallState);
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) am.unregisterAudioPlaybackCallback(mPlaybackCallback);
        if (am != null) am.unregisterAudioDeviceCallback(mAutomationDevices);
        unregisterReceiver(mAutomationReceiver);
        mHandler.removeCallbacks(mAutomationTick);
        mHandler.removeCallbacks(mPendingTimeout);
        unregisterReceiver(mPowerReceiver);
        unregisterReceiver(mUnlockReceiver);
        unregisterReceiver(mUserPresentReceiver);
        mChannelWatcher.unregister();
        unregisterReceiver(mButtonReceiver);
        unregisterReceiver(mEssentialKeyReceiver);
        getContentResolver().unregisterContentObserver(mSettingsObserver);
        mRecordLed.stop();
        mIdleController.stop();
        mAmbient.stop();
        mToyBridge.stop();
        mMusic.stop();
        mCallVoice.setActive(false);
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
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter pw, String[] args) {
        pw.println("OpenGlyph renderer:");
        pw.print(mRenderer.describe(SystemClock.uptimeMillis()));
        pw.println("bedtime=" + mBedtime + " pocket=" + (mAmbient != null && mAmbient.inPocket())
                + " auto=" + (mAmbient != null && mAmbient.autoOn())
                + " pendingPulse=" + (mPendingTheme != null));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // ---- the light starts with the sound ----
    // SystemUI's NotificationPlayer holds a notification sound back (~500 ms, the audio focus
    // ramp) and the audio HAL takes ~80 ms more to start the stream. So an audible notification's
    // animation waits for its player to start, as AudioManager reports it; a ringtone's animation
    // restarts with the ringtone.

    /** From the player starting to sound leaving the speaker (pal_stream_start, measured). */
    private static final long AUDIO_START_LATENCY_MS = 80;
    /** No player started by then (sound suppressed, e.g. too frequent): show it anyway. */
    private static final long SOUND_WAIT_MS = 1500;

    private Generative.Theme mPendingTheme;
    private final Set<Integer> mPlaying = new HashSet<>();
    private final Runnable mPendingTimeout = this::startPendingPulse;

    private void startPendingPulse() {
        mHandler.removeCallbacks(mPendingTimeout);
        Generative.Theme theme = mPendingTheme;
        if (theme == null) return;
        mPendingTheme = null;
        mPulse.trigger(SystemClock.uptimeMillis(), theme);
        mRenderer.invalidate();
    }

    private final AudioManager.AudioPlaybackCallback mPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            Set<Integer> playing = new HashSet<>();
            boolean notification = false, ringtone = false;
            for (AudioPlaybackConfiguration c : configs) {
                if (c.getPlayerState() != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) continue;
                int piid = c.getPlayerInterfaceId();
                playing.add(piid);
                if (mPlaying.contains(piid)) continue; // was already playing
                int usage = c.getAudioAttributes().getUsage();
                if (usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) ringtone = true;
                else if (usage == AudioAttributes.USAGE_NOTIFICATION
                        || usage == AudioAttributes.USAGE_NOTIFICATION_EVENT) notification = true;
            }
            mPlaying.clear();
            mPlaying.addAll(playing);
            evaluateAutomations();
            if (notification && mPendingTheme != null) {
                mHandler.removeCallbacks(mPendingTimeout);
                mHandler.postDelayed(mPendingTimeout, AUDIO_START_LATENCY_MS);
            }
            if (ringtone) {
                mHandler.postDelayed(() -> {
                    mCall.restartTheme(SystemClock.uptimeMillis());
                    mRenderer.invalidate();
                }, AUDIO_START_LATENCY_MS);
            }
        }
    };

    /**
     * Called by the notification listener for each alert-worthy notification; {@code audible}:
     * its channel has a sound and the ringer is on, so the light waits for the sound.
     */
    void onNotification(String pkg, boolean audible) {
        if (!GlyphSettings.flag(getContentResolver(), GlyphSettings.NOTIFICATIONS)) return;
        Generative.Theme theme = mSounds.theme(pkg);
        AudioManager am = getSystemService(AudioManager.class);
        if (audible && am != null && am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            if (mPendingTheme != null) startPendingPulse(); // a second one before the first sounded
            mPendingTheme = theme;
            mHandler.postDelayed(mPendingTimeout, SOUND_WAIT_MS);
        } else {
            mPulse.trigger(SystemClock.uptimeMillis(), theme);
            mRenderer.invalidate();
        }
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
        boolean dark = mBedtime || (mAmbient != null && mAmbient.inPocket());
        int level = mAmbient != null && mAmbient.autoOn()
                ? mAmbient.autoBrightness() : GlyphSettings.brightness(getContentResolver());
        mRenderer.setBrightness(enabled && !dark ? level : 0);
    }

    private void applySettings() {
        mRenderer.ensureRunning();
        int face = GlyphSettings.integer(getContentResolver(), GlyphSettings.CLOCK_FACE, 0);
        if (mToys.byId("clock") instanceof Toys.Clock clock) clock.setFace(face);
        mIdle.setClockFace(face);
        if (mAmbient != null) mAmbient.update();
        applyBrightness();
        mToys.setTimeoutMs(GlyphSettings.toyTimeoutMs(getContentResolver()));
        List<String> order = new ArrayList<>(GlyphSettings.activeToys(getContentResolver()));
        order.add(GlyphSettings.OFF);
        mToys.setOrder(order);
        if (!GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS)) mToys.deselect();
        mEssentialRules = GlyphSettings.essentialRules(getContentResolver());
        mEssentialShown = "\u0000"; // icons may have changed: redraw
        GlyphNotificationListener.refreshEssential();
        if (mRecordLed != null) mRecordLed.update();
        boolean fixed = GlyphSettings.visualizerFixed(getContentResolver());
        mVisFixed = fixed;
        if (mMusic != null) mMusic.setFixed(fixed);
        if (mCallVoice != null) mCallVoice.setFixed(fixed);
        mAutomations = org.openglyph.core.Automation.parse(
                GlyphSettings.string(getContentResolver(), GlyphSettings.AUTOMATIONS, null));
        evaluateAutomations();
        boolean genSounds = GlyphSettings.flagDefaultOff(getContentResolver(), GlyphSettings.GEN_SOUNDS);
        String engine = GlyphSettings.string(getContentResolver(), GlyphSettings.ENGINE, "DIODE")
                + "|" + GlyphSettings.string(getContentResolver(), GlyphSettings.SEED, "");
        if (mSounds != null && mEngine != null && !mEngine.equals(engine) && genSounds) {
            // Another engine or seed: every app's sound changes; swap the ones we set.
            mBackground.execute(mSounds::syncAll);
        }
        mEngine = engine;
        if (mSounds != null && (mGenSoundsOn == null || mGenSoundsOn != genSounds)) {
            boolean first = mGenSoundsOn == null;
            mGenSoundsOn = genSounds;
            if (genSounds) {
                mBackground.execute(mSounds::syncAll); // no-op until unlocked; see mUnlockReceiver
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
        mRenderer.ensureRunning();
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

    /** A toy frame: drawn into the package's selected toy, dropped once it is deselected. */
    private void showToyFrame(String pkg, int[] grid) {
        RemoteToy toy = mToyBridge.boundToyOf(pkg);
        if (toy == null) return; // a deselected toy still finishing its animation
        toy.setFrame(grid);
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
        previewFrame(grid, 3000);
    }

    void previewFrame(int[] grid, long ms) {
        showClientFrame(mPreviewToken, grid, 60);
        mHandler.removeCallbacks(mEndPreview);
        mHandler.postDelayed(mEndPreview, ms);
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

    /** Essential Key feedback: the action's icon on the matrix for a moment. */
    private final BroadcastReceiver mEssentialKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int[] icon = GlyphIcons.render(String.valueOf(intent.getStringExtra(EXTRA_ICON)), Frame.MAX);
            if (icon != null) previewFrame(icon, 900);
        }
    };

    private final BroadcastReceiver mUnlockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GlyphSettings.flagDefaultOff(getContentResolver(), GlyphSettings.GEN_SOUNDS)) {
                mBackground.execute(mSounds::syncAll);
            }
        }
    };

    private final BroadcastReceiver mUserPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideToys("unlock");
        }
    };

    /** Takes the toy off the matrix (it is deselected; a short press brings toys back). */
    private void hideToys(String why) {
        if (!mToys.isShowing(SystemClock.uptimeMillis())) return;
        Log.d(TAG, "toys off: " + why);
        mToys.hide();
        mRenderer.invalidate();
        updateToySensors();
    }

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
     * As the stock host: presses do nothing before setup is finished, and during a call they
     * do not reach the toys; a long press then shows who is calling (Caller ID).
     */
    private final BroadcastReceiver mButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String event = intent.getStringExtra(EXTRA_BUTTON_EVENT);
            Log.d(TAG, "Glyph Button " + event);
            if (Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0) == 0) {
                return;
            }
            mRenderer.ensureRunning(); // a press always gets a live matrix
            boolean toys = GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS);
            long now = SystemClock.uptimeMillis();
            if (mCallStateNow != TelephonyManager.CALL_STATE_IDLE) {
                if ("long_press".equals(event)) {
                    String caller = GlyphNotificationListener.callerName();
                    if (caller != null) mCallerId.show(caller, now);
                    mRenderer.invalidate();
                }
                return;
            }
            if ("tap".equals(event)) {
                if (toys) mToys.onTap(now); else showBatteryLevel();
            } else if ("long_press".equals(event) && toys) {
                mToys.onLongPress(now);
                if (mToys.selected() instanceof Toys.Clock clock) {
                    // Long press on the clock picks the next face; keep it (and use it always-on).
                    Settings.Secure.putInt(getContentResolver(), GlyphSettings.CLOCK_FACE, clock.face());
                }
                if (mToys.selected() instanceof Toys.Mirror m && m.takeCaptureRequest()) {
                    // The toy counts down 3-2-1 on the matrix; the photo is taken at 0.
                    mHandler.removeCallbacks(mMirrorShot);
                    // The shot goes as the blades close (Toys.Mirror.iris).
                    mHandler.postDelayed(mMirrorShot, Toys.Mirror.COUNTDOWN_MS + 120);
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
        mHandler.removeCallbacks(mMatrixDownCheck);
        mMatrixDownCheck.run();
        int needed = GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS)
                ? mToys.neededSensors(SystemClock.uptimeMillis()) : 0;
        mToySensors.setWanted(needed);
        mMirror.setWanted((needed & Toy.SENSOR_CAMERA) != 0);
        if (needed != 0) mHandler.postDelayed(mSensorCheck, 1000);
        mToySensors.refreshLocation(SystemClock.uptimeMillis());
    }

    /**
     * Toys go off when the matrix is laid face down (the stock host does the same). Gravity
     * runs only while a toy is on screen; only the change to face down counts, so a toy shown
     * while the phone already lies on its back stays.
     */
    private final Runnable mMatrixDownCheck = new Runnable() {
        private Boolean mWasDown;

        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            boolean showing = GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS)
                    && mToys.isShowing(now);
            mToySensors.setFaceWatch(showing);
            if (!showing) {
                mWasDown = null;
                return;
            }
            Boolean down = mToySensors.matrixDown();
            if (down != null) {
                if (down && Boolean.FALSE.equals(mWasDown)) {
                    mWasDown = null;
                    hideToys("face down");
                    return;
                }
                mWasDown = down;
            }
            mHandler.postDelayed(this, 500);
        }
    };

    private final Runnable mSensorCheck = this::updateToySensors;
    private final Runnable mMirrorShot = () -> {
        // The picture the mirror shows (the matrix is busy with the shutter blades by now).
        int[] grid = mMirror.grid();
        mMirror.capture(grid == null ? null : grid.clone());
    };

    private final class CallState extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            boolean show = GlyphSettings.flag(getContentResolver(), GlyphSettings.CALLS);
            mCall.setRinging(show && state == TelephonyManager.CALL_STATE_RINGING,
                    SystemClock.uptimeMillis());
            mCallStateNow = state;
            updateCallVoice();
            evaluateAutomations();
            if (state == TelephonyManager.CALL_STATE_IDLE) mCallerId.hide();
            mRenderer.invalidate();
        }
    }

    private final CallState mCallState = new CallState();

    /** The caller's voice is read only while its toy is on the matrix during a connected call. */
    private void updateCallVoice() {
        if (mCallVoice == null) return;
        mCallVoice.setActive(mCallVoiceShown
                && mCallStateNow == TelephonyManager.CALL_STATE_OFFHOOK
                && GlyphSettings.flag(getContentResolver(), GlyphSettings.ENABLED));
    }

    // ---- automations ----

    private volatile List<org.openglyph.core.Automation.Rule> mAutomations = List.of();
    private String mAutoToy;

    /** What the phone is doing now, for the automation rules. */
    private org.openglyph.core.Automation.State automationState() {
        org.openglyph.core.Automation.State st = new org.openglyph.core.Automation.State();
        st.timer = org.openglyph.toys.Hourglass.hasTimer();
        st.progress = mProgress.isActive(SystemClock.uptimeMillis());
        st.call = mCallStateNow == TelephonyManager.CALL_STATE_OFFHOOK;
        BatteryManager bm = getSystemService(BatteryManager.class);
        st.charging = bm != null && bm.isCharging();
        st.screenOff = mIdleController != null && mIdleController.isScreenOff();
        st.faceDown = mIdleController != null && mIdleController.isFaceDown();
        Calendar now = Calendar.getInstance();
        st.minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) {
            for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                switch (d.getType()) {
                    case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> st.headphones = true;
                    default -> { }
                }
            }
            // Sound: media or game playback, and a media volume above 0.
            boolean audible = am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
                    && !am.isStreamMute(AudioManager.STREAM_MUSIC);
            if (audible) {
                android.content.pm.PackageManager pm = getPackageManager();
                for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
                    int usage = c.getAudioAttributes().getUsage();
                    if (!c.isActive() || (usage != AudioAttributes.USAGE_MEDIA
                            && usage != AudioAttributes.USAGE_GAME)) continue;
                    String[] pkgs = pm.getPackagesForUid(c.getClientUid());
                    if (pkgs != null) for (String p : pkgs) st.soundApps.add(p);
                    else st.soundApps.add("uid:" + c.getClientUid());
                }
            }
        }
        return st;
    }

    /** Puts the first matching rule's toy on the matrix (below a toy picked with the button). */
    private void evaluateAutomations() {
        List<org.openglyph.core.Automation.Rule> rules = mAutomations;
        if (mIdleController != null) {
            mIdleController.setAutomationFaceDown(org.openglyph.core.Automation.uses(rules,
                    org.openglyph.core.Automation.Cond.FACE_DOWN));
        }
        boolean toysOn = GlyphSettings.flag(getContentResolver(), GlyphSettings.TOYS);
        String id = toysOn ? org.openglyph.core.Automation.pick(rules, automationState()) : null;
        mHandler.removeCallbacks(mAutomationTick);
        if (org.openglyph.core.Automation.usesTime(rules)) mHandler.postDelayed(mAutomationTick, 60_000);
        if (java.util.Objects.equals(id, mAutoToy)) return;
        mAutoToy = id;
        Log.i(TAG, "automation: " + (id == null ? "none" : id));
        mToys.setAuto(id, SystemClock.uptimeMillis());
        mRenderer.invalidate();
        updateToySensors();
    }

    private final Runnable mAutomationTick = this::evaluateAutomations;

    private final BroadcastReceiver mAutomationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            evaluateAutomations();
        }
    };

    private final android.media.AudioDeviceCallback mAutomationDevices =
            new android.media.AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] added) {
            evaluateAutomations();
        }

        @Override
        public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removed) {
            evaluateAutomations();
        }
    };

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
        public void showToyFrame(String pkg, int[] grid) {
            enforceCallingPermission(PERMISSION, "showToyFrame");
            if (pkg == null || grid == null || grid.length != MatrixLayout.GRID_SIZE) {
                throw new IllegalArgumentException("need a package and "
                        + MatrixLayout.GRID_SIZE + " pixels");
            }
            GlyphService.this.showToyFrame(pkg, grid);
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
