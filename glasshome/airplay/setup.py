#!/usr/bin/env python3
"""Install or remove the AirPlay receiver (github.com/jqssun/android-airplay-server) on the stick.

    TV_HOST=<stick> setup.py            download (once), verify, send to the stick and install it
    TV_HOST=<stick> setup.py --remove   uninstall it and put the old receiver back as it was

Two AirPlay receivers cannot share port 7000, and the old one (AirReceiver) starts itself at boot, so installing this
disables AirReceiver (nothing is uninstalled) after writing down whether it was enabled, in
~/.cleanyourtv/airplay-<TV_HOST>.json. --remove re-enables it only if it was enabled before.

The release is pinned: the file must match the SHA-256 below or nothing is sent. It is GPL-3.0 software by jqssun,
built on UxPlay; it needs Android 7.0+ and runs on this stick's 32-bit ARM. Control Center's AirPlay button opens it
when it is installed. The APK is 27 MB, which ADB moves in half an hour, so it travels over the local network the
same way the screensaver clips do (see ../aerials/push.py).
"""
import hashlib
import json
import os
from pathlib import Path
import sys
import threading
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "aerials"))
import push  # noqa: E402  (also loads tools/tv.py and exits with a readable message when TV_HOST is not set)

PACKAGE = "io.github.jqssun.airplay"
VERSION = "v0.0.36"
URL = f"https://github.com/jqssun/android-airplay-server/releases/download/{VERSION}/app-release.apk"
SHA256 = "b8c7c595ed93f91562feedce92c27800f08f9c84df424f6ac2045e82324cf2a4"
CACHE = Path.home() / ".cleanyourtv" / "downloads" / "airplay" / f"airplay-{VERSION}.apk"
REMOTE = "/data/local/tmp/airplay.apk"
OLD = "com.softmedia.receiver"   # AirReceiver, the receiver this replaces


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def fetch():
    if CACHE.exists() and sha256(CACHE) == SHA256:
        return CACHE
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading {URL} ...", flush=True)
    tmp = CACHE.with_name(CACHE.name + ".part")
    urllib.request.urlretrieve(URL, tmp)
    if sha256(tmp) != SHA256:
        tmp.unlink(missing_ok=True)
        raise SystemExit("the download does not match the pinned checksum, so it was thrown away and nothing "
                         "was sent to the stick. If a newer release is wanted, update VERSION and SHA256 here "
                         "after checking it.")
    tmp.replace(CACHE)
    return CACHE


def record_path():
    tag = "".join(c if c.isalnum() or c in ".-" else "_" for c in (os.environ.get("TV_HOST") or "no-host"))
    return Path.home() / ".cleanyourtv" / f"airplay-{tag}.json"


def remember_old_receiver(dev):
    """Write down whether AirReceiver was enabled, once: the first record is the original state."""
    path = record_path()
    if path.exists():
        return json.loads(path.read_text())
    installed = f"package:{OLD}" in push.shell(dev, f"pm list packages {OLD}").split()
    disabled = f"package:{OLD}" in push.shell(dev, f"pm list packages -d {OLD}").split()
    record = {"version": 1, "old_receiver": OLD,
              "old_receiver_state": "absent" if not installed else "disabled" if disabled else "enabled"}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2) + "\n")
    return record


def main(argv):
    dev = push.tv.connect()
    if argv[1:] == ["--remove"]:
        print(push.shell(dev, f"pm uninstall {PACKAGE}") or "removed")
        path = record_path()
        if path.exists() and json.loads(path.read_text()).get("old_receiver_state") == "enabled":
            push.shell(dev, f"pm enable {OLD}")
            print(f"{OLD} enabled again")
        return 0
    record = remember_old_receiver(dev)
    apk = fetch()
    errors = []
    sender = threading.Thread(target=push.send, args=(push.tv.HOST, apk, errors))
    sender.start()
    push.shell(dev, f"toybox nc -l -p {push.PORT} > {REMOTE}", timeout=push.TIMEOUT_S)
    sender.join()
    got = (push.shell(dev, f"md5sum {REMOTE}").split() or [""])[0]
    if errors or got != push.md5(apk):
        push.shell(dev, f"rm -f {REMOTE}")
        raise SystemExit(f"transfer failed ({errors[0] if errors else 'checksum differs'}); nothing was installed")
    result = push.shell(dev, f"pm install -r {REMOTE}", timeout=180.0)
    push.shell(dev, f"rm -f {REMOTE}")
    print(result)
    if "Success" not in result:
        return 1
    if record["old_receiver_state"] == "enabled":
        push.shell(dev, f"am force-stop {OLD}; pm disable-user --user 0 {OLD}")
        print(f"{OLD} disabled (it shares the AirPlay port); the record is in {record_path()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
