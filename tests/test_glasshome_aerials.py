"""glasshome/aerials: the clip list and the checks around downloading it.

Nothing here touches the network or the TV.
Run from the repository root: python3 -m unittest discover -s tests -v
"""
import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(f"aerials_{name}", ROOT / "glasshome" / "aerials" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetch = load("fetch")


class ClipListTest(unittest.TestCase):
    def test_every_line_parses(self):
        clips = fetch.clips()
        self.assertGreaterEqual(len(clips), 5)

    def test_ids_are_unique(self):
        ids = [clip_id for clip_id, _, _ in fetch.clips()]
        self.assertEqual(len(ids), len(set(ids)))

    def test_each_page_url_matches_its_id(self):
        for clip_id, _fps, page in fetch.clips():
            self.assertTrue(page.rstrip("/").endswith(clip_id), page)

    def test_download_url_is_the_1080p_file(self):
        self.assertEqual(fetch.source("123", 30),
                         "https://videos.pexels.com/video-files/123/123-hd_1920_1080_30fps.mp4")


class Mp4CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def test_a_real_mp4_header_passes(self):
        good = self.root / "good.mp4"
        good.write_bytes(b"\x00\x00\x00\x18ftypisom" + b"\x00" * 200_000)
        self.assertTrue(fetch.looks_like_mp4(good))

    def test_an_error_page_saved_as_mp4_is_refused(self):
        bad = self.root / "bad.mp4"
        bad.write_bytes(b"<html>Access denied</html>" + b" " * 200_000)
        self.assertFalse(fetch.looks_like_mp4(bad))

    def test_a_tiny_or_missing_file_is_refused(self):
        tiny = self.root / "tiny.mp4"
        tiny.write_bytes(b"\x00\x00\x00\x18ftypisom")
        self.assertFalse(fetch.looks_like_mp4(tiny))
        self.assertFalse(fetch.looks_like_mp4(self.root / "missing.mp4"))


if __name__ == "__main__":
    unittest.main()
