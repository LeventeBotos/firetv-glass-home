package com.glasshome.remote;

/** Shows the pairing PIN on the TV. */
public interface PinDisplay {
    void show(String pin);

    void hide();
}
