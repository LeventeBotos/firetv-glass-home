package com.glasshome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Line icons for Control Center, drawn from paths so there is nothing to bundle and they stay sharp at any size.
 * They are our own drawings, not Apple's symbols. Each is laid out on a 24-unit square centred on (cx, cy).
 */
final class Glyphs {
    static final int POWER = 0, WIFI = 1, SCREEN = 2, AUDIO = 3, TIMER = 4, AIRPLAY = 5, SOUND = 6, ACCESS = 7,
            GEAR = 8, TOGGLES = 9, EARBUDS = 10, CASE = 11;

    private static final Path path = new Path();
    private static final RectF box = new RectF();

    private Glyphs() {
    }

    /** paint must be a STROKE paint with round caps; its colour is the icon's colour. size = the icon's width in px. */
    static void draw(Canvas c, int kind, float cx, float cy, float size, Paint paint, boolean off) {
        float u = size / 24f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.6f, u * 1.9f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case POWER:
                box.set(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u);
                c.drawArc(box, -50, 280, false, paint);
                c.drawLine(cx, cy - 11 * u, cx, cy - 2 * u, paint);
                break;
            case WIFI:
                for (int i = 1; i <= 3; i++) {
                    float r = 4.2f * i * u;
                    box.set(cx - r, cy + 6 * u - r, cx + r, cy + 6 * u + r);
                    c.drawArc(box, -135, 90, false, paint);
                }
                dot(c, cx, cy + 6.5f * u, 1.5f * u, paint);
                break;
            case SCREEN:
                box.set(cx - 10 * u, cy - 8 * u, cx + 10 * u, cy + 5 * u);
                c.drawRoundRect(box, 2.5f * u, 2.5f * u, paint);
                c.drawLine(cx, cy + 5 * u, cx, cy + 9 * u, paint);
                c.drawLine(cx - 5 * u, cy + 9 * u, cx + 5 * u, cy + 9 * u, paint);
                break;
            case AUDIO:
                speaker(c, cx - 3 * u, cy, u, paint);
                for (int i = 1; i <= 2; i++) {
                    float r = 4.5f * i * u;
                    box.set(cx + 1 * u - r, cy - r, cx + 1 * u + r, cy + r);
                    c.drawArc(box, -45, 90, false, paint);
                }
                break;
            case TIMER:
                box.set(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u);
                c.drawOval(box, paint);
                c.drawLine(cx, cy, cx + 4 * u, cy - 4 * u, paint);
                c.drawLine(cx, cy - 9 * u, cx, cy - 11.5f * u, paint);
                break;
            case AIRPLAY:
                path.reset();
                path.moveTo(cx - 5 * u, cy + 5 * u);
                path.lineTo(cx - 10 * u, cy + 5 * u);
                path.lineTo(cx - 10 * u, cy - 9 * u);
                path.lineTo(cx + 10 * u, cy - 9 * u);
                path.lineTo(cx + 10 * u, cy + 5 * u);
                path.lineTo(cx + 5 * u, cy + 5 * u);
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(cx, cy + 2 * u);
                path.lineTo(cx - 6 * u, cy + 10 * u);
                path.lineTo(cx + 6 * u, cy + 10 * u);
                path.close();
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                c.drawPath(path, paint);
                break;
            case SOUND:
                speaker(c, cx - 2 * u, cy, u, paint);
                box.set(cx + 2 * u - 5 * u, cy - 5 * u, cx + 2 * u + 5 * u, cy + 5 * u);
                c.drawArc(box, -45, 90, false, paint);
                if (off) {
                    c.drawLine(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 9 * u, paint);
                }
                break;
            case ACCESS:
                box.set(cx - 10 * u, cy - 10 * u, cx + 10 * u, cy + 10 * u);
                c.drawOval(box, paint);
                dot(c, cx, cy - 5 * u, 1.6f * u, paint);
                c.drawLine(cx - 5 * u, cy - 2 * u, cx + 5 * u, cy - 2 * u, paint);
                c.drawLine(cx, cy - 2 * u, cx, cy + 2 * u, paint);
                c.drawLine(cx, cy + 2 * u, cx - 3 * u, cy + 7 * u, paint);
                c.drawLine(cx, cy + 2 * u, cx + 3 * u, cy + 7 * u, paint);
                break;
            case GEAR:
                box.set(cx - 6 * u, cy - 6 * u, cx + 6 * u, cy + 6 * u);
                c.drawOval(box, paint);
                box.set(cx - 2.5f * u, cy - 2.5f * u, cx + 2.5f * u, cy + 2.5f * u);
                c.drawOval(box, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * 6.5f * u, cy + (float) Math.sin(a) * 6.5f * u,
                            cx + (float) Math.cos(a) * 9.5f * u, cy + (float) Math.sin(a) * 9.5f * u, paint);
                }
                break;
            case TOGGLES:
                box.set(cx - 8 * u, cy - 7 * u, cx + 8 * u, cy - 1 * u);
                c.drawRoundRect(box, 3 * u, 3 * u, paint);
                dot(c, cx + 4.5f * u, cy - 4 * u, 1.6f * u, paint);
                box.set(cx - 8 * u, cy + 1 * u, cx + 8 * u, cy + 7 * u);
                c.drawRoundRect(box, 3 * u, 3 * u, paint);
                dot(c, cx - 4.5f * u, cy + 4 * u, 1.6f * u, paint);
                break;
            case EARBUDS:
                // two buds, each a round head with a stem on its outer side (our own drawing)
                for (int s = -1; s <= 1; s += 2) {
                    box.set(cx + s * 5.5f * u - 4 * u, cy - 8.5f * u, cx + s * 5.5f * u + 4 * u, cy - 0.5f * u);
                    c.drawOval(box, paint);
                    c.drawLine(cx + s * 8.6f * u, cy - 3 * u, cx + s * 8.6f * u, cy + 9 * u, paint);
                }
                break;
            case CASE:
                box.set(cx - 9 * u, cy - 6 * u, cx + 9 * u, cy + 8 * u);
                c.drawRoundRect(box, 5 * u, 5 * u, paint);
                c.drawLine(cx - 9 * u, cy - 0.5f * u, cx + 9 * u, cy - 0.5f * u, paint);
                dot(c, cx, cy + 3 * u, 1.2f * u, paint);
                break;
            default:
                break;
        }
    }

    /** A battery ring: a faint track and a sweep of `fraction` (0-1) from the top. paint's colour is the sweep colour. */
    static void ring(Canvas c, float cx, float cy, float radius, float fraction, float stroke, int track, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        int sweepColor = paint.getColor();
        box.set(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setColor(track);
        c.drawOval(box, paint);
        paint.setColor(sweepColor);
        if (fraction > 0f) {
            c.drawArc(box, -90, 360f * Math.min(1f, fraction), false, paint);
        }
    }

    /** A small lightning bolt for "charging", centred on (cx, cy), about `size` px tall. */
    static void bolt(Canvas c, float cx, float cy, float size, Paint paint) {
        float u = size / 10f;
        path.reset();
        path.moveTo(cx + 1.5f * u, cy - 5 * u);
        path.lineTo(cx - 2.5f * u, cy + 0.5f * u);
        path.lineTo(cx - 0.2f * u, cy + 0.5f * u);
        path.lineTo(cx - 1.5f * u, cy + 5 * u);
        path.lineTo(cx + 2.5f * u, cy - 0.8f * u);
        path.lineTo(cx + 0.2f * u, cy - 0.8f * u);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        c.drawPath(path, paint);
    }

    private static void speaker(Canvas c, float cx, float cy, float u, Paint paint) {
        path.reset();
        path.moveTo(cx - 7 * u, cy - 3 * u);
        path.lineTo(cx - 3 * u, cy - 3 * u);
        path.lineTo(cx + 2 * u, cy - 8 * u);
        path.lineTo(cx + 2 * u, cy + 8 * u);
        path.lineTo(cx - 3 * u, cy + 3 * u);
        path.lineTo(cx - 7 * u, cy + 3 * u);
        path.close();
        c.drawPath(path, paint);
    }

    private static void dot(Canvas c, float x, float y, float r, Paint paint) {
        Paint.Style before = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, r, paint);
        paint.setStyle(before);
    }
}
