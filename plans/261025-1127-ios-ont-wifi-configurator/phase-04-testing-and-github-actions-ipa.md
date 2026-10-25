---
phase: 4
title: "Phase 4: testing-and-github-actions-ipa"
status: completed
priority: P1
effort: "3h"
dependencies: [1, 2, 3]
---

# Phase 4: testing-and-github-actions-ipa

## Overview
Xây dựng bộ kiểm thử tự động XCTest (`ExcelAndValidatorTests.swift`, `OntMobileDriverTests.swift`), cấu hình dự án Xcode (`ios-app/project.yml` + `ios-app/ONTWiFiConfigurator.xcodeproj`), script đóng gói `.ipa` (`ios-app/scripts/package_unsigned_ipa.sh`), workflow GitHub Actions (`.github/workflows/build-ios-ipa.yml`), và cập nhật tài liệu hướng dẫn cài đặt Sideload `.ipa` cho KTV.

## Requirements
- [x] **[Red Team RT-04, RT-06, RT-09] Bộ Kiểm Thử Tự Động XCTest (`ios-app/ONTWiFiConfiguratorTests/`):**
  - Đặt bản sao `android-app/app/src/test/resources/file-mau.xlsx` vào `ios-app/ONTWiFiConfiguratorTests/Resources/file-mau.xlsx` (chỉ thuộc target `ONTWiFiConfiguratorTests`, tuyệt đối không nhúng vào target `ONTWiFiConfigurator` production).
  - `ExcelAndValidatorTests.swift`:
    - Kiểm thử đọc `ONTWiFiConfiguratorTests/Resources/file-mau.xlsx` (89 dòng `inlineStr`), bảo toàn số `0` đầu `SO_MAY` (`001560828`), điền đúng fallback `VNPT_{SO_MAY}` / `1234567890`, suy diễn đúng `SSID 5G` / `Pass 5G`, phát hiện đúng **13 dòng thiếu `Mật khẩu PPPoE`** ở `validateOnImport()`, và bắt chính xác **5 dòng có mật khẩu Wi-Fi 7 ký tự** (`3011965`, `3820275`, `5091993`, `4091981`, `8042020`) ở `validateBeforeRun()`.
    - Kiểm thử đọc định dạng `.xlsx` dùng `xl/sharedStrings.xml` (`t="s"`) + `xl/styles.xml` (`numFmt` zero-padding `000000000` và kiểm tra ô trống không bị biến thành `"000000000"`).
    - Kiểm thử chặn đủ **11 ký tự shell metacharacters** (`&|;$><`\!'"`), chặn ký tự điều khiển ASCII (`\r`, `\n`, `\0`, `0x7F`), giới hạn `SLID <= 10` ký tự ASCII, `urlEncodeFormValue`, `ontEncodeQuery`, và `maskSensitiveCredentials` (bao gồm cả `data=` và JSON fields).
    - Kiểm thử `AppDatabase` (khởi tạo với đường dẫn file tạm độc lập cho từng test, xác minh `upsertPreservingEdits` giữ trạng thái `SUCCESS`, `lastKnownAdminPass` và mật khẩu đã sửa khi nạp lại file) và `exportExcelReport` giữ nguyên đúng thứ tự 30 cột gốc (`STANDARD_30_HEADERS`) + 3 cột kết quả.
  - `OntMobileDriverTests.swift`:
    - Sử dụng `MockLuciURLProtocol` được tiêm trực tiếp qua `protocolClasses: [MockLuciURLProtocol.self]` vào cả phiên HTTP chính lẫn phiên logout `1.5s`, và đọc `request.httpBodyStream` khi `request.httpBody == nil`:
      1. Khi `connectedBand == .band24GHz`: Bước 6 cấu hình `wl_unit=1` (5GHz) và Bước 7 cuối cùng cấu hình `wl_unit=0` (2.4GHz).
      2. Khi `connectedBand == .band5GHz`: Bước 6 cấu hình `wl_unit=0` (2.4GHz) và Bước 7 cuối cùng cấu hình `wl_unit=1` (5GHz).
      3. Mô phỏng đứt kết nối radio ở Bước 7 (`URLError(.networkConnectionLost)` khi gọi `wirelesscfg.json` / `wlrestart.json`) $\rightarrow$ xác nhận `OntMobileDriver` xử lý thông minh và trả về `SUCCESS`; ngược lại nếu lỗi kết nối trước khi gửi lệnh (`URLError(.cannotConnectToHost)` hoặc `.timedOut` khi chưa gửi byte nào) thì báo lỗi chính xác.
      4. Mô phỏng lỗi giữa chừng ở Bước 3/4/6 $\rightarrow$ xác nhận `POST /cgi-bin/luci/logout.cmd` luôn được gọi trong khối cleanup để giải phóng `sessionKey`.
      5. Mô phỏng Retry sau khi Bước 2 đã đổi mật khẩu sang `Passwd2@` $\rightarrow$ xác nhận thử `Passwd2@` trước cho đúng bản ghi đó (nhưng không làm nhiễm sang bản ghi của ONT mới), và gửi đúng `serviceId=1` cho `editwan.json` khi VLAN 11 đã tồn tại.
- [x] **[Red Team RT-09 & RT-10] Cấu Hình Dự Án Xcode & Workflow Đóng Gói `.ipa` Tự Động:**
  - Tạo `ios-app/project.yml` (chuẩn XcodeGen) và file dự án `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj` hoàn chỉnh để có thể mở trực tiếp bằng Xcode trên macOS hoặc build tự động trên CI.
  - Tạo script `ios-app/scripts/package_unsigned_ipa.sh`:
    - Biên dịch `Release` (`-sdk iphoneos`, `CODE_SIGNING_ALLOWED=NO`, `CODE_SIGNING_REQUIRED=NO`, `AD_HOC_CODE_SIGNING_ALLOWED=YES`).
    - Kiểm tra bảo mật: dừng build ngay nếu phát hiện `file-mau.xlsx` nằm trong `Payload/ONTWiFiConfigurator.app`.
    - Gắn chữ ký ad-hoc Mach-O (`codesign --force --sign - Payload/ONTWiFiConfigurator.app`) để tương thích với cả TrollStore lẫn Sideloadly/AltStore, nén thành `ONT_WiFi_Configurator_Unsigned.ipa` và xuất kèm mã băm `SHA256SUMS.txt`.
  - Tạo workflow `.github/workflows/build-ios-ipa.yml` chạy trên `macos-14` với `permissions: contents: read`:
    1. Sinh/kiểm tra Xcode project qua `xcodegen`.
    2. Tự động tìm UDID của iPhone Simulator khả dụng trên runner (`xcrun simctl list devices available`) và chạy toàn bộ Unit Tests (`xcodebuild test`).
    3. Gọi `ios-app/scripts/package_unsigned_ipa.sh` để đóng gói `ONT_WiFi_Configurator_Unsigned.ipa` + `SHA256SUMS.txt` và tải lên làm GitHub Actions Artifact.
  - Tạo bài kiểm thử đồng bộ giao thức & cấu trúc dự án iOS (`tests/test_ios_app_parity.py`) để xác minh ngay trên máy Linux hiện tại rằng toàn bộ mã nguồn Swift, tài nguyên `sample-demo.xlsx`, `Info.plist`, `.xcodeproj`, script đóng gói và workflow GitHub Actions đồng bộ 100% với bản Android/Desktop.
- [x] **Cập Nhật Tài Liệu Hướng Dẫn (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`):**
  - Bổ sung hướng dẫn chi tiết cách tải file `ONT_WiFi_Configurator_Unsigned.ipa`, cách cài đặt lên iPhone qua **Sideloadly / AltStore / TrollStore**, và quy trình vận hành thực tế trên iOS tại nhà thuê bao.

## Related Code Files
- Create: `ios-app/ONTWiFiConfiguratorTests/Resources/file-mau.xlsx`
- Create: `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift`
- Create: `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- Create: `ios-app/project.yml`
- Create: `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`
- Create: `ios-app/scripts/package_unsigned_ipa.sh`
- Create: `.github/workflows/build-ios-ipa.yml`
- Create: `tests/test_ios_app_parity.py`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Sao chép `android-app/app/src/test/resources/file-mau.xlsx` vào `ios-app/ONTWiFiConfiguratorTests/Resources/file-mau.xlsx`, viết `ExcelAndValidatorTests.swift` và `OntMobileDriverTests.swift` (với `MockLuciURLProtocol` đọc `httpBodyStream`).
2. Tạo `ios-app/project.yml` và `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj` cùng script `ios-app/scripts/package_unsigned_ipa.sh` (có kiểm tra loại trừ `file-mau.xlsx` và tạo `SHA256SUMS.txt`).
3. Tạo `.github/workflows/build-ios-ipa.yml` tự động chọn Simulator UDID, chạy test và xuất artifact `ONT_WiFi_Configurator_Unsigned.ipa` trên GitHub Actions `macos-14`.
4. Viết `tests/test_ios_app_parity.py` kiểm chứng tính toàn vẹn của toàn bộ mã nguồn Swift, cấu hình Xcode, tài nguyên Excel và quy tắc bảo mật ngay trên môi trường Linux.
5. Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` với hướng dẫn đóng gói, cài đặt Sideload `.ipa` và sử dụng trên iOS.

## Todo
- [x] Viết `ExcelAndValidatorTests.swift` và `OntMobileDriverTests.swift`
- [x] Tạo `ios-app/project.yml`, `ONTWiFiConfigurator.xcodeproj`, `package_unsigned_ipa.sh` và `.github/workflows/build-ios-ipa.yml`
- [x] Viết và chạy `tests/test_ios_app_parity.py` cùng toàn bộ test suite hiện có
- [x] Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Success Criteria
- [x] Dự án `ios-app/` có đầy đủ `project.yml`, `ONTWiFiConfigurator.xcodeproj`, script đóng gói `package_unsigned_ipa.sh` và workflow `.github/workflows/build-ios-ipa.yml` hợp lệ.
- [x] Toàn bộ bài kiểm thử tự động trong `tests/` (bao gồm `test_ios_app_parity.py`) và bộ test Android hiện có đều PASS 100%.
- [x] Tài liệu hướng dẫn cài đặt Sideload `.ipa` (Sideloadly / AltStore / TrollStore) và vận hành trên iPhone rõ ràng, dễ hiểu cho KTV.

## Risk Assessment
- **Risk:** Trong `URLSession`, khi request đi qua `URLProtocol`, `request.httpBody` bị chuyển thành `request.httpBodyStream` (`request.httpBody == nil`), và `URLSessionConfiguration.ephemeral` bỏ qua `URLProtocol.registerClass`.
  - *Observable Signal:* `MockLuciURLProtocol` không bắt được request hoặc đọc POST body rỗng trong `OntMobileDriverTests`.
  - *Mitigation:* Truyền `protocolClasses: [MockLuciURLProtocol.self]` trực tiếp vào cả 2 phiên `UnsafeSslSession` trong `OntMobileDriver` và đọc dữ liệu từ `request.httpBodyStream` khi `request.httpBody == nil`.
