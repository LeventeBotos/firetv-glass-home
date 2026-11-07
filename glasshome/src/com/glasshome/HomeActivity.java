package com.glasshome;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home, laid out from measurements of a real tvOS 26 photo (1080p): six 5:3 icons about 250x150 px with 45 px gaps
 * in a glass dock that spans 95% of the screen width and sits 81 px above the bottom; the next row peeks out 40 px
 * below the dock; the focused icon grows to about 1.2x.
 */
public class HomeActivity extends Activity {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    // "Fire TV Home" starts Amazon's home the way a Home press does, so the watcher stands down this long
    private static final long AMAZON_PAUSE_MS = 10 * 60 * 1000L;
    // dp values measured from Apple's tvOS at 1080p (density 2.0).
    private static final float TILE_W = 127, TILE_H = 76, GAP = 21.3f;
    private static final float FOCUS_SCALE = 1.2f;
    // Concentric corners hold for the FOCUSED icon, which is how Apple draws it: once the icon has grown, the gap to
    // the dock's edge is the same on every side, and the dock's outer radius = the grown icon's radius + that gap.
    // A 5:3 icon grows more sideways than up, so the padding at rest is bigger left and right than top and bottom.
    private static final float TILE_RADIUS = 15, FOCUS_GAP = 12;
    private static final float PAD_X = FOCUS_GAP + (FOCUS_SCALE - 1f) / 2f * TILE_W;
    private static final float PAD_Y = FOCUS_GAP + (FOCUS_SCALE - 1f) / 2f * TILE_H;
    private static final float DOCK_RADIUS = FOCUS_SCALE * TILE_RADIUS + FOCUS_GAP;
    private static final float DOCK_TOP = 392, ROW2_TOP = 520, ROW_PITCH = 108;
    private static final int COLUMNS = 6;
    private static final float SHADOW_PAD = 36;

    private GradientBackdrop backdrop;
    private FrameLayout stage;
    private View dim;
    private TextView caption;
    private ControlCenterView controlCenter;
    private GlassButton pillButton;
    private View returnFocus;
    private ContextMenuView contextMenu;
    private boolean relayouting;             // a relayout re-seats tiles: focus changes then apply instantly
    private boolean moving;                  // move mode: the D-pad moves the picked tile instead of focus
    private boolean ignoreCenterUp;          // the release of the long press that started a move is not a drop
    private String movingId;
    private List<String> moveSnapshot;
    private ObjectAnimator wobble;
    private LayoutStore store;
    private Sounds sounds;
    private float k;
    private boolean showHidden;
    private boolean quietFocus = true;       // programmatic focus (first draw, relayout) makes no sound
    private List<AppCatalog.Entry> entries = new ArrayList<>();
    private final List<AppTile> tiles = new ArrayList<>();
    private final Map<String, AppTile> cache = new HashMap<>();
    private final Map<String, Integer> iconColors = new HashMap<>();   // package id -> dominant colour of its icon/banner
    private boolean paletteSet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        k = getResources().getDisplayMetrics().density;   // the numbers above are dp: pixels = value * density
        startService(new Intent(this, HomeWatcher.class));
        store = new LayoutStore(this);
        sounds = new Sounds(this, store);

        FrameLayout root = new FrameLayout(this);
        backdrop = new GradientBackdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        root.setClipChildren(false);

        caption = new TextView(this);
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(15);
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.setAlpha(0f);

