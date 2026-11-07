#!/usr/bin/env python3
"""Make Glass Home's UI sounds from scratch: short soft tones, nothing copied from any other system.

    python3 glasshome/sounds/make_sounds.py        writes glasshome/res/raw/*.wav

22.05 kHz, mono, 16-bit. Each sound is a few sine partials under a quick exponential envelope.
"""
import math
from pathlib import Path
import struct
import wave

RATE = 22050
OUT = Path(__file__).resolve().parent.parent / "res" / "raw"


def render(seconds, voice):
    """voice(t, n) returns a sample in [-1, 1] for time t (seconds) and index n."""
    n = int(RATE * seconds)
    return [max(-1.0, min(1.0, voice(i / RATE, i))) for i in range(n)]


def tone(freq, decay, amp, end_freq=None, seconds=0.1):
    """A sine (optionally gliding from freq to end_freq) with a soft attack and exponential decay."""
    phase = [0.0]

    def voice(t, i):
        f = freq if end_freq is None else freq + (end_freq - freq) * min(1.0, t / seconds)
        phase[0] += 2 * math.pi * f / RATE
        attack = min(1.0, t / 0.002)
        return amp * attack * math.exp(-t / decay) * (math.sin(phase[0]) + 0.25 * math.sin(2 * phase[0]))
    return voice


SOUNDS = {
    # a soft tick, as quiet as a button can be and still be felt
    "focus": render(0.045, tone(1900, 0.008, 0.30)),
    # a short two-note pop
    "select": render(0.14, tone(880, 0.030, 0.30)),
    # a lower, shorter step down
    "back": render(0.09, tone(700, 0.020, 0.30, end_freq=480, seconds=0.08)),
    # lift and set down, for move mode
    "pick": render(0.12, tone(520, 0.045, 0.32, end_freq=900, seconds=0.10)),
    "drop": render(0.12, tone(900, 0.040, 0.32, end_freq=520, seconds=0.10)),
}


def second_note(sound, freq, start, decay, amp):
    """Add a second note to a rendered sound, starting at `start` seconds."""
    offset = int(RATE * start)
    for i in range(offset, len(sound)):
        t = (i - offset) / RATE
        sound[i] = max(-1.0, min(1.0, sound[i] + amp * min(1.0, t / 0.002) * math.exp(-t / decay)
                                 * math.sin(2 * math.pi * freq * t)))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    second_note(SOUNDS["select"], 1320, 0.035, 0.040, 0.28)
    for name, samples in SOUNDS.items():
        path = OUT / f"{name}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(s * 32767)) for s in samples))
        print(f"{path.relative_to(OUT.parent.parent.parent)}  {len(samples) / RATE * 1000:.0f} ms")


if __name__ == "__main__":
    main()
