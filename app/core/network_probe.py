"""Network probe module for debounced connection detection to ONT Gateway."""

import os
import re
import time
import subprocess
from urllib.parse import urlparse
import urllib3
import requests
from typing import Callable, Optional

ONT_HTML_SIGNATURES = (
    "igate",
    "gw040",
    "vnpt",
    "login.js",
    "gpon ont",
    "ax3000",
)


class NetworkProbe:
    """Probes the ONT Gateway (default 192.168.1.1) with debounce logic."""

    def __init__(
        self,
        base_url: str = "https://192.168.1.1",
        timeout: float = 2.0,
        required_consecutive_success: int = 3,
        callback: Optional[Callable[[bool, int], None]] = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.required_consecutive_success = required_consecutive_success
        self.callback = callback

        self._consecutive_success = 0
        self._is_online = False
        self._is_paused = False
        self._has_seen_offline = True
        self._current_mac: Optional[str] = None
        self._last_reset_mac: Optional[str] = None
        self._session = self._create_session()
    @staticmethod
    def _create_session() -> requests.Session:
        """Create a fresh HTTP session with Connection: close to prevent stale socket reuse across ONT swaps."""
        session = requests.Session()
        session.verify = False
        session.headers.update({
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
            ),
            "Connection": "close",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        })
        return session

    def _reset_session(self) -> None:
        """Close existing pooled sockets and recreate session."""
        old_session = getattr(self, "_session", None)
        self._session = self._create_session()
        if old_session is not None:
            try:
                old_session.close()
            except Exception:
                pass
    @property
    def is_online(self) -> bool:
        return self._is_online

    @property
    def is_paused(self) -> bool:
        return self._is_paused

    @property
    def has_seen_offline(self) -> bool:
        """Return True if probe has observed at least one offline/disconnected state since last reset."""
        return self._has_seen_offline


    @property
    def is_same_device_as_last_reset(self) -> bool:
        """Return True if current online gateway MAC matches the MAC of the ONT just finished."""
        return bool(self._current_mac and self._last_reset_mac and self._current_mac == self._last_reset_mac)
    def pause(self) -> None:
        """Pause probing (e.g. during active configuration execution)."""
        self._is_paused = True

    def resume(self) -> None:
        """Resume probing."""
        self._is_paused = False

    def _get_gateway_mac(self) -> Optional[str]:
        """Best-effort non-intrusive lookup of gateway MAC from OS ARP cache."""
        host = urlparse(self.base_url).hostname
        if not host or host in ("127.0.0.1", "localhost"):
            return None
        try:
            if os.path.exists("/proc/net/arp"):
                with open("/proc/net/arp", "r", encoding="utf-8") as f:
                    for line in f.readlines()[1:]:
                        parts = line.split()
                        if len(parts) >= 4 and parts[0] == host:
                            mac = parts[3].lower()
                            if mac != "00:00:00:00:00:00":
                                return mac
            elif os.name == "nt":
                flags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
                proc = subprocess.run(
                    ["arp", "-a", host],
                    capture_output=True,
                    text=True,
                    timeout=1.0,
                    creationflags=flags,
                )
                if proc.returncode == 0 and proc.stdout:
                    m = re.search(
                        rf"{re.escape(host)}\s+([0-9a-fA-F]{{2}}(?:-[0-9a-fA-F]{{2}}){{5}})",
                        proc.stdout,
                    )
                    if m:
                        return m.group(1).replace("-", ":").lower()
        except Exception:
            pass
        return None

    def reset(self) -> None:
        """Reset consecutive counter, online status, and recreate HTTP session pool."""
        if self._current_mac:
            self._last_reset_mac = self._current_mac
        self._current_mac = None
        self._consecutive_success = 0
        self._is_online = False
        self._has_seen_offline = False
        self._reset_session()
        if self.callback:
            self.callback(self._is_online, self._consecutive_success)

    def probe_once(self) -> bool:
        """Perform a single HTTP/HTTPS GET request to check ONT availability."""
        if self._is_paused:
            return self._is_online

        reachable = False
        try:
            # Probe root URL or login page with short timeout
            resp = self._session.get(f"{self.base_url}/", timeout=self.timeout)
            if resp.status_code == 200:
                text = getattr(resp, "text", "")
                if isinstance(text, str):
                    text_lower = text.lower()
                    if any(sig in text_lower for sig in ONT_HTML_SIGNATURES):
                        reachable = True
                else:
                    reachable = True
        except (requests.RequestException, OSError):
            # Gateway unreachable or connection refused/reset
            reachable = False

        if reachable:
            self._consecutive_success += 1
            if self._consecutive_success >= self.required_consecutive_success:
                self._is_online = True
                mac = self._get_gateway_mac()
                self._current_mac = mac
                if mac and self._last_reset_mac and mac != self._last_reset_mac:
                    self._has_seen_offline = True
        else:
            self._consecutive_success = 0
            self._is_online = False
            self._has_seen_offline = True
            self._current_mac = None
            self._reset_session()

        if self.callback:
            self.callback(self._is_online, self._consecutive_success)
        return self._is_online

    def wait_until_online(
        self,
        poll_interval: float = 0.5,
        max_timeout: float = 30.0,
        abort_check: Optional[Callable[[], bool]] = None,
    ) -> bool:
        """Synchronously wait until sustained ONLINE status is reached."""
        start = time.time()
        while time.time() - start < max_timeout:
            if abort_check and abort_check():
                return False
            if self.probe_once():
                return True
            time.sleep(poll_interval)
        return False
