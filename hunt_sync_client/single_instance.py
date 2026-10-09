"""Hold a Windows named mutex for the complete application lifetime."""
import os


class SingleInstance:
    def __init__(self, name=r"Local\Hunt1896Tracker.Tray.v1"):
        self.name = name
        self.handle = None

    def acquire(self) -> bool:
        if os.name != "nt":
            raise RuntimeError("Hunt Tracker 托盘分发版仅支持 Windows")
        import win32api
        import win32event
        import winerror
        self.handle = win32event.CreateMutex(None, False, self.name)
        if win32api.GetLastError() == winerror.ERROR_ALREADY_EXISTS:
            self.close()
            return False
        return True

    def close(self):
        if self.handle is not None:
            self.handle.Close()
            self.handle = None
