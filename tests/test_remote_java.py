"""The iPhone Remote's protocol code (glasshome/src/com/glasshome/remote/): crypto, OPACK, TLV8, gestures.

The code is plain Java with no Android classes in it, so it is compiled here the way the app is (Java 8 language level)
and the checks in tests/java/CryptoTest.java run on the computer's own JDK, which is also what the hand-written crypto
is compared against (X25519, Ed25519 and ChaCha20-Poly1305 are built into JDK 17+; the stick's Android 7 has none).

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REMOTE = ROOT / "glasshome" / "src" / "com" / "glasshome" / "remote"
# everything in the package that does not touch Android
PURE = ["crypto/*.java", "Tlv8.java", "Opack.java", "KeySink.java", "PinDisplay.java", "PairingStore.java",
        "Gestures.java", "CompanionServer.java", "AdbClient.java", "Base64Compat.java", "InputBridge.java"]


def java_major():
    try:
        out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
        return int(out.split('"')[1].split(".")[0])
    except (OSError, IndexError, ValueError):
        return 0


@unittest.skipUnless(shutil.which("javac") and java_major() >= 17, "needs a JDK 17 or newer (javac and java)")
class RemoteJavaTests(unittest.TestCase):
    def test_crypto_codecs_and_gestures(self):
        with tempfile.TemporaryDirectory() as tmp:
            classes = Path(tmp) / "classes"
            tests = Path(tmp) / "tests"
            classes.mkdir()
            tests.mkdir()
            sources = [str(p) for pattern in PURE for p in sorted(REMOTE.glob(pattern))]
            build = subprocess.run(["javac", "-nowarn", "-Xlint:-options", "--release", "8", "-d", str(classes)] + sources,
                                   capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            build = subprocess.run(["javac", "-nowarn", "-cp", str(classes), "-d", str(tests),
                                    str(ROOT / "tests" / "java" / "CryptoTest.java")], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run(["java", "-cp", f"{classes}:{tests}", "CryptoTest"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
            self.assertIn("all ok", run.stdout)


if __name__ == "__main__":
    unittest.main()
