package com.glasshome;

import android.animation.ValueAnimator;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Control Center's Audio page, opened from the Audio tile (or by holding Play/Pause on the remote, the nearest thing a
 * Fire TV remote has to holding the TV button): the output list (TV speakers and paired headphones), battery rings for
 * the left bud, right bud and case, a volume bar, and the AirPods switches. Same glass, same corner rule as the main
 * panel (panel radius = row radius + padding).
 */
final class AudioPanelView extends LinearLayout implements AirPodsMonitor.Listener {
    private static final float ROW_W = 2 * ControlCenterView.TILE_W + ControlCenterView.GAP;

    private final AirPodsMonitor monitor;
    private final LayoutStore store;
    private final Sounds sounds;
    private final float k;
    private final Runnable onBack;
    private BatteryRow battery;
    private String signature = "";
    private ValueAnimator sweeper;
    private final LinearLayout list;
    private final int maxHeight;

    AudioPanelView(Context context, GradientBackdrop backdrop, Sounds sounds, LayoutStore store, Runnable onBack) {
        super(context);
        this.sounds = sounds;
        this.store = store;
        this.onBack = onBack;
        monitor = AirPodsMonitor.get(context);
        k = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int pad = Math.round(ControlCenterView.PAD * k);
        setPadding(pad, pad, pad, pad);
        setBackground(new GlassDrawable(this, backdrop, ControlCenterView.PANEL_RADIUS * k, false));
        setVisibility(GONE);

        // The page can be taller than the screen (several paired devices): the rows live in a scroll view, and the
        // panel never grows past the screen. The D-pad scrolls it by moving focus.
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        super.addView(scroll, new LayoutParams(-1, -2));
        maxHeight = context.getResources().getDisplayMetrics().heightPixels
                - Math.round((ControlCenterView.TOP + 28) * k);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = MeasureSpec.getSize(heightSpec);
        int cap = h > 0 ? Math.min(h, maxHeight) : maxHeight;
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }

    /** Rebuild the rows from the current state and start listening for changes. */
    void bind() {
        rebuild(-1);
        monitor.addListener(this);
    }

    void unbind() {
        monitor.removeListener(this);
        if (sweeper != null) {
            sweeper.cancel();
        }
    }

