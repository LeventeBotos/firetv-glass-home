package com.glasshome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Makes headphones behave like they do on an Apple TV. Lives in HomeWatcher's foreground service, so it keeps working
 * while any app is in front.
 *
 *  - Sees AirPods through their Bluetooth LE adverts (AirPodsAdvert): case lid, ears, battery.
 *  - Opening the case or putting a bud in connects the paired AirPods by itself (A2DP connect, a hidden method
 *    reached by reflection: it needs only BLUETOOTH_ADMIN on Android 7).
 *  - Shows the "Connected" / "Disconnected" / "Low battery" banner (AirPodsBanner).
 *  - Takes both buds out: playback pauses; a bud back in: it resumes (only if this paused it).
 *  - First time: pairing-mode AirPods near the TV raise EVENT_SETUP so Home can offer to pair them.
 *
 * What it cannot do on a stock stick: Noise Control, spatial audio, Share Audio (see the README).
 */
final class AirPodsMonitor {
    static final String TAG = "GlassAirPods";
    static final int EVENT_STATE = 0, EVENT_SETUP = 1, EVENT_PAIR_FAILED = 2;

    interface Listener {
        /** Always on the main thread. */
        void onAirPods(int event);
    }

    private static final int APPLE = 0x004C;
    private static final int RSSI_MIN = -72;            // farther than about 2 metres is somebody else's AirPods
    private static final long FRESH_MS = 15000;
    private static final long ATTEMPT_GAP_MS = 8000;
    private static final long RESTART_SCAN_MS = 25 * 60 * 1000L;   // Android 7 demotes scans older than 30 minutes
    private static final long PAIR_TIMEOUT_MS = 25000;

    private static AirPodsMonitor instance;

