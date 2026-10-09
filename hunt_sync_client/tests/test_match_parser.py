import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from match_parser import MatchParseError, parse_attributes


class MatchParserTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "attributes.xml"

    def write(self, pairs):
        root = ET.Element("Attributes")
        for key, value in pairs:
            ET.SubElement(root, "Attr", name=key, value=value)
        ET.ElementTree(root).write(self.path, encoding="utf-8", xml_declaration=True)
        return parse_attributes(self.path)

    def test_all_twelve_players_and_unicode(self):
        pairs = []
        for index in range(12):
            prefix = f"MissionBagPlayer_{index}_"
            for suffix, value in {"steamid": str(76561198000000000 + index),
                                  "name": f"猎人{index} & Co", "mmr": "2500", "kills": "2",
                                  "downs": "3", "downed_by_me": "1"}.items():
                pairs.append((prefix + suffix, value))
        result = self.write(pairs)
        self.assertEqual(len(result["players"]), 12)
        player = result["players"][11]
        self.assertNotIn("steam_id", player)
        self.assertNotIn("raw_attributes", player)
        self.assertEqual(player["name"], "猎人11 & Co")
        self.assertEqual((player["kills"], player["downs"], player["downed_by_me"]), (2, 3, 1))
        self.assertEqual(json.loads(json.dumps(result)), result)

    def test_sparse_slots_and_mixed_field_styles(self):
        result = self.write([("MissionBagPlayer_0_0_name", "A"),
                             ("MissionBagPlayer_0_mmr", "0"),
                             ("MissionBagPlayer_11_SteamID", "76561198000000011"),
                             ("MissionBagPlayer_11_downed_by_me", "2"),
                             ("MissionBagPlayer_12_name", "outside")])
        self.assertEqual([p["slot"] for p in result["players"]], [0, 11])
        self.assertEqual(result["players"][0]["mmr"], 0)
        self.assertIsNone(result["players"][1]["kills"])
        self.assertIsNone(result["players"][1]["downs"])

    def test_hash_ignores_order_and_non_match_data_but_tracks_match_changes(self):
        pairs = [("MissionBagPlayer_0_name", "A"), ("MissionBagMatchID", "round-1"),
                 ("MissionBagUnknownMetric", "3")]
        first = self.write(pairs)["match_hash"]
        second = self.write(list(reversed(pairs)) + [("AudioVolume", "50")])["match_hash"]
        self.assertEqual(first, second)
        self.assertEqual(len(first), 64)
        pairs[-1] = ("MissionBagUnknownMetric", "4")
        self.assertNotEqual(first, self.write(pairs)["match_hash"])

    def test_invalid_and_conflicting_fields_are_not_fabricated(self):
        result = self.write([("MissionBagPlayer_0_steamid", "0"),
                             ("MissionBagPlayer_0_mmr", "unknown"),
                             ("MissionBagPlayer_0_kills", "-1"),
                             ("MissionBagPlayer_0_name", "A"),
                             ("MissionBagPlayer_0_0_name", "B")])
        player = result["players"][0]
        for key in ("mmr", "kills", "name"):
            self.assertIsNone(player[key])
        self.assertEqual(len(result["warnings"]), 3)

    def test_incomplete_empty_and_conflicting_xml_fail(self):
        for text in ("<Attributes>", "<Attributes/>",
                     '<Attributes><Attr name="MissionBagKills"/></Attributes>'):
            self.path.write_text(text, encoding="utf-8")
            with self.assertRaises(MatchParseError):
                parse_attributes(self.path)
        with self.assertRaises(MatchParseError):
            self.write([("MissionBagKills", "1"), ("MissionBagKills", "2")])

    def test_actual_resource_keeps_all_nested_players_separate(self):
        sample = Path(__file__).resolve().parents[1] / "resource/attributes.xml"
        result = parse_attributes(sample)
        players = result["players"]
        self.assertEqual([(p["slot"], p["member_index"]) for p in players], [(0, 0), (0, 1), (1, 0)])
        self.assertEqual(players[0]["name"], "CaldwellConnoisseur")
        self.assertEqual(players[1]["name"], "BayouGhost_99")
        self.assertEqual([p["mmr"] for p in players[:2]], [2790, 2910])
        self.assertEqual([p["downed_by_me"] for p in players[:2]], [2, 1])
        self.assertEqual([p["killed_by_me"] for p in players[:2]], [1, 1])
        self.assertTrue(players[0]["bounty_picked_up"])
        self.assertFalse(players[0]["bounty_extracted"])
        self.assertTrue(players[2]["is_teammate"])
        self.assertTrue(all("steam_id" not in p and p["kills"] is None for p in players))
        self.assertNotIn("raw_match_attributes", result)
        self.assertEqual(result["metrics"]["kills"], 3)
        self.assertEqual(result["metrics"]["mmr"], 2865)
        self.assertEqual(result["warnings"], [])
        self.assertEqual(result["match_hash"], parse_attributes(sample)["match_hash"])


if __name__ == "__main__":
    unittest.main()
