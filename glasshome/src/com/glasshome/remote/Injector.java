package com.glasshome.remote;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * Presses keys on the stick without starting a process per key. `input keyevent` spends about 1.3 s of every call
 * starting a JVM on a Fire TV Stick 4K. This class is started once by {@link InputBridge}, through the stick's own ADB
 * (the ADB shell user is allowed to inject input; an app is not), detached so it outlives that ADB session, and then
 * takes one-line commands over a loopback socket:
 *
 *     key 22     press and release        down 23 / up 23   press / release, for long presses
 *     quit       stop
 *
 * Only this device can reach the socket, and only a client that knows the token may use it: the token arrives on this
 * process's standard input (never on its command line, which other apps can read) and is the first line a client sends.
 * It exits when nothing has been connected for thirty minutes, so a leftover from an older install does not linger.
 *
 *     CLASSPATH=<apk> app_process /system/bin com.glasshome.remote.Injector <port>        (token on stdin)
 */
public final class Injector {
    private static final long IDLE_MS = 30 * 60 * 1000;

    private static InputManager input;
    private static Method inject;
    private static final Map<Integer, Long> downTimes = new HashMap<>();
    private static int clients;
    private static long idleSince = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        final int port = Integer.parseInt(args[0]);
        final String token = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (token == null || token.isEmpty()) {
            return;
        }

        input = (InputManager) InputManager.class.getMethod("getInstance").invoke(null);
        inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);

        final ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        System.out.println("injector listening on " + port);
        System.out.flush();

        Thread watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(60 * 1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (Injector.class) {
                        if (clients == 0 && System.currentTimeMillis() - idleSince > IDLE_MS) {
                            System.exit(0);
                        }
                    }
                }
            }
        }, "idle-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        while (true) {
            final Socket client = server.accept();
            client.setTcpNoDelay(true);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(client, token);
                }
            }, "client").start();
        }
    }

    private static void serve(Socket client, String token) {
        synchronized (Injector.class) {
            clients++;
        }
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            if (!token.equals(in.readLine())) {
                return;
            }
            OutputStream out = c.getOutputStream();
            out.write("ready\n".getBytes());
            out.flush();
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.split(" ");
                if (p[0].equals("quit")) {
                    System.exit(0);
                }
                if (p.length < 2) {
                    continue;
                }
                try {
                    int code = Integer.parseInt(p[1]);
                    switch (p[0]) {
                        case "key":
                            send(KeyEvent.ACTION_DOWN, code);
                            send(KeyEvent.ACTION_UP, code);
                            break;
                        case "down":
                            send(KeyEvent.ACTION_DOWN, code);
                            break;
                        case "up":
                            send(KeyEvent.ACTION_UP, code);
                            break;
                        default:
                            break;
                    }
                } catch (Exception bad) {
                    // one bad command must not end the session
                }
            }
        } catch (IOException e) {
            // the sender went away; the next connection starts clean
        } finally {
            synchronized (Injector.class) {
                clients--;
                idleSince = System.currentTimeMillis();
            }
        }
    }

    private static synchronized void send(int action, int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        Long down = downTimes.get(code);
        if (action == KeyEvent.ACTION_DOWN) {
            down = now;
            downTimes.put(code, now);
        } else if (down == null) {
            down = now;
        }
        KeyEvent e = new KeyEvent(down, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        inject.invoke(input, e, 0);   // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
    }
}
