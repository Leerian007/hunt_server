"""Read the most recent Steam account; no credentials are exported."""

from pathlib import Path
import re

try:
    from .steam_locator import _read_vdf, find_steam_install_paths
except ImportError:
    from steam_locator import _read_vdf, find_steam_install_paths


class SteamUserError(RuntimeError):
    pass


def read_recent_steam_id(steam_directory: str | Path) -> str:
    path = Path(steam_directory) / "config/loginusers.vdf"
    try:
        data = _read_vdf(path)
    except (OSError, UnicodeError, ValueError) as exc:
        raise SteamUserError(f"无法读取 Steam 用户配置：{path}") from exc
    users = data.get("users")
    if not isinstance(users, dict):
        raise SteamUserError(f"Steam 用户配置缺少 users 节点：{path}")
    recent = [steam_id for steam_id, node in users.items()
              if isinstance(node, dict) and node.get("mostrecent", "").strip() == "1"]
    if len(recent) != 1 or not re.fullmatch(r"[0-9]{17}", recent[0]):
        raise SteamUserError("无法确定唯一的 MostRecent=1 且 SteamID 为 17 位数字的账号，请先在 Steam 登录。")
    return recent[0]


def get_uploader_steam_id(steam_directory: str | Path | None = None) -> str:
    if steam_directory is not None:
        return read_recent_steam_id(steam_directory)
    roots = find_steam_install_paths()
    if not roots:
        raise SteamUserError("注册表中未找到 Steam 安装目录，请安装/登录 Steam 或指定 --steam-dir。")
    identities = set()
    for root in roots:
        if (root / "config/loginusers.vdf").is_file():
            identities.add(read_recent_steam_id(root))
    if len(identities) != 1:
        raise SteamUserError("未找到唯一的最近 Steam 账号，请指定 --steam-dir 并确认 Steam 登录状态。")
    return identities.pop()
