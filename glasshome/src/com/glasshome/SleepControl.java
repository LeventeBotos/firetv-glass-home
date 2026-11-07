package com.glasshome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * What "Sleep" and the sleep timer can really do. An ordinary app cannot power the stick down (there is no sleep
 * action it may call), so Sleep stops whatever is playing and starts the screensaver; the stick then goes to sleep
 * on its own sleep timer (20 minutes by default), exactly as it does after any idle period.
 */
final class SleepControl {
    static final int[] MINUTES = {0, 15, 30, 60, 90};

    private SleepControl() {
    }

    static void sleepNow(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            // pause, then stop: players differ in which of the two they obey
            for (int key : new int[]{KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP}) {
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            }
        }
        context.startActivity(new Intent(context, SaverActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    static int timerMinutes(Context context) {
        return prefs(context).getInt("sleep_minutes", 0);
    }

    /** Off, 15, 30, 60, 90, off ... Returns the new setting in minutes (0 = off). */
    static int cycleTimer(Context context) {
        int current = timerMinutes(context), next = MINUTES[0];
        for (int i = 0; i < MINUTES.length; i++) {
            if (MINUTES[i] == current) {
                next = MINUTES[(i + 1) % MINUTES.length];
            }
        }
        setTimer(context, next);
        return next;
    }

    static void setTimer(Context context, int minutes) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent fire = PendingIntent.getBroadcast(context, 1, new Intent(context, SleepReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(fire);
        if (minutes > 0) {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, fire);
        }
        prefs(context).edit().putInt("sleep_minutes", minutes).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sleep", Context.MODE_PRIVATE);
    }
}
