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
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openglyph.toys.RemoteToy;

/**
 * Hosts toys written with the Glyph Matrix SDK, following its README:
 *
 * - toys are exported services with action {@code com.nothing.glyph.TOY} and meta-data
 *   {@code com.nothing.glyph.toy.name / image / summary} (resources) and
 *   {@code longpress / aod_support} ("1" to enable);
 * - the host binds the toy while it is selected, unbinds it when another toy is selected;
 * - onBind may return a Messenger, which receives {@code what = 1} messages whose bundle
 *   string {@code "data"} is the event: change, action_down, action_up, or aod (once a minute
 *   while the toy is the always-on toy).
 *
 * The toy draws through OpenGlyphCompat, which hands frames to the service by package.
 */
final class NothingToyBridge implements RemoteToy.Listener {
    static final String ACTION_TOY = "com.nothing.glyph.TOY";
    private static final String META_NAME = "com.nothing.glyph.toy.name";
    private static final String META_IMAGE = "com.nothing.glyph.toy.image";
    private static final String META_SUMMARY = "com.nothing.glyph.toy.summary";
    private static final String META_LONGPRESS = "com.nothing.glyph.toy.longpress";
    private static final String META_AOD = "com.nothing.glyph.toy.aod_support";
    private static final int MSG_GLYPH_TOY = 1;
    private static final String MSG_DATA = "data";

    interface ToysChanged {
        void onToysChanged(List<RemoteToy> toys);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final ToysChanged mChanged;
    /** Bindings because the toy is selected on the Glyph Button. */
    private final Map<RemoteToy, Connection> mSelected =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Binding because the toy is the always-on toy (separate, so either can end alone). */
    private volatile Connection mAod;
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
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        mContext.registerReceiver(mPackageReceiver, f, null, mHandler);
        rescan();
    }

    void stop() {
        mContext.unregisterReceiver(mPackageReceiver);
        mHandler.post(() -> {
            for (Connection c : mSelected.values()) mContext.unbindService(c);
            mSelected.clear();
            stopAodNow();
        });
    }

    List<RemoteToy> toys() {
        return mToys;
    }

    RemoteToy byId(String id) {
        for (RemoteToy t : mToys) if (t.id().equals(id)) return t;
        return null;
    }

    /** The toy of this package that is bound now (selected or always-on), else null. */
    RemoteToy boundToyOf(String pkg) {
        for (RemoteToy t : mSelected.keySet()) if (t.packageName().equals(pkg)) return t;
        if (mAod != null && mAod.toy.packageName().equals(pkg)) return mAod.toy;
        return null;
    }

    /** Preview image (com.nothing.glyph.toy.image) for Settings, or null. */
    Drawable preview(RemoteToy toy) {
        try {
            PackageManager pm = mContext.getPackageManager();
            ServiceInfo si = pm.getServiceInfo(new ComponentName(toy.packageName(),
                    toy.className()), PackageManager.GET_META_DATA);
            if (si.metaData == null || si.metaData.getInt(META_IMAGE, 0) == 0) return null;
            return pm.getResourcesForApplication(si.applicationInfo)
                    .getDrawable(si.metaData.getInt(META_IMAGE), null);
        } catch (Exception e) {
            return null;
        }
    }

