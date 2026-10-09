from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from steam_user import SteamUserError, get_uploader_steam_id, read_recent_steam_id
from upload_packet import build_upload_packet


class UploadPacketTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "config").mkdir()

    def write(self, nodes):
        (self.root / "config/loginusers.vdf").write_text('"users" { ' + nodes + ' }', encoding="utf-8-sig")

    def test_selects_recent_not_first_user_and_wraps_match(self):
        self.write('"76561198000000000" { "MostRecent" "0" } '
                   '"76561198000000001" { "MostRecent" "1" "AccountName" "private" }')
        with patch("steam_user.find_steam_install_paths", return_value=[self.root]):
            self.assertEqual(get_uploader_steam_id(), "76561198000000001")
        sample = Path(__file__).resolve().parents[1] / "resource/attributes.xml"
        packet = build_upload_packet(sample, self.root)
        self.assertEqual(set(packet), {"uploader_steam_id", "match"})
        self.assertEqual(packet["uploader_steam_id"], "76561198000000001")
        self.assertNotIn("private", str(packet))
        self.assertTrue(all("steam_id" not in p for p in packet["match"]["players"]))
        first_hash = packet["match"]["match_hash"]
        self.write('"76561198000000002" { "MostRecent" "1" }')
        self.assertEqual(build_upload_packet(sample, self.root)["match"]["match_hash"], first_hash)

    def test_missing_invalid_or_ambiguous_identity_fails(self):
        with self.assertRaises(SteamUserError):
            read_recent_steam_id(self.root)
        for nodes in ('', '"123" { "MostRecent" "1" }',
                      '"76561198000000000" { "MostRecent" "0" }',
                      '"76561198000000000" { "MostRecent" "1" } "76561198000000001" { "MostRecent" "1" }'):
            self.write(nodes)
            with self.assertRaises(SteamUserError):
                read_recent_steam_id(self.root)

    def test_malformed_and_no_registry(self):
        (self.root / "config/loginusers.vdf").write_text('"users" {', encoding="utf-8")
        with self.assertRaises(SteamUserError):
            read_recent_steam_id(self.root)
        with patch("steam_user.find_steam_install_paths", return_value=[]):
            with self.assertRaises(SteamUserError):
                get_uploader_steam_id()
