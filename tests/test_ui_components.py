"""Unit tests for UI components: Audio alert, DeviceTableModel, Worker, and credential masking (Phase 3 TDD)."""

import os
import unittest
import tempfile
from unittest.mock import MagicMock, patch

# Ensure Qt platform offscreen for headless testing
os.environ["QT_QPA_PLATFORM"] = "offscreen"

from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import Qt, QModelIndex

# Initialize single QApplication instance for tests
app = QApplication.instance()
if app is None:
    app = QApplication([])

from app.data.models import DeviceRecord, DeviceStatus
from app.data.excel_manager import ExcelManager
from app.ui.audio_alert import play_alert_sound
from app.ui.device_table_model import DeviceTableModel, get_status_brush
from app.ui.worker import ONTExecutionWorker, ProbeWorker, mask_sensitive_credentials
from app.core.network_probe import NetworkProbe
from app.core.config_models import GlobalConfig, get_app_path, get_app_base_dir


class TestAudioAlert(unittest.TestCase):
    def test_audio_alert_runs_without_crashing_on_linux(self):
        # Should not raise ModuleNotFoundError for winsound
        try:
            play_alert_sound()
        except Exception as e:
            self.fail(f"play_alert_sound raised unexpected exception: {e}")


class TestAppPathResolution(unittest.TestCase):
    def test_get_app_path_source_mode(self):
        base = get_app_base_dir()
        self.assertTrue(os.path.isdir(base))
        self.assertTrue(os.path.exists(os.path.join(base, "app")))
        sample = get_app_path("file-mau.xlsx")
        self.assertTrue(os.path.exists(sample))

    def test_get_app_path_frozen_mode(self):
        import sys
        orig_frozen = getattr(sys, "frozen", None)
        orig_executable = sys.executable
        try:
            sys.frozen = True
            sys.executable = "/fake/dist/ONT_040NS_Tool.exe"
            base = get_app_base_dir()
            self.assertEqual(base, "/fake/dist")
            self.assertEqual(get_app_path("file-mau.xlsx"), "/fake/dist/file-mau.xlsx")
            self.assertEqual(get_app_path(".checkpoint_results.json"), "/fake/dist/.checkpoint_results.json")
        finally:
            if orig_frozen is None:
                if hasattr(sys, "frozen"):
                    del sys.frozen
            else:
                sys.frozen = orig_frozen
            sys.executable = orig_executable

class TestCredentialMasking(unittest.TestCase):
    def test_mask_passwords_in_log(self):
        log1 = "Cài đặt WAN pppUserName=kimthitv878&pppPassword=SecretPass123"
        masked1 = mask_sensitive_credentials(log1)
        self.assertNotIn("SecretPass123", masked1)
        self.assertIn("pppPassword=***", masked1)

        log2 = "wl_wpa_psk=tholau224&wl_ssid=Kim Thu"
        masked2 = mask_sensitive_credentials(log2)
        self.assertNotIn("tholau224", masked2)
        self.assertIn("wl_wpa_psk=***", masked2)

        log3 = "newPass=Passwd2@&oldPass=admin&data=001560828"
        masked3 = mask_sensitive_credentials(log3)
        self.assertNotIn("Passwd2@", masked3)
        self.assertNotIn("001560828", masked3)
        self.assertIn("newPass=***", masked3)
        self.assertIn("data=***", masked3)

        log4 = 'Response JSON: {"status": "valid", "sessionKey": "47286a1b2def03a82dcf2b47e8960d55"}'
        masked4 = mask_sensitive_credentials(log4)
        self.assertNotIn("47286a1b2def03a82dcf2b47e8960d55", masked4)
        self.assertIn('"sessionKey": "***"', masked4)

        # \x7f delimiter masking (RT-04)
        log5 = "wl_wpa_psk=MySecretPass\x7fwl_ssid=Home\x7fsessionKey=tok999\x7fother=1"
        masked5 = mask_sensitive_credentials(log5)
        self.assertNotIn("MySecretPass", masked5)
        self.assertNotIn("tok999", masked5)
        self.assertIn("wl_wpa_psk=***\x7f", masked5)
        self.assertIn("sessionKey=***\x7f", masked5)
