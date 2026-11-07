package com.glasshome;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * Control Center: a glass panel under the clock pill, laid out from a measurement of tvOS's own at 1080p.
 * Nothing dims the screen behind it (tvOS does not either, and a full-screen overlay costs a frame on this GPU).
 *
 * Concentric corners: the panel's radius is the tiles' radius plus the padding between them (13 + 12 = 25 dp).
 * Tiles do not grow on focus (they turn white), so the rule holds at rest and in focus.
 *
 * What the tiles do on a Fire TV: Sleep stops playback and starts the screensaver (see SleepControl for why it
 * cannot power the stick off); Screensaver switches the idle screensaver on or off; Sleep Timer cycles Off, 15, 30,
 * 60 and 90 minutes; Wi-Fi and Audio open the matching system settings.
 */
final class ControlCenterView extends FrameLayout {
    // dp, measured from the reference at 1080p (density 2)
    static final float PAD = 12, GAP = 8.5f, TILE_W = 103, TILE_H = 48, SMALL = 47.25f, TILE_RADIUS = 13;
    static final float PANEL_RADIUS = TILE_RADIUS + PAD;
    static final float PANEL_W = 2 * PAD + 2 * TILE_W + GAP;
    static final float PANEL_H = 2 * PAD + (2 * TILE_H + GAP) + GAP + TILE_H + GAP + SMALL;
    static final float RIGHT = 24.5f, TOP = 63.5f;

    private final FrameLayout panel;
    private final AudioPanelView audioPanel;
    private final AirPodsMonitor pods;
    private boolean audioOpen;
    private final Sounds sounds;
    private final Runnable onClose;
    private final GlassButton sleep, wifi, saver, audio, timer, airplay, uiSounds, access, settings;
    private final float k;
    private boolean open;

