---
phase: 4
title: "Phase 4: Unit Tests, Cross-Platform Parity Suite & Field Debugging Documentation"
status: pending
priority: P1
effort: "1h"
dependencies: [1, 2, 3]
---

# Phase 4: Unit Tests, Cross-Platform Parity Suite & Field Debugging Documentation

## Context Links
- Depends on: [`phase-01-start.md`](./phase-01-start.md), [`phase-02-android-viewmodel-and-live-log-ui.md`](./phase-02-android-viewmodel-and-live-log-ui.md), [`phase-03-ios-reconnect-backoff-and-log-parity.md`](./phase-03-ios-reconnect-backoff-and-log-parity.md), [`reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md`](./reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md)
- Target Test & Doc Files:
  - `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
  - `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`
  - `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
  - `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift`
  - `tests/test_ios_app_parity.py`
  - `README.md`
  - `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Overview
Cập nhật toàn bộ các điểm khởi tạo và assertion timeout cũ trong bộ test Android & iOS (`[Red Team RT-05]`), bổ sung các kịch bản kiểm thử hành vi mới (lịch retry giãn cách `5s -> 10s -> 15s -> 20s`, thoát tức thì khi rớt mạng `isNetworkTransportAlive == false`, hội tụ `verifySecondConnectionBandAndMac`, rethrow TLS pin mismatch trong `postOptionalSessionJson`, đồng bộ hóa xoay vòng & lọc mật khẩu URL-encoded/JSON không hỏng `"admin"` của `AppDiagnosticLogger`), mở rộng `tests/test_ios_app_parity.py` và cập nhật tài liệu vận hành thực địa.

## Key Insights
- **Kiểm kê đầy đủ 100% điểm khởi tạo và assertion cần cập nhật (`Contract Verifier` `[Red Team RT-05]`):**
  1. `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt:428-430`: cập nhật assertion mặc định từ `120_000L / 45_000L / 60_000L` sang `180_000L / 60_000L / 90_000L`.
  2. `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift:294-296, 306`: cập nhật assertion mặc định từ `180_000 / 45_000 / 60_000` sang `240_000 / 60_000 / 90_000`.
  3. `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt:86-88, 104`: nâng `testSettings().wifiHandoverTimeoutMs = 400L`, `gatewayPostHandoverTimeoutMs = 200L` và truyền `postDhcpGatewaySettleMs = 5L`, `handoverRetryInitialBackoffMs = 15L`, `handoverRetryMaxBackoffMs = 40L`, `bandVerifyPollTimeoutMs = 60L`, `bandVerifyPollIntervalMs = 15L` vào `createTestDriver` (`:104`).
  4. `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`: nâng `makeTestSettings().wifiHandoverTimeoutMs = 400`, `gatewayPostHandoverTimeoutMs = 200` (`:187`) và truyền tường minh `postDhcpGatewaySettleMs: 5`, `handoverRetryInitialBackoffMs: 15`, `handoverRetryMaxBackoffMs: 40`, `bandVerifyPollTimeoutMs: 60`, `bandVerifyPollIntervalMs: 15` tại **TẤT CẢ 6 điểm khởi tạo `OntMobileDriver(...)`** (`:222, 675, 799, 899, 958, 1160`) để không còn bất kỳ bài XCTest nào giữ mặc định `2.5s`/`15s` gây hết hạn sớm hoặc làm chậm GitHub Actions CI!

## Requirements

### Functional
1. **Android Unit Tests (`OntMobileDriverTest.kt` & `ExcelAndValidatorTest.kt`):**
   - Trong `ExcelAndValidatorTest.kt`:
     - Cập nhật kiểm tra ngưỡng `GlobalSettings()` (`180_000L`, `60_000L`, `90_000L`, `180_000L`).
     - Thêm kiểm thử `OntWifiConnector.computeReconnectBackoffMs` (`5_000L -> 10_000L -> 15_000L -> 20_000L -> 20_000L`) và `hasAvailableProcessScanQuota`.
     - Thêm kiểm thử `OntInputValidator.maskSensitiveCredentials` & `AppDiagnosticLogger` (`[Red Team RT-03, RT-06]`): kiểm chứng che sạch mật khẩu có dấu cách trong JSON (`"pass": "Manh Ngoc@26"`), mật khẩu URL-encoded (`Passwd2%40`), xoay vòng đồng bộ `ont_debug.log` $\rightarrow$ `ont_debug.prev.log`, che giữa MAC/BSSID (`AA:BB:CC:**:**:D8`) khi xuất `.txt`, và **giữ nguyên không làm hỏng** chuỗi `"user=admin"` / `"tài khoản admin"`.
     - Thêm kiểm thử `MainViewModel.cleanupStaleExportFiles` xóa sạch cả `.xlsx` và `ONT_Debug_Log_*.txt`.
   - Trong `OntMobileDriverTest.kt`:
     - Kịch bản **Bước 7 rớt kết nối (`isNetworkTransportAlive == false`) & hội tụ `wlAssociatedDevice` qua Retry Giãn Cách:** lượt 1 của Bước 7 bị rớt mạng trong lúc chờ hoặc `deviceinfo2.json` / `wlAssociatedDevice` bận ở nhịp đầu $\rightarrow$ xác minh Driver thoát sớm khỏi socket chết, tự gọi lại `onSwitchToSecondaryWifi` ở lượt 2 với `outerAttempt == 2` & giữ nguyên `minScanTimestampMicros`, hoàn tất Bước 8 & Bước 9 Firmware và ghi đầy đủ dòng log `HANDOVER` / `DRIVER` đã che mật khẩu.
     - Kịch bản **Chặn đứng lỗi chí mạng không retry (`[Red Team RT-04]`):** xác minh khi `postOptionalSessionJson` gặp lỗi lệch chứng chỉ TLS SHA-256 hoặc khi `actualBaseMac` khác rỗng và khác `expectedOntBaseMac` (hoặc sai `adminNewPass`), Bước 7 dừng ngay ở lượt 1, không gọi lặp `onSwitchToSecondaryWifi`.

2. **iOS XCTests (`OntMobileDriverTests.swift` & `ExcelAndValidatorTests.swift`) & Python Parity (`tests/test_ios_app_parity.py`):**
   - Cập nhật **tất cả 6 điểm khởi tạo** trong `OntMobileDriverTests.swift` (`:222, 675, 799, 899, 958, 1160`) + `makeTestSettings()` (`:187`), và bổ sung bài test hội tụ `verifySecondConnectionBandAndMac` + rethrow TLS pin mismatch trong `postOptionalSessionJson`.
   - Cập nhật `ExcelAndValidatorTests.swift` (`240_000`, `60_000`, `90_000`), kiểm thử `IosWifiMonitor.computeReconnectBackoffMs` và `AppDiagnosticLogger` xoay vòng + lọc mật khẩu URL-encoded.
   - Mở rộng `tests/test_ios_app_parity.py` với `test_phase7_reconnect_readiness_spaced_backoff_and_diagnostic_logger_parity()`:
     - Xác minh đối xứng `AppDiagnosticLogger`, `DiagnosticLogDialog` / `DiagnosticLogSheet`, `computeReconnectBackoffMs`, `postDhcpGatewaySettleMs`, `bandVerifyPollTimeoutMs`, `isNetworkTransportAlive`, `StillOnPrimaryBandException` / `StillOnPrimaryBandError`.
     - Xác minh `AppDiagnosticLogger.swift` & `DiagnosticLogSheet.swift` được đăng ký đầy đủ trong `project.pbxproj`, `res/xml/file_paths.xml` không chứa `<files-path>`, và cả 6 điểm `OntMobileDriver(` trong `OntMobileDriverTests.swift` đều có `postDhcpGatewaySettleMs`.

3. **Tài liệu vận hành (`README.md` & `HUONG_DAN_SU_DUNG_TOOL_ONT.md`):**
   - Cập nhật thông số thời gian chờ mới (`180s` Android / `240s` iOS, lắng đầu `8s`, ổn định sau khi thấy sóng `8s`, chờ DHCP `15s`, lịch retry giãn cách `5s -> 10s -> 15s -> 20s`).
   - Bổ sung hướng dẫn **Xem trực tiếp & Chia sẻ Nhật ký Debug (`ONT_Debug_Log_*.txt`)** qua Zalo/Telegram để phục vụ chẩn đoán lỗi thực địa.

## Related Code Files
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`
- Modify: `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- Modify: `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift`
- Modify: `tests/test_ios_app_parity.py`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Cập nhật `ExcelAndValidatorTest.kt` & `OntMobileDriverTest.kt` và chạy `./gradlew testDebugUnitTest assembleDebug` xác nhận hoàn tất `< 15s` và PASS 100%.
2. Cập nhật cả 6 điểm khởi tạo trong `OntMobileDriverTests.swift`, cập nhật `ExcelAndValidatorTests.swift` và `tests/test_ios_app_parity.py`, chạy `PYTHONPATH=. pytest -v` xác nhận PASS 100%.
3. Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Todo List
- [x] Cập nhật & bổ sung Unit Tests Android (`ExcelAndValidatorTest.kt`, `OntMobileDriverTest.kt`) cho timeout mới, retry giãn cách, `isNetworkTransportAlive`, TLS rethrow và `AppDiagnosticLogger`
- [x] Cập nhật toàn bộ 6 điểm khởi tạo trong `OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift` và bổ sung `test_phase7_...` trong `tests/test_ios_app_parity.py`
- [x] Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` với thông số chờ/retry mới và hướng dẫn xuất Log Debug

## Success Criteria
- [x] `./gradlew testDebugUnitTest assembleDebug` trong `android-app/` chạy `< 15s`, 0 lỗi biên dịch, PASS 100% test.
- [x] `PYTHONPATH=. pytest -v` tại thư mục gốc PASS 100% trên toàn bộ suite kiểm thử.
- [x] Tài liệu hướng dẫn rõ ràng, khớp chính xác các hằng số và nút bấm trên giao diện.

## Risk Assessment
- **Rủi ro:** Bài test xoay vòng file log để lại thư mục tạm sau khi chạy.
  - *Giải pháp định sẵn:* Dùng thư mục tạm riêng biệt cho từng bài test, gọi `logger.close()` và xóa sạch trong `finally` / `defer`.

## Security Considerations
- Không đưa bất kỳ mật khẩu thật hay dữ liệu PII khách hàng thật vào fixture test hoặc ví dụ log trong tài liệu.

## Next Steps
- Sẵn sàng bàn giao sang `/ak:cook` để triển khai mã nguồn.
