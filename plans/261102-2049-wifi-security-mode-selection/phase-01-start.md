---
phase: 1
title: "Phase 1: Desktop Windows Wi-Fi Security Mode Selection & Mock Server"
status: pending
priority: P1
effort: "1h"
dependencies: []
---

# Phase 1: Desktop Windows Wi-Fi Security Mode Selection & Mock Server

## Overview
Bổ sung tùy chọn loại bảo mật Wi-Fi (`wifi_security_mode`) vào tầng dữ liệu cấu hình (`GlobalConfig`), tầng giao thức LuCI CGI (`ONTDriver.configure_wifi` & `execute_full_setup`), giao diện Desktop Windows (`MainWindow` `QComboBox`), bộ lọc log (`app/ui/worker.py`), và máy chủ giả lập `MockLuCIServer` kèm bộ kiểm thử tự động `pytest`.

## Requirements
- **Functional:**
  - Định nghĩa danh sách chuẩn 6 chế độ bảo mật Wi-Fi (`WIFI_SECURITY_MODES`) khớp với `wireless.html` (`seq: 176`) và `wireless.js` (`seq: 181`) trong `ont-040NSv2-setup.jsonl`:
    1. `wpa2_wpa3_psk`: `"WPA2-PSK / WPA3-PSK (Mặc định)"` (`wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=aes`)
    2. `wpa2_psk`: `"WPA2-PSK (Tương thích rộng / Mesh)"` (`wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=aes`)
    3. `wpa_wpa2_psk`: `"WPA-PSK / WPA2-PSK (Thiết bị cũ / Mesh)"` (`wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=tkip+aes`)
    4. `wpa3_psk`: `"WPA3-PSK"` (`wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=aes`)
    5. `wpa_psk`: `"WPA-PSK"` (`wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=tkip+aes`)
    6. `open`: `"Open (Không mật khẩu)"` (`wl_akm_psk=disabled`, `wl_akm_psk2=disabled`, `wl_crypto=aes`)
  - Thêm trường `wifi_security_mode: str = "wpa2_wpa3_psk"` vào `GlobalConfig` (`app/core/config_models.py`).
  - **[Red Team RT-04]** Trong `app/core/ont_driver.py`:
    - Cập nhật `sanitize_input_metacharacters(text)` chặn thêm các ký tự điều khiển ASCII (`ord(c) < 0x20 or ord(c) == 0x7F`) để ngăn chặn việc chèn ký tự phân tách `\x7f` vào chuỗi truy vấn LuCI CGI.
    - Thêm hàm `url_encode_form_value(val: str) -> str` sử dụng `urllib.parse.quote(val, safe="-._~")` (tương đương `OntInputValidator.urlEncodeFormValue` trên Android/iOS) để mã hóa an toàn `+`, `=`, `%`, khoảng trắng (`%20`) trong `wl_ssid`, `wl_wpa_psk`, `sec_mode`, và `sessionKey`.
    - Trong `app/ui/worker.py:20-30`, bổ sung `\x7f` vào tất cả các biểu thức chính quy che mật khẩu (`([^&\s\x7f]+)`) để đồng bộ với dòng `data=`.
  - **[Red Team RT-01, RT-02, RT-05]** Cập nhật `ONTDriver.configure_wifi()` và `ONTDriver.execute_full_setup()` (`app/core/ont_driver.py`):
    - Thêm hàm `validate_wifi_ssid_and_pass(ssid: str, password: str, sec_mode: str, band_label: str)` kiểm tra:
      - `sec_mode` thuộc `WIFI_SECURITY_MODES`.
      - `ssid`: dài `1..32` ký tự ASCII in được (`0x20..0x7E`), không chứa toàn dấu cách (`ssid.strip() != ""`), không chứa `UNSAFE_SHELL_METACHARS`.
      - `password`: không chứa `UNSAFE_SHELL_METACHARS` / ký tự điều khiển; nếu `sec_mode != "open"`, bắt buộc dài `8..63` ký tự ASCII in được (`0x20..0x7E`) và không chứa toàn dấu cách (`password.strip() != ""`).
    - Thực thi kiểm tra pre-flight cho cả băng tần 2.4GHz và 5GHz **ngay ở đầu hàm `execute_full_setup()` trước Bước 1 (`probe_gateway`)** để tuyệt đối không làm thay đổi mật khẩu Admin/WAN/SLID của ONT rồi mới dừng ở Bước 5 khi gặp mật khẩu 7 ký tự (`[Red Team RT-02]`).
    - Khi `sec_mode == "open"`, không gán fallback `"1234567890"` nếu `device_cfg.pass_24g` / `pass_5g` trống (`wifi24_pass = ""` và `wifi5_pass = ""` khi `sec_mode == "open"` `[Red Team RT-05]`).
    - Kiểm tra phản hồi JSON từ `wirelesscfg.json` (`[Red Team RT-01]`):
      - Nếu `status == "conflict_mesh"`, ném `ONTDriverError("ONT đang bật Mesh nên không cho phép đổi chế độ bảo mật Wi-Fi sang Open (chỉ hỗ trợ WPA2-PSK hoặc WPA-PSK/WPA2-PSK).")`.
      - Nếu `status` có mặt và `status != "OK"`, ném `ONTDriverError(f"Cài đặt WiFi unit={unit} bị từ chối (status={status})")`.
  - Thêm `QComboBox` (`self.cmb_wifi_sec_mode`) vào khung `"Cài Đặt Tham Số Chung ONT"` trên `MainWindow` (`app/ui/main_window.py`), khóa khi tiến trình đang chạy, kiểm tra hợp lệ mật khẩu Wi-Fi theo `cmb_wifi_sec_mode` trong `run_current_device()` / `_maybe_auto_run_next_device()`, và gán vào `self.global_cfg.wifi_security_mode`.
  - **[Red Team RT-07]** Cập nhật `MockLuCIServer` (`tests/mock_luci_server.py`) dùng `urllib.parse.parse_qs(normalized_body, keep_blank_values=True)`, lưu `sec_mode`, `wl_akm_psk`, `wl_akm_psk2`, `wl_crypto` trong `server.wifi_configs[unit]`, hỗ trợ `simulate_conflict_mesh: bool = False`, và reset trong `tests/test_e2e_workflow.py:setUp()`.

