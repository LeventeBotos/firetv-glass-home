package com.glasshome;

import android.service.dreams.DreamService;

/** The screensaver Android starts when the stick has been idle. glasshome/install.sh makes it the active one. */
public class GlassDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key or touch ends it
        setFullscreen(true);
        setContentView(new SaverView(this));
    }
}
