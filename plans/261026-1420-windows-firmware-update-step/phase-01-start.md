---
phase: 1
title: "Phase 1: Core Driver & Config Models Firmware Update"
status: completed
priority: P1
effort: "1h"
dependencies: []
---

# Phase 1: Core Driver & Config Models Firmware Update

## Overview
Mở rộng `GlobalConfig` trong `app/core/config_models.py` và bổ sung phương thức `update_firmware()` cùng luồng bước cuối vào `ONTDriver` trong `app/core/ont_driver.py` theo đúng giao thức LuCI CGI của iGate GW040-NSv2 trích xuất từ `update_firmware_040nsv2.jsonl`.

## Requirements
- **Functional:**
  - Thêm cấu hình `enable_firmware_update: bool = False`, `firmware_path: str = ""`, và `firmware_upload_timeout: float = 120.0` vào `GlobalConfig`.
  - Cài đặt `ONTDriver.update_firmware(self, firmware_path: str, upload_timeout: float = 120.0) -> Dict[str, Any]`:
    1. Kiểm tra sự tồn tại và kích thước `> 0` của `firmware_path` (nếu lỗi raise `ONTValidationError`). Chuẩn hóa tên file `safe_filename = re.sub(r"[^a-zA-Z0-9_.\-]", "_", os.path.basename(fw_path))` để loại bỏ hoàn toàn các ký tự shell metacharacter trong header `Content-Disposition` (`[Red Team RT-05]`).
    2. Gọi `POST /cgi-bin/luci/updatefwsessioncheck.json` với `sessionKey={self.session_key}` và header `Referer: {self.base_url}/update_software.html`. Bọc `requests.RequestException` -> `ONTNetworkError`, parse JSON và xác nhận `chk_data.get("status") == "OK"` (`[Red Team RT-04]`).
    3. Gọi `POST /cgi-bin/luci/imagesize.json` với `sessionKey={self.session_key}`, cập nhật `self.session_key` nếu response có `sessionKey` mới, đọc `ramFree` (KB) và bắt buộc `ramFree` là số nguyên dương và `file_size / 1024.0 <= float(ramFree)` (kể cả khi `ramFree == 0` hoặc thiếu đều raise `ONTDriverError` `[Red Team RT-04]`).
    4. Gọi `POST /cgi-bin/luci/uploadsoftware.conf` dạng `multipart/form-data` với thứ tự field: `("file", (safe_filename, fw_file, "application/octet-stream"))` và `("sessionKey", (None, self.session_key))`.
    5. Cập nhật `self.session_key = up_data["sessionKey"]` nếu response trả về `sessionKey` xoay vòng (`[Red Team RT-04]`). Xác nhận `up_data.get("status") == "valid"`:
       - Nếu `status == "valid"`: xóa ngay `self.session_key = ""` **bên trong** `update_firmware()` trước khi return (`[Red Team RT-01]`) để đảm bảo khối `finally: self.logout()` không bao giờ gửi `logout.cmd` vào ONT đang bắt đầu ghi flash/reboot (kể cả khi người dùng bấm Cancel đúng lúc request vừa xong).
       - Nếu `status != "valid"`: raise `ONTDriverError` (lúc này `self.session_key` vẫn còn giữ token mới nhất để `finally: self.logout()` giải phóng phiên an toàn, tránh lỗi `user exist`).
  - Tích hợp vào `ONTDriver.execute_full_setup()`:
    - Đọc snapshot `do_fw_update = bool(global_cfg.enable_firmware_update)` và `fw_path = (global_cfg.firmware_path or "").strip()` một lần duy nhất ở đầu hàm (`[Red Team RT-06]`).
    - Nếu `do_fw_update` là `False`: giữ nguyên `total_steps = 10` và gọi `self.logout()` ở `finally`.
    - Nếu `do_fw_update` là `True`: kiểm tra sớm `fw_path` tồn tại và `getsize > 0` trước khi chạy Bước 1; đặt `total_steps = 11`, chạy Bước 10 (`Nâng cấp Firmware (Update Software)`) và Bước 11 (`Hoàn tất & Tự động Khởi động lại ONT`). Nếu Bước 10 ném ngoại lệ (sau khi Bước 1–9 đã lưu cấu hình), tiền tố hóa thông báo lỗi bằng `"Đã đổ xong cấu hình (Bước 1-9), nhưng lỗi Nâng cấp Firmware (Bước 10): ..."` để KTV biết thiết bị đã mang cấu hình thuê bao và mật khẩu admin mới (`[Red Team RT-05]`).
    <!-- Updated: Validation Session 1 - Confirmed Step 10 error prefix and idempotent RETRY behavior -->
