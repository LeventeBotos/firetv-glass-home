package com.glasshome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

/**
 * The "AirPods Connected" pill: slides down from the top-centre with a little overshoot, the earbuds pop in, and the
 * battery rings sweep up to their level. Drawn here rather than with GlassDrawable because it also appears over other
 * apps' video, where there is no backdrop to blur: a dark translucent pill with a thin rim reads on anything.
 *
 * Shown in a window of its own (TYPE_SYSTEM_ALERT, which install.sh allows) so it works over Netflix or YouTube; if
 * that is refused it falls back to a Toast carrying the same view, without the slide.
 */
final class AirPodsBanner extends View {
    static final int CONNECTED = 0, DISCONNECTED = 1, LOW = 2, INFO = 3;

    private static final long HOLD_MS = 3200;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable dismiss;
    private static AirPodsBanner showing;

    /** What to say. buds/box are percent or AirPodsAdvert.UNKNOWN. */
    static final class Content {
        int kind = CONNECTED;
        String title = "", subtitle = "";
        int buds = AirPodsAdvert.UNKNOWN, box = AirPodsAdvert.UNKNOWN;
        boolean chargingBuds, chargingBox;
    }

    private final Content content;
    private final float k;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float pop;     // 0-1: the earbuds popping in
    private float sweep;   // 0-1: the rings filling

    private AirPodsBanner(Context context, Content content) {
        super(context);
        this.content = content;
        k = context.getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, k * 0.75f));
        rim.setColor(0x40FFFFFF);
        fill.setColor(0xE61C1C1E);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextSize(13 * k);
        title.setColor(Color_WHITE);
        sub.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sub.setTextSize(10.5f * k);
        sub.setColor(0xB3FFFFFF);
    }

    private static final int Color_WHITE = 0xFFFFFFFF;

    float widthDp() {
        return hasRings() ? 300 : 232;
    }

    private boolean hasRings() {
        return content.kind != DISCONNECTED && (content.buds != AirPodsAdvert.UNKNOWN || content.box != AirPodsAdvert.UNKNOWN);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(widthDp() * k), Math.round(58 * k));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        box.set(0, 0, w, h);
        canvas.drawRoundRect(box, r, r, fill);
        float in = rim.getStrokeWidth() / 2f;
        box.inset(in, in);
        canvas.drawRoundRect(box, r - in, r - in, rim);

        // earbuds: scale and fade in
        float s = 0.6f + 0.4f * pop;
        ink.setColor((Math.round(255 * Math.min(1f, pop * 1.4f)) << 24) | 0xFFFFFF);
        canvas.save();
        canvas.scale(s, s, 34 * k, h / 2f);
        Glyphs.draw(canvas, Glyphs.EARBUDS, 34 * k, h / 2f, 30 * k, ink, content.kind == DISCONNECTED);
        canvas.restore();

        float x = 62 * k;
        title.setTextAlign(Paint.Align.LEFT);
        sub.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(content.title, x, h / 2f - 1.5f * k, title);
        canvas.drawText(content.subtitle, x, h / 2f + 13 * k, sub);

        if (hasRings()) {
            float rr = 12 * k;
            float cx = w - 28 * k;
            ringWithBolt(canvas, cx, h / 2f, rr, content.box, content.chargingBox, Glyphs.CASE);
            ringWithBolt(canvas, cx - 40 * k, h / 2f, rr, content.buds, content.chargingBuds, Glyphs.EARBUDS);
        }
    }

    private void ringWithBolt(Canvas c, float cx, float cy, float rr, int percent, boolean charging, int glyph) {
        if (percent == AirPodsAdvert.UNKNOWN) {
            return;
        }
        int color = charging ? 0xFF30D158 : (percent <= 20 ? 0xFFFF453A : 0xFFFFFFFF);
        ink.setColor(color);
        Glyphs.ring(c, cx, cy, rr, percent / 100f * sweep, 2.6f * k, 0x33FFFFFF, ink);
        ink.setColor(0xCCFFFFFF);
        Glyphs.draw(c, glyph, cx, cy, rr * 1.05f, ink, false);
        if (charging) {
            ink.setColor(0xFF30D158);
            Glyphs.bolt(c, cx + rr * 0.95f, cy - rr * 0.95f, rr * 0.9f, ink);
        }
    }

    private void play(boolean slide) {
        ValueAnimator p = ValueAnimator.ofFloat(0f, 1f);
        p.setDuration(380);
        p.setStartDelay(slide ? 160 : 0);
        p.setInterpolator(new OvershootInterpolator(2f));
        p.addUpdateListener(a -> {
            pop = (Float) a.getAnimatedValue();
            invalidate();
        });
        p.start();
        ValueAnimator sw = ValueAnimator.ofFloat(0f, 1f);
        sw.setDuration(650);
        sw.setStartDelay(slide ? 280 : 0);
        sw.setInterpolator(new DecelerateInterpolator(1.8f));
        sw.addUpdateListener(a -> {
            sweep = (Float) a.getAnimatedValue();
            invalidate();
        });
        sw.start();
        if (slide) {
            setAlpha(0f);
            setTranslationY(-28 * k);
            animate().alpha(1f).translationY(0f).setDuration(420)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
    }

    /** Show a banner over whatever is on screen, replacing the one already up. */
    static void show(Context context, Content content) {
        MAIN.post(() -> {
            Context app = context.getApplicationContext();
            hide(app, false);
            AirPodsBanner b = new AirPodsBanner(app, content);
            float k = app.getResources().getDisplayMetrics().density;
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    Math.round(b.widthDp() * k), Math.round(58 * k),
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(24 * k);
            try {
                wm.addView(b, lp);
                showing = b;
                b.play(true);
                dismiss = () -> hide(app, true);
                MAIN.postDelayed(dismiss, HOLD_MS);
            } catch (RuntimeException e) {
                // overlay not allowed: a toast carries the same view
                Toast t = new Toast(app);
                t.setView(b);
                t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, Math.round(24 * k));
                t.setDuration(Toast.LENGTH_SHORT);
                t.show();
                b.play(false);
            }
        });
    }

    private static void hide(Context app, boolean animate) {
        if (dismiss != null) {
            MAIN.removeCallbacks(dismiss);
            dismiss = null;
        }
        final AirPodsBanner b = showing;
        if (b == null) {
            return;
        }
        showing = null;
        final WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Runnable remove = () -> {
            try {
                wm.removeView(b);
            } catch (RuntimeException ignored) {
                // already gone
            }
        };
        if (animate) {
            b.animate().alpha(0f).translationY(-20 * b.k).setDuration(220).withEndAction(remove).start();
        } else {
            remove.run();
        }
    }
}
