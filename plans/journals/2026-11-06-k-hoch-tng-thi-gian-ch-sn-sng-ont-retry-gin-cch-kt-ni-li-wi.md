---
title: "Kế hoạch tăng thời gian chờ sẵn sàng ONT, retry giãn cách kết nối lại Wi-Fi và ghi log debug trên App Mobile"
date: 2026-11-06
summary: "Thiết kế cơ chế chờ ổn định sau beacon, retry giãn cách 5s-20s ở 2 tầng và hệ thống ghi log debug an toàn mật khẩu cho Android & iOS"
---

# Kế hoạch tăng thời gian chờ sẵn sàng ONT, retry giãn cách kết nối lại Wi-Fi và ghi log debug trên App Mobile

## What happened
Sau khi triển khai kế hoạch `261105-1821-mobile-dual-wifi-handover-firmware` và chạy thử trên điện thoại thật, quy trình vẫn lỗi tại **Bước 7 (kết nối lại Wi-Fi mới sau khi đổi tên ở Lần 1)**. Phân tích trực tiếp mã nguồn (`OntWifiConnector.kt`, `OntMobileDriver.kt`, `IosWifiMonitor.swift`, `OntMobileDriver.swift`, `MainViewModel.kt/.swift`) xác định 4 nguyên nhân gốc rễ:
1. **Beacon != ONT Ready:** Driver `wl0`/`wl1` của GW040-NSv2 phát tên Wi-Fi mới trước khi `nas` (WPA2/WPA3), `br0` và `udhcpd` sẵn sàng (và `acsd` có thể nháy kênh lần 2 sau 3-8s), nhưng app kết nối ngay ở `0ms` sau khi thấy beacon và cắt DHCP sớm ở `5s`.
2. **Thời gian chờ ngắn & ghi đè cứng:** `initialSettleMs = 4s`, `wifiHandoverTimeoutMs = 120s`, `gatewayPostHandoverTimeoutMs = 45s` và `timeoutMs = 10s/25s` cứng trong `MainViewModel.kt`.
3. **Thiếu Retry Giãn Cách & Thiếu vòng lặp Bước 7 tổng thể trên Android:** `OntMobileDriver.kt:1661` chỉ gọi `onSwitchToSecondaryWifi` 1 lần ngoài vòng lặp (nếu rớt sóng `onLost` ngay sau khi bắt được thì kẹt 45-65s trên socket chết); khoảng nghỉ trong `OntWifiConnector` phẳng `2.5s`.
4. **Không có hệ thống ghi Log trong app:** Không có nhật ký thời gian thực hay xuất file `.txt` trên máy để chẩn đoán ngoài hiện trường.

## Decision
Thiết kế kế hoạch 4 Phase tại `plans/261106-0849-mobile-wifi-reconnect-backoff-and-debug-log` (đã qua Red Team Review 9 phát hiện `RT-01..RT-09` & Whole-Plan Consistency Sweep):
- Nâng ngân sách chờ (`180s` Android / `240s` iOS, lắng đầu `8s` + ổn định sau beacon `8s` ở lượt 1, bảo toàn mốc `step6CompletedMicros`, thoát sớm Pha A nếu hết quota `4/120s`, chờ DHCP `15s` + giữ link `1.5s`, chờ Gateway `60s`, đồng bộ trước Firmware `90s`).
- Bổ sung retry giãn cách lũy tiến `5s -> 10s -> 15s -> 20s` ở cả tầng OS Wi-Fi lẫn vòng lặp Bước 7 tổng thể trong Driver (kèm kiểm tra `isNetworkTransportAlive` thoát tức thì khi rớt link và tách luồng Sideloadly / auto-join trên iOS).
- Bổ sung `AppDiagnosticLogger` (Android & iOS) xoay vòng `512 KB x 2`, lọc mật khẩu 2 lớp đồng bộ trên luồng gọi (hỗ trợ URL-encoded `%XX` & JSON có dấu cách, không làm hỏng `"admin"`), xem trực tiếp trên `ExecutionProgressSheet` và chia sẻ file `.txt` qua Zalo/Telegram.

## Next steps
- Xem xét/xác nhận kế hoạch hoặc thực thi ngay bằng `/ak:cook /home/vtst/ONT-config/plans/261106-0849-mobile-wifi-reconnect-backoff-and-debug-log/plan.md`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
