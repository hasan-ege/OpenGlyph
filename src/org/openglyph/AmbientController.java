package org.openglyph;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.util.Log;

/**
 * Pocket mode and automatic Glyph brightness, from the proximity, light and accelerometer
 * sensors. Each runs only while its feature is on and the matrix has something to show
 * ({@link #setMatrixActive}), never around the clock.
 *
 * - The matrix is on the back, so the light that matters is behind the phone: the Phone (3)'s
 *   rear light sensor ({@link #TYPE_LIGHT_BACK}) is used when present. The front sensor reads 0
 *   while you hold the phone to look at the matrix (screen in your palm), which left the
 *   matrix at its dimmest while the Settings preview showed it bright.
 * - Pocket: front covered (proximity near), back dark too, and not lying face down (which is
 *   Flip to Glyph's job), for a moment: the matrix goes dark until it is uncovered. Without
 *   the back check, a palm over the screen while looking at the matrix switched it off.
 * - Auto brightness: the matrix follows the ambient light on a log curve, smoothed so it does
 *   not flicker with every shadow.
 */
final class AmbientController implements SensorEventListener {
    interface Listener {
        void onAmbientChanged();
    }

    private static final long POCKET_DELAY_MS = 1500;
    static final int MIN_AUTO = 32;
    /** qti.sensor.light_back: the stk6a2x next to the matrix; values[0] is lux. */
    static final int TYPE_LIGHT_BACK = 33171066;
    /** Rear light at or below this (lux) counts as "covered" for pocket mode. */
    static final float POCKET_MAX_BACK_LUX = 1.5f;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final SensorManager mSensors;
    private final Sensor mProximity, mLight, mAccel, mBackLight;

    private boolean mPocketOn, mAutoOn, mMatrixActive;
    private boolean mNear, mFaceDown, mInPocket;
    private float mLux = -1;
    /** Latest rear light (lux), or -1 before the first reading. */
    private float mBackLux = -1;
    private int mAutoBrightness = 160;

    AmbientController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mSensors = context.getSystemService(SensorManager.class);
        mProximity = mSensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mAccel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor back = null;
        for (Sensor x : mSensors.getSensorList(Sensor.TYPE_ALL)) {
            if (x.getType() == TYPE_LIGHT_BACK && !x.isWakeUpSensor()) back = x;
        }
        mBackLight = back;
        mLight = back != null ? back : mSensors.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    /** Re-reads the settings and (un)registers sensors accordingly. */
    void update() {
        ContentResolver cr = mContext.getContentResolver();
        boolean enabled = GlyphSettings.flag(cr, GlyphSettings.ENABLED);
        boolean pocket = enabled && GlyphSettings.flagDefaultOff(cr, GlyphSettings.POCKET);
        boolean auto = enabled && GlyphSettings.flagDefaultOff(cr, GlyphSettings.AUTO_BRIGHTNESS);
        mPocketOn = pocket;
        mAutoOn = auto;
        if (!pocket) setInPocket(false);
        apply();
    }

    /** The matrix has something to show (an effect is active) or went idle. */
    void setMatrixActive(boolean active) {
        if (active == mMatrixActive) return;
        mMatrixActive = active;
        apply();
    }

    private boolean mProxOn, mAccelOn, mLightOn;

    private void apply() {
        boolean pocket = mPocketOn && mMatrixActive;
        boolean light = mMatrixActive && (mAutoOn || (pocket && mBackLight != null));
        if (pocket != mProxOn) {
            mProxOn = pocket;
            register(mProximity, pocket);
            if (!pocket) {
                mHandler.removeCallbacks(mPocketCheck);
                mNear = false;
                // Leave the pocket state as it is: the next show re-checks within moments.
            }
        }
        if (pocket != mAccelOn) {
            mAccelOn = pocket;
            register(mAccel, pocket);
            if (!pocket) mFaceDown = false;
        }
        if (light != mLightOn) {
            mLightOn = light;
            register(mLight, light);
            if (!light) mBackLux = -1;
        }
    }

    void stop() {
        mSensors.unregisterListener(this);
        mPocketOn = mAutoOn = mMatrixActive = false;
        mProxOn = mAccelOn = mLightOn = false;
        mHandler.removeCallbacks(mPocketCheck);
    }

    private void register(Sensor s, boolean on) {
        if (s == null) return;
        if (on) {
            mSensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 500_000, mHandler);
        } else {
            mSensors.unregisterListener(this, s);
        }
    }

    boolean inPocket() {
        return mInPocket;
    }

    boolean autoOn() {
        return mAutoOn;
    }

    /** 0..255 for the current light, while auto brightness is on. */
    int autoBrightness() {
        return mAutoBrightness;
    }

    /** Lux to brightness: dim in the dark, full in daylight (log scale, like the eye). */
    static int luxToBrightness(float lux) {
        double b = MIN_AUTO + (255 - MIN_AUTO) * Math.log10(1 + Math.max(0, lux)) / Math.log10(1 + 3000);
        return (int) Math.max(MIN_AUTO, Math.min(255, Math.round(b)));
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor == mProximity) {
            mNear = e.values[0] < Math.min(5f, mProximity.getMaximumRange());
            scheduleFaceCheck();
        } else if (e.sensor == mAccel) {
            mFaceDown = mFaceDown ? !org.openglyph.core.AodPolicy.isFaceUp(e.values[0], e.values[1], e.values[2])
                    : org.openglyph.core.AodPolicy.isFaceDown(e.values[0], e.values[1], e.values[2]);
            scheduleFaceCheck();
        } else if (e.sensor == mLight) {
            float lux = e.values[0];
            if (e.sensor == mBackLight) {
                boolean wasDark = isBackDark();
                mBackLux = lux;
                if (wasDark != isBackDark()) scheduleFaceCheck();
                if (!mAutoOn) return;
            }
            // Smooth: follow quickly when it gets brighter, slowly when darker.
            mLux = mLux < 0 ? lux : (lux > mLux ? mLux + (lux - mLux) * 0.5f : mLux + (lux - mLux) * 0.15f);
            int b = luxToBrightness(mLux);
            if (Math.abs(b - mAutoBrightness) >= 4) {
                mAutoBrightness = b;
                mListener.onAmbientChanged();
            }
        }
    }

    /** Rear sensor says dark (or there is no rear sensor: proximity alone decides). */
    private boolean isBackDark() {
        return mBackLight == null || (mBackLux >= 0 && mBackLux <= POCKET_MAX_BACK_LUX);
    }

    private boolean covered() {
        return mNear && !mFaceDown && isBackDark();
    }

    /** Pocket only after it has stayed covered (and not face down) for a moment. */
    private void scheduleFaceCheck() {
        mHandler.removeCallbacks(mPocketCheck);
        if (covered()) {
            mHandler.postDelayed(mPocketCheck, POCKET_DELAY_MS);
        } else {
            setInPocket(false);
        }
    }

    private final Runnable mPocketCheck = () -> setInPocket(mPocketOn && covered());

    private void setInPocket(boolean in) {
        if (in == mInPocket) return;
        mInPocket = in;
        Log.i(GlyphService.TAG, in ? "pocket: lights off" : "out of pocket");
        mListener.onAmbientChanged();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
