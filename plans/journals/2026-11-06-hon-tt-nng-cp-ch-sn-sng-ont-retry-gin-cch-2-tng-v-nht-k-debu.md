---
title: "Hoàn tất nâng cấp chờ sẵn sàng ONT, retry giãn cách 2 tầng và nhật ký Debug trên Android & iOS"
date: 2026-11-06
summary: "Triển khai cửa sổ ổn định sau beacon, retry giãn cách 5s-20s hai tầng ở Bước 7 và AppDiagnosticLogger xuất ONT_Debug_Log_*.txt an toàn mật khẩu trên cả Android và iOS (32/32 JUnit + 93/93 pytest PASS)."
---

# Hoàn tất nâng cấp chờ sẵn sàng ONT, retry giãn cách 2 tầng và nhật ký Debug trên Android & iOS

## What happened
Triển khai trọn vẹn kế hoạch `261106-0849-mobile-wifi-reconnect-backoff-and-debug-log` trên cả Android Native (`android-app/`) và iOS Native (`ios-app/`) để xử lý lỗi thực địa ở **Bước 7 (kết nối lại Wi-Fi sau khi đổi tên Lần 1)** trên phần cứng Broadcom **VNPT iGate GW040-NSv2**:
1. **Chờ ONT sẵn sàng thực sự sau khi thấy sóng (Beacon != ONT Ready):** Nâng ngân sách handover lên `180s` (Android) / `240s` (iOS), lắng đầu `8s` + cửa sổ ổn định `8s` sau beacon ở lượt 1 (`0s + 2s` ở lượt retry ngoài, bảo toàn `step6CompletedMicros`), thoát sớm Pha A sau `8s` nếu cạn hạn mức quét `4 lần/120s`, chờ DHCP `192.168.1.x` tới `15s` + giữ link `1.5s`/`1.2s`, và chờ `2.5s` trước khi thăm dò HTTPS Gateway (`60s`) / đồng bộ trước Firmware (`90s`).
2. **Retry giãn cách lũy tiến 2 tầng (`5s -> 10s -> 15s -> 20s`):** Bổ sung giãn cách tại tầng OS Wi-Fi (`OntWifiConnector` & `IosWifiMonitor.tryJoinWifiDetailed`) lẫn vòng lặp Bước 7 tổng thể trong `OntMobileDriver` (thoát tức thì khỏi socket chết qua `isNetworkTransportAlive`, hội tụ `wlAssociatedDevice` trong `15s`, khóa BSSID khi 2 băng tần trùng tên, phân tách luồng Sideloadly không trả sớm IP cũ của `Band A` và không thử lại khi sai `adminNewPass`, lệch TLS cert SHA-256 trong `postOptionalSessionJson` hoặc sai `baseMacAddress`).
3. **Hệ thống ghi & chia sẻ Nhật ký Debug (`AppDiagnosticLogger` + UI):** Che mật khẩu 2 lớp đồng bộ ngay trên luồng gọi (nguyên văn, URL-encoded `%XX`, `ontEncodeQuery`, JSON có khoảng trắng mà không làm hỏng từ khóa `admin`), xoay vòng `512 KB x 2` (`ont_debug.log` / `ont_debug.prev.log`) đồng bộ hóa qua hàng đợi ghi, tách luồng UI `200ms`, bảo vệ Clipboard (`EXTRA_IS_SENSITIVE` / `.localOnly` `180s`), thu hẹp `file_paths.xml`, và hỗ trợ chia sẻ `ONT_Debug_Log_*.txt` qua Zalo/Telegram.

## Decision
- Giữ nguyên khả năng đóng gói `.ipa` miễn phí cho Sideloadly (`CODE_SIGNING_ALLOWED=NO`, zero `.entitlements`) bằng cách phân loại chuẩn `NEHotspotConfigurationError` (`alreadyAssociated` `13` vs `< 40ms` `internal` `8` vs `userDenied` `7`).
- Đưa toàn bộ tham số thời gian chờ mới của `OntMobileDriver` thành tham số constructor có mặc định thực tế và ghi đè nhanh tại tất cả 8 điểm khởi tạo trong `OntMobileDriverTests.swift` và `OntMobileDriverTest.kt`, giữ thời gian chạy JUnit `< 5s` (`4.26s` wall-clock / `2.515s` JUnit, `32/32` PASS) và `pytest -v` (`93/93` PASS).

## Next steps
- Cài bản `app-debug.apk` (Android) và `ONT_WiFi_Configurator_Unsigned.ipa` (iOS) ra thiết bị thực địa để nghiệm thu Bước 7 trên các modem GW040-NSv2 đặt kênh 5GHz tự động / DFS và kiểm tra luồng gửi `ONT_Debug_Log_*.txt` qua Zalo.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
