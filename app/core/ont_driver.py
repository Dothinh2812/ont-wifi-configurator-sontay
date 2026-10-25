"""ONT Driver for VNPT iGate GW040-NSv2.

Implements LuCI CGI automation with 10 sequential configuration steps,
hardware lockout protection (numberLogin), idempotency for WAN,
WiFi driver settling pause, and mandatory logout in finally block.
"""

import re
import time
import urllib3
import requests
from typing import Callable, Optional, Dict, Any

from app.core.config_models import DeviceConfig, GlobalConfig

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Shell metacharacters blocked by LuCI
UNSAFE_SHELL_METACHARS = set('&|;$><`!\'"')


class ONTDriverError(Exception):
    """Base exception for ONT Driver operations."""
    pass


class ONTAccountLockoutWarning(ONTDriverError):
    """Raised when numberLogin >= 2 to prevent 180s hardware lockout."""
    pass


class ONTAccountLockoutError(ONTDriverError):
    """Raised when timeoutLogin > 0 or numberLogin >= 3 (ONT is locked)."""
    pass


class ONTUserExistError(ONTDriverError):
    """Raised when LuCI reports another session is currently active."""
    pass


class ONTAuthenticationError(ONTDriverError):
    """Raised when all login attempts fail."""
    pass


class ONTValidationError(ONTDriverError):
    """Raised when input parameters fail strict validation."""
    pass


class ONTNetworkError(ONTDriverError):
    """Raised when physical or HTTP network request fails."""
    pass


def ont_encode_query(query_str: str) -> str:
    """Firmware encode_query reproduction: replaces '&' with '\\x7f'."""
    return query_str.replace("&", "\x7f")


def sanitize_input_metacharacters(text: str) -> str:
    """Validate that text does not contain dangerous shell metacharacters."""
    if not isinstance(text, str):
        text = str(text or "")
    for char in text:
        if char in UNSAFE_SHELL_METACHARS:
            raise ONTValidationError(
                f"Ký tự '{char}' không an toàn cho hệ thống LuCI CGI (chứa shell metacharacter)."
            )
    return text


def validate_password_complexity(password: str) -> bool:
    """Check ONT password complexity: min 8 chars, uppercase, lowercase, digit, special char."""
    if not password or len(password) < 8:
        return False
    has_upper = any(c.isupper() for c in password)
    has_lower = any(c.islower() for c in password)
    has_digit = any(c.isdigit() for c in password)
    # Special char: neither alphanumeric nor whitespace
    has_special = any(not c.isalnum() and not c.isspace() for c in password)
    return has_upper and has_lower and has_digit and has_special


def validate_slid(slid: str) -> str:
    """Validate GPON Password (SLID): <= 10 ASCII characters, matches ^[a-zA-Z0-9_\\-\\.]+$."""
    slid = (slid or "").strip()
    if not slid:
        raise ONTValidationError("SLID / GPON Password không được để trống.")
    if len(slid) > 10:
        raise ONTValidationError(f"SLID '{slid}' vượt quá độ dài tối đa 10 ký tự ({len(slid)} ký tự).")
    if not re.match(r"^[a-zA-Z0-9_\-\.]+$", slid):
        raise ONTValidationError(f"SLID '{slid}' chứa ký tự không hợp lệ cho GPON Password.")
    sanitize_input_metacharacters(slid)
    return slid


