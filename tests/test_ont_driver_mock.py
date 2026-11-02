"""Unit tests for ONTDriver with LuCI mock responses (Phase 1 TDD)."""

import os
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import json
import requests

from app.core.config_models import DeviceConfig, GlobalConfig
from app.core.ont_driver import (
    ONTDriver,
    ONTDriverError,
    ONTNetworkError,
    ONTAccountLockoutWarning,
    ONTAccountLockoutError,
    ONTUserExistError,
    ONTAuthenticationError,
    ONTValidationError,
    UNSAFE_SHELL_METACHARS,
    validate_password_complexity,
    validate_slid,
    sanitize_input_metacharacters,
    ont_encode_query,
    WIFI_SECURITY_MODES,
    resolve_wifi_security_params,
    validate_wifi_ssid_and_pass,
    url_encode_form_value,
)

class TestONTDriverHelpers(unittest.TestCase):
    def test_ont_encode_query_replaces_ampersand(self):
        query = "user=admin&pass=Pass&123&other=val"
        encoded = ont_encode_query(query)
        self.assertEqual(encoded, "user=admin\x7fpass=Pass\x7f123\x7fother=val")

    def test_sanitize_input_metacharacters_rejects_unsafe(self):
        self.assertEqual(len(UNSAFE_SHELL_METACHARS), 11)
        self.assertIn("\\", UNSAFE_SHELL_METACHARS)
        unsafe_samples = [
            "pass&cmd",
            "pass;rm",
            "pass|cat",
            "pass$var",
            "pass>out",
            "pass<in",
            "pass`id`",
            "pass\\backslash",
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


    def test_sanitize_input_metacharacters_rejects_control_chars_including_del(self):
        for ctrl in ["pass\x7finject", "pass\x00null", "pass\nline", "pass\ttab"]:
            with self.assertRaises(ONTValidationError):
                sanitize_input_metacharacters(ctrl)

    def test_url_encode_form_value_encodes_plus_equals_percent_and_space(self):
        self.assertEqual(url_encode_form_value("VNPT + 5G=100%"), "VNPT%20%2B%205G%3D100%25")

    def test_wifi_security_modes_and_validation(self):
        self.assertEqual(len(WIFI_SECURITY_MODES), 6)
        for mode_key, expected_psk, expected_crypto in [
            ("wpa2_wpa3_psk", "enabled", "aes"),
            ("wpa2_psk", "enabled", "aes"),
            ("wpa_wpa2_psk", "enabled", "tkip+aes"),
            ("wpa3_psk", "enabled", "aes"),
            ("wpa_psk", "enabled", "tkip+aes"),
            ("open", "disabled", "aes"),
        ]:
            params = resolve_wifi_security_params(mode_key)
            self.assertEqual(params["wl_akm_psk"], expected_psk)
            self.assertEqual(params["wl_akm_psk2"], expected_psk)
            self.assertEqual(params["wl_crypto"], expected_crypto)

        with self.assertRaises(ONTValidationError):
            resolve_wifi_security_params("wep_invalid")

        # Open mode allows empty or short password, but still rejects whitespace-only SSID
        validate_wifi_ssid_and_pass("VNPT Guest", "", "open")
        with self.assertRaises(ONTValidationError):
            validate_wifi_ssid_and_pass("   ", "", "open")

        # PSK modes require 8..63 printable ASCII, not all spaces
        validate_wifi_ssid_and_pass("VNPT Home", "12345678", "wpa2_wpa3_psk")
        with self.assertRaises(ONTValidationError):
            validate_wifi_ssid_and_pass("VNPT Home", "1234567", "wpa2_wpa3_psk")
        with self.assertRaises(ONTValidationError):
            validate_wifi_ssid_and_pass("VNPT Home", "        ", "wpa2_psk")
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


    @patch("time.sleep", return_value=None)
    @patch("requests.Session.post")
    def test_configure_wifi_all_six_security_modes_and_conflict_mesh(self, mock_post, mock_sleep):
        self.driver.session_key = "tok123"
        captured_payloads = []

        def side_effect(url, **kwargs):
            if "wirelesscfg.json" in url:
                captured_payloads.append(kwargs.get("data", ""))
            r = MagicMock(status_code=200)
            r.text = '{"status": "OK"}'
            r.json.return_value = {"status": "OK"}
            return r

        mock_post.side_effect = side_effect

        for mode_key, expected_psk, expected_crypto in [
            ("wpa2_wpa3_psk", "enabled", "aes"),
            ("wpa2_psk", "enabled", "aes"),
            ("wpa_wpa2_psk", "enabled", "tkip+aes"),
            ("wpa3_psk", "enabled", "aes"),
            ("wpa_psk", "enabled", "tkip+aes"),
            ("open", "disabled", "aes"),
        ]:
            captured_payloads.clear()
            pw = "" if mode_key == "open" else "Pass+100%=OK"
            self.driver.configure_wifi(
                unit=1, ssid="VNPT + 5G", password=pw, pause_settling=False, sec_mode=mode_key
            )
            self.assertEqual(len(captured_payloads), 1)
            body = captured_payloads[0]
            self.assertIn(f"sec_mode={mode_key}", body)
            self.assertIn(f"wl_akm_psk={expected_psk}", body)
            self.assertIn(f"wl_akm_psk2={expected_psk}", body)
            self.assertIn(f"wl_crypto={expected_crypto}", body)
            self.assertIn("wl_ssid=VNPT%20%2B%205G", body)
            if mode_key != "open":
                self.assertIn("wl_wpa_psk=Pass%2B100%25%3DOK", body)

        # Test conflict_mesh rejection raises ONTDriverError
        def conflict_mesh_effect(url, **kwargs):
            r = MagicMock(status_code=200)
            r.text = '{"status": "conflict_mesh"}'
            r.json.return_value = {"status": "conflict_mesh"}
            return r

        mock_post.side_effect = conflict_mesh_effect
        with self.assertRaises(ONTDriverError) as ctx:
            self.driver.configure_wifi(unit=0, ssid="VNPT_Open", password="", pause_settling=False, sec_mode="open")
        self.assertIn("Mesh", str(ctx.exception))

    @patch.object(ONTDriver, "probe_gateway")
    def test_execute_full_setup_preflight_blocks_short_wifi_pass_before_step_1(self, mock_probe):
        device = DeviceConfig(
            slid="001560828",
            account_pppoe="kimthitv878",
            pass_pppoe="secretpass",
            ssid_24g="Kim Thu",
            pass_24g="1234567",  # 7 chars - invalid for PSK
            ssid_5g="Kim Thu 5G",
            pass_5g="12345678",
        )
        global_cfg = GlobalConfig(wifi_security_mode="wpa2_wpa3_psk")
        with self.assertRaises(ONTValidationError):
            self.driver.execute_full_setup(device, global_cfg)
        # Pre-flight validation must fail BEFORE probe_gateway (Step 1) is even called!
        mock_probe.assert_not_called()
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
            slid="001560828",
            account_pppoe="kimthitv878",
            pass_pppoe="secretpass",
            ssid_24g="Kim Thu",
            pass_24g="tholau224",
            ssid_5g="Kim Thu 5G",
            pass_5g="tholau224",
        )
        self.assertEqual(device.so_may, "001560828")
        global_cfg = GlobalConfig()

        with self.assertRaises(RuntimeError):
            self.driver.execute_full_setup(device, global_cfg)

        # Crucial invariant: logout MUST have been called in finally block
        mock_logout.assert_called_once()

    @patch("requests.Session.post")
    def test_update_firmware_success_clears_session_key_and_sanitizes_filename(self, mock_post):
        self.driver.session_key = "tok123"
        with tempfile.NamedTemporaryFile(prefix="fw;bad'name ", suffix=".signed", delete=False) as tf:
            tf.write(b"\x89PNG\r\n\x1a\n" + b"\xff\x00\x7f" * 1024)
            fw_path = tf.name
        self.addCleanup(lambda: os.path.exists(fw_path) and os.remove(fw_path))

        captured_files = []
        captured_headers = []

        def post_side_effect(url, **kwargs):
            resp = MagicMock()
            resp.status_code = 200
            if "updatefwsessioncheck.json" in url:
                resp.text = '{"status": "OK"}'
                resp.json.return_value = {"status": "OK"}
            elif "imagesize.json" in url:
                resp.text = '{"ramFree": "251696", "sessionKey": "tok456"}'
                resp.json.return_value = {"ramFree": "251696", "sessionKey": "tok456"}
            elif "uploadsoftware.conf" in url:
                captured_headers.append(kwargs.get("headers", {}))
                files_arg = kwargs.get("files", [])
                captured_files.append([(k, v[0], v[1] if k == "sessionKey" else type(v[1])) for k, v in files_arg])
                resp.text = '{"status": "valid", "sessionKey": "tok789"}'
                resp.json.return_value = {"status": "valid", "sessionKey": "tok789"}
            return resp

        mock_post.side_effect = post_side_effect
        res = self.driver.update_firmware(fw_path, upload_timeout=60.0)
        self.assertEqual(res.get("status"), "valid")
        # [Red Team RT-01]: session_key must be cleared immediately inside update_firmware()
        self.assertEqual(self.driver.session_key, "")
        # [Red Team RT-06]: Content-Type must be None so requests generates multipart boundary
        self.assertEqual(len(captured_headers), 1)
        self.assertIn("Content-Type", captured_headers[0])
        self.assertIsNone(captured_headers[0]["Content-Type"])
        # [Red Team RT-05]: Filename sanitized of unsafe chars
        file_tuple = captured_files[0][0]
        self.assertEqual(file_tuple[0], "file")
        self.assertNotIn(";", file_tuple[1])
        self.assertNotIn("'", file_tuple[1])
        self.assertNotIn(" ", file_tuple[1])
        session_tuple = captured_files[0][1]
        self.assertEqual(session_tuple, ("sessionKey", None, "tok456"))

    def test_update_firmware_validation_errors_on_missing_or_empty_file(self):
        with self.assertRaises(ONTValidationError):
            self.driver.update_firmware("/nonexistent/path/firmware.signed")

        with tempfile.NamedTemporaryFile(suffix=".signed", delete=False) as tf:
            empty_path = tf.name
        self.addCleanup(lambda: os.path.exists(empty_path) and os.remove(empty_path))

        with self.assertRaises(ONTValidationError):
            self.driver.update_firmware(empty_path)

    @patch("requests.Session.post")
    def test_update_firmware_error_conditions_and_session_key_rotation(self, mock_post):
        with tempfile.NamedTemporaryFile(suffix=".signed", delete=False) as tf:
            tf.write(b"A" * 4096)  # 4 KB
            fw_path = tf.name
        self.addCleanup(lambda: os.path.exists(fw_path) and os.remove(fw_path))

        # Case 1: updatefwsessioncheck status != OK
        self.driver.session_key = "tok1"
        r_bad_chk = MagicMock(status_code=200, text='{"status": "FAIL"}')
        r_bad_chk.json.return_value = {"status": "FAIL"}
        mock_post.return_value = r_bad_chk
        with self.assertRaises(ONTDriverError):
            self.driver.update_firmware(fw_path)

        # Case 2: imagesize ramFree == "0" -> must reject
        def side_effect_zero_ram(url, **kwargs):
            r = MagicMock(status_code=200)
            if "updatefwsessioncheck.json" in url:
                r.text = '{"status": "OK"}'
                r.json.return_value = {"status": "OK"}
            else:
                r.text = '{"ramFree": "0", "sessionKey": "tok2"}'
                r.json.return_value = {"ramFree": "0", "sessionKey": "tok2"}
            return r

        mock_post.side_effect = side_effect_zero_ram
        with self.assertRaises(ONTDriverError):
            self.driver.update_firmware(fw_path)
        self.assertEqual(self.driver.session_key, "tok2")

        # Case 3: uploadsoftware.conf returns status="invalid" with rotated sessionKey
        def side_effect_invalid_fw(url, **kwargs):
            r = MagicMock(status_code=200)
            if "updatefwsessioncheck.json" in url:
                r.text = '{"status": "OK"}'
                r.json.return_value = {"status": "OK"}
            elif "imagesize.json" in url:
                r.text = '{"ramFree": "251696", "sessionKey": "tok3"}'
                r.json.return_value = {"ramFree": "251696", "sessionKey": "tok3"}
            else:
                r.text = '{"status": "invalid", "sessionKey": "tok_rotated_4"}'
                r.json.return_value = {"status": "invalid", "sessionKey": "tok_rotated_4"}
            return r

        mock_post.side_effect = side_effect_invalid_fw
        with self.assertRaises(ONTDriverError):
            self.driver.update_firmware(fw_path)
        # Session key must be updated to rotated key so finally: logout() can release it
        self.assertEqual(self.driver.session_key, "tok_rotated_4")

        # Case 4: RequestException wrapped in ONTNetworkError
        mock_post.side_effect = requests.RequestException("Connection reset")
        with self.assertRaises(ONTNetworkError):
            self.driver.update_firmware(fw_path)

    @patch("time.sleep", return_value=None)
    @patch("requests.Session.post")
    @patch.object(ONTDriver, "probe_gateway", return_value=True)
    def test_execute_full_setup_with_firmware_update_11_steps_and_error_prefix(
        self, mock_probe, mock_post, mock_sleep
    ):
        with tempfile.NamedTemporaryFile(suffix=".signed", delete=False) as tf:
            tf.write(b"FW_BINARY_DATA" * 100)
            fw_path = tf.name
        self.addCleanup(lambda: os.path.exists(fw_path) and os.remove(fw_path))

        device = DeviceConfig(
            slid="001560828",
            account_pppoe="kimthitv878",
            pass_pppoe="secretpass",
            ssid_24g="Kim Thu",
            pass_24g="tholau224",
            ssid_5g="Kim Thu 5G",
            pass_5g="tholau224",
        )
        global_cfg = GlobalConfig(enable_firmware_update=True, firmware_path=fw_path)

        urls_called = []

        def post_ok(url, **kwargs):
            urls_called.append(url)
            r = MagicMock(status_code=200)
            if "login.cmd" in url:
                r.text = '{"status":"OK","firstlog":"0","sessionKey":"tok_init"}'
                r.json.return_value = {"status": "OK", "firstlog": "0", "sessionKey": "tok_init"}
            elif "wancfg.json" in url:
                r.text = '{"wanCfg":[]}'
                r.json.return_value = {"wanCfg": []}
            elif "updatefwsessioncheck.json" in url:
                r.text = '{"status":"OK"}'
                r.json.return_value = {"status": "OK"}
            elif "imagesize.json" in url:
                r.text = '{"ramFree":"251696","sessionKey":"tok_img"}'
                r.json.return_value = {"ramFree": "251696", "sessionKey": "tok_img"}
            elif "uploadsoftware.conf" in url:
                r.text = '{"status":"valid","sessionKey":"tok_up"}'
                r.json.return_value = {"status": "valid", "sessionKey": "tok_up"}
            else:
                r.text = '{"status":"OK"}'
                r.json.return_value = {"status": "OK"}
            return r

        mock_post.side_effect = post_ok
        steps = []
        res = self.driver.execute_full_setup(
            device, global_cfg, progress_cb=lambda name, num, tot, st, det: steps.append((num, tot, st))
        )
        self.assertTrue(res.get("firmware_updated"))
        self.assertEqual(res.get("firmware_file"), os.path.basename(fw_path))
        self.assertTrue(all(tot == 11 for _, tot, _ in steps))
        self.assertEqual(max(num for num, _, _ in steps), 11)
        # Must NOT call logout.cmd when firmware update succeeded
        self.assertFalse(any("logout.cmd" in u for u in urls_called))

        # Now test Step 10 failure prefixes message with Step 1-9 notice and calls logout.cmd
        urls_called.clear()
        with patch.object(ONTDriver, "update_firmware", side_effect=ONTDriverError("Sai chữ ký số")):
            with self.assertRaises(ONTDriverError) as ctx:
                self.driver.execute_full_setup(device, global_cfg)
            self.assertIn("Đã đổ xong cấu hình (Bước 1-9), nhưng lỗi Nâng cấp Firmware (Bước 10):", str(ctx.exception))
            self.assertTrue(any("logout.cmd" in u for u in urls_called))


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
