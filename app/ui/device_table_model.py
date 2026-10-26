"""High-performance Qt Table Model for ONT device records with inline editing and reactive re-validation."""

import re
from typing import List, Optional, Any, Tuple, Dict
from PyQt6.QtCore import Qt, QAbstractTableModel, QModelIndex
from PyQt6.QtGui import QColor, QBrush

from app.data.models import DeviceRecord, DeviceStatus
from app.core.ont_driver import UNSAFE_SHELL_METACHARS


COLUMNS = [
    ("STT", "stt"),
    ("SLID", "slid"),
    ("Account PPPoE", "account_pppoe"),
    ("Mật khẩu PPPoE", "pass_pppoe"),
    ("SSID 2.4G", "ssid_24g"),
    ("Mật khẩu 2.4G", "pass_24g"),
    ("SSID 5G", "ssid_5g"),
    ("Mật khẩu 5G", "pass_5g"),
    ("Trạng thái", "status"),
    ("Ghi chú / Chi tiết", "error_message"),
]


def validate_device_record(rec: DeviceRecord) -> List[str]:
    """Validate all fields of a DeviceRecord and return a list of validation error messages."""
    errors: List[str] = []

    if not rec.pass_pppoe:
        errors.append("Thiếu mật khẩu PPPoE - Yêu cầu nhập tay (không dùng mật khẩu giả lập)")

    if not rec.account_pppoe:
        errors.append("Thiếu Account PPPoE")

    if rec.slid:
        if len(rec.slid) > 10:
            errors.append(f"SLID '{rec.slid}' vượt quá 10 ký tự ({len(rec.slid)} ký tự)")
        elif not re.match(r"^[a-zA-Z0-9_\-\.]+$", rec.slid):
            errors.append(f"SLID '{rec.slid}' chứa ký tự không hợp lệ cho GPON")
    else:
        errors.append("Thiếu thông tin SLID / Mã MEN")

    fields_to_check = [
        ("SLID", rec.slid),
        ("Account PPPoE", rec.account_pppoe),
        ("Pass PPPoE", rec.pass_pppoe),
        ("SSID 2.4G", rec.ssid_24g),
        ("Pass 2.4G", rec.pass_24g),
        ("SSID 5G", rec.ssid_5g),
        ("Pass 5G", rec.pass_5g),
    ]
    for field_name, f_val in fields_to_check:
        for ch in (f_val or ""):
            if ch in UNSAFE_SHELL_METACHARS:
                errors.append(f"{field_name} chứa ký tự shell không an toàn: '{ch}'")
                break

    return errors

# Lazy-loaded status brush cache (prevents early C++ QBrush allocation before QApplication
# and avoids exit crashes 0xC0000005 on Windows)
_STATUS_BRUSH_CACHE: Optional[Dict[DeviceStatus, Tuple[QBrush, QBrush]]] = None


def get_status_brush(status: DeviceStatus) -> Tuple[Optional[QBrush], Optional[QBrush]]:
    """Lazy-load and return cached (background_brush, foreground_brush) for a given DeviceStatus.

    Eliminates heap allocation of QBrush and QColor on each paint frame in data().
    Returns (None, None) for unstyled or PENDING status.
    """
    global _STATUS_BRUSH_CACHE
    if _STATUS_BRUSH_CACHE is None:
        _STATUS_BRUSH_CACHE = {
            DeviceStatus.INVALID_DATA: (
                QBrush(QColor("#FFF3CD")),  # Light Amber/Yellow bg
                QBrush(QColor("#856404")),  # Dark amber fg
            ),
            DeviceStatus.RUNNING: (
                QBrush(QColor("#D1ECF1")),  # Light Blue bg
                QBrush(QColor("#0C5460")),  # Dark blue fg
            ),
            DeviceStatus.SUCCESS: (
                QBrush(QColor("#D4EDDA")),  # Light Green bg
                QBrush(QColor("#155724")),  # Dark green fg
            ),
            DeviceStatus.FAILED: (
                QBrush(QColor("#F8D7DA")),  # Light Red bg
                QBrush(QColor("#721C24")),  # Dark red fg
            ),
            DeviceStatus.SKIPPED: (
                QBrush(QColor("#E2E3E5")),  # Light Gray bg
                QBrush(QColor("#383D41")),  # Dark gray fg
            ),
        }
    return _STATUS_BRUSH_CACHE.get(status, (None, None))


