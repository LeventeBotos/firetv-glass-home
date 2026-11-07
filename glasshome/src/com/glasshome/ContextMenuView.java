package com.glasshome;

import android.content.Context;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * The small glass menu that Menu opens on a tile: Move, Hide, Cancel. Placed beside the tile (below it, or above when
 * there is no room), over the same kind of glass as Control Center. Concentric corners: each row's radius is 13 dp,
 * the padding around the rows is 10 dp, so the panel's radius is 23 dp.
 */
final class ContextMenuView extends FrameLayout {
    private static final float ROW_W = 190, ROW_H = 40, GAP = 6, PAD = 10, ROW_RADIUS = 13;
    private static final float PANEL_RADIUS = ROW_RADIUS + PAD;

    private final GradientBackdrop backdrop;
    private final Sounds sounds;
    private final Runnable onClose;
    private final float k;
    private FrameLayout panel;
    private boolean open;

    ContextMenuView(Context context, GradientBackdrop backdrop, Sounds sounds, Runnable onClose) {
        super(context);
        this.backdrop = backdrop;
        this.sounds = sounds;
        this.onClose = onClose;
        k = context.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
    }

    boolean isOpen() {
        return open;
    }

    /**
     * Show the menu next to a tile. anchor* are in window pixels: the tile's horizontal centre and its top and bottom
     * edges. labels and actions line up; choosing a row closes the menu and then runs its action.
     */
    void show(String[] labels, Runnable[] actions, float anchorCx, float anchorTop, float anchorBottom) {
        removeAllViews();
        float panelW = 2 * PAD + ROW_W, panelH = 2 * PAD + labels.length * ROW_H + (labels.length - 1) * GAP;
        panel = new FrameLayout(getContext());
        panel.setBackground(new GlassDrawable(panel, backdrop, PANEL_RADIUS * k, false));
        int w = Math.round(panelW * k), h = Math.round(panelH * k);
        LayoutParams lp = new LayoutParams(w, h);
        float screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        float x = Math.max(24 * k, Math.min(screenW - w - 24 * k, anchorCx - w / 2f));
        float below = anchorBottom + 14 * k;
        float y = below + h + 24 * k <= screenH ? below : anchorTop - 14 * k - h;
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(Math.max(24 * k, y));
        addView(panel, lp);

        GlassButton first = null;
        for (int i = 0; i < labels.length; i++) {
            GlassButton row = new GlassButton(getContext(), GlassButton.ROW, -1, labels[i], null, ROW_RADIUS * k, k);
            LayoutParams rp = new LayoutParams(Math.round(ROW_W * k), Math.round(ROW_H * k));
            rp.leftMargin = Math.round(PAD * k);
            rp.topMargin = Math.round((PAD + i * (ROW_H + GAP)) * k);
            final Runnable action = actions[i];
            row.setOnClickListener((View v) -> {
                close();
                if (action != null) {
                    action.run();
                }
            });
            panel.addView(row, rp);
            if (first == null) {
                first = row;
            }
        }
        open = true;
        setVisibility(VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).withLayer().setInterpolator(new DecelerateInterpolator(1.5f))
                .setDuration(180).start();
        sounds.play(Sounds.SELECT);
        first.requestFocus();
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        sounds.play(Sounds.BACK);
        final FrameLayout closing = panel;
        closing.animate().alpha(0f).withLayer().setDuration(120).withEndAction(() -> {
            if (!open) {
                setVisibility(GONE);
                removeAllViews();
            }
        }).start();
        onClose.run();
    }
}
