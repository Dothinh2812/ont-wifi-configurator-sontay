---
phase: 3
title: "Phase 3: iOS Native Two-Connection Handover & Streaming Firmware Parity"
status: completed
priority: P1
effort: "3h"
dependencies: [1]
---

# Phase 3: iOS Native Two-Connection Handover & Streaming Firmware Parity

## Context Links
- Research & Red Team: [`research/researcher-02-ios-and-parity.md`](./research/researcher-02-ios-and-parity.md), [`reports/red-team-261105-mobile-dual-wifi-handover-firmware.md`](./reports/red-team-261105-mobile-dual-wifi-handover-firmware.md)
- Build & Packaging: `ios-app/project.yml`, `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`, `ios-app/scripts/package_unsigned_ipa.sh`
- Core & Data: `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`, `UnsafeSslSession.swift`, `Core/Wifi/IosWifiMonitor.swift`, `Core/Validation/OntInputValidator.swift`, `Data/Model/GlobalSettings.swift`, `Data/Local/AppDatabase.swift`
- SwiftUI Views & VM: `ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`, `UI/Screens/ConfigReviewView.swift`, `UI/Components/ExecutionProgressSheet.swift`, `UI/Components/WifiGuideSheet.swift`

## Overview
Bổ sung trọn vẹn khả năng **Chọn & Nạp Firmware mới** kết hợp **Quy trình 2 lần kết nối tự động** trên bản iOS Native (`ios-app/`), đảm bảo tương thích 100% với gói `ONT_WiFi_Configurator_Unsigned.ipa` cài qua Sideloadly / AltStore / TrollStore (`CODE_SIGNING_ALLOWED=NO`) và vượt qua mọi giới hạn đóng băng nền / khóa màn hình của iOS 16+.

## Key Insights
- **`[Red Team RT-04, RT-06]`:** Liên kết `NetworkExtension.framework` trong cả `ios-app/project.yml` và `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj` **không kèm `.entitlements` trả phí**: khi gọi best-effort `NEHotspotConfigurationManager.shared.apply`, dùng `NEHotspotConfiguration(ssid: ssid)` cho chế độ `open` (đặt `joinOnce = true` và gọi `removeConfiguration(forSSID:)` khi dọn dẹp để không lưu vĩnh viễn mạng Open) và `NEHotspotConfiguration(ssid:passphrase:isWEP: false)` cho các chế độ có mật khẩu; ghim SHA-256 chứng chỉ TLS lá từ Lần 1 sang Lần 2 trong `UnsafeSslSession.swift`.
- **`[Red Team RT-05]`:** Tuyệt đối **không gọi trực tiếp `IosWifiMonitor.inspectInterfaces()` bên trong `OntMobileDriver.swift`**, vì trên máy chạy CI `macos-14` Simulator (`build-ios-ipa.yml`), giao diện `en0` mang IP `10.x.x.x` sẽ làm treo toàn bộ XCTest 180s/test! Thay vào đó, tiêm closure `onSwitchToSecondaryWifi` vào `OntMobileDriver.swift` giống hệt Android; đồng thời lưu `private let protocolClasses: [AnyClass]` trong `UnsafeSslSession.swift`, dùng `uploadTask(withStreamedRequest:)` (`needNewBodyStream` đọc từ file tạm) và gọi `uploadSession.finishTasksAndInvalidate()` trong `defer` để `MockLuciURLProtocol` đọc được body multipart mà không rò rỉ bộ nhớ.
- **`[Red Team RT-07]`:**
  1. Khi KTV chuyển sang ứng dụng Cài đặt iOS quá 30s và màn hình tự khóa, `FileProtectionType.complete` sẽ thu hồi khóa giải mã làm sập SQLite & đọc file Firmware; cần dùng `FileProtectionType.completeUntilFirstUserAuthentication` cho `AppDatabase.swift`, thư mục `firmware_cache/` và file multipart tạm.
  2. Trong vòng chờ Handover Bước 7 ở `MainViewModel.swift`, khi ứng dụng đang ở nền (`isInBackground == true`) và đã ghi nhận máy vẫn đang ở `Band A`, **tạm dừng gửi `login.cmd`** cho tới khi IP `en0` thay đổi, `NWPathMonitor` báo đổi mạng, hoặc `onScenePhaseActive()` được gọi (đồng thời làm mới `beginBackgroundTask` trong `onScenePhaseActive()` để bảo vệ trọn vẹn Bước 9 tải Firmware `180s`).
  3. Mọi thao tác `UIPasteboard.general` phải thực hiện trên `@MainActor`, chỉ sao chép khi `secMode.requiresPassword && !joinedViaEntitlement` (hoặc khi KTV bấm nút), luôn truyền `[.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]` (giữ đúng chuẩn `test_ios_app_parity.py:132`).

