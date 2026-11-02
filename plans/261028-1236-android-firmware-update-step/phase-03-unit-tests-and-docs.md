---
phase: 3
title: "Phase 3: MockWebServer Unit Tests & Documentation"
status: completed
priority: P1
effort: "1h"
dependencies: [1, 2]
---

# Phase 3: MockWebServer Unit Tests & Documentation

## Overview
Bổ sung bộ kiểm thử tự động trong `OntMobileDriverTest.kt` và `ExcelAndValidatorTest.kt` để xác minh toàn bộ luồng upload & cập nhật Firmware trên Android (giao thức 3 bước LuCI CGI, thứ tự thực thi trước bước đổi Wi-Fi kết nối chính, xử lý lỗi RAM/chữ ký/mạng, giải phóng phiên `sessionKey`) và cập nhật tài liệu hướng dẫn sử dụng.

## Requirements
- **Functional:**
  - Trong `OntMobileDriverTest.kt` (`android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`):
    1. Test **`executeMobileSetup with enableFirmwareUpdate=true uploads firmware at Step 7 before connected Wi-Fi step and skips automatic connected Wi-Fi rename`**:
       - Khi đang kết nối ở băng tần `2.4GHz` (`BAND_2_4_GHZ`), xác nhận:
         - Bước 6 chỉ cấu hình băng tần phụ `5GHz` (`wl_unit=1`), gọi `ensureGatewayReadyBeforeStep7` (xác minh recovery khi gặp `user exist` `[Red Team RT-02]`), và **không** cấu hình `wl_unit=0` (`2.4GHz`), **không** gọi `onSwitchToConfiguredWifi`.
         - Bước 7 gọi đúng thứ tự `POST /cgi-bin/luci/updatefwsessioncheck.json` $\rightarrow$ `POST /cgi-bin/luci/imagesize.json` $\rightarrow$ `POST /cgi-bin/luci/uploadsoftware.conf` (kiểm tra header `Content-Type` chứa `multipart/form-data; boundary=`, header `Referer` kết thúc bằng `/update_software.html`, body multipart chứa đúng tên file đã chuẩn hóa, nội dung nhị phân có byte `0x7f`/`0xff` và `sessionKey`).
         - Sau khi `uploadsoftware.conf` trả về `{"status":"valid"}`, `sessionKey` được xóa ngay nên `/cgi-bin/luci/logout.cmd` **không** được gọi sau `uploadsoftware.conf` (kể cả khi socket ngắt sau khi đã flush đủ 100% file `[Red Team RT-03]`).
         - `result.firmwareUpdated == true`, `result.manualWifiStepPending == true`, `result.manualWifiBandLabel == "2.4GHz"`, `result.manualWifiSsid == "Manh Ngoc"`, và `unbindCalled == true`.
    2. Test **`updateFirmware fails when ONT ramFree is insufficient or zero and still calls logout in finally`**:
       - Khi `imagesize.json` trả về `ramFree = "10"` (nhỏ hơn kích thước file test) hoặc `"0"`, xác nhận `executeMobileSetup` ném `OntDriverException` có tiền tố `"Đã đổ xong cấu hình (Bước 1-6..."`, và khối `finally` **vẫn gọi `logout.cmd`** với `sessionKey` xoay vòng mới nhất để không làm kẹt phiên `"user exist"`.
    3. Test **`updateFirmware fails when uploadsoftware returns invalid status and rotates sessionKey for clean logout`**:
       - Khi `uploadsoftware.conf` trả về `{"status":"invalid","sessionKey":"sess_rot_99"}`, xác nhận ném `OntDriverException` và `logout.cmd` gửi đúng `sessionKey=sess_rot_99`.
    4. Test **`executeManualConnectedWifiOnly probes gateway, logs in, and renames the remaining connected Wi-Fi band with radio drop tolerance after ONT reboot`**:
       - Kiểm thử hàm hỗ trợ đổi nốt băng tần Wi-Fi kết nối chính (`wl_unit=0`) sau khi ONT đã khởi động lại xong (`probeGateway` $\rightarrow$ `login.cmd` $\rightarrow$ `wirelesscfg.json` + xử lý ngắt kết nối khi radio khởi động lại `[Red Team RT-06]`).
  - Trong `ExcelAndValidatorTest.kt` (`android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`):
    - Kiểm thử `OntInputValidator.validateBeforeRun()` khi `enableFirmwareUpdate = true` (báo lỗi khi chưa chọn file, sai định dạng ngoài `.signed`/`.bin`/`.img`, hoặc kích thước ngoài khoảng `64 KB .. 64 MB`; hợp lệ khi cấu hình đúng `[Red Team RT-04]`).
    - Kiểm thử `OntInputValidator.sanitizeFirmwareFilename()`, `isValidFirmwareExtension()`, và `maskSensitiveCredentials()` với chuỗi JSON `"sessionKey": "secret123"` (kể cả chuỗi bị cắt ngắn `[Red Team RT-04]`).
    - Kiểm thử `ExcelManager.exportResults()` xuất đúng ghi chú chờ đổi Wi-Fi chính thủ công khi `errorMessage` chứa thông báo từ bước nạp Firmware (`[Red Team RT-05]`).
- **Non-functional:**
  - Toàn bộ unit tests trong `android-app` chạy độc lập, tự dọn dẹp file firmware tạm (`File.createTempFile` / `@After`) sau khi kết thúc test.

## Related Code Files
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Bổ sung 4 test case nâng cấp Firmware và đổi Wi-Fi chính thủ công vào `OntMobileDriverTest.kt`.
2. Bổ sung các test case kiểm tra `validateBeforeRun` với `enableFirmwareUpdate = true`, `sanitizeFirmwareFilename`, và JSON `sessionKey` masking vào `ExcelAndValidatorTest.kt`.
3. Chạy `./gradlew testDebugUnitTest` trong `android-app/` để xác nhận 100% test pass.
4. Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Todo
- [x] Viết unit tests cho `updateFirmware`, `executeMobileSetup(enableFirmwareUpdate=true)` và `executeManualConnectedWifiOnly` trong `OntMobileDriverTest.kt`
- [x] Viết unit tests cho `validateBeforeRun`, `sanitizeFirmwareFilename` và JSON `sessionKey` masking trong `ExcelAndValidatorTest.kt`
- [x] Chạy `./gradlew testDebugUnitTest` xác nhận pass 100%
- [x] Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Success Criteria
- [x] `./gradlew testDebugUnitTest` trong thư mục `android-app` vượt qua 100% bài test (0 lỗi).
- [x] Tài liệu mô tả rõ luồng cập nhật Firmware trước bước đổi Wi-Fi kết nối chính trên ứng dụng Android.

## Risk Assessment
- **Rủi ro (File tạm sinh ra khi chạy unit test):**
  - *Giải pháp:* Tạo file firmware nhị phân tạm bằng `File.createTempFile("fw_test_", ".signed")` và gọi `delete()` trong khối `finally` của từng test.
