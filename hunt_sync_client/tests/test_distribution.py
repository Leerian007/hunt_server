import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runtime_paths
from single_instance import SingleInstance
import autostart


class DistributionTest(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Windows mutex test")
    def test_real_mutex_duplicate_and_release(self):
        name = "Local\\HuntTracker.Test." + uuid.uuid4().hex
        first, second = SingleInstance(name), SingleInstance(name)
        try:
            self.assertTrue(first.acquire())
            self.assertFalse(second.acquire())
            first.close()
            self.assertTrue(second.acquire())
        finally:
            first.close()
            second.close()

    def test_frozen_defaults_copied_once_outside_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            bundle.mkdir()
            (bundle / "config.json").write_text('{"default":true}', encoding="utf-8")
            with patch.object(sys, "frozen", True, create=True), \
                    patch.object(sys, "_MEIPASS", str(bundle), create=True), \
                    patch.dict(os.environ, {"LOCALAPPDATA": str(root / "local")}):
                config = runtime_paths.default_config_path()
                self.assertEqual(config, root / "local/HuntTracker/config.json")
                self.assertEqual(config.read_text(), '{"default":true}')
                config.write_text('{"custom":true}')
                self.assertEqual(runtime_paths.default_config_path().read_text(), '{"custom":true}')

    def test_frozen_autostart_points_at_executable_not_extraction_folder(self):
        with patch.object(sys, "frozen", True, create=True), \
                patch.object(sys, "executable", "C:/My Apps/hunt_tracker.exe"):
            command = autostart.startup_command(Path("config.json"))
            self.assertIn("hunt_tracker.exe", command)
            self.assertNotIn("python", command)
            self.assertNotIn("tray_client.py", command)