class TestDeviceTableModel(unittest.TestCase):
    def setUp(self):
        self.manager = ExcelManager()
        self.records = self.manager.load_file("file-mau.xlsx")[:5]
        self.model = DeviceTableModel(self.records)

    def test_model_row_and_column_count(self):
        self.assertEqual(self.model.rowCount(), 5)
        self.assertEqual(self.model.columnCount(), 10)

    def test_row1_missing_pppoe_displays_invalid_data(self):
        # Row 0 is missing PPPoE password (col 8 is Trạng thái)
        idx_status = self.model.index(0, 8)
        status_text = self.model.data(idx_status, Qt.ItemDataRole.DisplayRole)
        self.assertEqual(status_text, DeviceStatus.INVALID_DATA.value)

    def test_inline_editing_pppoe_password_reactively_validates(self):
        # Row 0 initially INVALID_DATA
        row0 = self.model.get_record(0)
        self.assertFalse(row0.is_valid)
        self.assertEqual(row0.status, DeviceStatus.INVALID_DATA)

        # Inline edit column 3 (Mật khẩu PPPoE)
        col_pppoe_pass = 3
        idx = self.model.index(0, col_pppoe_pass)
        success = self.model.setData(idx, "MyNewPass123", Qt.ItemDataRole.EditRole)
        self.assertTrue(success)

        # After supplying password, row should become valid and status PENDING
        updated_row0 = self.model.get_record(0)
        self.assertEqual(updated_row0.pass_pppoe, "MyNewPass123")
        self.assertTrue(updated_row0.is_valid)
        self.assertEqual(updated_row0.status, DeviceStatus.PENDING)

    def test_status_update_updates_record_and_emits(self):
        self.model.update_record_status(1, DeviceStatus.SUCCESS, message="10/10 steps OK")
        rec = self.model.get_record(1)
        self.assertEqual(rec.status, DeviceStatus.SUCCESS)
        self.assertEqual(rec.error_message, "10/10 steps OK")

    def test_get_status_brush_returns_cached_singleton_instances(self):
        bg1, fg1 = get_status_brush(DeviceStatus.SUCCESS)
        bg2, fg2 = get_status_brush(DeviceStatus.SUCCESS)
        self.assertIsNotNone(bg1)
        self.assertIsNotNone(fg1)
        # Must be identical cached objects (identity check `is`), not newly allocated
        self.assertIs(bg1, bg2)
        self.assertIs(fg1, fg2)

    def test_get_status_brush_handles_all_statuses(self):
        # All styled statuses must return valid QBrush tuples
        for st in (DeviceStatus.INVALID_DATA, DeviceStatus.RUNNING, DeviceStatus.SUCCESS, DeviceStatus.FAILED, DeviceStatus.SKIPPED):
            bg, fg = get_status_brush(st)
            self.assertIsNotNone(bg, f"Background brush missing for {st}")
            self.assertIsNotNone(fg, f"Foreground brush missing for {st}")
        # PENDING should return (None, None)
        bg_pending, fg_pending = get_status_brush(DeviceStatus.PENDING)
        self.assertIsNone(bg_pending)
        self.assertIsNone(fg_pending)

    def test_model_data_returns_cached_brush_references(self):
        # Row 0 has status INVALID_DATA
        idx = self.model.index(0, 0)
        bg_brush = self.model.data(idx, Qt.ItemDataRole.BackgroundRole)
        fg_brush = self.model.data(idx, Qt.ItemDataRole.ForegroundRole)
        expected_bg, expected_fg = get_status_brush(DeviceStatus.INVALID_DATA)
        self.assertIs(bg_brush, expected_bg)
        self.assertIs(fg_brush, expected_fg)


