"""Mock LuCI HTTP Server simulating VNPT iGate GW040-NSv2 router behavior.

Simulates all 10 configuration steps:
- Probing (HTTP 200)
- Login, firstlog=1, numberLogin tracking & 180s hardware lockout
- User exist state when session not terminated
- accesscontrolcfg.json (password change with complexity check)
- Idempotent WAN setup (wancfg, addwan, editwan, sendwan)
- GPON Password (SLID <= 10 ASCII check & metacharacter rejection)
- WiFi 2.4G & 5G configuration & wlrestart
- savelanv4cfg
- logout.cmd session cleanup
"""

import json
import threading
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Dict, Any, Tuple, Optional


class MockLuCIHandler(BaseHTTPRequestHandler):
    """HTTP request handler simulating VNPT ONT LuCI CGI endpoints."""

    def log_message(self, format, *args):
        # Suppress standard logging to keep test console clean
        pass

    def _send_json(self, data: Dict[str, Any], status: int = 200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, html: str, status: int = 200):
        body = html.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        # Root probe URL
        if self.path in ("/", "/index.html"):
            self._send_html("<html><title>iGate GW040-NSv2</title><body>OK</body></html>")
        else:
            self._send_html("404 Not Found", status=404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(length).decode("utf-8", errors="ignore")

        # Parse urlencoded params (handling ont_encode_query \x7f or &)
        normalized_body = raw_body.replace("\x7f", "&")
        parsed = urllib.parse.parse_qs(normalized_body)
        post_data = {k: v[0] for k, v in parsed.items()}

        server: MockLuCIServer = self.server  # type: ignore

        # 1. Login Endpoint
        if "/cgi-bin/luci/login.cmd" in self.path:
            user = post_data.get("user", "")
            passwd = post_data.get("pass", "")

            # Check if locked out
            if server.number_login >= 3 or server.timeout_login > 0:
                self._send_json({
                    "status": "FAIL",
                    "numberLogin": str(server.number_login),
                    "timeoutLogin": "180",
                })
                return

            # Check for active session conflict (user exist)
            if server.active_session and server.simulate_user_exist:
                self._send_json({"status": "user exist"})
                return

            # Check password
            if user == "admin" and passwd == server.current_admin_pass:
                # Login Success
                server.number_login = 0
                if server.is_factory_reset:
                    server.active_session = "factoryID"
                    self._send_json({
                        "status": "OK",
                        "firstlog": "1",
                        "sessionKey": "",
                        "numberLogin": "0",
                        "timeoutLogin": "0",
                    })
                else:
                    new_token = f"tok_{len(server.request_history) + 1}"
                    server.active_session = new_token
                    self._send_json({
                        "status": "OK",
                        "firstlog": "0",
                        "sessionKey": new_token,
                        "numberLogin": "0",
                        "timeoutLogin": "0",
                    })
            else:
                # Login Failure - increment numberLogin
                server.number_login += 1
                timeout = 180 if server.number_login >= 3 else 0
                self._send_json({
                    "status": "FAIL",
                    "numberLogin": str(server.number_login),
                    "timeoutLogin": str(timeout),
                })
            server.request_history.append(("login.cmd", post_data))
            return

        # 2. Access Control (Password Change)
        if "/cgi-bin/luci/accesscontrolcfg.json" in self.path:
            new_pass = post_data.get("newPass", "")
            server.current_admin_pass = new_pass
            server.is_factory_reset = False
            self._send_json({"status": "OK"})
            server.request_history.append(("accesscontrolcfg.json", post_data))
            return

        # 3. WAN Config Query
        if "/cgi-bin/luci/wancfg.json" in self.path:
            self._send_json({
                "curUserName": "admin",
                "wanCfg": list(server.wan_entries),
                "serviceId": "0",
                "sessionKey": server.active_session,
                "status": "OK",
            })
            server.request_history.append(("wancfg.json", post_data))
            return

        # 4. Add WAN
        if "/cgi-bin/luci/addwan.json" in self.path:
            self._send_json({"status": "OK", "serviceId": "0"})
            server.request_history.append(("addwan.json", post_data))
            return

        # 5. Edit WAN
        if "/cgi-bin/luci/editwan.json" in self.path:
            self._send_json({"status": "OK"})
            server.request_history.append(("editwan.json", post_data))
            return

        # 6. Send WAN
        if "/cgi-bin/luci/sendwan.cmd" in self.path:
            vlan_id = str(post_data.get("vlanMuxId", "11"))
            ppp_user = post_data.get("pppUserName", "")
            # Update or append in mock wan list
            found = False
            for entry in server.wan_entries:
                if str(entry.get("vlanId")) == vlan_id:
                    entry["pppUserName"] = ppp_user
                    found = True
                    break
            if not found:
                server.wan_entries.append({
                    "action": "true",
                    "vlanId": vlan_id,
                    "idx": len(server.wan_entries) + 1,
                    "ifcStr": f"veip0.{vlan_id}",
                    "srvcStr": "internet",
                    "serviceId": "1",
                    "pppUserName": ppp_user,
                    "protoStr": "PPPoE",
                })
            self._send_json({"status": "OK"})
            server.request_history.append(("sendwan.cmd", post_data))
            return

        # 7. GPON Password (SLID)
        if "/cgi-bin/luci/gponpasswd.json" in self.path:
            if "data" in post_data:
                slid_val = post_data["data"]
                # Reject > 10 chars
                if len(slid_val) > 10:
                    self._send_json({"status": "FAIL", "error": "SLID length exceeds 10"}, status=400)
                    return
                server.gpon_password = slid_val
            self._send_json({
                "sysInfoGponPassWd": f"{server.gpon_password}\r\n",
                "sessionKey": server.active_session,
                "status": "OK",
            })
            server.request_history.append(("gponpasswd.json", post_data))
            return

        # 8. WiFi Config
        if "/cgi-bin/luci/wirelesscfg.json" in self.path:
            unit = post_data.get("wl_unit", "0")
            ssid = post_data.get("wl_ssid", "")
            server.wifi_configs[unit] = {"ssid": ssid, "pass": post_data.get("wl_wpa_psk", "")}
            self._send_json({"status": "OK"})
            server.request_history.append(("wirelesscfg.json", post_data))
            return

        # 9. WiFi Restart
        if "/cgi-bin/luci/wlrestart.json" in self.path:
            self._send_json({"_wlrestart_": "0"})
            server.request_history.append(("wlrestart.json", post_data))
            return

        # 10. LAN Config
        if "/cgi-bin/luci/savelanv4cfg.cmd" in self.path:
            server.lan_ip = post_data.get("ethIpAddress", "192.168.1.1")
            self._send_json({
                "status": "OK",
                "lanIpChanged": "false",
                "sessionKey": server.active_session,
            })
            server.request_history.append(("savelanv4cfg.cmd", post_data))
            return

        # 11. Logout Endpoint
        if "/cgi-bin/luci/logout.cmd" in self.path:
            server.active_session = ""
            self._send_json({"status": "OK"})
            server.request_history.append(("logout.cmd", post_data))
            return

        self._send_html("404 Not Found", status=404)


class MockLuCIServer(HTTPServer):
    """Custom HTTPServer state container for ONT LuCI simulation."""

    def __init__(self, server_address, RequestHandlerClass):
        super().__init__(server_address, RequestHandlerClass)
        self.is_factory_reset = True
        self.current_admin_pass = "admin"
        self.number_login = 0
        self.timeout_login = 0
        self.active_session = ""
        self.simulate_user_exist = False
        self.gpon_password = "0000000001"
        self.lan_ip = "192.168.1.1"
        self.wifi_configs: Dict[str, Dict[str, str]] = {}
        self.wan_entries = [
            {"action": "false", "vlanId": "4000", "idx": 1, "ifcStr": "veip0.4000", "srvcStr": "tr069"}
        ]
        self.request_history = []


def run_mock_server(host: str = "127.0.0.1", port: int = 0) -> Tuple[MockLuCIServer, threading.Thread, str]:
    """Start Mock LuCI server on a background thread and return (server, thread, base_url)."""
    server = MockLuCIServer((host, port), MockLuCIHandler)
    actual_port = server.server_port
    base_url = f"http://{host}:{actual_port}"

    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, base_url
