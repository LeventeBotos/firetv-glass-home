package com.glasshome;

import android.content.Context;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;

/**
 * The UI sounds: five short tones made by glasshome/sounds/make_sounds.py. SoundPool decodes them once at start and
 * plays asynchronously, so a sound never adds time to a frame. Rapid repeats (holding a D-pad key) are throttled.
 */
final class Sounds {
    static final int FOCUS = 0, SELECT = 1, BACK = 2, PICK = 3, DROP = 4;
    private static final long MIN_GAP_MS = 60;

    private final SoundPool pool;
    private final int[] ids = new int[5];
    private final LayoutStore store;
    private long last;

    @SuppressWarnings("deprecation")
    Sounds(Context context, LayoutStore store) {
        this.store = store;
        pool = new SoundPool(4, AudioManager.STREAM_MUSIC, 0);
        ids[FOCUS] = pool.load(context, R.raw.focus, 1);
        ids[SELECT] = pool.load(context, R.raw.select, 1);
        ids[BACK] = pool.load(context, R.raw.back, 1);
        ids[PICK] = pool.load(context, R.raw.pick, 1);
        ids[DROP] = pool.load(context, R.raw.drop, 1);
    }

    boolean isOn() {
        return store.sounds();
    }

    void setOn(boolean on) {
        store.setSounds(on);
    }

    void play(int sound) {
        if (!store.sounds()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (sound == FOCUS && now - last < MIN_GAP_MS) {
            return;
        }
        last = now;
        pool.play(ids[sound], 0.55f, 0.55f, 1, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
