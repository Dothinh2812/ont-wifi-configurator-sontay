---
phase: 2
title: "Phase 2: iOS Native Keep-Alive, Server-Side Band Resolution & Session Recovery Parity"
status: completed
priority: P1
effort: "1h"
dependencies: [1]
---

# Phase 2: iOS Native Keep-Alive, Server-Side Band Resolution & Session Recovery Parity

## Context Links
- `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
- `ios-app/ONTWiFiConfigurator/Core/Driver/UnsafeSslSession.swift`
- `ios-app/ONTWiFiConfigurator/Core/Wifi/IosWifiMonitor.swift`
- `ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`
- `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`

## Overview
Đồng bộ 100% cơ chế giữ kết nối Wi-Fi xuyên suốt và đảm bảo lưu cấu hình Wi-Fi cuối trước khi ngắt kết nối sang bản iOS Native (`ios-app/`): tách `recoveryRetryDelayMs = 1200` khỏi `settlingDelayMs: 200`, tận dụng địa chỉ IPv4 `en0` (`192.168.1.x`) từ `IosWifiMonitor` để tự động đối chiếu băng tần kết nối thực tế trên ONT ngay sau Bước 2 (`dhcpclient.json` + `deviceinfo2.json` mảng 2 chiều), bổ sung `reloginAfterRadioBounce` + `lastActiveSessionKey` xử lý `"user exist"` và `"fail authen"` an toàn chống khóa 180s, và kiểm tra chặt cả 3 lần thử ở Bước 7.

## Requirements
- **Functional:**
  - **Tách `recoveryRetryDelayMs` khỏi `settlingDelayMs` (`[Red Team RT-03]`):**
    - Trong `OntMobileDriver.swift`, thêm tham số khởi tạo `recoveryRetryDelayMs: Int = 1200` (trong XCTest truyền `recoveryRetryDelayMs: 20`).
    - Thay thế biểu thức `settlingDelayMs >= 500 ? 1200 : 20` / `50` tại `OntMobileDriver.swift:872, 903` bằng `recoveryRetryDelayMs`, nhờ đó giữ nguyên `settlingDelayMs: 200` trong `MainViewModel.swift:348` (tương thích `test_ios_app_parity.py:117`) mà không làm co cửa sổ hồi phục sóng ngoài thực địa xuống `20ms`!
  - **Tự động nhận diện băng tần thực tế ngay sau Bước 2 mà không cần Apple Entitlement trả phí (`[Red Team RT-02, RT-09]`):**
    - Trên `OntMobileDriver.executeMobileSetup(...)` (`OntMobileDriver.swift:752-757`), bổ sung `localWifiIpv4: String? = nil` và `onBandResolved: (@Sendable (WifiBand) -> Void)? = nil` **trước** tham số trailing closure `onProgress` để giữ nguyên cú pháp gọi trailing closure tại `MainViewModel.swift:363-367` và 7 điểm gọi test trong `OntMobileDriverTests.swift`.
    - Cài đặt `resolveConnectedBandFromOnt(localIpv4: String?, fallbackBand: WifiBand) async -> WifiBand` và gọi **ngay sau Bước 2 (`loginAndChangePassword`)** dùng `session` chính (`timeoutMs`, mặc định `5000ms` thay vì `logoutSession` `1500ms` do `deviceinfo2.json` mất `1,372 ms` trên phần cứng thật):
      1. Đọc `POST /cgi-bin/luci/dhcpclient.json` (`undefined&sessionKey=\(encKey)`): khớp `localIpv4` trong `dhcpInfoList` (`parts[0]`) để lấy `clientMac` (`parts[3]`) và `leaseIfc` (`parts[7]`); đối chiếu `clientMac` với `wlAssociatedDevice` (`assocParts[0]`) để ưu tiên lấy giao diện thực tế `assocParts[3]`, chuẩn hóa `wl0.1` / `wl0_1` $\rightarrow$ `wl0`, `wl1.1` $\rightarrow$ `wl1`.
      2. Đọc `POST /cgi-bin/luci/deviceinfo2.json`: ép kiểu `wirelessInfoList` đúng cấu trúc **mảng 2 chiều** `[[Any]]` (`(row.first as? String)` theo `ont-040NSv2-setup.jsonl:152`), `.trimmingCharacters(in: .whitespacesAndNewlines)` từng phần tử cắt bởi `"|"`, đọc phần chữ số đầu của kênh `subData[3]`: kênh `1...14` $\rightarrow$ `.band24GHz`, kênh `>= 36` $\rightarrow$ `.band5GHz`; nếu kênh `"0"` (`auto`) hoặc không đọc được `deviceinfo2.json`, ánh xạ `wl0` $\rightarrow$ `.band24GHz`, `wl1` $\rightarrow$ `.band5GHz`.
      3. Gọi `onBandResolved?(effectiveBand)` để cập nhật `MainViewModel.connectedBand` trên MainActor. Mọi lỗi mạng/404 đều fallback êm về `fallbackBand`.
  - **Cổng `ensureGatewayReadyBeforeStep7`, `reloginAfterRadioBounce` & Quản lý `lastActiveSessionKey` (`[Red Team RT-01, RT-05, RT-09]`):**
    - Theo dõi `lastActiveSessionKey` trong `OntMobileDriver.swift` (chỉ cập nhật khi `!key.isEmpty && key != "0" && key != "factoryID"`). Nếu `reloginAfterRadioBounce` thất bại sau khi `logout()` xóa `currentSessionKey`, khôi phục `currentSessionKey = lastActiveSessionKey` để `Task.detached { await self.logout() }` ở cuối hàm vẫn giải phóng phiên.
    - Cài đặt `reloginAfterRadioBounce(settings: GlobalSettings, recordKey: String, previousSessionKey: String) async throws -> Bool`: nếu `loginAndChangePassword` ném `OntUserExistError`, tạm gán `currentSessionKey = previousSessionKey`, gọi `await logout()` rồi đăng nhập lại; **ném trả ngay lập tức** `CancellationError`, `OntAccountLockoutError`, `OntAccountLockoutWarning`, `OntAuthenticationError` mà không bao giờ nuốt hoặc gọi đúp login.
    - Nâng cấp `ensureGatewayReadyBeforeStep7(settings:recordKey:previousSessionKey:maxAttempts:retryDelayMs:) async throws -> Bool` (`OntMobileDriver.swift:639-684`):
      - Bọc `do { postForm("/cgi-bin/luci/wlrestart.json", ...) } catch` hẹp quanh lệnh HTTP (ném lại `CancellationError`); nếu `statusCode != 200 || body.hasPrefix("<")` **hoặc** `wlJson == nil || stringValue(wlJson, "status").lowercased() == "fail authen" || wlJson["_wlrestart_"] == nil`, gọi đúng 1 lần `try await reloginAfterRadioBounce(...)`.
      - Cập nhật đủ **cả 3 điểm gọi** `try await ensureGatewayReadyBeforeStep7(...)` trong `OntMobileDriver.swift`: (1) ngay trước vòng lặp Bước 7 (nếu `false` thì ném `OntNetworkError`), (2) nhánh kiểm tra sau rớt mạng tại `:873`, và (3) nhánh `catch` khôi phục tại `:905` — luôn truyền `previousSessionKey: lastActiveSessionKey` và `retryDelayMs: recoveryRetryDelayMs`.
  - **Đảm bảo Lưu Xong Bước 7 Mới Ngắt Kết Nối & Chống Nháy Online Sau Hoàn Tất (`[Red Team RT-04, RT-07, RT-08]`):**
    - Trong `OntMobileDriver.swift`:
      - Xóa `NSURLErrorSecureConnectionFailed` khỏi `isPostFlushRadioDrop` (`dòng 716`, vì lỗi bắt tay TLS xảy ra trước khi gửi bất kỳ byte HTTP nào).
      - Nếu `wirelesscfg.json` ở Bước 7 trả về `status == "fail authen"`, cho phép đi vào nhánh `ensureGatewayReadyBeforeStep7` đăng nhập lại và thử lại (chỉ ném ngay đối với `conflict_mesh` và `OntValidationError`).
      - Chạy kiểm tra `stillOnOldWifi = try await ensureGatewayReadyBeforeStep7(..., maxAttempts: 3, retryDelayMs: recoveryRetryDelayMs)` cho **cả 3 lần thử (`1...3`)**: ở lần thử 3, nếu `stillOnOldWifi == true` thì ép `droppedDuringRadioReload = false` và ném `OntNetworkError` (không bao giờ báo `SUCCESS` giả khi sóng cũ chưa tắt).
      - Khi `cfgSavedToNvram == true`, gọi `wlrestart.json` ở Bước 7 với `useShortLogoutTimeout: true` (`1.5s`).
    - Trong `MainViewModel.swift` & `ConfigReviewView.swift`:
      - Kiểm tra nhanh `wifiMonitor.inspectInterfaces().isOnOntSubnet` ở đầu `runOntConfiguration()` (cảnh báo/chặn rõ nếu iPhone chưa có IP `192.168.1.x` trên card `en0`, hoặc đang bật VPN chiếm route `[Red Team RT-08]`).
      - Thêm cờ `suppressProbeAfterCompletion` trong `MainViewModel.swift` (bật `true` khi hoàn tất Bước 7, đặt lại `false` khi chọn thuê bao khác, mở `WifiGuideSheet`, hoặc bấm `RUN`) để vòng lặp probe 1s không nháy `ONLINE` giả trên sóng cũ đang tắt.
      - Cập nhật ghi chú hướng dẫn dưới bộ chọn băng tần trên `ConfigReviewView.swift`.
- **Non-functional:**
  - Giữ nguyên `settlingDelayMs: 200` trong `MainViewModel.swift:348` và toàn bộ hợp đồng kiểm tra trong `tests/test_ios_app_parity.py`.
  - Không thêm bất kỳ dependency ngoài hoặc entitlement nào vào `ios-app/project.yml` / `ONTWiFiConfigurator.xcodeproj`.

## Architecture
- Luồng thực thi iOS Bước 1 $\rightarrow$ Bước 7:
  `Precheck en0 192.168.1.x` $\rightarrow$ `Step 1..2 (Probe, Login & Admin Pass, lưu lastActiveSessionKey)` $\rightarrow$ `resolveConnectedBandFromOnt(en0IpAddress, connectedBand)` $\rightarrow$ `Step 3..5 (WAN, SLID, LAN)` $\rightarrow$ `Step 6: configureWifiBand(step6Unit, isConnectedBandFinalStep: false)` + `Task.sleep(200ms)` $\rightarrow$ `try await ensureGatewayReadyBeforeStep7(previousSessionKey: lastActiveSessionKey, retryDelayMs: recoveryRetryDelayMs)` $\rightarrow$ `Step 7: configureWifiBand(step7Unit, isConnectedBandFinalStep: true)` (kiểm tra `stillOnOldWifi` cho cả `1...3`, `wlrestart.json` `1.5s`) $\rightarrow$ `Task.detached { await self.logout() }` + `suppressProbeAfterCompletion = true`.

## Related Code Files
- Modify: `/home/vtst/ONT-config/ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
- Modify: `/home/vtst/ONT-config/ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`
- Modify: `/home/vtst/ONT-config/ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`

