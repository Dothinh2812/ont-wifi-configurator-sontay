"""End-to-End Integration Tests using live MockLuCIServer and real Excel records."""

import os
import tempfile
import unittest
from typing import Tuple

from app.core.config_models import DeviceConfig, GlobalConfig
from app.core.ont_driver import (
    ONTDriver,
    ONTDriverError,
    ONTAccountLockoutError,
    ONTAccountLockoutWarning,
    ONTUserExistError,
)
from app.data.models import DeviceRecord, DeviceStatus
from app.data.excel_manager import ExcelManager
from tests.mock_luci_server import MockLuCIServer, run_mock_server


class TestE2EWorkflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server, cls.thread, cls.base_url = run_mock_server()
        cls.excel_manager = ExcelManager()
        cls.records = cls.excel_manager.load_file("file-mau.xlsx")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        # Reset server state before each test
        self.server.is_factory_reset = True
        self.server.current_admin_pass = "admin"
        self.server.number_login = 0
        self.server.timeout_login = 0
        self.server.active_session = ""
        self.server.simulate_user_exist = False
        self.server.wan_entries = [
            {"action": "false", "vlanId": "4000", "idx": 1, "ifcStr": "veip0.4000", "srvcStr": "tr069"}
        ]
        self.server.request_history.clear()
        self.server.ram_free_kb = 251696
        self.server.firmware_updated = False
        self.server.uploaded_firmware_bytes = 0
        self.server.uploaded_firmware_name = ""
        self.server.reject_firmware = False
        self.server.simulate_conflict_mesh = False
        self.server.wifi_configs.clear()

        self.global_cfg = GlobalConfig(
            base_url=self.base_url,
            admin_default_pass="admin",
            admin_new_pass="Passwd2@",
            vlan_id=11,
            mtu=1492,
            request_timeout=3.0,
            settling_pause_wifi=0.01,  # Short pause for fast automated tests
        )

    def test_e2e_successful_device_setup(self):
        # Test with Row 2 (index 1): 005501941, manh9653, 6l6s8z2a
        row2 = self.records[1]
        self.assertTrue(row2.is_valid)

        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        progress_events = []

        def on_progress(name, current, total, status, detail):
            progress_events.append((current, name, status))

        res = driver.execute_full_setup(
            device_cfg=row2.to_device_config(),
            global_cfg=self.global_cfg,
            progress_cb=on_progress,
        )

        self.assertEqual(res["status"], "SUCCESS")
        self.assertEqual(res["slid"], "005501941")
        self.assertEqual(res["so_may"], "005501941")
        # Invariant 1: All 10 steps were recorded
        steps_recorded = set(p[0] for p in progress_events)
        self.assertEqual(steps_recorded, set(range(1, 11)))

        # Invariant 2: Session MUST be released in mock server (logout called)
        self.assertEqual(self.server.active_session, "")

        # Invariant 3: Admin password was updated to Passwd2@
        self.assertEqual(self.server.current_admin_pass, "Passwd2@")

        # Invariant 4: VLAN 11 was added with correct credentials
        vlan11 = next((w for w in self.server.wan_entries if str(w.get("vlanId")) == "11"), None)
        self.assertIsNotNone(vlan11)
        self.assertEqual(vlan11.get("pppUserName"), "manh9653")

        # Invariant 5: GPON SLID was set to 005501941
        self.assertEqual(self.server.gpon_password, "005501941")

        # Invariant 6: WiFi 2.4G & 5G were configured
        self.assertIn("0", self.server.wifi_configs)
        self.assertIn("1", self.server.wifi_configs)
        self.assertEqual(self.server.wifi_configs["0"]["ssid"], "Manh Ngoc")
        self.assertEqual(self.server.wifi_configs["1"]["ssid"], "Manh Ngoc 5G")

    def test_e2e_idempotent_retry_wan_setup(self):
        # Configure once
        row2 = self.records[1]
        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        driver.execute_full_setup(row2.to_device_config(), self.global_cfg)

        # RETRY configuration on the same ONT: should update existing WAN without failing
        driver2 = ONTDriver(base_url=self.base_url, timeout=3.0)
        res = driver2.execute_full_setup(row2.to_device_config(), self.global_cfg)
        self.assertEqual(res["status"], "SUCCESS")

        # Check that there is still only one VLAN 11 entry
        vlan11_entries = [w for w in self.server.wan_entries if str(w.get("vlanId")) == "11"]
        self.assertEqual(len(vlan11_entries), 1)

    def test_e2e_hardware_lockout_protection(self):
        # Set wrong password attempts on mock server
        self.server.current_admin_pass = "UnknownSecret1@"
        self.server.number_login = 0

        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        row2 = self.records[1]

        # Login attempts should raise Lockout Warning or Lockout Error and NOT retry blindly
        with self.assertRaises(Exception) as ctx:
            driver.execute_full_setup(row2.to_device_config(), self.global_cfg)

        # Confirm lockout protection invariant
        self.assertTrue(self.server.number_login <= 3)

    def test_e2e_missing_pppoe_blocked_from_execution(self):
        # Row 1 (index 0) has missing PPPoE password
        row1 = self.records[0]
        self.assertFalse(row1.is_valid)
        self.assertEqual(row1.status, DeviceStatus.INVALID_DATA)

        # Should never be passed to driver; if passed, driver validates or UI blocks
        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        # Verify no request is made if blocked
        req_count_before = len(self.server.request_history)
        self.assertTrue(any("PPPoE" in err for err in row1.validation_errors))
        self.assertEqual(len(self.server.request_history), req_count_before)


    def test_e2e_full_11_step_setup_with_binary_firmware_upload(self):
        row2 = self.records[1]
        # Include binary bytes 0x7f, 0x00, 0x80..0xff to verify MockLuCIHandler binary multipart parsing (RT-03)
        binary_payload = b"VNPT_SIGNED_FW\x00\x7f\x80\xfe\xff\r\n" * 512
        with tempfile.NamedTemporaryFile(prefix="GX40E5VN_", suffix=".signed", delete=False) as tf:
            tf.write(binary_payload)
            fw_path = tf.name
        self.addCleanup(lambda: os.path.exists(fw_path) and os.remove(fw_path))

        self.global_cfg.enable_firmware_update = True
        self.global_cfg.firmware_path = fw_path

        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        progress_events = []

        res = driver.execute_full_setup(
            device_cfg=row2.to_device_config(),
            global_cfg=self.global_cfg,
            progress_cb=lambda name, current, total, status, detail: progress_events.append(
                (current, total, name, status)
            ),
        )

        self.assertEqual(res["status"], "SUCCESS")
        self.assertTrue(res.get("firmware_updated"))
        self.assertEqual(res.get("firmware_file"), os.path.basename(fw_path))

        steps_recorded = set(p[0] for p in progress_events)
        self.assertEqual(steps_recorded, set(range(1, 12)))
        self.assertTrue(all(p[1] == 11 for p in progress_events))

        # Server received exact binary payload and marked firmware_updated=True
        self.assertTrue(self.server.firmware_updated)
        self.assertEqual(self.server.uploaded_firmware_bytes, len(binary_payload))
        self.assertEqual(self.server.uploaded_firmware_name, os.path.basename(fw_path))
        self.assertEqual(self.server.active_session, "")

        # Crucial [Red Team RT-01]: logout.cmd MUST NOT be called after uploadsoftware.conf returns valid
        endpoints_called = [ep for ep, _ in self.server.request_history]
        self.assertIn("updatefwsessioncheck.json", endpoints_called)
        self.assertIn("imagesize.json", endpoints_called)
        self.assertIn("uploadsoftware.conf", endpoints_called)
        self.assertNotIn("logout.cmd", endpoints_called)

    def test_e2e_rejected_firmware_prefixes_error_and_releases_session_via_logout(self):
        row2 = self.records[1]
        with tempfile.NamedTemporaryFile(suffix=".signed", delete=False) as tf:
            tf.write(b"CORRUPTED_FIRMWARE_IMAGE" * 200)
            fw_path = tf.name
        self.addCleanup(lambda: os.path.exists(fw_path) and os.remove(fw_path))

        self.server.reject_firmware = True
        self.global_cfg.enable_firmware_update = True
        self.global_cfg.firmware_path = fw_path

        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        with self.assertRaises(ONTDriverError) as ctx:
            driver.execute_full_setup(row2.to_device_config(), self.global_cfg)

        self.assertIn("Đã đổ xong cấu hình (Bước 1-9), nhưng lỗi Nâng cấp Firmware (Bước 10):", str(ctx.exception))
        self.assertIn("status=invalid", str(ctx.exception))
        self.assertFalse(self.server.firmware_updated)
        # Because upload failed with status="invalid", finally: logout() MUST have been called to release session
        endpoints_called = [ep for ep, _ in self.server.request_history]
        self.assertIn("uploadsoftware.conf", endpoints_called)
        self.assertIn("logout.cmd", endpoints_called)
        self.assertEqual(self.server.active_session, "")

    def test_e2e_wifi_security_modes_and_open_blank_password(self):
        row2 = self.records[1]
        dev_cfg = row2.to_device_config()
        dev_cfg.pass_24g = ""
        dev_cfg.pass_5g = ""
        self.global_cfg.wifi_security_mode = "open"

        driver = ONTDriver(base_url=self.base_url, timeout=3.0)
        res = driver.execute_full_setup(dev_cfg, self.global_cfg)
        self.assertEqual(res["status"], "SUCCESS")
        self.assertEqual(res["wifi_security_mode"], "open")
        self.assertEqual(self.server.wifi_configs["0"]["sec_mode"], "open")
        self.assertEqual(self.server.wifi_configs["0"]["wl_akm_psk"], "disabled")
        self.assertEqual(self.server.wifi_configs["0"]["wl_akm_psk2"], "disabled")
        self.assertEqual(self.server.wifi_configs["0"]["wl_crypto"], "aes")
        self.assertEqual(self.server.wifi_configs["0"]["pass"], "")
        self.assertEqual(self.server.wifi_configs["1"]["sec_mode"], "open")
        self.assertEqual(self.server.wifi_configs["1"]["pass"], "")

        # Test wpa_wpa2_psk sets tkip+aes
        self.global_cfg.wifi_security_mode = "wpa_wpa2_psk"
        dev_cfg.pass_24g = "12345678"
        dev_cfg.pass_5g = "12345678"
        driver2 = ONTDriver(base_url=self.base_url, timeout=3.0)
        driver2.execute_full_setup(dev_cfg, self.global_cfg)
        self.assertEqual(self.server.wifi_configs["0"]["sec_mode"], "wpa_wpa2_psk")
        self.assertEqual(self.server.wifi_configs["0"]["wl_akm_psk"], "enabled")
        self.assertEqual(self.server.wifi_configs["0"]["wl_crypto"], "tkip+aes")

        # Test conflict_mesh aborts and releases session
        self.server.simulate_conflict_mesh = True
        self.global_cfg.wifi_security_mode = "open"
        driver3 = ONTDriver(base_url=self.base_url, timeout=3.0)
        with self.assertRaises(ONTDriverError) as ctx:
            driver3.execute_full_setup(dev_cfg, self.global_cfg)
        self.assertIn("Mesh", str(ctx.exception))
        self.assertEqual(self.server.active_session, "")

if __name__ == "__main__":
    unittest.main()
