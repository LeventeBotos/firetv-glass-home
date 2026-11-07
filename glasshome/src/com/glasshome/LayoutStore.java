package com.glasshome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the user arranged: the order of the tiles, which apps are hidden or deliberately un-hidden, and whether the
 * UI sounds are on. Package names never contain commas, so lists are stored as comma-separated strings.
 */
final class LayoutStore {
    private final SharedPreferences prefs;

    LayoutStore(Context context) {
        prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE);
    }

    List<String> order() {
        return split(prefs.getString("order", ""));
    }

    void setOrder(List<String> ids) {
        prefs.edit().putString("order", join(ids)).apply();
    }

    /** Apps the user hid. */
    Set<String> hidden() {
        return new LinkedHashSet<String>(split(prefs.getString("hidden", "")));
    }

    /** Apps hidden by default (Amazon's utility apps) that the user chose to show. */
    Set<String> unhidden() {
        return new LinkedHashSet<String>(split(prefs.getString("unhidden", "")));
    }

    void hide(String id) {
        Set<String> hidden = hidden(), shown = unhidden();
        hidden.add(id);
        shown.remove(id);
        prefs.edit().putString("hidden", join(hidden)).putString("unhidden", join(shown)).apply();
    }

    void unhide(String id) {
        Set<String> hidden = hidden(), shown = unhidden();
        hidden.remove(id);
        shown.add(id);
        prefs.edit().putString("hidden", join(hidden)).putString("unhidden", join(shown)).apply();
    }

    boolean sounds() {
        return prefs.getBoolean("sounds", true);
    }

    void setSounds(boolean on) {
        prefs.edit().putBoolean("sounds", on).apply();
    }

    /** Connect AirPods by themselves when their case opens or they go in an ear. */
    boolean autoConnect() {
        return prefs.getBoolean("airpodsAuto", true);
    }

    void setAutoConnect(boolean on) {
        prefs.edit().putBoolean("airpodsAuto", on).apply();
    }

    /** Pause when both buds come out, resume when one goes back in. */
    boolean earDetection() {
        return prefs.getBoolean("airpodsEar", true);
    }

    void setEarDetection(boolean on) {
        prefs.edit().putBoolean("airpodsEar", on).apply();
    }

    /** Bluetooth address of the headphones that were last the output, tried first on auto-connect. */
    String lastHeadphones() {
        return prefs.getString("lastHeadphones", "");
    }

    void setLastHeadphones(String address) {
        prefs.edit().putString("lastHeadphones", address).apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        for (String part : s.split(",")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    private static String join(Iterable<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}
