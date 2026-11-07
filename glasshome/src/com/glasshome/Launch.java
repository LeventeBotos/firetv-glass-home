package com.glasshome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

/** Starting other screens safely: a missing target is a message, never a crash. */
final class Launch {
    private Launch() {
    }

    static boolean start(Context context, Intent intent, String failure) {
        try {
            if (intent == null) {
                throw new ActivityNotFoundException();
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            HomeWatcher.pause(0);
            Toast.makeText(context, failure, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Open an app by package, the way its own Home tile would. */
    static boolean app(Context context, String pkg, String failure) {
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return start(context, i != null ? i : pm.getLaunchIntentForPackage(pkg), failure);
    }

    /** Open a system settings screen if this stick has one for the action, else the main Settings list. */
    static boolean settings(Context context, String action, String failure) {
        Intent wanted = new Intent(action);
        if (wanted.resolveActivity(context.getPackageManager()) == null) {
            wanted = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        return start(context, wanted, failure);
    }
}
