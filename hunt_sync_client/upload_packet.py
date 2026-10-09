"""Build a local upload envelope; transport and JWT verification remain separate."""

import argparse
import json
from pathlib import Path

try:
    from .match_parser import MatchParseError, parse_attributes
    from .steam_locator import LocatorError, locate_attributes_file
    from .steam_user import SteamUserError, get_uploader_steam_id
except ImportError:
    from match_parser import MatchParseError, parse_attributes
    from steam_locator import LocatorError, locate_attributes_file
    from steam_user import SteamUserError, get_uploader_steam_id


def build_upload_packet(xml_path: str | Path | None = None,
                        steam_directory: str | Path | None = None,
                        config_path: str | Path | None = None) -> dict:
    uploader = get_uploader_steam_id(steam_directory)
    match = parse_attributes(xml_path if xml_path is not None else locate_attributes_file(config_path))
    return {"uploader_steam_id": uploader, "match": match}


def main() -> int:
    parser = argparse.ArgumentParser(description="解析对局并封装 Steam 上传者账号")
    parser.add_argument("xml", nargs="?", type=Path)
    parser.add_argument("--steam-dir", type=Path, help="Steam 安装目录；默认读取注册表")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        packet = build_upload_packet(args.xml, args.steam_dir, args.config)
        text = json.dumps(packet, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(text, encoding="utf-8")
        else:
            print(text, end="")
    except (SteamUserError, LocatorError, MatchParseError, OSError) as exc:
        parser.exit(1, f"生成上传包失败：{exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
