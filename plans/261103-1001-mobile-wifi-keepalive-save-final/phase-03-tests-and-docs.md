---
phase: 3
title: "Phase 3: Automated Unit/Parity Tests & Field Documentation Cleanup"
status: completed
priority: P1
effort: "0.5h"
dependencies: [1, 2]
---

# Phase 3: Automated Unit/Parity Tests & Field Documentation Cleanup

## Context Links
- `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
- `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- `tests/test_ios_app_parity.py`
- `README.md`
- `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Overview
Thay thế các bài kiểm thử Band-Hopping cũ bằng bộ kiểm thử tự động kiểm chứng hợp đồng **Giữ nguyên kết nối xuyên suốt & Chỉ ngắt sau khi đã lưu cấu hình Wi-Fi cuối** (sử dụng đúng cấu trúc JSON mảng 2 chiều thực tế của `deviceinfo2.json` và `dhcpclient.json` từ `ont-040NSv2-setup.jsonl`, kiểm chứng chống gọi đúp login 180s, khôi phục `"fail authen"` & `"user exist"`, chống báo `SUCCESS` giả ở Attempt 3, và giữ nguyên luồng 8 bước nạp Firmware), đồng thời cập nhật tài liệu hướng dẫn vận hành cho kỹ thuật viên.

## Requirements
- **Functional:**
  - **Cập nhật bộ Unit Test Android (`OntMobileDriverTest.kt`):**
    - Truyền `recoveryRetryDelayMs = 20L` vào các đối tượng `OntMobileDriver` trong unit test để bài test chạy tức thì mà không ảnh hưởng mặc định `1200L` của môi trường thật (`[Red Team RT-03]`).
    - Thay thế 4 bài test `onSwitchToConfiguredWifi` cũ (`OntMobileDriverTest.kt:488-723`) bằng 4 kịch bản kiểm chứng thực tế:
      1. **`Step 6 keeps current Wi-Fi session alive without logout and Step 7 saves connected band last before final logout and unbind`**: xác minh trình tự sự kiện `wirelesscfg:unit=1:sess=sess_1` $\rightarrow$ `wlrestart` $\rightarrow$ `ensureGatewayReady` (probe + `wlrestart`) $\rightarrow$ `wirelesscfg:unit=0:sess=sess_1` $\rightarrow$ `wlrestart` $\rightarrow$ `logout:sess_1` $\rightarrow$ `unbind` (chỉ đăng nhập 1 lần, không có `logout` giữa Bước 6 và Bước 7).
      2. **`resolveConnectedBandFromOnt overrides misdetected 2_4GHz client band using real 2D wirelessInfoList and wlAssociatedDevice schema`**: giả lập đúng payload thực tế từ `ont-040NSv2-setup.jsonl:151-152` (`dhcpInfoList` có `"192.168.1.2|DHCP||aa:bb:cc:dd:ee:ff|phone|WiFi|1|wl0"` cũ nhưng `wlAssociatedDevice` báo `"aa:bb:cc:dd:ee:ff|192.168.1.2|...|wl1|-45"`, và `deviceinfo2.json` trả về mảng 2 chiều `"wirelessInfoList":[["wl0|1|100|10\n|GW040-NSv2_2.4G_60BCD8\n|c4:2c:7b:60:bc:d9\n","GW040-NSv2_2.4G_60BCD8\n|wl0"],["wl1|1|100|120\n|GW040-NSv2_5G_60BCD8\n|c4:2c:7b:60:bc:da\n","GW040-NSv2_5G_60BCD8\n|wl1"]]`) $\rightarrow$ xác nhận `resolveConnectedBandFromOnt` chạy ngay sau Bước 2, ưu tiên `wlAssociatedDevice` (`wl1`), đồng bộ `onBandResolved(BAND_5_GHZ)`, cấu hình `wl_unit=0` ở Bước 6 và để `wl_unit=1` (5GHz) ở Bước 7 cuối cùng (`[Red Team RT-02]`).
      3. **`ensureGatewayReadyBeforeStep7 recovers from fail authen and user exist using dynamic lastActiveSessionKey without double login`**: giả lập sau Bước 6 `wlrestart.json` trả về `{"status":"fail authen"}` và `login.cmd` lần đầu gặp `{"status":"user exist"}` $\rightarrow$ Driver tự giải phóng `lastActiveSessionKey` qua `logout.cmd`, lấy `sess_new`, không gọi đúp login nếu gặp `numberLogin == 2`, và lưu thành công Bước 7 (`[Red Team RT-01, RT-05]`).
      4. **`Step 7 retries all 3 attempts and throws OntNetworkError without false success when old gateway stays online or pre-flush fails`**: giả lập `wirelesscfg.json` cho `wl_unit=0` bị đóng kết nối trước khi trả `HTTP 200` trên cả 3 lần thử trong khi gateway sóng cũ vẫn phản hồi (`stillOnOldWifi == true` ở cả Attempt 3) + kiểm thử trực tiếp `FlushTrackedIOException(..., allBytesFlushed = false)` $\rightarrow$ xác minh ném `OntNetworkError` (tuyệt đối không báo `SUCCESS` giả!), ngược lại khi `SocketPolicy.DISCONNECT_AT_END` sau khi đã flush hết request body và sóng cũ tắt hẳn thì hoàn tất `SUCCESS` (`[Red Team RT-04]`).
    - Cập nhật bài test Firmware 8 bước (`OntMobileDriverTest.kt:807-811` và xóa `assertFalse(..., switchWifiCalled.get())` tại `:838` `[Red Team RT-09]`): xác nhận toàn bộ các bài test Firmware + `executeManualConnectedWifiOnly` + 6 chế độ bảo mật Wi-Fi vẫn PASS 100%.
  - **Cập nhật bộ Unit Test iOS (`OntMobileDriverTests.swift`) & Kiểm thử đối xứng (`tests/test_ios_app_parity.py`):**
    - Truyền `recoveryRetryDelayMs: 20` vào `OntMobileDriver` trong `OntMobileDriverTests.swift` và bổ sung 3 bài test XCTest mới:
      1. `testResolveConnectedBandFromOntParses2DWirelessInfoListAndOverridesMisdetectedBand`.
      2. `testEnsureGatewayReadyBeforeStep7RecoversFromFailAuthenAndUserExistWithoutDoubleLogin`.
      3. `testStep7Attempt3ThrowsNetworkErrorInsteadOfFalseSuccessWhenOldGatewayRemainsOnline`.
    - Mở rộng `tests/test_ios_app_parity.py` kiểm tra tính đối xứng giữa `OntMobileDriver.kt` và `OntMobileDriver.swift`: xác nhận cả hai đều cài đặt `resolveConnectedBandFromOnt`, `reloginAfterRadioBounce`, `recoveryRetryDelayMs`, `lastActiveSessionKey`, `/cgi-bin/luci/dhcpclient.json`, `/cgi-bin/luci/deviceinfo2.json`, giữ `settlingDelayMs: 200` trong `MainViewModel.swift`, và xác nhận `onSwitchToConfiguredWifi` / `switchToConfiguredOntWifi` / `isSystemConnectedToTargetWifi` đã được xóa sạch hoàn toàn.
  - **Cập nhật tài liệu (`README.md` & `HUONG_DAN_SU_DUNG_TOOL_ONT.md`):**
    - Cập nhật `README.md` (mục tính năng Android/iOS dòng 126–128) và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` (Mục 6B dòng 142–172, đặc biệt các ý 7 bước tại dòng 162–165, và Mục 6C dòng 175–202 `[Red Team RT-09]`) mô tả đúng trình tự mới: giữ nguyên 1 kết nối Wi-Fi duy nhất từ Bước 1 đến Bước 7, tự động xác thực băng tần kết nối thực tế với ONT ngay sau Bước 2, lưu xong băng tần phụ ở Bước 6 và lưu xong băng tần chính ở Bước 7 rồi mới ngắt sóng & khôi phục 4G/5G.