## Requirements
- **Functional:**
  - **Trong `ios-app/project.yml` & `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj` `[Red Team RT-10]`:**
    - Thêm `NetworkExtension.framework` vào liên kết framework của target `ONTWiFiConfigurator` trên cả 2 tệp (không thêm `.entitlements`).
  - **Trong `AppDatabase.swift` (`:25, 53-56`) `[Red Team RT-07]`:**
    - Đổi mức bảo vệ file từ `FileProtectionType.complete` sang `FileProtectionType.completeUntilFirstUserAuthentication` để không lỗi `SQLITE_IOERR` nếu màn hình iPhone tạm khóa trong lúc đợi DFS CAC 60s.
  - **Trong `GlobalSettings.swift` (`:67-154`) & `OntInputValidator.swift` (`:22-363`) `[Red Team RT-02]`:**
    - Bổ sung `enableFirmwareUpdate: Bool = false`, `firmwareFilePath: String = ""`, `firmwareFileName: String = ""`, `firmwareFileSize: Int64 = 0`, `wifiHandoverTimeoutMs: Int = 180_000`, `gatewayPostHandoverTimeoutMs: Int = 45_000`, `postSecondWifiSettleTimeoutMs: Int = 60_000`, `firmwareUploadTimeoutMs: Int = 180_000` (dùng `decodeIfPresent` trong `init(from:)`).
    - Bổ sung `minFirmwareSizeBytes`, `maxFirmwareSizeBytes`, `isValidFirmwareExtension`, `sanitizeFirmwareFilename`, kiểm tra Firmware trong `validateBeforeRun`, và che JSON `"sessionKey"` + `nvramWlWpaPsk` / `nvramWlRadiusKey` / `nvramWlKey[1-4]?` trong `maskSensitiveCredentials`.
  - **Trong `UnsafeSslSession.swift` (`:16-173`) `[Red Team RT-04, RT-05, RT-07]`:**
    - Lưu `private let protocolClasses: [AnyClass]`, ghi nhận SHA-256 chứng chỉ lá `lastPeerCertSha256` và hỗ trợ `pinnedCertSha256: String?` để xác thực đúng ONT ở Lần 2.
    - Cài đặt `public func uploadMultipartFile(url: URL, headers: [String: String] = [:], fileFieldName: String = "file", fileURL: URL, safeFileName: String, formFields: [(String, String)], timeoutOverrideMs: Int = 180_000, onProgressPercent: (@Sendable (Int) -> Void)? = nil) async throws -> HttpResponsePayload`:
      - Ghép streaming file tạm theo khối `32 KB` (`FileProtectionType.completeUntilFirstUserAuthentication`), gắn `Content-Length` tường minh.
      - Tạo `URLSession` riêng có `config.protocolClasses = self.protocolClasses`, `allowsCellularAccess = false`, timeout `180s`, dùng `uploadTask(withStreamedRequest:)` (`urlSession(_:task:needNewBodyStream:)` mở `InputStream(url: tempMultipartURL)`), bọc trong `withTaskCancellationHandler` hủy `uploadTask` trước khi `defer` gọi `uploadSession.finishTasksAndInvalidate()` và xóa file tạm.
  - **Trong `IosWifiMonitor.swift` (`:1-155`) `[Red Team RT-04, RT-06]`:**
    - Bổ sung `public func tryJoinWifiIfEntitled(ssid: String, passphrase: String, securityMode: String) async -> Bool` (dùng `NEHotspotConfiguration(ssid: ssid)` với `joinOnce = true` khi `securityMode == "open"`, hoặc `NEHotspotConfiguration(ssid:passphrase:isWEP: false)` khi có mật khẩu; bắt mọi `NSError` thiếu entitlement trả về `false` an toàn) và `removeTemporaryOpenWifiIfNeeded(ssid:)`.
  - **Trong `OntMobileDriver.swift` (`:33-1306`) `[Red Team RT-01, RT-02, RT-05, RT-07, RT-08]`:**
    - Dùng `defer { probePauseMutex?.unlock() }` ngay sau `await probePauseMutex?.lock()` để loại bỏ hoàn toàn nguy cơ deadlock ở mọi nhánh return/throw (`[Red Team RT-08]`).
    - Mở rộng `MobileSetupResult` với các trường Firmware & băng tần phụ; nâng cấp `resolveConnectedBandFromOnt` trả về `(WifiBand, String?)` luôn đọc `deviceinfo2.json` lấy `baseMacAddress` (cắt `\r\n`) (`[Red Team RT-02]`).
    - Tách các tham số constructor `wlRestartPollIntervalMs: Int = 1000`, `maxWlRestartPolls: Int = 30`, `userExistPollIntervalMs: Int = 3000`, và closure `onSwitchToSecondaryWifi: (@Sendable (_ ssid: String, _ pass: String, _ band: WifiBand, _ secMode: String, _ expectedOntBaseMac: String?, _ timeoutMs: Int, _ onDetail: @Sendable (String) -> Void) async throws -> String?)? = nil` (`[Red Team RT-05]`).
    - Bỏ kiểm tra `!isAppInBackground()` khi `cfgSavedToNvram == true` tại `configureWifiBand` (`:686`) và kiểm tra thẳng `didFlushFullRequestBody == true` (bất kể `isAppInBackground()`) trong `updateFirmware` để cuộc gọi đến hay khóa màn hình không bao giờ làm bắn `logout.cmd` vào ONT đang ghi flash (`[Red Team RT-05, RT-07]`).
    - Cài đặt `authenticateSecondConnection` (chỉ gửi `settings.adminNewPass`), `ensureGatewayReadyOnSecondaryBand` (thay thế hoàn toàn `ensureGatewayReadyBeforeStep7` / `reloginAfterRadioBounce` sau Bước 2 `[Red Team RT-01]`), `verifySecondConnectionBandAndMac` (bắt buộc `wlAssociatedDevice == expectedBand` & khớp `baseMacAddress`, không dùng fallback `nvramWlSsid` ở Bước 7 `[Red Team RT-02]`), `readConfiguredBandSsidFromNvram` (`page=ssid.asp&wl_unit=\(unit)&wl_bssid=0&sessionKey=\(encKey)`), `updateFirmware(...)` và trình tự 8/9 bước trong `executeMobileSetup`.
  - **Trong `MainViewModel.swift`, `ConfigReviewView.swift`, `ExecutionProgressSheet.swift`, `WifiGuideSheet.swift` `[Red Team RT-07, RT-10]`:**
    - Trong `MainViewModel.swift`: giữ `startAccessingSecurityScopedResource()` xuyên suốt quá trình copy streaming trong `importFirmwareFile(from:)` vào `firmware_cache/` (`completeUntilFirstUserAuthentication`); làm mới `beginBackgroundTask` trong `onScenePhaseActive()` nếu `isRunningSetup && bgTaskId == .invalid`; cài đặt closure `onSwitchToSecondaryWifi` chạy theo hạn chót `Date()` (`180s`), thử `tryJoinWifiIfEntitled`, nếu trả về `false` thì trên `@MainActor` bật thẻ hướng dẫn Bước 7 + sao chép mật khẩu với `[.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]` (khi `requiresPassword`), tạm dừng bắn HTTP dưới nền khi IP `en0` chưa đổi và đánh thức ngay lập tức qua `onScenePhaseActive()` / `NWPathMonitor`.
    - Cập nhật `ConfigReviewView.swift` (Section 4 chọn Firmware `.signed`/`.bin`/`.img`), `ExecutionProgressSheet.swift` (thẻ Handover Bước 7 + tiến trình tải Firmware + đếm ngược chờ ONT reboot `150s`), và cập nhật hướng dẫn 2 lần kết nối trong `WifiGuideSheet.swift:120` (`[Red Team RT-10]`).

