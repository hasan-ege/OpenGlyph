package org.openglyph;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openglyph.toys.RemoteToy;

/**
 * Hosts toys written with Nothing's Glyph Matrix SDK, using only what the SDK's README documents:
 * services with action {@code com.nothing.glyph.TOY}, their {@code com.nothing.glyph.toy.*}
 * meta-data, and a Messenger that receives {@code what = 1} messages carrying the event in the
 * {@code "data"} string of the bundle. The toy draws through OpenGlyphCompat, which hands the
 * frames to {@link GlyphService#showPackageFrame}.
 */
final class NothingToyBridge implements RemoteToy.Listener {
    static final String ACTION_TOY = "com.nothing.glyph.TOY";
    private static final String META_NAME = "com.nothing.glyph.toy.name";
    private static final String META_LONGPRESS = "com.nothing.glyph.toy.longpress";
    private static final int MSG_GLYPH_TOY = 1;
    private static final String MSG_DATA = "data";

    interface ToysChanged {
        void onToysChanged(List<RemoteToy> toys);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final ToysChanged mChanged;
    private final Map<RemoteToy, Connection> mBound = new HashMap<>();
    private volatile List<RemoteToy> mToys = new ArrayList<>();

    NothingToyBridge(Context context, Handler handler, ToysChanged changed) {
        mContext = context;
        mHandler = handler;
        mChanged = changed;
    }

    void start() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        mContext.registerReceiver(mPackageReceiver, f, null, mHandler);
        rescan();
    }

    void stop() {
        mContext.unregisterReceiver(mPackageReceiver);
        mHandler.post(() -> {
            for (Connection c : mBound.values()) mContext.unbindService(c);
            mBound.clear();
        });
    }

    List<RemoteToy> toys() {
        return mToys;
    }

    /** The toy of this package that is on screen now, else any toy of it, else null. */
    RemoteToy toyOf(String pkg) {
        RemoteToy any = null;
        for (RemoteToy t : mToys) {
            if (!t.packageName().equals(pkg)) continue;
            if (mBound.containsKey(t)) return t;
            if (any == null) any = t;
        }
        return any;
    }

    private void rescan() {
        PackageManager pm = mContext.getPackageManager();
        List<RemoteToy> found = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentServices(new Intent(ACTION_TOY),
                PackageManager.GET_META_DATA)) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null || !si.exported) continue;
            CharSequence label = si.loadLabel(pm);
            boolean longPress = false;
            if (si.metaData != null) {
                Object name = si.metaData.get(META_NAME);
                if (name instanceof Integer) {
                    try {
                        label = pm.getResourcesForApplication(si.applicationInfo)
                                .getString((Integer) name);
                    } catch (Exception ignored) {
                    }
                } else if (name != null) {
                    label = name.toString();
                }
                Object lp = si.metaData.get(META_LONGPRESS);
                longPress = lp != null && !"0".equals(lp.toString())
                        && !"false".equalsIgnoreCase(lp.toString());
            }
            found.add(new RemoteToy(si.packageName, si.name, label.toString(), longPress, this));
        }
        mToys = found;
        Log.i(GlyphService.TAG, "Nothing SDK toys: " + found.size());
        mChanged.onToysChanged(found);
    }

    @Override
    public void onShow(RemoteToy toy) {
        mHandler.post(() -> {
            if (mBound.containsKey(toy)) return;
            Connection c = new Connection(toy);
            Intent intent = new Intent(ACTION_TOY)
                    .setComponent(new ComponentName(toy.packageName(), toy.className()));
            if (mContext.bindService(intent, c, Context.BIND_AUTO_CREATE)) {
                mBound.put(toy, c);
            } else {
                Log.w(GlyphService.TAG, "cannot bind toy " + toy.className());
            }
        });
    }

    @Override
    public void onHide(RemoteToy toy) {
        mHandler.post(() -> {
            Connection c = mBound.remove(toy);
            if (c != null) mContext.unbindService(c);
        });
    }

    @Override
    public void onEvent(RemoteToy toy, String event) {
        mHandler.post(() -> {
            Connection c = mBound.get(toy);
            if (c == null || c.messenger == null) return;
            Message msg = Message.obtain(null, MSG_GLYPH_TOY);
            Bundle data = new Bundle();
            data.putString(MSG_DATA, event);
            msg.setData(data);
            try {
                c.messenger.send(msg);
            } catch (RemoteException e) {
                Log.w(GlyphService.TAG, "toy " + toy.className() + " gone", e);
            }
        });
    }

    private final class Connection implements ServiceConnection {
        final RemoteToy toy;
        Messenger messenger;

        Connection(RemoteToy toy) {
            this.toy = toy;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            messenger = service == null ? null : new Messenger(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            messenger = null;
        }
    }

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            rescan();
        }
    };
}
