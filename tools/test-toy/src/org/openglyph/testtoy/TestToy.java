package org.openglyph.testtoy;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.nothing.thirdparty.IGlyphService;

/**
 * Test toy for OpenGlyph's Glyph Matrix SDK support. It follows only the documented toy
 * contract (service + meta-data + Messenger events) and draws through the compatibility
 * service's binder interface directly, so it needs no SDK and does not depend on Build.MODEL.
 *
 * Picture: a ring of 8 dots; the number of lit dots is the number of events seen (mod 9),
 * and the centre is lit while the button is held (action_down .. action_up).
 * Every step is logged with tag "TestToy".
 */
public final class TestToy extends Service {
    private static final String TAG = "TestToy";
    private IGlyphService mGlyph;
    private int mEvents;
    private boolean mHeld;

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind (selected)");
        Intent i = new Intent("com.nothing.thirdparty.bind_glyphservice")
                .setComponent(new ComponentName("com.nothing.thirdparty",
                        "com.nothing.thirdparty.GlyphService"));
        boolean ok = bindService(i, mConnection, BIND_AUTO_CREATE);
        Log.i(TAG, "bind compat service: " + ok);
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "onUnbind (deselected) after " + mEvents + " events");
        try {
            unbindService(mConnection);
        } catch (IllegalArgumentException ignored) {
        }
        mGlyph = null;
        return false;
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mGlyph = IGlyphService.Stub.asInterface(service);
            try {
                Log.i(TAG, "registerMatrixSDK: " + mGlyph.registerMatrixSDK("A024"));
            } catch (RemoteException e) {
                Log.w(TAG, "register failed", e);
            }
            draw();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mGlyph = null;
        }
    };

    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != 1) {
                super.handleMessage(msg);
                return;
            }
            String event = msg.getData().getString("data");
            mEvents++;
            if ("action_down".equals(event)) mHeld = true;
            if ("action_up".equals(event)) mHeld = false;
            Log.i(TAG, "event " + event + " (#" + mEvents + ")");
            draw();
        }
    });

    private void draw() {
        if (mGlyph == null) return;
        int[] grid = new int[625];
        int lit = mEvents % 9;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int x = (int) Math.round(12 + 9 * Math.sin(a));
            int y = (int) Math.round(12 - 9 * Math.cos(a));
            int v = i < lit ? 4095 : 600;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) grid[(y + dy) * 25 + x + dx] = v;
        }
        if (mHeld) for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
            grid[(12 + dy) * 25 + 12 + dx] = 4095;
        try {
            mGlyph.setMatrixColors(grid);
        } catch (RemoteException e) {
            Log.w(TAG, "setMatrixColors failed", e);
        }
    }
}
