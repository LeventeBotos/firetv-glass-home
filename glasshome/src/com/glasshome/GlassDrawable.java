package com.glasshome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Fake Liquid Glass for Android 7 (the Liquid Glass libraries need API 33). The look, taken from zooming into Apple's
 * own dock over a colourful background:
 *
 *  1. mostly transparent: the backdrop behind the panel, blurred and pushed to high saturation,
 *  2. a very light adaptive tint: a faint white lift over dark areas, a faint darkening over bright ones,
 *  3. a soft band of brighter, backdrop-coloured light just inside the edge (the lens effect), fading inward,
 *  4. a thin specular rim, brightest at the top left,
 *  5. nothing else. No coloured fringe, no hard inner rings.
 *
 * Everything is drawn with rounded rects filled by a bitmap shader: no clipPath, which is slow and not anti-aliased
 * on this GPU.
 */
public class GlassDrawable extends Drawable {
    private final View host;
    private final GradientBackdrop backdrop;
    private final float radius;
    private final float rimWidth;
    private final float band;                    // width of the lens band along the inside of the edge
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint darken = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final RectF srcF = new RectF();
    private final RectF dstF = new RectF();
    private final RectF ring = new RectF();
    private final Rect src = new Rect();
    private final int[] where = new int[2];
    private BitmapShader blurShader;

    public GlassDrawable(View host, GradientBackdrop backdrop, float radiusPx, boolean light) {
        this.host = host;
        this.backdrop = backdrop;
        this.radius = radiusPx;
        this.rimWidth = Math.max(2f, radiusPx / 22f);
        this.band = radiusPx * 0.42f;

        ColorMatrix vivid = new ColorMatrix();
        vivid.setSaturation(1.3f);
        fill.setColorFilter(new ColorMatrixColorFilter(vivid));

        // the lens band is the same backdrop, lifted and even more saturated: light gathered at the edge
        ColorMatrix lift = new ColorMatrix();
        lift.setSaturation(1.45f);
        lift.postConcat(new ColorMatrix(new float[]{
                1, 0, 0, 0, 28,
                0, 1, 0, 0, 28,
                0, 0, 1, 0, 28,
                0, 0, 0, 1, 0}));
        lens.setColorFilter(new ColorMatrixColorFilter(lift));
        lens.setStyle(Paint.Style.STROKE);

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(rimWidth);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        // specular rim: bright at the top left, faint along the sides, a little light again at the bottom right
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xE0FFFFFF, 0x40FFFFFF, 0x2EFFFFFF, 0x70FFFFFF}, new float[]{0f, 0.3f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        Bitmap blur = backdrop.blurred();
        host.getLocationInWindow(where);
        int sw = Math.max(1, backdrop.getWidth()), sh = Math.max(1, backdrop.getHeight());
        int bw = blur.getWidth(), bh = blur.getHeight();
        // the part of the blurred bitmap that sits behind this panel
        src.set(clamp(where[0] * bw / sw, 0, bw - 1), clamp(where[1] * bh / sh, 0, bh - 1),
                clamp((where[0] + b.width()) * bw / sw + 1, 1, bw), clamp((where[1] + b.height()) * bh / sh + 1, 1, bh));
        srcF.set(src);
        dstF.set(b);
        if (blurShader == null) {
            blurShader = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            fill.setShader(blurShader);
            lens.setShader(blurShader);
        }
        shaderMatrix.setRectToRect(srcF, dstF, Matrix.ScaleToFit.FILL);
        blurShader.setLocalMatrix(shaderMatrix);

        // tint adapts both ways, as tvOS does: over a dark backdrop the glass lifts a little, over a bright one it dims
        int mid = blur.getPixel(clamp((src.left + src.right) / 2, 0, bw - 1), clamp((src.top + src.bottom) / 2, 0, bh - 1));
        float lum = (0.299f * Color.red(mid) + 0.587f * Color.green(mid) + 0.114f * Color.blue(mid)) / 255f;
        darken.setColor(Color.argb((int) (255 * 0.26f * lum), 0, 0, 0));
        veil.setColor(Color.argb((int) (255 * (0.03f + 0.07f * (1f - lum))), 255, 255, 255));

        canvas.drawRoundRect(dstF, radius, radius, fill);
        canvas.drawRoundRect(dstF, radius, radius, darken);
        canvas.drawRoundRect(dstF, radius, radius, veil);

        // lens band: three strokes hugging the edge, wide and faint to narrow and stronger, so the light fades inward
        for (int i = 3; i >= 1; i--) {
            float w = band * i / 3f;
            lens.setStrokeWidth(w);
            lens.setAlpha(i == 3 ? 30 : i == 2 ? 44 : 62);
            float in = rimWidth * 0.5f + w / 2f;
            ring.set(dstF.left + in, dstF.top + in, dstF.right - in, dstF.bottom - in);
            float r = Math.max(0f, radius - in);
            canvas.drawRoundRect(ring, r, r, lens);
        }

        ring.set(dstF.left + rimWidth / 2f, dstF.top + rimWidth / 2f, dstF.right - rimWidth / 2f, dstF.bottom - rimWidth / 2f);
        canvas.drawRoundRect(ring, radius - rimWidth / 2f, radius - rimWidth / 2f, rim);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
