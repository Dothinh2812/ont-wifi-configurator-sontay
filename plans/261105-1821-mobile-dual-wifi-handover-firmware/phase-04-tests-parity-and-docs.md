---
phase: 4
title: "Phase 4: Unit Tests, Cross-Platform Parity Suite & Field Documentation"
status: completed
priority: P1
effort: "1.5h"
dependencies: [2, 3]
---

# Phase 4: Unit Tests, Cross-Platform Parity Suite & Field Documentation

## Context Links
- Red Team Report: [`reports/red-team-261105-mobile-dual-wifi-handover-firmware.md`](./reports/red-team-261105-mobile-dual-wifi-handover-firmware.md)
- Android Tests: `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`, `ExcelAndValidatorTest.kt`
- iOS Tests & Mock Server: `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift`, `tests/mock_luci_server.py`, `tests/test_ios_app_parity.py`
- Documentation: `README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Overview
Cập nhật toàn bộ 20 điểm gọi kiểm thử trong `OntMobileDriverTest.kt`, 11 điểm gọi trong `OntMobileDriverTests.swift`, `mock_luci_server.py`, bộ kiểm thử đối xứng đa nền tảng (`tests/test_ios_app_parity.py` toàn tệp từ dòng 1 đến hết) và tài liệu vận hành hiện trường (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`) khớp 100% với quy trình 2 lần kết nối + đổ Firmware mới ở bước cuối.

## Key Insights
- **`[Red Team RT-05, RT-10]`:** Để toàn bộ test suite Android và CI `macos-14` chạy trong `< 5 giây` (không bao giờ treo vào các ngân sách thực tế `120s` / `180s` / `60s` / `45s`), xây dựng helper tạo `OntMobileDriver` & `GlobalSettings` dùng chung cho test truyền `wlRestartPollIntervalMs = 10`, `maxWlRestartPolls = 3`, `userExistPollIntervalMs = 20`, `wifiHandoverTimeoutMs = 150`, `gatewayPostHandoverTimeoutMs = 150`, `postSecondWifiSettleTimeoutMs = 150` và stub `onSwitchToSecondaryWifi` cho toàn bộ **20 callsites** trong `OntMobileDriverTest.kt` (`:129..1364`) và **11 callsites** trong `OntMobileDriverTests.swift` (`:194..770`).
- **`[Red Team RT-02, RT-10]`:** Trong `tests/mock_luci_server.py:212`, endpoint `/cgi-bin/luci/wirelesscfg.json` cần phân biệt rõ lệnh ghi (`action == "Apply"`) và lệnh đọc NVRAM read-only (không có `action=Apply`, đọc theo `wl_unit=0|1` và trả về `nvramWlSsid`). Đồng thời rà soát toàn bộ `tests/test_ios_app_parity.py` (bao gồm cả dòng `106`, `132`, `154-161` và `250-350`) để cập nhật đồng bộ các kiểm chứng cấu trúc mới.

