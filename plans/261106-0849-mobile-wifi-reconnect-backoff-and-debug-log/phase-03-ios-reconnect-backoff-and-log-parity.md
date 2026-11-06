---
phase: 3
title: "Phase 3: iOS Native Readiness Budgets, Spaced-Backoff Handover & Diagnostic Log Parity"
status: pending
priority: P1
effort: "1.5h"
dependencies: [1]
---

# Phase 3: iOS Native Readiness Budgets, Spaced-Backoff Handover & Diagnostic Log Parity

## Context Links
- Depends on: [`phase-01-start.md`](./phase-01-start.md), [`reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md`](./reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md)
- Target iOS Files:
  - `ios-app/ONTWiFiConfigurator/Core/Logging/AppDiagnosticLogger.swift` (new)
  - `ios-app/ONTWiFiConfigurator/UI/Components/DiagnosticLogSheet.swift` (new)
  - `ios-app/ONTWiFiConfigurator/Data/Model/GlobalSettings.swift`
  - `ios-app/ONTWiFiConfigurator/Core/Validation/OntInputValidator.swift`
  - `ios-app/ONTWiFiConfigurator/Core/Wifi/IosWifiMonitor.swift`
  - `ios-app/ONTWiFiConfigurator/Core/Driver/UnsafeSslSession.swift`
  - `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
  - `ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`
  - `ios-app/ONTWiFiConfigurator/UI/Components/ExecutionProgressSheet.swift`
  - `ios-app/ONTWiFiConfigurator/UI/Screens/SubscriberListView.swift`
  - `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`
  - `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`

## Overview
Đồng bộ trọn vẹn lên bản iOS Native (`iOS 16.0+`, pure Swift, giữ nguyên khả năng cài `.ipa` miễn phí qua Sideloadly/AltStore không `.entitlements` trả phí): nâng ngân sách chờ sẵn sàng ONT (`240s` handover wall-clock, `8s` lắng đầu, `15s` DHCP, `60s` Gateway, `90s` trước Firmware), sửa triệt để lỗi trả sớm IP cũ của `Band A` trên Sideloadly & phân tách luồng `NEHotspotConfigurationManager` (`[Red Team RT-01]`), bổ sung retry giãn cách lũy tiến (`5s -> 10s -> 15s -> 20s`), sửa hội tụ `verifySecondConnectionBandAndMac` + rethrow TLS pin mismatch (`[Red Team RT-04]`), và tích hợp `AppDiagnosticLogger.swift` + `DiagnosticLogSheet.swift` không xung đột `.sheet` (`[Red Team RT-08]`).

## Key Insights
- **Kiểm kê toàn bộ điểm khởi tạo trên iOS (`Contract Verifier` `[Red Team RT-05]`):**
  - `OntMobileDriver(...)` có **3 điểm khởi tạo** trong mã chính (`MainViewModel.swift:127`, `:388`, `:661`) và **6 điểm khởi tạo** trong test (`OntMobileDriverTests.swift:222, 675, 799, 899, 958, 1160` — cả 6 điểm sẽ được gắn tham số test nhanh ở Phase 4). Giữ nguyên `settlingDelayMs: 200` tại `MainViewModel.swift:664` và không gọi `IosWifiMonitor` trực tiếp trong `OntMobileDriver.swift` (`tests/test_ios_app_parity.py:117, 375, 379`).
- **Sửa lỗi trả sớm IP `Band A` & Phân loại chuẩn `NEHotspotConfigurationError` (`[Red Team RT-01]`):**
  - Trên bản Sideloadly không có paid entitlement, `NEHotspotConfigurationManager.shared.apply` trả về `NEHotspotConfigurationError.internal` (`code == 8`) trong **`< 40ms`** mà không hiện hộp thoại hệ thống; ngược lại trên bản có quyền (TrollStore / Developer), khi người dùng bấm Join hoặc `wifid` đang ghép nối mà ONT chưa sẵn sàng thì `apply` mất **`>= 40ms`** (hoặc trả `alreadyAssociated` `code == 13` khi L2 đã nối xong).
  - Trong `MainViewModel.swift:735-743` hiện tại, vì `en0` vẫn đang giữ IP `192.168.1.x` của `Band A` (`snap.isOnOntSubnet == true`), nhánh `if attemptIdx == 1` trả về ngay lập tức ở `0ms` làm mất hoàn toàn vòng retry tự động VÀ khiến bản Sideloadly liên tục đăng nhập nhầm trên `Band A` giữ khóa phiên! Phải bỏ hẳn việc trả sớm ở `attemptIdx == 1` khi chưa đổi mạng: nếu `.transientFailure` thì lặp retry `tryJoinWifiDetailed` giãn cách `6s -> 12s -> 18s` ngay trong `onSwitchToSecondaryWifi`; nếu `.entitlementUnavailable` thì hiện `HandoverPromptState` và chỉ trả về khi `ipChanged || wakeChanged` (từ `NWPathMonitor` / quay lại từ Cài đặt iOS) hoặc sau mỗi `15s` kiểm tra nhẹ!
  - Đồng thời, ngay khi `apply` trả `error == nil` hoặc `code == 13` ở chế độ Open (`"open"`), phải ghi nhận ngay `onOpenProfileApplied` *trước khi* chờ DHCP `15s` để `defer` luôn dọn sạch cấu hình Open tạm thời!

## Requirements

### Functional
1. **`AppDiagnosticLogger.swift` (tạo mới tại `Core/Logging/AppDiagnosticLogger.swift`) `[Red Team RT-03, RT-06, RT-08]`:**
   - Định nghĩa `public enum LogLevel: String, Codable, Sendable { case info = "INFO"; case warn = "WARN"; case error = "ERROR" }` và `public struct DiagnosticLogEntry: Identifiable, Equatable, Sendable`.
   - Thực hiện **2 lớp che mật khẩu đồng bộ ngay trên luồng gọi `log(...)`** (gồm cả `raw`, `urlEncodeFormValue`, `ontEncodeQuery`, loại trừ `"admin"`, yêu cầu `length >= 6` cho chuỗi không khóa `[Red Team RT-03]`) trước khi đưa vào ring buffer `1_500` dòng và hàng đợi tuần tự `ioQueue = DispatchQueue(label: "vn.vnpt.ontconfig.diaglogger", qos: .utility)`.
   - Ghi xoay vòng `ont_debug.log` & `ont_debug.prev.log` (`512 KB`/file) tại `Application Support/ONTWiFiConfigurator/logs/` với `FileProtectionType.completeUntilFirstUserAuthentication` và `isExcludedFromBackup = true`; đồng bộ hóa `exportCombinedLogText(...)`, `flushForTest()` và `clearLogs()` qua `ioQueue.sync { ... }` (`[Red Team RT-06]`).
   - Gộp nhịp thông báo lên UI tối đa mỗi `200ms` qua `DispatchQueue.main.asyncAfter` có cờ chống trùng để đảm bảo thứ tự FIFO và không gây giật SwiftUI (`[Red Team RT-08]`).

2. **`GlobalSettings.swift` (`:67-194`) & `OntInputValidator.swift` (`:383-430`):**
   - Nâng mặc định trong `GlobalSettings.init` và `init(from decoder: Decoder)` (`decodeIfPresent`):
     - `wifiHandoverTimeoutMs: Int = 240_000` (`240s` / 4 phút wall-clock)
     - `gatewayPostHandoverTimeoutMs: Int = 60_000` (`60s`)
     - `postSecondWifiSettleTimeoutMs: Int = 90_000` (`90s`)
   - Mở rộng `queryKeys` trong `OntInputValidator.maskSensitiveCredentials` thêm `"passphrase"`, `"wpa_psk"`, `"preSharedKey"`.

3. **`IosWifiMonitor.swift` (`:47-210`) `[Red Team RT-01]`:**
   - Định nghĩa `public enum HotspotJoinResult: Equatable, Sendable { case joined(WifiInterfaceSnapshot); case entitlementUnavailable(String); case transientFailure(String) }`.
   - Cài đặt `public func tryJoinWifiDetailed(ssid: String, passphrase: String, securityMode: String, dhcpWaitMs: Int = 15_000, onOpenProfileApplied: (@Sendable () -> Void)? = nil) async -> HotspotJoinResult`:
     - Đo thời gian thực thi `apply`: nếu `error == nil` hoặc `(nsErr.domain == NEHotspotConfigurationErrorDomain && nsErr.code == NEHotspotConfigurationError.alreadyAssociated.rawValue)` (`13`), gọi ngay `if isOpen { onOpenProfileApplied?() }`, chờ `awaitWifiIpv4Ready(maxWaitMs: dhcpWaitMs)` (`15s`) + kiểm tra giữ IP `en0` ổn định `1_200 ms`, trả về `.joined(snapshot)` nếu `isOnOntSubnet` hoặc `.transientFailure` nếu chưa cấp xong DHCP.
     - Nếu `nsErr.code == NEHotspotConfigurationError.internal.rawValue` (`8`) và thời gian gọi `< 40ms` (không có thay đổi IP `en0`) $\rightarrow$ trả về `.entitlementUnavailable`; các lỗi còn lại $\rightarrow$ trả về `.transientFailure`.
   - Giữ hàm `public func tryJoinWifiIfEntitled(...) async -> Bool` ủy quyền sang `tryJoinWifiDetailed` (`tests/test_ios_app_parity.py:369`), và thêm `public static func computeReconnectBackoffMs(attempt: Int, initialBackoffMs: Int = 5_000, maxBackoffMs: Int = 20_000) -> Int`.

4. **`OntMobileDriver.swift` (`:99-1894`) `[Red Team RT-01, RT-04]`:**
   - Bổ sung tham số `init` có mặc định: `postDhcpGatewaySettleMs: Int = 2500`, `handoverRetryInitialBackoffMs: Int = 5000`, `handoverRetryMaxBackoffMs: Int = 20000`, `bandVerifyPollTimeoutMs: Int = 15000`, `bandVerifyPollIntervalMs: Int = 2000`, `isNetworkTransportAlive: (@Sendable () -> Bool)? = nil`, `onLog: (@Sendable (LogLevel, String, String) -> Void)? = nil`.
   - Cập nhật `_lastPeerCertSha256` trong `UnsafeSslSession` ở mỗi lần bắt tay TLS (giữ `pinnedCertSha256` bất biến); thử đọc `deviceinfo2.json` tối đa 3 lần ở Step 2c; chuyển `postOptionalSessionJson` sang `async throws` (hoặc kiểm tra lỗi) để **rethrow ngay `OntDriverException` khi gặp `isTlsPinMismatch(error)`** (`[Red Team RT-04]`).
   - Kiểm tra `isNetworkTransportAlive?() == false` trong `authenticateSecondConnection` (lưu `lastNetworkError` để ném `OntNetworkError` khi rớt mạng liên tiếp thay vì `OntUserExistError` `[Red Team RT-04]`).
   - Nâng cấp `verifySecondConnectionBandAndMac` sang poll hội tụ `bandVerifyPollTimeoutMs` (`15s`, mỗi `2s`): ném ngay `OntDriverException` không retry khi `!actualBaseMac.isEmpty && actualBaseMac.caseInsensitiveCompare(expectedMac) != .orderedSame`; ném `StillOnPrimaryBandError` ngay sau **2 lần đọc liên tiếp** thấy vẫn ở `Band A` (`~2s`, không giữ phiên suốt `15s` `[Red Team RT-01]`); giới hạn tối đa 2 lượt retry ngoài nếu `deviceinfo2.json` hoàn toàn không phản hồi.
   - Trong vòng lặp Bước 7 (`:1630-1726`): kẹp `postDhcpGatewaySettleMs` theo thời gian còn lại của `handoverDeadline`; với `StillOnPrimaryBandError` gọi `await logout()` và chỉ nghỉ ngắn `min(max(recoveryRetryDelayMs, 25), 1500)` (`~1.2s–1.5s`), còn với `OntNetworkError` thực sự thì áp dụng giãn cách lũy tiến `IosWifiMonitor.computeReconnectBackoffMs(attempt: outerAttempt, initialBackoffMs: handoverRetryInitialBackoffMs, maxBackoffMs: handoverRetryMaxBackoffMs)` (`5s -> 10s -> 15s -> 20s` `[Red Team RT-01]`).

5. **`MainViewModel.swift`, `ExecutionProgressSheet.swift`, `DiagnosticLogSheet.swift`, `SubscriberListView.swift`, `ConfigReviewView.swift` & `project.pbxproj` `[Red Team RT-01, RT-08, RT-09]`:**
   - Trong `MainViewModel.swift`:
     - Tích hợp `diagnosticLogger: AppDiagnosticLogger`, cập nhật `@Published public var diagnosticLogs: [DiagnosticLogEntry] = []` qua callback gộp nhịp `200ms`, đăng ký đầy đủ mật khẩu phiên chạy và chỉ xóa sau khi `catch` đã ghi xong log lỗi.
     - Trong `onSwitchToSecondaryWifi`: ở lượt 1 chờ lắng đầu `8.0s`; gọi `tryJoinWifiDetailed(..., onOpenProfileApplied: { joinedOpenSsidBox.value = targetSsid })`. Nếu `.transientFailure` (bản có quyền), thực hiện vòng retry `tryJoinWifiDetailed` giãn cách `6s -> 12s -> 18s` khi `!bgBox.value` kết hợp theo dõi `ipChanged || wakeChanged`; chỉ khi `.entitlementUnavailable` mới bật `HandoverPromptState` + chép mật khẩu vào `UIPasteboard` (`.localOnly: true`, `120s`) và chờ `ipChanged || wakeChanged` (hoặc `>= 15s` ở tiền cảnh), bỏ hẳn nhánh trả sớm `attemptIdx == 1` trên IP cũ của `Band A` (`[Red Team RT-01]`).
     - Bổ sung `exportDiagnosticLogToTempFile() throws -> URL`, `copyDiagnosticLogsToPasteboard()` với `[.localOnly: true, .expirationDate: Date().addingTimeInterval(180)]`, và dọn `ONT_Debug_Log_*.txt` trong cả `cleanupStaleExportFiles()` lẫn `completionWithItemsHandler` của `UIActivityViewController` (`[Red Team RT-09]`).
   - Tạo `DiagnosticLogSheet.swift` dùng `LazyVStack`; chỉ gắn `.sheet(isPresented: $viewModel.showDiagnosticLogSheet)` tại **1 điểm gốc duy nhất** trên `SubscriberListView.swift` và dùng `@State private var showFullLogSheet = false` riêng bên trong `ExecutionProgressSheet.swift` (kèm khung `LazyVStack` 200 dòng cuối tự bung mở khi lỗi) để không bao giờ đụng độ 2 `.sheet` trên SwiftUI (`[Red Team RT-08]`).
   - Đăng ký `AppDiagnosticLogger.swift` & `DiagnosticLogSheet.swift` vào `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`.

## Related Code Files
- Create: `ios-app/ONTWiFiConfigurator/Core/Logging/AppDiagnosticLogger.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/Components/DiagnosticLogSheet.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Data/Model/GlobalSettings.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Validation/OntInputValidator.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Wifi/IosWifiMonitor.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Driver/UnsafeSslSession.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Components/ExecutionProgressSheet.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Screens/SubscriberListView.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`
- Modify: `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`

## Implementation Steps
1. Tạo `AppDiagnosticLogger.swift` với lọc mật khẩu 2 lớp đồng bộ trên luồng gọi, đồng bộ hóa `ioQueue.sync` cho `exportCombinedLogText()` / `clearLogs()` và gộp nhịp UI `200ms`.
2. Cập nhật `GlobalSettings.swift` (`240s`, `60s`, `90s`), `OntInputValidator.swift`, `UnsafeSslSession.swift` (cập nhật `_lastPeerCertSha256` mỗi handshake) và `IosWifiMonitor.swift` (`tryJoinWifiDetailed` xử lý `code == 13` / `< 40ms code == 8` / Open cleanup + `computeReconnectBackoffMs`).
3. Nâng cấp `OntMobileDriver.swift`: rethrow TLS pin mismatch trong `postOptionalSessionJson`, kiểm tra `isNetworkTransportAlive`, poll hội tụ `verifySecondConnectionBandAndMac` (thoát `StillOnPrimaryBandError` sau 2 nhịp) và retry giãn cách Bước 7.
4. Cập nhật `MainViewModel.swift` (bỏ trả sớm `Band A` ở `0ms`, giữ `settlingDelayMs: 200`), tạo `DiagnosticLogSheet.swift`, cập nhật `ExecutionProgressSheet.swift`, `SubscriberListView.swift`, `ConfigReviewView.swift` và `project.pbxproj`.

## Todo List
- [x] Tạo `AppDiagnosticLogger.swift` và cập nhật `GlobalSettings.swift` (`240s`, `60s`, `90s`), `OntInputValidator.swift`, `UnsafeSslSession.swift`
- [x] Nâng cấp `IosWifiMonitor.swift` (`tryJoinWifiDetailed`, `computeReconnectBackoffMs`) và `OntMobileDriver.swift` (rethrow TLS mismatch + poll hội tụ + retry giãn cách Bước 7)
- [x] Cập nhật `MainViewModel.swift` (tách rõ luồng auto-join retry và Sideloadly không kẹt `Band A`), tạo `DiagnosticLogSheet.swift` và cập nhật các màn hình SwiftUI không đụng độ `.sheet`
- [x] Đăng ký `AppDiagnosticLogger.swift` & `DiagnosticLogSheet.swift` vào `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`

## Success Criteria
- [x] Trên cả bản Sideloadly (chuyển tay trong Cài đặt iOS) lẫn bản có entitlement, Bước 7 không khóa phiên trên `Band A` cũ, không kẹt chờ mù khi KTV vừa đổi Wi-Fi xong quay lại app, và tự động retry giãn cách khi mạng chập chờn.
- [x] Giữ nguyên 100% khả năng đóng gói `.ipa` không ký (`CODE_SIGNING_ALLOWED=NO`) và mở xem/chia sẻ Log mượt mà ở mọi màn hình.

## Risk Assessment
- **Rủi ro:** Đụng độ 2 `.sheet` song song trên SwiftUI khi đang mở `ExecutionProgressSheet`.
  - *Giải pháp định sẵn:* `ExecutionProgressSheet` quản lý `@State private var showFullLogSheet = false` cục bộ của chính nó, trong khi `viewModel.showDiagnosticLogSheet` chỉ gắn duy nhất trên `SubscriberListView`.

## Security Considerations
- Pasteboard khi chép log luôn gắn `.localOnly: true` và hết hạn sau `180s`; mật khẩu khách hàng không bị chép tự động vào Pasteboard khi thiết bị đang chạy auto-join có quyền (`[Red Team RT-01, RT-09]`).

## Next Steps
- Chuyển sang Phase 4 để cập nhật đầy đủ toàn bộ các điểm khởi tạo test, viết Unit Tests mới và cập nhật tài liệu hướng dẫn.
