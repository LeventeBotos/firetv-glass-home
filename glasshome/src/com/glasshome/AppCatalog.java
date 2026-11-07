package com.glasshome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tiles on the home screen, in order: every launchable app on the stick plus a few special tiles. Order is the
 * user's saved order first, then the favourites below, then everything else A-Z. Amazon's own utility apps are
 * hidden by default; the user can show or hide any app.
 */
final class AppCatalog {
    static final int APP = 0, SETTINGS = 1, AMAZON_HOME = 2, HIDDEN_TOGGLE = 3;
    static final String ID_SETTINGS = "special:settings", ID_AMAZON = "special:amazonhome", ID_HIDDEN = "special:hidden";

    /** One tile. id is the package name, or one of the special ids. */
    static final class Entry {
        final String id;
        final String label;
        final int kind;
        final boolean hidden;     // true only for hidden apps shown on purpose in the "Hidden Apps" view

        Entry(String id, String label, int kind, boolean hidden) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.hidden = hidden;
        }
    }

    // the user's current favourites, in the order they sit in the dock
    private static final String[] FAVOURITES = {
            "com.apple.atve.amazon.appletv", "com.amazon.firetv.youtube", "com.netflix.ninja", "com.disney.disneyplus",
            "com.hbo.hbonow", "com.spotify.tv.android", "tunein.player", "org.videolan.vlc", "io.github.jqssun.airplay",
            "org.jellyfin.androidtv", "com.limelight", "com.amazon.venezia"};

    private AppCatalog() {
    }

    /** Can this tile be hidden? Settings and Fire TV Home must stay, so there is always a way out. */
    static boolean canHide(Entry e) {
        return e.kind == APP;
    }

    static List<Entry> build(Context context, LayoutStore store, boolean showHidden) {
        PackageManager pm = context.getPackageManager();
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(context.getPackageName()) && !labels.containsKey(pkg)) {
                    labels.put(pkg, String.valueOf(info.loadLabel(pm)));
                }
            }
        }
        labels.put(ID_SETTINGS, "Settings");
        labels.put(ID_AMAZON, "Fire TV Home");

        // default order: favourites, then Settings and Fire TV Home, then the rest A-Z
        List<String> fallback = new ArrayList<String>();
        for (String fav : FAVOURITES) {
            if (labels.containsKey(fav)) {
                fallback.add(fav);
            }
        }
        fallback.add(ID_SETTINGS);
        fallback.add(ID_AMAZON);
        List<String> rest = new ArrayList<String>();
        for (String id : labels.keySet()) {
            if (!fallback.contains(id)) {
                rest.add(id);
            }
        }
        final Map<String, String> byLabel = labels;
        Collections.sort(rest, (a, b) -> byLabel.get(a).compareToIgnoreCase(byLabel.get(b)));
        fallback.addAll(rest);

        List<String> order = new ArrayList<String>();
        for (String id : store.order()) {
            if (labels.containsKey(id) && !order.contains(id)) {
                order.add(id);
            }
        }
        for (String id : fallback) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }

        Set<String> hidden = store.hidden(), shown = store.unhidden();
        List<Entry> visible = new ArrayList<Entry>(), tucked = new ArrayList<Entry>();
        for (String id : order) {
            int kind = id.equals(ID_SETTINGS) ? SETTINGS : id.equals(ID_AMAZON) ? AMAZON_HOME : APP;
            boolean isHidden = kind == APP && (hidden.contains(id) || (!shown.contains(id) && hiddenByDefault(id)));
            if (isHidden) {
                tucked.add(new Entry(id, labels.get(id), kind, true));
            } else {
                visible.add(new Entry(id, labels.get(id), kind, false));
            }
        }
        List<Entry> result = new ArrayList<Entry>(visible);
        if (!tucked.isEmpty()) {
            if (showHidden) {
                result.addAll(tucked);
            }
            result.add(new Entry(ID_HIDDEN, "Hidden Apps (" + tucked.size() + ")", HIDDEN_TOGGLE, false));
        }
        return result;
    }

    /** Amazon's and Android's own utility apps are not apps the user opens from Home. */
    private static boolean hiddenByDefault(String pkg) {
        Set<String> favourites = new HashSet<String>();
        Collections.addAll(favourites, FAVOURITES);
        return !favourites.contains(pkg) && (pkg.startsWith("com.amazon.") || pkg.startsWith("com.android."));
    }
}
