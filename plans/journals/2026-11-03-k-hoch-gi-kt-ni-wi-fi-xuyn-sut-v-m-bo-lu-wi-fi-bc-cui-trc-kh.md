---
title: "Kế hoạch giữ kết nối Wi-Fi xuyên suốt và đảm bảo lưu Wi-Fi bước cuối trước khi ngắt trên Android & iOS"
date: 2026-11-03
summary: "Thiết kế kế hoạch 3 Phase loại bỏ Band-Hopping Handover, xác thực băng tần thực tế qua dhcpclient.json + deviceinfo2.json ngay sau Bước 2, vá 9 lỗ hổng Red Team (RT-01..RT-09) chống khóa 180s và chống báo SUCCESS giả ở Bước 7."
---

# Kế hoạch giữ kết nối Wi-Fi xuyên suốt và đảm bảo lưu Wi-Fi bước cuối trước khi ngắt trên Android & iOS

## What happened
Xây dựng và thẩm định Red Team kế hoạch triển khai chi tiết `plans/261103-1001-mobile-wifi-keepalive-save-final/` (3 Phase) nhằm khắc phục triệt để hiện tượng App Mobile (Android & iOS) bị ngắt kết nối trước khi hoàn thành cấu hình Wi-Fi bước cuối:
- **Đối chiếu gói tin phần cứng thực tế (`ont-040NSv2-setup.jsonl`):** Xác nhận `wirelesscfg.json` (`action=Apply`) là API duy nhất ghi SSID & mật khẩu vào NVRAM (~200ms trả `{"status": "OK"}`) và kích hoạt `wlmngr` dưới nền, trong khi `wlrestart.json` (`wlcfg_ok`) là API đọc cờ `_wlrestart_`.
- **Xác định 4 nguyên nhân gây đứt kết nối sớm:** (1) Android chủ động `logout()` + `unbindOntWifi()` để nhảy sóng phụ (`Band-Hopping Handover`) giữa Bước 6 và Bước 7; (2) nhận diện sai băng tần khi Android tắt GPS hoặc iOS để lệch nút chọn `2.4GHz / 5GHz`; (3) thiếu cổng `ensureGatewayReadyBeforeStep7` trước Bước 7 Attempt 1 kèm lỗi lồng `try/catch` và sót `"fail authen"`; (4) thiếu kiểm tra `stillOnOldWifi` ở Attempt 3 của Bước 7.
- **Red Team Review (3 Hostile Reviewers — 9/9 Findings Accepted, `RT-01..RT-09`):** Tách `recoveryRetryDelayMs = 1200` khỏi `settlingDelayMs`; chuyển `resolveConnectedBandFromOnt` lên ngay sau Bước 2 với parser mảng 2 chiều `wirelessInfoList` của `deviceinfo2.json` + ưu tiên `wlAssociatedDevice`; sửa lỗi gọi đúp `reloginAfterRadioBounce` gây khóa 180s; gỡ bọc `FlushTrackedIOException.cause` và kiểm tra `stillOnOldWifi` trên cả 3 lần thử (`1..3`); giữ `suppressAutoBindAfterCompletion = true` qua `dismissExecutionSheet()` để bảo vệ nạp Firmware 8 bước và mạng 4G/5G gửi Zalo.

## Decision
Chọn kiến trúc **Single-Connection Keep-Alive & Save-Before-Disconnect** đồng nhất trên cả Android và iOS (tương tự cơ chế giữ kết nối đã chứng minh truyền tải ổn định 30 MB ở chế độ nạp Firmware 8 bước), loại bỏ hoàn toàn Band-Hopping Handover và không đăng ký `WifiNetworkSuggestion` tạm cho sóng Open xuất xưởng để tránh hộp thoại thu hồi quyền `CHANGE_WIFI_STATE` trên Android 11+.

## Next steps
- Thực thi triển khai theo 3 Phase bằng `/ak:cook /home/vtst/ONT-config/plans/261103-1001-mobile-wifi-keepalive-save-final/plan.md`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
