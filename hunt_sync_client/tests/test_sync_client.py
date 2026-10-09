import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

import requests
from watchdog.events import FileModifiedEvent, FileMovedEvent

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sync_client import ApiClient, DebouncedHandler, SyncError, SyncProcessor, load_config, read_snapshot, to_match_dto

SAMPLE = Path(__file__).resolve().parents[1] / "resource/attributes.xml"
ID = "76561198000000000"


class SyncClientTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config_path = Path(self.temp.name) / "config.json"
        self.config_path.write_text('{"backend_base_url":"http://localhost:8080/context"}', encoding="utf-8")
        self.config = load_config(self.config_path)

    def response(self, status=200, data=None):
        data = data if data is not None else {"code": 0, "data": None}
        return Mock(status_code=status, text=json.dumps(data), json=Mock(return_value=data))

    def test_dto_matches_actual_java_record(self):
        match, mtime = read_snapshot(SAMPLE)
        body = to_match_dto(match, ID, mtime)
        import re
        dto = (Path(__file__).resolve().parents[2] / "src/main/java/com/guxx/ray/ai/match/MatchSyncDTO.java").read_text()
        names = re.findall(r"(?:String|Instant|Boolean|Integer|List<@NotNull Map<String, Object>>)\s+(\w+)", dto.split("public record MatchSyncDTO(")[1].split(") {")[0])
        snake = lambda name: re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
        self.assertEqual(set(body), {snake(name) for name in names})
        self.assertNotIn("uploader_steam_id", body)
        self.assertEqual(body["kills"], 3)
        self.assertEqual(body["deaths"], 0)
        self.assertEqual(body["game_mode"], "Bounty Hunt")
        self.assertEqual(len(body["encountered_players"]), 3)
        self.assertTrue(body["match_time"].endswith("Z"))
        self.assertLessEqual(len(body["match_id"]), 128)
        self.assertEqual(body["match_id"], to_match_dto(match, ID, mtime + 1)["match_id"])

    def test_read_failure_retries_five_times(self):
        sleep = Mock()
        with patch("pathlib.Path.open", side_effect=PermissionError):
            with self.assertRaises(SyncError):
                read_snapshot(SAMPLE, sleep)
        self.assertEqual(sleep.call_count, 5)
        sleep.assert_called_with(0.5)

    def test_network_retry_backoff_and_do_not_follow_redirects(self):
        session, sleep = Mock(), Mock()
        session.request.side_effect = [requests.ConnectTimeout(), self.response(503), self.response(429), self.response()]
        api = ApiClient(self.config, session, sleep)
        api.request("POST", "/api/v1/matches/sync", "token", {"match_id": "same"})
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2, 4])
        self.assertEqual(session.request.call_count, 4)
        self.assertEqual(session.request.call_args.args[1], "http://localhost:8080/context/api/v1/matches/sync")
        self.assertFalse(session.request.call_args.kwargs["allow_redirects"])

    def test_401_not_retried_and_identity_mismatch_blocks_post(self):
        session = Mock()
        session.request.return_value = self.response(401)
        api = ApiClient(self.config, session, Mock())
        with self.assertRaises(SyncError):
            api.request("POST", "/api/v1/matches/sync", "token", {})
        self.assertEqual(session.request.call_count, 1)
        session.reset_mock()
        session.request.return_value = self.response(data={"code": 0, "data": {"authenticated": True, "steam_id": "other"}})
        with patch.dict(os.environ, {"HUNT_SYNC_TOKEN": "token"}):
            with self.assertRaises(SyncError):
                api.upload(ID, {})
        self.assertEqual(session.request.call_count, 1)
        self.assertEqual(session.request.call_args.args[0], "GET")

    def test_failure_not_marked_and_success_survives_restart(self):
        api = Mock()
        api.upload.side_effect = SyncError("offline")
        processor = SyncProcessor(SAMPLE, self.config, api)
        with patch("sync_client.get_uploader_steam_id", return_value=ID):
            with self.assertRaises(SyncError):
                processor.process()
            self.assertFalse(self.config["state_file"].exists())
            api.upload.side_effect = None
            processor.process()
            reloaded = SyncProcessor(SAMPLE, self.config, api)
            reloaded.process()
        self.assertEqual(api.upload.call_count, 2)

    def test_event_burst_debounces_and_atomic_replace_is_detected(self):
        done = threading.Event()
        process = Mock(side_effect=done.set)
        handler = DebouncedHandler(SAMPLE, process)
        worker = threading.Thread(target=handler.run)
        worker.start()
        try:
            handler.on_any_event(FileModifiedEvent(str(SAMPLE)))
            time.sleep(0.1)
            handler.on_any_event(FileMovedEvent("temporary.xml", str(SAMPLE)))
            self.assertFalse(done.wait(1.0))
            self.assertTrue(done.wait(2.0))
            self.assertEqual(process.call_count, 1)
        finally:
            handler.stop()
            worker.join(2)