    static synchronized AirPodsMonitor get(Context context) {
        if (instance == null) {
            instance = new AirPodsMonitor(context.getApplicationContext());
        }
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LayoutStore store;
    private final Sounds sounds;
    private final AudioManager audio;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    private BluetoothA2dp a2dp;
    private BluetoothLeScanner scanner;
    private boolean started;

    // Every set of AirPods in range, by Bluetooth address: yours, a neighbour's, a second pair of your own. Nothing is
    // thrown away for being weaker, because another Apple device that is closer or chattier must not hide yours.
    private static final class Seen {
        AirPodsAdvert a;
        long at;
        int rssi;
    }

    private final java.util.Map<String, Seen> seen = new java.util.HashMap<>();
    private BluetoothDevice output;         // the connected headphones, null = TV speakers
    private long lastAttempt;
    private long lastSetup;
    private boolean pausedByUs;
    private int lowNotified = 100;
    private boolean pairing;
    private boolean everSawAdvert;

    private AirPodsMonitor(Context context) {
        ctx = context;
        store = new LayoutStore(context);
        sounds = new Sounds(context, store);
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---- state for the UI ---------------------------------------------------------------------------------------

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    boolean available() {
        return adapter != null;
    }

    boolean connected() {
        return output != null;
    }

    String outputName() {
        return output == null ? null : nameOf(output);
    }

    /**
     * The advert of the AirPods that are most likely yours: recent, a model that matches a paired set, and the
     * strongest of those. Null if there is none.
     */
    AirPodsAdvert fresh() {
        long now = SystemClock.elapsedRealtime();
        Seen best = null;
        for (Seen e : seen.values()) {
            if (now - e.at < FRESH_MS && !e.a.pairing && matchesBonded(e.a) && (best == null || e.rssi > best.rssi)) {
                best = e;
            }
        }
        return best == null ? null : best.a;
    }

    /** True if a paired set could be this model ("AirPods" means the plain ones, not Pro or Max). Also true if none is paired. */
    private boolean matchesBonded(AirPodsAdvert a) {
        boolean any = false;
        for (BluetoothDevice d : audioDevices()) {
            if (!looksLikeAirPods(d)) {
                continue;
            }
            any = true;
            String n = d.getName();
            boolean plain = a.name.equals("AirPods");
            if (plain ? !(n.contains("Pro") || n.contains("Max")) : n.contains(a.name)) {
                return true;
            }
        }
        return !any;
    }

    /** True if scanning cannot return results because Location is off (Android 6-11 need it for BLE scans). */
    boolean locationOff() {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE, 3)
                    == Settings.Secure.LOCATION_MODE_OFF;
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean sawAdvert() {
        return everSawAdvert;
    }

    /** Paired audio devices (headphones, speakers, earbuds). */
    List<BluetoothDevice> audioDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        if (adapter == null || !adapter.isEnabled()) {
            return out;
        }
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            BluetoothClass c = d.getBluetoothClass();
            String n = d.getName();
            if (n != null && n.endsWith("Find My")) {
                continue;   // Find My network entry for the same AirPods: not something audio can be sent to
            }
            if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                out.add(d);
            }
        }
        return out;
    }

    static String nameOf(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    boolean isOutput(BluetoothDevice d) {
        return output != null && output.getAddress().equals(d.getAddress());
    }

    // ---- life cycle ----------------------------------------------------------------------------------------------

    void start() {
        if (started || adapter == null) {
            return;
        }
        started = true;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        ctx.registerReceiver(receiver, f);
        adapter.getProfileProxy(ctx, new Proxy(this), BluetoothProfile.A2DP);
        startScan();
    }

    // Nested classes are static with an explicit owner: javac 21+ writes MethodParameters for the hidden outer-instance
    // argument of inner and anonymous classes, and the d8 that ships with older build-tools crashes on it.
    private static final class Proxy implements BluetoothProfile.ServiceListener {
        private final AirPodsMonitor m;

        Proxy(AirPodsMonitor m) {
            this.m = m;
        }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            m.a2dp = (BluetoothA2dp) proxy;
            List<BluetoothDevice> now = m.a2dp.getConnectedDevices();
            m.output = now.isEmpty() ? null : now.get(0);
            m.notifyUi(EVENT_STATE);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            m.a2dp = null;
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        stopScan();
        try {
            ctx.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        if (a2dp != null) {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
    }

    private void startScan() {
        stopScan();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        try {
            ScanFilter apple = new ScanFilter.Builder()
                    .setManufacturerData(APPLE, new byte[]{0x07}, new byte[]{(byte) 0xFF}).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(apple);
            scanner.startScan(filters, settings, scan);
            Log.i(TAG, "scanning for AirPods adverts" + (locationOff() ? " (Location is OFF: results may be empty)" : ""));
        } catch (RuntimeException e) {
            Log.w(TAG, "can't scan: " + e);
        }
        main.removeCallbacks(rescan);
        main.postDelayed(rescan, RESTART_SCAN_MS);
    }

    private void stopScan() {
        main.removeCallbacks(rescan);
        if (scanner != null) {
            try {
                scanner.stopScan(scan);
            } catch (RuntimeException ignored) {
                // Bluetooth went off under us
            }
            scanner = null;
        }
    }

    private final Runnable rescan = this::startScan;

    private final ScanCallback scan = new Scan(this);

    private static final class Scan extends ScanCallback {
        private final AirPodsMonitor m;

        Scan(AirPodsMonitor m) {
            this.m = m;
        }

        @Override
        public void onScanResult(int type, ScanResult r) {
            if (r.getScanRecord() == null) {
                return;
            }
            AirPodsAdvert a = AirPodsAdvert.parse(r.getScanRecord().getManufacturerSpecificData(APPLE));
            if (a != null) {
                final int rssi = r.getRssi();
                final String address = r.getDevice().getAddress();
                m.main.post(() -> m.onAdvert(address, a, rssi));
            }
        }

        @Override
        public void onScanFailed(int code) {
            Log.w(TAG, "scan failed: " + code);
        }
    }

    // ---- adverts -------------------------------------------------------------------------------------------------

    private void onAdvert(String address, AirPodsAdvert a, int rssi) {
        long now = SystemClock.elapsedRealtime();
        everSawAdvert = true;
        for (java.util.Iterator<Seen> it = seen.values().iterator(); it.hasNext(); ) {
            if (now - it.next().at > 60000) {
                it.remove();   // gone: a neighbour who walked off, a case that was shut
            }
        }
        if (rssi < RSSI_MIN) {
            seen.remove(address);
            return;
        }
        Seen e = seen.get(address);
        if (e == null) {
            e = new Seen();
            seen.put(address, e);
        }
        e.a = a;
        e.at = now;
        e.rssi = rssi;
        Log.i(TAG, a.name + " " + address + " rssi " + rssi + " L" + a.left + " R" + a.right + " case" + a.box + " lid"
                + (a.lidOpen ? "open" : "closed") + " ear " + a.inEarLeft + "/" + a.inEarRight
                + (a.pairing ? " PAIRING" : ""));

        if (a.pairing) {
            if (now - lastSetup > 30000 && !pairing && output == null && !hasBondedAirPods()) {
                lastSetup = now;
                notifyUi(EVENT_SETUP);
            }
            return;
        }
        // Any matching set opening its case counts, however many other Apple devices are talking: connecting goes by
        // the paired address, so if it was not yours the attempt just finds nothing there.
        if (output == null && store.autoConnect() && (a.lidOpen || a.inEar()) && matchesBonded(a)
                && now - lastAttempt > ATTEMPT_GAP_MS) {
            lastAttempt = now;
            BluetoothDevice target = pickBonded();
            if (target != null) {
                Log.i(TAG, "auto-connecting " + nameOf(target));
                connect(target);
            }
        }
        if (output != null) {
            // ears and battery come from the set that is yours, not from whichever advert arrived last
            AirPodsAdvert mine = fresh();
            if (mine != null) {
                earDetection(mine);
                lowBattery(mine);
            }
        }
        notifyUi(EVENT_STATE);
    }

    private final Runnable pauseNow = this::pauseIfStillOut;

    private boolean pausePending;

    private void pauseIfStillOut() {
        pausePending = false;
        if (output != null && store.earDetection() && audio.isMusicActive()) {
            pausedByUs = true;
            media(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
    }

    private void earDetection(AirPodsAdvert a) {
        if (!store.earDetection()) {
            return;
        }
        if (a.inEar()) {
            main.removeCallbacks(pauseNow);
            pausePending = false;
            if (pausedByUs) {
                pausedByUs = false;
                media(KeyEvent.KEYCODE_MEDIA_PLAY);
            }
        } else if (!pausedByUs && !pausePending) {
            // both out for a second. Not re-armed by every advert (they arrive many times a second), or it never fires
            pausePending = true;
            main.postDelayed(pauseNow, 1000);
        }
    }

    private void media(int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void lowBattery(AirPodsAdvert a) {
        int low = a.budsLow();
        if (low == AirPodsAdvert.UNKNOWN || a.budsCharging() || low > 25) {
            if (low > 25 || a.budsCharging()) {
                lowNotified = 100;
            }
            return;
        }
        int level = low <= 10 ? 10 : 20;
        if (level < lowNotified) {
            lowNotified = level;
            AirPodsBanner.Content c = new AirPodsBanner.Content();
            c.kind = AirPodsBanner.LOW;
            c.title = "Low Battery";
            c.subtitle = a.name + " · " + low + "%";
            c.buds = low;
            AirPodsBanner.show(ctx, c);
        }
    }

    // ---- connecting ----------------------------------------------------------------------------------------------

    private boolean hasBondedAirPods() {
        for (BluetoothDevice d : audioDevices()) {
            if (looksLikeAirPods(d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeAirPods(BluetoothDevice d) {
        String n = d.getName();
        return n != null && (n.contains("AirPods") || n.contains("Beats"));
    }

    /** The paired AirPods to connect: the ones last used, else the first AirPods/Beats paired. */
    private BluetoothDevice pickBonded() {
        String last = store.lastHeadphones();
        BluetoothDevice first = null;
        for (BluetoothDevice d : audioDevices()) {
            if (!looksLikeAirPods(d)) {
                continue;
            }
            if (d.getAddress().equals(last)) {
                return d;
            }
            if (first == null) {
                first = d;
            }
        }
        return first;
    }

    void connect(BluetoothDevice d) {
        invoke("connect", d);
    }

    void disconnect(BluetoothDevice d) {
        invoke("disconnect", d);
    }

    /** Back to the TV speakers. */
    void useTvSpeakers() {
        if (output != null) {
            disconnect(output);
        }
    }

    private void invoke(String method, BluetoothDevice d) {
        if (a2dp == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = BluetoothA2dp.class.getMethod(method, BluetoothDevice.class);
            m.invoke(a2dp, d);
        } catch (Exception e) {
            Log.w(TAG, method + " failed: " + e);
        }
    }

    /** First-time setup: find AirPods in pairing mode by classic discovery and bond with the first one found. */
    void pair() {
        if (adapter == null || pairing) {
            return;
        }
        pairing = true;
        adapter.cancelDiscovery();
        adapter.startDiscovery();
        main.postDelayed(pairTimeout, PAIR_TIMEOUT_MS);
    }

    private final Runnable pairTimeout = () -> {
        if (pairing) {
            pairing = false;
            adapter.cancelDiscovery();
            notifyUi(EVENT_PAIR_FAILED);
        }
    };

    private final BroadcastReceiver receiver = new Receiver(this);

    private static final class Receiver extends BroadcastReceiver {
        private final AirPodsMonitor m;

        Receiver(AirPodsMonitor m) {
            this.m = m;
        }

        @Override
        public void onReceive(Context c, Intent i) {
            m.received(i);
        }
    }

    private void received(Intent i) {
        String action = i.getAction();
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action) && d != null) {
            onA2dp(d, i.getIntExtra(BluetoothProfile.EXTRA_STATE, 0));
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
            if (s == BluetoothAdapter.STATE_ON) {
                startScan();
            } else if (s == BluetoothAdapter.STATE_OFF) {
                stopScan();
            }
        } else if (BluetoothDevice.ACTION_FOUND.equals(action) && pairing && d != null && looksLikeAirPods(d)) {
            pairing = false;
            main.removeCallbacks(pairTimeout);
            adapter.cancelDiscovery();
            d.createBond();
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && d != null
                && i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) == BluetoothDevice.BOND_BONDED
                && looksLikeAirPods(d)) {
            connect(d);
        } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action) && d != null
                && i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) == BluetoothDevice.BOND_NONE && looksLikeAirPods(d)) {
            notifyUi(EVENT_PAIR_FAILED);
        }
    }

    private void onA2dp(BluetoothDevice d, int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) {
            output = d;
            lowNotified = 100;
            store.setLastHeadphones(d.getAddress());
            sounds.play(Sounds.SELECT);
            AirPodsBanner.Content c = new AirPodsBanner.Content();
            c.kind = AirPodsBanner.CONNECTED;
            c.title = nameOf(d);
            c.subtitle = "Connected";
            AirPodsAdvert a = fresh();
            if (a != null && !a.pairing) {
                c.buds = a.budsLow();
                c.box = a.box;
                c.chargingBuds = a.budsCharging();
                c.chargingBox = a.chargingBox;
            }
            AirPodsBanner.show(ctx, c);
        } else if (state == BluetoothProfile.STATE_DISCONNECTED && output != null
                && output.getAddress().equals(d.getAddress())) {
            output = null;
            main.removeCallbacks(pauseNow);
            pausedByUs = false;
            sounds.play(Sounds.BACK);
            AirPodsBanner.Content c = new AirPodsBanner.Content();
            c.kind = AirPodsBanner.DISCONNECTED;
            c.title = nameOf(d);
            c.subtitle = "Disconnected";
            AirPodsBanner.show(ctx, c);
        }
        notifyUi(EVENT_STATE);
    }

    private void notifyUi(int event) {
        for (Listener l : listeners) {
            l.onAirPods(event);
        }
    }
}
