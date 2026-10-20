package org.openglyph;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.openglyph.core.Frame;
import org.openglyph.core.MatrixLayout;

/**
 * Glyph Mirror: the main rear camera, shrunk to the 25x25 matrix (luminance, auto contrast,
 * mirrored so it moves like a mirror when you face the back of the phone). Long press takes a
 * full-size photo into DCIM/Camera. The camera only runs while the toy is on screen.
 *
 * We run as the system uid, so opening a camera would evict a camera app that is using one
 * (seen on device: Aperture lost the logical camera). So the mirror only opens when no camera is
 * in use, and lets go as soon as camera access priorities change (another app wants one).
 */
final class MirrorCamera {
    private static final String TAG = "OpenGlyphMirror";

    private final Context mContext;
    private final CameraManager mCameras;
    private HandlerThread mThread;
    private Handler mHandler;
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private ImageReader mPreview;
    private ImageReader mStill;
    private int mSensorOrientation;
    private volatile int[] mGrid;
    private boolean mWanted;

    /** Camera ids opened by anyone (including us), from the availability callback. */
    private final java.util.Set<String> mInUse = java.util.concurrent.ConcurrentHashMap.newKeySet();

    MirrorCamera(Context context) {
        mContext = context;
        mCameras = context.getSystemService(CameraManager.class);
        mCameras.registerAvailabilityCallback(mAvailability,
                new Handler(android.os.Looper.getMainLooper()));
    }

