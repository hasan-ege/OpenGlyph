package com.nothing.thirdparty;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.openglyph.IOpenGlyph;

/**
 * OpenGlyphCompat: answers apps built with Nothing's Glyph Matrix SDK (which bind
 * {@code com.nothing.thirdparty/.GlyphService}) and forwards their frames to OpenGlyph.
 *
 * Only the matrix calls do something. The legacy Glyph-strip calls (openSession,
 * setFrameColors, ...) have no hardware on the Phone (3) and are accepted and ignored.
 */
public final class GlyphService extends Service {
    private static final String TAG = "OpenGlyphCompat";
    private static final int MATRIX_PIXELS = 25 * 25;
    private static final int MAX_VALUE = 4095;

    private IOpenGlyph mOpenGlyph;
    private boolean mBinding;
    /** Packages that drew an app frame, cleared when the last client unbinds. */
    private final Set<String> mDrawing = new HashSet<>();
    /** Packages whose first frame was logged (frame size problems show up there). */
    private final Set<String> mSeen = new HashSet<>();
    /**
     * Latest frame per package while OpenGlyph is still connecting. A toy draws its first frame
     * right after binding, often once only, so it must not be lost while we connect.
     */
    private final Map<String, int[]> mPending = new LinkedHashMap<>();
    private final Map<String, Boolean> mPendingTimeout = new LinkedHashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        connect();
        return mStub;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // No client is left: nothing can call closeAppMatrix() any more, so clear their frames.
        Set<String> pkgs;
        synchronized (mDrawing) {
            pkgs = new HashSet<>(mDrawing);
            mDrawing.clear();
        }
        IOpenGlyph og = mOpenGlyph;
        if (og != null) {
            for (String pkg : pkgs) {
                try {
                    og.clearPackageFrame(pkg);
                } catch (RemoteException ignored) {
                }
            }
        }
        return false;
    }

    @Override
    public void onDestroy() {
        if (mBinding) unbindService(mConnection);
        super.onDestroy();
    }

    private synchronized void connect() {
        if (mBinding) return;
        Intent intent = new Intent("org.openglyph.BIND").setPackage("org.openglyph");
        mBinding = bindService(intent, mConnection, BIND_AUTO_CREATE);
        if (!mBinding) Log.w(TAG, "OpenGlyph not available");
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IOpenGlyph og = IOpenGlyph.Stub.asInterface(service);
            Map<String, int[]> frames;
            Map<String, Boolean> timeouts;
            synchronized (mPending) {
                mOpenGlyph = og;
                frames = new LinkedHashMap<>(mPending);
                timeouts = new LinkedHashMap<>(mPendingTimeout);
                mPending.clear();
                mPendingTimeout.clear();
            }
            try {
                for (Map.Entry<String, Boolean> e : timeouts.entrySet()) {
                    og.setPackageTimeout(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, int[]> e : frames.entrySet()) {
                    og.showPackageFrame(e.getKey(), e.getValue());
                }
                if (!frames.isEmpty()) Log.i(TAG, "delivered " + frames.size() + " frame(s) held while connecting");
            } catch (RemoteException e) {
                mOpenGlyph = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mOpenGlyph = null;
        }
    };

    /** The package of the app calling us (first one for shared uids). */
    private String callingPackage() {
        String[] pkgs = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        return pkgs == null || pkgs.length == 0 ? null : pkgs[0];
    }

    private void forward(int[] colors, boolean app) {
        String pkg = callingPackage();
        IOpenGlyph og = mOpenGlyph;
        if (pkg == null || colors == null) return;
        synchronized (mSeen) {
            if (mSeen.add(pkg)) {
                Log.i(TAG, "first " + (app ? "app" : "toy") + " frame from " + pkg + ": "
                        + colors.length + " values" + (colors.length != MATRIX_PIXELS
                        ? " (expected " + MATRIX_PIXELS + ")" : ""));
            }
        }
        int[] grid = new int[MATRIX_PIXELS];
        int n = Math.min(colors.length, MATRIX_PIXELS);
        for (int i = 0; i < n; i++) grid[i] = Math.max(0, Math.min(MAX_VALUE, colors[i]));
        if (app) {
            synchronized (mDrawing) {
                mDrawing.add(pkg);
            }
        }
        if (og == null) {
            synchronized (mPending) {
                og = mOpenGlyph;
                if (og == null) {
                    mPending.put(pkg, grid); // delivered by onServiceConnected
                    connect();
                    return;
                }
            }
        }
        final long id = Binder.clearCallingIdentity(); // call OpenGlyph as ourselves
        try {
            og.showPackageFrame(pkg, grid);
        } catch (RemoteException e) {
            mOpenGlyph = null;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    private final IGlyphService.Stub mStub = new IGlyphService.Stub() {
        @Override public void setFrameColors(int[] colors) {}
        @Override public void openSession() {}
        @Override public void closeSession() {}

        @Override public boolean register(String target) {
            return true;
        }

        @Override public boolean registerSDK(String target, String version) {
            return true;
        }

        @Override public boolean registerMatrixSDK(String target) {
            Log.i(TAG, callingPackage() + " registered for " + target);
            return true;
        }

        @Override public void setMatrixColors(int[] colors) {
            forward(colors, false); // toys
        }

        @Override public void setGlyphMatrixTimeout(boolean enable) {
            String pkg = callingPackage();
            if (pkg == null) return;
            IOpenGlyph og;
            synchronized (mPending) {
                og = mOpenGlyph;
                if (og == null) {
                    mPendingTimeout.put(pkg, enable);
                    connect();
                    return;
                }
            }
            final long id = Binder.clearCallingIdentity();
            try {
                og.setPackageTimeout(pkg, enable);
            } catch (RemoteException ignored) {
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }

        @Override public void setAppMatrixColors(int[] colors) {
            forward(colors, true); // apps
        }

        @Override public void closeAppMatrix() {
            String pkg = callingPackage();
            IOpenGlyph og = mOpenGlyph;
            if (pkg == null || og == null) return;
            synchronized (mDrawing) {
                mDrawing.remove(pkg);
            }
            final long id = Binder.clearCallingIdentity();
            try {
                og.clearPackageFrame(pkg);
            } catch (RemoteException ignored) {
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
    };
}