- **Non-functional:**
  - Xử lý đúng `Content-Type` của `requests.Session`: do `self.session.headers` mặc định đặt `"Content-Type": "application/x-www-form-urlencoded"`, khi gọi `self.session.post(..., files=...)` bắt buộc truyền `headers={"Content-Type": None, "Referer": f"{self.base_url}/update_software.html", "Accept": "application/json, text/javascript, */*; q=0.01"}` để `requests` tự sinh `multipart/form-data; boundary=...` chuẩn.

## Architecture

```python
# Trong app/core/config_models.py
@dataclass
class GlobalConfig:
    ...
    enable_firmware_update: bool = False
    firmware_path: str = ""
    firmware_upload_timeout: float = 120.0
```

```python
# Trong app/core/ont_driver.py
def update_firmware(self, firmware_path: str, upload_timeout: float = 120.0) -> Dict[str, Any]:
    """Upload firmware image via LuCI CGI and trigger automatic ONT flash & reboot."""
    fw_path = (firmware_path or "").strip()
    if not fw_path or not os.path.isfile(fw_path):
        raise ONTValidationError(f"File firmware không tồn tại: '{fw_path}'")
    file_size = os.path.getsize(fw_path)
    if file_size <= 0:
        raise ONTValidationError(f"File firmware rỗng (0 byte): '{fw_path}'")

    raw_name = os.path.basename(fw_path)
    safe_filename = re.sub(r"[^a-zA-Z0-9_.\-]", "_", raw_name) or "firmware.signed"
    referer_hdr = {"Referer": f"{self.base_url}/update_software.html"}

    # 1. Check firmware update session
    chk_url = f"{self.base_url}/cgi-bin/luci/updatefwsessioncheck.json"
    try:
        chk_resp = self.session.post(
            chk_url, data=f"sessionKey={self.session_key}", headers=referer_hdr, timeout=self.timeout
        )
    except requests.RequestException as e:
        raise ONTNetworkError(f"Lỗi mạng khi kiểm tra phiên nâng cấp firmware: {e}") from e
    if chk_resp.status_code != 200:
        raise ONTDriverError(f"Kiểm tra phiên nâng cấp firmware thất bại (HTTP {chk_resp.status_code})")
    try:
        chk_data = chk_resp.json() if chk_resp.text else {}
    except ValueError as e:
        raise ONTDriverError("Phản hồi updatefwsessioncheck.json không đúng định dạng JSON.") from e
    if chk_data.get("status") != "OK":
        raise ONTDriverError(f"Phiên nâng cấp firmware không hợp lệ (status={chk_data.get('status')}).")

    # 2. Query available RAM size (KB)
    img_url = f"{self.base_url}/cgi-bin/luci/imagesize.json"
    try:
        img_resp = self.session.post(
            img_url, data=f"sessionKey={self.session_key}", headers=referer_hdr, timeout=self.timeout
        )
    except requests.RequestException as e:
        raise ONTNetworkError(f"Lỗi mạng khi kiểm tra dung lượng RAM trống của ONT: {e}") from e
    if img_resp.status_code != 200:
        raise ONTDriverError(f"Không thể kiểm tra dung lượng RAM trống của ONT (HTTP {img_resp.status_code})")
    try:
        img_data = img_resp.json() if img_resp.text else {}
    except ValueError as e:
        raise ONTDriverError("Phản hồi imagesize.json không đúng định dạng JSON.") from e
    if img_data.get("sessionKey"):
        self.session_key = img_data["sessionKey"]
    ram_free_str = str(img_data.get("ramFree", "")).strip()
    if not ram_free_str.isdigit():
        raise ONTDriverError(f"Không đọc được thông số ramFree hợp lệ từ ONT (ramFree={ram_free_str!r}).")
    ram_free_kb = int(ram_free_str)
    file_size_kb = file_size / 1024.0
    if ram_free_kb <= 0 or file_size_kb > ram_free_kb:
        raise ONTDriverError(
            f"Không đủ bộ nhớ RAM trống trên ONT ({ram_free_kb} KB) để tải file firmware ({int(file_size_kb)} KB)."
        )

    # 3. Upload firmware binary via multipart/form-data
    upload_url = f"{self.base_url}/cgi-bin/luci/uploadsoftware.conf"
    try:
        with open(fw_path, "rb") as fw_file:
            multipart_files = [
                ("file", (safe_filename, fw_file, "application/octet-stream")),
                ("sessionKey", (None, self.session_key)),
            ]
            up_resp = self.session.post(
                upload_url,
                files=multipart_files,
                headers={
                    "Content-Type": None,
                    "Referer": f"{self.base_url}/update_software.html",
                    "Accept": "application/json, text/javascript, */*; q=0.01",
                },
                timeout=upload_timeout,
            )
    except requests.RequestException as e:
        raise ONTNetworkError(f"Lỗi kết nối trong khi tải lên file firmware: {e}") from e
    if up_resp.status_code != 200:
        raise ONTDriverError(f"Tải lên file firmware thất bại (HTTP {up_resp.status_code})")

    try:
        up_data = up_resp.json() if up_resp.text else {}
    except ValueError as e:
        raise ONTDriverError("Phản hồi uploadsoftware.conf không đúng định dạng JSON.") from e
    if up_data.get("sessionKey"):
        self.session_key = up_data["sessionKey"]
    if up_data.get("status") != "valid":
        raise ONTDriverError(
            f"File firmware không hợp lệ hoặc sai chữ ký (status={up_data.get('status', 'unknown')})."
        )
    # Crucial [Red Team RT-01]: Clear session_key immediately before returning so finally: self.logout()
    # never sends logout.cmd into an ONT that has already started flashing/rebooting.
    self.session_key = ""
    return up_data
```

