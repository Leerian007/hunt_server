"""Current-user Windows logon startup; only this application's value is changed."""
from pathlib import Path
import subprocess
import sys

try:
    import winreg
except ImportError:
    winreg = None

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "Hunt1896SyncClient"


def startup_command(config_path: Path) -> str:
    executable = Path(sys.executable)
    if not getattr(sys, "frozen", False):
        executable = executable.with_name("pythonw.exe")
        if not executable.is_file():
            raise RuntimeError("未找到 pythonw.exe，无法配置无窗口自启")
        args = [str(executable), str(Path(__file__).with_name("tray_client.py").resolve())]
    else:
        args = [str(executable)]
    return subprocess.list2cmdline(args + ["--config", str(config_path.resolve())])


def is_enabled(config_path: Path) -> bool:
    if winreg is None:
        return False
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_READ) as key:
            value, _ = winreg.QueryValueEx(key, VALUE_NAME)
        return value == startup_command(config_path)
    except FileNotFoundError:
        return False


def set_enabled(enabled: bool, config_path: Path):
    if winreg is None:
        raise RuntimeError("自启设置仅支持 Windows")
    command = startup_command(config_path) if enabled else None
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, command)
        else:
            try:
                winreg.DeleteValue(key, VALUE_NAME)
            except FileNotFoundError:
                pass
