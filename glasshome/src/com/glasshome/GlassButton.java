package com.glasshome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * A Control Center tile, in four shapes: big (icon above its label), wide (icon at the left, label beside it),
 * icon-only and row (just a label, for menus). At rest it is a light veil on the glass; focused it turns solid white with dark ink. Focus does not
 * scale it, so the panel's concentric corners (panel radius = tile radius + padding) hold at rest and in focus.
 */
final class GlassButton extends View {
    static final int BIG = 0, WIDE = 1, ICON = 2, ROW = 3;

    private final int shape;
    private int glyph;
    private final float radius;
    private final float k;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String label;
    private String sub;
    private boolean off;      // for the sounds icon: drawn with a slash
    private float iconDp = 21;

    GlassButton(Context context, int shape, int glyph, String label, String sub, float radiusPx, float density) {
        super(context);
        this.shape = shape;
        this.glyph = glyph;
        this.label = label;
        this.sub = sub;
        this.radius = radiusPx;
        this.k = density;
        setFocusable(true);
        setClickable(true);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, density * 0.75f));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(10.5f * density);
        subText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        subText.setTextSize(9f * density);
    }

    void setLabel(String label, String sub) {
        this.label = label;
        this.sub = sub;
        invalidate();
    }

    /** Icon size in dp for the icon-only and wide shapes (default 21). */
    void setIconDp(float dp) {
        iconDp = dp;
        invalidate();
    }

    void setGlyph(int glyph) {
        if (this.glyph != glyph) {
            this.glyph = glyph;
            invalidate();
        }
    }

    void setOff(boolean off) {
        this.off = off;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        boolean focused = isFocused();
        box.set(0, 0, w, h);
        fill.setColor(focused ? 0xF5FFFFFF : 0x2EFFFFFF);
        canvas.drawRoundRect(box, radius, radius, fill);
        if (!focused) {
            rim.setColor(0x33FFFFFF);
            float in = rim.getStrokeWidth() / 2f;
            box.inset(in, in);
            canvas.drawRoundRect(box, radius - in, radius - in, rim);
        }
        int color = focused ? 0xFF1C1C1E : 0xFFFFFFFF;
        ink.setColor(color);
        text.setColor(color);
        subText.setColor(focused ? 0xB31C1C1E : 0xB3FFFFFF);

        if (shape == BIG) {
            Glyphs.draw(canvas, glyph, w / 2f, h * 0.40f, 49 * k, ink, off);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, w / 2f, h * 0.84f, text);
        } else if (shape == ROW) {
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(label, 14 * k, h / 2f + text.getTextSize() * 0.35f, text);
        } else if (shape == ICON) {
            Glyphs.draw(canvas, glyph, w / 2f, h / 2f, iconDp * k, ink, off);
        } else {
            float size = 21 * k;
            Glyphs.draw(canvas, glyph, 13 * k + size / 2f, h / 2f, size, ink, off);
            text.setTextAlign(Paint.Align.LEFT);
            subText.setTextAlign(Paint.Align.LEFT);
            float x = 43 * k;
            if (sub == null || sub.isEmpty()) {
                canvas.drawText(label, x, h / 2f + text.getTextSize() * 0.35f, text);
            } else {
                canvas.drawText(label, x, h / 2f - 1.5f * k, text);
                canvas.drawText(ellipsize(sub, w - x - 8 * k), x, h / 2f + 1.5f * k + subText.getTextSize() * 0.8f, subText);
            }
        }
    }

    private String ellipsize(String s, float width) {
        if (subText.measureText(s) <= width) {
            return s;
        }
        int n = s.length();
        while (n > 1 && subText.measureText(s, 0, n) + subText.measureText("…") > width) {
            n--;
        }
        return s.substring(0, n) + "…";
    }
}