class TestONTExecutionWorker(unittest.TestCase):
    @patch("app.core.ont_driver.ONTDriver.execute_full_setup")
    def test_worker_signals_and_probe_coordination(self, mock_setup):
        mock_setup.return_value = {"status": "SUCCESS"}

        rec = DeviceRecord(
            row_index=2,
            so_may="005501941",
            slid="005501941",
            account_pppoe="manh9653",
            pass_pppoe="6l6s8z2a",
        )
        global_cfg = GlobalConfig()

        worker = ONTExecutionWorker(rec.to_device_config(), global_cfg)

        pause_called = []
        resume_called = []
        finished_called = []

        worker.probe_pause_requested.connect(lambda: pause_called.append(True))
        worker.probe_resume_requested.connect(lambda: resume_called.append(True))
        worker.finished.connect(lambda ok, msg, res: finished_called.append((ok, msg, res)))

        # Run synchronously for unit test
        worker.run()

        # Invariants:
        # 1. Probe MUST be paused before execution
        self.assertTrue(len(pause_called) > 0)
        # 2. Probe MUST be resumed in finally block after execution
        self.assertTrue(len(resume_called) > 0)
        # 3. Finished signal emitted
        self.assertTrue(len(finished_called) > 0)
        self.assertTrue(finished_called[0][0])  # success == True


    @patch("app.core.ont_driver.ONTDriver.execute_full_setup")
    def test_worker_cancel_during_step_10_success_does_not_abort(self, mock_setup):
        rec = DeviceRecord(
            row_index=2,
            so_may="005501941",
            slid="005501941",
            account_pppoe="manh9653",
            pass_pppoe="6l6s8z2a",
        )
        global_cfg = GlobalConfig(enable_firmware_update=True, firmware_path="/tmp/fw.signed")
        worker = ONTExecutionWorker(rec.to_device_config(), global_cfg)

        def fake_setup(device_cfg, global_cfg, progress_cb=None):
            progress_cb("Nâng cấp Firmware", 10, 11, "RUNNING", "Đang tải...")
            # Simulate user clicking cancel while file upload is in-flight
            worker.cancel()
            # Step 10 completes with SUCCESS -> must NOT raise RuntimeError on SUCCESS status
            progress_cb("Nâng cấp Firmware", 10, 11, "SUCCESS", "Đã tải xong")
            progress_cb("Hoàn tất", 11, 11, "SUCCESS", "Khởi động lại")
            return {"status": "SUCCESS", "firmware_updated": True, "firmware_file": "fw.signed"}

        mock_setup.side_effect = fake_setup
        finished_events = []
        worker.finished.connect(lambda ok, msg, res: finished_events.append((ok, msg, res)))
        worker.run()

        self.assertEqual(len(finished_events), 1)
        ok, msg, res = finished_events[0]
        self.assertTrue(ok)
        self.assertIn("11/11", msg)
        self.assertIn("fw.signed", msg)
        self.assertTrue(res.get("firmware_updated"))

class TestProbeWorker(unittest.TestCase):
    def setUp(self):
        self.probe = NetworkProbe(base_url="https://192.168.1.1", required_consecutive_success=2)

    def test_probe_worker_emits_status_changed(self):
        worker = ProbeWorker(self.probe, interval_ms=10)
        emitted = []
        worker.status_changed.connect(lambda online, count: emitted.append((online, count)))
        
        with patch.object(self.probe, "probe_once", return_value=True):
            worker.run_iteration()
            self.assertTrue(len(emitted) > 0)
            self.assertEqual(emitted[0][0], True)

    def test_probe_worker_pause_barrier(self):
        import time
        import threading
        worker = ProbeWorker(self.probe, interval_ms=50)
        
        probe_in_progress = threading.Event()
        probe_can_finish = threading.Event()
        
        def slow_probe():
            probe_in_progress.set()
            probe_can_finish.wait(timeout=1.0)
            return True
            
        with patch.object(self.probe, "probe_once", side_effect=slow_probe):
            worker.start()
            self.assertTrue(probe_in_progress.wait(timeout=2.0))
            # When we call pause(), it should block until slow_probe finishes
            paused_done = threading.Event()
            def do_pause():
                worker.pause(timeout=2.0)
                paused_done.set()
                
            t = threading.Thread(target=do_pause)
            t.start()
            # pause shouldn't be done yet because slow_probe is still waiting
            time.sleep(0.05)
            self.assertFalse(paused_done.is_set())
            
            # Release slow probe
            probe_can_finish.set()
            self.assertTrue(paused_done.wait(timeout=2.0))
            self.assertTrue(worker.is_paused)
            worker.stop()
            worker.wait(2000)

    def test_probe_worker_stop_and_resume(self):
        worker = ProbeWorker(self.probe, interval_ms=50)
        self.assertFalse(worker.is_paused)
        worker.pause()
        self.assertTrue(worker.is_paused)
        worker.resume()
        self.assertFalse(worker.is_paused)
        worker.stop()
        self.assertTrue(worker._is_stopped)