    private void rescan() {
        PackageManager pm = mContext.getPackageManager();
        List<RemoteToy> found = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentServices(new Intent(ACTION_TOY),
                PackageManager.GET_META_DATA)) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null || !si.exported) continue;
            Bundle meta = si.metaData;
            Resources res = null;
            try {
                res = pm.getResourcesForApplication(si.applicationInfo);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            String label = text(meta, META_NAME, res);
            if (label == null) label = si.loadLabel(pm).toString();
            String summary = text(meta, META_SUMMARY, res);
            found.add(new RemoteToy(si.packageName, si.name, label,
                    summary == null ? "" : summary, flag(meta, META_LONGPRESS),
                    flag(meta, META_AOD), this));
        }
        mToys = found;
        Log.i(GlyphService.TAG, "Glyph Matrix SDK toys: " + found.size());
        mChanged.onToysChanged(found);
    }

    /** android:resource="@string/.." (int) or android:value="..." (string). */
    private static String text(Bundle meta, String key, Resources res) {
        if (meta == null || !meta.containsKey(key)) return null;
        Object v = meta.get(key);
        if (v instanceof Integer && res != null) {
            try {
                return res.getString((Integer) v);
            } catch (Resources.NotFoundException e) {
                return null;
            }
        }
        return v == null ? null : v.toString();
    }

    private static boolean flag(Bundle meta, String key) {
        if (meta == null || !meta.containsKey(key)) return false;
        Object v = meta.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Integer) return (Integer) v != 0;
        return v != null && ("1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString()));
    }

    private Connection bind(RemoteToy toy) {
        Connection c = new Connection(toy);
        Intent intent = new Intent(ACTION_TOY)
                .setComponent(new ComponentName(toy.packageName(), toy.className()));
        if (mContext.bindService(intent, c, Context.BIND_AUTO_CREATE)) return c;
        Log.w(GlyphService.TAG, "cannot bind toy " + toy.className());
        return null;
    }

    @Override
    public void onSelect(RemoteToy toy) {
        mHandler.post(() -> {
            if (mSelected.containsKey(toy)) return;
            Connection c = bind(toy);
            if (c != null) mSelected.put(toy, c);
        });
    }

    @Override
    public void onDeselect(RemoteToy toy) {
        mHandler.post(() -> {
            Connection c = mSelected.remove(toy);
            if (c != null) mContext.unbindService(c);
        });
    }

    @Override
    public void onEvent(RemoteToy toy, String event) {
        mHandler.post(() -> send(mSelected.get(toy), event));
    }

    /** Always-on toy: bind it (if not yet) and send it the minute's "aod" event. */
    void aodTick(RemoteToy toy) {
        mHandler.post(() -> {
            if (mAod != null && mAod.toy != toy) stopAodNow();
            if (mAod == null) {
                mAod = bind(toy);
                if (mAod == null) return;
                mAod.sendOnConnect = RemoteToy.EVENT_AOD;
                return;
            }
            send(mAod, RemoteToy.EVENT_AOD);
        });
    }

    void stopAod() {
        mHandler.post(this::stopAodNow);
    }

    private void stopAodNow() {
        if (mAod == null) return;
        mContext.unbindService(mAod);
        mAod = null;
    }

    private void send(Connection c, String event) {
        if (c == null) return;
        if (c.messenger == null) {
            if (RemoteToy.EVENT_AOD.equals(event)) c.sendOnConnect = event;
            return;
        }
        Message msg = Message.obtain(null, MSG_GLYPH_TOY);
        Bundle data = new Bundle();
        data.putString(MSG_DATA, event);
        msg.setData(data);
        try {
            c.messenger.send(msg);
        } catch (RemoteException e) {
            Log.w(GlyphService.TAG, "toy " + c.toy.className() + " gone", e);
        }
    }

    private final class Connection implements ServiceConnection {
        final RemoteToy toy;
        Messenger messenger;
        /** An event to deliver as soon as the toy's Messenger arrives (first aod tick). */
        String sendOnConnect;

        Connection(RemoteToy toy) {
            this.toy = toy;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            messenger = service == null ? null : new Messenger(service);
            Log.i(GlyphService.TAG, "toy bound: " + toy.className()
                    + (messenger == null ? " (no Messenger)" : ""));
            if (sendOnConnect != null) {
                String e = sendOnConnect;
                sendOnConnect = null;
                send(this, e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            messenger = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            // onBind returned null: the toy works but takes no button events.
            Log.i(GlyphService.TAG, "toy bound: " + toy.className() + " (no Messenger)");
        }
    }

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            rescan();
        }
    };
}
