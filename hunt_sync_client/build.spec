# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path

root = Path(SPECPATH)
a = Analysis(
    [str(root / 'tray_client.py')],
    pathex=[str(root)],
    binaries=[],
    # Explicit allowlist: do not bundle game XML, logs, state or local credentials.
    datas=[(str(root / 'distribution/config.json'), '.'), (str(root / 'assets'), 'assets')],
    hiddenimports=['pystray._win32', 'watchdog.observers.read_directory_changes',
                   'PIL.IcoImagePlugin', 'PIL.PngImagePlugin', 'win32api', 'win32event', 'winerror'],
    hookspath=[], hooksconfig={}, runtime_hooks=[],
    excludes=['tkinter', 'pytest'],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [],
    name='hunt_tracker',
    debug=False, bootloader_ignore_signals=False,
    strip=False, upx=False,
    console=False,
    disable_windowed_traceback=False,
    icon=str(root / 'assets/hunt_tracker.ico'),
    uac_admin=False,
)
