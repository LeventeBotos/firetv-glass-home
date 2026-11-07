package com.glasshome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * One app icon, 5:3 like a tvOS icon: its banner cropped to fill a rounded rectangle.
 *
 * Two things here are deliberate, both for the Fire TV Stick's GPU:
 *  - no native elevation shadow (it is rebuilt on every frame of a scale animation); HomeActivity draws a shadow image;
 *  - no clipPath for the rounded corners (hardware clipping is not anti-aliased, so edges came out jagged). The
 *    banner is rendered once into a bitmap, a little larger than the tile so it stays sharp when it grows to 1.2x,
 *    and drawn as a rounded rect filled by that bitmap, which the GPU anti-aliases properly.
 */
public class AppTile extends View {
    private static final float OVERSAMPLE = 1.3f;

    final String label;
    String id;          // the catalog id: a package name or a special id
    int kind;           // AppCatalog.APP, SETTINGS, ...
    final Runnable launch;
    View shadow;        // soft shadow image behind this tile, owned by the row (see HomeActivity)
    View rowView;       // the row this tile sits in, for placing the shadow
    private final Drawable art;
    private final float corner;
    private final int plainColor;
    private final boolean contain;
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix faceMatrix = new Matrix();
    private final RectF box = new RectF();
    private Bitmap face;

    public AppTile(Context c, String label, Drawable art, int plainColor, boolean contain, float cornerPx, Runnable launch) {
        super(c);
        this.label = label;
        this.art = art;
        this.corner = cornerPx;
        this.plainColor = plainColor;
        this.contain = contain;
        this.launch = launch;
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> this.launch.run());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int fw = Math.round(w * OVERSAMPLE), fh = Math.round(h * OVERSAMPLE);
        face = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(face);
        c.drawColor(plainColor);
        if (art != null && art.getIntrinsicWidth() > 0) {
            // a banner is centre-cropped to fill the tile; a square app icon is shown whole, with room around it
            float iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
            float s = contain ? Math.min(fw / iw, fh / ih) * 0.72f : Math.max(fw / iw, fh / ih);
            c.save();
            c.translate((fw - iw * s) / 2f, (fh - ih * s) / 2f);
            c.scale(s, s);
            art.setBounds(0, 0, (int) iw, (int) ih);
            art.draw(c);
            c.restore();   // the banner's scale must not leak into the highlight drawn next
        } else {
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(fh * 0.2f);
            c.drawText(label, fw / 2f, fh / 2f + text.getTextSize() / 3f, text);
        }
        // light catching the top edge, as on Apple's own icons: a thin bright line that fades by a third of the way down
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f * OVERSAMPLE);
        edge.setShader(new LinearGradient(0, 0, 0, fh * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        float cr = corner * OVERSAMPLE, hw = edge.getStrokeWidth() / 2f;
        c.drawRoundRect(new RectF(hw, hw, fw - hw, fh - hw), cr - hw, cr - hw, edge);

        BitmapShader shader = new BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        faceMatrix.setScale(1f / OVERSAMPLE, 1f / OVERSAMPLE);
        shader.setLocalMatrix(faceMatrix);
        facePaint.setShader(shader);
        box.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (face != null) {
            canvas.drawRoundRect(box, corner, corner, facePaint);
        }
    }
}
