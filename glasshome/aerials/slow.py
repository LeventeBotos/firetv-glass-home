#!/usr/bin/env python3
"""Make the screensaver footage slow and smooth: each clip at half speed, with new in-between frames.

    slow.py                  slow every clip in ~/.cleanyourtv/aerials/source/ into ~/.cleanyourtv/aerials/
    SLOWDOWN=3 slow.py       three times slower instead (default 2)

Playing a 30 fps clip at half speed would show every frame twice, 15 distinct pictures a second, which stutters on
a drone pan. Instead ffmpeg's motion-compensated interpolation (minterpolate) invents the frames between, so the
result is still a full 30 frames per second and the motion is even. Audio is dropped; the screensaver is silent.

It is slow work (about 25 seconds of computing per second of footage, per core), so clips are done in parallel and
a finished clip is never redone. Needs ffmpeg on the PATH. Nothing here touches the TV.
"""
from concurrent.futures import ProcessPoolExecutor
import os
from pathlib import Path
import shutil
import subprocess
import sys

BASE = Path.home() / ".cleanyourtv" / "aerials"
SOURCE = BASE / "source"
FPS = 30


def slowdown():
    try:
        factor = float(os.environ.get("SLOWDOWN", "2"))
    except ValueError:
        raise SystemExit("SLOWDOWN must be a number, e.g. SLOWDOWN=2")
    if not 1.0 <= factor <= 8.0:
        raise SystemExit("SLOWDOWN must be between 1 and 8")
    return factor


def slow_one(args):
    src, dst, factor = args
    tmp = dst.with_name(dst.stem + ".part")   # not *.mp4, so nothing mistakes it for a finished clip
    command = ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-an",
               "-vf", f"setpts={factor}*PTS,minterpolate=fps={FPS}:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1",
               "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
               "-movflags", "+faststart", "-f", "mp4", str(tmp)]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0 or not tmp.exists():
        tmp.unlink(missing_ok=True)
        return src.name, result.stderr.strip()[-300:] or "ffmpeg failed"
    tmp.replace(dst)
    return src.name, None


def main():
    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg is needed to slow the clips down (brew install ffmpeg)")
    factor = slowdown()
    sources = sorted(SOURCE.glob("*.mp4"))
    if not sources:
        raise SystemExit(f"no clips in {SOURCE}; run glasshome/aerials/fetch.py first")
    # a clip is redone only if its slowed version is missing or older than the source
    todo = [(s, BASE / s.name, factor) for s in sources
            if not (BASE / s.name).exists() or (BASE / s.name).stat().st_mtime < s.stat().st_mtime]
    if not todo:
        print(f"all {len(sources)} clips are already slowed down")
        return 0
    workers = max(1, min(len(todo), (os.cpu_count() or 2) // 2))
    print(f"slowing {len(todo)} clips {factor:g}x with {workers} at a time; this takes a while the first time ...",
          flush=True)
    failed = []
    with ProcessPoolExecutor(max_workers=workers) as pool:
        for name, error in pool.map(slow_one, todo):
            print(f"  {name}: {'done' if error is None else 'FAILED'}", flush=True)
            if error:
                failed.append(f"{name}: {error}")
    if failed:
        raise SystemExit("\n".join(failed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
