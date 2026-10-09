"""Windowless tray entry point. Icon loop stays on the main thread."""
from __future__ import annotations

import argparse
from datetime import datetime
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path
import threading

from PIL import Image, ImageDraw
import pystray
from watchdog.observers import Observer

try:
    from . import autostart
    from .single_instance import SingleInstance
    from .runtime_paths import default_config_path
    from .sync_client import ApiClient, DebouncedHandler, SyncProcessor, load_config
    from .steam_locator import DEFAULT_CONFIG, locate_attributes_file
except ImportError:
    import autostart
    from single_instance import SingleInstance
    from runtime_paths import default_config_path
    from sync_client import ApiClient, DebouncedHandler, SyncProcessor, load_config
    from steam_locator import DEFAULT_CONFIG, locate_attributes_file

log = logging.getLogger("hunt.tray")


def make_icon(healthy: bool) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((3, 3, 61, 61), fill="#279653" if healthy else "#e59128")
    draw.ellipse((17, 17, 47, 47), outline="white", width=3)
    draw.line((32, 10, 32, 54), fill="white", width=3)
    draw.line((10, 32, 54, 32), fill="white", width=3)
    return image


class TrayApp:
    def __init__(self, config_path: Path):
        self.config_path = config_path.resolve()
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.phase = "初始化中"
        self.last_upload = "尚无成功上传"
        self.backend = "尚未验证"
        self.healthy = False
        self.autostart_enabled = False
        self.handler = None
        self.observer = None
        self.processor = None
        self.api = None
        self.manual_pending = threading.Event()
        self.worker = threading.Thread(target=self._background, name="hunt-tray-worker")
        self.icon = pystray.Icon("hunt1896", make_icon(False), "Hunt 战绩同步", menu=pystray.Menu(
            pystray.MenuItem(lambda item: self.label("phase", "状态"), None, enabled=False),
            pystray.MenuItem(lambda item: self.label("last_upload", "上次上传"), None, enabled=False),
            pystray.MenuItem(lambda item: self.label("backend", "后端"), None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("手动上传当前对局", self.manual_upload),
            pystray.MenuItem("开机自启（用户登录时）", self.toggle_autostart,
                             checked=lambda item: self.autostart_enabled),
            pystray.MenuItem("退出", self.quit),
        ))

    def label(self, field: str, title: str):
        with self.lock:
            return f"{title}：{getattr(self, field)}"

    def update(self, **values):
        with self.lock:
            for key, value in values.items():
                setattr(self, key, value)
            title = f"Hunt 同步：{self.phase}"[:120]
            healthy = self.healthy
        self.icon.title = title
        self.icon.icon = make_icon(healthy)
        self.icon.update_menu()

    def manual_upload(self, icon=None, item=None):
        # Never perform disk/network operations on the tray message thread.
        self.manual_pending.set()
        with self.lock:
            handler = self.handler
        if handler is not None:
            handler.trigger()

    def toggle_autostart(self, icon=None, item=None):
        try:
            desired = not autostart.is_enabled(self.config_path)
            autostart.set_enabled(desired, self.config_path)
            self.autostart_enabled = desired
            self.icon.update_menu()
        except (OSError, RuntimeError) as exc:
            log.error("自启切换失败：%s", exc)
            self.update(phase="自启设置失败，请查看日志")

    def quit(self, icon=None, item=None):
        self.stop_event.set()
        with self.lock:
            handler = self.handler
        if handler is not None:
            handler.stop()
        self.update(phase="退出中，等待当前请求结束", healthy=False)
        self.icon.stop()

    def _process(self):
        self.manual_pending.clear()
        if self.stop_event.is_set():
            return
        self.update(phase="解析/上传中", healthy=False)
        try:
            if self.processor.process():
                stamp = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S")
                self.update(phase="监听中", last_upload=stamp, backend="上次上传连接正常", healthy=True)
            else:
                self.update(phase="监听中（当前对局已同步）", healthy=True)
        except Exception as exc:
            log.error("上传失败：%s", exc)
            self.update(phase="监听中（上传失败，见日志）", backend="最近处理失败，连接未确认", healthy=False)

    def _background(self):
        try:
            try:
                self.autostart_enabled = autostart.is_enabled(self.config_path)
            except (OSError, RuntimeError):
                log.warning("无法读取自启设置")
            # Steam or the game drive may not be available immediately at logon.
            while not self.stop_event.is_set():
                try:
                    config = load_config(self.config_path)
                    path = locate_attributes_file(self.config_path)
                    self.api = ApiClient(config, stop_event=self.stop_event)
                    self.processor = SyncProcessor(path, config, self.api)
                    handler = DebouncedHandler(path, self._process)
                    observer = Observer()
                    observer.schedule(handler, str(path.parent), recursive=False)
                    observer.start()
                    with self.lock:
                        self.handler, self.observer = handler, observer
                    if self.stop_event.is_set():
                        handler.stop()
                    else:
                        self.update(phase="监听中", healthy=True)
                        if config["upload_on_start"] or self.manual_pending.is_set():
                            handler.trigger()
                    handler.run()
                    break
                except Exception as exc:
                    log.error("监听初始化失败：%s", exc)
                    if self.api is not None:
                        self.api.session.close()
                        self.api = None
                    self.update(phase="等待 Steam/配置/游戏文件，5秒后重试", healthy=False)
                    self.stop_event.wait(5)
        finally:
            if self.observer is not None:
                self.observer.stop()
                self.observer.join()
            if self.api is not None:
                self.api.session.close()

    def run(self):
        # pystray's default setup only exposes the icon; network work has its own worker.
        self.worker.start()
        try:
            self.icon.run()
        finally:
            self.stop_event.set()
            with self.lock:
                if self.handler is not None:
                    self.handler.stop()
            self.worker.join()


def main():
    parser = argparse.ArgumentParser(description="Hunt 战绩同步系统托盘")
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    instance = SingleInstance()
    try:
        if not instance.acquire():
            return 0  # Duplicate double-click exits silently before starting any worker.
        config_path = args.config or default_config_path()
        log_directory = config_path.resolve().parent / "logs"
        log_directory.mkdir(parents=True, exist_ok=True)
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                            handlers=[RotatingFileHandler(log_directory / "tray.log", maxBytes=2_000_000,
                                                          backupCount=3, encoding="utf-8")])
        TrayApp(config_path).run()
    except Exception:
        log.exception("托盘启动失败")
        return 1
    finally:
        logging.shutdown()
        instance.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
