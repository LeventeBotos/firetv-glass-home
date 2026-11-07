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

    // Fire OS shows Settings inside Amazon's own launcher: starting it (even by its own activity name) makes the launcher
    // start its Home screen, which HomeWatcher takes for a Home press and answers by covering Settings with Glass Home.
    // So the watcher stands down for a moment when Glass Home itself opens Settings. A real Home press a few seconds
    // later is still redirected as usual.
    private static final long SETTINGS_GRACE_MS = 4000;

    /** Open a system settings screen if this stick has one for the action, else the main Settings list. */
    static boolean settings(Context context, String action, String failure) {
        HomeWatcher.pause(SETTINGS_GRACE_MS);
        Intent wanted = new Intent(action);
        if (wanted.resolveActivity(context.getPackageManager()) == null) {
            wanted = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        return start(context, wanted, failure);
    }
}
