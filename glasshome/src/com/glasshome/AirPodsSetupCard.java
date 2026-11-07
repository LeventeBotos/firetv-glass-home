package com.glasshome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * "Set Up AirPods": rises from the bottom when AirPods in pairing mode are near the TV and none are paired yet (the
 * iPhone shows the same kind of card when you open the case beside it). A drawn case opens its lid and the buds lift
 * out while the card settles. Only appears over Home; elsewhere the banner and auto-connect do the work.
 */
final class AirPodsSetupCard extends FrameLayout {
    private static final float W = 340, H = 168;

    private final FrameLayout card;
    private final Sounds sounds;
    private final Runnable onConnect, onDismiss;
    private final TextView title, body;
    private final GlassButton connect, later;
    private final CaseArt art;
    private final float k;
    private boolean open;

    AirPodsSetupCard(Context context, GradientBackdrop backdrop, Sounds sounds, Runnable onConnect, Runnable onDismiss) {
        super(context);
        this.sounds = sounds;
        this.onConnect = onConnect;
        this.onDismiss = onDismiss;
        k = context.getResources().getDisplayMetrics().density;
        setVisibility(GONE);

        card = new FrameLayout(context);
        card.setBackground(new GlassDrawable(card, backdrop, (ControlCenterView.TILE_RADIUS + 14) * k, false));
        LayoutParams cp = new LayoutParams(Math.round(W * k), Math.round(H * k), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = Math.round(44 * k);
        addView(card, cp);

        art = new CaseArt(context, k);
        place(art, 14, 14, 112, H - 28);

        title = text(context, "Set Up AirPods", 17, "sans-serif-medium", 0xFFFFFFFF);
        place(title, 138, 18, W - 152, 24);
        body = text(context, "AirPods found nearby, in pairing mode.", 11.5f, "sans-serif", 0xB3FFFFFF);
        place(body, 138, 44, W - 152, 40);

        connect = new GlassButton(context, GlassButton.ROW, Glyphs.EARBUDS, "Connect", null, ControlCenterView.TILE_RADIUS * k, k);
        later = new GlassButton(context, GlassButton.ROW, Glyphs.EARBUDS, "Not Now", null, ControlCenterView.TILE_RADIUS * k, k);
        place(connect, 138, 92, 94, 44);
        place(later, 240, 92, 86, 44);
        connect.setOnClickListener(v -> {
            sounds.play(Sounds.SELECT);
            setStatus("Connecting…");
            onConnect.run();
        });
        later.setOnClickListener(v -> dismiss());
    }

    private TextView text(Context c, String s, float sp, String face, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(face, Typeface.NORMAL));
        return t;
    }

    private void place(View v, float x, float y, float w, float h) {
        LayoutParams lp = new LayoutParams(Math.round(w * k), Math.round(h * k));
        lp.leftMargin = Math.round(x * k);
        lp.topMargin = Math.round(y * k);
        card.addView(v, lp);
    }

    boolean isOpen() {
        return open;
    }

    void setStatus(String text) {
        body.setText(text);
    }

    void show() {
        if (open) {
            return;
        }
        open = true;
        title.setText("Set Up AirPods");
        body.setText("AirPods found nearby, in pairing mode.");
        setVisibility(VISIBLE);
        card.setAlpha(0f);
        card.setTranslationY(60 * k);
        card.animate().alpha(1f).translationY(0f).withLayer()
                .setInterpolator(new OvershootInterpolator(0.9f)).setDuration(460).start();
        art.play();
        sounds.play(Sounds.SELECT);
        connect.requestFocus();
    }

    void dismiss() {
        if (!open) {
            return;
        }
        open = false;
        sounds.play(Sounds.BACK);
        card.animate().alpha(0f).translationY(40 * k).withLayer().setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> setVisibility(GONE)).start();
        onDismiss.run();
    }

    // ---- the case, lid opening, buds rising -----------------------------------------------------------------------

    private static final class CaseArt extends View {
        private final float k;
        private float openness;   // 0 closed, 1 open
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        CaseArt(Context c, float k) {
            super(c);
            this.k = k;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f * k);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(0xFFFFFFFF);
            body.setColor(0x26FFFFFF);
        }

        void play() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setStartDelay(380);
            a.setDuration(700);
            a.setInterpolator(new DecelerateInterpolator(2f));
            a.addUpdateListener(v -> {
                openness = (Float) v.getAnimatedValue();
                invalidate();
            });
            openness = 0f;
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cw = Math.min(w * 0.82f, h * 0.9f), ch = cw * 0.62f;
            float left = (w - cw) / 2f, bottom = h * 0.9f, top = bottom - ch;
            // buds first, so the case front covers their lower half: they rise out as the lid opens
            float lift = openness * ch * 0.55f;
            for (int s = -1; s <= 1; s += 2) {
                float bx = w / 2f + s * cw * 0.2f;
                r.set(bx - cw * 0.11f, top - lift, bx + cw * 0.11f, top - lift + cw * 0.2f);
                line.setAlpha(Math.round(255 * Math.min(1f, openness * 2f)));
                c.drawOval(r, line);
                c.drawLine(bx + s * cw * 0.1f, top - lift + cw * 0.12f, bx + s * cw * 0.1f, top - lift + cw * 0.46f, line);
            }
            line.setAlpha(255);
            // body
            r.set(left, top, left + cw, bottom);
            c.drawRoundRect(r, ch * 0.3f, ch * 0.3f, body);
            c.drawRoundRect(r, ch * 0.3f, ch * 0.3f, line);
            // lid: hinged at the back-left, swings up and away
            c.save();
            c.rotate(-58f * openness, left + cw * 0.05f, top);
            r.set(left, top - ch * 0.42f, left + cw, top + ch * 0.06f);
            c.drawRoundRect(r, ch * 0.2f, ch * 0.2f, line);
            c.restore();
        }
    }
}
