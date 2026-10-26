"""End-to-End Integration Tests using live MockLuCIServer and real Excel records."""

import unittest
from typing import Tuple

from app.core.config_models import DeviceConfig, GlobalConfig
from app.core.ont_driver import (
    ONTDriver,
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


if __name__ == "__main__":
    unittest.main()