## Related Code Files
- Modify: `ios-app/project.yml`
- Modify: `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`
- Modify: `ios-app/ONTWiFiConfigurator/Data/Local/AppDatabase.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Data/Model/GlobalSettings.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Validation/OntInputValidator.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Driver/UnsafeSslSession.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Wifi/IosWifiMonitor.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Components/ExecutionProgressSheet.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Components/WifiGuideSheet.swift`

## Implementation Steps
1. Cập nhật đồng bộ `ios-app/project.yml` và `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj` thêm `NetworkExtension.framework` (không thêm `.entitlements`).
2. Cập nhật `AppDatabase.swift` (`completeUntilFirstUserAuthentication`), `GlobalSettings.swift` và `OntInputValidator.swift`.
3. Nâng cấp `UnsafeSslSession.swift` (lưu `protocolClasses`, ghim SHA-256 leaf cert, `uploadMultipartFile` qua `uploadTask(withStreamedRequest:)` + `withTaskCancellationHandler` + `finishTasksAndInvalidate()`) và `IosWifiMonitor.swift` (`tryJoinWifiIfEntitled`).
4. Cập nhật `OntMobileDriver.swift`: `defer` unlock mutex, `resolveConnectedBandFromOnt` trả kèm `baseMacAddress`, tách tham số poll, tiêm `onSwitchToSecondaryWifi`, cài đặt `authenticateSecondConnection`, `ensureGatewayReadyOnSecondaryBand`, `verifySecondConnectionBandAndMac`, `updateFirmware`, và luồng 8/9 bước.
5. Cập nhật `MainViewModel.swift`, `ConfigReviewView.swift`, `ExecutionProgressSheet.swift` và `WifiGuideSheet.swift`.