## Implementation Steps
1. **Nâng cấp `OntMobileDriver.swift`:**
   - Thêm `recoveryRetryDelayMs: Int = 1200` và `lastActiveSessionKey`.
   - Thêm `resolveConnectedBandFromOnt(localIpv4:fallbackBand:)` đọc `dhcpclient.json` + mảng 2 chiều `wirelessInfoList` của `deviceinfo2.json` ngay sau Bước 2 qua `session` chính.
   - Cài đặt `reloginAfterRadioBounce(...)`, sửa `ensureGatewayReadyBeforeStep7(...)` bắt `"fail authen"` và không gọi đúp login, cập nhật đủ 3 điểm gọi `try await ensureGatewayReadyBeforeStep7(...)`, xóa `NSURLErrorSecureConnectionFailed` khỏi `isPostFlushRadioDrop`, kiểm tra `stillOnOldWifi` trên cả 3 lần thử Bước 7, và dùng `useShortLogoutTimeout: true` cho `wlrestart.json` cuối Bước 7.
2. **Cập nhật `MainViewModel.swift` & `ConfigReviewView.swift`:**
   - Kiểm tra `snapshot.isOnOntSubnet` (và cảnh báo VPN) trong `runOntConfiguration()`, truyền `localWifiIpv4: snapshot.en0IpAddress` và `onBandResolved` vào `driver.executeMobileSetup(...)` (đặt trước trailing closure `onProgress`), bật `suppressProbeAfterCompletion = true` khi hoàn tất, và cập nhật ghi chú `ConfigReviewView.swift`.

