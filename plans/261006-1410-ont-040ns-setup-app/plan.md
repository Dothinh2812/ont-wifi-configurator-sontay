---
title: "Ứng dụng Desktop Windows tự động đổ cấu hình ONT VNPT iGate GW040-NSv2 từ file Excel"
description: "Ứng dụng Desktop Windows (PyQt6) tự động nạp cấu hình ONT iGate GW040-NSv2 theo quy trình 1-by-1 từ file Excel mẫu (SLID, PPPoE, WiFi 2.4G/5G, LAN IPv4, đổi pass admin, logout an toàn)."
status: completed
priority: P1
effort: "14h"
issue: null
branch: main
tags: [desktop, windows, automation, ont, pyqt, excel]
blockedBy: []
blocks: []
created: 2026-10-06
---

# Kế hoạch: Xây dựng Ứng dụng Desktop Windows Đổ Cấu Hình Tự Động ONT VNPT iGate GW040-NSv2 từ File Excel

## Overview
Xây dựng một ứng dụng Desktop đồ họa độc lập chạy trên hệ điều hành Windows (PyQt6/PySide6, đóng gói `.exe`), cho phép kỹ thuật viên nạp danh sách cấu hình từ file Excel mẫu (`file-mau.xlsx`), tự động cấu hình từng thiết bị ONT iGate GW040-NSv2 qua giao tiếp cáp mạng LAN theo luồng 1-by-1 (cắm cáp $\rightarrow$ ấn RUN $\rightarrow$ đổ cấu hình $\rightarrow$ chuông/hộp thoại báo hoàn tất $\rightarrow$ đổi sang thiết bị tiếp theo).

Ứng dụng tái hiện đầy đủ và tự động hóa toàn bộ 10 bước cấu hình an toàn trích xuất từ dữ liệu thực tế `ont-040NSv2-setup.jsonl`:
1. Kết nối và probe ổn định thiết bị ONT tại `https://192.168.1.1/` (yêu cầu 3 lần HTTP 200 liên tiếp để tránh kết nối chập chờn khi đàm phán DHCP).
2. Đăng nhập tài khoản quản trị mặc định ban đầu (`admin` / `admin`), kiểm tra `firstlog == "1"` (gán `sessionKey = "factoryID"`) và kiểm soát số lần thử `numberLogin` tránh bị khóa phần cứng 180s.
3. Đổi mật khẩu quản trị sang mật khẩu mới (mặc định `Passwd2@`, cho phép cấu hình trên UI) qua `/cgi-bin/luci/accesscontrolcfg.json` kèm kiểm tra độ phức tạp.
4. Đăng nhập lại với mật khẩu mới để lấy `sessionKey` phiên làm việc chính thức.
5. Cài đặt kết nối WAN PPPoE an toàn & lũy thừa (kiểm tra `wancfg.json`, nếu có VLAN 11 gọi `editwan.json`, nếu chưa có gọi `addwan.json` và `sendwan.cmd`).
6. Cài đặt mật khẩu GPON / SLID (ưu tiên `Mã MEN`, dự phòng `SO_MAY`, bắt buộc kiểm tra độ dài $\le 10$ ký tự ASCII).
7. Cấu hình Wi-Fi 2.4 GHz (SSID đọc từ cột `SSID Wi-Fi 2.4G`, mật khẩu đọc từ cột `Mật khẩu Wi-Fi 2.4G`, bảo mật WPA2/WPA3 PSK AES) và restart Wi-Fi, chèn khoảng nghỉ 2s cho driver ổn định.
8. Cấu hình Wi-Fi 5 GHz: Tên Wi-Fi 5G tự động lấy bằng tên Wi-Fi 2.4G hiện tại + ` 5G` (nếu có, ví dụ `Kim Thu` $\rightarrow$ `Kim Thu 5G`, fallback `VNPT 5G`); Mật khẩu Wi-Fi 5G mặc định giống hệt Wi-Fi 2.4G (fallback `1234567890`), cho phép sửa trên UI và kích hoạt restart Wi-Fi.
9. Lưu cấu hình mạng LAN IPv4 (Group Name: `br-lan`, IP: `192.168.1.1`, Subnet: `255.255.255.0`, DHCP Range: `192.168.1.2` - `192.168.1.254`, lease: 1440 - mặc định khóa chỉnh sửa để tránh làm mất kết nối gateway).
10. Đăng xuất và dọn dẹp phiên (Logout & Session Cleanup): gửi `POST /cgi-bin/luci/logout.cmd` trong khối `finally` để giải phóng phiên, loại trừ triệt để lỗi `"user exist"`.