class TestNetworkProbeFingerprint(unittest.TestCase):
    def setUp(self):
        self.probe = NetworkProbe(base_url="https://192.168.1.1", required_consecutive_success=1)

    @patch("requests.Session.get")
    def test_probe_accepts_igate_signature(self, mock_get):
        mock_get.return_value = MagicMock(status_code=200, text="<html><title>iGate GW040-NSv2</title></html>")
        self.assertTrue(self.probe.probe_once())

    @patch("requests.Session.get")
    def test_probe_accepts_vnpt_signature(self, mock_get):
        mock_get.return_value = MagicMock(status_code=200, text="<html>VNPT Technology Corporation</html>")
        self.assertTrue(self.probe.probe_once())

    @patch("requests.Session.get")
    def test_probe_rejects_tplink_or_other_gateway(self, mock_get):
        mock_get.return_value = MagicMock(status_code=200, text="<html><title>TP-Link Wireless Router</title></html>")
        self.assertFalse(self.probe.probe_once())

    @patch("requests.Session.get")
    def test_probe_accepts_real_gw040_nsv2_login_html_lowercase_vnpt(self, mock_get):
        real_html = (
            '<!DOCTYPE html><html lang="en"><head><title>Login</title>'
            '<link rel="icon" href="icon-vnpt.png" type="image/x-icon">'
            '<script src="login.js"></script></head><body>'
            '<img class="logo" src="logo-vnpt.png">'
            '<p>AX3000 Wireless Dual-Band GPON ONT</p></body></html>'
        )
        mock_get.return_value = MagicMock(status_code=200, text=real_html)
        self.assertTrue(self.probe.probe_once())
        self.assertEqual(self.probe._session.headers.get("Connection"), "close")

    @patch("requests.Session.get")
    def test_probe_reset_recreates_session_and_tracks_offline_transition(self, mock_get):
        probe = NetworkProbe(base_url="https://192.168.1.1", required_consecutive_success=2)
        mock_get.return_value = MagicMock(status_code=200, text='<script src="login.js"></script>')
        self.assertFalse(probe.probe_once())
        self.assertTrue(probe.probe_once())
        self.assertTrue(probe.is_online)

        old_session = probe._session
        probe.reset()
        self.assertFalse(probe.is_online)
        self.assertFalse(probe.has_seen_offline)
        self.assertIsNot(probe._session, old_session)

        # Cable unplugged -> probe fails -> has_seen_offline becomes True
        mock_get.side_effect = OSError("Network unreachable")
        self.assertFalse(probe.probe_once())
        self.assertTrue(probe.has_seen_offline)

        # Next ONT plugged in -> reaches online after debounce
        mock_get.side_effect = None
        mock_get.return_value = MagicMock(status_code=200, text='<script src="login.js"></script>')
        self.assertFalse(probe.probe_once())
        self.assertTrue(probe.probe_once())
        self.assertTrue(probe.is_online)
        self.assertTrue(probe.has_seen_offline)


    @patch("requests.Session.get")
    def test_probe_reset_clears_current_mac_and_detects_same_device(self, mock_get):
        probe = NetworkProbe(base_url="https://192.168.1.1", required_consecutive_success=1)
        mock_get.return_value = MagicMock(status_code=200, text='<script src="login.js"></script>')

        with patch.object(probe, "_get_gateway_mac", return_value="aa:bb:cc:dd:ee:01"):
            self.assertTrue(probe.probe_once())
            self.assertEqual(probe._current_mac, "aa:bb:cc:dd:ee:01")
            self.assertFalse(probe.is_same_device_as_last_reset)

        probe.reset()
        self.assertEqual(probe._last_reset_mac, "aa:bb:cc:dd:ee:01")
        self.assertIsNone(probe._current_mac)
        self.assertFalse(probe.is_same_device_as_last_reset)

        # Same ONT #1 comes back online with identical MAC -> is_same_device_as_last_reset is True
        with patch.object(probe, "_get_gateway_mac", return_value="aa:bb:cc:dd:ee:01"):
            self.assertTrue(probe.probe_once())
            self.assertTrue(probe.is_same_device_as_last_reset)

        # Different ONT #2 plugged in with new MAC -> is_same_device_as_last_reset is False
        with patch.object(probe, "_get_gateway_mac", return_value="aa:bb:cc:dd:ee:02"):
            self.assertTrue(probe.probe_once())
            self.assertFalse(probe.is_same_device_as_last_reset)

