package org.openglyph.glyphlive;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import com.nothing.thirdparty.IGlyphService;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Receives Glyph Matrix frames over HTTP on 127.0.0.1:9090 (reach it from the computer with
 * "adb forward tcp:9090 tcp:9090") and draws them through the Glyph Matrix SDK interface, so a
 * browser simulator can be watched on the real matrix. Frames show while this screen is open.
 *
 * One page drives the matrix at a time: POST /claim with a session id makes that page the owner,
 * and POST /frame bodies are "id|v,v,...,v" (625 values 0..4095, 25x25 row by row). Frames from
 * any other id get 409, so two open pages can no longer take turns on the matrix.
 */
public class LiveActivity extends Activity {
    private static final String TAG = "GlyphLive";
    static final int PORT = 9090;
    private static final int GRID = 625;

    private volatile IGlyphService mGlyph;
    private ServerSocket mServer;
    private TextView mStatus;
    private volatile int mFrames;
    /** Session id of the page that last claimed the matrix. */
    private volatile String mOwner = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mStatus = new TextView(this);
        mStatus.setPadding(48, 160, 48, 48);
        mStatus.setTextSize(18);
        setContentView(mStatus);
        status("Bağlanıyor…");
        Intent i = new Intent("com.nothing.thirdparty.bind_glyphservice").setComponent(
                new ComponentName("com.nothing.thirdparty", "com.nothing.thirdparty.GlyphService"));
        if (!bindService(i, mConnection, BIND_AUTO_CREATE)) status("Glyph servisi bulunamadı");
        new Thread(this::serve, "GlyphLiveServer").start();
    }

    @Override
    protected void onDestroy() {
        try {
            if (mServer != null) mServer.close();
        } catch (IOException ignored) {
        }
        IGlyphService g = mGlyph;
        if (g != null) {
            try {
                g.closeAppMatrix();
            } catch (Exception ignored) {
            }
        }
        unbindService(mConnection);
        super.onDestroy();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IGlyphService g = IGlyphService.Stub.asInterface(service);
            try {
                g.registerMatrixSDK("A024");
            } catch (Exception e) {
                Log.w(TAG, "register", e);
            }
            mGlyph = g;
            status("Hazır. Bilgisayarda: adb forward tcp:" + PORT + " tcp:" + PORT);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mGlyph = null;
            status("Glyph servisi koptu");
        }
    };

    private void status(String s) {
        runOnUiThread(() -> mStatus.setText("Glyph Live\n\n" + s + "\n\nKare: " + mFrames));
    }

    private void serve() {
        try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))) {
            mServer = server;
            while (!server.isClosed()) {
                Socket s = server.accept();
                new Thread(() -> handle(s), "GlyphLiveConn").start();
            }
        } catch (IOException e) {
            Log.i(TAG, "server stopped: " + e);
        }
    }

    /** Minimal HTTP/1.1 with keep-alive: enough for fetch() from the simulator page. */
    private void handle(Socket s) {
        try (Socket sock = s; InputStream in = new BufferedInputStream(sock.getInputStream());
                OutputStream out = sock.getOutputStream()) {
            int[] grid = new int[GRID];
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) return;
                String method = line.split(" ")[0];
                int length = 0;
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    int c = h.indexOf(':');
                    if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                        length = Integer.parseInt(h.substring(c + 1).trim());
                    }
                }
                byte[] body = new byte[length];
                int n = 0;
                while (n < length) {
                    int r = in.read(body, n, length - n);
                    if (r < 0) return;
                    n += r;
                }
                String path = line.split(" ").length > 1 ? line.split(" ")[1] : "";
                String text = new String(body, StandardCharsets.US_ASCII);
                int code = 200;
                if ("POST".equals(method) && path.startsWith("/claim")) {
                    mOwner = text.trim();
                    status("Sayfa bağlandı");
                } else if ("POST".equals(method)) {
                    int bar = text.indexOf('|');
                    String id = bar < 0 ? "" : text.substring(0, bar);
                    if (id.equals(mOwner)) draw(text.substring(bar + 1), grid); else code = 409;
                }
                String reply = "HTTP/1.1 " + (code == 200 ? "200 OK" : "409 Conflict")
                        + "\r\nAccess-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: *\r\n"
                        + "Access-Control-Allow-Private-Network: true\r\n"
                        + "Content-Length: 2\r\nConnection: keep-alive\r\n\r\nok";
                out.write(reply.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "connection closed: " + e);
        }
    }

    private void draw(String csv, int[] grid) {
        String[] parts = csv.split(",");
        if (parts.length != GRID) return;
        for (int i = 0; i < GRID; i++) {
            grid[i] = Math.max(0, Math.min(4095, Integer.parseInt(parts[i].trim())));
        }
        IGlyphService g = mGlyph;
        if (g == null) return;
        try {
            g.setAppMatrixColors(grid);
        } catch (Exception e) {
            Log.w(TAG, "draw", e);
        }
        if (++mFrames % 25 == 1) status("Canlı görüntü geliyor");
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return b.toString().trim();
            b.append((char) c);
        }
        return b.length() == 0 ? null : b.toString();
    }
}