    private final CameraManager.AvailabilityCallback mAvailability =
            new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            mInUse.remove(id);
        }

        @Override
        public void onCameraUnavailable(String id) {
            mInUse.add(id);
        }

        @Override
        public void onCameraAccessPrioritiesChanged() {
            // Another app came to the front and may want a camera: give ours up.
            synchronized (MirrorCamera.this) {
                if (mDevice == null) return;
                Log.i(TAG, "camera access priorities changed: releasing the mirror camera");
                // Give the app time to open its camera before we would try again.
                mBackoffUntil = android.os.SystemClock.uptimeMillis() + 3000;
                mWanted = false;
                close();
            }
        }
    };

    /** Latest picture (625 grid), or null while the camera is starting / off. */
    int[] grid() {
        return mGrid;
    }

    synchronized void setWanted(boolean on) {
        if (on == mWanted) return;
        mWanted = on;
        if (on) open(); else close();
    }

    private String backCameraId() throws Exception {
        for (String id : mCameras.getCameraIdList()) {
            CameraCharacteristics c = mCameras.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    private long mBackoffUntil;
    private boolean mLoggedBusy;

    private void open() {
        if (!mInUse.isEmpty() || android.os.SystemClock.uptimeMillis() < mBackoffUntil) {
            // Opening any camera now would take one from (or conflict with) the app using it.
            // Not wanted, so the host's next check (once a second) tries again.
            if (!mLoggedBusy) Log.i(TAG, "a camera is in use " + mInUse + ": mirror stays off");
            mLoggedBusy = true;
            mWanted = false;
            return;
        }
        mLoggedBusy = false;
        mThread = new HandlerThread("OpenGlyphMirror");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        try {
            String id = backCameraId();
            if (id == null) return;
            CameraCharacteristics c = mCameras.getCameraCharacteristics(id);
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = orientation == null ? 90 : orientation;
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size small = smallest(map.getOutputSizes(ImageFormat.YUV_420_888), 160);
            Size big = largest(map.getOutputSizes(ImageFormat.JPEG));
            mPreview = ImageReader.newInstance(small.getWidth(), small.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            mPreview.setOnImageAvailableListener(this::onPreview, mHandler);
            mStill = ImageReader.newInstance(big.getWidth(), big.getHeight(), ImageFormat.JPEG, 1);
            mStill.setOnImageAvailableListener(this::onStill, mHandler);
            mCameras.openCamera(id, mHandler::post, mDeviceCallback);
        } catch (Exception e) {
            Log.w(TAG, "camera open failed", e);
        }
    }

    private static Size smallest(Size[] sizes, int minWidth) {
        Size best = sizes[0];
        for (Size s : sizes) {
            if (s.getWidth() >= minWidth && (best.getWidth() < minWidth
                    || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best;
    }

    private static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private final CameraDevice.StateCallback mDeviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            synchronized (MirrorCamera.this) {
                if (!mWanted) {
                    device.close();
                    return;
                }
                mDevice = device;
            }
            try {
                List<OutputConfiguration> outs = List.of(
                        new OutputConfiguration(mPreview.getSurface()),
                        new OutputConfiguration(mStill.getSurface()));
                device.createCaptureSession(new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outs, mHandler::post, mSessionCallback));
            } catch (Exception e) {
                Log.w(TAG, "session failed", e);
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            device.close();
            mGrid = null;
        }

        @Override
        public void onError(CameraDevice device, int error) {
            Log.w(TAG, "camera error " + error);
            device.close();
            mGrid = null;
        }
    };

    private final CameraCaptureSession.StateCallback mSessionCallback =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            mSession = session;
            try {
                CaptureRequest.Builder b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(mPreview.getSurface());
                session.setRepeatingRequest(b.build(), null, mHandler);
            } catch (Exception e) {
                Log.w(TAG, "preview failed", e);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.w(TAG, "session configure failed");
        }
    };

    /** What the matrix showed when the shutter went (625 grid, 0..Frame.MAX), saved with it. */
    private volatile int[] mGlyphShot;

    /** Takes a full-size photo (saved to DCIM/Camera), with the matrix picture {@code glyph}. */
    synchronized void capture(int[] glyph) {
        if (mSession == null || mDevice == null) return;
        mGlyphShot = glyph;
        try {
            CaptureRequest.Builder b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mStill.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, mSensorOrientation);
            mSession.capture(b.build(), null, mHandler);
        } catch (Exception e) {
            Log.w(TAG, "capture failed", e);
        }
    }

    private void onStill(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buf.remaining()];
            buf.get(jpeg);
            String name = "IMG_GLYPH_" + System.currentTimeMillis();
            // The photo as the camera took it, and the selfie as seen in the Glyph mirror.
            save(name + ".jpg", jpeg, "image/jpeg");
            byte[] mirrored = mirrored(jpeg);
            if (mirrored != null) save(name + "_MIRROR.jpg", mirrored, "image/jpeg");
            // And the shot as the Glyph Matrix showed it, LED by LED.
            int[] glyph = mGlyphShot;
            mGlyphShot = null;
            byte[] png = glyph == null ? null : glyphPicture(glyph);
            if (png != null) save(name + "_GLYPH.png", png, "image/png");
        } catch (Exception e) {
            Log.w(TAG, "saving photo failed", e);
        }
    }

    private void save(String name, byte[] data, String mime) throws java.io.IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
        Uri uri = mContext.getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return;
        try (OutputStream out = mContext.getContentResolver().openOutputStream(uri)) {
            out.write(data);
        }
        Log.i(TAG, "photo saved: " + uri);
    }

    /**
     * The matrix as a picture: round LEDs on black, each as bright as it was, unlit ones
     * faintly visible, like looking at the back of the phone.
     */
    static byte[] glyphPicture(int[] grid) {
        final int size = 1200, n = org.openglyph.core.MatrixLayout.WIDTH;
        final float cell = size * 0.92f / n, origin = (size - cell * n) / 2;
        android.graphics.Bitmap bmp = null;
        try {
            bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            c.drawColor(0xFF000000);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (!org.openglyph.core.MatrixLayout.exists(x, y)) continue;
                    double v = grid[y * n + x] / (double) org.openglyph.core.Frame.MAX;
                    int level = (int) Math.round(22 + 233 * Math.pow(Math.max(0, Math.min(1, v)), 0.8));
                    p.setColor(0xFF000000 | level << 16 | level << 8 | level);
                    c.drawCircle(origin + (x + 0.5f) * cell, origin + (y + 0.5f) * cell, cell * 0.38f, p);
                }
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes);
            return bytes.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "glyph picture failed", e);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** The photo upright (EXIF orientation applied) and flipped left-right, as a JPEG. */
    private static byte[] mirrored(byte[] jpeg) {
        try {
            int orientation = new android.media.ExifInterface(new java.io.ByteArrayInputStream(jpeg))
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL);
            int degrees = switch (orientation) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270;
                default -> 0;
            };
            android.graphics.Bitmap photo = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (photo == null) return null;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(degrees);
            m.postScale(-1, 1);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(photo, 0, 0,
                    photo.getWidth(), photo.getHeight(), m, true);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, bytes);
            photo.recycle();
            out.recycle();
            return bytes.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "mirrored copy failed", e);
            return null;
        }
    }

    private void onPreview(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            mGrid = toGrid(image, mSensorOrientation);
        } catch (Exception e) {
            Log.w(TAG, "preview frame failed", e);
        }
    }

    /** Centre square of the luminance plane, averaged into 25x25, rotated upright, mirrored. */
    static int[] toGrid(Image image, int sensorOrientation) {
        Image.Plane y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int w = image.getWidth(), h = image.getHeight();
        int rowStride = y.getRowStride(), pixStride = y.getPixelStride();
        int side = Math.min(w, h);
        int x0 = (w - side) / 2, y0 = (h - side) / 2;
        double[] lum = new double[MatrixLayout.GRID_SIZE];
        double lo = 255, hi = 0;
        for (int gy = 0; gy < 25; gy++) {
            for (int gx = 0; gx < 25; gx++) {
                int sx0 = x0 + gx * side / 25, sx1 = x0 + (gx + 1) * side / 25;
                int sy0 = y0 + gy * side / 25, sy1 = y0 + (gy + 1) * side / 25;
                long sum = 0;
                int n = 0;
                for (int sy = sy0; sy < sy1; sy += 2) {
                    for (int sx = sx0; sx < sx1; sx += 2) {
                        sum += buf.get(sy * rowStride + sx * pixStride) & 0xff;
                        n++;
                    }
                }
                double v = n == 0 ? 0 : sum / (double) n;
                // Sensor rows run along the long side; turn the picture upright.
                int ox, oy;
                switch (sensorOrientation) {
                    case 90 -> { ox = 24 - gy; oy = gx; }
                    case 180 -> { ox = 24 - gx; oy = 24 - gy; }
                    case 270 -> { ox = gy; oy = 24 - gx; }
                    default -> { ox = gx; oy = gy; }
                }
                ox = 24 - ox; // seen from the back, like a mirror
                lum[oy * 25 + ox] = v;
                if (MatrixLayout.exists(ox, oy)) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
        }
        int[] grid = new int[MatrixLayout.GRID_SIZE];
        double range = Math.max(24, hi - lo);
        for (int i = 0; i < grid.length; i++) {
            double t = Math.max(0, Math.min(1, (lum[i] - lo) / range));
            grid[i] = (int) (Frame.MAX * t * t); // gamma: LEDs look brighter than they are
        }
        return grid;
    }

    private synchronized void close() {
        try {
            if (mSession != null) mSession.close();
        } catch (Exception ignored) {
        }
        if (mDevice != null) mDevice.close();
        if (mPreview != null) mPreview.close();
        if (mStill != null) mStill.close();
        mSession = null;
        mDevice = null;
        mPreview = null;
        mStill = null;
        mGrid = null;
        if (mThread != null) mThread.quitSafely();
        mThread = null;
    }
}
