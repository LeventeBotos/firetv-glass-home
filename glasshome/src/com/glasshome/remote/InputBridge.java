package com.glasshome.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.SecureRandom;

/**
 * Gets key presses into the stick. Apps may not inject keys into other apps, but the ADB shell user may, so this
 * keeps a small helper ({@link Injector}) running as that user and sends it one-line commands over a loopback socket.
 *
 * The helper is started through the stick's own ADB (127.0.0.1:5555, the same one a computer uses, authorised once by
 * ticking the prompt on the TV) and then the ADB connection is closed again, because Fire OS's adbd serves one client at
 * a time and a computer must still be able to connect. The helper's port and secret token are remembered, so after the
 * app restarts it just reconnects; ADB is only needed again after a reboot, a reinstall or if the helper died.
 *
 * Everything here runs on one background thread; the key methods never block on the stick.
 */
public final class InputBridge implements KeySink {
    /** What differs between running on the stick and running on a computer. */
    public interface Env {
        String apkPath();

        /** Changes when the app is reinstalled, so a helper from the old version is replaced. */
        long apkStamp();

        KeyPair adbKey();

        /** Addresses to reach the stick's own adbd on, in the order to try. */
        String[] adbHosts();

        int adbPort();
    }

    public static final String STARTING = "starting", NEEDS_APPROVAL = "waiting for you to allow it on the TV",
            READY = "ready", NO_HELPER = "key helper not running (run glasshome/remote/helper.py from a computer)";

    /** How often the app tries the stick's own ADB for a helper. Fire OS 6 refuses it, so this is rare on purpose. */
    private static final long SELF_ADB_EVERY_MS = 10 * 60 * 1000;

    private final PairingStore.Storage storage;
    private final Env env;
    private final CompanionServer.Log log;
    private final SecureRandom random = new SecureRandom();
    private volatile String status = STARTING;
    private volatile boolean stopped;
    private Thread worker;
    private Socket socket;
    private OutputStream out;
    private final Object lock = new Object();

    public InputBridge(PairingStore.Storage storage, Env env, CompanionServer.Log log) {
        this.storage = storage;
        this.env = env;
        this.log = log;
    }

    public String status() {
        return status;
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                maintain();
            }
        }, "input-bridge");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        stopped = true;
        close();
    }

    // ---- KeySink

    @Override
    public void key(int keyCode) {
        send("key " + keyCode);
    }

    @Override
    public void down(int keyCode) {
        send("down " + keyCode);
    }

    @Override
    public void up(int keyCode) {
        send("up " + keyCode);
    }

    private void send(String line) {
        OutputStream o;
        synchronized (lock) {
            o = out;
        }
        if (o == null) {
            log.d("dropped " + line + " (the key helper is not up: " + status + ")");
            return;
        }
        try {
            synchronized (lock) {
                o.write((line + "\n").getBytes("UTF-8"));
                o.flush();
            }
        } catch (IOException e) {
            log.d("lost the key helper: " + e);
            close();
        }
    }

    // ---- keeping the helper running

    private void maintain() {
        long nextSelfAdb = 0;
        while (!stopped) {
            try {
                connectOrLaunch(System.currentTimeMillis() >= nextSelfAdb);
                waitUntilBroken();
                log.d("the key helper connection ended");
            } catch (Exception e) {
                if (!NEEDS_APPROVAL.equals(status)) {
                    status = NO_HELPER;
                }
                nextSelfAdb = System.currentTimeMillis() + SELF_ADB_EVERY_MS;
                log.d("key helper: " + e.getMessage());
            }
            close();
            waitForWake(NO_HELPER.equals(status) ? 15000 : 2000);
        }
    }

    /** A computer has just started the helper and handed over its port and token: look again now. */
    public void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    private final Object wakeLock = new Object();

    private void waitForWake(long ms) {
        synchronized (wakeLock) {
            try {
                wakeLock.wait(ms);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void connectOrLaunch(boolean mayLaunch) throws Exception {
        String port = storage.get("inj_port"), token = storage.get("inj_token");
        if (port != null && token != null && tryConnect(Integer.parseInt(port), token)) {
            status = READY;
            log.d("connected to the key helper on port " + port);
            return;
        }
        if (!mayLaunch) {
            throw new IOException("no key helper yet");
        }
        status = STARTING;
        launch();
    }

    private void launch() throws Exception {
        int port = 30000 + random.nextInt(30000);
        byte[] raw = new byte[12];
        random.nextBytes(raw);
        String token = PairingStore.hex(raw);

        AdbClient adb = null;
        IOException last = null;
        for (String host : env.adbHosts()) {
            try {
                adb = new AdbClient(host, env.adbPort(), 5000);
                adb.authenticate(env.adbKey(), "glasshome@nappali", 10 * 60 * 1000, new Runnable() {
                    @Override
                    public void run() {
                        status = NEEDS_APPROVAL;
                        log.d("waiting for \"Allow USB debugging?\" to be accepted on the TV");
                    }
                });
                log.d("connected to the stick's adbd at " + host);
                last = null;
                break;
            } catch (IOException e) {
                log.d("adbd at " + host + ": " + e.getMessage());
                last = e;
                if (adb != null) {
                    adb.close();
                    adb = null;
                }
            }
        }
        if (adb == null) {
            throw last != null ? last : new IOException("no address to reach adbd on");
        }
        try {
            status = STARTING;
            // The shell reads the token from its terminal, so it is never on a command line, and hands it on through a
            // pipe that is still there once this ADB session is over. setsid detaches the helper from the session.
            AdbClient.Shell sh = adb.shell("read T; echo \"$T\" | CLASSPATH=" + env.apkPath()
                    + " setsid app_process /system/bin com.glasshome.remote.Injector " + port
                    + " >/data/local/tmp/glassinj.out 2>&1 & sleep 6");
            sh.write(token + "\n");
            boolean up = false;
            for (int i = 0; i < 40 && !up && !stopped; i++) {
                sleep(500);
                up = tryConnect(port, token);
            }
            if (!up) {
                throw new IOException("the key helper did not start (see /data/local/tmp/glassinj.out on the stick)");
            }
            sleep(1500);   // let the shell and the helper settle before the session ends
        } finally {
            adb.close();
        }
        storage.put("inj_port", Integer.toString(port));
        storage.put("inj_token", token);
        storage.put("inj_stamp", String.valueOf(env.apkStamp()));
        status = READY;
        log.d("key helper started on port " + port);
    }

    private boolean tryConnect(int port, String token) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);
            OutputStream o = s.getOutputStream();
            o.write((token + "\n").getBytes("UTF-8"));
            o.flush();
            byte[] buf = new byte[16];
            int n = s.getInputStream().read(buf);
            if (n <= 0 || !new String(buf, 0, n, "UTF-8").startsWith("ready")) {
                s.close();
                return false;
            }
            s.setSoTimeout(0);
            synchronized (lock) {
                socket = s;
                out = o;
            }
            return true;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            return false;
        }
    }

    private void waitUntilBroken() throws IOException {
        InputStream in;
        synchronized (lock) {
            in = socket == null ? null : socket.getInputStream();
        }
        if (in == null) {
            return;
        }
        while (!stopped && in.read() >= 0) {
            // the helper sends nothing after "ready"; reading just notices when it goes away
        }
    }

    private void close() {
        synchronized (lock) {
            out = null;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
                socket = null;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
