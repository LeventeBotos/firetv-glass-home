package com.glasshome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** The screensaver on demand (Sleep, the sleep timer): the same view as GlassDream, closed by any key. */
public class SaverActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SaverView view = new SaverView(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(view);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            finish();
        }
        return true;
    }
}
