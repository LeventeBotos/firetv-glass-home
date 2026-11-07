#!/usr/bin/env python3
"""What Glass Home changes on the stick, recorded first, so that undo means undo.

    record.py --save        read the stick and write the record (the first record wins)
    record.py --commands    print a batch for tools/tv.py --batch that puts the recorded state back

Glass Home changes two settings (the screensaver Android starts when idle, and whether screensavers are on) and
grants itself one permission (WRITE_SECURE_SETTINGS, so Control Center can switch the screensaver off). All three
go back to what was there before. The record is ~/.cleanyourtv/glasshome-<TV_HOST>.json, written once: a
re-install after a half-finished run must not record the broken state as the original.

Every value read from the stick ends up inside a command the stick runs, so nothing that could carry shell syntax
is allowed through.
"""
import json
import os
from pathlib import Path
import re
import sys
import time

PACKAGE = "com.glasshome"
PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
COMPONENT = r"[A-Za-z_][\w.]*/[\w.$]+"
# a component list ("a/.B,c/.D") or a 0/1 flag; "null" (the setting does not exist) is handled before these
PATTERNS = {
    "screensaver_components": re.compile(COMPONENT + "(," + COMPONENT + ")*"),
    "screensaver_enabled": re.compile(r"[01]"),
}
VERSION = 1


def record_path(host):
    tag = re.sub(r"[^A-Za-z0-9.-]", "_", host or "no-host")
    return Path.home() / ".cleanyourtv" / f"glasshome-{tag}.json"


def clean(key, value):
    """None when the setting is not set on the stick; otherwise the value, if it is a safe one."""
    value = (value or "").strip()
    if value in ("", "null"):
        return None
    if not PATTERNS[key].fullmatch(value):
        raise SystemExit(f"{key}: the stick answered {value!r}, which is not a value this script will "
                         "write back. Nothing was recorded and nothing was changed.")
    return value


def read_state(shell):
    """shell(command) -> the stick's answer. Returns what is about to be changed."""
    settings = {key: clean(key, shell(f"settings get secure {key}")) for key in PATTERNS}
    granted = f"{PERMISSION}: granted=true" in shell(f"dumpsys package {PACKAGE}")
    return {"settings": settings, "write_secure_settings_granted": granted}


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        raise SystemExit(f"{path}: not a usable record ({e}).\n"
                         "It is left where it is: move it aside deliberately, a retry must not replace "
                         "the state recorded before the first change.")


def write(path, record):
    # next to its final name first: a crash halfway must not leave a truncated record that blocks every retry
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w") as f:
        json.dump(record, f, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def save(path, shell):
    """Write the record unless one already holds the original state. Returns True if it wrote."""
    existing = load(path)
    if existing and existing.get("version") == VERSION:
        return False
    record = dict(existing or {})   # an older note from the first install keeps its fields
    record.update(read_state(shell))
    record.update(version=VERSION, recorded=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                  note="undo with glasshome/install.sh --remove")
    write(path, record)
    return True


def restore_commands(record):
    """The batch that puts the recorded state back. Order matters: revoke before the app is uninstalled."""
    lines = []
    for key in PATTERNS:
        value = (record.get("settings") or {}).get(key)
        if value is not None:
            clean(key, value)   # a hand-edited record is held to the same rule as the stick
        lines.append(f"settings delete secure {key}" if value is None else f"settings put secure {key} {value}")
    if not record.get("write_secure_settings_granted"):
        lines.append(f"pm revoke {PACKAGE} {PERMISSION}")
    return lines


def fallback_commands():
    """No record: Fire OS keeps Amazon's own screensaver in screensaver_default_component, so go back to that."""
    return ["settings put secure screensaver_components $(settings get secure screensaver_default_component)",
            f"pm revoke {PACKAGE} {PERMISSION}"]


def connect():
    # imported here so --commands works on a machine without adb_shell: printing the batch needs no TV
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))
    try:
        import tv
    except ImportError as e:
        raise SystemExit(f"cannot load tools/tv.py ({e}).\nrun: python3 -m venv tools/venv && "
                         "tools/venv/bin/pip install -r tools/requirements.txt")
    return tv.connect()


def main(argv):
    host = os.environ.get("TV_HOST", "")
    path = record_path(host)
    if argv[1:] == ["--save"]:
        dev = connect()
        wrote = save(path, lambda command: (dev.shell(command, read_timeout_s=60.0) or "").strip())
        print(f"recorded the starting state in {path}" if wrote else f"already recorded in {path}", file=sys.stderr)
        return 0
    if argv[1:] == ["--commands"]:
        record = load(path)
        if record and record.get("version") == VERSION:
            print("\n".join(restore_commands(record)))
        else:
            print(f"no record at {path}; going back to Amazon's own screensaver", file=sys.stderr)
            print("\n".join(fallback_commands()))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
