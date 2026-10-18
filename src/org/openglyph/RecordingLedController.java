package org.openglyph;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openglyph.core.RecordingDetector;
import org.openglyph.core.TimerLed;

/**
 * Blinks the red LED next to the cameras while a video is being recorded, like stock
 * Nothing OS. "Recording" = an app has a camera open and is capturing audio
 * (see {@link RecordingDetector}).
 *
 * Setting: {@link GlyphSettings#RECORD_LED}.
 */
final class RecordingLedController {
    private static final int BLINK_ON_MS = 500;
    private static final int BLINK_OFF_MS = 500;

    private final Context mContext;
    private final Handler mHandler;
    private final RecordingDetector mDetector = new RecordingDetector();
    private final TimerLed mLed = new TimerLed(TimerLed.RECORD_LED_DIR);
    private CameraManager mCameras;
    private AudioManager mAudio;

    RecordingLedController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    void start() {
        logLedNode();
        mCameras = mContext.getSystemService(CameraManager.class);
        mAudio = mContext.getSystemService(AudioManager.class);
        mCameras.registerAvailabilityCallback(mContext.getMainExecutor(), mCameraCallback);
        mAudio.registerAudioRecordingCallback(mAudioCallback, mHandler);
        mDetector.setRecordingUids(uidsOf(mAudio.getActiveRecordingConfigurations()));
        update();
    }

    void stop() {
        mCameras.unregisterAvailabilityCallback(mCameraCallback);
        mAudio.unregisterAudioRecordingCallback(mAudioCallback);
        setBlinking(false);
    }

    /** Re-reads the setting; called by the service's settings observer. */
    void update() {
        boolean enabled = GlyphSettings.flag(mContext.getContentResolver(), GlyphSettings.RECORD_LED);
        setBlinking(enabled && mDetector.isRecordingVideo());
    }

    private void setBlinking(boolean on) {
        if (on == mLed.isBlinking()) return;
        try {
            if (on) mLed.blink(BLINK_ON_MS, BLINK_OFF_MS); else mLed.off();
            Log.i(GlyphService.TAG, "record LED " + (on ? "blinking" : "off"));
        } catch (IOException e) {
            Log.w(GlyphService.TAG, "record LED: " + e.getMessage());
        }
    }

    /** First-boot diagnostics: where the LED really lives and whether we may drive it. */
    private void logLedNode() {
        File dir = new File(TimerLed.RECORD_LED_DIR);
        String real;
        try {
            real = dir.getCanonicalPath();
        } catch (IOException e) {
            real = "? (" + e.getMessage() + ")";
        }
        Log.i(GlyphService.TAG, "record LED node " + real
                + " trigger writable=" + new File(dir, "trigger").canWrite());
    }

    private Set<Integer> uidsOf(List<AudioRecordingConfiguration> configs) {
        Set<Integer> uids = new HashSet<>();
        if (configs == null) return uids;
        for (AudioRecordingConfiguration c : configs) {
            // getClientUid() is filled in for callers holding MODIFY_AUDIO_ROUTING.
            if (!c.isClientSilenced()) uids.add(c.getClientUid());
        }
        return uids;
    }

    private int uidOf(String packageName) {
        try {
            return mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private final CameraManager.AvailabilityCallback mCameraCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraOpened(String cameraId, String packageId) {
                    mDetector.cameraOpened(cameraId, uidOf(packageId));
                    update();
                }

                @Override
                public void onCameraClosed(String cameraId) {
                    mDetector.cameraClosed(cameraId);
                    update();
                }
            };

    private final AudioManager.AudioRecordingCallback mAudioCallback =
            new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    mDetector.setRecordingUids(uidsOf(configs));
                    update();
                }
            };
}