    View firstFocus() {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (list.getChildAt(i).isFocusable()) {
                return list.getChildAt(i);
            }
        }
        return this;
    }

    @Override
    public void onAirPods(int event) {
        if (getVisibility() != VISIBLE) {
            return;
        }
        if (!signature().equals(signature)) {
            int at = list.indexOfChild(list.getFocusedChild());
            rebuild(at);
        } else if (battery != null) {
            battery.set(monitor.fresh());
        }
    }

    private String signature() {
        StringBuilder sb = new StringBuilder(String.valueOf(monitor.outputName()));
        for (BluetoothDevice d : monitor.audioDevices()) {
            sb.append('|').append(d.getAddress());
        }
        sb.append(monitor.fresh() != null).append(store.autoConnect()).append(store.earDetection());
        return sb.toString();
    }

    private void rebuild(int focusIndex) {
        list.removeAllViews();
        battery = null;
        signature = signature();
        Context c = getContext();

        TextView head = new TextView(c);
        head.setText("Audio");
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(15);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setPadding(Math.round(4 * k), Math.round(2 * k), 0, Math.round(8 * k));
        list.addView(head, new LayoutParams(-1, -2));

        // outputs: the TV, then every paired audio device
        row(Glyphs.SCREEN, "TV Speakers", monitor.connected() ? "" : "Selected", v -> {
            sounds.play(Sounds.SELECT);
            monitor.useTvSpeakers();
        });
        List<BluetoothDevice> devices = monitor.audioDevices();
        for (BluetoothDevice d : devices) {
            if (AirPodsMonitor.nameOf(d).endsWith("Find My")) {
                continue;   // a Find My network entry for the same AirPods, not an audio output
            }
            boolean on = monitor.isOutput(d);
            String name = AirPodsMonitor.nameOf(d);
            boolean pods = name.contains("AirPods") || name.contains("Beats");
            row(pods ? Glyphs.EARBUDS : Glyphs.AUDIO, name, on ? "Selected" : "Tap to connect", v -> {
                sounds.play(Sounds.SELECT);
                if (on) {
                    monitor.disconnect(d);
                } else {
                    monitor.connect(d);
                }
            });
        }
        if (devices.isEmpty()) {
            row(Glyphs.EARBUDS, "Add Headphones", "Open Bluetooth", v -> {
                onBack.run();
                Launch.settings(getContext(), Settings.ACTION_BLUETOOTH_SETTINGS, "Couldn't open Bluetooth settings.");
            });
        }

        AirPodsAdvert a = monitor.fresh();
        if (a != null && !a.pairing && monitor.connected()) {
            battery = new BatteryRow(c, k);
            battery.set(a);
            list.addView(battery, new LayoutParams(Math.round(ROW_W * k), Math.round(70 * k)));
            startSweep();
        }

        list.addView(new VolumeBar(c, sounds, k), params(ControlCenterView.TILE_H));

        row(Glyphs.EARBUDS, "Auto-Connect", store.autoConnect() ? "On" : "Off", v -> {
            sounds.play(Sounds.SELECT);
            store.setAutoConnect(!store.autoConnect());
            rebuild(list.indexOfChild(v));
        });
        row(Glyphs.EARBUDS, "Ear Detection", store.earDetection() ? "On" : "Off", v -> {
            sounds.play(Sounds.SELECT);
            store.setEarDetection(!store.earDetection());
            rebuild(list.indexOfChild(v));
        });
        row(Glyphs.GEAR, "Bluetooth Settings", "", v -> {
            onBack.run();
            Launch.settings(getContext(), Settings.ACTION_BLUETOOTH_SETTINGS, "Couldn't open Bluetooth settings.");
        });

        if (monitor.available() && monitor.locationOff() && !monitor.sawAdvert()) {
            TextView note = new TextView(c);
            note.setText("Turn Location on in Settings so Glass Home can see AirPods.");
            note.setTextColor(0xB3FFFFFF);
            note.setTextSize(10);
            note.setPadding(Math.round(4 * k), Math.round(6 * k), Math.round(4 * k), 0);
            list.addView(note, new LayoutParams(Math.round(ROW_W * k), -2));
        }

        View target = focusIndex >= 0 && focusIndex < list.getChildCount() && list.getChildAt(focusIndex).isFocusable()
                ? list.getChildAt(focusIndex) : firstFocus();
        target.post(target::requestFocus);
    }

    private LayoutParams params(float heightDp) {
        LayoutParams lp = new LayoutParams(Math.round(ROW_W * k), Math.round(heightDp * k));
        lp.topMargin = Math.round(ControlCenterView.GAP * k);
        return lp;
    }

    private void row(int glyph, String label, String sub, OnClickListener click) {
        GlassButton b = new GlassButton(getContext(), GlassButton.WIDE, glyph, label, sub,
                ControlCenterView.TILE_RADIUS * k, k);
        b.setOnClickListener(click);
        list.addView(b, params(ControlCenterView.TILE_H));
    }

    private void startSweep() {
        if (sweeper != null) {
            sweeper.cancel();
        }
        sweeper = ValueAnimator.ofFloat(0f, 1f);
        sweeper.setDuration(650);
        sweeper.setStartDelay(200);
        sweeper.setInterpolator(new DecelerateInterpolator(1.8f));
        sweeper.addUpdateListener(an -> {
            if (battery != null) {
                battery.sweep = (Float) an.getAnimatedValue();
                battery.invalidate();
            }
        });
        sweeper.start();
    }

    // ---- left bud, right bud, case --------------------------------------------------------------------------------

    private static final class BatteryRow extends View {
        float sweep = 1f;
        private final float k;
        private AirPodsAdvert a;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);

        BatteryRow(Context c, float k) {
            super(c);
            this.k = k;
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(9.5f * k);
            text.setTextAlign(Paint.Align.CENTER);
            small.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            small.setTextSize(9f * k);
            small.setTextAlign(Paint.Align.CENTER);
            small.setColor(0xB3FFFFFF);
        }

        void set(AirPodsAdvert advert) {
            a = advert;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (a == null) {
                return;
            }
            float w = getWidth(), cy = 27 * k;
            cell(canvas, w * 1 / 6f, cy, "Left", a.left, a.chargingLeft);
            cell(canvas, w * 3 / 6f, cy, "Right", a.right, a.chargingRight);
            cell(canvas, w * 5 / 6f, cy, "Case", a.box, a.chargingBox);
        }

        private void cell(Canvas c, float cx, float cy, String name, int percent, boolean charging) {
            int color = charging ? 0xFF30D158 : (percent != AirPodsAdvert.UNKNOWN && percent <= 20 ? 0xFFFF453A : 0xFFFFFFFF);
            ink.setColor(color);
            Glyphs.ring(c, cx, cy, 16 * k, percent == AirPodsAdvert.UNKNOWN ? 0f : percent / 100f * sweep,
                    3f * k, 0x33FFFFFF, ink);
            text.setColor(0xFFFFFFFF);
            c.drawText(percent == AirPodsAdvert.UNKNOWN ? "-" : String.valueOf(percent), cx, cy + 3.4f * k, text);
            if (charging) {
                ink.setColor(0xFF30D158);
                Glyphs.bolt(c, cx + 14 * k, cy - 14 * k, 9 * k, ink);
            }
            c.drawText(name, cx, cy + 30 * k, small);
        }
    }

    // ---- volume -------------------------------------------------------------------------------------------------

    private static final class VolumeBar extends View {
        private final AudioManager am;
        private final Sounds sounds;
        private final float k;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        VolumeBar(Context c, Sounds sounds, float k) {
            super(c);
            this.sounds = sounds;
            this.k = k;
            am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            setFocusable(true);
        }

        @Override
        public boolean onKeyDown(int code, KeyEvent e) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int next = Math.max(0, Math.min(max, v + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                if (next != v) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                    sounds.play(Sounds.FOCUS);
                    invalidate();
                }
                return true;
            }
            return super.onKeyDown(code, e);
        }

        @Override
        protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = ControlCenterView.TILE_RADIUS * k;
            boolean focused = isFocused();
            box.set(0, 0, w, h);
            fill.setColor(focused ? 0xF5FFFFFF : 0x2EFFFFFF);
            canvas.drawRoundRect(box, r, r, fill);
            ink.setColor(focused ? 0xFF1C1C1E : 0xFFFFFFFF);
            Glyphs.draw(canvas, Glyphs.SOUND, 26 * k, h / 2f, 20 * k, ink, false);
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            float level = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
            float x0 = 46 * k, x1 = w - 16 * k, th = 6 * k;
            box.set(x0, h / 2f - th / 2f, x1, h / 2f + th / 2f);
            fill.setColor(focused ? 0x331C1C1E : 0x40FFFFFF);
            canvas.drawRoundRect(box, th / 2f, th / 2f, fill);
            box.right = x0 + (x1 - x0) * level;
            fill.setColor(focused ? 0xFF1C1C1E : 0xFFFFFFFF);
            canvas.drawRoundRect(box, th / 2f, th / 2f, fill);
        }
    }
}
