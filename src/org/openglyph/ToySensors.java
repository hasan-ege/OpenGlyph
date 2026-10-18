package org.openglyph;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;

import org.openglyph.toys.Toy;

/**
 * Sensor data for the toys: gravity (Leveller, Feed The Fly, shakes) and compass heading,
 * each running only while a toy on screen asks for it; and the last known location for Solar
 * Path, read without turning any location provider on.
 */
final class ToySensors implements SensorEventListener {
    private final SensorManager mSensors;
    private final LocationManager mLocation;
    private final Handler mHandler;
    private int mRunning;

    private volatile boolean mHasGravity, mHasHeading;
    private volatile float mGx, mGy, mGz, mHeading;
    private final float[] mRotation = new float[9];
    private final float[] mOrientation = new float[3];
    private volatile double mLat = Double.NaN, mLon = Double.NaN;
    private long mLocationReadMs = Long.MIN_VALUE;

    ToySensors(Context context, Handler handler) {
        mSensors = context.getSystemService(SensorManager.class);
        mLocation = context.getSystemService(LocationManager.class);
        mHandler = handler;
    }

    /** Runs exactly the sensors in {@code flags} (Toy.SENSOR_GRAVITY / SENSOR_HEADING). */
    void setWanted(int flags) {
        flags &= Toy.SENSOR_GRAVITY | Toy.SENSOR_HEADING;
        if (flags == mRunning) return;
        mSensors.unregisterListener(this);
        mHasGravity = false;
        mHasHeading = false;
        if ((flags & Toy.SENSOR_GRAVITY) != 0) {
            Sensor g = mSensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (g == null) g = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (g != null) mSensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME, mHandler);
            // Shakes need the raw accelerometer: gravity is low-pass filtered.
            Sensor a = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a != null && a != g) {
                mSensors.registerListener(mShakeListener, a, SensorManager.SENSOR_DELAY_GAME, mHandler);
            }
        } else {
            mSensors.unregisterListener(mShakeListener);
        }
        if ((flags & Toy.SENSOR_HEADING) != 0) {
            Sensor r = mSensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (r != null) mSensors.registerListener(this, r, SensorManager.SENSOR_DELAY_UI, mHandler);
        }
        mRunning = flags;
    }

    private volatile float mJolt;

    private final SensorEventListener mShakeListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            mJolt = (float) Math.sqrt(x * x + y * y + z * z);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                mGx = e.values[0];
                mGy = e.values[1];
                mGz = e.values[2];
                mHasGravity = true;
            }
            case Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(mRotation, e.values);
                SensorManager.getOrientation(mRotation, mOrientation);
                float deg = (float) Math.toDegrees(mOrientation[0]);
                mHeading = deg < 0 ? deg + 360 : deg;
                mHasHeading = true;
            }
            default -> {}
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /** Copies the latest values into a toy context. */
    void fill(Toy.Context ctx) {
        ctx.hasGravity = mHasGravity;
        if (mHasGravity) {
            ctx.gx = mGx;
            ctx.gy = mGy;
            // A jolt shows up as a gravity vector much longer than 9.8 (the toys' shake test).
            float jolt = mJolt;
            ctx.gz = jolt > 15 ? Math.copySign(jolt, mGz) : mGz;
        }
        ctx.hasHeading = mHasHeading;
        ctx.heading = mHeading;
        ctx.latitude = mLat;
        ctx.longitude = mLon;
    }

    /** Refreshes the last known location at most every 10 minutes (never starts a fix). */
    void refreshLocation(long now) {
        if (mLocationReadMs != Long.MIN_VALUE && now - mLocationReadMs < 600000) return;
        mLocationReadMs = now;
        Location best = null;
        try {
            for (String p : mLocation.getProviders(false)) {
                Location l = mLocation.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException e) {
            return;
        }
        if (best != null) {
            mLat = best.getLatitude();
            mLon = best.getLongitude();
        }
    }

    void stop() {
        mSensors.unregisterListener(this);
        mSensors.unregisterListener(mShakeListener);
        mRunning = 0;
    }
}