## Architecture
- **Bảng ánh xạ tham số bảo mật (`WIFI_SECURITY_MODES` trong `app/core/ont_driver.py`):**
  ```python
  WIFI_SECURITY_MODES: Dict[str, Dict[str, str]] = {
      "wpa2_wpa3_psk": {
          "label": "WPA2-PSK / WPA3-PSK (Mặc định)",
          "wl_akm_psk": "enabled",
          "wl_akm_psk2": "enabled",
          "wl_crypto": "aes",
      },
      "wpa2_psk": {
          "label": "WPA2-PSK (Tương thích rộng / Mesh)",
          "wl_akm_psk": "enabled",
          "wl_akm_psk2": "enabled",
          "wl_crypto": "aes",
      },
      "wpa_wpa2_psk": {
          "label": "WPA-PSK / WPA2-PSK (Thiết bị cũ / Mesh)",
          "wl_akm_psk": "enabled",
          "wl_akm_psk2": "enabled",
          "wl_crypto": "tkip+aes",
      },
      "wpa3_psk": {
          "label": "WPA3-PSK",
          "wl_akm_psk": "enabled",
          "wl_akm_psk2": "enabled",
          "wl_crypto": "aes",
      },
      "wpa_psk": {
          "label": "WPA-PSK",
          "wl_akm_psk": "enabled",
          "wl_akm_psk2": "enabled",
          "wl_crypto": "tkip+aes",
      },
      "open": {
          "label": "Open (Không mật khẩu)",
          "wl_akm_psk": "disabled",
          "wl_akm_psk2": "disabled",
          "wl_crypto": "aes",
      },
  }
  ```