## Related Code Files
- Modify: `app/core/config_models.py`
- Modify: `app/core/ont_driver.py`

## Implementation Steps
1. Cập nhật `GlobalConfig` trong `app/core/config_models.py` thêm `enable_firmware_update: bool = False`, `firmware_path: str = ""`, `firmware_upload_timeout: float = 120.0`.
2. Trong `app/core/ont_driver.py`, import `os` và thêm phương thức `update_firmware(self, firmware_path: str, upload_timeout: float = 120.0) -> Dict[str, Any]` với đầy đủ các bước kiểm tra `RT-01`, `RT-04`, `RT-05`.
3. Cập nhật `ONTDriver.execute_full_setup()`:
   - Đọc snapshot `do_fw_update = bool(global_cfg.enable_firmware_update)` và `fw_path = (global_cfg.firmware_path or "").strip()` ngay đầu hàm (`[Red Team RT-06]`).
   - Nếu `do_fw_update` bật: kiểm tra sớm `fw_path` tồn tại và `os.path.getsize(fw_path) > 0` ngay trước Bước 1.
   - Đặt `total_steps = 11 if do_fw_update else 10`.
   - Sau Bước 9 (`Đồng bộ dữ liệu trạng thái`):
     - Nếu `do_fw_update` bật:
       - Emit Bước 10 `RUNNING`: `"Nâng cấp Firmware (Update Software)"` — `f"Đang tải lên {os.path.basename(fw_path)}..."`.
       - Gọi `self.update_firmware(fw_path, upload_timeout=global_cfg.firmware_upload_timeout)` trong khối `try ... except ONTDriverError as e: raise type(e)(f"Đã đổ xong cấu hình (Bước 1-9), nhưng lỗi Nâng cấp Firmware (Bước 10): {e}") from e` (`[Red Team RT-05]`). (Bên trong `update_firmware()`, khi `status == "valid"`, `self.session_key` đã được xóa về `""` ngay lập tức `[Red Team RT-01]`).
       - Emit Bước 10 `SUCCESS`: `"Đã tải lên Firmware hợp lệ, ONT bắt đầu cập nhật"`.
       - Emit Bước 11 `SUCCESS`: `"Hoàn tất & Tự động Khởi động lại ONT"` — `"ONT đang tự nâng cấp và khởi động lại. Có thể rút cáp ngay!"`.
       - Trả về dict kết quả kèm `"firmware_updated": True` và `"firmware_file": os.path.basename(fw_path)`.
     - Nếu `do_fw_update` tắt:
       - Giữ nguyên Bước 10 `"Đăng xuất & Dọn dẹp phiên (Logout)"` và trả về kết quả 10/10 bước như cũ.

