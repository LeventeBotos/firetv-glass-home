package com.glasshome.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Where a computer tells the app about the key helper it just started (see helper.py): the helper's port and secret
 * token. Fire OS refuses ADB connections that come from the stick itself, so the app cannot start the helper on its
 * own; a computer does it, and this is how the app finds out. Only the ADB shell (or the system) may send this: the
 * receiver requires the DUMP permission, which apps cannot hold, so no other app can point the app at a helper of its own.
 */
public class HelperReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int port = intent.getIntExtra("port", 0);
        String token = intent.getStringExtra("token");
        if (port <= 0 || token == null || token.isEmpty()) {
            return;
        }
        AndroidStorage storage = new AndroidStorage(context);
        storage.put("inj_port", Integer.toString(port));
        storage.put("inj_token", token);
        Log.i(RemoteService.TAG, "told about a key helper on port " + port);
        RemoteService.start(context);
        RemoteService.helperAnnounced();
    }
}
