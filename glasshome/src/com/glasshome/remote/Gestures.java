package com.glasshome.remote;

/**
 * The phone's Remote shows a plain touch area (1000 x 1000) for a device that is not a real Apple TV. A swipe over it
 * becomes D-pad presses: the first STEP of travel picks the axis, then each further STEP is one press, so a long swipe
 * keeps scrolling. Phase 1 is press, 2 move, 4 release; 5 is the click, which the phone also reports as the Select
 * button and so is ignored here.
 */
public final class Gestures {
    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22;

    private final KeySink keys;
    private final int step;
    private boolean down;
    private int anchorX, anchorY;
    private char axis;

    public Gestures(KeySink keys, int step) {
        this.keys = keys;
        this.step = step;
    }

    public synchronized void touch(int phase, int x, int y) {
        if (phase == 1) {
            down = true;
            anchorX = x;
            anchorY = y;
            axis = 0;
        } else if (phase == 2 && down) {
            int dx = x - anchorX, dy = y - anchorY;
            if (axis == 0 && Math.max(Math.abs(dx), Math.abs(dy)) >= step) {
                axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
            }
            if (axis == 0) {
                return;
            }
            int d = axis == 'x' ? dx : dy;
            while (Math.abs(d) >= step) {
                int sign = d > 0 ? 1 : -1;
                keys.key(axis == 'x' ? (sign > 0 ? RIGHT : LEFT) : (sign > 0 ? DOWN : UP));
                d -= sign * step;
                if (axis == 'x') {
                    anchorX += sign * step;
                } else {
                    anchorY += sign * step;
                }
            }
        } else if (phase == 3 || phase == 4) {
            down = false;
            axis = 0;
        }
    }
}