## Goals

| # | Mục tiêu | Độ ưu tiên |
|---|----------|------------|
| 1 | Xây dựng Core ONT Protocol Driver tương thích chính xác với LuCI CGI của VNPT GW040-NSv2 (hỗ trợ 10 bước, chống lockout DoS, kiểm soát idempotency và `encode_query`) | P1 |
| 2 | Xây dựng Module phân tích và kiểm tra tính hợp lệ dữ liệu file Excel mẫu (`file-mau.xlsx`), chặn lỗi mất mạng do thiếu PPPoE pass và lưu checkpoint liên tục | P1 |
| 3 | Xây dựng Giao diện Desktop Windows hiện đại, trực quan, hỗ trợ luồng cắm/rút thủ công từng thiết bị, đồng bộ hóa tạm dừng probe khi chạy | P1 |
| 4 | Cung cấp thông báo âm thanh đa nền tảng an toàn, pop-up hoàn tất và tính năng xuất file Excel báo cáo kết quả có chống khóa file | P1 |
| 5 | Đóng gói thành file thực thi `.exe` độc lập cho Windows và tài liệu hướng dẫn kỹ thuật viên chi tiết | P1 |

## System Architecture

```
┌────────────────────────────────────────────────────────┐
│                   Desktop UI (PyQt6)                   │
├──────────────────────────┬─────────────────────────────┤
│   Device Table View      │     Settings Panel (UI)     │
│   - Load file-mau.xlsx   │     - Admin Pass (Passwd2@) │
│   - Display 89 rows      │     - WiFi 5G ({SSID_2.4G} 5G)│
│   - Highlight status     │     - LAN IPv4 (🔒 192.168.1.1)│
│   - Inline PPPoE edit    │     - Advanced Unlock Toggle│
├──────────────────────────┴─────────────────────────────┤
│   Operation Control Panel                              │
│   - ONT Status: [● ONLINE (Sustained 3s Debounce)]     │
│   - [RUN (Đổ cấu hình)]   [SKIP]   [RETRY]   [CANCEL]  │
│   - Step Progress Bar (1..10) & Console Log (Masked)   │
│   - Safe Audio Alert & Completion Dialog               │
└──────────────────────────┬─────────────────────────────┘
                           │ (Signals / QThread)
                           ▼
┌────────────────────────────────────────────────────────┐
│              Core Automation Engine                    │
├────────────────────────────────────────────────────────┤
│ 1. Network Probe (Debounced 3s - PAUSED during RUN)    │
│ 2. ExcelManager (OpenPyXL + Checkpoint Persistence)    │
│ 3. ONTDriver (Session Management & LuCI Endpoints):    │
│    - /cgi-bin/luci/login.cmd (Track numberLogin/DoS)   │
│    - /cgi-bin/luci/accesscontrolcfg.json (Change Pass) │
│    - /cgi-bin/luci/wancfg.json + editwan.json (Idemp)  │
│    - /cgi-bin/luci/sendwan.cmd (PPPoE + VLAN 11)       │
│    - /cgi-bin/luci/gponpasswd.json (SLID <= 10 chars)  │
│    - /cgi-bin/luci/wirelesscfg.json (WiFi 2.4G & 5G)   │
│    - /cgi-bin/luci/wlrestart.json (2s Settling Pause)  │
│    - /cgi-bin/luci/savelanv4cfg.cmd (LAN IPv4 & DHCP)  │
│    - /cgi-bin/luci/logout.cmd (Mandatory in finally)   │
└────────────────────────────────────────────────────────┘
```

## Phases

| # | Giai đoạn | Trạng thái | Ước tính | Phụ thuộc |
|---|-----------|------------|----------|-----------|
| 1 | [Phase 1: Xây dựng Core Automation Engine & Protocol Driver](./phase-01-core-engine.md) | Completed | 4h | Không |
| 2 | [Phase 2: Xây dựng Module Đọc/Ghi Dữ Liệu Excel & Validation](./phase-02-excel-data-parser.md) | Completed | 3h | Phase 1 |
| 3 | [Phase 3: Xây dựng Giao Diện Desktop Windows & Luồng Tương Tác 1-by-1](./phase-03-desktop-gui.md) | Completed | 4h | Phase 1, Phase 2 |
| 4 | [Phase 4: Đóng Gói Ứng Dụng Windows (.exe), Kiểm Thử & Tài Liệu](./phase-04-packaging-windows.md) | Completed | 3h | Phase 1, 2, 3 |