class DeviceTableModel(QAbstractTableModel):
    """Table model displaying ONT configuration records with inline editing and colored status."""

    def __init__(self, records: Optional[List[DeviceRecord]] = None):
        super().__init__()
        self.records: List[DeviceRecord] = records or []

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return len(self.records)

    def columnCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return len(COLUMNS)

    def headerData(self, section: int, orientation: Qt.Orientation, role: int = Qt.ItemDataRole.DisplayRole) -> Any:
        if orientation == Qt.Orientation.Horizontal and role == Qt.ItemDataRole.DisplayRole:
            if 0 <= section < len(COLUMNS):
                return COLUMNS[section][0]
        return None

    def get_record(self, row: int) -> Optional[DeviceRecord]:
        """Retrieve record by row index."""
        if 0 <= row < len(self.records):
            return self.records[row]
        return None

    def set_records(self, records: List[DeviceRecord]) -> None:
        """Replace records and reset model."""
        self.beginResetModel()
        self.records = records
        self.endResetModel()

    def update_record_status(
        self,
        row: int,
        status: DeviceStatus,
        message: Optional[str] = None,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
    ) -> None:
        """Update status and timestamps for a device record."""
        rec = self.get_record(row)
        if rec:
            rec.status = status
            if message is not None:
                rec.error_message = message
            if start_time is not None:
                rec.start_time = start_time
            if end_time is not None:
                rec.end_time = end_time

            left_idx = self.index(row, 0)
            right_idx = self.index(row, self.columnCount() - 1)
            self.dataChanged.emit(left_idx, right_idx)

    def flags(self, index: QModelIndex) -> Qt.ItemFlag:
        if not index.isValid():
            return Qt.ItemFlag.NoItemFlags

        base_flags = Qt.ItemFlag.ItemIsEnabled | Qt.ItemFlag.ItemIsSelectable
        # Editable columns: SLID (col 1), PPPoE password (col 3), WiFi SSID/Pass (cols 4..7)
        editable_cols = {1, 3, 4, 5, 6, 7}
        if index.column() in editable_cols:
            return base_flags | Qt.ItemFlag.ItemIsEditable

        return base_flags

    def data(self, index: QModelIndex, role: int = Qt.ItemDataRole.DisplayRole) -> Any:
        if not index.isValid() or index.row() >= len(self.records):
            return None

        rec = self.records[index.row()]
        col = index.column()
        attr_name = COLUMNS[col][1]

        if role == Qt.ItemDataRole.DisplayRole or role == Qt.ItemDataRole.EditRole:
            if attr_name == "status":
                return rec.status.value if isinstance(rec.status, DeviceStatus) else str(rec.status)
            if attr_name == "error_message":
                if rec.error_message:
                    return rec.error_message
                if rec.validation_errors:
                    return ", ".join(rec.validation_errors)
                return "Sẵn sàng" if rec.is_valid else "Lỗi dữ liệu"

            val = getattr(rec, attr_name, "")
            return "" if val is None else str(val)

        elif role in (Qt.ItemDataRole.BackgroundRole, Qt.ItemDataRole.ForegroundRole):
            bg_brush, fg_brush = get_status_brush(rec.status)
            return bg_brush if role == Qt.ItemDataRole.BackgroundRole else fg_brush
        elif role == Qt.ItemDataRole.TextAlignmentRole:
            if col in (0, 8):  # STT, Status
                return int(Qt.AlignmentFlag.AlignCenter)
            return int(Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft)

        return None

    def setData(self, index: QModelIndex, value: Any, role: int = Qt.ItemDataRole.EditRole) -> bool:
        if not index.isValid() or role != Qt.ItemDataRole.EditRole:
            return False

        row = index.row()
        col = index.column()
        rec = self.records[row]
        attr_name = COLUMNS[col][1]

        val_str = str(value or "").strip()
        setattr(rec, attr_name, val_str)
        if attr_name == "slid":
            rec.so_may = val_str

        # Reactive re-validation when any editable field is modified
        if col in {1, 3, 4, 5, 6, 7}:
            rec.validation_errors = validate_device_record(rec)
            rec.is_valid = len(rec.validation_errors) == 0
            if rec.is_valid and rec.status == DeviceStatus.INVALID_DATA:
                rec.status = DeviceStatus.PENDING
                rec.error_message = None
            elif not rec.is_valid:
                rec.status = DeviceStatus.INVALID_DATA
        left_idx = self.index(row, 0)
        right_idx = self.index(row, self.columnCount() - 1)
        self.dataChanged.emit(left_idx, right_idx)
        return True
