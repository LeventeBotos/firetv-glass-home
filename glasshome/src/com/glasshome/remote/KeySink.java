package com.glasshome.remote;

/** Where the remote's key presses end up. On the stick that is {@link InputBridge}; in tests it is a printer. */
public interface KeySink {
    void down(int keyCode);

    void up(int keyCode);

    /** A press and release in one go. */
    void key(int keyCode);
}
