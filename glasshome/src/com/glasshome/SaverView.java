package com.glasshome;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The screensaver: slow aerial nature footage, nothing else on screen. No clock, no date, no text.
 *
 * The clips are plain H.264 files that glasshome/install.sh puts in the app's own folder on the stick (see
 * glasshome/aerials/). They play in shuffled order, muted, each fading through black into the next. If there are no
 * clips (or none will play) the home screen's slow gradient is shown instead, so the screen is never empty.
 * The same view runs inside GlassDream (the system's idle screensaver) and SaverActivity (Sleep, the sleep timer).
 */
final class SaverView extends FrameLayout {
    static final String CLIP_DIR = "aerials";
    private static final int FADE_OUT_MS = 500, FADE_IN_MS = 700, GIVE_UP_AFTER_ERRORS = 4;

    private final VideoView video;
    private final View curtain;
    private final GradientBackdrop backdrop;
    private final List<File> playlist = new ArrayList<File>();
    private int next;
    private int errors;
    private boolean attached;

    SaverView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        // the gradient is the fallback; it only draws while it is visible
        backdrop = new GradientBackdrop(context);
        backdrop.setSpeed(0.25f);
        addView(backdrop, new LayoutParams(-1, -1));

        playlist.addAll(findClips(context));
        Collections.shuffle(playlist);
        if (playlist.isEmpty()) {
            video = null;
            curtain = null;
            return;
        }
        backdrop.setVisibility(GONE);
        video = new VideoView(context);
        addView(video, new LayoutParams(-1, -1, Gravity.CENTER));
        curtain = new View(context);
        curtain.setBackgroundColor(Color.BLACK);
        addView(curtain, new LayoutParams(-1, -1));

        video.setOnPreparedListener(this::onPrepared);
        video.setOnCompletionListener(mp -> fadeOutThen(this::playNext));
        video.setOnErrorListener((mp, what, extra) -> {
            if (++errors >= GIVE_UP_AFTER_ERRORS) {
                showGradient();
            } else {
                playNext();
            }
            return true;   // handled: no "can't play this video" dialog
        });
    }

    /** The clips on the stick, if any. */
    static List<File> findClips(Context context) {
        List<File> found = new ArrayList<File>();
        File base = context.getExternalFilesDir(null);
        File[] files = base == null ? null : new File(base, CLIP_DIR).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".mp4") && f.length() > 0) {
                    found.add(f);
                }
            }
        }
        return found;
    }

    private void onPrepared(MediaPlayer mp) {
        errors = 0;
        mp.setVolume(0f, 0f);
        video.start();
        curtain.animate().alpha(0f).setDuration(FADE_IN_MS).start();
    }

    private void playNext() {
        if (!attached || playlist.isEmpty()) {
            return;
        }
        File clip = playlist.get(next++ % playlist.size());
        video.setVideoPath(clip.getPath());
    }

    private void fadeOutThen(Runnable then) {
        curtain.animate().alpha(1f).setDuration(FADE_OUT_MS).withEndAction(then).start();
    }

    private void showGradient() {
        video.stopPlayback();
        video.setVisibility(GONE);
        curtain.setVisibility(GONE);
        backdrop.setVisibility(VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (video != null) {
            curtain.setAlpha(1f);
            playNext();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        if (video != null) {
            curtain.animate().cancel();
            video.stopPlayback();
        }
        super.onDetachedFromWindow();
    }
}
