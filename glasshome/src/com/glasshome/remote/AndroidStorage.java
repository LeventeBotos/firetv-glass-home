package com.glasshome.remote;

import android.content.Context;
import android.content.SharedPreferences;

/** {@link PairingStore.Storage} on the app's private SharedPreferences. */
final class AndroidStorage implements PairingStore.Storage {
    private final SharedPreferences prefs;

    AndroidStorage(Context context) {
        prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    @Override
    public String get(String key) {
        return prefs.getString(key, null);
    }

    @Override
    public void put(String key, String value) {
        SharedPreferences.Editor e = prefs.edit();
        if (value == null) {
            e.remove(key);
        } else {
            e.putString(key, value);
        }
        e.commit();   // not apply(): a pairing must be on disk before the phone is told it succeeded
    }
}