## Related Code Files
- Modify: `app/core/config_models.py`
- Modify: `app/core/ont_driver.py`
- Modify: `app/ui/main_window.py`
- Modify: `app/ui/worker.py`
- Modify: `tests/mock_luci_server.py`
- Modify: `tests/test_ont_driver_mock.py`
- Modify: `tests/test_ui_components.py`
- Modify: `tests/test_e2e_workflow.py`

## Implementation Steps
1. **`app/core/config_models.py`**:
   - Bổ sung trường `wifi_security_mode: str = "wpa2_wpa3_psk"` vào `GlobalConfig`.
2. **`app/core/ont_driver.py` & `app/ui/worker.py`**:
   - Cập nhật `sanitize_input_metacharacters(text)` kiểm tra thêm `ord(char) < 0x20 or ord(char) == 0x7F` (`[Red Team RT-04]`).
   - Thêm `url_encode_form_value(text: str) -> str` dùng `urllib.parse.quote(text, safe="-._~")`.
   - Định nghĩa `WIFI_SECURITY_MODES`, `resolve_wifi_security_params(sec_mode: str)` và `validate_wifi_ssid_and_pass(ssid: str, password: str, sec_mode: str, band_label: str = "Wi-Fi")`.
   - Trong `configure_wifi(self, unit: int, ssid: str, password: str, pause_settling: bool = True, sec_mode: str = "wpa2_wpa3_psk")`:
     - Gọi `params = resolve_wifi_security_params(sec_mode)` và `validate_wifi_ssid_and_pass(ssid, password, sec_mode, f"unit={unit}")`.
     - Mã hóa `enc_ssid = url_encode_form_value(ssid)`, `enc_pass = url_encode_form_value(password)`, `enc_sec = url_encode_form_value(sec_mode)`, `enc_key = url_encode_form_value(self.session_key)`.
     - Gửi `wirelesscfg.json` và kiểm tra phản hồi JSON: nếu `status == "conflict_mesh"` ném `ONTDriverError` báo lỗi xung đột Mesh; nếu `status` khác rỗng và `!= "OK"` ném `ONTDriverError` (`[Red Team RT-01]`).
   - Trong `execute_full_setup()`:
     - Tính `sec_mode = (global_cfg.wifi_security_mode or "wpa2_wpa3_psk").strip()`, `wifi24_ssid`, `wifi24_pass`, `wifi5_ssid`, `wifi5_pass` (nếu `sec_mode == "open"`, dùng `""` khi mật khẩu trống thay vì `"1234567890"` `[Red Team RT-05]`) và gọi `validate_wifi_ssid_and_pass` cho cả 2.4GHz và 5GHz **ngay trước Bước 1 (`probe_gateway`)** (`[Red Team RT-02]`).
     - Truyền `sec_mode=sec_mode` vào Bước 5 (`unit=0`) và Bước 6 (`unit=1`), trả thêm `"wifi_security_mode": sec_mode` trong dict kết quả.
   - Trong `app/ui/worker.py:20-30`: cập nhật tất cả các mẫu regex trong `mask_sensitive_credentials` dùng `([^&\s\x7f]+)` (`[Red Team RT-04]`).
