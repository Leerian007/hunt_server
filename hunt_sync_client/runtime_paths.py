"""Keep writable state outside PyInstaller's temporary extraction directory."""
import os
from pathlib import Path
import sys


def resource_path(name: str) -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / name


def default_config_path() -> Path:
    if not getattr(sys, "frozen", False):
        return Path(__file__).with_name("config.json")
    directory = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "HuntTracker"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / "config.json"
    if not target.exists():
        # Exclusive creation preserves existing user settings across upgrades.
        content = resource_path("config.json").read_bytes()
        try:
            with target.open("xb") as stream:
                stream.write(content)
        except FileExistsError:
            pass
    return target
