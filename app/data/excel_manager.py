"""Excel parsing, validation, checkpoint persistence, and report export."""

import os
import re
import json
import shutil
from typing import List, Dict, Tuple, Optional, Any
import openpyxl

from app.data.models import DeviceRecord, DeviceStatus
from app.core.ont_driver import UNSAFE_SHELL_METACHARS


class ExcelParsingError(Exception):
    """Raised when parsing Excel file structure fails."""
    pass


class ExcelFileLockedError(Exception):
    """Raised when an Excel file is locked by another program (e.g. MS Excel)."""
    pass


class ExcelManager:
    """Manages loading, validating, checkpointing, and exporting ONT Excel records."""

    def __init__(self):
        self.column_indices: Dict[str, int] = {}

    @staticmethod
    def derive_wifi5_config(
        ssid_24g: str,
        pass_24g: str,
        raw_ssid_5g: Optional[str] = "",
        raw_pass_5g: Optional[str] = "",
        default_ssid_5g: str = "VNPT 5G",
        default_pass_5g: str = "1234567890",
    ) -> Tuple[str, str]:
        """Derive 5GHz SSID and Password following 2.4G inheritance and length constraints."""
        raw_ssid_5g = (raw_ssid_5g or "").strip()
        raw_pass_5g = (raw_pass_5g or "").strip()
        ssid_24g = (ssid_24g or "").strip()
        pass_24g = (pass_24g or "").strip()

        # SSID 5G Derivation
        if raw_ssid_5g:
            ssid_5g = raw_ssid_5g
        elif ssid_24g:
            ssid_5g = f"{ssid_24g} 5G"
        else:
            ssid_5g = default_ssid_5g

        # Enforce max 32 characters
        if len(ssid_5g) > 32:
            ssid_5g = ssid_5g[:32].strip()

        # Password 5G Derivation
        if raw_pass_5g:
            pass_5g = raw_pass_5g
        elif pass_24g:
            pass_5g = pass_24g
        else:
            pass_5g = default_pass_5g

        return ssid_5g, pass_5g

    def _locate_headers(self, ws) -> Dict[str, int]:
        """Identify column index (1-based) by standard header names."""
        indices: Dict[str, int] = {}
        for col in range(1, ws.max_column + 1):
            val = ws.cell(1, col).value
            if val is None:
                continue
            hdr = str(val).strip()
            indices[hdr] = col
        return indices

    def load_file(
        self,
        file_path: str,
        default_wifi5_ssid: str = "VNPT 5G",
        default_wifi5_pass: str = "1234567890",
    ) -> List[DeviceRecord]:
        """Load and strictly validate Excel file into DeviceRecord objects."""
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"File không tồn tại: {file_path}")

        try:
            wb = openpyxl.load_workbook(file_path, data_only=True)
        except Exception as e:
            raise ExcelParsingError(f"Không thể đọc file Excel '{file_path}': {e}") from e

        ws = wb.active
        self.column_indices = self._locate_headers(ws)

        def get_val(row_idx: int, hdr_name: str, fallback_idx: Optional[int] = None) -> Any:
            col_idx = self.column_indices.get(hdr_name, fallback_idx)
            if col_idx is not None and col_idx <= ws.max_column:
                return ws.cell(row_idx, col_idx).value
            return None

        records: List[DeviceRecord] = []
        for r in range(2, ws.max_row + 1):
            # Check if row is entirely empty
            stt_val = get_val(r, "STT", 1)
            so_may_val = get_val(r, "SO_MAY", 9)
            ma_men_val = get_val(r, "Mã MEN", 13)
            account_val = get_val(r, "Account", 10)

            # Preserve string format with leading zeros; SLID priority: Mã MEN first, then SO_MAY
            so_may = str(so_may_val).strip() if so_may_val is not None else ""
            ma_men = str(ma_men_val).strip() if ma_men_val is not None else ""
            slid = ma_men if ma_men else so_may
            so_may = slid

            if stt_val is None and not slid and account_val is None:
                continue

            stt = int(stt_val) if stt_val is not None and str(stt_val).isdigit() else None
            ten_tb = str(get_val(r, "TEN_TB", 6) or "").strip()
            diachi_tb = str(get_val(r, "DIACHI_TB", 7) or "").strip()
            so_dt = str(get_val(r, "SO_DT", 8) or "").strip()
            account_pppoe = str(account_val or "").strip()

            pass_pppoe_val = get_val(r, "Mật khẩu PPPoE", 11)
            pass_pppoe = str(pass_pppoe_val).strip() if pass_pppoe_val is not None else ""
            ssid_24g = str(get_val(r, "SSID Wi-Fi 2.4G", 15) or "").strip()
            pass_24g = str(get_val(r, "Mật khẩu Wi-Fi 2.4G", 16) or "").strip()

            raw_ssid_5g = str(get_val(r, "SSID Wi-Fi 5G", 19) or "").strip()
            raw_pass_5g = str(get_val(r, "Mật khẩu Wi-Fi 5G", 20) or "").strip()

            ssid_5g, pass_5g = self.derive_wifi5_config(
                ssid_24g=ssid_24g,
                pass_24g=pass_24g,
                raw_ssid_5g=raw_ssid_5g,
                raw_pass_5g=raw_pass_5g,
                default_ssid_5g=default_wifi5_ssid,
                default_pass_5g=default_wifi5_pass,
            )

            # Strict Validation
            errors: List[str] = []

            # 1. Fatal: Missing PPPoE password
            if not pass_pppoe:
                errors.append("Thiếu mật khẩu PPPoE - Yêu cầu nhập tay (không dùng mật khẩu giả lập)")

            # 2. Identification fields
            if not account_pppoe:
                errors.append("Thiếu Account PPPoE")

            # 3. SLID format and length check
            if slid:
                if len(slid) > 10:
                    errors.append(f"SLID '{slid}' vượt quá 10 ký tự ({len(slid)} ký tự)")
                elif not re.match(r"^[a-zA-Z0-9_\-\.]+$", slid):
                    errors.append(f"SLID '{slid}' chứa ký tự không hợp lệ cho GPON")
            else:
                errors.append("Thiếu thông tin SLID / Mã MEN")

            # 4. Check for unsafe shell metacharacters
            fields_to_check = [
                ("SLID", slid),
                ("Account PPPoE", account_pppoe),
                ("Pass PPPoE", pass_pppoe),
                ("SSID 2.4G", ssid_24g),
                ("Pass 2.4G", pass_24g),
                ("SSID 5G", ssid_5g),
                ("Pass 5G", pass_5g),
            ]
            for field_name, f_val in fields_to_check:
                for ch in f_val:
                    if ch in UNSAFE_SHELL_METACHARS:
                        errors.append(f"{field_name} chứa ký tự shell không an toàn: '{ch}'")
                        break

            is_valid = len(errors) == 0
            status = DeviceStatus.PENDING if is_valid else DeviceStatus.INVALID_DATA

            # Store raw row mapping
            raw_data = {
                ws.cell(1, c).value: ws.cell(r, c).value
                for c in range(1, ws.max_column + 1)
                if ws.cell(1, c).value is not None
            }

            record = DeviceRecord(
                row_index=r,
                stt=stt,
                so_may=so_may,
                slid=slid,
                account_pppoe=account_pppoe,
                pass_pppoe=pass_pppoe,
                ssid_24g=ssid_24g,
                pass_24g=pass_24g,
                ssid_5g=ssid_5g,
                pass_5g=pass_5g,
                ten_tb=ten_tb,
                diachi_tb=diachi_tb,
                so_dt=so_dt,
                is_valid=is_valid,
                validation_errors=errors,
                status=status,
                raw_row_data=raw_data,
            )
            records.append(record)

        return records

    def save_checkpoint(self, records: List[DeviceRecord], checkpoint_path: str = ".checkpoint_results.json") -> None:
        """Atomically persist status checkpoint to disk after each ONT device."""
        data = {r.slid or str(r.row_index): r.to_dict() for r in records}
        tmp_path = f"{checkpoint_path}.tmp"
        try:
            with open(tmp_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp_path, checkpoint_path)
        except Exception as e:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
            raise e

    def load_checkpoint(self, checkpoint_path: str = ".checkpoint_results.json") -> Dict[str, Dict[str, Any]]:
        """Load checkpoint results dictionary from disk."""
        if not os.path.exists(checkpoint_path):
            return {}
        try:
            with open(checkpoint_path, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def export_results(
        self,
        output_path: str,
        records: List[DeviceRecord],
        source_excel_path: Optional[str] = None,
    ) -> str:
        """Export results into a new Excel file with configuration status columns."""
        try:
            if source_excel_path and os.path.exists(source_excel_path):
                wb = openpyxl.load_workbook(source_excel_path)
                ws = wb.active
            else:
                wb = openpyxl.Workbook()
                ws = wb.active
                # Write basic header
                headers = [
                    "STT", "SO_MAY", "Account", "Mật khẩu PPPoE", "Mã MEN",
                    "SSID Wi-Fi 2.4G", "Mật khẩu Wi-Fi 2.4G", "SSID Wi-Fi 5G", "Mật khẩu Wi-Fi 5G"
                ]
                for c_idx, h in enumerate(headers, start=1):
                    ws.cell(1, c_idx, h)
                for r_idx, rec in enumerate(records, start=2):
                    ws.cell(r_idx, 1, rec.stt)
                    ws.cell(r_idx, 2, rec.slid)
                    ws.cell(r_idx, 3, rec.account_pppoe)
                    ws.cell(r_idx, 4, rec.pass_pppoe)
                    ws.cell(r_idx, 5, rec.slid)
                    ws.cell(r_idx, 6, rec.ssid_24g)
                    ws.cell(r_idx, 7, rec.pass_24g)
                    ws.cell(r_idx, 8, rec.ssid_5g)
                    ws.cell(r_idx, 9, rec.pass_5g)

            col_map = self._locate_headers(ws)
            has_source = bool(source_excel_path and os.path.exists(source_excel_path))
            so_may_col = col_map.get("SO_MAY", 9 if has_source else 2)
            ma_men_col = col_map.get("Mã MEN", 13 if has_source else 5)
            pass_pppoe_col = col_map.get("Mật khẩu PPPoE")
            ssid_24_col = col_map.get("SSID Wi-Fi 2.4G")
            pass_24_col = col_map.get("Mật khẩu Wi-Fi 2.4G")
            ssid_5_col = col_map.get("SSID Wi-Fi 5G")
            pass_5_col = col_map.get("Mật khẩu Wi-Fi 5G")

            # Append status columns
            base_col = ws.max_column
            status_col = base_col + 1
            time_col = base_col + 2
            note_col = base_col + 3

            ws.cell(1, status_col, "Trạng thái cấu hình")
            ws.cell(1, time_col, "Thời gian thực hiện")
            ws.cell(1, note_col, "Ghi chú chi tiết")

            # Create quick lookup by row_index, slid, or original raw SO_MAY
            rec_map = {r.row_index: r for r in records}
            slid_map: Dict[str, DeviceRecord] = {}
            for r in records:
                if r.slid:
                    slid_map[r.slid] = r
                raw_so_may = str(r.raw_row_data.get("SO_MAY") or "").strip() if r.raw_row_data else ""
                if raw_so_may:
                    slid_map[raw_so_may] = r

            for row in range(2, ws.max_row + 1):
                rec = rec_map.get(row)
                if not rec:
                    so_val = str(ws.cell(row, so_may_col).value or "").strip()
                    rec = slid_map.get(so_val)

                if rec:
                    if so_may_col:
                        ws.cell(row, so_may_col, rec.slid)
                    if ma_men_col:
                        ws.cell(row, ma_men_col, rec.slid)
                    if pass_pppoe_col and rec.pass_pppoe:
                        ws.cell(row, pass_pppoe_col, rec.pass_pppoe)
                    if ssid_24_col and rec.ssid_24g:
                        ws.cell(row, ssid_24_col, rec.ssid_24g)
                    if pass_24_col and rec.pass_24g:
                        ws.cell(row, pass_24_col, rec.pass_24g)
                    if ssid_5_col and rec.ssid_5g:
                        ws.cell(row, ssid_5_col, rec.ssid_5g)
                    if pass_5_col and rec.pass_5g:
                        ws.cell(row, pass_5_col, rec.pass_5g)

                    ws.cell(row, status_col, str(rec.status))
                    time_str = ""
                    if rec.start_time and rec.end_time:
                        time_str = f"{rec.start_time} -> {rec.end_time}"
                    elif rec.end_time:
                        time_str = rec.end_time
                    ws.cell(row, time_col, time_str)

                    note = rec.error_message or (", ".join(rec.validation_errors) if rec.validation_errors else "OK")
                    ws.cell(row, note_col, note)
            wb.save(output_path)
            return output_path

        except PermissionError as e:
            raise ExcelFileLockedError(
                f"File '{output_path}' đang bị khóa bởi ứng dụng khác (ví dụ Microsoft Excel). "
                "Vui lòng đóng file Excel trước khi xuất báo cáo hoặc lưu với tên khác."
            ) from e
        except Exception as e:
            raise ExcelParsingError(f"Lỗi khi xuất file báo cáo Excel: {e}") from e