3. **`app/ui/main_window.py`**:
   - Import `QComboBox` từ `PyQt6.QtWidgets` và `WIFI_SECURITY_MODES`, `validate_wifi_ssid_and_pass` từ `app.core.ont_driver`.
   - Trong `_build_ui()`, thêm `self.cmb_wifi_sec_mode = QComboBox()` vào `form_layout` của `grp_settings` ngay dưới `"WiFi 5G Pass mặc định:"` với nhãn `"Chuẩn bảo mật Wi-Fi:"`.
   - Nạp 6 mục `(info["label"], mode_key)` vào `self.cmb_wifi_sec_mode`, mặc định chọn `self.global_cfg.wifi_security_mode`, kết nối `currentIndexChanged` tới `self._on_wifi_sec_mode_changed`.
   - Trong `keyPressEvent()`, bỏ qua phím `Space`/`Enter` khi `isinstance(focused, (QLineEdit, QSpinBox, QPlainTextEdit, QComboBox))`.
   - Trong `_update_run_button_state()`, vô hiệu hóa `self.cmb_wifi_sec_mode.setEnabled(False)` khi `active_worker` đang chạy và bật lại khi hoàn tất.
   - Trong `run_current_device()` và `_maybe_auto_run_next_device()`, lấy `sec_mode = str(self.cmb_wifi_sec_mode.currentData() or "wpa2_wpa3_psk")` và kiểm tra hợp lệ mật khẩu Wi-Fi trước khi khởi chạy worker (`[Red Team RT-02]`).
4. **`tests/mock_luci_server.py`, `tests/test_ont_driver_mock.py`, `tests/test_ui_components.py` & `tests/test_e2e_workflow.py`**:
   - Trong `tests/mock_luci_server.py:77`: đổi sang `urllib.parse.parse_qs(normalized_body, keep_blank_values=True)`, thêm `self.simulate_conflict_mesh: bool = False`, trả về `{"status": "conflict_mesh"}` khi bật cờ này, và lưu `sec_mode`, `wl_akm_psk`, `wl_akm_psk2`, `wl_crypto` vào `server.wifi_configs[unit]` (`[Red Team RT-07]`).
   - Trong `tests/test_e2e_workflow.py:setUp()`: reset `self.server.simulate_conflict_mesh = False` và `self.server.wifi_configs.clear()`.
   - Bổ sung unit test và UI test kiểm tra đầy đủ 6 chế độ bảo mật, kịch bản `conflict_mesh`, và kiểm tra pre-flight mật khẩu ngắn.

## Todo
- [ ] Thêm `wifi_security_mode` vào `GlobalConfig` trong `app/core/config_models.py`
- [ ] Cập nhật `WIFI_SECURITY_MODES`, `resolve_wifi_security_params`, `validate_wifi_ssid_and_pass`, `configure_wifi` và `execute_full_setup` trong `app/core/ont_driver.py` + `app/ui/worker.py`
- [ ] Thêm `QComboBox` `cmb_wifi_sec_mode` và kiểm tra pre-flight vào `MainWindow` (`app/ui/main_window.py`)
- [ ] Cập nhật `tests/mock_luci_server.py`, `tests/test_ont_driver_mock.py`, `tests/test_ui_components.py` và `tests/test_e2e_workflow.py`

## Success Criteria
- [ ] `pytest tests/test_ont_driver_mock.py tests/test_ui_components.py tests/test_e2e_workflow.py` chạy PASS 100%.
- [ ] `MockLuCIServer` ghi nhận đúng `sec_mode`, `wl_akm_psk`, `wl_akm_psk2`, `wl_crypto` cho cả 6 chế độ bảo mật và xử lý đúng `conflict_mesh` cũng như mật khẩu rỗng ở chế độ `open`.

## Risk Assessment
- **Risk:** KTV chạy dòng dữ liệu có mật khẩu Wi-Fi 7 ký tự ở chế độ PSK khiến ONT bị đổi mật khẩu Admin/WAN/SLID ở Bước 2–4 rồi mới báo lỗi ở Bước 5.
  - **Signal:** `ONTValidationError` xảy ra sau khi `login.cmd` / `accesscontrolcfg.json` đã được gọi.
  - **Mitigation:** Kiểm tra `validate_wifi_ssid_and_pass` cho cả 2 băng tần ngay ở đầu `execute_full_setup()` trước Bước 1 (`[Red Team RT-02]`).
