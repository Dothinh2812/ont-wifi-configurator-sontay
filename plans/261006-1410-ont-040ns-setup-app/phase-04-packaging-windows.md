---
phase: 4
title: "Đóng Gói Ứng Dụng Windows (.exe), Kiểm Thử Tích Hợp & Tài Liệu Vận Hành"
status: completed
priority: P1
effort: "3h"
dependencies: [1, 2, 3]
---

# Phase 4: Đóng Gói Ứng Dụng Windows (.exe), Kiểm Thử Tích Hợp & Tài Liệu Vận Hành

## Overview
Xây dựng kịch bản đóng gói ứng dụng thành file thực thi độc lập `.exe` chạy trên Windows 10/11 không cần cài đặt Python (sử dụng PyInstaller), hoàn thiện máy chủ giả lập `mock_luci_server.py` mô phỏng đầy đủ mọi tình huống biên của ONT 040NSv2 (lockout DoS, firstlog, user exist, idempotency, giới hạn SLID), và cung cấp tài liệu hướng dẫn kỹ thuật viên sử dụng.

## Requirements
- Functional:
  - Cấu hình file build `ont_setup.spec` và kịch bản `build_windows.py` / `build.bat`:
    - Đóng gói toàn bộ mã nguồn Python, PyQt6, requests, openpyxl, urllib3, assets (icon `.ico`, âm thanh thông báo) thành 1 thư mục chạy độc lập (`dist/ONT_Configurator/`) hoặc 1 file duy nhất (`dist/ONT_Configurator.exe`).
    - Khai báo đầy đủ hidden imports: `PyQt6.sip`, `openpyxl`, `urllib3`, Qt platform plugins (`qwindows.dll`).
    - Tự động tích hợp icon ứng dụng chuyên nghiệp của VNPT / Thiết bị mạng.
  - <!-- Updated: Red Team Review - Comprehensive Mock Server -->
  - Server giả lập kiểm thử nâng cao `mock_luci_server.py`:
    - Tạo một web server HTTP/HTTPS cục bộ giả lập đúng hành vi của ONT iGate GW040-NSv2 theo các mốc sequence trong `ont-040NSv2-setup.jsonl`:
      + Trang login ban đầu với pass `admin/admin` và `firstlog=1`.
      + Bộ đếm `numberLogin` và kích hoạt timeout lockout 180s khi `numberLogin >= 3`.
      + Phản hồi `status: "user exist"` nếu phiên trước đó chưa gọi `logout.cmd`.
      + Endpoint đổi pass `accesscontrolcfg.json` xác thực tham số `oldPass`, `newPass`, `user_cur_log` và quy tắc mật khẩu mạnh.
      + Endpoint re-login sinh sessionKey ngẫu nhiên khi `firstlog=0`.
      + Endpoint kiểm tra WAN `wancfg.json`, tạo WAN `addwan.json`, chỉnh sửa WAN `editwan.json`, gửi WAN `sendwan.cmd`.
      + Endpoint đổi mật khẩu GPON `gponpasswd.json` kiểm tra giới hạn $\le 10$ ký tự ASCII và chặn 11 ký tự shell metacharacter.
      + Endpoint đổi WiFi 2.4G & 5G `wirelesscfg.json`, kích hoạt `wlrestart.json`.
      + Endpoint dọn dẹp phiên `logout.cmd`.
    - Dùng để kiểm thử tự động toàn diện mà không cần thiết bị vật lý thật trong tay.
  - Hướng dẫn vận hành chi tiết `HUONG_DAN_SU_DUNG_TOOL_ONT.md`:
    - Chi tiết các bước kết nối dây mạng LAN từ cổng Ethernet máy tính tới cổng LAN1/LAN2 trên ONT.
    - Cấu hình card mạng máy tính (đặt IP tĩnh `192.168.1.100/24` hoặc để DHCP tự nhận IP từ ONT).
    - Quy trình chuẩn bị file Excel, xử lý các dòng thiếu mật khẩu PPPoE (nhập trực tiếp trên bảng trước khi bấm RUN).
    - Ý nghĩa đèn báo trạng thái: Chờ 3 giây đèn ONLINE xanh ổn định sau khi cắm cáp trước khi bấm RUN.
    - Hướng dẫn xử lý sự cố thường gặp: ONT bị khóa 180s do sai mật khẩu (bấm nút Reset cứng sau thân máy), ONT bị treo phiên (`user exist`).
