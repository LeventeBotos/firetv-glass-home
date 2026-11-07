"""glasshome/record.py: the record that makes Glass Home's undo exact.

The stick is replaced by a function that answers `settings get` and `dumpsys package` from a dict, so the real
record.py logic runs and only the ADB transport is simulated.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("glasshome_record", ROOT / "glasshome" / "record.py")
record = importlib.util.module_from_spec(spec)
spec.loader.exec_module(record)

AMAZON = "com.amazon.ftv.screensaver/.app.services.ScreensaverService"


def stick(components=AMAZON, enabled="1", granted=False):
    """What the stick would answer."""
    def shell(command):
        if command == "settings get secure screensaver_components":
            return (components or "null") + "\n"
        if command == "settings get secure screensaver_enabled":
            return (enabled or "null") + "\n"
        if command.startswith("dumpsys package"):
            return ("    android.permission.WRITE_SECURE_SETTINGS: granted=true\n" if granted
                    else "    android.permission.READ_LOGS: granted=true\n")
        raise AssertionError(f"unexpected command {command!r}")
    return shell


class RecordTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "glasshome-test.json"

    def tearDown(self):
        self.dir.cleanup()

    def test_records_the_starting_state(self):
        self.assertTrue(record.save(self.path, stick()))
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved["settings"], {"screensaver_components": AMAZON, "screensaver_enabled": "1"})
        self.assertFalse(saved["write_secure_settings_granted"])

    def test_first_record_wins(self):
        record.save(self.path, stick())
        # a retry after Glass Home already changed the screensaver must not record the changed state
        self.assertFalse(record.save(self.path, stick(components="com.glasshome/.GlassDream")))
        self.assertEqual(json.loads(self.path.read_text())["settings"]["screensaver_components"], AMAZON)

    def test_restore_puts_back_what_was_recorded(self):
        record.save(self.path, stick(enabled="0"))
        commands = record.restore_commands(json.loads(self.path.read_text()))
        self.assertIn(f"settings put secure screensaver_components {AMAZON}", commands)
        self.assertIn("settings put secure screensaver_enabled 0", commands)
        self.assertIn("pm revoke com.glasshome android.permission.WRITE_SECURE_SETTINGS", commands)

    def test_permission_already_granted_stays_granted(self):
        record.save(self.path, stick(granted=True))
        commands = record.restore_commands(json.loads(self.path.read_text()))
        self.assertFalse([c for c in commands if c.startswith("pm revoke")])

    def test_a_setting_that_did_not_exist_is_deleted_again(self):
        record.save(self.path, stick(components=None))
        commands = record.restore_commands(json.loads(self.path.read_text()))
        self.assertIn("settings delete secure screensaver_components", commands)

    def test_unsafe_values_from_the_stick_are_refused(self):
        with self.assertRaises(SystemExit):
            record.save(self.path, stick(components="a/.B; rm -rf /sdcard"))
        self.assertFalse(self.path.exists())

    def test_unsafe_values_in_an_edited_record_are_refused(self):
        bad = {"version": 1, "write_secure_settings_granted": True,
               "settings": {"screensaver_components": "x/.Y && reboot", "screensaver_enabled": "1"}}
        with self.assertRaises(SystemExit):
            record.restore_commands(bad)

    def test_a_note_from_the_first_install_is_kept(self):
        self.path.write_text(json.dumps({"host": "tv", "glasshome_installed_before": False}))
        self.assertTrue(record.save(self.path, stick()))
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved["host"], "tv")
        self.assertEqual(saved["version"], 1)

    def test_an_unreadable_record_is_left_alone(self):
        self.path.write_text("{ not json")
        with self.assertRaises(SystemExit):
            record.save(self.path, stick())
        self.assertEqual(self.path.read_text(), "{ not json")

    def test_without_a_record_undo_goes_back_to_amazons_default(self):
        self.assertIn("screensaver_default_component", record.fallback_commands()[0])


if __name__ == "__main__":
    unittest.main()
