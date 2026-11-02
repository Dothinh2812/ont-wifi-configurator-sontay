---
phase: 3
title: "Phase 3: iOS Parity, Automated Test Suites & Documentation"
status: pending
priority: P1
effort: "1h"
dependencies: [1, 2]
---

# Phase 3: iOS Parity, Automated Test Suites & Documentation

## Overview
Đồng bộ hoàn toàn tính năng chọn loại bảo mật Wi-Fi sang ứng dụng iOS Native (`ios-app/`), cập nhật bộ kiểm thử tự động (`OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift`, `tests/test_ios_app_parity.py`) và tài liệu hướng dẫn sử dụng (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`).

## Requirements
- **Functional:**
  - Định nghĩa `public enum WifiSecurityMode: String, Codable, CaseIterable, Sendable` trong `GlobalSettings.swift` với 6 chế độ chuẩn (`wpa2Wpa3Psk = "wpa2_wpa3_psk"`, `wpa2Psk = "wpa2_psk"`, `wpaWpa2Psk = "wpa_wpa2_psk"`, `wpa3Psk = "wpa3_psk"`, `wpaPsk = "wpa_psk"`, `open = "open"`) kèm các thuộc tính `displayLabel`, `shortLabel`, `akmPsk`, `akmPsk2`, `crypto`, `requiresPassword`.
  - **[Red Team RT-07]** Thêm `public var wifiSecurityMode: String` (mặc định `"wpa2_wpa3_psk"`) vào `GlobalSettings.swift` và cài đặt `public init(from decoder: Decoder) throws` sử dụng `decodeIfPresent(String.self, forKey: .wifiSecurityMode) ?? WifiSecurityMode.wpa2Wpa3Psk.rawValue` để giải mã an toàn dữ liệu JSON cũ chưa có trường `wifiSecurityMode`.
  - **[Red Team RT-06]** Cập nhật `OntInputValidator.swift` (`validateBeforeRun`):
    - Kiểm tra `WifiSecurityMode(rawValue: settings.wifiSecurityMode)` hợp lệ.
    - Kiểm tra `ssid24` và `ssid5` không chứa toàn dấu cách (`!ssid24.trimmingCharacters(in: .whitespaces).isEmpty`).
    - Tách kiểm tra `isPrintableAscii(pass24)` / `isPrintableAscii(pass5)` độc lập với kiểm tra độ dài `8...63`: khi `secMode.requiresPassword == true`, bắt buộc `pass24` và `pass5` dài `8...63` ký tự ASCII in được và không chứa toàn dấu cách; khi `secMode == .open`, không bắt buộc độ dài `8...63` và đặt `canPrependZero24g = false`, `canPrependZero5g = false` (nhưng vẫn kiểm tra `isPrintableAscii` nếu người dùng có nhập chuỗi khác rỗng).
  - **[Red Team RT-01, RT-05]** Cập nhật `OntMobileDriver.swift`:
    - Bổ sung `public let wifiSecurityMode: String` (mặc định `WifiSecurityMode.wpa2Wpa3Psk.rawValue`) vào `MobileSetupResult`.
    - Mở rộng `configureWifiBand(unit:ssid:password:secMode:isConnectedBandFinalStep:)` với mặc định `secMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue`.
    - Ánh xạ `wl_akm_psk`, `wl_akm_psk2`, `wl_crypto` từ `WifiSecurityMode`, kiểm tra lỗi `stringValue(cfgJson, "status") == "conflict_mesh"` (ném `OntDriverException`) và `status != "OK"`.
    - Trong khối `do { ... } catch` của `configureWifiBand` (`OntMobileDriver.swift:543-559`): thêm `catch let err as OntDriverException { throw err }` trước nhánh `catch` tổng quát để lỗi `conflict_mesh` không bị bọc nhầm thành `OntNetworkError` (`[Red Team RT-01]`).
    - Trong vòng lặp retry Bước 7 (`for step7Attempt in 1...3` tại `OntMobileDriver.swift:819-878`): nếu bắt được `OntValidationError` hoặc `OntDriverException` không phải `OntNetworkError`, **ném trả ngay (`throw error`)** mà không chạy `ensureGatewayReadyBeforeStep7` hay thử lại 3 lần (`[Red Team RT-01]`).
    - Trong `executeMobileSetup()`: khi `settings.wifiSecurityMode == "open"`, đặt `newPass24g = ""` và `newPass5g = ""` trong `MobileSetupResult` (không gán `"1234567890"` giả tạo `[Red Team RT-05]`).
  - **[Red Team RT-05]** Cập nhật `ConfigReviewView.swift` & `ExecutionProgressSheet.swift`:
    - Trong `wifiConfigurationSection` (`ConfigReviewView.swift`), thêm `Picker("Chuẩn bảo mật Wi-Fi", selection: ...)` hiển thị 6 chế độ bảo mật.
    - Cập nhật nhãn mật khẩu Wi-Fi khi chọn `open` và trên `ExecutionProgressSheet.swift` hiển thị chuẩn bảo mật Wi-Fi; khi `result.wifiSecurityMode == "open"`, hiển thị `"• Bảo mật Wi-Fi: Open (Không mật khẩu)"` và ẩn nút `"Sao chép mật khẩu Wi-Fi mới"`.
  - Cập nhật `OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift`, `tests/test_ios_app_parity.py`, `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Architecture
- Đồng nhất 100% bảng ánh xạ `WifiSecurityMode` giữa Python (`app/core/ont_driver.py`), Kotlin (`GlobalSettings.kt`) và Swift (`GlobalSettings.swift`):
  - `wpa2_wpa3_psk` $\rightarrow$ `wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=aes`
  - `wpa2_psk` $\rightarrow$ `wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=aes`
  - `wpa_wpa2_psk` $\rightarrow$ `wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=tkip+aes`
  - `wpa3_psk` $\rightarrow$ `wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=aes`
  - `wpa_psk` $\rightarrow$ `wl_akm_psk=enabled`, `wl_akm_psk2=enabled`, `wl_crypto=tkip+aes`
  - `open` $\rightarrow$ `wl_akm_psk=disabled`, `wl_akm_psk2=disabled`, `wl_crypto=aes`

## Related Code Files
- Modify: `ios-app/ONTWiFiConfigurator/Data/Model/GlobalSettings.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Validation/OntInputValidator.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Components/ExecutionProgressSheet.swift`
- Modify: `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- Modify: `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift`
- Modify: `tests/test_ios_app_parity.py`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. **`GlobalSettings.swift`**:
   - Định nghĩa `public enum WifiSecurityMode: String, Codable, CaseIterable, Sendable` và thêm `public var wifiSecurityMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue` vào `GlobalSettings` kèm custom `init(from decoder: Decoder) throws` dùng `decodeIfPresent` (`[Red Team RT-07]`).
2. **`OntInputValidator.swift`**:
   - Trong `validateBeforeRun(_ record: SubscriberRecord, settings: GlobalSettings)`:
     - Kiểm tra `WifiSecurityMode(rawValue: settings.wifiSecurityMode)`.
     - Khi `secMode.requiresPassword == true`, kiểm tra `8...63` ký tự, không toàn dấu cách và bật `canFix24` / `canFix5`.
     - Khi `secMode == .open`, tắt `canFix24 = false`, `canFix5 = false`, bỏ qua kiểm tra `8...63` ký tự nhưng vẫn kiểm tra `isPrintableAscii` nếu chuỗi mật khẩu khác rỗng (`[Red Team RT-06]`).
3. **`OntMobileDriver.swift`**:
   - Thêm `public let wifiSecurityMode: String` vào `MobileSetupResult`.
   - Cập nhật `configureWifiBand` nhận `secMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue`, kiểm tra hợp lệ, tạo `cfgPayload` với `sec_mode=\(mode.rawValue)`, `wl_akm_psk=\(mode.akmPsk)`, `wl_akm_psk2=\(mode.akmPsk2)`, `wl_crypto=\(mode.crypto)`, kiểm tra `status == "conflict_mesh"` / `status == "OK"`, và rethrow `OntDriverException` trước nhánh `catch` mạng (`[Red Team RT-01]`).
   - Trong `executeMobileSetup`: truyền `secMode: settings.wifiSecurityMode` vào cả Bước 6 và Bước 7, rethrow ngay `OntValidationError` / `OntDriverException` trong vòng lặp Bước 7, và trả về `newPass24g = ""` / `newPass5g = ""` khi `open` (`[Red Team RT-01, RT-05]`).
4. **`ConfigReviewView.swift` & `ExecutionProgressSheet.swift`**:
   - Thêm `Picker("Chuẩn bảo mật Wi-Fi", selection: ...)` vào đầu `wifiConfigurationSection` trong `ConfigReviewView.swift`.
   - Hiển thị chuẩn bảo mật Wi-Fi trong `ExecutionProgressSheet.swift` và ẩn nút sao chép mật khẩu khi `result.wifiSecurityMode == "open"` (`[Red Team RT-05]`).
5. **Kiểm thử & Tài liệu (`OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift`, `tests/test_ios_app_parity.py`, `README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`)**:
   - Bổ sung test trong `OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift` và `tests/test_ios_app_parity.py` xác nhận đồng bộ 6 chế độ bảo mật trên cả 3 nền tảng.
   - Cập nhật bảng tính năng và hướng dẫn chọn loại bảo mật Wi-Fi trong `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Todo
- [ ] Thêm `WifiSecurityMode`, `wifiSecurityMode` và custom `init(from decoder:)` vào `GlobalSettings.swift`
- [ ] Cập nhật `OntInputValidator.swift` và `OntMobileDriver.swift` (bao gồm `MobileSetupResult.wifiSecurityMode` và xử lý `conflict_mesh`)
- [ ] Bổ sung `Picker` chọn chuẩn bảo mật Wi-Fi trong `ConfigReviewView.swift` và hiển thị trong `ExecutionProgressSheet.swift`
- [ ] Cập nhật `OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift`, `tests/test_ios_app_parity.py`
- [ ] Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Success Criteria
- [ ] Chạy toàn bộ `pytest` (`37+` tests trên Desktop Windows & iOS parity) và `./gradlew testDebugUnitTest` (trên Android) đạt PASS 100%.
- [ ] Tài liệu `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` mô tả đầy đủ cách chọn loại bảo mật Wi-Fi trên cả Windows và Mobile.

## Risk Assessment
- **Risk:** Khối `do { ... } catch` không định kiểu trong `OntMobileDriver.swift:543-559` bắt mọi lỗi ném ra bên trong và bọc lại thành `OntNetworkError`, làm mất thông báo lỗi `conflict_mesh`.
  - **Signal:** Khi ONT trả về `{"status": "conflict_mesh"}`, iOS báo `"Lỗi mạng khi gửi cấu hình Wi-Fi"` và lặp lại 3 lần ở Bước 7.
  - **Mitigation:** Thêm `catch let err as OntDriverException { throw err }` trước nhánh `catch` tổng quát trong `configureWifiBand` và rethrow ngay trong vòng lặp Bước 7 (`[Red Team RT-01]`).