## Success Criteria

- [x] Phân tích chính xác 100% các cột từ file `file-mau.xlsx` không bị mất định dạng chuỗi số máy.
- [x] Bắt lỗi dữ liệu nghiêm ngặt: phát hiện 13 dòng thiếu pass PPPoE và chặn không cho chạy với mật khẩu rác; kiểm tra SLID $\le 10$ ký tự ASCII.
- [x] Tự động hóa đầy đủ 10 bước cấu hình ONT 040NSv2 bao gồm đóng phiên bằng `logout.cmd`, không để lại session treo.
- [x] Chống khóa phần cứng ONT 180s nhờ kiểm soát `numberLogin`, không retry mù khi sai mật khẩu.
- [x] Cấu hình WAN mang tính lũy thừa (idempotent), cho phép chạy lại thiết bị cấu hình dở dang an toàn.
- [x] Giao diện mượt mà, phát hiện cắm/rút cáp với bộ lọc debounce 3s, tự động tạm dừng probe khi chạy cấu hình.
- [x] Âm thanh thông báo tương thích đa nền tảng (không crash `winsound` trên Linux/macOS).
- [x] Checkpoint kết quả ghi nhận liên tục sau từng máy, bảo vệ dữ liệu chống mất điện / crash.
- [x] Đóng gói thành file `.exe` hoạt động độc lập trên Windows 10/11.

## Validation Log

### Session 1 - 2026-10-06
- Verification Results: 10 claims checked, 10 verified, 0 failed.
- Decisions Confirmed: Technology Stack (PyQt6 + PyInstaller), Trigger Mode (Manual RUN), ONT Password Fallback (2 steps).

### Session 2 - 2026-10-06 (User Revision)
- Decision: Tên Wi-Fi 5G lấy bằng tên Wi-Fi 2.4G hiện tại (nếu có) + ` 5G`; Mật khẩu Wi-Fi 5G mặc định lấy giống hệt Wi-Fi 2.4G.
- Propagation: Đã đồng bộ vào `phase-02-excel-data-parser.md`, `phase-03-desktop-gui.md` và `plan.md`.

## Red Team Review