## Todo
- [x] Thêm `recoveryRetryDelayMs`, `lastActiveSessionKey`, `resolveConnectedBandFromOnt` (sau Bước 2, 2D `wirelessInfoList`) và `reloginAfterRadioBounce` vào `OntMobileDriver.swift`
- [x] Cập nhật đủ 3 điểm gọi `try await ensureGatewayReadyBeforeStep7`, xóa `NSURLErrorSecureConnectionFailed` khỏi `isPostFlushRadioDrop`, và kiểm tra `stillOnOldWifi` trên cả `1...3` trong `OntMobileDriver.swift`
- [x] Cập nhật `MainViewModel.swift` (`en0IpAddress`, `onBandResolved`, `isOnOntSubnet` precheck, `suppressProbeAfterCompletion`) và `ConfigReviewView.swift`

## Success Criteria
- [x] Trên iOS, kể cả khi `connectedBand` truyền vào là `.band24GHz` nhưng `dhcpclient.json` + `deviceinfo2.json` báo IP `en0` đang gắn với `wl1` (`5GHz`), `OntMobileDriver.swift` tự động cấu hình `wl_unit=0` ở Bước 6 và để `wl_unit=1` ở Bước 7 cuối cùng.
- [x] `ensureGatewayReadyBeforeStep7` xử lý chính xác cả `"status": "fail authen"` lẫn `"status": "user exist"` mà không bao giờ gọi đúp login làm tăng `numberLogin` chạm ngưỡng khóa 180s.

## Risk Assessment
- **Risk:** Trong môi trường kiểm thử XCTest (`MockLuciURLProtocol`), các bài test hiện có ném `URLError(.networkConnectionLost)` ở Bước 7 mà không qua tầng socket vật lý nên không kích hoạt delegate `didSendBodyData`.
  - **Signal:** Test `testStep7HandlesSocketDisconnectDuringRadioReloadWhenOldGatewayDisappears` bị fail nếu ép `didFlushFullRequestBody == true` cho `NSURLErrorNetworkConnectionLost`.
  - **Mitigation:** Chỉ loại bỏ `NSURLErrorSecureConnectionFailed` khỏi `isPostFlushRadioDrop`, giữ `NSURLErrorNetworkConnectionLost` kết hợp kiểm tra `stillOnOldWifi` trên cả 3 lần thử (`1...3`) để vừa tương thích `MockLuciURLProtocol` vừa chống báo `SUCCESS` giả khi sóng cũ chưa tắt (`[Red Team RT-04]`).
