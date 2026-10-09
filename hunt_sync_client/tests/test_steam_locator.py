import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import steam_locator as locator


class SteamLocatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, text=""):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_secondary_library_manifest_and_automatic_precedence(self):
        steam = self.root / "Steam"
        library = self.root / "游戏库"
        escaped = str(library).replace("\\", "\\\\")
        self.write(steam / "steamapps/libraryfolders.vdf",
                   f'"libraryfolders" {{ "1" {{ "path" "{escaped}" "apps" {{ "594650" "100" }} }} }}')
        self.write(library / "steamapps/appmanifest_594650.acf",
                   '"AppState" { "appid" "594650" "installdir" "Custom Hunt Folder" }')
        target = self.write(library / "steamapps/common/Custom Hunt Folder" / locator.ATTRIBUTES_RELATIVE)
        config = self.write(self.root / "config.json", "invalid JSON ignored on automatic success")
        with patch.object(locator, "find_steam_install_paths", return_value=[steam]):
            self.assertEqual(locator.locate_attributes_file(config), target.resolve())

    def test_legacy_library_and_missing_manifest(self):
        steam = self.root / "Steam"
        library = self.root / "Library"
        self.write(steam / "config/libraryfolders.vdf", f'// comment\n"LibraryFolders" {{ "1" "{library.as_posix()}" }}')
        target = self.write(library / "steamapps/common/Hunt Showdown" / locator.ATTRIBUTES_RELATIVE)
        with patch.object(locator, "find_steam_install_paths", return_value=[steam]):
            self.assertEqual(locator.locate_attributes_file(self.root / "absent.json"), target.resolve())

    def test_config_relative_path_and_invalid_config(self):
        target = self.write(self.root / "custom/attributes.xml")
        config = self.write(self.root / "config.json", json.dumps({"attributes_path": "custom/attributes.xml"}))
        with patch.object(locator, "find_steam_install_paths", return_value=[]):
            self.assertEqual(locator.locate_attributes_file(config), target.resolve())
            for content in ('{invalid', '[]', '{"attributes_path": 123}', '{"attributes_path":"missing.xml"}', '{}'):
                self.write(config, content)
                with self.assertRaises(locator.LocatorError):
                    locator.locate_attributes_file(config)

    def test_broken_vdf_does_not_block_config_fallback(self):
        steam = self.root / "Steam"
        self.write(steam / "steamapps/libraryfolders.vdf", '"libraryfolders" {')
        target = self.write(self.root / "attributes.xml")
        config = self.write(self.root / "config.json", json.dumps({"attributes_path": str(target)}))
        with patch.object(locator, "find_steam_install_paths", return_value=[steam]):
            self.assertEqual(locator.locate_attributes_file(config), target.resolve())

    def test_registry_reads_both_views_and_deduplicates(self):
        steam = self.root / "Steam"
        steam.mkdir()
        registry = MagicMock()
        registry.KEY_READ = 1
        registry.KEY_WOW64_32KEY = 2
        registry.KEY_WOW64_64KEY = 4
        registry.QueryValueEx.side_effect = lambda key, name: (str(steam / "steam.exe" if name == "SteamExe" else steam), 1)
        with patch.object(locator, "winreg", registry):
            self.assertEqual(locator.find_steam_install_paths(), [steam])
        self.assertEqual(registry.OpenKey.call_count, 9)

    def test_missing_registry_keys_are_ignored(self):
        registry = MagicMock()
        registry.KEY_READ, registry.KEY_WOW64_32KEY, registry.KEY_WOW64_64KEY = 1, 2, 4
        registry.OpenKey.side_effect = FileNotFoundError
        with patch.object(locator, "winreg", registry):
            self.assertEqual(locator.find_steam_install_paths(), [])


if __name__ == "__main__":
    unittest.main()