## Requirements
- **Functional:**
  - **Trong `tests/mock_luci_server.py` (`:212`) `[Red Team RT-02, RT-10]`:**
    - Chỉ cập nhật cấu hình Wi-Fi lưu trữ khi form chứa `action == "Apply"`, và hỗ trợ trả về đúng `nvramWlSsid` theo `wl_unit` (`0` hoặc `1`) khi truy vấn read-only `page=ssid.asp&wl_unit=<0|1>&wl_bssid=0`.
  - **Android Unit Tests (`OntMobileDriverTest.kt` — 20 callsites, `ExcelAndValidatorTest.kt`):**
    - Cập nhật toàn bộ 20 điểm gọi `executeMobileSetup` qua helper chung có `onSwitchToSecondaryWifi` stub và timeout test ngắn (`< 200 ms`); xóa các test của `executeManualConnectedWifiOnly` / `manualWifiStepPending`.
    - Bổ sung kiểm thử chuyên sâu cho quy trình mới:
      1. **Full 9-Step Dual-Wi-Fi + Final Firmware Flow (`2.4GHz -> 5GHz` & `5GHz -> 2.4GHz`):** xác nhận Bước 6 chỉ đổi `Band B` + gọi `logout.cmd`; Bước 7 gọi `onSwitchToSecondaryWifi`, ghim chứng chỉ TLS, đăng nhập lại **chỉ bằng `Passwd2@`** (không bao giờ gửi `"admin"` cũ); Bước 8 đổi nốt `Band A`; Bước 9 chạy `ensureGatewayReadyOnSecondaryBand` rồi gọi `updatefwsessioncheck.json` $\rightarrow$ `imagesize.json` $\rightarrow$ `uploadsoftware.conf` multipart, xóa `sessionKey` (không gọi `logout.cmd` sau `"status": "valid"`), và trả về `firmwareUpdated = true` với cả 2 Wi-Fi đã lưu.
      2. **Connection 2 `"user exist"` Recovery & Zero `"admin"` Fallback (`[Red Team RT-01]`):** mô phỏng `logout.cmd` cuối Bước 6 rớt mạng khiến lần đăng nhập đầu ở Bước 7 trả về `"user exist"` và mô phỏng lỗi mạng thoáng qua ở `ensureGatewayReadyOnSecondaryBand` $\rightarrow$ xác nhận giải phóng phiên bằng `lastActiveSessionKey`, đăng nhập lại bằng `Passwd2@` và `recordedLoginPasswords` **không bao giờ chứa `"admin"`** sau Bước 2.
      3. **Same-SSID (`ssid24g == ssid5g`) & Neighbor ONT `baseMacAddress` Rejection (`[Red Team RT-02, RT-04]`):** xác nhận khi cả 2 băng tần đặt cùng tên `"GiaDinh"`, Bước 7 vẫn từ chối nếu `wlAssociatedDevice` còn ở `wl0` (`Band A`) và ném lỗi ngay nếu `baseMacAddress` hoặc chứng chỉ TLS khác với Lần 1.
      4. **Step 8 Read-Only NVRAM Verification (`page=ssid.asp&wl_unit=...`) & 2.4GHz-Only Fallback (`[Red Team RT-02, RT-08]`):** kiểm thử đọc `nvramWlSsid` đúng `wl_unit=1`, bắn bổ sung `wlrestart.json` / retry khi rớt gói, và nhánh `allowSingleBand24gFallback = true` trên máy chỉ hỗ trợ 2.4GHz.
      5. **Validator Masking (`ExcelAndValidatorTest.kt` `[Red Team RT-02]`):** kiểm thử `maskSensitiveCredentials` che kín `nvramWlWpaPsk`, `nvramWlRadiusKey`, `nvramWlKey1` và JSON `"sessionKey"`.
  - **iOS Unit Tests (`OntMobileDriverTests.swift` — 11 callsites, `ExcelAndValidatorTests.swift`):**
    - Nâng cấp `MockLuciURLProtocol` đọc đầy đủ `request.httpBodyStream` (từ `uploadTask(withStreamedRequest:)`) lưu ra `rawData: Data`.
    - Cập nhật toàn bộ 11 điểm gọi `executeMobileSetup` sang luồng 2 lần kết nối qua helper `onSwitchToSecondaryWifi` nhanh; bổ sung test 9 bước có `updateFirmware` nhị phân, test chặn kích hoạt sớm Lần 2 khi `wlAssociatedDevice` còn báo `wl0`, test không bắn `logout.cmd` khi app ở nền lúc post-flush drop (`[Red Team RT-05]`), và test `GlobalSettings` `Codable` / `OntInputValidator` Firmware trên iOS.
  - **Cross-Platform Parity Suite (`tests/test_ios_app_parity.py` — toàn bộ tệp `[Red Team RT-10]`):**
    - Cập nhật các kiểm tra ở dòng `106` (`ensureGatewayReadyOnSecondaryBand` thay cho `ensureGatewayReadyBeforeStep7`), giữ nguyên kiểm tra `.localOnly` ở dòng `132` và `project.pbxproj` ở dòng `154-161` (bổ sung xác nhận liên kết `NetworkExtension.framework` và không có `.entitlements`), và viết lại bài kiểm thử Phase 6 (`:250-350`) xác minh tính đối xứng 2 lần kết nối + Firmware bước cuối, ghim TLS cert, không trộn ngưỡng `settlingDelayMs`, và xóa sạch `manualWifiStepPending` / `executeManualConnectedWifiOnly` / `verifyNewSsidBroadcast`.
  - **Documentation (`README.md` mục `5B`, `5C` & `HUONG_DAN_SU_DUNG_TOOL_ONT.md` mục `6B`, `6C`):**
    - Cập nhật hướng dẫn vận hành cho KTV hiện trường trên cả Android và iOS theo đúng luồng mới: Lần 1 đổi Wi-Fi phụ $\rightarrow$ đợi ONT cập nhật sóng & tự chuyển sang Wi-Fi phụ vừa đổi $\rightarrow$ Lần 2 tự đăng nhập bằng mật khẩu ONT mới, đổi nốt Wi-Fi còn lại và đổ Firmware mới ở bước cuối cùng.

