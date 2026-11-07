package com.glasshome;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Fire OS sends Home to com.amazon.tv.launcher whatever else is installed, and silently drops any accessibility
 * service ADB tries to switch on. It does let ADB grant READ_LOGS, and ActivityManager logs every Home press as
 * "START u0 {... cat=[android.intent.category.HOME] ... cmp=com.amazon.tv.launcher/...}". This service follows
 * the log and puts HomeActivity on top each time.
 *
 * It also follows the AirPlay receiver (io.github.jqssun.airplay): when the last sender disconnects, or a cast video
 * ends, and the receiver's own screen is what is showing, Glass Home comes forward instead of leaving the
 * receiver's "Waiting for connection" page on screen. Nothing is done if something else is showing by then.
 */
public class HomeWatcher extends Service {
    private static long pausedUntil;
    private static long lastLaunch;
    private static final String AIRPLAY = "io.github.jqssun.airplay";
    private static final long VIDEO_GRACE_MS = 1500;   // a stop followed at once by a play is the next video, not the end

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread reader;
    private String lastStartPackage = "";   // whose screen the last activity start belonged to
    private int senders;                    // AirPlay senders connected, counted from the log
    private volatile long airplayActiveAt;  // last time a sender connected or a video started

    /** Leave Amazon's home alone for a while, for the "Fire TV home" button. */
    static void pause(long millis) {
        pausedUntil = SystemClock.elapsedRealtime() + millis;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (reader == null) {
            // foreground, so the low-memory killer on a 1 GB stick leaves the watcher running
            startForeground(1, new Notification.Builder(this)
                    .setSmallIcon(R.drawable.icon)
                    .setContentTitle(getString(R.string.app_name))
                    .setPriority(Notification.PRIORITY_MIN)
                    .build());
            reader = new Thread(this::follow, "home-watcher");
            reader.setDaemon(true);
            reader.start();
        }
        // AirPods: auto-connect, the connected banner, ear detection (does nothing if the stick has no Bluetooth)
        try {
            AirPodsMonitor.get(this).start();
        } catch (RuntimeException e) {
            android.util.Log.w(AirPodsMonitor.TAG, "AirPods support not started: " + e);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AirPodsMonitor.get(this).stop();
        super.onDestroy();
    }

    private void follow() {
        catchUp();
        while (true) {
            Process logcat = null;
            try {
                // -T 1 starts at the newest line, so Home presses already in the buffer are not replayed
                logcat = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "ActivityManager:I",
                        "AirPlayService:I", "AirPlayVideoPlayer:I", "AirPlay:I", "*:S")
                        .redirectErrorStream(true).start();
                BufferedReader lines = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
                for (String line; (line = lines.readLine()) != null; ) {
                    if (isAmazonHome(line)) {
                        amazonHomeStarted();
                    }
                    noteStart(line);
                    noteAirPlay(line);
                }
            } catch (Exception e) {
                // logcat ended or could not start; try again below
            } finally {
                if (logcat != null) {
                    logcat.destroy();
                }
            }
            SystemClock.sleep(2000);
        }
    }

    /**
     * At boot, or when the system restarts this service, Amazon's home may already be on screen. Only take over
     * if it was the last thing started, so a video someone opened in the meantime is left alone.
     */
    private void catchUp() {
        String last = null;
        try {
            Process dump = new ProcessBuilder("logcat", "-d", "-v", "brief", "ActivityManager:I", "*:S")
                    .redirectErrorStream(true).start();
            BufferedReader lines = new BufferedReader(new InputStreamReader(dump.getInputStream()));
            for (String line; (line = lines.readLine()) != null; ) {
                if (line.contains("START u0")) {
                    last = line;
                }
            }
            dump.waitFor();
        } catch (Exception e) {
            return;
        }
        if (last != null && isAmazonHome(last)) {
            amazonHomeStarted();
        }
    }

    /** "START u0 {... cmp=pkg/.Activity ...}": remember whose screen just came up. */
    private void noteStart(String line) {
        int start = line.indexOf("START u0");
        int cmp = start < 0 ? -1 : line.indexOf("cmp=", start);
        if (cmp >= 0) {
            int slash = line.indexOf('/', cmp);
            if (slash > cmp) {
                lastStartPackage = line.substring(cmp + 4, slash);
            }
        }
    }

    private void noteAirPlay(String line) {
        if (line.contains("Sender connected")) {
            senders++;
            airplayActiveAt = SystemClock.elapsedRealtime();
        } else if (line.contains("Sender disconnected")) {
            senders = Math.max(0, senders - 1);
            if (senders == 0) {
                handler.post(this::leaveAirPlay);
            }
        } else if (line.contains("AirPlay Video play")) {
            airplayActiveAt = SystemClock.elapsedRealtime();
        } else if (line.contains("AirPlay Video stopped")) {
            final long stoppedAt = SystemClock.elapsedRealtime();
            handler.postDelayed(() -> {
                if (airplayActiveAt < stoppedAt && senders == 0) {
                    leaveAirPlay();
                }
            }, VIDEO_GRACE_MS);
        }
    }

    /** A session ended: if the receiver's page is what is showing, go back to Glass Home (no Home press, so no delay). */
    private void leaveAirPlay() {
        long now = SystemClock.elapsedRealtime();
        if (!AIRPLAY.equals(lastStartPackage) || now - lastLaunch < 1500) {
            return;
        }
        lastLaunch = now;
        showHome();
    }

    private static boolean isAmazonHome(String line) {
        return line.contains("android.intent.category.HOME")
                && line.contains("cmp=" + HomeActivity.AMAZON_HOME + "/");
    }

    private void amazonHomeStarted() {
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil || now - lastLaunch < 1500) {
            return;
        }
        lastLaunch = now;
        // Android holds back activity starts from background apps for about 5 seconds after a Home press, so Glass
        // Home appears about 5 s late. Tried and ruled out on this stick: an alarm or PendingIntent (alarms wait 5 s
        // too) and a loopback ADB client (adbd drops connections from apps on the stick itself).
        showHome();
    }

    private void showHome() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