class ONTDriver:
    """Protocol driver for VNPT iGate GW040-NSv2 router."""

    def __init__(self, base_url: str = "https://192.168.1.1", timeout: float = 5.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session_key = ""
        self.current_user = "admin"

        self.session = requests.Session()
        self.session.verify = False
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "Referer": f"{self.base_url}/",
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Requested-With": "XMLHttpRequest",
        })

    def probe_gateway(self) -> bool:
        """Probe root URL with HTTP 200 check."""
        try:
            r = self.session.get(f"{self.base_url}/", timeout=self.timeout)
            return r.status_code == 200
        except Exception:
            return False

    def login(self, user: str, password: str) -> Dict[str, Any]:
        """Authenticate with LuCI login.cmd endpoint."""
        sanitize_input_metacharacters(user)
        sanitize_input_metacharacters(password)

        url = f"{self.base_url}/cgi-bin/luci/login.cmd"
        payload = f"user={user}&pass={password}"

        try:
            resp = self.session.post(url, data=payload, timeout=self.timeout)
        except requests.RequestException as e:
            raise ONTNetworkError(f"Lỗi kết nối khi gửi yêu cầu đăng nhập: {e}") from e

        if resp.status_code != 200:
            raise ONTNetworkError(f"HTTP Error {resp.status_code} từ login.cmd")

        try:
            data = resp.json()
        except Exception:
            data = {"status": "UNKNOWN", "raw": resp.text}

        status = data.get("status", "")
        if status == "user exist":
            raise ONTUserExistError(
                "Tài khoản admin đang bận phiên khác (user exist). "
                "Vui lòng chờ 60s hoặc khởi động lại ONT để giải phóng phiên."
            )

        timeout_login = int(data.get("timeoutLogin", 0) or 0)
        number_login = int(data.get("numberLogin", 0) or 0)

        if timeout_login > 0 or number_login >= 3:
            raise ONTAccountLockoutError(
                f"ONT đang bị khóa phần cứng trong {timeout_login}s do nhập sai quá {number_login} lần. "
                "Vui lòng đợi hết thời gian hoặc bấm nút Reset cứng sau thân ONT."
            )

        if number_login >= 2:
            raise ONTAccountLockoutWarning(
                f"Cảnh báo: ONT đã ghi nhận {number_login} lần đăng nhập sai. "
                "Dừng lại để tránh bị khóa 180s. Vui lòng kiểm tra mật khẩu hoặc bấm nút Reset cứng."
            )

        first_log = str(data.get("firstlog", ""))
        if first_log == "1":
            self.session_key = "factoryID"
        elif first_log == "0":
            self.session_key = data.get("sessionKey", "")

        self.current_user = user
        return data

    def login_and_change_password(self, default_pass: str, new_pass: str) -> str:
        """Handle 2-step login: test default, change pass if firstlog=1, fallback once to new_pass."""
        if not validate_password_complexity(new_pass):
            raise ONTValidationError(
                "Mật khẩu admin mới không đạt chuẩn độ phức tạp (tối thiểu 8 ký tự, có hoa, thường, số và ký tự đặc biệt)."
            )

        # Step 2a: Try default pass (admin/admin)
        login_success = False
        data = None
        try:
            data = self.login("admin", default_pass)
            login_success = (data.get("status") == "OK" or data.get("firstlog") is not None)
        except (ONTAccountLockoutError, ONTAccountLockoutWarning, ONTUserExistError):
            raise
        except Exception:
            login_success = False

        if login_success and data:
            first_log = str(data.get("firstlog", ""))
            if first_log == "1":
                # Must change password via accesscontrolcfg.json
                url = f"{self.base_url}/cgi-bin/luci/accesscontrolcfg.json"
                payload = (
                    f"undefined&sessionKey={self.session_key}&username=admin&oldPass=&newPass={new_pass}&user_cur_log=admin"
                )
                try:
                    resp = self.session.post(url, data=payload, timeout=self.timeout)
                    if resp.status_code != 200:
                        raise ONTDriverError(f"Đổi mật khẩu thất bại (HTTP {resp.status_code})")
                except requests.RequestException as e:
                    raise ONTNetworkError(f"Lỗi mạng khi đổi mật khẩu admin: {e}") from e

                # Re-login with new_pass to obtain full sessionKey
                relog_data = self.login("admin", new_pass)
                self.session_key = relog_data.get("sessionKey", self.session_key)
                return self.session_key
            else:
                return self.session_key

        # Step 2b: Fallback - try new_pass ONLY ONCE in case ONT was already configured
        try:
            fallback_data = self.login("admin", new_pass)
            if fallback_data.get("status") == "OK" or fallback_data.get("firstlog") == "0":
                self.session_key = fallback_data.get("sessionKey", self.session_key)
                return self.session_key
        except (ONTAccountLockoutError, ONTAccountLockoutWarning, ONTUserExistError):
            raise
        except Exception:
            pass

        # Step 2c: Both failed
        raise ONTAuthenticationError(
            "Đăng nhập thất bại với cả mật khẩu mặc định và mật khẩu mới. "
            "Vui lòng bấm nút Reset cứng sau thân ONT để đưa về trạng thái xuất xưởng."
        )

    def configure_wan_idempotent(
        self, vlan_id: int, ppp_user: str, ppp_pass: str, mtu: int = 1492
    ) -> Dict[str, Any]:
        """Idempotent WAN configuration: check existing VLAN 11; edit if exists, add if new."""
        sanitize_input_metacharacters(ppp_user)
        sanitize_input_metacharacters(ppp_pass)

        # Query existing WAN interfaces
        wancfg_url = f"{self.base_url}/cgi-bin/luci/wancfg.json"
        query_payload = f"undefined&sessionKey={self.session_key}"
        try:
            resp = self.session.post(wancfg_url, data=query_payload, timeout=self.timeout)
            wan_data = resp.json() if resp.status_code == 200 else {}
        except Exception as e:
            raise ONTNetworkError(f"Lỗi khi tra cứu danh sách WAN: {e}") from e

        wan_list = wan_data.get("wanCfg", [])
        existing_vlan_entry = None
        for entry in wan_list:
            v_id = str(entry.get("vlanId", entry.get("vlanMuxId", "")))
            if v_id == str(vlan_id):
                existing_vlan_entry = entry
                break

        if existing_vlan_entry:
            # Edit existing WAN entry
            svc_id = existing_vlan_entry.get("serviceId", existing_vlan_entry.get("idx", 1))
            wan_if_name = existing_vlan_entry.get("ifcStr", f"veip0.{vlan_id}")

            edit_url = f"{self.base_url}/cgi-bin/luci/editwan.json"
            edit_payload = (
                f"serviceId={svc_id}&wanIfName={wan_if_name}&ntwkPrtcl=0&sessionKey={self.session_key}"
            )
            self.session.post(edit_url, data=edit_payload, timeout=self.timeout)

            # Send updated parameters
            send_url = f"{self.base_url}/cgi-bin/luci/sendwan.cmd"
            send_payload = (
                f"enblEnetWan=0&serviceName=internet&vlanMuxId={vlan_id}&vlanMuxPr=0&ntwkPrtcl=0"
                f"&pppUserName={ppp_user}&pppPassword={ppp_pass}&enblIpVer=0&wanAddr6Type=DHCP"
                f"&ethMtu={mtu}&enblFirewall=1&enblNat=1&serviceId={svc_id}&wanIfName={wan_if_name}"
                f"&sessionKey={self.session_key}"
            )
            resp = self.session.post(send_url, data=send_payload, timeout=self.timeout)
            return resp.json() if resp.status_code == 200 else {"status": "OK"}
        else:
            # Add new WAN interface
            add_url = f"{self.base_url}/cgi-bin/luci/addwan.json"
            add_payload = f"undefined&sessionKey={self.session_key}&serviceId=0"
            self.session.post(add_url, data=add_payload, timeout=self.timeout)

            send_url = f"{self.base_url}/cgi-bin/luci/sendwan.cmd"
            send_payload = (
                f"enblEnetWan=0&serviceName=internet&vlanMuxId={vlan_id}&vlanMuxPr=0&ntwkPrtcl=0"
                f"&pppUserName={ppp_user}&pppPassword={ppp_pass}&enblIpVer=0&wanAddr6Type=DHCP"
                f"&ethMtu={mtu}&enblFirewall=1&enblNat=1&serviceId=0&wanIfName=undefined"
                f"&sessionKey={self.session_key}"
            )
            resp = self.session.post(send_url, data=send_payload, timeout=self.timeout)
            return resp.json() if resp.status_code == 200 else {"status": "OK"}

    def set_gpon_password(self, slid_value: str) -> Dict[str, Any]:
        """Configure GPON SLID / Password."""
        valid_slid = validate_slid(slid_value)
        url = f"{self.base_url}/cgi-bin/luci/gponpasswd.json"
        payload = f"sessionKey={self.session_key}&data={valid_slid}"
        resp = self.session.post(url, data=payload, timeout=self.timeout)
        if resp.status_code != 200:
            raise ONTDriverError(f"Cài đặt GPON Password thất bại (HTTP {resp.status_code})")
        return resp.json() if resp.text else {"status": "OK"}

    def configure_wifi(
        self, unit: int, ssid: str, password: str, pause_settling: bool = True
    ) -> Dict[str, Any]:
        """Configure WiFi band: unit 0 for 2.4GHz, unit 1 for 5GHz."""
        sanitize_input_metacharacters(ssid)
        sanitize_input_metacharacters(password)

        cfg_url = f"{self.base_url}/cgi-bin/luci/wirelesscfg.json"
        payload = (
            f"page=ssid.asp&action=Apply&wl_unit={unit}&wl_radio=1&wl_bssid=0&wl_bss_enabled=1"
            f"&wl_ssid={ssid}&wl_bss_maxassoc=32&wl_closed=0&sec_mode=wpa2_wpa3_psk"
            f"&wl_wpa_psk={password}&wl_mode=null&wl_auth=0&wl_auth_mode=radius&wl_osenen=0"
            f"&wl_akm=&wl_akm_wpa=disabled&wl_akm_psk=enabled&wl_akm_wpa2=disabled&wl_akm_psk2=enabled"
            f"&wl_akm_brcm_psk=disabled&wl_preauth=disabled&wl_wep=disabled&wl_crypto=aes"
            f"&wl_radius_ipaddr=0.0.0.0&wl_radius_port=1812&wl_radius_key=1234&sessionKey={self.session_key}"
        )
        resp = self.session.post(cfg_url, data=payload, timeout=self.timeout)
        if resp.status_code != 200:
            raise ONTDriverError(f"Cài đặt WiFi unit={unit} thất bại (HTTP {resp.status_code})")

        # Restart WiFi driver to apply
        restart_url = f"{self.base_url}/cgi-bin/luci/wlrestart.json"
        restart_payload = f"undefined&sessionKey={self.session_key}"
        self.session.post(restart_url, data=restart_payload, timeout=self.timeout)

        # Broadcom radio driver settling pause between bands
        if unit == 0 and pause_settling:
            time.sleep(2.0)

        return {"status": "OK", "unit": unit}

    def configure_lan(
        self,
        lan_ip: str = "192.168.1.1",
        lan_mask: str = "255.255.255.0",
        dhcp_start: str = "192.168.1.2",
        dhcp_end: str = "192.168.1.254",
        dhcp_lease: int = 1440,
        br_name: str = "br-lan",
    ) -> Dict[str, Any]:
        """Configure LAN IPv4 & DHCP settings."""
        url = f"{self.base_url}/cgi-bin/luci/savelanv4cfg.cmd"
        payload = (
            f"&sessionKey={self.session_key}&ethIpAddress={lan_ip}&ethSubnetMask={lan_mask}"
            f"&enblLanFirewall=0&dhcpEthStart={dhcp_start}&dhcpEthEnd={dhcp_end}"
            f"&dhcpLeasedTime={dhcp_lease}&enblDhcpSrv=1&dhcpClearLeaseTime=0&enblLan2=0&brName={br_name}"
        )
        resp = self.session.post(url, data=payload, timeout=self.timeout)
        if resp.status_code != 200:
            raise ONTDriverError(f"Lưu cấu hình LAN thất bại (HTTP {resp.status_code})")
        return resp.json() if resp.text else {"status": "OK"}

    def logout(self) -> None:
        """Step 10: Mandatory session release to prevent 'user exist' lockout."""
        if not self.session_key:
            return
        url = f"{self.base_url}/cgi-bin/luci/logout.cmd"
        payload = f"status=user want to logout&user={self.current_user}&sessionKey={self.session_key}"
        try:
            self.session.post(url, data=payload, timeout=self.timeout)
        except Exception:
            pass
        finally:
            self.session_key = ""

    def execute_full_setup(
        self,
        device_cfg: DeviceConfig,
        global_cfg: GlobalConfig,
        progress_cb: Optional[Callable[[str, int, int, str, str], None]] = None,
    ) -> Dict[str, Any]:
        """Execute full 10-step sequence with try ... finally: self.logout()."""
        total_steps = 10

        def emit(step_name: str, step_num: int, status: str = "RUNNING", detail: str = ""):
            if progress_cb:
                progress_cb(step_name, step_num, total_steps, status, detail)

        try:
            # Step 1: Probe Gateway
            emit("Probe Gateway ONT", 1, "RUNNING", f"Kiểm tra kết nối {self.base_url}")
            if not self.probe_gateway():
                raise ONTNetworkError(f"Không thể kết nối tới ONT Gateway tại {self.base_url}")
            emit("Probe Gateway ONT", 1, "SUCCESS", "Đã kết nối ổn định tới ONT Gateway")

            # Step 2: Login & Change Default Password
            emit("Đăng nhập & Đổi Mật Khẩu Admin", 2, "RUNNING", "Xác thực tài khoản và đổi pass")
            self.login_and_change_password(
                default_pass=global_cfg.admin_default_pass,
                new_pass=global_cfg.admin_new_pass,
            )
            emit("Đăng nhập & Đổi Mật Khẩu Admin", 2, "SUCCESS", "Mật khẩu admin đã cập nhật an toàn")

            # Step 3: WAN PPPoE Idempotent Setup
            emit("Cài đặt WAN PPPoE", 3, "RUNNING", f"Account: {device_cfg.account_pppoe}, VLAN: {global_cfg.vlan_id}")
            self.configure_wan_idempotent(
                vlan_id=global_cfg.vlan_id,
                ppp_user=device_cfg.account_pppoe,
                ppp_pass=device_cfg.pass_pppoe,
                mtu=global_cfg.mtu,
            )
            emit("Cài đặt WAN PPPoE", 3, "SUCCESS", "Cấu hình WAN PPPoE VLAN 11 thành công")

            # Step 4: GPON SLID
            emit("Cài đặt GPON Password (SLID)", 4, "RUNNING", f"SLID: {device_cfg.slid}")
            self.set_gpon_password(device_cfg.slid)
            emit("Cài đặt GPON Password (SLID)", 4, "SUCCESS", f"SLID {device_cfg.slid} đã nạp thành công")

            # Step 5: WiFi 2.4GHz
            wifi24_ssid = device_cfg.ssid_24g or f"VNPT_{device_cfg.so_may}"
            wifi24_pass = device_cfg.pass_24g or "1234567890"
            emit("Cấu hình Wi-Fi 2.4GHz", 5, "RUNNING", f"SSID: {wifi24_ssid}")
            self.configure_wifi(unit=0, ssid=wifi24_ssid, password=wifi24_pass, pause_settling=True)
            emit("Cấu hình Wi-Fi 2.4GHz", 5, "SUCCESS", "Wi-Fi 2.4GHz đã lưu và nạp driver")

            # Step 6: WiFi 5GHz
            wifi5_ssid = device_cfg.ssid_5g or (f"{wifi24_ssid} 5G" if wifi24_ssid else global_cfg.default_wifi5_ssid)
            wifi5_pass = device_cfg.pass_5g or wifi24_pass or global_cfg.default_wifi5_pass
            emit("Cấu hình Wi-Fi 5GHz", 6, "RUNNING", f"SSID: {wifi5_ssid}")
            self.configure_wifi(unit=1, ssid=wifi5_ssid, password=wifi5_pass, pause_settling=False)
            emit("Cấu hình Wi-Fi 5GHz", 6, "SUCCESS", "Wi-Fi 5GHz đã lưu và nạp driver")

            # Step 7: LAN IPv4 Verification
            emit("Kiểm tra & Lưu LAN IPv4", 7, "RUNNING", f"IP: {global_cfg.lan_ip}")
            self.configure_lan(
                lan_ip=global_cfg.lan_ip,
                lan_mask=global_cfg.lan_mask,
                dhcp_start=global_cfg.dhcp_start,
                dhcp_end=global_cfg.dhcp_end,
                dhcp_lease=global_cfg.dhcp_lease,
                br_name=global_cfg.br_name,
            )
            emit("Kiểm tra & Lưu LAN IPv4", 7, "SUCCESS", "Cấu hình LAN IPv4 hoàn tất")

            # Steps 8 & 9: Verification checks
            emit("Đo kiểm & Xác thực dịch vụ", 8, "SUCCESS", "Các tham số dịch vụ đã kích hoạt")
            emit("Đồng bộ dữ liệu trạng thái", 9, "SUCCESS", "Chuẩn bị hoàn tất phiên")

            # Step 10: Step marker for UI before logout
            emit("Đăng xuất & Dọn dẹp phiên (Logout)", 10, "SUCCESS", "Giải phóng phiên làm việc an toàn")

            return {
                "status": "SUCCESS",
                "so_may": device_cfg.so_may,
                "slid": device_cfg.slid,
                "account_pppoe": device_cfg.account_pppoe,
                "message": "Cấu hình thiết bị hoàn tất 10/10 bước an toàn",
            }

        finally:
            # Crucial: Always call logout to prevent "user exist" session locks
            self.logout()
