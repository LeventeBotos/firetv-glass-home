package com.glasshome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The sleep timer going off: do what the Sleep tile does, and show the timer as off again. */
public class SleepReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("sleep", Context.MODE_PRIVATE).edit().putInt("sleep_minutes", 0).apply();
        SleepControl.sleepNow(context);
    }
}
