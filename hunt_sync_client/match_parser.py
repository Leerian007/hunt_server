"""Parse MissionBag player attributes without inventing missing statistics."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

try:
    from .steam_locator import LocatorError, locate_attributes_file
except ImportError:
    from steam_locator import LocatorError, locate_attributes_file


class MatchParseError(ValueError):
    """An unreadable or incomplete attributes snapshot cannot be parsed."""


# Preserve both indices: _0_0 and _0_1 identify different players in the sample.
PLAYER_KEY = re.compile(r"^MissionBagPlayer_(\d+)_(?:(\d+)_)?([a-z].*)$", re.IGNORECASE)
ALIASES = {
    "name": ("name", "nickname", "persona_name"),
    "mmr": ("mmr",),
    "kills": ("kills", "num_kills", "numkills"),
    "downs": ("downs", "downed", "num_downs", "numdowns"),
    # Interaction counters are separate from the player's own total kills/downs.
    "killed_by_me": ("killed_by_me",),
    "downed_by_me": ("downed_by_me",),
    "killed_me": ("killed_me",),
    "downed_me": ("downed_me",),
    "bounty_picked_up": ("bountypickedup",),
    "bounty_extracted": ("bountyextracted",),
    "is_teammate": ("is_teammate",),
}
BOOLEAN_FIELDS = {"bounty_picked_up", "bounty_extracted", "is_teammate"}


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_attributes(path: str | Path) -> dict:
    """Read a single snapshot, return a JSON-serializable match/player document.

    Hash version 1 covers every MissionBag* key/value, including unrecognized
    fields. It excludes XML ordering, file paths, mtimes and parse timestamps.
    """
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise MatchParseError(f"无法读取完整 attributes.xml：{exc}") from exc

    attributes: dict[str, str] = {}
    for element in root.iter():
        if _local_name(element.tag).lower() != "attr":
            continue
        name = element.get("name", "")
        if not name.lower().startswith("missionbag"):
            continue
        if "value" not in element.attrib:
            raise MatchParseError(f"字段 {name} 缺少 value，可能是未完成的快照。")
        value = element.attrib["value"]
        if name in attributes and attributes[name] != value:
            raise MatchParseError(f"字段 {name} 存在冲突的重复值。")
        attributes[name] = value
    if not attributes:
        raise MatchParseError("未找到 MissionBag 字段，无法生成对局数据。")

    canonical = json.dumps(attributes, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    match_hash = hashlib.sha256(("hunt-missionbag-v1\n" + canonical).encode("utf-8")).hexdigest()
    slots: dict[tuple[int, int], dict[str, list[str]]] = {}
    warnings: list[str] = []
    for key, value in sorted(attributes.items()):
        match = PLAYER_KEY.fullmatch(key)
        if not match:
            continue
        outer, member, suffix = int(match[1]), int(match[2] or 0), match[3].lower()
        if outer not in range(12):
            continue
        index = (outer, member)
        slots.setdefault(index, {}).setdefault(suffix, []).append(value)

    def field(index: tuple[int, int], name: str):
        values = {value.strip() for alias in ALIASES[name]
                  for value in slots[index].get(alias, []) if value.strip()}
        if not values:
            return None
        if len(values) != 1:
            warnings.append(f"player[{index}].{name}: 字段别名值冲突，保留为 null")
            return None
        value = values.pop()
        if name == "name":
            return value
        if name in BOOLEAN_FIELDS:
            if value.lower() in ("true", "1"):
                return True
            if value.lower() in ("false", "0"):
                return False
            warnings.append(f"player[{index}].{name}: 非法布尔值，保留为 null")
            return None
        if re.fullmatch(r"[0-9]+", value):
            return int(value)
        warnings.append(f"player[{index}].{name}: 非法数值，保留为 null")
        return None

    players = []
    for index in sorted(slots):
        player = {"slot": index[0], "member_index": index[1],
                  **{name: field(index, name) for name in ALIASES}}
        players.append(player)
    # Whitelist round metrics rather than exporting arbitrary raw attributes.
    metrics = {}
    numeric = {
        "kills": "MissionBagNumKills", "downs": "MissionBagNumDowned",
        "assists": "MissionBagNumAssists", "team_kills": "MissionBagTeamKills",
        "team_wipes": "MissionBagTeamWipes", "bounty_extracted": "MissionBagBounty",
        "mmr": "MissionBagPlayerMMR", "mmr_change": "MissionBagPlayerMMRChange",
        "downed_by_me": "MissionBagNumDownedByMe", "killed_by_me": "MissionBagNumKilledByMe",
        "downed_me": "MissionBagNumDownedMe", "killed_me": "MissionBagNumKilledMe",
    }
    for name, key in numeric.items():
        value = attributes.get(key, "").strip()
        pattern = r"[+-]?[0-9]+" if name == "mmr_change" else r"[0-9]+"
        metrics[name] = int(value) if re.fullmatch(pattern, value) else None
        if value and metrics[name] is None:
            warnings.append(f"match.{name}: 非法数值，保留为 null")
    metrics["map_name"] = attributes.get("MissionBagMapName")
    for name, key in {"extracted": "MissionBagIsExtract", "hunter_dead": "MissionBagIsHunterDead",
                      "quick_play": "MissionBagIsQuickPlay"}.items():
        value = attributes.get(key, "").strip().lower()
        metrics[name] = {"true": True, "1": True, "false": False, "0": False}.get(value)
    return {
        "schema_version": 2,
        "match_hash": match_hash,
        "metrics": metrics,
        "players": players,
        "warnings": warnings,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="解析 Hunt MissionBag 玩家数据并生成稳定 SHA-256 指纹")
    parser.add_argument("xml", nargs="?", type=Path, help="attributes.xml 路径；省略则自动定位")
    parser.add_argument("--config", type=Path, help="自动定位的 config.json")
    parser.add_argument("--output", type=Path, help="输出 JSON 文件；省略则输出到控制台")
    args = parser.parse_args()
    try:
        result = parse_attributes(args.xml if args.xml else locate_attributes_file(args.config))
        text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(text, encoding="utf-8")
        else:
            print(text, end="")
    except (LocatorError, MatchParseError, OSError) as exc:
        parser.exit(1, f"解析失败：{exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
