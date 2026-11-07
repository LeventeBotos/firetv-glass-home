#!/usr/bin/env python3
"""Start the key helper on the stick, from a computer, and tell the Glass Home app where it is.

    TV_HOST=<stick> tools/venv/bin/python -I glasshome/remote/helper.py

Why a computer is needed at all: an app may not press keys in other apps, but the ADB shell user may. Glass Home would
start the helper itself through the stick's own ADB if Fire OS let it, but Fire OS 6 ignores ADB connections that come from
the stick itself (tested: the stick's own shell and the app are both turned away while a computer connects fine). So the
helper is started from here, once, and then runs on its own: it keeps working when this computer is switched off, when the
app restarts and when Glass Home is reinstalled, and stops when the stick is rebooted (run this again then). A helper
nobody has connected to for half an hour also stops, which only happens to one left behind by an older run.

The helper takes its commands over a loopback socket and only from a client that knows its token, which goes to it through
a pipe (never on its command line). The app learns port and token from a broadcast that only the ADB shell may send.
"""
import secrets
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import tv  # noqa: E402  (exits with a readable message when TV_HOST is not set)

PACKAGE = "com.glasshome"
HELPER = "com.glasshome.remote.Injector"


def main():
    dev = tv.connect()
    out = dev.shell(f"pm path {PACKAGE}").strip()
    if not out.startswith("package:"):
        sys.exit("Glass Home is not installed on the stick; run glasshome/install.sh first.")
    apk = out.split(":", 1)[1]

    port = 30000 + secrets.randbelow(30000)
    token = secrets.token_hex(12)
    # The helper outlives this ADB session only if the session stays open until it has started and detached from it
    # (setsid); a JVM start takes a second or two on this hardware, so the shell waits for that.
    dev.shell(f"echo {token} | CLASSPATH={apk} setsid app_process /system/bin {HELPER} {port} "
              ">/data/local/tmp/glassinj.out 2>&1 & sleep 6", read_timeout_s=60.0)
    log = dev.shell("cat /data/local/tmp/glassinj.out").strip()
    if "listening" not in log:
        sys.exit(f"the key helper did not start: {log or '(no output)'}")
    reply = dev.shell(f"am broadcast -n {PACKAGE}/.remote.HelperReceiver -a {PACKAGE}.remote.HELPER "
                      f"--ei port {port} --es token {token}")
    if "Error" in reply or "Denial" in reply or "Exception" in reply:
        sys.exit(f"the app did not take the hand-off: {reply.strip()}")
    print(f"key helper running on the stick (port {port}); Glass Home has been told.")
    dev.close()


if __name__ == "__main__":
    main()