- Non-functional:
  - File đóng gói khởi động nhanh trên Windows (< 3 giây).
  - Không bị các phần mềm diệt virus thông thường (Windows Defender) chặn do thiếu chữ ký số hoặc mã nguồn không rõ ràng.

## Architecture
```
┌────────────────────────────────────────────────────────┐
│ PyInstaller Build Pipeline                             │
│ - Entry Point: app/main.py                             │
│ - Spec file: ont_setup.spec                            │
│ - Hidden imports: PyQt6.sip, openpyxl, urllib3         │
│ - Binaries & Data: Qt plugins, assets/                 │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ Output Distribution: dist/ONT_040NS_Tool.exe           │
│ (Portable Windows Executable)                          │
└────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────┴─────────────────────────────┐
│ Automated Integration Test Environment                 │
│ - mock_luci_server.py (Lockout, Idempotency, Logout)   │
│ - test_e2e_workflow.py (89 Excel rows verification)    │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Create: `build_windows.py`
- Create: `ont_setup.spec`
- Create: `tests/mock_luci_server.py`
- Create: `tests/test_e2e_workflow.py`
- Create: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Xây dựng `tests/mock_luci_server.py` sử dụng thư viện `http.server` chạy trên cổng cục bộ (ví dụ `127.0.0.1:8443`):
   - Mô phỏng chính xác chuỗi phản hồi JSON, cơ chế session, cơ chế lockout 180s, kiểm tra giới hạn 10 ký tự GPON và xử lý ký tự đặc biệt `\x7f`.
2. Viết integration test `tests/test_e2e_workflow.py` kích hoạt `ONTDriver` và `ExcelManager` chạy qua dữ liệu từ `file-mau.xlsx`, xác nhận:
   - Phát hiện đúng 13 dòng thiếu mật khẩu PPPoE.
   - Hoàn thành thành công các dòng hợp lệ với mock server.
   - Kiểm tra khối `finally:` luôn gọi `logout.cmd`.
3. Viết kịch bản cấu hình PyInstaller trong `ont_setup.spec` và `build_windows.py`:
   - Thêm icon ứng dụng.
   - Thêm cờ `--windowed`.
   - Cấu hình thu gom tự động thư mục `platforms` của PyQt6.
4. Biên soạn tài liệu chi tiết `HUONG_DAN_SU_DUNG_TOOL_ONT.md` bằng tiếng Việt rõ ràng, từng bước kèm ảnh minh họa quy trình làm việc của kỹ thuật viên.

## Success Criteria
- [x] Mock LuCI server chạy độc lập và mô phỏng chính xác mọi tình huống biên: lockout 180s, firstlog=1, user exist, kiểm tra SLID 10 ký tự, idempotency WAN.
- [x] Test E2E tự động chạy thành công trên cả môi trường Linux CI và Windows.
- [x] Kịch bản `build_windows.py` tạo ra file thực thi chạy độc lập không thiếu thư viện Qt.
- [x] Tài liệu hướng dẫn sử dụng đầy đủ, có mục cảnh báo đặc biệt về nhập mật khẩu PPPoE và thời gian chờ DHCP.

## Risk Assessment
- **Risk:** Windows Defender cảnh báo False Positive khi đóng gói bằng PyInstaller `--onefile`.
  - *Observable Signal:* Trình duyệt hoặc Windows Defender gắn cờ cảnh báo rủi ro khi mở file.
  - *Mitigation:* Cung cấp tùy chọn đóng gói dạng thư mục `--onedir` kèm kịch bản tạo shortcut, hướng dẫn thêm thư mục cài đặt vào exclusion list của Windows Defender nếu cần.
