package org.openglyph.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a video is being recorded: some camera is open AND the app that opened it is
 * also capturing audio. A photo (camera only) or a voice memo (microphone only) does not count.
 * A muted video recording is not detected, since nothing in the platform reports "encoding".
 */
public final class RecordingDetector {
    /** camera id -> uid of the app that opened it */
    private final Map<String, Integer> openCameras = new HashMap<>();
    private Set<Integer> recordingUids = new HashSet<>();

    public synchronized void cameraOpened(String cameraId, int uid) {
        openCameras.put(cameraId, uid);
    }

    public synchronized void cameraClosed(String cameraId) {
        openCameras.remove(cameraId);
    }

    /** The full set of uids with an active audio recording, as the audio callback reports it. */
    public synchronized void setRecordingUids(Set<Integer> uids) {
        recordingUids = new HashSet<>(uids);
    }

    public synchronized boolean isRecordingVideo() {
        for (int uid : openCameras.values()) {
            if (uid >= 0 && recordingUids.contains(uid)) return true;
        }
        return false;
    }
}
