---
title: Kế hoạch xây dựng ứng dụng Android Native cấu hình ONT GW040-NSv2 qua Wi-Fi
date: 2026-10-17
summary: Thiết kế và lập kế hoạch 4 giai đoạn cho ứng dụng Android Native (Kotlin + Jetpack Compose) cấu hình ONT qua Wi-Fi từ file Excel, hỗ trợ chọn đích danh ma_tb, sửa tham số trước khi RUN và tối ưu hóa đổi Wi-Fi 2.4G ở bước cuối cùng
---

# Kế hoạch xây dựng ứng dụng Android Native cấu hình ONT GW040-NSv2 qua Wi-Fi

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

## Bối cảnh & Hợp đồng thiết kế (Brainstorm Contract)

Người dùng yêu cầu phát triển ứng dụng **Android Native** để kỹ thuật viên cấu hình ONT VNPT iGate GW040-NSv2 qua kết nối Wi-Fi ngoài hiện trường (không bao gồm tính năng upgrade firmware từ file có sẵn trong đợt triển khai này):
- Tìm và bắt sóng Wi-Fi mặc định của ONT sau khi khởi động (`GW040-NSv2_2.4G_XXXXXX` / `GW040-NSv2_5G_XXXXXX`, mặc định xuất xưởng là mạng Open theo trace `ont-040NSv2-setup.jsonl`).
- Thay vì chạy tuần tự từng thiết bị như bản Windows, ứng dụng nạp sẵn toàn bộ danh sách cấu hình từ 1 file Excel (`file-mau.xlsx`) vào Room SQLite Database, cho phép KTV tìm kiếm và **chọn đúng 1 `ma_tb`** (`Account` / `SO_MAY`) cần đổ cấu hình.
- Hiển thị toàn bộ tham số của `ma_tb` lên màn hình để KTV **xác nhận và chỉnh sửa trực tiếp** trước khi bấm **RUN (Đổ cấu hình)**.
- Đưa bước **đổi tên và mật khẩu Wi-Fi 2.4G xuống bước cuối cùng** do thao tác này làm khởi động lại radio và ngắt kết nối Wi-Fi tới ONT.

## Giải pháp kỹ thuật trọng tâm

1. **Ép định tuyến qua Wi-Fi nội bộ (Bypass 4G/5G trên Android 10+):**
   - Sử dụng `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork` và gắn `wifiNetwork.socketFactory` vào `OkHttpClient` để toàn bộ request tới `https://192.168.1.1` luôn đi qua Wi-Fi của ONT ngay cả khi máy đang bật dữ liệu di động 4G/5G.
2. **Single-Restart Batching + Dynamic Band Ordering cho Bước Đổi Wi-Fi Cuối Cùng:**
   - Thực thi tuần tự 7 bước: (1) Probe Gateway $\rightarrow$ (2) Login & Đổi Pass Admin $\rightarrow$ (3) WAN PPPoE VLAN 11 Idempotent $\rightarrow$ (4) GPON SLID $\rightarrow$ (5) LAN IPv4 $\rightarrow$ (6) Ghi NVRAM băng tần phụ (mặc định Wi-Fi 5G `wl_unit=1`, **chưa gọi** `wlrestart.json`) $\rightarrow$ (7) Ghi NVRAM băng tần đang kết nối (Wi-Fi 2.4G `wl_unit=0`), nhận `HTTP 200 {"status": "OK"}` rồi mới gọi **1 lần duy nhất `wlrestart.json`**.
   - Bắt ngoại lệ ngắt socket có chủ đích (`Commit Ambiguity`) khi radio tắt sóng và xác nhận hoàn tất bằng **Passive Beacon Verification** (quét thấy SSID mới phát sóng).

## Cấu trúc 4 Giai đoạn (`plans/261017-0914-android-ont-wifi-configurator/`)

- **Phase 1 (`phase-01-start.md`):** Khởi tạo project Android Native (`android-app/`), đọc/ghi `.xlsx`, Room SQLite Database và bộ kiểm tra hợp lệ `OntInputValidator`.
- **Phase 2 (`phase-02-wifi-binding-and-ont-driver.md`):** Xây dựng `OntWifiConnector` (quét, bắt Wi-Fi mặc định ONT, bind socket) và `OntMobileDriver` (7 bước LuCI CGI tối ưu cho Wi-Fi).
- **Phase 3 (`phase-03-mobile-ui-and-review-flow.md`):** Giao diện Jetpack Compose (`SubscriberListScreen`, `ConfigReviewScreen` cho phép kiểm tra/sửa tham số trước khi RUN, `ExecutionProgressSheet` và Footer chữ ký).
- **Phase 4 (`phase-04-testing-and-apk-packaging.md`):** Kiểm thử tự động với `MockWebServer` (mô phỏng đứt kết nối bước cuối) và đóng gói APK.