## Related Code Files
- Modify: `tests/mock_luci_server.py`
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`
- Modify: `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- Modify: `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift`
- Modify: `tests/test_ios_app_parity.py`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Cập nhật `tests/mock_luci_server.py` hỗ trợ truy vấn read-only `wirelesscfg.json` theo `wl_unit`.
2. Cập nhật toàn bộ 20 callsites và bổ sung bộ test 9 bước + recovery + bảo mật trong `OntMobileDriverTest.kt` & `ExcelAndValidatorTest.kt`.
3. Cập nhật `MockLuciURLProtocol`, toàn bộ 11 callsites trong `OntMobileDriverTests.swift`, và `ExcelAndValidatorTests.swift`.
4. Rà soát và cập nhật toàn bộ `tests/test_ios_app_parity.py` cùng `README.md` (`5B`, `5C`) và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` (`6B`, `6C`).
5. Thực thi kiểm chứng: `cd android-app && ./gradlew testDebugUnitTest assembleDebug` và `PYTHONPATH=. pytest -v`.

## Todo List
- [x] Cập nhật `tests/mock_luci_server.py`, `OntMobileDriverTest.kt` (20 callsites + 9-step tests) và `ExcelAndValidatorTest.kt`
- [x] Cập nhật `OntMobileDriverTests.swift` (11 callsites + streamed multipart tests) và `ExcelAndValidatorTests.swift`
- [x] Cập nhật toàn diện `tests/test_ios_app_parity.py`, `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`
- [x] Chạy xác nhận PASS 100% `./gradlew testDebugUnitTest assembleDebug` và `PYTHONPATH=. pytest -v`

## Success Criteria
- [x] `./gradlew testDebugUnitTest` chạy hoàn tất trong `< 15 giây` và PASS 100%; `./gradlew assembleDebug` đóng gói APK thành công.
- [x] `PYTHONPATH=. pytest -v` chạy PASS 100% trên toàn bộ bộ kiểm thử Desktop, Mock LuCI và iOS/Android Parity.
- [x] Tài liệu hướng dẫn mô tả nhất quán quy trình 2 lần kết nối + đổ Firmware bước cuối trên cả Android và iOS.

## Risk Assessment
- **Rủi ro — Sót điểm gọi test cũ dùng `GlobalSettings` mặc định (`120s`/`180s`) gây chậm CI:**
  - *Observable signal:* Một ca kiểm thử âm tính (timeout/offline) chạy quá 5 giây.
  - *Pre-decided response:* Tập trung tạo `testSettings(baseUrl)` dùng chung cho mọi ca kiểm thử trong `OntMobileDriverTest.kt` và `OntMobileDriverTests.swift` với timeout `< 200 ms`.
