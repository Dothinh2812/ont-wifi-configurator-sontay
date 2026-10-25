"""Unit tests for ONTDriver with LuCI mock responses (Phase 1 TDD)."""

import unittest
from unittest.mock import MagicMock, patch
import json

from app.core.config_models import DeviceConfig, GlobalConfig
from app.core.ont_driver import (
    ONTDriver,
    ONTAccountLockoutWarning,
    ONTAccountLockoutError,
    ONTUserExistError,
    ONTAuthenticationError,
    ONTValidationError,
    validate_password_complexity,
    validate_slid,
    sanitize_input_metacharacters,
    ont_encode_query,
)


class TestONTDriverHelpers(unittest.TestCase):
    def test_ont_encode_query_replaces_ampersand(self):
        query = "user=admin&pass=Pass&123&other=val"
        encoded = ont_encode_query(query)
        self.assertEqual(encoded, "user=admin\x7fpass=Pass\x7f123\x7fother=val")

    def test_sanitize_input_metacharacters_rejects_unsafe(self):
        unsafe_samples = [
            "pass;rm",
            "pass|cat",
            "pass$var",
            "pass>out",
            "pass<in",
            "pass`id`",
            "pass!bang",
            "pass'quote",
            'pass"quote',
        ]
        for s in unsafe_samples:
            with self.assertRaises(ONTValidationError):
                sanitize_input_metacharacters(s)

    def test_sanitize_input_metacharacters_accepts_safe(self):
        safe_samples = ["Passwd2@", "kimthitv878", "001560828", "WiFi_2.4G-Home"]
        for s in safe_samples:
            self.assertEqual(sanitize_input_metacharacters(s), s)

    def test_validate_password_complexity(self):
        self.assertTrue(validate_password_complexity("Passwd2@"))
        self.assertTrue(validate_password_complexity("Admin#2026"))
        # Fails: < 8 chars
        self.assertFalse(validate_password_complexity("Pass1@"))
        # Fails: no uppercase
        self.assertFalse(validate_password_complexity("passwd2@"))
        # Fails: no lowercase
        self.assertFalse(validate_password_complexity("PASSWD2@"))
        # Fails: no digit
        self.assertFalse(validate_password_complexity("Passwddd@"))
        # Fails: no special char
        self.assertFalse(validate_password_complexity("Passwd22"))

    def test_validate_slid(self):
        # Length <= 10 valid ASCII
        self.assertEqual(validate_slid("001560828"), "001560828")
        self.assertEqual(validate_slid("MA-MEN"), "MA-MEN")
        self.assertEqual(validate_slid("1234567890"), "1234567890")
        # > 10 chars fails
        with self.assertRaises(ONTValidationError):
            validate_slid("12345678901")
        # Unsafe chars fail
        with self.assertRaises(ONTValidationError):
            validate_slid("MEN;01")