## Todo List
- [x] Liên kết `NetworkExtension.framework` trong `project.yml` và `project.pbxproj` (không dùng `.entitlements`)
- [x] Cập nhật `AppDatabase.swift`, `GlobalSettings.swift`, `OntInputValidator.swift` và `UnsafeSslSession.swift` (TLS cert pinning + streamed `uploadMultipartFile`)
- [x] Cài đặt `tryJoinWifiIfEntitled` (`IosWifiMonitor.swift`), `updateFirmware` và luồng 2 lần kết nối an toàn trong `OntMobileDriver.swift`
- [x] Cập nhật `MainViewModel.swift`, `ConfigReviewView.swift`, `ExecutionProgressSheet.swift` và `WifiGuideSheet.swift`

## Success Criteria
- [x] Gói `ONT_WiFi_Configurator_Unsigned.ipa` biên dịch sạch với `CODE_SIGNING_ALLOWED=NO`, không chứa `.entitlements` trả phí, và chạy XCTest trên `macos-14` Simulator trong vài giây mà không phụ thuộc vào IP `en0` của máy host.
- [x] Vòng chờ Handover trên iOS không làm kẹt phiên `"user exist"` dưới nền, làm mới `beginBackgroundTask` khi quay lại app, giữ `.localOnly: true` (`120s`) cho `UIPasteboard` trên `@MainActor`, và không bao giờ bắn `logout.cmd` vào ONT đang ghi flash.

## Risk Assessment
- **Rủi ro — Màn hình iPhone tự khóa khi KTV đứng chờ kênh 5GHz DFS CAC trong Cài đặt iOS:**
  - *Observable signal:* Ứng dụng ở nền > 30s và iOS kích hoạt khóa màn hình.
  - *Pre-decided response:* Dùng `FileProtectionType.completeUntilFirstUserAuthentication` cho SQLite và cache Firmware, tạm dừng gọi HTTP khi `en0` chưa đổi IP, và đánh thức tức thì + xin lại `beginBackgroundTask` ngay khi KTV mở lại app (`onScenePhaseActive`).
