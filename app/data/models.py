"""Data models for Excel records and device state management."""

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional, List, Dict, Any

from app.core.config_models import DeviceConfig


class DeviceStatus(str, Enum):
    """Execution status for each ONT device."""
    PENDING = "CHỜ_CẤU_HÌNH"
    INVALID_DATA = "LỖI_DỮ_LIỆU"
    RUNNING = "ĐANG_CẤU_HÌNH"
    SUCCESS = "THÀNH_CÔNG"
    FAILED = "THẤT_BẠI"
    SKIPPED = "BỎ_QUA"

    def __str__(self) -> str:
        return self.value

    def __repr__(self) -> str:
        return self.value


@dataclass
class DeviceRecord:
    """Represents a row parsed from the input Excel configuration file."""
    row_index: int
    stt: Optional[int] = None
    so_may: str = ""
    slid: str = ""
    account_pppoe: str = ""
    pass_pppoe: str = ""
    ssid_24g: str = ""
    pass_24g: str = ""
    ssid_5g: str = ""
    pass_5g: str = ""
    ten_tb: str = ""
    diachi_tb: str = ""
    so_dt: str = ""
    is_valid: bool = True
    validation_errors: List[str] = field(default_factory=list)
    status: str = DeviceStatus.PENDING
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    error_message: Optional[str] = None
    raw_row_data: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        effective = (self.slid or self.so_may or "").strip()
        self.slid = effective
        self.so_may = effective

    def to_device_config(self) -> DeviceConfig:
        """Convert record to driver DeviceConfig."""
        effective_slid = (self.slid or self.so_may or "").strip()
        return DeviceConfig(
            so_may=effective_slid,
            slid=effective_slid,
            account_pppoe=self.account_pppoe,
            pass_pppoe=self.pass_pppoe,
            ssid_24g=self.ssid_24g,
            pass_24g=self.pass_24g,
            ssid_5g=self.ssid_5g,
            pass_5g=self.pass_5g,
            stt=self.stt,
            ten_tb=self.ten_tb,
            diachi_tb=self.diachi_tb,
            so_dt=self.so_dt,
            extra_data=self.raw_row_data,
        )

    def to_dict(self) -> Dict[str, Any]:
        """Serialize record to dictionary for checkpoint storage."""
        effective_slid = (self.slid or self.so_may or "").strip()
        return {
            "row_index": self.row_index,
            "stt": self.stt,
            "so_may": effective_slid,
            "slid": effective_slid,
            "account_pppoe": self.account_pppoe,
            "pass_pppoe": self.pass_pppoe,
            "ssid_24g": self.ssid_24g,
            "pass_24g": self.pass_24g,
            "ssid_5g": self.ssid_5g,
            "pass_5g": self.pass_5g,
            "ten_tb": self.ten_tb,
            "diachi_tb": self.diachi_tb,
            "so_dt": self.so_dt,
            "is_valid": self.is_valid,
            "validation_errors": list(self.validation_errors),
            "status": self.status.value if isinstance(self.status, DeviceStatus) else str(self.status),
            "start_time": self.start_time,
            "end_time": self.end_time,
            "error_message": self.error_message,
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DeviceRecord":
        """Deserialize record from dictionary."""
        effective_slid = str(data.get("slid") or data.get("so_may") or "").strip()
        return cls(
            row_index=data.get("row_index", 0),
            stt=data.get("stt"),
            so_may=effective_slid,
            slid=effective_slid,
            account_pppoe=data.get("account_pppoe", ""),
            pass_pppoe=data.get("pass_pppoe", ""),
            ssid_24g=data.get("ssid_24g", ""),
            pass_24g=data.get("pass_24g", ""),
            ssid_5g=data.get("ssid_5g", ""),
            pass_5g=data.get("pass_5g", ""),
            ten_tb=data.get("ten_tb", ""),
            diachi_tb=data.get("diachi_tb", ""),
            so_dt=data.get("so_dt", ""),
            is_valid=data.get("is_valid", True),
            validation_errors=data.get("validation_errors", []),
            status=data.get("status", DeviceStatus.PENDING),
            start_time=data.get("start_time"),
            end_time=data.get("end_time"),
            error_message=data.get("error_message"),
        )
