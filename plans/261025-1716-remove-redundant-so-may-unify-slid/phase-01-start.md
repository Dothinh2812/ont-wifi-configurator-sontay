---
phase: 1
title: "Phase 1: Start"
status: completed
priority: P1
effort: "1.5h"
dependencies: []
---

# Phase 1: Bản Desktop Windows (`app/` & `tests/`) — Bỏ Tham Số `Số máy`, Hợp Nhất Về `SLID`

## Overview
Loại bỏ cột `"Số máy"` và các bước kiểm tra/hiển thị `"Số máy"` trùng lặp trên ứng dụng Desktop Windows (PyQt6), hợp nhất toàn bộ luồng định danh thiết bị, chỉnh sửa trực tiếp trên bảng, tạo SSID mặc định (`VNPT_{slid}`), lưu checkpoint và xuất báo cáo Excel về tham số duy nhất là **`SLID`**.

## Requirements
- **Functional:**
  - Bảng danh sách thiết bị (`DeviceTableModel` trong `app/ui/device_table_model.py`) bỏ cột `("Số máy", "so_may")`, giảm từ **11 cột xuống 10 cột**:
    - `0`: `("STT", "stt")`
    - `1`: `("SLID", "slid")` *(cho phép chỉnh sửa inline)*
    - `2`: `("Account PPPoE", "account_pppoe")`
    - `3`: `("Mật khẩu PPPoE", "pass_pppoe")` *(cho phép chỉnh sửa inline)*
    - `4`: `("SSID 2.4G", "ssid_24g")` *(cho phép chỉnh sửa inline)*
    - `5`: `("Mật khẩu 2.4G", "pass_24g")` *(cho phép chỉnh sửa inline)*
    - `6`: `("SSID 5G", "ssid_5g")` *(cho phép chỉnh sửa inline)*
    - `7`: `("Mật khẩu 5G", "pass_5g")` *(cho phép chỉnh sửa inline)*
    - `8`: `("Trạng thái", "status")`
    - `9`: `("Ghi chú / Chi tiết", "error_message")`
  - Khi KTV sửa ô `SLID` (cột `1`), tự động đồng bộ `rec.so_may = val_str` và chạy lại `validate_device_record(rec)` tức thì.
  - **[Red Team RT-04]** Bỏ kiểm tra lỗi `"Thiếu Số máy định danh"` và `("Số máy", ...)` khỏi `app/data/excel_manager.py` và `app/ui/device_table_model.py`; chỉ kiểm tra duy nhất trường `SLID`, đồng thời sửa `UNSAFE_SHELL_METACHARS` trong `app/core/ont_driver.py:21` dùng raw string để giữ đủ **11 ký tự** (bao gồm `\`) và bổ sung che `data=` (GPON SLID payload) trong `mask_sensitive_credentials` (`app/ui/worker.py:20-28`).
  - **[Red Team RT-01, RT-02, RT-03]** Trong `app/core/ont_driver.py`, `app/core/config_models.py`, `app/data/models.py`, `app/ui/worker.py`, `app/ui/main_window.py`: sử dụng `slid` làm định danh chuẩn (fallback `wifi24_ssid = device_cfg.ssid_24g or f"VNPT_{device_cfg.slid}"`, log tiến trình, hộp thoại hoàn tất/báo lỗi, checkpoint key kèm fallback theo `raw_row_data["SO_MAY"]` và `row_index`), gán giá trị mặc định `""` cho toàn bộ trường chuỗi bắt buộc trong `DeviceConfig` để tránh `TypeError` của `@dataclass`, và khi `export_results` chạy với `source_excel_path` thì ghi đè `rec.slid` vào cả cột `SO_MAY` (Cột 9) và `Mã MEN` (Cột 13) cùng các trường KTV đã sửa inline.
- **Non-functional:**
  - Giữ nguyên tốc độ render bảng PyQt6 (Color Caching `_STATUS_BRUSH_CACHE`) và tương thích 100% với file Excel 30 cột gốc (`file-mau.xlsx`).

## Architecture
```
[file-mau.xlsx] ──(Cột 13 Mã MEN / Cột 9 SO_MAY)──► ExcelManager.load_file()
                                                       │  slid = ma_men or so_may
                                                       │  so_may = slid (đồng bộ 100%)
                                                       ▼
                                              DeviceRecord(slid=...)
                                                       │
                       ┌───────────────────────────────┴───────────────────────────────┐
                       ▼                                                               ▼
        DeviceTableModel (10 cột)                                          ONTDriver.execute_full_setup()
  - Col 0: STT (căn giữa)                                            - Step 4: set_gpon_password(slid)
  - Col 1: SLID (editable, đồng bộ so_may)                           - Step 5: fallback SSID "VNPT_{slid}"
  - Col 3..7: Pass PPPoE & Wi-Fi (editable)                          - Return: {"slid": slid, "so_may": slid}
  - Col 8: Trạng thái (căn giữa)
```

## Related Code Files
- Modify: `app/core/config_models.py`
- Modify: `app/core/ont_driver.py`
- Modify: `app/data/models.py`
- Modify: `app/data/excel_manager.py`
- Modify: `app/ui/device_table_model.py`
- Modify: `app/ui/main_window.py`
- Modify: `app/ui/worker.py`
- Modify: `tests/test_ui_components.py`
- Modify: `tests/test_excel_manager.py`
- Modify: `tests/test_ont_driver_mock.py`
- Modify: `tests/test_e2e_workflow.py`
- Modify: `tests/benchmark_perf.py`

## Implementation Steps
1. **[Red Team RT-03 & RT-04] Hợp nhất `slid` và `so_may` trong Model & Driver (`app/core/config_models.py`, `app/data/models.py`, `app/core/ont_driver.py`):**
   - Trong `DeviceConfig` (`app/core/config_models.py:29-35`): đặt giá trị mặc định `""` cho `so_may: str = ""`, `slid: str = ""`, `account_pppoe: str = ""`, `pass_pppoe: str = ""` (tránh lỗi `TypeError: non-default argument follows default argument` của Python `@dataclass`) và thêm `__post_init__` đồng bộ `effective = (self.slid or self.so_may).strip(); self.slid = effective; self.so_may = effective`. Áp dụng tương tự trong `DeviceRecord.__post_init__` (`app/data/models.py`).
   - Trong `app/core/ont_driver.py:21`: sửa `UNSAFE_SHELL_METACHARS` để chứa đủ 11 ký tự bao gồm cả dấu gạch chéo ngược `\` (`{'&', '|', ';', '$', '>', '<', '`', '\\', '!', "'", '"'}`).
   - Trong `ONTDriver.execute_full_setup` (`app/core/ont_driver.py:414`): đổi fallback `wifi24_ssid = device_cfg.ssid_24g or f"VNPT_{device_cfg.slid}"` và trả về cả `"slid": device_cfg.slid` lẫn `"so_may": device_cfg.slid`.
2. **[Red Team RT-01, RT-02, RT-07] Cập nhật bộ đọc/ghi Excel (`app/data/excel_manager.py`):**
   - Trong `load_file`: đọc `ma_men` (Cột 13) và `so_may` (Cột 9), tính `slid = ma_men if ma_men else so_may` và gán `so_may = slid` **trước** bước kiểm tra bỏ qua dòng trống (`if stt_val is None and not slid and account_val is None: continue`).
   - Xóa khối kiểm tra `if not so_may: errors.append("Thiếu Số máy định danh")` và xóa `("Số máy", so_may)` khỏi `fields_to_check`.
   - Trong `save_checkpoint`: dùng `r.slid or str(r.row_index)` làm khóa lưu trữ.
   - Trong `export_results` (`app/data/excel_manager.py:249-303`): cả khi tạo workbook mới lẫn khi mở từ `source_excel_path`, xác định vị trí cột qua `self._locate_headers(ws)`, khớp dòng ưu tiên theo `row_index` (hoặc fallback theo `rec.slid` và `rec.raw_row_data.get("SO_MAY")`), và ghi đè `rec.slid` vào cả hai cột `"SO_MAY"` (Cột 9) và `"Mã MEN"` (Cột 13) cùng các trường đã sửa inline (`Mật khẩu PPPoE`, `SSID/Mật khẩu Wi-Fi 2.4G & 5G`).
3. **[Red Team RT-02 & RT-04] Loại bỏ cột `Số máy` khỏi bảng giao diện Windows (`app/ui/device_table_model.py`, `app/ui/main_window.py`, `app/ui/worker.py`):**
   - Trong `app/ui/device_table_model.py`:
     - Xóa `("Số máy", "so_may")` khỏi `COLUMNS` (còn 10 cột).
     - Trong `validate_device_record(rec)`: xóa kiểm tra `if not rec.so_may` và `("Số máy", rec.so_may)`.
     - Trong `flags()` và `setData()`: cập nhật `editable_cols = {1, 3, 4, 5, 6, 7}` (cột `1` là `SLID`, cột `3` là `Mật khẩu PPPoE`, cột `4..7` là Wi-Fi 2.4G/5G). Khi `attr_name == "slid"`, cập nhật đồng thời `rec.so_may = val_str`.
     - Trong `data()` (`TextAlignmentRole`): căn giữa cho `col in (0, 8)` (`STT` và `Trạng thái`).
   - Trong `app/ui/main_window.py`:
     - Cập nhật `_configure_table_columns()` cho 10 cột (`0: STT`, `1: SLID`, `2: Account PPPoE`, `3: Mật khẩu PPPoE`, `4: SSID 2.4G`, `5: Mật khẩu 2.4G`, `6: SSID 5G`, `7: Mật khẩu 5G`, `8: Trạng thái`).
     - Khi khôi phục checkpoint trong `load_excel`: khớp theo `r.slid`, `str(r.raw_row_data.get("SO_MAY") or "").strip()`, hoặc `str(r.row_index)`, đồng thời khôi phục cả `slid` (`r.so_may = r.slid`), `pass_pppoe`, và thông số Wi-Fi nếu KTV đã sửa trước đó.
     - Dùng `rec.slid` khi ghi log tự động chạy thiết bị tiếp theo và hiển thị hộp thoại hoàn tất/báo lỗi.
   - Trong `app/ui/worker.py`: bổ sung `(r"(data=)([^&\s\x7f]+)", r"\1***")` vào `SENSITIVE_PATTERNS` và hiển thị `self.device_cfg.slid` trong log tiến trình và kết quả lỗi (đã qua `mask_sensitive_credentials`).
4. **Cập nhật bộ kiểm thử tự động Desktop (`tests/`):**
   - Cập nhật chỉ số cột trong `tests/test_ui_components.py` (ví dụ: cột `SLID` là index `1`, cột `Mật khẩu PPPoE` là index `3`, tổng số cột là `10`).
   - Kiểm tra toàn bộ test trong `tests/test_excel_manager.py`, `tests/test_ont_driver_mock.py`, `tests/test_e2e_workflow.py`, `tests/benchmark_perf.py`.

## Todo
- [x] Hợp nhất `slid` / `so_may` trong `app/core/config_models.py`, `app/data/models.py`, và `app/core/ont_driver.py`
- [x] Bỏ kiểm tra lỗi `Số máy` trùng lặp và đồng bộ `slid` trong `app/data/excel_manager.py`
- [x] Bỏ cột `Số máy` khỏi `app/ui/device_table_model.py` (còn 10 cột) và cập nhật `app/ui/main_window.py`, `app/ui/worker.py`
- [x] Cập nhật và chạy pass bộ kiểm thử `tests/test_*.py`

## Success Criteria
- [x] `DeviceTableModel.columnCount()` trả về `10`, cột `1` là `"SLID"` và cho phép chỉnh sửa trực tiếp.
- [x] Chỉnh sửa cột `SLID` (cột `1`) hoặc `Mật khẩu PPPoE` (cột `3`) trên bảng tự động tái kiểm tra hợp lệ và đổi trạng thái `PENDING` / `INVALID_DATA` chính xác.
- [x] Chạy `pytest tests/test_excel_manager.py tests/test_ont_driver_mock.py tests/test_ui_components.py tests/test_e2e_workflow.py` đạt PASS 100%.

## Risk Assessment
- **Rủi ro lệch chỉ số cột trong Unit Test hoặc UI (`editable_cols`, `TextAlignmentRole`, `_configure_table_columns`):**
  - *Tín hiệu nhận biết:* Test `test_inline_editing_slid_and_wifi_reactively_validates` hoặc `test_missing_pppoe_row_blocks_run_until_edited_inline` thất bại do sửa nhầm cột không cho phép edit.
  - *Đối sách định sẵn:* Kiểm tra đối chiếu bảng ánh xạ chỉ số cột mới (`0..9`) tại mọi vị trí dùng chỉ số cột trong `device_table_model.py`, `main_window.py` và `test_ui_components.py`.
