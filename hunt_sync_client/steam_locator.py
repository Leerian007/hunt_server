"""Find Hunt's attributes.xml through the Windows registry and Steam libraries.

Requires Python 3.10+ and only the standard library. Importing on other platforms
is supported for tests and for the config.json fallback.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
from typing import Any

try:
    import winreg
except ImportError:
    winreg = None

APP_ID = "594650"
DEFAULT_CONFIG = Path(__file__).with_name("config.json")
ATTRIBUTES_RELATIVE = Path("user/profiles/default/attributes.xml")
KNOWN_GAME_DIRS = ("Hunt Showdown", "Hunt Showdown 1896", "Hunt: Showdown 1896")


class LocatorError(RuntimeError):
    """The configuration is invalid or the attributes file cannot be found."""


def _unique(paths: list[Path]) -> list[Path]:
    result: list[Path] = []
    seen: set[str] = set()
    for path in paths:
        key = os.path.normcase(os.path.abspath(path))
        if key not in seen:
            seen.add(key)
            result.append(path)
    return result


def find_steam_install_paths() -> list[Path]:
    """Read user/machine Steam registrations, including both registry views."""
    if winreg is None:
        return []
    registrations = (
        (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamPath"),
        (winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam", "SteamExe"),
        (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam", "InstallPath"),
    )
    paths: list[Path] = []
    for hive, key_name, value_name in registrations:
        for view in (0, winreg.KEY_WOW64_32KEY, winreg.KEY_WOW64_64KEY):
            try:
                with winreg.OpenKey(hive, key_name, 0, winreg.KEY_READ | view) as key:
                    value, _ = winreg.QueryValueEx(key, value_name)
                if not isinstance(value, str) or not value.strip():
                    continue
                path = Path(os.path.expandvars(value.strip().strip('"')))
                if value_name == "SteamExe":
                    path = path.parent
                if path.is_dir():
                    paths.append(path)
            except OSError:
                # Missing keys, inaccessible views and stale installations are normal.
                continue
    return _unique(paths)


_TOKEN = re.compile(r'\s+|//[^\r\n]*|"((?:\\.|[^"\\])*)"|([{}])|([^\s{}"]+)')


def _read_vdf(path: Path) -> dict[str, Any]:
    """Parse Steam KeyValues without interpreting Windows paths as JSON escapes."""
    content = path.read_text(encoding="utf-8-sig")
    tokens: list[str] = []
    for match in _TOKEN.finditer(content):
        quoted, brace, bare = match.groups()
        if quoted is not None:
            tokens.append(re.sub(r'\\([\\"])', r'\1', quoted))
        elif brace is not None or bare is not None:
            tokens.append(brace or bare)
    index = 0

    def object_value(nested: bool = False) -> dict[str, Any]:
        nonlocal index
        result: dict[str, Any] = {}
        while index < len(tokens):
            key = tokens[index]
            index += 1
            if key == "}":
                if nested:
                    return result
                raise ValueError("Unexpected closing brace")
            if key == "{" or index >= len(tokens):
                raise ValueError("Invalid VDF key/value pair")
            value = tokens[index]
            index += 1
            if value == "}":
                raise ValueError("Missing VDF value")
            result[key.lower()] = object_value(True) if value == "{" else value
        if nested:
            raise ValueError("Unclosed VDF object")
        return result

    return object_value()


def _safe_vdf(path: Path) -> dict[str, Any]:
    try:
        return _read_vdf(path)
    except (OSError, UnicodeError, ValueError):
        return {}


def find_steam_libraries(steam_paths: list[Path]) -> list[Path]:
    """Include Steam's own library and modern/legacy libraryfolders.vdf entries."""
    libraries = list(steam_paths)
    for steam in steam_paths:
        for relative in ("steamapps/libraryfolders.vdf", "config/libraryfolders.vdf"):
            entries = _safe_vdf(steam / relative).get("libraryfolders", {})
            if not isinstance(entries, dict):
                continue
            for number, entry in entries.items():
                if not number.isdigit():
                    continue
                value = entry.get("path") if isinstance(entry, dict) else entry
                if isinstance(value, str) and value.strip():
                    libraries.append(Path(os.path.expandvars(value)))
    return _unique(libraries)


def _game_directories(library: Path) -> list[Path]:
    steamapps = library / "steamapps"
    manifest = _safe_vdf(steamapps / f"appmanifest_{APP_ID}.acf").get("appstate", {})
    directories: list[Path] = []
    if isinstance(manifest, dict):
        install_dir = manifest.get("installdir")
        # Steam installdir is a single folder name, not an arbitrary path.
        if (isinstance(install_dir, str) and install_dir not in ("", ".", "..")
                and not any(char in install_dir for char in '/\\:')):
            directories.append(steamapps / "common" / install_dir)
    directories.extend(steamapps / "common" / name for name in KNOWN_GAME_DIRS)
    return _unique(directories)


def locate_attributes_file(config_path: str | Path | None = None) -> Path:
    """Return an existing XML path; auto-detection takes precedence over config.

    config.json key: attributes_path. Relative custom paths resolve against the
    configuration file's directory, independent of the process working directory.
    Raises LocatorError with an actionable message if no file can be located.
    """
    for library in find_steam_libraries(find_steam_install_paths()):
        for game in _game_directories(library):
            candidate = game / ATTRIBUTES_RELATIVE
            if candidate.is_file():
                return candidate.resolve()

    config = Path(config_path) if config_path is not None else DEFAULT_CONFIG
    try:
        settings = json.loads(config.read_text(encoding="utf-8-sig"))
    except FileNotFoundError:
        settings = {}
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise LocatorError(f"无法读取配置文件 {config}：{exc}") from exc
    if not isinstance(settings, dict):
        raise LocatorError(f"{config} 的内容必须是 JSON 对象。")
    custom = settings.get("attributes_path", "")
    if not isinstance(custom, str):
        raise LocatorError("config.json 的 attributes_path 必须是字符串。")
    if custom.strip():
        candidate = Path(os.path.expandvars(custom.strip())).expanduser()
        if not candidate.is_absolute():
            candidate = config.resolve().parent / candidate
        if candidate.is_file():
            return candidate.resolve()
        raise LocatorError(f"自定义 attributes.xml 路径不存在或不是文件：{candidate}")
    raise LocatorError(
        "未找到《猎杀：对决》的 attributes.xml。请确认游戏已运行并生成该文件，"
        f"或在 {config} 的 attributes_path 中填写文件完整路径。"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="定位《猎杀：对决》attributes.xml")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG, help="自定义 config.json 路径")
    args = parser.parse_args()
    try:
        print(locate_attributes_file(args.config))
        return 0
    except LocatorError as exc:
        parser.exit(1, f"定位失败：{exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