- **Non-functional:**
  - Chạy thực tế `./gradlew testDebugUnitTest` trong `android-app/` và `pytest` ở thư mục gốc đạt 100% PASS, 0 cảnh báo lỗi biên dịch.

## Related Code Files
- Modify: `/home/vtst/ONT-config/android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
- Modify: `/home/vtst/ONT-config/ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- Modify: `/home/vtst/ONT-config/tests/test_ios_app_parity.py`
- Modify: `/home/vtst/ONT-config/README.md`
- Modify: `/home/vtst/ONT-config/HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Cập nhật `OntMobileDriverTest.kt`: truyền `recoveryRetryDelayMs = 20L`, thay thế 4 test Band-Hopping cũ (`:488-723`) bằng 4 bài test hợp đồng mới (dùng đúng schema mảng 2 chiều `wirelessInfoList`), và dọn `onSwitchToConfiguredWifi` / `switchWifiCalled` ở bài test Firmware (`:808-811, :838`).
2. Cập nhật `OntMobileDriverTests.swift` (thêm 3 bài test XCTest mới với `recoveryRetryDelayMs: 20`) và mở rộng `tests/test_ios_app_parity.py`.
3. Cập nhật `README.md` (`dòng 126–128`) và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` (`Mục 6B dòng 142–172` & `Mục 6C dòng 175–202`).
4. Chạy kiểm chứng `./gradlew testDebugUnitTest` và `pytest -v` để xác nhận xanh 100%.

## Todo
- [x] Cập nhật `OntMobileDriverTest.kt` kiểm chứng giữ kết nối xuyên suốt, 2D `wirelessInfoList` `resolveConnectedBandFromOnt`, `"fail authen"` / `"user exist"` recovery, và chống `SUCCESS` giả ở Attempt 3
- [x] Bổ sung 3 bài test XCTest vào `OntMobileDriverTests.swift` và kiểm tra đối xứng trong `tests/test_ios_app_parity.py`
- [x] Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Success Criteria
- [x] `./gradlew testDebugUnitTest` trong `android-app/` PASS 100%.
- [x] `pytest` toàn bộ repo (bao gồm `tests/test_ios_app_parity.py` và Desktop tests) PASS 100%.
- [x] Tài liệu `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` phản ánh chính xác cơ chế giữ nguyên kết nối và chỉ ngắt sau khi đã lưu bước cuối.

## Risk Assessment
- **Risk:** `SocketPolicy.DISCONNECT_AT_START` / `DISCONNECT_AFTER_REQUEST` của OkHttp `MockWebServer` trên loopback `127.0.0.1` cho phép `RequestBody.writeTo` (~380 bytes) ghi hết vào send buffer cục bộ trước khi nhận `RST`.
  - **Signal:** `allBytesFlushed` bằng `true` ngay cả khi `MockWebServer` đóng sớm không trả HTTP 200.
  - **Mitigation:** Nhờ cổng kiểm tra `stillOnOldWifi = ensureGatewayReadyBeforeStep7` chạy trên **cả 3 lần thử (`1..3`)** (`[Red Team RT-04]`), khi `MockWebServer` vẫn phản hồi trên sóng cũ sau lần rớt thứ 3, Driver phát hiện sóng cũ chưa tắt và ném `OntNetworkError` đúng kỳ vọng; đồng thời kiểm thử đơn vị trực tiếp `isPostFlushRadioDrop(FlushTrackedIOException(SocketTimeoutException("timeout"), allBytesFlushed = false)) == false`.
