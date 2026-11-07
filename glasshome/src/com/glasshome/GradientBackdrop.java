package com.glasshome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * The hero area: one accent colour (the focused app's own) as two irregular, ink-splatter blobs sitting in the
 * bottom corners, together spanning the full width of the bottom of the screen but capped well short of the
 * top. Each blob is a closed {@link Path} through a ring of randomised (but fixed-seed, so stable frame to
 * frame) radii, not a circle or a smooth teardrop, plus a few small stray "droplets" scattered around its rim
 * - then the whole thing is run through a {@link BlurMaskFilter} (a real blur of the alpha mask) so the jagged
 * polygon reads as a soft organic splash instead of a jagged polygon. Along its radius it sweeps through a few
 * related hues, not one flat colour: a pale "hot" tone at the centre, into the full accent colour, through a
 * hue-shifted tone, fading to transparent at the rim. Two passes per corner (a wide dim spread, a smaller
 * brighter core, each its own hue lean) are the "layers". Both corners drift very slowly so the shape never
 * looks static without ever looking busy. It is rendered into a small bitmap (the scale-up is what keeps it
 * smooth and cheap on a Fire TV Stick) and a much smaller copy of that bitmap serves as the "blurred
 * background" that every GlassDrawable samples. There is no real-time blur on Android 7 for the UI itself, but
 * we know exactly what is behind every panel, so glass can be faked exactly.
 */
public class GradientBackdrop extends View {
    private static final int W = 192, H = 108;       // render size, scaled up to the screen
    private static final int BW = 24, BH = 14;       // "blurred" copy: scaled-down, then scaled-up with filtering
    private static final int FRAME_MS = 66;          // about 15 fps; the drift is slow, so nobody can tell
    private static final int BLOB_POINTS = 14;       // vertices around each splatter's rim
    private static final int DROPLETS = 3;           // small stray blobs scattered around each splatter

    // two layers per corner: a wide, dim spread underneath a smaller, brighter core, each a slightly different
    // hue. Wide/short on purpose: together the two corners span the bottom edge but stay well clear of the top.
    private static final float[] RX = {W * 0.58f, W * 0.36f};
    private static final float[] RY = {H * 0.52f, H * 0.33f};
    private static final float[] BLUR = {H * 0.12f, H * 0.065f};
    private static final float[] HUE_SHIFT = {18f, -12f};   // each layer leans a little towards a neighbouring hue

    private final Bitmap sharp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Bitmap blur = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
    private final Canvas sharpCanvas = new Canvas(sharp);
    private final Canvas blurCanvas = new Canvas(blur);
    private final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect screen = new Rect();
    private final Rect sharpRect = new Rect(0, 0, W, H);
    private final Rect blurRect = new Rect(0, 0, BW, BH);
    private final Path blobPath = new Path();
    private final Paint[] blobPaint = {streakPaint(BLUR[0]), streakPaint(BLUR[1])};
    private final Paint dropletPaint = streakPaint(H * 0.05f);
    // each corner's splatter silhouette and droplets: randomised once with a fixed seed, so the shape itself
    // never reshapes - only its position and rotation drift, which is what keeps "random" from reading as "busy"
    private final float[][] rimRadius = new float[2][BLOB_POINTS];
    private final float[][] dropletAngle = new float[2][DROPLETS];
    private final float[][] dropletDist = new float[2][DROPLETS];
    private final float[][] dropletRadius = new float[2][DROPLETS];
    // a faint stippled grain, tiled over the final frame; it also dithers away the gradient's banding "lines"
    private final Paint noisePaint = makeNoisePaint();
    private final int[] from = new int[2];
    private final int[] to = new int[2];
    private final int[] now = new int[2];   // index 0 is the base (black), index 1 is the shared accent
    private final ArgbEvaluator mix = new ArgbEvaluator();
    private long blendStart;
    private float speed = 1f;
    private boolean running;

    public GradientBackdrop(Context context) {
        super(context);
        genSplatter(0, new Random(11));
        genSplatter(1, new Random(22));
        setAccentColor(Palettes.INDIGO[1], false);
    }

    /** Slow factor for the screensaver, which wants the same look drifting about 4x slower. */
    public void setSpeed(float speed) {
        this.speed = speed;
    }

    /** Eases both corner splatters to a new shared accent colour over 800 ms, on the fixed black base. */
    public void setAccentColor(int color, boolean animate) {
        from[0] = now[0];
        from[1] = now[1];
        to[0] = Color.BLACK;
        to[1] = color;
        if (!animate) {
            now[0] = to[0];
            now[1] = to[1];
        }
        blendStart = SystemClock.uptimeMillis();
    }

    /** Builds one corner's fixed rim-radius ring and droplet scatter. A plain per-vertex random looks spiky, so
     * the raw values are smoothed (a circular moving average, once) into irregular lumps - a splatter, not a
     * star, but with enough swing left in it (and a light enough blur, see {@link #BLUR}) to still read as
     * distinctly not a circle once drawn. */
    private void genSplatter(int corner, Random rnd) {
        float[] raw = new float[BLOB_POINTS];
        for (int i = 0; i < BLOB_POINTS; i++) {
            raw[i] = 0.55f + rnd.nextFloat() * 1.0f;
        }
        float[] dst = rimRadius[corner];
        for (int i = 0; i < BLOB_POINTS; i++) {
            float prev = raw[(i - 1 + BLOB_POINTS) % BLOB_POINTS];
            float next = raw[(i + 1) % BLOB_POINTS];
            dst[i] = (prev + raw[i] + next) / 3f;
        }
        for (int i = 0; i < DROPLETS; i++) {
            dropletAngle[corner][i] = rnd.nextFloat() * (float) (2 * Math.PI);
            dropletDist[corner][i] = 0.8f + rnd.nextFloat() * 0.35f;   // close to the core's own rim, not loose
            dropletRadius[corner][i] = 0.07f + rnd.nextFloat() * 0.09f;
        }
    }

    private static Paint streakPaint(float blurRadius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL));
        return p;
    }

    /** A small tileable stipple texture (sparse black/white dots, like halftone grain), generated once with a
     * fixed seed so it never flickers between frames; kept faint so it reads as film grain. */
    private static Paint makeNoisePaint() {
        int n = 80;
        Bitmap grain = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        int[] px = new int[n * n];
        Random rnd = new Random(1);
        for (int i = 0; i < px.length; i++) {
            if (rnd.nextFloat() < 0.14f) {
                int gray = rnd.nextBoolean() ? 255 : 0;
                int alpha = 20 + rnd.nextInt(35);
                px[i] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
            }
        }
        grain.setPixels(px, 0, n, 0, 0, n, n);
        BitmapShader shader = new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Paint paint = new Paint();
        paint.setShader(shader);
        paint.setAlpha(110);
        return paint;
    }

    /** The blurred copy GlassDrawable samples; same aspect as the screen. */
    Bitmap blurred() {
        return blur;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long t = SystemClock.uptimeMillis();
        float k = Math.min(1f, (t - blendStart) / 800f);
        if (k < 1f) {
            now[0] = (Integer) mix.evaluate(k, from[0], to[0]);
            now[1] = (Integer) mix.evaluate(k, from[1], to[1]);
        }
        sharpCanvas.drawColor(now[0], PorterDuff.Mode.SRC);

        double s = t / 1000.0 * speed;
        for (int corner = 0; corner < 2; corner++) {
            // anchored at a bottom corner, drifting a little - slowly - in position and tilt
            boolean left = corner == 0;
            float cx = W * (left ? 0f : 1f) + (float) (W * 0.045 * Math.sin(s * 0.022 + corner * 2.3));
            float cy = (float) (H * (1.0 + 0.04 * Math.sin(s * 0.018 + corner * 1.1)));
            float rotation = (float) (7 * Math.sin(s * 0.015 + corner * 1.7));
            for (int layer = 0; layer < RX.length; layer++) {
                drawBlob(now[1], cx, cy, rotation, corner, layer);
            }
            drawDroplets(now[1], cx, cy, rotation, corner);
        }
        blurCanvas.drawBitmap(sharp, sharpRect, blurRect, scalePaint);

        screen.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(sharp, sharpRect, screen, scalePaint);
        canvas.drawRect(screen, noisePaint);
        if (running) {
            postInvalidateDelayed(FRAME_MS);
        }
    }

    /** One splatter: a pale "hot" tone at its centre, the full accent colour just past it, a hue-shifted tone
     * further out, fading to transparent at the rim - a colour sweep, not one flat hue. The blur on `paint`
     * (see {@link #streakPaint}) is what turns the jagged rim into a soft, organic splash. */
    private void drawBlob(int color, float cx, float cy, float rotationDeg, int corner, int layer) {
        float rx = RX[layer], ry = RY[layer];
        Paint paint = blobPaint[layer];
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float hue = hsv[0], sat = hsv[1], val = hsv[2];
        int hot = Color.HSVToColor(255, new float[]{hue, Math.max(0.15f, sat * 0.3f), Math.min(1f, val * 1.3f + 0.3f)});
        int mid = Color.HSVToColor(235, new float[]{hue, sat, val});
        float shifted = (hue + HUE_SHIFT[layer] + 360f) % 360f;
        int sub = Color.HSVToColor(160, new float[]{shifted, Math.min(1f, sat * 1.05f), val * 0.72f});
        int edge = Color.HSVToColor(0, new float[]{shifted, sat, val * 0.4f});
        paint.setShader(new RadialGradient(0, 0, Math.max(rx, ry) * 1.1f, new int[]{hot, mid, sub, edge},
                new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP));

        float[] rim = rimRadius[corner];
        blobPath.reset();
        for (int i = 0; i < BLOB_POINTS; i++) {
            double angle = i * 2 * Math.PI / BLOB_POINTS;
            float r = rim[i];
            float px = (float) Math.cos(angle) * rx * r;
            float py = (float) Math.sin(angle) * ry * r;
            if (i == 0) {
                blobPath.moveTo(px, py);
            } else {
                blobPath.lineTo(px, py);
            }
        }
        blobPath.close();

        sharpCanvas.save();
        sharpCanvas.translate(cx, cy);
        sharpCanvas.rotate(rotationDeg);
        sharpCanvas.drawPath(blobPath, paint);
        sharpCanvas.restore();
    }

    /** A few small, stray blobs scattered around the main splatter's rim, same accent colour - the bits that
     * "flew off" the main splash, the detail that sells the effect as a splatter rather than a single blob. */
    private void drawDroplets(int color, float cx, float cy, float rotationDeg, int corner) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        int mid = Color.HSVToColor(190, new float[]{hsv[0], hsv[1], hsv[2]});
        dropletPaint.setColor(mid);
        double rad = Math.toRadians(rotationDeg);
        float cosR = (float) Math.cos(rad), sinR = (float) Math.sin(rad);
        for (int i = 0; i < DROPLETS; i++) {
            double angle = dropletAngle[corner][i];
            float dist = dropletDist[corner][i];
            // scattered around the smaller core layer's rim, so they read as flecks beside the splash, not loose
            float lx = (float) Math.cos(angle) * RX[1] * dist;
            float ly = (float) Math.sin(angle) * RY[1] * dist;
            // rotate the droplet offset by the same tilt as the blob it belongs to
            float dx = lx * cosR - ly * sinR;
            float dy = lx * sinR + ly * cosR;
            float r = dropletRadius[corner][i] * Math.min(RX[1], RY[1]);
            sharpCanvas.drawCircle(cx + dx, cy + dy, r, dropletPaint);
        }
    }

    /** Fallback accent colour, used before any app icon has loaded. */
    public static final class Palettes {
        public static final int[] INDIGO = {0xFF0B0B1E, 0xFF4B3FD1};

        private Palettes() {
        }
    }
}
