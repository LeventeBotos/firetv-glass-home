package com.glasshome.remote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shows the pairing PIN over whatever is on screen, so pairing works from any app, not only the home screen. */
final class PinOverlay implements PinDisplay {
    private static final long TIMEOUT_MS = 2 * 60 * 1000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View view;

    PinOverlay(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void show(final String pin) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                remove();
                float d = context.getResources().getDisplayMetrics().density;
                LinearLayout box = new LinearLayout(context);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                int pad = Math.round(36 * d);
                box.setPadding(pad, Math.round(28 * d), pad, Math.round(32 * d));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.argb(235, 28, 28, 32));
                bg.setCornerRadius(28 * d);
                box.setBackground(bg);

                TextView title = new TextView(context);
                title.setText("Enter this code on your iPhone");
                title.setTextColor(Color.argb(200, 255, 255, 255));
                title.setTextSize(20);
                title.setGravity(Gravity.CENTER);
                box.addView(title);

                TextView code = new TextView(context);
                code.setText(pin);
                code.setTextColor(Color.WHITE);
                code.setTextSize(64);
                code.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                code.setLetterSpacing(0.25f);
                code.setGravity(Gravity.CENTER);
                code.setPadding(0, Math.round(10 * d), 0, 0);
                box.addView(code);

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_TOAST,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.CENTER;
                try {
                    ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).addView(box, lp);
                    view = box;
                } catch (RuntimeException e) {
                    android.util.Log.w("GlassRemote", "could not show the PIN: " + e);
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        remove();
                    }
                }, TIMEOUT_MS);
            }
        });
    }

    @Override
    public void hide() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                remove();
            }
        });
    }

    private void remove() {
        handler.removeCallbacksAndMessages(null);
        if (view != null) {
            try {
                ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
            } catch (RuntimeException ignored) {
                // already gone
            }
            view = null;
        }
    }
}