## Todo
- [x] Thêm `enable_firmware_update`, `firmware_path`, `firmware_upload_timeout` vào `GlobalConfig` (`app/core/config_models.py`)
- [x] Cài đặt `ONTDriver.update_firmware()` trong `app/core/ont_driver.py` (kèm xử lý `RT-01`, `RT-04`, `RT-05`)
- [x] Tích hợp bước nâng cấp firmware tùy chọn vào `ONTDriver.execute_full_setup()` (kèm snapshot `RT-06` và thông báo lỗi `RT-05`)

## Success Criteria
- [x] `ONTDriver.update_firmware()` gửi đúng 3 request tuần tự với header `multipart/form-data; boundary=...` cho `uploadsoftware.conf`.
- [x] Khi `enable_firmware_update=False`, `execute_full_setup` chạy đúng 10 bước như cũ.
- [x] Khi `enable_firmware_update=True`, `execute_full_setup` chạy 11 bước, kiểm tra `status == "OK"` của `updatefwsessioncheck.json`, kiểm tra `ramFree > 0`, xác nhận `status == "valid"`, xóa `self.session_key = ""` ngay trước khi return khỏi `update_firmware()`, nhưng vẫn giữ `sessionKey` mới nhất để gọi `logout.cmd` nếu quá trình upload gặp lỗi.

## Risk Assessment
- **Rủi ro 1 (`Content-Type` header conflict trong `requests.Session`):** `self.session.headers` khởi tạo với `"Content-Type": "application/x-www-form-urlencoded"`.
  - *Giải pháp:* Truyền rõ `"Content-Type": None` trong `headers` của `self.session.post(upload_url, files=..., headers={"Content-Type": None, ...})` và kiểm chứng ở cả tầng mock `Session.post` lẫn tầng HTTP wire trong `MockLuCIServer` (`[Red Team RT-06]`).
- **Rủi ro 2 (Race condition nhấn Cancel trong lúc đang truyền file 12s):** Nếu `emit()` ném `RuntimeError` sau khi `update_firmware()` đã nhận `status == "valid"`, `logout.cmd` sẽ bị bắn vào ONT đang flash.
  - *Giải pháp:* Xóa `self.session_key = ""` ngay bên trong `update_firmware()` trước khi return và chỉ kiểm tra `_is_cancelled` khi `status == "RUNNING"` (`[Red Team RT-01]`).
