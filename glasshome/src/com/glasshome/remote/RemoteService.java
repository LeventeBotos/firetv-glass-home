package com.glasshome.remote;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.util.Log;

import com.glasshome.R;

import java.io.File;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.math.BigInteger;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Makes the stick show up as an Apple TV in the iPhone's Remote and acts on what the phone sends. Started at boot and
 * whenever Glass Home starts; it keeps itself running (a foreground service, like HomeWatcher).
 *
 * Three parts: {@link CompanionServer} speaks the protocol, {@link Advertiser} announces it on the network, and
 * {@link InputBridge} turns the phone's buttons into real key presses in whatever app is open.
 */
public class RemoteService extends Service {
    static final String TAG = "GlassRemote";
    private static final int SWIPE_STEP = 130;   // 1000 units across the phone's touch area; one D-pad press per step

    private static volatile RemoteService running;

    private CompanionServer server;
    private Advertiser advertiser;
    private InputBridge bridge;
    private PairingStore store;
    private WifiManager.MulticastLock multicast;

    public static void start(Context context) {
        context.startService(new Intent(context, RemoteService.class));
    }

    /** One line for the UI, e.g. "Nappali: ready, 2 iPhones paired". */
    public static String summary() {
        RemoteService s = running;
        if (s == null || s.bridge == null) {
            return "not running";
        }
        int n = s.store.clientCount();
        return s.store.name() + ": " + s.bridge.status() + ", " + n + (n == 1 ? " iPhone" : " iPhones") + " paired";
    }

    /** Called when a computer has started the key helper and told the app where it is. */
    static void helperAnnounced() {
        RemoteService s = running;
        if (s != null && s.bridge != null) {
            s.bridge.wake();
        }
    }

    /** Forgets every paired iPhone (and the identity, so they start over). */
    public static void forgetPhones() {
        RemoteService s = running;
        if (s != null) {
            s.store.forgetAll();
            s.restartAnnouncement();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) {
            startForeground(2, new Notification.Builder(this)
                    .setSmallIcon(R.drawable.icon)
                    .setContentTitle(getString(R.string.app_name))
                    .setPriority(Notification.PRIORITY_MIN)
                    .build());
            try {
                begin();
            } catch (Exception e) {
                Log.w(TAG, "the iPhone remote could not start: " + e);
            }
        }
        return START_STICKY;
    }

    private void begin() throws Exception {
        final CompanionServer.Log log = new CompanionServer.Log() {
            @Override
            public void d(String message) {
                Log.i(TAG, message);
            }
        };
        AndroidStorage storage = new AndroidStorage(this);
        store = new PairingStore(storage);

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicast = wifi.createMulticastLock("glasshome-remote");
        multicast.setReferenceCounted(false);
        multicast.acquire();

        bridge = new InputBridge(storage, new AndroidEnv(storage), log);
        bridge.start();

        server = new CompanionServer(store, bridge, new PinOverlay(this), log, SWIPE_STEP);
        int port = server.start(store.port());
        store.setPort(port);
        advertiser = new Advertiser(this, store, log);
        advertiser.start(port);
        running = this;
        Log.i(TAG, "listening on " + port + " as \"" + store.name() + "\"");
    }

    private void restartAnnouncement() {
        if (advertiser != null) {
            advertiser.stop();
            advertiser.start(server == null ? store.port() : store.port());
        }
    }

    @Override
    public void onDestroy() {
        running = null;
        if (advertiser != null) {
            advertiser.stop();
        }
        if (server != null) {
            server.stop();
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (multicast != null && multicast.isHeld()) {
            multicast.release();
        }
        super.onDestroy();
    }

    /** What {@link InputBridge} needs from the device: where the APK is, and the key this app uses with the stick's ADB. */
    private final class AndroidEnv implements InputBridge.Env {
        private final PairingStore.Storage storage;
        private KeyPair adbKey;

        AndroidEnv(PairingStore.Storage storage) {
            this.storage = storage;
        }

        @Override
        public String apkPath() {
            return getPackageCodePath();
        }

        @Override
        public long apkStamp() {
            return new File(getPackageCodePath()).lastModified();
        }

        @Override
        public synchronized KeyPair adbKey() {
            if (adbKey == null) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    String saved = storage.get("adb_key");
                    if (saved == null) {
                        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                        gen.initialize(2048);
                        KeyPair pair = gen.generateKeyPair();
                        storage.put("adb_key", PairingStore.hex(pair.getPrivate().getEncoded()));
                        adbKey = pair;
                    } else {
                        // Android's key class is not an RSAPrivateCrtKey; the exponent is the standard 65537
                        RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(
                                new PKCS8EncodedKeySpec(PairingStore.unhex(saved)));
                        adbKey = new KeyPair(kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(),
                                BigInteger.valueOf(65537))), priv);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return adbKey;
        }

        @Override
        public String[] adbHosts() {
            int ip = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                    .getConnectionInfo().getIpAddress();
            String own = (ip & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >> 24 & 0xff);
            return ip == 0 ? new String[]{"127.0.0.1"} : new String[]{"127.0.0.1", own};
        }

        @Override
        public int adbPort() {
            return 5555;
        }
    }
}
