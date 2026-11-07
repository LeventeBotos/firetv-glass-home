#!/usr/bin/env python3
"""Put the aerial clips on the stick, at network speed.

    push.py             make the stick's clips match ~/.cleanyourtv/aerials/*.mp4 (the slowed versions)
    push.py --clear     delete the clips from the stick (the app and its settings stay)

tools/tv.py --push moves about 16 KB/s over ADB, which is hours for video. The stick's shell has toybox netcat, so for each
clip the stick listens on a port while this script sends the file over the local network, then the checksums are
compared. ADB only carries the short commands. Needs TV_HOST; the stick and this computer must be on the same network.
"""
import hashlib
from pathlib import Path
import socket
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import tv  # noqa: E402  (exits with a readable message when TV_HOST is not set)

SOURCE = Path.home() / ".cleanyourtv" / "aerials"
REMOTE = "/sdcard/Android/data/com.glasshome/files/aerials"
PORT = 9123
TIMEOUT_S = 600


def shell(dev, command, timeout=60.0):
    return (dev.shell(command, read_timeout_s=timeout) or "").strip()


def send(host, path, errors):
    """Connect once the stick is listening, send the file, close (which ends the stick's netcat)."""
    deadline = time.time() + 15
    while True:
        try:
            s = socket.create_connection((host, PORT), timeout=5)
            break
        except OSError:
            if time.time() > deadline:
                errors.append("the stick never started listening on the network port")
                return
            time.sleep(0.4)
    try:
        with s, open(path, "rb") as f:
            while chunk := f.read(1 << 20):
                s.sendall(chunk)
    except OSError as e:
        errors.append(str(e))


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def main(argv):
    dev = tv.connect()
    if argv[1:] == ["--clear"]:
        shell(dev, f"rm -rf {REMOTE}/*.mp4")
        print("clips removed from the stick")
        return 0
    clips = sorted(p for p in SOURCE.glob("*.mp4") if ".part" not in p.name)   # never a half-rendered file
    if not clips:
        raise SystemExit(f"no clips in {SOURCE}; run glasshome/aerials/fetch.py and slow.py first")
    shell(dev, f"mkdir -p {REMOTE}")
    # the stick holds exactly what is in the folder here: clips that were taken out (or replaced by a newer
    # version with the same name, below) do not stay behind
    wanted = {p.name for p in clips}
    for line in shell(dev, f"ls {REMOTE}").split():
        if line.endswith(".mp4") and line not in wanted:
            shell(dev, f"rm -f {REMOTE}/{line}")
    for path in clips:
        name = path.name
        size = path.stat().st_size
        remote = f"{REMOTE}/{name}"
        have = shell(dev, f"stat -c %s {remote} 2>/dev/null").split()
        if have and have[-1] == str(size):
            continue
        print(f"sending {name} ({size / 1e6:.1f} MB) ...", flush=True)
        errors = []
        sender = threading.Thread(target=send, args=(tv.HOST, path, errors))
        sender.start()
        shell(dev, f"toybox nc -l -p {PORT} > {remote}", timeout=TIMEOUT_S)   # returns when the sender closes
        sender.join()
        want = md5(path)
        got = (shell(dev, f"md5sum {remote}").split() or [""])[0]
        if errors or got != want:
            shell(dev, f"rm -f {remote}")
            raise SystemExit(f"{name}: transfer failed ({errors[0] if errors else 'checksum differs'}); "
                             "the partial file was removed. Run it again.")
    print(f"done: {len(clips)} clips on the stick in {REMOTE}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
