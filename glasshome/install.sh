#!/usr/bin/env bash
# Build and install Glass Home on a Fire TV, let its watcher read the log, and make it the home screen.
# Usage:   TV_HOST=192.168.68.104 glasshome/install.sh
# Undo:    TV_HOST=192.168.68.104 glasshome/install.sh --remove     (Amazon's home is back on the next Home press)
#
# Nothing on the stick is uninstalled or disabled by this. It adds one app (com.glasshome), grants it two permissions
# (READ_LOGS to see Home presses, WRITE_SECURE_SETTINGS so Control Center can switch the screensaver) and makes the
# app's screensaver the active one (aerial clips go to the app's own folder on the stick). What was there before is
# recorded in ~/.cleanyourtv/glasshome-<host>.json the first time (the first record wins); --remove puts it all back.
set -euo pipefail
cd "$(dirname "$0")/.."
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
: "${TV_HOST:?set TV_HOST to the IP of the stick, e.g. TV_HOST=192.168.68.104}"
tv() { "$PY" -I tools/tv.py "$@"; }

if [ "${1:-}" = "--remove" ]; then
  # restore the recorded screensaver settings and permission first (a revoke needs the app still installed)
  { "$PY" -I glasshome/record.py --commands; printf '%s\n' "pm uninstall com.glasshome" "input keyevent 3"; } | tv --batch
  echo "Glass Home removed; Amazon's home and Amazon's screensaver are back."
  exit 0
fi

# the starting state, before anything below changes it (the first record wins)
"$PY" -I glasshome/record.py --save

glasshome/build.sh
tv --push glasshome/build/glasshome.apk /data/local/tmp/glasshome.apk
# READ_LOGS lets the watcher see Home presses. Fire OS drops accessibility services set over ADB, but grants this.
printf '%s\n' \
  "pm install -r /data/local/tmp/glasshome.apk" \
  "rm /data/local/tmp/glasshome.apk" \
  "pm grant com.glasshome android.permission.READ_LOGS" \
  "pm grant com.glasshome android.permission.WRITE_SECURE_SETTINGS" \
  "settings put secure screensaver_components com.glasshome/.GlassDream" \
  "am force-stop com.glasshome" \
  "am start -n com.glasshome/.HomeActivity" | tv --batch

# the iPhone Remote's key helper: started from here because Fire OS refuses ADB connections from the stick itself (see
# glasshome/remote/helper.py). It keeps running without this computer until the stick is rebooted.
"$PY" -I glasshome/remote/helper.py || echo "The iPhone Remote's key helper did not start; run glasshome/remote/helper.py again when the stick is up."

# the screensaver's footage: free aerial nature clips, fetched once into ~/.cleanyourtv/aerials/ and sent over the
# network (ADB is far too slow for video). Skip with NO_AERIALS=1; the screensaver then shows the slow gradient.
if [ -z "${NO_AERIALS:-}" ]; then
  python3 glasshome/aerials/fetch.py
  python3 glasshome/aerials/slow.py     # half speed with smooth in-between frames; slow the first time, then cached
  "$PY" -I glasshome/aerials/push.py
fi
