from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import autostart
from tray_client import TrayApp, make_icon
from sync_client import ApiClient, SyncError


class AutostartTest(unittest.TestCase):
    def test_command_uses_pythonw_absolute_paths_and_quotes_spaces(self):
        with patch.object(autostart.sys, "executable", "C:/Python With Space/python.exe"), \
                patch.object(autostart.Path, "is_file", return_value=True):
            command = autostart.startup_command(Path("my config.json"))
        self.assertIn('"C:\\Python With Space\\pythonw.exe"', command)
        self.assertIn("tray_client.py", command)
        self.assertIn("--config", command)
        self.assertNotIn("HUNT_SYNC_TOKEN", command)

    def test_toggle_only_changes_application_run_value(self):
        registry = Mock()
        key = Mock()
        registry.CreateKeyEx.return_value.__enter__ = Mock(return_value=key)
        registry.CreateKeyEx.return_value.__exit__ = Mock(return_value=False)
        with patch.object(autostart, "winreg", registry), patch.object(autostart, "startup_command", return_value="command"):
            autostart.set_enabled(True, Path("config.json"))
            registry.SetValueEx.assert_called_once_with(key, autostart.VALUE_NAME, 0, registry.REG_SZ, "command")
            autostart.set_enabled(False, Path("config.json"))
            registry.DeleteValue.assert_called_once_with(key, autostart.VALUE_NAME)


class TrayTest(unittest.TestCase):
    def app(self):
        with patch("tray_client.pystray.Icon"):
            return TrayApp(Path("config.json"))

    def test_icon_is_64px_and_has_two_states(self):
        green, orange = make_icon(True), make_icon(False)
        self.assertEqual(green.size, (64, 64))
        self.assertNotEqual(green.tobytes(), orange.tobytes())

    def test_manual_upload_queues_without_doing_network_on_menu_thread(self):
        app = self.app()
        app.handler = Mock()
        app.processor = Mock()
        app.manual_upload()
        app.handler.trigger.assert_called_once()
        app.processor.process.assert_not_called()
        self.assertTrue(app.manual_pending.is_set())

    def test_success_skip_and_failure_status(self):
        app = self.app()
        app.processor = Mock()
        app.processor.process.return_value = True
        app._process()
        timestamp = app.last_upload
        self.assertNotEqual(timestamp, "尚无成功上传")
        self.assertEqual(app.backend, "上次上传连接正常")
        app.processor.process.return_value = False
        app._process()
        self.assertEqual(app.last_upload, timestamp)
        app.processor.process.side_effect = SyncError("failure")
        app._process()
        self.assertFalse(app.healthy)
        self.assertEqual(app.last_upload, timestamp)

    def test_exit_stops_worker_signal_handler_and_icon(self):
        app = self.app()
        app.handler = Mock()
        app.quit()
        self.assertTrue(app.stop_event.is_set())
        app.handler.stop.assert_called_once()
        app.icon.stop.assert_called_once()

    def test_stop_event_prevents_new_network_requests(self):
        stopped = threading.Event()
        stopped.set()
        session = Mock()
        api = ApiClient({"backend_base_url": "http://localhost:8080"}, session=session, stop_event=stopped)
        with self.assertRaises(SyncError):
            api.request("GET", "/api/v1/auth/session", "token")
        session.request.assert_not_called()

    def test_background_shutdown_releases_observer_and_http_session(self):
        app = self.app()
        observer, handler, api = Mock(), Mock(), Mock()
        handler.run.side_effect = app.stop_event.set
        with patch("tray_client.autostart.is_enabled", return_value=False), \
                patch("tray_client.load_config", return_value={"upload_on_start": False}), \
                patch("tray_client.locate_attributes_file", return_value=Path("attributes.xml")), \
                patch("tray_client.ApiClient", return_value=api), \
                patch("tray_client.SyncProcessor"), \
                patch("tray_client.DebouncedHandler", return_value=handler), \
                patch("tray_client.Observer", return_value=observer):
            app._background()
        observer.stop.assert_called_once()
        observer.join.assert_called_once()
        api.session.close.assert_called_once()
