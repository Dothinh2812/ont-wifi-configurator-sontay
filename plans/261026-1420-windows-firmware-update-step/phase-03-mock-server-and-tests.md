---
phase: 3
title: "Phase 3: Mock LuCI Server & Automated Tests"
status: completed
priority: P1
effort: "1h"
dependencies: [1, 2]
---

# Phase 3: Mock LuCI Server & Automated Tests

## Overview
Mở rộng `MockLuCIServer` (`tests/mock_luci_server.py`) để mô phỏng chính xác 3 endpoint nâng cấp firmware (`updatefwsessioncheck.json`, `imagesize.json`, `uploadsoftware.conf`) và bổ sung các bài kiểm thử tự động trong `tests/test_ont_driver_mock.py`, `tests/test_ui_components.py`, `tests/test_e2e_workflow.py` cũng như cập nhật tài liệu hướng dẫn (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`).

## Requirements
- **Functional:**
  - Trong `tests/mock_luci_server.py` (`[Red Team RT-03]`):
    - Bổ sung thuộc tính trạng thái trên `MockLuCIServer`: `ram_free_kb: int = 251696`, `firmware_updated: bool = False`, `uploaded_firmware_bytes: int = 0`, `uploaded_firmware_name: str = ""`, `reject_firmware: bool = False`.
    - Refactor đầu hàm `MockLuCIHandler.do_POST`: đọc `raw_bytes = self.rfile.read(length)` dưới dạng `bytes` trước.
    - Nếu `Content-Type` chứa `multipart/form-data`: dùng `email.parser.BytesParser` (stdlib Python 3.13-compatible) để tách các MIME parts từ `raw_bytes`, lấy chính xác kích thước byte nhị phân của part `file` (`uploaded_firmware_bytes`), `filename` (`uploaded_firmware_name`), và giá trị text của part `sessionKey` mà không bị hỏng bởi `.decode("utf-8")` hay `replace("\x7f", "&")`.
    - Xử lý `POST /cgi-bin/luci/updatefwsessioncheck.json`: kiểm tra `sessionKey == self.server.active_session`, trả về `{"status": "OK"}`.
    - Xử lý `POST /cgi-bin/luci/imagesize.json`: kiểm tra `sessionKey == self.server.active_session`, trả về `{"ramFree": str(self.server.ram_free_kb), "sessionKey": self.server.active_session}`.
    - Xử lý `POST /cgi-bin/luci/uploadsoftware.conf`: lưu `cur_session = self.server.active_session`; kiểm tra `Content-Type` có `multipart/form-data` và `sessionKey == cur_session`; nếu `self.server.reject_firmware` là `True` thì trả về `{"status": "invalid", "sessionKey": cur_session}` (giữ nguyên `active_session` để test xác nhận driver gọi `logout.cmd` khi lỗi), ngược lại đặt `self.server.firmware_updated = True`, xóa `self.server.active_session = ""` (mô phỏng ONT tự động reboot) và trả về `{"status": "valid", "sessionKey": cur_session}`.
  - Trong `tests/test_ont_driver_mock.py`:
    - Kiểm thử `ONTDriver.update_firmware()` thành công (kiểm tra đủ 3 endpoint, `headers["Content-Type"] is None` ở boundary `Session.post`, chuẩn hóa tên file `safe_filename`, xóa `self.session_key == ""` ngay khi `status == "valid"`).
    - Kiểm thử `ONTDriver.update_firmware()` báo lỗi khi file không tồn tại, khi `updatefwsessioncheck.json` trả `status != "OK"`, khi thiếu RAM (`ramFree == "0"` hoặc `file_size_kb > ramFree`), và khi ONT trả về `status: "invalid"` (cập nhật `sessionKey` xoay vòng trước khi raise lỗi).
    - Kiểm thử `ONTDriver.execute_full_setup()` với `enable_firmware_update=True` chạy đủ 11 bước, không gọi `logout.cmd` sau khi `update_firmware` thành công (kể cả khi `progress_cb` nhận tín hiệu hủy ở bước 10 `SUCCESS`), và gọi `logout.cmd` kèm thông báo tiền tố `"Đã đổ xong cấu hình (Bước 1-9)..."` nếu Bước 10 thất bại.
  - Trong `tests/test_ui_components.py`:
    - Kiểm thử các control UI mới trên `MainWindow` (`chk_enable_fw_update`, `txt_fw_path`, `btn_browse_fw`, `lbl_fw_status`), luôn gán tường minh `self.win.txt_fw_path.setText(...)` trong test để không phụ thuộc môi trường local/CI (`[Red Team RT-06]`), kiểm tra khóa nút `RUN` khi bật checkbox mà đường dẫn file không hợp lệ, và đồng bộ bản sao `GlobalConfig` khi chạy.
    - Kiểm thử cửa sổ bảo vệ `_awaiting_fw_reboot_disconnect` và `NetworkProbe.is_same_device_as_last_reset` (bao gồm cả `reset()` xóa `_current_mac = None` và chặn tự đóng `_completion_msg_box` khi cùng MAC vừa reboot xong `[Red Team RT-02]`).
    - Kiểm thử `mask_sensitive_credentials()` che cả `"sessionKey": "<token>"` dạng JSON (`[Red Team RT-05]`).
  - Trong `tests/test_e2e_workflow.py`:
    - Bổ sung test E2E trọn vẹn 11/11 bước (đổ cấu hình + upload file firmware nhị phân có chứa các byte `0x7f`, `0x80..0xff` lên `MockLuCIServer`), xác nhận `self.server.firmware_updated == True`, `uploaded_firmware_bytes` khớp 100%, `steps_recorded == set(range(1, 12))`, và `/cgi-bin/luci/logout.cmd` không xuất hiện trong `self.server.request_history`.
    - Bổ sung test E2E khi `reject_firmware = True`: xác nhận báo lỗi Bước 10 và `logout.cmd` vẫn được gọi để giải phóng `self.server.active_session == ""`.
  - Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` mô tả tính năng tùy chọn Update Firmware ở bước cuối cùng.

## Related Code Files
- Modify: `tests/mock_luci_server.py`
- Modify: `tests/test_ont_driver_mock.py`
- Modify: `tests/test_ui_components.py`
- Modify: `tests/test_e2e_workflow.py`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Cập nhật `MockLuCIServer` và `MockLuCIHandler.do_POST` trong `tests/mock_luci_server.py` với bộ đọc `raw_bytes` và parser `multipart/form-data` bằng `email.parser.BytesParser`.
2. Viết unit tests cho `update_firmware` và `execute_full_setup(enable_firmware_update=True)` trong `tests/test_ont_driver_mock.py`.
3. Viết unit tests cho UI checkbox/path chọn firmware, `_awaiting_fw_reboot_disconnect`, `is_same_device_as_last_reset`, và JSON `sessionKey` masking trong `tests/test_ui_components.py`.
4. Viết E2E tests 11 bước (thành công và lỗi firmware bị từ chối) trong `tests/test_e2e_workflow.py`.
5. Chạy toàn bộ test suite (`python3 -m unittest discover -s tests`) để xác nhận 100% pass, sau đó cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Todo
- [x] Bổ sung mô phỏng 3 API firmware update (hỗ trợ binary multipart) vào `tests/mock_luci_server.py`
- [x] Thêm unit tests cho `ONTDriver.update_firmware()` trong `tests/test_ont_driver_mock.py`
- [x] Thêm unit tests cho UI Firmware Update, `_awaiting_fw_reboot_disconnect`, `NetworkProbe` và JSON `sessionKey` masking trong `tests/test_ui_components.py`
- [x] Thêm E2E tests 11 bước trong `tests/test_e2e_workflow.py`
- [x] Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Success Criteria
- [x] `ak plan validate ./plans/261026-1420-windows-firmware-update-step` trả về hợp lệ (exit 0).
- [x] Toàn bộ test suite (`python3 -m unittest discover -s tests`) chạy xanh 100%.

## Risk Assessment
- **Rủi ro (Tạo file tạm trong quá trình chạy test):** Test upload firmware cần file thật trên đĩa.
  - *Giải pháp:* Sử dụng `tempfile.NamedTemporaryFile` chứa dữ liệu nhị phân (`b"\xff\x7f\x00..."`) trong unit/E2E test và xóa sạch trong `finally` / `addCleanup`.