        layoutGrid(null);
        root.addView(statusPill(k), pillParams(k));
        controlCenter = new ControlCenterView(this, backdrop, sounds, this::controlCenterClosed);
        root.addView(controlCenter, new FrameLayout.LayoutParams(-1, -1));
        contextMenu = new ContextMenuView(this, backdrop, sounds, this::exitModal);
        root.addView(contextMenu, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    /**
     * (Re)build the rows from the catalog. Tile views are kept between rebuilds (their pictures are drawn once), so
     * moving, hiding and new installs only re-seat them. focusId says which tile gets focus afterwards.
     */
    private void layoutGrid(String focusId) {
        entries = AppCatalog.build(this, store, showHidden);
        layoutRows(focusId);
    }

    /** Seat the tiles of `entries` in rows. Moving a tile only reorders `entries` and calls this. */
    private void layoutRows(String focusId) {
        relayouting = true;
        for (AppTile t : cache.values()) {
            detach(t);
            detach(t.shadow);
        }
        stage.removeAllViews();
        tiles.clear();
        List<AppTile> built = new ArrayList<>();
        for (AppCatalog.Entry e : entries) {
            built.add(tileFor(e));
        }
        updateBackdropPalette();
        int rows = (built.size() + COLUMNS - 1) / COLUMNS;
        int shadowPad = Math.round(SHADOW_PAD * k);
        for (int r = 0; r < rows; r++) {
            // wrap = the glass dock (or bare row); shadows sit under the tiles, inside the wrap
            FrameLayout wrap = new FrameLayout(this);
            wrap.setClipChildren(false);
            wrap.setClipToPadding(false);   // the focused icon grows past the row's padding
            // every row is as wide as a full one, so short rows stay left-aligned under the dock like tvOS
            wrap.setMinimumWidth(Math.round((COLUMNS * TILE_W + (COLUMNS - 1) * GAP + 2 * PAD_X) * k));
            wrap.setPadding(Math.round(PAD_X * k), Math.round(PAD_Y * k), Math.round(PAD_X * k), Math.round(PAD_Y * k));
            if (r == 0) {
                wrap.setBackground(new GlassDrawable(wrap, backdrop, DOCK_RADIUS * k, false));
            }
            LinearLayout row = new LinearLayout(this);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            for (int c = 0; c < COLUMNS && r * COLUMNS + c < built.size(); c++) {
                AppTile t = built.get(r * COLUMNS + c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(TILE_W * k), Math.round(TILE_H * k));
                lp.setMargins(c == 0 ? 0 : Math.round(GAP * k), 0, 0, 0);
                // negative margins cancel the shadow's extra size, so it does not stretch the dock
                FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(lp.width + 2 * shadowPad, lp.height + 2 * shadowPad);
                sp.setMargins(-shadowPad, -shadowPad, -shadowPad, -shadowPad);
                t.shadow.setAlpha(0f);
                t.shadow.setScaleX(1f);
                t.shadow.setScaleY(1f);
                wrap.addView(t.shadow, sp);
                t.rowView = row;
                t.setScaleX(1f);
                t.setScaleY(1f);
                row.addView(t, lp);
                tiles.add(t);
            }
            wrap.addView(row, new FrameLayout.LayoutParams(-2, -2));
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            float top = r == 0 ? DOCK_TOP : ROW2_TOP + (r - 1) * ROW_PITCH - PAD_Y;
            rp.topMargin = Math.round(top * k);
            stage.addView(wrap, rp);
        }
        stage.addView(caption, new FrameLayout.LayoutParams(-2, -2));
        caption.setAlpha(0f);

        AppTile target = null;
        for (int i = 0; i < entries.size() && focusId != null; i++) {
            if (entries.get(i).id.equals(focusId)) {
                target = tiles.get(i);
            }
        }
        if (target == null && !tiles.isEmpty()) {
            target = tiles.get(0);
        }
        if (target != null) {
            quietFocus = true;
            target.requestFocus();
            final AppTile focused = target;
            focused.post(() -> {
                quietFocus = false;
                relayouting = false;
            });
        } else {
            relayouting = false;
        }
    }

    /** Seeds both corner glows from the first real app in the dock, on a black base. */
    private void updateBackdropPalette() {
        for (AppCatalog.Entry e : entries) {
            if (e.kind != AppCatalog.APP) {
                continue;
            }
            Integer c = iconColors.get(e.id);
            if (c != null && c != 0) {
                backdrop.setAccentColor(c, paletteSet);
                paletteSet = true;
                return;
            }
        }
    }

    /** Called when focus settles on a real app (not a relayout): both corner glows become its accent colour. */
    private void focusedApp(AppTile tile) {
        if (tile.kind != AppCatalog.APP) {
            return;
        }
        Integer c = iconColors.get(tile.id);
        if (c == null || c == 0) {
            return;
        }
        backdrop.setAccentColor(c, true);
    }

    /**
     * The icon's own accent colour: the average of its vivid (saturated, mid-brightness) pixels, so near-white and
     * near-black icon chrome (most app icons have plenty of both) doesn't wash the result out to grey.
     */
    private static int dominantColor(Drawable d) {
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) {
            return 0;
        }
        int n = 24;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        int[] px = new int[n * n];
        b.getPixels(px, 0, n, 0, 0, n, n);
        b.recycle();
        float[] hsv = new float[3];
        double sumR = 0, sumG = 0, sumB = 0, weight = 0;
        for (int p : px) {
            if (Color.alpha(p) < 200) {
                continue;
            }
            int r = Color.red(p), g = Color.green(p), bl = Color.blue(p);
            Color.RGBToHSV(r, g, bl, hsv);
            if (hsv[2] < 0.18f || hsv[2] > 0.97f || hsv[1] < 0.22f) {
                continue;   // near-black, near-white or near-grey: not the icon's accent colour
            }
            double w = hsv[1] * hsv[1];   // weight toward the most saturated pixels
            sumR += r * w;
            sumG += g * w;
            sumB += bl * w;
            weight += w;
        }
        if (weight < 1) {
            return 0;   // a flat/monochrome icon: let it be skipped rather than guess grey
        }
        int r = (int) Math.round(sumR / weight), g = (int) Math.round(sumG / weight), bl = (int) Math.round(sumB / weight);
        Color.RGBToHSV(r, g, bl, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.25f);           // push saturation up a bit, like tvOS's own wallpaper hues
        hsv[2] = Math.max(0.55f, Math.min(0.85f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private AppTile tileFor(AppCatalog.Entry e) {
        String key = e.id + "|" + e.label + "|" + e.hidden;
        AppTile cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        AppTile tile = makeTile(e);
        cache.put(key, tile);
        return tile;
    }

    private AppTile makeTile(final AppCatalog.Entry e) {
        PackageManager pm = getPackageManager();
        Drawable art = null;
        boolean contain = false;
        int plain = 0xFF2C2C2E;
        Runnable action;
        switch (e.kind) {
            case AppCatalog.AMAZON_HOME:
                action = this::openAmazonHome;
                plain = 0xFF48484A;
                break;
            case AppCatalog.SETTINGS:
                action = () -> Launch.start(this, new Intent(Settings.ACTION_SETTINGS), "Couldn't open Settings.");
                plain = 0xFF48484A;
                break;
            case AppCatalog.HIDDEN_TOGGLE:
                action = () -> {
                    showHidden = !showHidden;
                    layoutGrid(AppCatalog.ID_HIDDEN);
                };
                plain = 0xFF3A3A3C;
                break;
            default:
                art = bannerFor(pm, e.id);
                if (art == null) {
                    art = iconFor(pm, e.id);
                    contain = true;   // a square app icon sits in the tile, it is not cropped to fill it
                }
                iconColors.put(e.id, dominantColor(art));
                action = () -> Launch.app(this, e.id, e.label + " can't be opened.");
        }
        final Runnable launch = action;
        final AppTile tile = new AppTile(this, e.label, art, plain, contain, TILE_RADIUS * k,
                () -> {
                    sounds.play(Sounds.SELECT);
                    launch.run();
                });
        tile.id = e.id;
        tile.kind = e.kind;
        if (e.hidden) {
            tile.setAlpha(0.5f);
        }
        if (e.kind != AppCatalog.HIDDEN_TOGGLE) {
            tile.setOnLongClickListener(v -> {
                ignoreCenterUp = true;   // the key is still down; its release must not drop the tile
                startMove(e.id);
                return true;
            });
        }
        tile.setOnFocusChangeListener((v, focused) -> focusChanged(tile, focused, k));
        ImageView sh = new ImageView(this);
        sh.setImageBitmap(shadowImage(k));
        sh.setScaleType(ImageView.ScaleType.FIT_XY);
        sh.setAlpha(0f);
        tile.shadow = sh;
        return tile;
    }

    private static Drawable bannerFor(PackageManager pm, String pkg) {
        try {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) {
                Drawable banner = pm.getActivityBanner(launch.getComponent());
                if (banner != null) {
                    return banner;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }
    }

    private static Drawable iconFor(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }
    }

    private void focusChanged(AppTile tile, boolean focused, float k) {
        tile.animate().cancel();
        if (relayouting) {
            // tiles are being re-seated (a move, a hide): take the final state at once instead of animating from 1x
            float scale = focused ? FOCUS_SCALE : 1f;
            tile.setScaleX(scale);
            tile.setScaleY(scale);
            tile.shadow.animate().cancel();
            tile.shadow.setScaleX(scale);
            tile.shadow.setScaleY(scale);
            tile.shadow.setAlpha(focused ? 1f : 0f);
            if (focused) {
                tile.post(() -> placeShadow(tile, k));
                scrollTo(tiles.indexOf(tile) / COLUMNS, k);
                showCaption(tile, k);
            }
            return;
        }
        if (focused) {
            if (!quietFocus) {
                sounds.play(Sounds.FOCUS);
            }
            // lift: a spring-like scale and a shadow, nothing else moves
            if (tile.getWidth() == 0) {
                tile.post(() -> placeShadow(tile, k));   // first focus happens before the first layout
            } else {
                placeShadow(tile, k);
            }
            tile.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                    .setInterpolator(new OvershootInterpolator(1.4f)).setDuration(320).start();
            tile.shadow.animate().cancel();
            tile.shadow.animate().alpha(1f).scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                    .setInterpolator(new OvershootInterpolator(1.4f)).setDuration(320).start();
            scrollTo(tiles.indexOf(tile) / COLUMNS, k);
            showCaption(tile, k);
            focusedApp(tile);
        } else {
            tile.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(220).start();
            tile.shadow.animate().cancel();
            tile.shadow.animate().alpha(0f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(220).start();
        }
    }

    /** Put the tile's shadow behind it, a little lower than the tile (light comes from above). */
    private void placeShadow(AppTile tile, float k) {
        // the shadow's layout origin is the tile row's origin minus its own padding, so only the tile's offset is added
        tile.shadow.setTranslationX(tile.getLeft());
        tile.shadow.setTranslationY(tile.getTop() + 10 * k);
    }

    private Bitmap shadowBitmap;

    /** One soft rounded shadow, drawn once and shared by every tile. */
    private Bitmap shadowImage(float k) {
        if (shadowBitmap == null) {
            int pad = Math.round(SHADOW_PAD * k), w = Math.round(TILE_W * k) + 2 * pad, h = Math.round(TILE_H * k) + 2 * pad;
            shadowBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xB0000000);
            p.setMaskFilter(new BlurMaskFilter(pad * 0.55f, BlurMaskFilter.Blur.NORMAL));
            new Canvas(shadowBitmap).drawRoundRect(new RectF(pad, pad, w - pad, h - pad), TILE_RADIUS * k, TILE_RADIUS * k, p);
        }
        return shadowBitmap;
    }

    /** Down into the grid: everything rises, the hero dims. Back in the dock row it all settles back. */
    private void scrollTo(int row, float k) {
        float to = row == 0 ? 0f : -(ROW2_TOP + (row - 1) * ROW_PITCH - 218f) * k;
        stage.animate().translationY(to).setInterpolator(new DecelerateInterpolator(1.8f)).setDuration(380).start();
        dim.animate().alpha(row == 0 ? 0f : 0.32f).setDuration(380).start();
    }

    /** The name shows under the focused icon once it is in the grid; tvOS leaves the dock row bare. */
    private void showCaption(AppTile tile, float k) {
        boolean inDock = tiles.indexOf(tile) < COLUMNS;
        caption.animate().cancel();
        if (inDock) {
            caption.animate().alpha(0f).setDuration(120).start();
            return;
        }
        caption.setText(tile.label);
        tile.post(() -> {
            int[] loc = new int[2];
            tile.getLocationInWindow(loc);
            caption.measure(0, 0);
            float scaledH = tile.getHeight() * FOCUS_SCALE;
            // loc is mid-animation; use the tile's resting centre and the scaled height
            caption.setTranslationX(loc[0] + tile.getWidth() / 2f - caption.getMeasuredWidth() / 2f - stage.getTranslationX());
            caption.setTranslationY(loc[1] - stage.getTranslationY() + (tile.getHeight() + scaledH) / 2f + 6 * k);
            caption.animate().alpha(1f).setStartDelay(120).setDuration(180).start();
        });
    }

    private View statusPill(float k) {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Math.round(11 * k), Math.round(3 * k), Math.round(3 * k), Math.round(3 * k));
        pill.setBackground(new GlassDrawable(pill, backdrop, 14.75f * k, false));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(14);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.addView(clock);
        pillButton = new GlassButton(this, GlassButton.ICON, Glyphs.TOGGLES, "Control Center", null, 11.75f * k, k);
        pillButton.setIconDp(14);
        pillButton.setOnClickListener(v -> toggleControlCenter());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.round(23.5f * k), Math.round(23.5f * k));
        bp.setMargins(Math.round(8 * k), 0, 0, 0);
        pill.addView(pillButton, bp);
        TextView avatar = new TextView(this);
        avatar.setText("L");
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(12);
        avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFA1A1A8, 0xFF6B6B72});
        ring.setShape(GradientDrawable.OVAL);
        avatar.setBackground(ring);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Math.round(23.5f * k), Math.round(23.5f * k));
        ap.setMargins(Math.round(8 * k), 0, 0, 0);
        pill.addView(avatar, ap);
        return pill;
    }

    private FrameLayout.LayoutParams pillParams(float k) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        p.setMargins(0, Math.round(25 * k), Math.round(24.5f * k), 0);
        return p;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (moving) {
            return handleMoveKey(e, code);
        }
        boolean modal = controlCenter.isOpen() || contextMenu.isOpen();
        if (e.getAction() == KeyEvent.ACTION_DOWN && !modal) {
            int at = tiles.indexOf(getCurrentFocus());
            if (code == KeyEvent.KEYCODE_DPAD_UP && at >= 0 && at < COLUMNS) {
                sounds.play(Sounds.FOCUS);
                pillButton.requestFocus();   // Up from the dock reaches the clock pill, as on tvOS
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && getCurrentFocus() == pillButton && !tiles.isEmpty()) {
                tiles.get(0).requestFocus();
                return true;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_MENU) {
            if (contextMenu.isOpen()) {
                contextMenu.close();
            } else if (controlCenter.isOpen()) {
                controlCenter.close();
            } else if (getCurrentFocus() instanceof AppTile) {
                showMenuFor((AppTile) getCurrentFocus());   // Menu on an icon: Move / Hide
            } else {
                toggleControlCenter();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** While a tile is picked up, the D-pad moves it. OK drops it where it is, Back puts it back. */
    private boolean handleMoveKey(KeyEvent e, int code) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveStep(-1);
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveStep(1);
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                moveStep(-COLUMNS);
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveStep(COLUMNS);
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (ignoreCenterUp) {
                    ignoreCenterUp = false;
                } else {
                    finishMove();
                }
            } else if (code == KeyEvent.KEYCODE_BACK) {
                cancelMove();
            }
        }
        return true;   // nothing else gets through while moving
    }

    private List<String> visibleIds() {
        List<String> ids = new ArrayList<>();
        for (AppCatalog.Entry e : entries) {
            if (!e.hidden && e.kind != AppCatalog.HIDDEN_TOGGLE) {
                ids.add(e.id);
            }
        }
        return ids;
    }

    private AppTile tileById(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                return tiles.get(i);
            }
        }
        return null;
    }

    private void startMove(String id) {
        if (moving || controlCenter.isOpen() || contextMenu.isOpen() || tileById(id) == null) {
            return;
        }
        moving = true;
        movingId = id;
        moveSnapshot = visibleIds();
        sounds.play(Sounds.PICK);
        wobble = ObjectAnimator.ofFloat(tileById(id), View.ROTATION, -1.4f, 1.4f);
        wobble.setDuration(110);
        wobble.setRepeatCount(ObjectAnimator.INFINITE);
        wobble.setRepeatMode(ObjectAnimator.REVERSE);
        wobble.start();
    }

    private void moveStep(int delta) {
        List<String> ids = visibleIds();
        int at = ids.indexOf(movingId);
        int to = Math.max(0, Math.min(ids.size() - 1, at + delta));
        if (at < 0 || to == at) {
            return;
        }
        ids.remove(at);
        ids.add(to, movingId);
        // entries in the new order: the visible ones as moved, then whatever is hidden or special at the end
        List<AppCatalog.Entry> reordered = new ArrayList<>();
        for (String id : ids) {
            for (AppCatalog.Entry en : entries) {
                if (en.id.equals(id)) {
                    reordered.add(en);
                }
            }
        }
        for (AppCatalog.Entry en : entries) {
            if (en.hidden || en.kind == AppCatalog.HIDDEN_TOGGLE) {
                reordered.add(en);
            }
        }
        entries = reordered;
        sounds.play(Sounds.FOCUS);
        layoutRows(movingId);
    }

    private void endWobble() {
        if (wobble != null) {
            wobble.cancel();
            wobble = null;
        }
        AppTile t = tileById(movingId);
        if (t != null) {
            t.setRotation(0f);
        }
    }

    private void finishMove() {
        endWobble();
        moving = false;
        store.setOrder(visibleIds());
        sounds.play(Sounds.DROP);
    }

    private void cancelMove() {
        endWobble();
        moving = false;
        store.setOrder(moveSnapshot);
        sounds.play(Sounds.BACK);
        layoutGrid(movingId);
    }

    /** Menu on an icon: Move, Hide (or Unhide), Cancel. Settings and Fire TV Home can move but never hide. */
    private void showMenuFor(AppTile tile) {
        int at = tiles.indexOf(tile);
        if (at < 0 || tile.kind == AppCatalog.HIDDEN_TOGGLE) {
            return;
        }
        AppCatalog.Entry e = entries.get(at);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!e.hidden) {
            labels.add("Move");
            actions.add(() -> startMove(e.id));
        }
        if (AppCatalog.canHide(e)) {
            labels.add(e.hidden ? "Unhide" : "Hide");
            actions.add(() -> setHidden(e, at, !e.hidden));
        }
        labels.add("Cancel");
        actions.add(null);
        int[] where = new int[2];
        tile.getLocationInWindow(where);
        float grow = tile.getHeight() * (FOCUS_SCALE - 1f) / 2f;
        enterModal();
        contextMenu.show(labels.toArray(new String[0]), actions.toArray(new Runnable[0]),
                where[0] + tile.getWidth() / 2f, where[1] - grow, where[1] + tile.getHeight() + grow);
    }

    private void setHidden(AppCatalog.Entry e, int at, boolean hide) {
        String next = e.id;
        if (hide) {
            // focus moves to the neighbour that takes its place
            next = at + 1 < entries.size() ? entries.get(at + 1).id : (at > 0 ? entries.get(at - 1).id : null);
            store.hide(e.id);
        } else {
            store.unhide(e.id);
        }
        sounds.play(Sounds.DROP);
        layoutGrid(next);
    }

    /** While a panel or menu is open, focus stays inside it: the tiles behind and the pill button cannot take it. */
    private void enterModal() {
        returnFocus = getCurrentFocus();
        stage.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        pillButton.setFocusable(false);
    }

    private void exitModal() {
        stage.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        pillButton.setFocusable(true);
        quietFocus = true;
        View back = returnFocus != null && returnFocus.isAttachedToWindow() ? returnFocus
                : (tiles.isEmpty() ? null : tiles.get(0));
        if (back != null) {
            back.requestFocus();
        }
        pillButton.post(() -> quietFocus = false);
    }

    private void toggleControlCenter() {
        if (controlCenter.isOpen()) {
            controlCenter.close();
            return;
        }
        enterModal();
        controlCenter.open();
    }

    private void controlCenterClosed() {
        exitModal();
    }

    @Override
    public void onBackPressed() {
        if (contextMenu.isOpen()) {
            contextMenu.close();
            return;
        }
        if (controlCenter.isOpen()) {
            controlCenter.close();
            return;
        }
        if (!tiles.isEmpty() && tiles.indexOf(getCurrentFocus()) >= COLUMNS) {
            sounds.play(Sounds.BACK);
            tiles.get(0).requestFocus();
        }
        // this is Home: Back has nowhere else to go
    }

    private void openAmazonHome() {
        HomeWatcher.pause(AMAZON_PAUSE_MS);
        Toast.makeText(this, "Amazon's home for the next 10 minutes", Toast.LENGTH_LONG).show();
        Launch.start(this, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(AMAZON_HOME),
                "Couldn't open Amazon's home.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeWatcher.pause(0);   // back from Settings or Amazon's home: the next Home press brings this screen up again
        refreshIfAppsChanged();
    }

    /** A new install or uninstall shows up the next time Home comes forward. */
    private void refreshIfAppsChanged() {
        List<AppCatalog.Entry> now = AppCatalog.build(this, store, showHidden);
        boolean same = now.size() == entries.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = now.get(i).id.equals(entries.get(i).id) && now.get(i).label.equals(entries.get(i).label);
        }
        if (!same) {
            View focus = getCurrentFocus();
            int at = tiles.indexOf(focus);
            layoutGrid(at >= 0 && at < entries.size() ? entries.get(at).id : null);
        }
    }

    @Override
    protected void onDestroy() {
        sounds.release();
        super.onDestroy();
    }
}