class TestWorkerInThreadCancel(unittest.TestCase):
    def test_cancel_does_not_call_logout_on_caller_thread(self):
        rec = DeviceRecord(
            row_index=2,
            so_may="005501941",
            slid="005501941",
            account_pppoe="manh9653",
            pass_pppoe="6l6s8z2a",
        )
        global_cfg = GlobalConfig()
        worker = ONTExecutionWorker(rec.to_device_config(), global_cfg)
        mock_driver = MagicMock()
        worker.driver = mock_driver
        
        worker.cancel()
        self.assertTrue(worker._is_cancelled)
        # Must NOT call driver.logout on caller thread!
        mock_driver.logout.assert_not_called()

class TestMainWindowHeadless(unittest.TestCase):
    def setUp(self):
        cp = get_app_path(".checkpoint_results.json")
        if os.path.exists(cp):
            try:
                os.remove(cp)
            except OSError:
                pass
        from app.ui.main_window import MainWindow
        self.win = MainWindow(initial_excel_path="file-mau.xlsx")
    def tearDown(self):
        if hasattr(self.win, "probe_timer") and self.win.probe_timer:
            self.win.probe_timer.stop()
        self.win.close()
        cp = get_app_path(".checkpoint_results.json")
        if os.path.exists(cp):
            try:
                os.remove(cp)
            except OSError:
                pass
    def test_main_window_loads_sample_file_and_updates_stats(self):
        self.assertEqual(len(self.win.records), 89)
        self.assertIn("89", self.win.lbl_stat_total.text())
        self.assertIn("13", self.win.lbl_stat_invalid.text())

    def test_admin_password_complexity_reactivity(self):
        self.win.txt_admin_pass.setText("weak")
        self.assertIn("Yêu cầu", self.win.lbl_pass_complexity.text())
        self.assertFalse(self.win.btn_run.isEnabled())

        self.win.txt_admin_pass.setText("Passwd2@")
        self.assertIn("Đạt chuẩn", self.win.lbl_pass_complexity.text())

    def test_lan_ip_is_read_only_by_default(self):
        self.assertTrue(self.win.txt_lan_ip.isReadOnly())
        self.assertTrue(self.win.txt_lan_mask.isReadOnly())
        self.assertTrue(self.win.txt_dhcp_range.isReadOnly())

    def test_skip_advances_selection(self):
        initial_row = self.win.selected_row
        self.win.skip_current_device()
        rec_skipped = self.win.table_model.get_record(initial_row)
        self.assertEqual(rec_skipped.status, DeviceStatus.SKIPPED)


    def test_footer_signature_credits_displayed(self):
        from PyQt6.QtCore import Qt
        self.assertTrue(hasattr(self.win, "lbl_footer_signature"))
        self.assertTrue(hasattr(self.win, "lbl_footer_left"))
        sig_text = self.win.lbl_footer_signature.text()
        self.assertIn("Trung tâm viễn thông Sơn Tây", sig_text)
        self.assertIn("Đỗ Xuân Thịnh", sig_text)
        self.assertIn("0946827186", sig_text)
        self.assertIn("VNPT iGate GW040-NSv2 Configurator", self.win.lbl_footer_left.text())
        self.assertEqual(self.win.lbl_footer_signature.cursor().shape(), Qt.CursorShape.PointingHandCursor)
        self.assertTrue(len(self.win.lbl_footer_signature.toolTip()) > 0)

    def test_footer_signature_click_copies_phone_to_clipboard(self):
        from PyQt6.QtWidgets import QApplication
        from PyQt6.QtGui import QMouseEvent
        from PyQt6.QtCore import QEvent, QPointF, Qt
        clipboard = QApplication.clipboard()
        if clipboard:
            clipboard.clear()
        # Trigger mouse click on footer label
        self.assertTrue(hasattr(self.win, "_on_signature_clicked"))
        event = QMouseEvent(QEvent.Type.MouseButtonPress, QPointF(5, 5), Qt.MouseButton.LeftButton, Qt.MouseButton.LeftButton, Qt.KeyboardModifier.NoModifier)
        self.win._on_signature_clicked(event)
        if clipboard:
            self.assertEqual(clipboard.text(), "0946827186")

        # Right click should not trigger copy
        if clipboard:
            clipboard.clear()
        right_event = QMouseEvent(QEvent.Type.MouseButtonPress, QPointF(5, 5), Qt.MouseButton.RightButton, Qt.MouseButton.RightButton, Qt.KeyboardModifier.NoModifier)
        self.win._on_signature_clicked(right_event)
        if clipboard:
            self.assertEqual(clipboard.text(), "")

    @patch("PyQt6.QtWidgets.QMessageBox.information")
    @patch("app.ui.main_window.play_alert_sound")
    def test_auto_detect_and_auto_run_next_ont_after_completion(self, mock_beep, mock_msgbox):
        # Initial selected row is row 1 (first valid PENDING record: 005501941)
        first_row = self.win.selected_row
        self.assertEqual(first_row, 1)

        # Simulate ONT #1 finishing successfully
        self.win.probe._is_online = True
        self.win.probe._consecutive_success = 3
        self.win.probe_worker.pause()

        with patch.object(self.win, "run_current_device") as mock_run_next:
            self.win._on_worker_finished(True, "Cấu hình thành công 10/10 bước!", {"status": "SUCCESS"})

            # Selection must advance to next pending row (row 2)
            self.assertEqual(self.win.selected_row, 2)
            self.assertTrue(self.win._awaiting_next_ont)
            # Probe must be reset so stale ONT #1 state is cleared
            self.assertFalse(self.win.probe.is_online)
            self.assertFalse(self.win.probe.has_seen_offline)
            mock_run_next.assert_not_called()

            # If ONT #1 is still plugged in (has_seen_offline is False), auto-run must NOT fire prematurely
            self.win.probe._is_online = True
            self.win.probe._consecutive_success = 3
            self.win._on_probe_status_changed(True, 3)
            mock_run_next.assert_not_called()
            # However, manual RUN button is enabled when online
            self.assertTrue(self.win.btn_run.isEnabled())

            # Technician unplugs ONT #1 -> probe goes offline
            self.win.probe._is_online = False
            self.win.probe._consecutive_success = 0
            self.win.probe._has_seen_offline = True
            self.win._on_probe_status_changed(False, 0)
            self.assertFalse(self.win.btn_run.isEnabled())

            # Technician plugs in ONT #2 -> probe detects online after 3 consecutive checks
            self.win.probe._is_online = True
            self.win.probe._consecutive_success = 3
            self.win._on_probe_status_changed(True, 3)

            # Must automatically trigger run_current_device() for ONT #2 without pressing Retry!
            mock_run_next.assert_called_once()
            self.assertFalse(self.win._awaiting_next_ont)

    def test_inline_editing_slid_and_wifi_reactively_validates(self):
        # Row 1 is valid initially
        row1 = self.win.table_model.get_record(1)
        self.assertTrue(row1.is_valid)
        self.assertEqual(row1.status, DeviceStatus.PENDING)

        # Edit SLID (col 1) to > 10 chars -> should become INVALID_DATA
        idx_slid = self.win.table_model.index(1, 1)
        self.win.table_model.setData(idx_slid, "0123456789ABC", Qt.ItemDataRole.EditRole)
        self.assertFalse(row1.is_valid)
        self.assertEqual(row1.status, DeviceStatus.INVALID_DATA)
        self.assertEqual(row1.so_may, "0123456789ABC")

        # Fix SLID back to valid -> should restore PENDING and sync so_may
        self.win.table_model.setData(idx_slid, "005501941", Qt.ItemDataRole.EditRole)
        self.assertTrue(row1.is_valid)
        self.assertEqual(row1.status, DeviceStatus.PENDING)
        self.assertEqual(row1.so_may, "005501941")

        # Edit SSID 2.4G (col 4) with unsafe shell metachar -> should become INVALID_DATA
        idx_ssid = self.win.table_model.index(1, 4)
        self.win.table_model.setData(idx_ssid, "Bad;SSID", Qt.ItemDataRole.EditRole)
        self.assertFalse(row1.is_valid)
        self.assertEqual(row1.status, DeviceStatus.INVALID_DATA)
    @patch("PyQt6.QtWidgets.QMessageBox.warning")
    @patch("app.ui.main_window.ONTExecutionWorker")
    def test_lan_settings_applied_and_validated_on_run(self, mock_worker_cls, mock_warn):
        mock_worker = MagicMock()
        mock_worker_cls.return_value = mock_worker

        # Invalid LAN IP should show warning and abort before creating worker
        self.win.txt_lan_ip.setText("999.999.1.1")
        self.win.run_current_device()
        mock_warn.assert_called_once()
        mock_worker_cls.assert_not_called()

        # Valid custom LAN IP / mask / DHCP range should sync to global_cfg
        mock_warn.reset_mock()
        self.win.txt_lan_ip.setText("192.168.10.1")
        self.win.txt_lan_mask.setText("255.255.255.0")
        self.win.txt_dhcp_range.setText("192.168.10.10 - 192.168.10.200")
        self.win.run_current_device()
        mock_warn.assert_not_called()
        self.assertEqual(self.win.global_cfg.lan_ip, "192.168.10.1")
        self.assertEqual(self.win.global_cfg.lan_mask, "255.255.255.0")
        self.assertEqual(self.win.global_cfg.dhcp_start, "192.168.10.10")
        self.assertEqual(self.win.global_cfg.dhcp_end, "192.168.10.200")

    def test_shortcut_ignored_when_input_or_table_editor_focused(self):
        from PyQt6.QtGui import QKeyEvent
        from PyQt6.QtCore import QEvent
        from PyQt6.QtWidgets import QAbstractItemView

        self.win.probe._is_online = True
        self.win._update_run_button_state()
        self.assertTrue(self.win.btn_run.isEnabled())

        space_event = QKeyEvent(QEvent.Type.KeyPress, Qt.Key.Key_Space, Qt.KeyboardModifier.NoModifier, " ")
        with patch.object(self.win, "run_current_device") as mock_run:
            # When focusWidget is a QLineEdit, Space must NOT trigger run_current_device
            with patch.object(self.win, "focusWidget", return_value=self.win.txt_wifi5_ssid):
                self.win.keyPressEvent(space_event)
                mock_run.assert_not_called()

            # When table_view is in EditingState, Space must NOT trigger run_current_device
            with patch.object(self.win, "focusWidget", return_value=self.win.table_view), \
                 patch.object(self.win.table_view, "state", return_value=QAbstractItemView.State.EditingState):
                self.win.keyPressEvent(space_event)
                mock_run.assert_not_called()

            # When table_view is in NoState and focused, Space triggers run_current_device
            with patch.object(self.win, "focusWidget", return_value=self.win.table_view), \
                 patch.object(self.win.table_view, "state", return_value=QAbstractItemView.State.NoState):
                self.win.keyPressEvent(space_event)
                mock_run.assert_called_once()

    @patch("PyQt6.QtWidgets.QMessageBox.warning")
    @patch("app.ui.main_window.ONTExecutionWorker")
    def test_firmware_update_ui_controls_and_run_gating(self, mock_worker_cls, mock_warn):
        mock_worker = MagicMock()
        mock_worker.isRunning.return_value = False
        mock_worker_cls.return_value = mock_worker

        # Default state: checkbox is unchecked
        self.assertFalse(self.win.chk_enable_fw_update.isChecked())
        self.win.probe._is_online = True
        self.win._update_run_button_state()
        self.assertTrue(self.win.btn_run.isEnabled())

        # Enable firmware update with invalid path -> RUN disabled & warning on run_current_device
        self.win.txt_fw_path.setText("/nonexistent/fw_invalid.signed")
        self.win.chk_enable_fw_update.setChecked(True)
        self.assertFalse(self.win.btn_run.isEnabled())
        self.win.run_current_device()
        mock_warn.assert_called_once()
        mock_worker_cls.assert_not_called()

        # Set valid firmware file -> RUN enabled & snapshot GlobalConfig passed to worker
        mock_warn.reset_mock()
        with tempfile.NamedTemporaryFile(suffix=".signed", delete=False) as tf:
            tf.write(b"FIRMWARE_IMAGE" * 1024)
            valid_fw = tf.name
        self.addCleanup(lambda: os.path.exists(valid_fw) and os.remove(valid_fw))

        self.win.txt_fw_path.setText(valid_fw)
        self.assertTrue(self.win.btn_run.isEnabled())
        self.win.run_current_device()
        mock_warn.assert_not_called()
        mock_worker_cls.assert_called_once()
        passed_cfg = mock_worker_cls.call_args[0][1]
        self.assertIsNot(passed_cfg, self.win.global_cfg)
        self.assertTrue(passed_cfg.enable_firmware_update)
        self.assertEqual(passed_cfg.firmware_path, valid_fw)
        self.assertEqual(self.win.progress_bar.maximum(), 11)

    @patch("PyQt6.QtWidgets.QMessageBox.information")
    @patch("app.ui.main_window.play_alert_sound")
    def test_firmware_reboot_hot_swap_protection_blocks_14s_window_and_same_mac(
        self, mock_beep, mock_msgbox
    ):
        with tempfile.NamedTemporaryFile(suffix=".signed", delete=False) as tf:
            tf.write(b"FW_DATA" * 512)
            valid_fw = tf.name
        self.addCleanup(lambda: os.path.exists(valid_fw) and os.remove(valid_fw))

        self.win.txt_fw_path.setText(valid_fw)
        self.win.chk_enable_fw_update.setChecked(True)

        # Pause background probe thread before setting mock state so in-flight probe doesn't overwrite MAC
        self.win.probe_worker.pause()
        self.win.probe._is_online = True
        self.win.probe._consecutive_success = 3
        self.win.probe._current_mac = "11:22:33:44:55:01"

        with patch.object(self.win, "run_current_device") as mock_run_next:
            self.win._on_worker_finished(
                True,
                "Cấu hình & Nạp Firmware thành công 11/11 bước!",
                {"status": "SUCCESS", "firmware_updated": True, "firmware_file": os.path.basename(valid_fw)},
            )
            # Pause again after _on_worker_finished resumed it so deterministic state transitions are tested cleanly
            self.win.probe_worker.pause()
            self.assertTrue(self.win._awaiting_fw_reboot_disconnect)
            self.assertEqual(self.win.probe._last_reset_mac, "11:22:33:44:55:01")
            self.assertIsNone(self.win.probe._current_mac)

            # 1. During 14s window where uHTTPd of ONT #1 still responds (has_seen_offline=False)
            self.win.probe._is_online = True
            self.win.probe._consecutive_success = 3
            self.win.probe._has_seen_offline = False
            self.win._on_probe_status_changed(True, 3)
            self.assertFalse(self.win.btn_run.isEnabled())
            mock_run_next.assert_not_called()

            # 2. ONT #1 goes offline while rebooting, then comes back online 2 min later with SAME MAC
            self.win.probe._has_seen_offline = True
            self.win.probe._current_mac = "11:22:33:44:55:01"
            self.assertTrue(self.win.probe.is_same_device_as_last_reset)
            self.win._on_probe_status_changed(True, 3)
            self.assertTrue(self.win._awaiting_fw_reboot_disconnect)
            self.assertFalse(self.win.btn_run.isEnabled())
            mock_run_next.assert_not_called()

            # 3. Technician plugs in ONT #2 with DIFFERENT MAC -> unlocks and auto-runs!
            self.win.probe._current_mac = "11:22:33:44:55:02"
            self.assertFalse(self.win.probe.is_same_device_as_last_reset)
            self.win._on_probe_status_changed(True, 3)
            self.assertFalse(self.win._awaiting_fw_reboot_disconnect)
            mock_run_next.assert_called_once()

    @patch("PyQt6.QtWidgets.QMessageBox.warning")
    @patch("app.ui.main_window.ONTExecutionWorker")
    def test_wifi_security_mode_combobox_and_preflight_gating(self, mock_worker_cls, mock_warn):
        mock_worker = MagicMock()
        mock_worker.isRunning.return_value = False
        mock_worker_cls.return_value = mock_worker

        # Default mode is wpa2_wpa3_psk with 6 options
        self.assertEqual(self.win.cmb_wifi_sec_mode.count(), 6)
        self.assertEqual(self.win.cmb_wifi_sec_mode.currentData(), "wpa2_wpa3_psk")

        # Select row with 7-char Wi-Fi password -> blocked in PSK mode, allowed in Open mode
        rec = self.win.table_model.get_record(self.win.selected_row)
        rec.pass_24g = "1234567"
        self.win.probe._is_online = True
        self.win._update_run_button_state()

        self.win.run_current_device()
        mock_warn.assert_called_once()
        mock_worker_cls.assert_not_called()

        # Switch combobox to Open mode -> allowed!
        mock_warn.reset_mock()
        open_idx = self.win.cmb_wifi_sec_mode.findData("open")
        self.assertGreaterEqual(open_idx, 0)
        self.win.cmb_wifi_sec_mode.setCurrentIndex(open_idx)
        self.assertEqual(self.win.global_cfg.wifi_security_mode, "open")

        self.win.run_current_device()
        mock_warn.assert_not_called()
        mock_worker_cls.assert_called_once()
        passed_cfg = mock_worker_cls.call_args[0][1]
        self.assertEqual(passed_cfg.wifi_security_mode, "open")
if __name__ == "__main__":
    unittest.main()