    ControlCenterView(Context context, GradientBackdrop backdrop, Sounds sounds, LayoutStore store, Runnable onClose) {
        super(context);
        pods = AirPodsMonitor.get(context);
        this.sounds = sounds;
        this.onClose = onClose;
        k = context.getResources().getDisplayMetrics().density;
        setVisibility(GONE);

        panel = new FrameLayout(context);
        panel.setBackground(new GlassDrawable(panel, backdrop, PANEL_RADIUS * k, false));
        LayoutParams pp = new LayoutParams(Math.round(PANEL_W * k), Math.round(PANEL_H * k), Gravity.TOP | Gravity.END);
        pp.setMargins(0, Math.round(TOP * k), Math.round(RIGHT * k), 0);
        addView(panel, pp);

        // the Audio page: a second glass panel in the same corner, swapped in when the Audio tile is chosen
        audioPanel = new AudioPanelView(context, backdrop, sounds, store, this::closeAll);
        LayoutParams ap = new LayoutParams(Math.round(PANEL_W * k), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ap.setMargins(0, Math.round(TOP * k), Math.round(RIGHT * k), 0);
        addView(audioPanel, ap);

        float left = PAD, right = PAD + TILE_W + GAP;
        float row2 = PAD + 2 * TILE_H + GAP + GAP, row3 = row2 + TILE_H + GAP;
        float bigH = 2 * TILE_H + GAP;
        sleep = tile(GlassButton.BIG, Glyphs.POWER, "Sleep", null, left, PAD, TILE_W, bigH);
        wifi = tile(GlassButton.WIDE, Glyphs.WIFI, "Wi-Fi", "", right, PAD, TILE_W, TILE_H);
        saver = tile(GlassButton.WIDE, Glyphs.SCREEN, "Screensaver", "", right, PAD + TILE_H + GAP, TILE_W, TILE_H);
        audio = tile(GlassButton.WIDE, Glyphs.AUDIO, "Audio", "Bluetooth", left, row2, TILE_W, TILE_H);
        timer = tile(GlassButton.WIDE, Glyphs.TIMER, "Sleep Timer", "", right, row2, TILE_W, TILE_H);
        float step = SMALL + (2 * TILE_W + GAP - 4 * SMALL) / 3f;
        airplay = tile(GlassButton.ICON, Glyphs.AIRPLAY, "AirPlay", null, PAD, row3, SMALL, SMALL);
        uiSounds = tile(GlassButton.ICON, Glyphs.SOUND, "Sounds", null, PAD + step, row3, SMALL, SMALL);
        access = tile(GlassButton.ICON, Glyphs.ACCESS, "Accessibility", null, PAD + 2 * step, row3, SMALL, SMALL);
        settings = tile(GlassButton.ICON, Glyphs.GEAR, "Settings", null, PAD + 3 * step, row3, SMALL, SMALL);

        sleep.setOnClickListener(v -> {
            closeAll();
            SleepControl.sleepNow(getContext());
        });
        wifi.setOnClickListener(v -> go(Settings.ACTION_WIFI_SETTINGS));
        audio.setOnClickListener(v -> showAudio());
        saver.setOnClickListener(v -> toggleSaver());
        timer.setOnClickListener(v -> {
            sounds.play(Sounds.SELECT);
            SleepControl.cycleTimer(getContext());
            refresh();
        });
        airplay.setOnClickListener(v -> {
            closeAll();
            // the AirPlay receiver set up by glasshome/airplay/setup.py; AirReceiver is only the fallback
            if (!isInstalled("io.github.jqssun.airplay")) {
                Launch.app(getContext(), "com.softmedia.receiver", "No AirPlay receiver is installed.");
            } else {
                Launch.app(getContext(), "io.github.jqssun.airplay", "The AirPlay receiver can't be opened.");
            }
        });
        uiSounds.setOnClickListener(v -> {
            sounds.setOn(!sounds.isOn());
            sounds.play(Sounds.SELECT);
            refresh();
        });
        access.setOnClickListener(v -> go(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        settings.setOnClickListener(v -> go(Settings.ACTION_SETTINGS));
    }

    private boolean isInstalled(String pkg) {
        try {
            getContext().getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private GlassButton tile(int shape, int glyph, String label, String sub, float x, float y, float w, float h) {
        GlassButton b = new GlassButton(getContext(), shape, glyph, label, sub, TILE_RADIUS * k, k);
        LayoutParams lp = new LayoutParams(Math.round(w * k), Math.round(h * k));
        lp.setMargins(Math.round(x * k), Math.round(y * k), 0, 0);
        panel.addView(b, lp);
        return b;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) {
            return;
        }
        open = true;
        refresh();
        setVisibility(VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationY(-12 * k);
        panel.setScaleX(0.97f);
        panel.setScaleY(0.97f);
        panel.setPivotX(panel.getLayoutParams().width);
        panel.setPivotY(0f);
        // withLayer(): the panel is drawn once into a texture and that is faded, instead of redrawn every frame
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).withLayer()
                .setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(260).start();
        sounds.play(Sounds.SELECT);
        sleep.requestFocus();
    }

    /** Back/Menu: collapse the Audio page first, then the whole panel. */
    void close() {
        if (audioOpen) {
            hideAudio();
            return;
        }
        closeAll();
    }

    private void closeAll() {
        if (!open) {
            return;
        }
        if (audioOpen) {
            audioOpen = false;
            audioPanel.unbind();
            audioPanel.setVisibility(GONE);
            panel.setVisibility(VISIBLE);
            panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        open = false;
        sounds.play(Sounds.BACK);
        panel.animate().alpha(0f).translationY(-8 * k).withLayer().setDuration(180)
                .withEndAction(() -> setVisibility(GONE)).start();
        onClose.run();
    }

    private void go(String action) {
        closeAll();
        Launch.settings(getContext(), action, "Couldn't open that settings page.");
    }

    /** The Audio page, as if the TV button were held: opens Control Center straight onto it. */
    void openAudio() {
        if (!open) {
            open = true;
            refresh();
            setVisibility(VISIBLE);
            panel.setAlpha(0f);
            panel.setVisibility(INVISIBLE);
        }
        if (!audioOpen) {
            showAudio();
        }
    }

    boolean isAudioOpen() {
        return audioOpen;
    }

    private void showAudio() {
        audioOpen = true;
        sounds.play(Sounds.SELECT);
        panel.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        panel.animate().cancel();
        panel.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            if (audioOpen) {
                panel.setVisibility(INVISIBLE);
            }
        }).start();
        audioPanel.bind();
        audioPanel.setVisibility(VISIBLE);
        audioPanel.setAlpha(0f);
        audioPanel.setTranslationY(-12 * k);
        audioPanel.setScaleX(0.97f);
        audioPanel.setScaleY(0.97f);
        audioPanel.setPivotX(Math.round(PANEL_W * k));
        audioPanel.setPivotY(0f);
        audioPanel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).withLayer()
                .setInterpolator(new DecelerateInterpolator(1.6f)).setDuration(260).start();
    }

    private void hideAudio() {
        audioOpen = false;
        sounds.play(Sounds.BACK);
        audioPanel.unbind();
        audioPanel.animate().cancel();
        audioPanel.animate().alpha(0f).translationY(-8 * k).withLayer().setDuration(160)
                .withEndAction(() -> {
                    if (!audioOpen) {
                        audioPanel.setVisibility(GONE);
                    }
                }).start();
        refresh();
        panel.setVisibility(VISIBLE);
        panel.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        panel.animate().cancel();
        panel.animate().alpha(1f).setDuration(200).start();
        audio.requestFocus();
    }

    private void toggleSaver() {
        try {
            boolean on = Settings.Secure.getInt(getContext().getContentResolver(), "screensaver_enabled", 1) == 1;
            Settings.Secure.putInt(getContext().getContentResolver(), "screensaver_enabled", on ? 0 : 1);
            sounds.play(Sounds.SELECT);
        } catch (SecurityException e) {
            android.widget.Toast.makeText(getContext(), "Run glasshome/install.sh again to allow this.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    /** Bring the tiles' second lines up to date: network name, screensaver on/off, timer, sounds. */
    private void refresh() {
        Context c = getContext();
        wifi.setLabel("Wi-Fi", networkName(c));
        boolean on = Settings.Secure.getInt(c.getContentResolver(), "screensaver_enabled", 1) == 1;
        saver.setLabel("Screensaver", on ? "On" : "Off");
        int minutes = SleepControl.timerMinutes(c);
        timer.setLabel("Sleep Timer", minutes == 0 ? "Off" : minutes + " min");
        uiSounds.setOff(!sounds.isOn());
        // Audio: the live output, with the buds' level when AirPods are in use
        String out = pods.outputName();
        AirPodsAdvert a = pods.fresh();
        audio.setGlyph(out != null ? Glyphs.EARBUDS : Glyphs.AUDIO);
        audio.setLabel("Audio", out == null ? "TV Speakers"
                : (a != null && !a.pairing && a.budsLow() != AirPodsAdvert.UNKNOWN ? out + " · " + a.budsLow() + "%" : out));
    }

    private static String networkName(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !ssid.equals("<unknown ssid>") && !ssid.equals("0x")) {
                return ssid.replace("\"", "");
            }
        } catch (SecurityException ignored) {
            // no permission to read it: show a plain state below
        }
        return "Connected";
    }

    /** The first tile to receive focus when the panel opens, so the back path can restore it. */
    View firstTile() {
        return sleep;
    }
}
