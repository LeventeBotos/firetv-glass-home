package com.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the watcher at boot; it opens HomeActivity if Amazon's home is what came up (see HomeWatcher.catchUp). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, HomeWatcher.class));
    }
}