### Session 3 - 2026-10-06 (Adversarial Review)
**Reviewers:** Security Adversary, Assumption Destroyer, Failure Mode Analyst
**Findings:** 15 (15 accepted, 0 rejected)
**Severity breakdown:** 5 Critical, 9 High, 1 Medium

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Blind Login Fallback & Retries Trigger ONT 3-Minute Hardware Lockout DoS (`numberLogin >= 3`, `timeoutLogin = 180s`) | Critical | Accept | Phase 1 (`phase-01-core-engine.md`) |
| 2 | Silent Fallback of Missing PPPoE Passwords in Excel (13 rows in `file-mau.xlsx`) Causes Total WAN Outage on BRAS | Critical | Accept | Phase 2 (`phase-02-excel-data-parser.md`) |
| 3 | Conflating SO_MAY with SLID Violates ONT 10-Char GPON Password Limit (`optical.js` `len_char` check) | Critical | Accept | Phase 1 & 2 (`phase-01-core-engine.md`, `phase-02-excel-data-parser.md`) |
| 4 | Command Injection & CGI Crashes via Unsanitized Input Matching `unsafeName` Shell Metacharacters (`&\|;$><\`!'\"`) | Critical | Accept | Phase 1 & 2 (`phase-01-core-engine.md`, `phase-02-excel-data-parser.md`) |
| 5 | LAN IPv4 Gateway Edit in Step 9 Destroys Host Network Route and Freezes Automation (`lanIpChanged = true`) | Critical | Accept | Phase 1 & 3 (`phase-01-core-engine.md`, `phase-03-desktop-gui.md`) |
| 6 | Hardcoded `addwan.json` with `serviceId=0` Fails on Existing WAN or RETRY (`wan.js` `editwan` vs `addwan`) | High | Accept | Phase 1 (`phase-01-core-engine.md`) |
| 7 | Missing Session Termination (`logout.cmd`) Induces Session Leaks and `"user exist"` Concurrency Lockout | High | Accept | Phase 1 & 3 (`phase-01-core-engine.md`, `phase-03-desktop-gui.md`) |
| 8 | Threading Race Condition: 1s Network Probe Polling Concurrently with Multi-Step ONT POST Requests | High | Accept | Phase 3 (`phase-03-desktop-gui.md`) |
| 9 | Form Parameter Truncation via Unhandled `&` Delimiter in Passwords (ONT `encode_query` expects `\x7f`) | High | Accept | Phase 1 (`phase-01-core-engine.md`) |
| 10 | Application Crash on Factory Default ONT Due to Missing `sessionKey` in `firstlog=1` Protocol Contract | High | Accept | Phase 1 (`phase-01-core-engine.md`) |
| 11 | Platform Dependency Crash: Windows-Only `winsound` Breaks Tests & CI on Linux/macOS | High | Accept | Phase 3 (`phase-03-desktop-gui.md`) |
| 12 | Connection Probe Debounce: Prevent False ONLINE States during Windows DHCP Negotiation & ONT Boot | High | Accept | Phase 3 (`phase-03-desktop-gui.md`) |
| 13 | Wi-Fi Driver Restart Race: Consecutive `wlrestart.json` Calls Corrupt NVRAM / Drop Requests on Broadcom Radio | High | Accept | Phase 1 (`phase-01-core-engine.md`) |
| 14 | State Loss on Crash & Unhandled Excel File Write Locks (`PermissionError` if file is open in Excel) | High | Accept | Phase 2 & 3 (`phase-02-excel-data-parser.md`, `phase-03-desktop-gui.md`) |
| 15 | Admin Password Complexity Check & Missing Mandatory Parameters in `accesscontrolcfg.json` Payload | Medium | Accept | Phase 1 & 3 (`phase-01-core-engine.md`, `phase-03-desktop-gui.md`) |

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-core-engine.md`, `phase-02-excel-data-parser.md`, `phase-03-desktop-gui.md`, `phase-04-packaging-windows.md`
- **Decision deltas checked:** 15
- **Reconciled stale references:** 15
- **Unresolved contradictions:** 0
- **Summary of Reconciliations:**
  1. Đồng bộ toàn bộ tài liệu kế hoạch từ luồng 9 bước thành luồng 10 bước chuẩn (bổ sung bước 10 `logout.cmd` trong khối `finally`).
  2. Bãi bỏ hoàn toàn chính sách "gán mật khẩu dự phòng tự động cho PPPoE" do nguy cơ làm gián đoạn dịch vụ thuê bao; thay thế bằng quy tắc chặn lỗi nghiêm ngặt (Fatal Validation Error), yêu cầu nhập tay trên UI.
  3. Tách bạch khái niệm `SO_MAY` và `SLID` (GPON Password), áp dụng kiểm tra giới hạn $\le 10$ ký tự ASCII và ưu tiên `Mã MEN`.
  4. Đưa kiểm tra 11 ký tự shell metacharacter nguy hiểm (`&|;$><`\!'"` ) vào module validation và driver.
  5. Khóa mặc định chỉnh sửa IP LAN `192.168.1.1` để tránh mất định tuyến máy tính.
  6. Bổ sung cơ chế Idempotent WAN qua `wancfg.json` và `editwan.json` giúp RETRY an toàn.
  7. Giải quyết triệt để xung đột đa luồng: tự động pause Network Probe khi Worker đang chạy.
  8. Chuẩn hóa `encode_query` thay thế `&` bằng `\x7f` theo đúng đặc tả firmware LuCI.
  9. Phân biệt rõ hợp đồng `firstlog == "1"` (`sessionKey="factoryID"`) và `firstlog == "0"`.
  10. Thêm khoảng nghỉ 2s cho driver Wi-Fi giữa băng tần 2.4G và 5G.
  11. Thêm bộ lọc debounce 3s cho trạng thái ONLINE.
  12. Thêm wrapper an toàn đa nền tảng cho âm thanh thông báo (`winsound` guard).
  13. Thêm cơ chế ghi checkpoint liên tục sau từng máy để chống mất dữ liệu khi mất điện.
  14. Bắt lỗi khóa file Excel `PermissionError` khi người dùng đang mở file.
  15. Bổ sung kiểm tra độ phức tạp mật khẩu admin mới và đầy đủ tham số cho `accesscontrolcfg.json`.

<!-- slug: ont-040ns-setup-app -->
