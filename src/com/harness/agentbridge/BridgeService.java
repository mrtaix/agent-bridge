package com.harness.agentbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a hidden WebView, exposes its Chrome DevTools socket on 127.0.0.1:9222,
 * and executes the bundled agent-browser CLI on 127.0.0.1:9224 behind a token.
 *
 * Both halves must run under this app's UID: SELinux forbids the adb shell domain
 * from creating unix sockets, which agent-browser needs for its daemon.
 */
public class BridgeService extends Service {

    public static final String TAG = "AgentBridge";
    public static final int CDP_PORT = 9222;
    public static final int CMD_PORT = 9224;

    private static volatile String status = "starting";
    private static volatile String token = "";

    public static String status() { return status; }
    public static String token() { return token; }

    private WebView webView;
    private Thread cdpThread;
    private Thread cmdThread;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNow();
        token = loadOrCreateToken();
        Log.i(TAG, "AB-TOKEN: " + token);
        publishToken();
        initWebView();
        startCdpProxy();
        startCommandServer();
        copyAgentBrowser();
        autoConnect();
        startWatchdog();
    }

    /** agent-browser keeps its target in the daemon; reconnect after every process restart. */
    private void autoConnect() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        for (int attempt = 1; attempt <= 6; attempt++) {
                            try {
                                File bin = new File(getFilesDir(), "agent-browser");
                                if (!bin.exists()) { Thread.sleep(1500); continue; }
                                List<String> a = new ArrayList<>();
                                a.add("connect");
                                a.add(String.valueOf(CDP_PORT));
                                JSONObject r = execAgentBrowser(a);
                                Log.i(TAG, "auto connect attempt " + attempt + " code " + r.optInt("code")
                                        + " out " + r.optString("out"));
                                if (r.optInt("code") == 0) { status = "connected :" + CDP_PORT; return; }
                            } catch (Throwable t) {
                                Log.w(TAG, "auto connect failed", t);
                            }
                            try { Thread.sleep(2000); } catch (InterruptedException ignored) { return; }
                        }
                    }
                }).start();
            }
        }, 3000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundNow() {
        String channelId = "bridge";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channelId, "Agent Bridge",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("Agent Bridge")
                .setContentText("DevTools on 127.0.0.1:" + CDP_PORT)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    /**
     * Writes the token somewhere the agent can read without adb/logcat.
     * targetSdk 28 keeps legacy shared-storage access, so this lands in the workspace.
     */
    private void publishToken() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String[] targets = {
                        "/storage/emulated/0/HARNESS/.harness/bridge-token.txt",
                        android.os.Environment.getExternalStorageDirectory()
                                + "/.harness/bridge-token.txt",
                };
                for (String path : targets) {
                    try {
                        File f = new File(path);
                        File dir = f.getParentFile();
                        if (dir != null && !dir.exists()) dir.mkdirs();
                        FileOutputStream out = new FileOutputStream(f);
                        out.write((token + "\n").getBytes("UTF-8"));
                        out.close();
                        Log.i(TAG, "token published to " + path);
                        return;
                    } catch (Throwable t) {
                        Log.w(TAG, "token publish failed for " + path + ": " + t);
                    }
                }
            }
        }, "token-publish").start();
    }

    private String loadOrCreateToken() {
        SharedPreferences sp = getSharedPreferences("bridge", Context.MODE_PRIVATE);
        String t = sp.getString("token", null);
        if (t == null || t.length() < 16) {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte x : raw) sb.append(String.format("%02x", x));
            t = sb.toString();
            sp.edit().putString("token", t).apply();
        }
        return t;
    }

    private void initWebView() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 19) {
                        WebView.setWebContentsDebuggingEnabled(true);
                    }
                    webView = new WebView(getApplicationContext());
                    WebSettings s = webView.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    s.setDatabaseEnabled(true);
                    s.setUseWideViewPort(true);
                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            layoutView();
                        }
                    });
                    webView.loadUrl("about:blank");
                    layoutView();
                    status = "webview ready";
                    Log.i(TAG, "webview ready, pid " + android.os.Process.myPid());
                } catch (Throwable t) {
                    status = "webview failed: " + t;
                    Log.e(TAG, "webview init failed", t);
                }
            }
        });
    }

    /** The WebView has no window here, so give it a viewport by hand. */
    private void layoutView() {
        if (webView == null) return;
        try {
            int w = 1080, h = 1920;
            webView.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
            webView.layout(0, 0, w, h);
        } catch (Throwable t) {
            Log.w(TAG, "layout failed", t);
        }
    }

    /** Cheap liveness probe straight at the WebView devtools socket. */
    private boolean cdpAlive() {
        LocalSocket ls = new LocalSocket();
        try {
            ls.connect(new LocalSocketAddress(devtoolsSocketName(),
                    LocalSocketAddress.Namespace.ABSTRACT));
            ls.setSoTimeout(3000);
            ls.getOutputStream().write(("GET /json/version HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            ls.getOutputStream().flush();
            byte[] buf = new byte[512];
            int n = ls.getInputStream().read(buf);
            return n > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { ls.close(); } catch (Throwable ignored) {}
        }
    }

    private void resetEverything() {
        Log.i(TAG, "resetting bridge");
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c",
                    "pkill -f agent-browser 2>/dev/null"});
        } catch (Throwable ignored) {}
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    if (webView != null) { webView.destroy(); webView = null; }
                } catch (Throwable t) {
                    Log.w(TAG, "webview destroy failed", t);
                }
                initWebView();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { autoConnect(); }
                }, 4000);
            }
        });
    }

    private void startWatchdog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int misses = 0;
                while (true) {
                    try {
                        Thread.sleep(30000);
                        if (webView == null) { continue; }
                        if (cdpAlive()) {
                            misses = 0;
                            if (!status.startsWith("connected")) status = "connected :" + CDP_PORT;
                        } else {
                            misses++;
                            Log.w(TAG, "watchdog: cdp not answering (" + misses + ")");
                            if (misses >= 3) { misses = 0; resetEverything(); }
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        Log.w(TAG, "watchdog error", t);
                    }
                }
            }
        }, "watchdog").start();
    }

    private String devtoolsSocketName() {
        return "webview_devtools_remote_" + android.os.Process.myPid();
    }

    private void startCdpProxy() {
        cdpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket server = null;
                try {
                    server = new ServerSocket(CDP_PORT, 32, InetAddress.getByName("127.0.0.1"));
                    Log.i(TAG, "CDP proxy listening on " + CDP_PORT);
                    status = "cdp " + CDP_PORT;
                    while (true) {
                        final Socket client = server.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() { bridge(client); }
                        }).start();
                    }
                } catch (Throwable t) {
                    status = "cdp proxy failed: " + t;
                    Log.e(TAG, "cdp proxy failed", t);
                } finally {
                    if (server != null) try { server.close(); } catch (IOException ignored) {}
                }
            }
        }, "cdp-proxy");
        cdpThread.start();
    }

    private void bridge(Socket client) {
        LocalSocket devtools = new LocalSocket();
        try {
            devtools.connect(new LocalSocketAddress(devtoolsSocketName(),
                    LocalSocketAddress.Namespace.ABSTRACT));
            final LocalSocket dev = devtools;
            final Socket cli = client;
            Thread up = new Thread(new Runnable() {
                @Override public void run() { pump(cli, dev); }
            });
            Thread down = new Thread(new Runnable() {
                @Override public void run() { pump(dev, cli); }
            });
            up.start();
            down.start();
        } catch (Throwable t) {
            Log.w(TAG, "devtools connect failed", t);
            try { client.close(); } catch (IOException ignored) {}
            try { devtools.close(); } catch (IOException ignored) {}
        }
    }

    private static void pump(Object from, Object to) {
        try {
            InputStream in = (from instanceof Socket)
                    ? ((Socket) from).getInputStream()
                    : ((LocalSocket) from).getInputStream();
            OutputStream out = (to instanceof Socket)
                    ? ((Socket) to).getOutputStream()
                    : ((LocalSocket) to).getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (from instanceof Socket) ((Socket) from).close(); else ((LocalSocket) from).close(); } catch (Throwable ignored) {}
            try { if (to instanceof Socket) ((Socket) to).close(); else ((LocalSocket) to).close(); } catch (Throwable ignored) {}
        }
    }

    private void startCommandServer() {
        cmdThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket server = null;
                try {
                    server = new ServerSocket(CMD_PORT, 16, InetAddress.getByName("127.0.0.1"));
                    Log.i(TAG, "command server listening on " + CMD_PORT);
                    while (true) {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handleCommand(s); }
                        }).start();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "command server failed", t);
                } finally {
                    if (server != null) try { server.close(); } catch (IOException ignored) {}
                }
            }
        }, "command-server");
        cmdThread.start();
    }

    private void handleCommand(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = in.readLine();
            JSONObject resp = new JSONObject();
            if (line == null) { socket.close(); return; }
            JSONObject req = new JSONObject(line);
            if (!token.equals(req.optString("token"))) {
                resp.put("code", 403);
                resp.put("out", "");
                resp.put("err", "bad token");
            } else {
                List<String> args = new ArrayList<>();
                JSONArray arr = req.optJSONArray("args");
                if (arr != null) for (int i = 0; i < arr.length(); i++) args.add(arr.getString(i));
                if (!args.isEmpty() && "__reset".equals(args.get(0))) {
                    resetEverything();
                    resp.put("code", 0);
                    resp.put("out", "reset requested");
                    resp.put("err", "");
                } else if (!args.isEmpty() && "__ping".equals(args.get(0))) {
                    resp.put("code", cdpAlive() ? 0 : 1);
                    resp.put("out", status);
                    resp.put("err", "");
                } else {
                    JSONObject r = execAgentBrowser(args);
                    resp.put("code", r.optInt("code", -1));
                    resp.put("out", r.optString("out"));
                    resp.put("err", r.optString("err"));
                    String err = r.optString("err", "");
                    boolean transportTrouble = r.optInt("code", 0) != 0
                            && (err.contains("daemon") || err.contains("Failed to read")
                                || err.contains("timed out") || err.contains("os error"));
                    if (transportTrouble) {
                        Log.w(TAG, "transport trouble, scheduling reset: " + err);
                        resetAgentBrowser();
                    }
                }
            }
            OutputStream os = socket.getOutputStream();
            os.write((resp.toString() + "\n").getBytes("UTF-8"));
            os.flush();
            socket.close();
        } catch (Throwable t) {
            Log.w(TAG, "command handling failed", t);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private final Object execLock = new Object();

    private JSONObject execAgentBrowser(List<String> args) throws Exception {
        JSONObject r = new JSONObject();
        final Runnable abortNav = new Runnable() {
            @Override public void run() {
                Log.w(TAG, "navigation still running after 45s, stopping load");
                final WebView wv = webView;
                if (wv != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { wv.stopLoading(); }
                });
            }
        };
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(abortNav, 45000);
        try {
            synchronized (execLock) {
                return execAgentBrowserInner(args);
            }
        } finally {
            handler.removeCallbacks(abortNav);
        }
    }

    private JSONObject execAgentBrowserInner(List<String> args) throws Exception {
        JSONObject r = new JSONObject();
        File home = new File(getFilesDir(), "home");
        if (!home.exists() && !home.mkdirs()) {
            r.put("code", -2);
            r.put("out", "");
            r.put("err", "cannot create home dir");
            return r;
        }
        File bin = new File(getFilesDir(), "agent-browser");
        if (!bin.exists()) {
            r.put("code", -3);
            r.put("out", "");
            r.put("err", "agent-browser binary not installed yet");
            return r;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home);
        Map<String, String> env = pb.environment();
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", home.getAbsolutePath());
        env.put("XDG_RUNTIME_DIR", home.getAbsolutePath());
        env.put("AGENT_BROWSER_SOCKET_DIR", new File(home, "sock").getAbsolutePath());
        String path = env.get("PATH");
        env.put("PATH", (path == null || path.isEmpty()) ? "/system/bin:/system/xbin" : path);
        pb.redirectErrorStream(false);

        java.lang.Process p = pb.start();
        p.getOutputStream().close();
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        Thread t1 = readerThread(p.getInputStream(), out);
        Thread t2 = readerThread(p.getErrorStream(), err);
        boolean finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            resetAgentBrowser();
            r.put("code", 124);
            r.put("out", out.toString());
            r.put("err", "timeout after 120s\n" + err);
            return r;
        }
        t1.join(2000);
        t2.join(2000);
        r.put("code", p.exitValue());
        r.put("out", out.toString());
        r.put("err", err.toString());
        return r;
    }

    /** A wedged page can leave the agent-browser daemon waiting on CDP forever. */
    private void resetAgentBrowser() {
        synchronized (execLock) {
            final WebView wv = webView;
            if (wv != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { try { wv.stopLoading(); } catch (Throwable ignored) {} }
            });
            try {
                Runtime.getRuntime().exec(new String[]{
                        "/system/bin/sh", "-c", "pkill -f agent-browser 2>/dev/null"});
                Log.i(TAG, "agent-browser processes killed after timeout");
            } catch (Throwable t) {
                Log.w(TAG, "reset failed", t);
            }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            List<String> a = new ArrayList<>();
            a.add("connect");
            a.add(String.valueOf(CDP_PORT));
            for (int i = 1; i <= 3; i++) {
                try {
                    JSONObject r = execAgentBrowserInner(a);
                    Log.i(TAG, "reconnect " + i + " code " + r.optInt("code"));
                    if (r.optInt("code") == 0) { status = "connected :" + CDP_PORT; return; }
                } catch (Throwable t) {
                    Log.w(TAG, "reconnect failed", t);
                }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static Thread readerThread(final InputStream in, final StringBuilder sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) sink.append(new String(buf, 0, n, "UTF-8"));
                } catch (Throwable ignored) {}
            }
        });
        t.start();
        return t;
    }

    private void copyAgentBrowser() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File target = new File(getFilesDir(), "agent-browser");
                try {
                    if (target.exists() && target.length() > 1_000_000) {
                        target.setExecutable(true);
                        Log.i(TAG, "agent-browser present: " + target.length());
                        return;
                    }
                    InputStream in = getAssets().open("agent-browser");
                    FileOutputStream out = new FileOutputStream(target);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    target.setExecutable(true, false);
                    Log.i(TAG, "agent-browser installed: " + target.length());
                } catch (Throwable t) {
                    Log.e(TAG, "copy agent-browser failed", t);
                }
            }
        }, "copy-bin").start();
    }

    @Override
    public void onDestroy() {
        if (cdpThread != null) cdpThread.interrupt();
        if (cmdThread != null) cmdThread.interrupt();
        super.onDestroy();
    }
}
