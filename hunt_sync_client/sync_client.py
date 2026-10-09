"""Watch stable XML snapshots and upload the exact Spring MatchSyncDTO contract."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import io
import json
import logging
import os
from pathlib import Path
import threading
import time
from urllib.parse import urlparse

import requests
from watchdog.events import FileSystemEventHandler
from watchdog.observers import Observer

try:
    from .match_parser import MatchParseError, parse_attributes
    from .steam_locator import DEFAULT_CONFIG, locate_attributes_file
    from .steam_user import get_uploader_steam_id
except ImportError:
    from match_parser import MatchParseError, parse_attributes
    from steam_locator import DEFAULT_CONFIG, locate_attributes_file
    from steam_user import get_uploader_steam_id

log = logging.getLogger("hunt.sync")


class SyncError(RuntimeError):
    pass


def load_config(path: Path) -> dict:
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(config, dict):
        raise SyncError("config.json 必须是对象")
    config.setdefault("backend_base_url", "http://localhost:8080")
    config.setdefault("sync_path", "/api/v1/matches/sync")
    config.setdefault("session_path", "/api/v1/auth/session")
    config.setdefault("token_env", "HUNT_SYNC_TOKEN")
    config.setdefault("request_timeout_seconds", 15)
    config.setdefault("upload_on_start", False)
    base = urlparse(config["backend_base_url"])
    if base.scheme not in ("http", "https") or not base.hostname or base.username or base.query or base.fragment:
        raise SyncError("backend_base_url 必须是有效 HTTP(S) 地址，可包含部署 context-path")
    for key in ("sync_path", "session_path"):
        value = config[key]
        if not isinstance(value, str) or not value.startswith("/") or value.startswith("//") or "?" in value or "#" in value:
            raise SyncError(f"{key} 必须是 / 开头的接口路径")
    if type(config["request_timeout_seconds"]) not in (int, float) or not 0 < config["request_timeout_seconds"] <= 120:
        raise SyncError("request_timeout_seconds 必须在 0～120 秒之间")
    if type(config["upload_on_start"]) is not bool:
        raise SyncError("upload_on_start 必须是布尔值")
    config["state_file"] = (path.resolve().parent / config.get("state_file", "sync_state.json")).resolve()
    if config.get("steam_directory"):
        config["steam_directory"] = str((path.resolve().parent / os.path.expandvars(config["steam_directory"])).resolve())
    return config


def read_snapshot(path: Path, sleep=time.sleep) -> tuple[dict, float]:
    """Initial read plus at most five retries; never open the game file for writing."""
    for attempt in range(6):
        try:
            before = path.stat()
            with path.open("rb") as stream:
                content = stream.read()
            after = path.stat()
            if (before.st_mtime_ns, before.st_size, before.st_ino) != (after.st_mtime_ns, after.st_size, after.st_ino):
                raise OSError("文件仍在写入")
            return parse_attributes(io.BytesIO(content)), after.st_mtime
        except (OSError, MatchParseError) as exc:
            if attempt == 5:
                raise SyncError("XML 读取/解析失败，已完成 5 次重试") from exc
            log.warning("文件占用、变化或 XML 未完整，0.5 秒后重试 %d/5", attempt + 1)
            sleep(0.5)
    raise AssertionError("unreachable")


def to_match_dto(match: dict, uploader: str, mtime: float) -> dict:
    metrics = match["metrics"]
    result = {
        "match_id": f"m_{uploader}_{match['match_hash']}",
        "match_time": datetime.fromtimestamp(mtime, timezone.utc).isoformat().replace("+00:00", "Z"),
        "map_name": metrics["map_name"],
        "game_mode": "Soul Survivor" if metrics["quick_play"] else "Bounty Hunt",
        "extracted": metrics["extracted"],
        "deaths": int(metrics["hunter_dead"]) if type(metrics["hunter_dead"]) is bool else None,
        "encountered_players": match["players"],
    }
    for key in ("kills", "team_kills", "assists", "team_wipes", "bounty_extracted", "mmr", "mmr_change"):
        result[key] = metrics[key]
    for key in ("quick_play", "hunter_dead", "extracted"):
        if type(metrics[key]) is not bool:
            raise SyncError(f"缺少有效单局字段 {key}，拒绝伪造默认值")
    if not isinstance(result["map_name"], str) or not result["map_name"].strip() or len(result["map_name"]) > 100:
        raise SyncError("map_name 为空或超出后端长度限制")
    for key in ("kills", "assists", "team_wipes", "bounty_extracted", "deaths", "team_kills", "mmr", "mmr_change"):
        value = result[key]
        if value is None and key in ("team_kills", "mmr", "mmr_change"):
            continue
        if type(value) is not int or not -(2**31) <= value < 2**31 or (key != "mmr_change" and value < 0):
            raise SyncError(f"{key} 不符合后端 Integer/非负校验")
    return result


class ApiClient:
    def __init__(self, config: dict, session=None, sleep=time.sleep, stop_event=None):
        self.config = config
        self.session = session or requests.Session()
        self.sleep = sleep
        self.stop_event = stop_event

    def request(self, method: str, path: str, token: str, body=None) -> dict:
        url = self.config["backend_base_url"].rstrip("/") + path
        # Three retries after the initial attempt, using the same payload/ID.
        for attempt in range(4):
            if self.stop_event is not None and self.stop_event.is_set():
                raise SyncError("客户端正在退出")
            log.info("%s %s attempt=%d/4 body=%s", method, url, attempt + 1,
                     json.dumps(body, ensure_ascii=False) if body is not None else "<none>")
            try:
                response = self.session.request(method, url, json=body,
                    headers={"Authorization": f"Bearer {token}"},
                    timeout=self.config["request_timeout_seconds"], allow_redirects=False)
                log.info("HTTP %s response=%s", response.status_code, response.text.replace(token, "<redacted>"))
                retryable = response.status_code in (408, 429) or response.status_code >= 500
                if not retryable:
                    if not 200 <= response.status_code < 300:
                        raise SyncError(f"HTTP {response.status_code}，请检查 Token、账号和请求配置")
                    try:
                        result = response.json()
                    except ValueError as exc:
                        raise SyncError("后端响应不是 JSON，未标记上传成功") from exc
                    if not isinstance(result, dict) or type(result.get("code")) is not int or result["code"] != 0:
                        raise SyncError("后端未返回成功业务码，未标记上传成功")
                    return result
            except requests.RequestException as exc:
                log.warning("网络请求失败：%s", type(exc).__name__)
            if attempt == 3:
                raise SyncError("网络请求失败，已完成最多 3 次重试")
            delay = 2**attempt
            log.info("%s 秒后重试", delay)
            if self.stop_event is not None:
                if self.stop_event.wait(delay):
                    raise SyncError("客户端正在退出")
            else:
                self.sleep(delay)
        raise AssertionError("unreachable")

    def upload(self, uploader: str, body: dict):
        token = os.environ.get(self.config["token_env"], "").strip()
        if not token:
            raise SyncError(f"请设置 {self.config['token_env']} 环境变量为登录 JWT（不是 JWT_SECRET）")
        session = self.request("GET", self.config["session_path"], token)
        data = session.get("data")
        if not isinstance(data, dict) or data.get("authenticated") is not True or data.get("steam_id") != uploader:
            raise SyncError("JWT 登录身份与 loginusers.vdf 的最近账号不一致，已阻止上传")
        self.request("POST", self.config["sync_path"], token, body)


class SyncProcessor:
    def __init__(self, path: Path, config: dict, api=None):
        self.path, self.config = path, config
        self.api = api or ApiClient(config)
        self.state_path = Path(config["state_file"])
        self.successes: set[str] = set()
        if self.state_path.exists():
            data = json.loads(self.state_path.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or not isinstance(data.get("successful_ids"), list) or not all(isinstance(x, str) for x in data["successful_ids"]):
                raise SyncError("防重状态文件格式错误")
            self.successes.update(data["successful_ids"])

    def process(self):
        uploader = get_uploader_steam_id(self.config.get("steam_directory") or None)
        match, mtime = read_snapshot(self.path)
        body = to_match_dto(match, uploader, mtime)
        # A different backend must receive its own copy.
        key = self.config["backend_base_url"].rstrip("/") + self.config["sync_path"] + "|" + body["match_id"]
        if key in self.successes:
            log.info("跳过已成功同步对局 %s", body["match_id"])
            return False
        for warning in match["warnings"]:
            log.warning("解析提示：%s", warning)
        self.api.upload(uploader, body)
        updated = self.successes | {key}
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        temporary.write_text(json.dumps({"successful_ids": sorted(updated)}, indent=2), encoding="utf-8")
        temporary.replace(self.state_path)
        self.successes = updated
        log.info("同步成功 %s", body["match_id"])
        return True


class DebouncedHandler(FileSystemEventHandler):
    """One worker; events during an upload remain pending for the next iteration."""
    def __init__(self, path: Path, process):
        self.target = os.path.normcase(str(path.resolve()))
        self.process = process
        self.condition = threading.Condition()
        self.deadline = None
        self.stopped = False

    def trigger(self):
        with self.condition:
            self.deadline = time.monotonic() + 2.0
            self.condition.notify_all()

    def on_any_event(self, event):
        if event.is_directory or event.event_type not in ("modified", "created", "moved"):
            return
        for name in (event.src_path, getattr(event, "dest_path", "")):
            if name and os.path.normcase(str(Path(name).resolve())) == self.target:
                self.trigger()
                break

    def stop(self):
        with self.condition:
            self.stopped = True
            self.condition.notify_all()

    def run(self):
        while True:
            with self.condition:
                while not self.stopped:
                    if self.deadline is None:
                        self.condition.wait()
                    else:
                        remaining = self.deadline - time.monotonic()
                        if remaining > 0:
                            self.condition.wait(remaining)
                        else:
                            self.deadline = None
                            break
                if self.stopped:
                    return
            try:
                self.process()
            except Exception as exc:
                log.error("处理失败（未记录成功）：%s", exc)


def main():
    parser = argparse.ArgumentParser(description="Hunt 对局上传客户端")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--once", action="store_true", help="立即同步现有文件一次，不启动监听")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        config = load_config(args.config)
        path = locate_attributes_file(args.config)
        processor = SyncProcessor(path, config)
        if args.once:
            processor.process()
            return 0
        handler = DebouncedHandler(path, processor.process)
        observer = Observer()
        observer.schedule(handler, str(path.parent), recursive=False)
        worker = threading.Thread(target=handler.run, name="match-sync-worker")
        observer.start()
        worker.start()
        log.info("监听 %s；最后一次修改后 2 秒处理；Ctrl+C 退出", path)
        if config["upload_on_start"]:
            handler.trigger()
        try:
            while worker.is_alive():
                worker.join(0.5)
        except KeyboardInterrupt:
            log.info("停止监听，等待当前请求结束")
        finally:
            observer.stop()
            handler.stop()
            observer.join()
            worker.join()
        return 0
    except Exception as exc:
        log.error("客户端失败：%s", exc)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
