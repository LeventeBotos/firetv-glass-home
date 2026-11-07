#!/usr/bin/env python3
"""Download the screensaver's aerial clips listed in clips.txt into ~/.cleanyourtv/aerials/source/ (slow.py then
makes the slowed versions that go on the stick).

    fetch.py            download what is missing, check every file
    fetch.py --list     print the clip files that should exist, one per line

Plain urllib, no extra packages. A file is kept if it already has a plausible MP4 header and size; a partial or
broken download is removed and fetched again. Nothing here touches the TV.
"""
from pathlib import Path
import re
import sys
import urllib.request

HERE = Path(__file__).resolve().parent
DEST = Path.home() / ".cleanyourtv" / "aerials" / "source"
LINE = re.compile(r"(\d+)\s+(\d+)\s+(https://www\.pexels\.com/video/\S+)")


def clips():
    """[(id, fps, page url)] from clips.txt."""
    found = []
    for number, line in enumerate((HERE / "clips.txt").read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = LINE.fullmatch(line)
        if not m:
            raise SystemExit(f"clips.txt:{number}: expected '<id> <fps> <pexels url>', got {line!r}")
        found.append((m.group(1), int(m.group(2)), m.group(3)))
    return found


def source(clip_id, fps):
    return f"https://videos.pexels.com/video-files/{clip_id}/{clip_id}-hd_1920_1080_{fps}fps.mp4"


def looks_like_mp4(path):
    try:
        with open(path, "rb") as f:
            head = f.read(12)
        return path.stat().st_size > 100_000 and head[4:8] == b"ftyp"
    except OSError:
        return False


def main(argv):
    if argv[1:] == ["--list"]:
        print("\n".join(str(DEST / f"{clip_id}.mp4") for clip_id, _, _ in clips()))
        return 0
    DEST.mkdir(parents=True, exist_ok=True)
    for clip_id, fps, _page in clips():
        path = DEST / f"{clip_id}.mp4"
        if looks_like_mp4(path):
            continue
        print(f"downloading {clip_id} ...", flush=True)
        tmp = path.with_name(path.name + ".part")
        try:
            urllib.request.urlretrieve(source(clip_id, fps), tmp)
        except OSError as e:
            tmp.unlink(missing_ok=True)
            raise SystemExit(f"{clip_id}: download failed ({e}). Try again, or put your own .mp4 files in {DEST}.")
        if not looks_like_mp4(tmp):
            tmp.unlink(missing_ok=True)
            raise SystemExit(f"{clip_id}: what came back is not an MP4. Nothing was kept.")
        tmp.replace(path)
    have = sorted(DEST.glob("*.mp4"))
    print(f"{len(have)} clips, {sum(p.stat().st_size for p in have) / 1e6:.0f} MB in {DEST}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
