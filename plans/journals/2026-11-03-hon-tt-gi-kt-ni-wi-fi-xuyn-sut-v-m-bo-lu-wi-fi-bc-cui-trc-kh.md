---
title: "Hoàn tất giữ kết nối Wi-Fi xuyên suốt và đảm bảo lưu Wi-Fi bước cuối trước khi ngắt kết nối trên App Mobile (Android & iOS)"
date: 2026-11-03
summary: "Loại bỏ Band-Hopping Handover, xác thực băng tần thực tế ngay sau Bước 2 qua dhcpclient.json + deviceinfo2.json (2D wirelessInfoList), chống gọi đúp login khóa 180s và đảm bảo lưu NVRAM Bước 7 trước khi ngắt kết nối (RT-01..RT-09)."
---

# Hoàn tất giữ kết nối Wi-Fi xuyên suốt và đảm bảo lưu Wi-Fi bước cuối trước khi ngắt kết nối trên App Mobile (Android & iOS)

## What happened
- Thực thi kế hoạch `plans/261103-1001-mobile-wifi-keepalive-save-final/plan.md` theo quy trình `--tdd --advice --auto` qua 3 Phase:
  1. **Phase 1 (Android Single-Connection Keep-Alive & Band Ground-Truth):** Gỡ bỏ hoàn toàn cơ chế nhảy sóng phụ (`onSwitchToConfiguredWifi`, `switchToSecondaryWifiAndAuthenticate`, `switchToConfiguredOntWifi`, `isSystemConnectedToTargetWifi`, `hasSwitchedToSecondaryBand`); tách `recoveryRetryDelayMs = 1200L` khỏi `settlingDelayMs = 400L` (`RT-03`); nâng cấp `OntWifiConnector` đọc `NetworkCapabilities.transportInfo` trên Android 12+ và lấy `getBoundWifiIpv4Address()` không cần GPS (`RT-02`, `RT-06`); cài đặt `resolveConnectedBandFromOnt` ngay sau Bước 2 phân tích mảng 2 chiều `wirelessInfoList` của `deviceinfo2.json` và ưu tiên `wlAssociatedDevice` của `dhcpclient.json`; sửa `ensureGatewayReadyBeforeStep7` bắt hẹp `IOException`, nhận diện `"fail authen"` và `"user exist"` qua `lastActiveSessionKey` mà không bao giờ gọi đúp login khi `numberLogin >= 2` (`RT-01`, `RT-05`); bọc `FlushTrackedRequestBody` (`sink.flush()`) + gỡ bọc `FlushTrackedIOException`, kiểm tra `stillOnOldWifi` trên cả 3 lần thử (`1..3`, với `allowActiveRebind = false`), chặn null `boundWifiNetwork` (`RT-08`) và giữ `suppressAutoBindAfterCompletion` qua `dismissExecutionSheet()` (`RT-07`).
  2. **Phase 2 (iOS Native Keep-Alive & Session Recovery Parity):** Đồng bộ `recoveryRetryDelayMs: 1200` (giữ `settlingDelayMs: 200`), `lastActiveSessionKey` an toàn `NSLock` không đệ quy, `resolveConnectedBandFromOnt` qua IP `en0`, `reloginAfterRadioBounce`, cập nhật đủ các điểm gọi `ensureGatewayReadyBeforeStep7`, xóa `NSURLErrorSecureConnectionFailed` khỏi `isPostFlushRadioDrop`, kiểm tra `isOnOntSubnet` + `awaitWifiIpv4Ready` và `suppressProbeAfterCompletion` trong `MainViewModel.swift`.
  3. **Phase 3 (Automated Tests, Simplification & Docs):** Cập nhật `OntMobileDriverTest.kt` (24 bài test), `OntMobileDriverTests.swift` (kèm `TestLockedBox` tương thích `@Sendable` Swift 5.9), `tests/test_ios_app_parity.py`, rút gọn mã lặp ở Step 3.S (`applyFinalConnectedBandWithRetries`), và cập nhật `README.md` + `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Decision
- Giữ nguyên duy nhất kết nối Wi-Fi đang kết nối từ Bước 1 đến hết Bước 7 và đối chiếu băng tần thực tế trực tiếp từ ONT ngay sau Bước 2 (`dhcpclient.json` + `deviceinfo2.json`) để loại bỏ hoàn toàn rủi ro rớt mạng khi nhảy sóng phụ hoặc nhận diện nhầm băng tần khi tắt GPS / quên gạt nút chọn tay trên iOS.
- Chỉ cho phép báo `SUCCESS` ở Bước 7 khi `wirelesscfg.json` (`action=Apply`) trả về `HTTP 200 {"status": "OK"}` hoặc đã flush đủ 100% POST body và xác nhận sóng cũ đã tắt trên cả 3 lần thử (`1..3`).

## Verification
- `ANDROID_HOME=/home/vtst/android-sdk ./gradlew --rerun-tasks testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL** (`36/36` unit tests passed + `app-debug.apk` built).
- `PYTHONPATH=. python3 -m pytest -v`: **92/92 passed**.
- Kongming Advisory Checkpoints (Phases 1, 2, 3), DocsManager & Mandatory Code Review: **9.8/10 (Auto-approved, GO)**.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
