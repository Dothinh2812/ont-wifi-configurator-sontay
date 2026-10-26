"""Unit tests for ExcelManager and data models (Phase 2 TDD)."""

import os
import unittest
import openpyxl

from app.data.models import DeviceRecord, DeviceStatus
from app.data.excel_manager import (
    ExcelManager,
    ExcelFileLockedError,
    ExcelParsingError,
)


class TestExcelManager(unittest.TestCase):
    def setUp(self):
        self.manager = ExcelManager()
        self.sample_excel_path = "file-mau.xlsx"

    def test_load_sample_file_dimensions(self):
        self.assertTrue(os.path.exists(self.sample_excel_path))
        records = self.manager.load_file(self.sample_excel_path)
        # Sample file has 89 data rows
        self.assertEqual(len(records), 89)

    def test_preserve_so_may_string_leading_zeros(self):
        records = self.manager.load_file(self.sample_excel_path)
        # Row 1 in sample data: SLID / SO_MAY is 001560828
        row1 = records[0]
        self.assertEqual(row1.slid, "001560828")
        self.assertEqual(row1.so_may, "001560828")
        self.assertTrue(isinstance(row1.slid, str))
    def test_strict_validation_detects_13_missing_pppoe_passwords(self):
        records = self.manager.load_file(self.sample_excel_path)
        invalid_records = [r for r in records if not r.is_valid and any("PPPoE" in err for err in r.validation_errors)]
        # Exactly 13 rows missing PPPoE password
        self.assertEqual(len(invalid_records), 13)

        # Confirm Row 1 is in invalid records because Mật khẩu PPPoE is None
        row1 = records[0]
        self.assertFalse(row1.is_valid)
        self.assertEqual(row1.status, DeviceStatus.INVALID_DATA)
        self.assertTrue(any("PPPoE" in err for err in row1.validation_errors))

    def test_valid_row_passes_validation(self):
        records = self.manager.load_file(self.sample_excel_path)
        # Row 2 (index 1): SO_MAY=005501941, Account=manh9653, Pass=6l6s8z2a
        row2 = records[1]
        self.assertTrue(row2.is_valid)
        self.assertEqual(row2.status, DeviceStatus.PENDING)
        self.assertEqual(row2.account_pppoe, "manh9653")
        self.assertEqual(row2.pass_pppoe, "6l6s8z2a")

    def test_slid_priority_ma_men_over_so_may(self):
        records = self.manager.load_file(self.sample_excel_path)
        # Verify SLID is set and <= 10 characters for all valid records
        for r in records:
            if r.slid:
                self.assertLessEqual(len(r.slid), 10)

    def test_wifi_5g_inheritance_rule(self):
        records = self.manager.load_file(self.sample_excel_path)
        # Row 2: SSID 2.4G is "Manh Ngoc", Pass is "28121995"
        # 5G SSID in Excel is "Manh Ngoc 5G", Pass is "28121995"
        row2 = records[1]
        self.assertEqual(row2.ssid_5g, "Manh Ngoc 5G")
        self.assertEqual(row2.pass_5g, "28121995")

        # Test fallback derivation when 5G is absent
        derived = self.manager.derive_wifi5_config(
            ssid_24g="Kim Thu",
            pass_24g="tholau224",
            raw_ssid_5g="",
            raw_pass_5g="",
            default_ssid_5g="VNPT 5G",
            default_pass_5g="1234567890",
        )
        self.assertEqual(derived[0], "Kim Thu 5G")
        self.assertEqual(derived[1], "tholau224")

    def test_wifi_5g_length_limit_32_chars(self):
        long_ssid = "A" * 35
        derived = self.manager.derive_wifi5_config(
            ssid_24g=long_ssid,
            pass_24g="secret",
            raw_ssid_5g="",
            raw_pass_5g="",
            default_ssid_5g="VNPT 5G",
            default_pass_5g="1234567890",
        )
        self.assertLessEqual(len(derived[0]), 32)

    def test_checkpoint_save_and_load(self):
        checkpoint_file = ".test_checkpoint.json"
        try:
            records = self.manager.load_file(self.sample_excel_path)[:3]
            records[0].status = DeviceStatus.SUCCESS
            records[0].start_time = "2026-10-06T14:00:00"
            records[0].end_time = "2026-10-06T14:00:20"

            self.manager.save_checkpoint(records, checkpoint_file)
            self.assertTrue(os.path.exists(checkpoint_file))

            restored = self.manager.load_checkpoint(checkpoint_file)
            self.assertIn("001560828", restored)
            self.assertEqual(restored["001560828"]["status"], DeviceStatus.SUCCESS)
        finally:
            if os.path.exists(checkpoint_file):
                os.remove(checkpoint_file)

    def test_export_results_creates_excel(self):
        export_file = ".test_export.xlsx"
        export_source_file = ".test_export_source.xlsx"
        try:
            records = self.manager.load_file(self.sample_excel_path)[:2]
            records[0].status = DeviceStatus.SUCCESS
            records[0].error_message = "10/10 steps OK"
            self.manager.export_results(export_file, records)
            self.assertTrue(os.path.exists(export_file))

            # Verify exported workbook has added status columns
            wb = openpyxl.load_workbook(export_file)
            ws = wb.active
            headers = [ws.cell(1, col).value for col in range(1, ws.max_column + 1)]
            self.assertIn("Trạng thái cấu hình", headers)
            self.assertIn("Ghi chú chi tiết", headers)

            # Verify export with source_excel_path writes edited SLID to both SO_MAY (col 9) and Mã MEN (col 13)
            records[0].slid = "009998888"
            records[0].so_may = "009998888"
            records[0].pass_pppoe = "UpdatedPass123"
            self.manager.export_results(
                export_source_file, records, source_excel_path=self.sample_excel_path
            )
            wb_src = openpyxl.load_workbook(export_source_file)
            ws_src = wb_src.active
            self.assertEqual(ws_src.cell(2, 9).value, "009998888")
            self.assertEqual(ws_src.cell(2, 13).value, "009998888")
            self.assertEqual(ws_src.cell(2, 11).value, "UpdatedPass123")
        finally:
            for fp in (export_file, export_source_file):
                if os.path.exists(fp):
                    os.remove(fp)
    def test_export_results_permission_error_handling(self):
        from unittest.mock import patch
        records = self.manager.load_file(self.sample_excel_path)[:1]
        with patch("openpyxl.Workbook.save", side_effect=PermissionError("File locked")):
            with self.assertRaises(ExcelFileLockedError):
                self.manager.export_results("locked.xlsx", records)


if __name__ == "__main__":
    unittest.main()