class TestONTDriverCore(unittest.TestCase):
    def setUp(self):
        self.driver = ONTDriver(base_url="https://192.168.1.1", timeout=5.0)

    @patch("requests.Session.post")
    def test_login_firstlog_1_assigns_factory_id(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = '{"status":"OK","firstlog":"1","sessionKey":"","numberLogin":"0","timeoutLogin":"0"}'
        mock_resp.json.return_value = {
            "status": "OK",
            "firstlog": "1",
            "sessionKey": "",
            "numberLogin": "0",
            "timeoutLogin": "0",
        }
        mock_post.return_value = mock_resp

        res = self.driver.login("admin", "admin")
        self.assertEqual(self.driver.session_key, "factoryID")
        self.assertTrue(res.get("firstlog") == "1")

    @patch("requests.Session.post")
    def test_login_firstlog_0_assigns_session_key(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        token = "47286a1b2def03a82dcf2b47e8960d55"
        mock_resp.text = f'{{"status":"OK","firstlog":"0","sessionKey":"{token}","numberLogin":"0","timeoutLogin":"0"}}'
        mock_resp.json.return_value = {
            "status": "OK",
            "firstlog": "0",
            "sessionKey": token,
            "numberLogin": "0",
            "timeoutLogin": "0",
        }
        mock_post.return_value = mock_resp

        self.driver.login("admin", "Passwd2@")
        self.assertEqual(self.driver.session_key, token)

    @patch("requests.Session.post")
    def test_login_lockout_warning_on_number_login(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = '{"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}'
        mock_resp.json.return_value = {
            "status": "FAIL",
            "numberLogin": "2",
            "timeoutLogin": "0",
        }
        mock_post.return_value = mock_resp

        with self.assertRaises(ONTAccountLockoutWarning):
            self.driver.login("admin", "wrongpass")

    @patch("requests.Session.post")
    def test_login_lockout_error_on_timeout_login(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = '{"status":"FAIL","numberLogin":"3","timeoutLogin":"180"}'
        mock_resp.json.return_value = {
            "status": "FAIL",
            "numberLogin": "3",
            "timeoutLogin": "180",
        }
        mock_post.return_value = mock_resp

        with self.assertRaises(ONTAccountLockoutError):
            self.driver.login("admin", "wrongpass")

    @patch("requests.Session.post")
    def test_login_user_exist_error(self, mock_post):
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = '{"status":"user exist"}'
        mock_resp.json.return_value = {"status": "user exist"}
        mock_post.return_value = mock_resp

        with self.assertRaises(ONTUserExistError):
            self.driver.login("admin", "admin")

    @patch.object(ONTDriver, "login")
    @patch("requests.Session.post")
    def test_login_and_change_password_success(self, mock_post, mock_login):
        # 1. login admin/admin success with firstlog=1
        mock_login.side_effect = [
            {"status": "OK", "firstlog": "1", "sessionKey": ""},
            {"status": "OK", "firstlog": "0", "sessionKey": "tok123"},
        ]
        self.driver.session_key = "factoryID"

        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = '{"status":"OK"}'
        mock_resp.json.return_value = {"status": "OK"}
        mock_post.return_value = mock_resp

        self.driver.login_and_change_password("admin", "Passwd2@")

        # Verify accesscontrolcfg call payload
        args, kwargs = mock_post.call_args
        self.assertIn("/cgi-bin/luci/accesscontrolcfg.json", args[0])
        data = kwargs.get("data", "")
        self.assertIn("sessionKey=factoryID", data)
        self.assertIn("newPass=Passwd2@", data)
        self.assertIn("user_cur_log=admin", data)

    @patch("requests.Session.post")
    def test_configure_wan_idempotent_creates_when_missing(self, mock_post):
        self.driver.session_key = "tok123"

        def post_side_effect(url, **kwargs):
            resp = MagicMock()
            resp.status_code = 200
            if "wancfg.json" in url:
                # Existing WAN only has vlan 4000
                resp.json.return_value = {
                    "wanCfg": [{"vlanId": "4000", "idx": 1, "ifcStr": "veip0.4000"}]
                }
            elif "addwan.json" in url:
                resp.json.return_value = {"status": "OK", "serviceId": "0"}
            elif "sendwan.cmd" in url:
                resp.json.return_value = {"status": "OK"}
            return resp

        mock_post.side_effect = post_side_effect

        self.driver.configure_wan_idempotent(
            vlan_id=11, ppp_user="account1", ppp_pass="pass1", mtu=1492
        )
        # Verify addwan and sendwan were called
        urls_called = [call[0][0] for call in mock_post.call_args_list]
        self.assertTrue(any("addwan.json" in u for u in urls_called))
        self.assertTrue(any("sendwan.cmd" in u for u in urls_called))

    @patch("requests.Session.post")
    def test_configure_wan_idempotent_edits_when_existing(self, mock_post):
        self.driver.session_key = "tok123"

        def post_side_effect(url, **kwargs):
            resp = MagicMock()
            resp.status_code = 200
            if "wancfg.json" in url:
                # Existing WAN already has vlan 11
                resp.json.return_value = {
                    "wanCfg": [
                        {"vlanId": "4000", "idx": 1, "ifcStr": "veip0.4000"},
                        {"vlanId": "11", "idx": 2, "ifcStr": "veip0.11", "serviceId": "1"},
                    ]
                }
            elif "editwan.json" in url:
                resp.json.return_value = {"status": "OK"}
            elif "sendwan.cmd" in url:
                resp.json.return_value = {"status": "OK"}
            return resp

        mock_post.side_effect = post_side_effect

        self.driver.configure_wan_idempotent(
            vlan_id=11, ppp_user="account1", ppp_pass="pass1", mtu=1492
        )
        urls_called = [call[0][0] for call in mock_post.call_args_list]
        self.assertTrue(any("editwan.json" in u for u in urls_called))
        self.assertTrue(any("sendwan.cmd" in u for u in urls_called))

    @patch("requests.Session.post")
    def test_set_gpon_password(self, mock_post):
        self.driver.session_key = "tok123"
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"status": "OK", "sysInfoGponPassWd": "001560828\r\n"}
        mock_post.return_value = mock_resp

        self.driver.set_gpon_password("001560828")
        args, kwargs = mock_post.call_args
        self.assertIn("/cgi-bin/luci/gponpasswd.json", args[0])
        self.assertIn("data=001560828", kwargs.get("data", ""))

    @patch("time.sleep", return_value=None)
    @patch("requests.Session.post")
    def test_configure_wifi_settling_pause_between_bands(self, mock_post, mock_sleep):
        self.driver.session_key = "tok123"
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"status": "OK"}
        mock_post.return_value = mock_resp

        # Configure 2.4G (unit 0)
        self.driver.configure_wifi(unit=0, ssid="WiFi-2.4G", password="pass24ghzpassword")
        # Sleep 2.0s must be called for unit=0
        mock_sleep.assert_called_with(2.0)

        mock_sleep.reset_mock()
        # Configure 5G (unit 1)
        self.driver.configure_wifi(unit=1, ssid="WiFi-5G", password="pass5ghzpassword")
        mock_sleep.assert_not_called()

    @patch("requests.Session.post")
    def test_logout_cleanup_called(self, mock_post):
        self.driver.session_key = "tok123"
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.text = '{"status":"OK"}'
        mock_post.return_value = mock_resp

        self.driver.logout()
        args, kwargs = mock_post.call_args
        self.assertIn("/cgi-bin/luci/logout.cmd", args[0])
        data = kwargs.get("data", "")
        self.assertIn("user=admin", data)
        self.assertIn("sessionKey=tok123", data)

    @patch.object(ONTDriver, "logout")
    @patch.object(ONTDriver, "probe_gateway")
    @patch.object(ONTDriver, "login_and_change_password")
    @patch.object(ONTDriver, "configure_wan_idempotent")
    def test_execute_full_setup_calls_logout_in_finally_on_error(
        self, mock_wan, mock_login, mock_probe, mock_logout
    ):
        mock_probe.return_value = True
        mock_login.return_value = "tok123"
        mock_wan.side_effect = RuntimeError("WAN setup failure")

        device = DeviceConfig(
            so_may="001560828",
            slid="001560828",
            account_pppoe="kimthitv878",
            pass_pppoe="secretpass",
            ssid_24g="Kim Thu",
            pass_24g="tholau224",
            ssid_5g="Kim Thu 5G",
            pass_5g="tholau224",
        )
        global_cfg = GlobalConfig()

        with self.assertRaises(RuntimeError):
            self.driver.execute_full_setup(device, global_cfg)

        # Crucial invariant: logout MUST have been called in finally block
        mock_logout.assert_called_once()


class TestNetworkProbe(unittest.TestCase):
    def setUp(self):
        from app.core.network_probe import NetworkProbe
        self.probe = NetworkProbe(base_url="https://192.168.1.1", required_consecutive_success=3)

    @patch("requests.Session.get")
    def test_debounce_requires_three_consecutive_successes(self, mock_get):
        mock_resp_ok = MagicMock(status_code=200)
        mock_resp_fail = MagicMock(status_code=500)

        # 1st success -> still offline
        mock_get.return_value = mock_resp_ok
        self.assertFalse(self.probe.probe_once())
        self.assertFalse(self.probe.is_online)

        # 2nd success -> still offline
        self.assertFalse(self.probe.probe_once())
        self.assertFalse(self.probe.is_online)

        # 3rd success -> NOW ONLINE!
        self.assertTrue(self.probe.probe_once())
        self.assertTrue(self.probe.is_online)

        # Failure resets to offline immediately
        mock_get.return_value = mock_resp_fail
        self.assertFalse(self.probe.probe_once())
        self.assertFalse(self.probe.is_online)

    @patch("requests.Session.get")
    def test_pause_and_resume(self, mock_get):
        mock_resp_ok = MagicMock(status_code=200)
        mock_get.return_value = mock_resp_ok
        # Make online
        self.probe.probe_once()
        self.probe.probe_once()
        self.probe.probe_once()
        self.assertTrue(self.probe.is_online)

        # Pause probe
        self.probe.pause()
        self.assertTrue(self.probe.is_paused)
        mock_get.reset_mock()
        res = self.probe.probe_once()
        # In paused state, get is not called and prior state is returned
        mock_get.assert_not_called()
        self.assertTrue(res)

        # Resume probe
        self.probe.resume()
        self.assertFalse(self.probe.is_paused)
if __name__ == "__main__":
    unittest.main()
