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
 * sensors (each registered only while its feature is on).
 *
 * - Pocket: covered (proximity near) and not lying face down, which is Flip to Glyph's
 *   job, for a moment: the matrix goes dark until it is uncovered.
 * - Auto brightness: the matrix follows the ambient light on a log curve, smoothed so it does
 *   not flicker with every shadow.
 */
final class AmbientController implements SensorEventListener {
    interface Listener {
        void onAmbientChanged();
    }

    private static final long POCKET_DELAY_MS = 1500;
    static final int MIN_AUTO = 12;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final SensorManager mSensors;
    private final Sensor mProximity, mLight, mAccel;

    private boolean mPocketOn, mAutoOn;
    private boolean mNear, mFaceDown, mInPocket;
    private float mLux = -1;
    private int mAutoBrightness = 160;

    AmbientController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mSensors = context.getSystemService(SensorManager.class);
        mProximity = mSensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        mLight = mSensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        mAccel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    /** Re-reads the settings and (un)registers sensors accordingly. */
    void update() {
        ContentResolver cr = mContext.getContentResolver();
        boolean enabled = GlyphSettings.flag(cr, GlyphSettings.ENABLED);
        boolean pocket = enabled && GlyphSettings.flagDefaultOff(cr, GlyphSettings.POCKET);
        boolean auto = enabled && GlyphSettings.flagDefaultOff(cr, GlyphSettings.AUTO_BRIGHTNESS);
        if (pocket != mPocketOn) {
            mPocketOn = pocket;
            register(mProximity, pocket);
            register(mAccel, pocket);
            if (!pocket) setInPocket(false);
        }
        if (auto != mAutoOn) {
            mAutoOn = auto;
            register(mLight, auto);
        }
    }

    void stop() {
        mSensors.unregisterListener(this);
        mPocketOn = mAutoOn = false;
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
            // Smooth: follow quickly when it gets brighter, slowly when darker.
            mLux = mLux < 0 ? lux : (lux > mLux ? mLux + (lux - mLux) * 0.5f : mLux + (lux - mLux) * 0.15f);
            int b = luxToBrightness(mLux);
            if (Math.abs(b - mAutoBrightness) >= 4) {
                mAutoBrightness = b;
                mListener.onAmbientChanged();
            }
        }
    }

    /** Pocket only after it has stayed covered (and not face down) for a moment. */
    private void scheduleFaceCheck() {
        mHandler.removeCallbacks(mPocketCheck);
        if (mNear && !mFaceDown) {
            mHandler.postDelayed(mPocketCheck, POCKET_DELAY_MS);
        } else {
            setInPocket(false);
        }
    }

    private final Runnable mPocketCheck = () -> setInPocket(mPocketOn && mNear && !mFaceDown);

    private void setInPocket(boolean in) {
        if (in == mInPocket) return;
        mInPocket = in;
        Log.i(GlyphService.TAG, in ? "pocket: lights off" : "out of pocket");
        mListener.onAmbientChanged();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
