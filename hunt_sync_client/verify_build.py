"""Check frozen resources, PE GUI subsystem and duplicate-launch exit without opening a tray."""
from pathlib import Path
import subprocess

import pefile
from PyInstaller.archive.readers import CArchiveReader
from single_instance import SingleInstance


def main():
    executable = Path(__file__).resolve().parent / "dist/hunt_tracker.exe"
    with pefile.PE(str(executable)) as pe:
        assert pe.OPTIONAL_HEADER.Subsystem == 2, "Executable must use Windows GUI subsystem"
    archive = CArchiveReader(str(executable))
    assert "config.json" in archive.toc
    assert any(name.replace("\\", "/") == "assets/hunt_tracker.ico" for name in archive.toc)
    assert not any(name.replace("\\", "/").startswith("resource/") for name in archive.toc)
    mutex = SingleInstance()
    try:
        assert mutex.acquire(), "Close the running Hunt Tracker before this smoke test"
        result = subprocess.run([str(executable)], timeout=45, check=False)
        assert result.returncode == 0, f"Duplicate instance exited with {result.returncode}"
    finally:
        mutex.close()
    print("PASS: GUI subsystem, packaged config/icon and frozen duplicate-launch mutex")


if __name__ == "__main__":
    main()
