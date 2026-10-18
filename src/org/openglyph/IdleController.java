package org.openglyph;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;

import org.openglyph.core.AodPolicy;
import org.openglyph.toys.IdleLayer;

/**
 * Always-on Glyph and Flip to Glyph.
 *
 * - Watches screen on/off and the charger, and (only while a feature needs it) the
 *   accelerometer for "face down".
 * - Decides with {@link AodPolicy} whether the dim clock in {@link IdleLayer} is shown, and
 *   wakes once a minute while it is, so the clock stays right through deep sleep.
 * - Flip to Glyph: face down silences the ringer (saving the old mode), face up restores it.
 *   Notifications keep lighting the matrix, which is the point.
 */
final class IdleController implements SensorEventListener {
    private final Context mContext;
    private final Handler mHandler;
    private final IdleLayer mIdle;
    private final Runnable mInvalidate;
    private final SensorManager mSensors;
    private final AlarmManager mAlarms;
    private final AudioManager mAudio;

    private boolean mScreenOff;
    private boolean mCharging;
    private boolean mFaceDown;
    private boolean mSensorOn;
    private boolean mAlarmSet;
    private int mSavedRingerMode = -1;

    IdleController(Context context, Handler handler, IdleLayer idle, Runnable invalidate) {
        mContext = context;
        mHandler = handler;
        mIdle = idle;
        mInvalidate = invalidate;
        mSensors = context.getSystemService(SensorManager.class);
        mAlarms = context.getSystemService(AlarmManager.class);
        mAudio = context.getSystemService(AudioManager.class);
    }

    void start() {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        mScreenOff = !pm.isInteractive();
        mCharging = mContext.getSystemService(BatteryManager.class).isCharging();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(mReceiver, f, null, mHandler);
        update();
    }

    void stop() {
        mContext.unregisterReceiver(mReceiver);
        setSensor(false);
        cancelMinuteAlarm();
        restoreRinger();
    }

    /** Re-evaluates everything; called on any state or settings change. */
    void update() {
        ContentResolver cr = mContext.getContentResolver();
        boolean enabled = GlyphSettings.flag(cr, GlyphSettings.ENABLED);
        AodPolicy.Mode mode = enabled
                ? AodPolicy.Mode.parse(GlyphSettings.string(cr, GlyphSettings.AOD, "OFF"))
                : AodPolicy.Mode.OFF;
        boolean flip = enabled && GlyphSettings.flagDefaultOff(cr, GlyphSettings.FLIP);

        // The accelerometer only runs while something needs "face down".
        setSensor(flip || mode == AodPolicy.Mode.FACE_DOWN);
        if (!flip) restoreRinger();

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean show = AodPolicy.shouldShow(mode, mScreenOff, mFaceDown, mCharging, hour,
                GlyphSettings.integer(cr, GlyphSettings.NIGHT_START, 23),
                GlyphSettings.integer(cr, GlyphSettings.NIGHT_END, 7));
        mIdle.setAlwaysOn(show);
        if (show) scheduleMinuteAlarm(); else cancelMinuteAlarm();
        mInvalidate.run();
    }

    private void setSensor(boolean on) {
        if (on == mSensorOn) return;
        if (on) {
            // Prefer the wake-up accelerometer so a flip is noticed while the CPU sleeps.
            Sensor accel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
            if (accel == null) accel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel == null) return;
            mSensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL,
                    1_000_000 /* batch up to 1 s */, mHandler);
            Log.i(GlyphService.TAG, "flip detection on (" + accel.getName()
                    + (accel.isWakeUpSensor() ? ", wake-up)" : ")"));
        } else {
            mSensors.unregisterListener(this);
            mFaceDown = false;
        }
        mSensorOn = on;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        boolean down = mFaceDown ? !AodPolicy.isFaceUp(x, y, z) : AodPolicy.isFaceDown(x, y, z);
        if (down == mFaceDown) return;
        mFaceDown = down;
        Log.i(GlyphService.TAG, down ? "face down" : "face up");
        if (GlyphSettings.flagDefaultOff(mContext.getContentResolver(), GlyphSettings.FLIP)) {
            if (down) silenceRinger(); else restoreRinger();
        }
        update();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void silenceRinger() {
        if (mSavedRingerMode >= 0) return;
        mSavedRingerMode = mAudio.getRingerMode();
        mAudio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
    }

    private void restoreRinger() {
        if (mSavedRingerMode < 0) return;
        // Only undo our own change: if the user picked another mode meanwhile, keep theirs.
        if (mAudio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
            mAudio.setRingerMode(mSavedRingerMode);
        }
        mSavedRingerMode = -1;
    }

    private void scheduleMinuteAlarm() {
        if (mAlarmSet) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        next.add(Calendar.MINUTE, 1);
        mAlarms.setExact(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), "OpenGlyph:clock",
                mMinuteAlarm, mHandler);
        mAlarmSet = true;
    }

    private void cancelMinuteAlarm() {
        if (!mAlarmSet) return;
        mAlarms.cancel(mMinuteAlarm);
        mAlarmSet = false;
    }

    private final AlarmManager.OnAlarmListener mMinuteAlarm = () -> {
        mAlarmSet = false;
        update(); // redraws the clock, re-checks the quiet period, re-arms if still shown
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_ON -> mScreenOff = false;
                case Intent.ACTION_SCREEN_OFF -> mScreenOff = true;
                case Intent.ACTION_POWER_CONNECTED -> mCharging = true;
                case Intent.ACTION_POWER_DISCONNECTED -> mCharging = false;
                default -> { return; }
            }
            update();
        }
    };
}
