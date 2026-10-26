"""Configuration data models for ONT 040-NSv2 setup application."""

import sys
import os
from dataclasses import dataclass, field
from typing import Optional, Dict, Any


def get_app_base_dir() -> str:
    """Return the base directory of the application.

    When running frozen under PyInstaller (--onedir or --onefile), sys.frozen is True
    and sys.executable points to the .exe location.
    In development source mode, returns the root repository folder.
    """
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def get_app_path(relative_path: str = "") -> str:
    """Resolve an absolute path relative to the application base directory."""
    base = get_app_base_dir()
    if not relative_path:
        return base
    return os.path.normpath(os.path.join(base, relative_path))

@dataclass
class DeviceConfig:
    """Configuration parameters for a single ONT device."""
    so_may: str = ""
    slid: str = ""
    account_pppoe: str = ""
    pass_pppoe: str = ""
    ssid_24g: str = ""
    pass_24g: str = ""
    ssid_5g: str = ""
    pass_5g: str = ""
    stt: Optional[int] = None
    ten_tb: Optional[str] = None
    diachi_tb: Optional[str] = None
    so_dt: Optional[str] = None
    extra_data: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        effective = (self.slid or self.so_may or "").strip()
        self.slid = effective
        self.so_may = effective


@dataclass
class GlobalConfig:
    """Global configuration settings for ONT driver and network parameters."""
    admin_user: str = "admin"
    admin_default_pass: str = "admin"
    admin_new_pass: str = "Passwd2@"
    vlan_id: int = 11
    mtu: int = 1492
    lan_ip: str = "192.168.1.1"
    lan_mask: str = "255.255.255.0"
    dhcp_start: str = "192.168.1.2"
    dhcp_end: str = "192.168.1.254"
    dhcp_lease: int = 1440
    br_name: str = "br-lan"
    default_wifi5_ssid: str = "VNPT 5G"
    default_wifi5_pass: str = "1234567890"
    base_url: str = "https://192.168.1.1"
    request_timeout: float = 5.0
    settling_pause_wifi: float = 2.0
    debounce_probe_count: int = 3
