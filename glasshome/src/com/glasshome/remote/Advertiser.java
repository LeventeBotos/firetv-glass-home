package com.glasshome.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;

/**
 * Announces the stick on the network as an Apple TV (the "_companion-link._tcp" service the iPhone's Remote looks for),
 * with the records an Apple TV publishes. If the network is not up yet, or drops, it tries again.
 */
final class Advertiser {
    private final NsdManager nsd;
    private final PairingStore store;
    private final CompanionServer.Log log;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NsdManager.RegistrationListener listener;
    private int port;
    private boolean stopped;

    Advertiser(Context context, PairingStore store, CompanionServer.Log log) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.store = store;
        this.log = log;
    }

    synchronized void start(int port) {
        this.port = port;
        stopped = false;
        register();
    }

    synchronized void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (listener != null) {
            try {
                nsd.unregisterService(listener);
            } catch (RuntimeException ignored) {
                // not registered
            }
            listener = null;
        }
    }

    private synchronized void register() {
        if (stopped) {
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(store.name());
        info.setServiceType("_companion-link._tcp");
        info.setPort(port);
        for (Map.Entry<String, String> e : store.txtRecords().entrySet()) {
            info.setAttribute(e.getKey(), e.getValue());
        }
        listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo registered) {
                log.d("announced on the network as \"" + registered.getServiceName() + "\"");
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo failed, int errorCode) {
                log.d("could not announce (error " + errorCode + "); trying again in 10 s");
                retry();
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                // stop() or a restart
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                // nothing to do
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            log.d("could not announce: " + e);
            retry();
        }
    }

    private void retry() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                register();
            }
        }, 10000);
    }
}
