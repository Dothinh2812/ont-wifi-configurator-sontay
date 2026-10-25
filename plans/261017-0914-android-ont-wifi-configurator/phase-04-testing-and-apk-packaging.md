---
phase: 4
title: "Phase 4: testing-and-apk-packaging"
status: completed
priority: P1
effort: "3h"
dependencies: [1, 2, 3]
---

# Phase 4: testing-and-apk-packaging

## Overview
Xây dựng bộ kiểm thử tự động (Unit Tests cho trình đọc Excel, bộ kiểm tra hợp lệ `OntInputValidator`, và kiểm thử tích hợp `OntMobileDriver` với `MockWebServer` mô phỏng LuCI CGI cùng kịch bản ngắt kết nối Wi-Fi ở bước 7 cuối cùng), đóng gói APK và cập nhật tài liệu hướng dẫn vận hành cho KTV.

## Requirements
- [x] **Kiểm thử trình đọc/ghi Excel & Validator 2 tầng (`ExcelAndValidatorTest`):**
  - **[Red Team RT-03, RT-05 & RT-09]** Đặt `file-mau.xlsx` trong `android-app/app/src/test/resources/file-mau.xlsx` để kiểm thử tự động trên dữ liệu thực tế (89 dòng) mà không đóng gói dữ liệu nhạy cảm của thuê bao thật vào APK production:
    - Xác nhận đọc chính xác 89 dòng, giữ nguyên số `0` đầu `SO_MAY` (`001560828`), điền đúng fallback `VNPT_{SO_MAY}` / `1234567890` cho các dòng trống Wi-Fi 2.4G, và suy diễn đúng `SSID 5G` / `Pass 5G`.
    - Xác nhận `validateOnImport()` phát hiện đúng **13 dòng thiếu `Mật khẩu PPPoE`**.
    - Xác nhận `validateBeforeRun()` bắt chính xác **5 dòng có mật khẩu Wi-Fi 7 ký tự** (`3011965`, `3820275`, `5091993`, `4091981`, `8042020`) trước khi cho phép chạy.
    - Xác nhận xuất báo cáo `.xlsx` giữ nguyên đủ **30 cột gốc** + ghi đè các trường KTV đã chỉnh sửa (`passPppoe`, `ssid24g`, `pass24g`, `slid`) + thêm 3 cột trạng thái (đã che mật khẩu qua `maskSensitiveCredentials`).
    - Xác nhận nạp lại file Excel không làm mất trạng thái `SUCCESS` và mật khẩu PPPoE đã nhập tay của KTV.
  - **[Red Team RT-08]** Kiểm thử `OntInputValidator`: bắt đúng lỗi `SLID > 10` ký tự, chặn đủ **11 ký tự shell metacharacters** (`&|;$><`\!'"` bao gồm cả dấu `\`), kiểm tra độ phức tạp & giới hạn `8..32` ký tự ASCII không khoảng trắng của mật khẩu Admin, kiểm thử `urlEncodeFormValue` (với `+`, `%`, `=`, khoảng trắng) và `ontEncodeQuery` (`&` $\rightarrow$ `\u007f`).
- [x] **Kiểm thử giao thức LuCI không dây & Kịch bản đứt sóng bước cuối (`OntMobileDriverTest`):**
  - Sử dụng `MockWebServer` mô phỏng toàn bộ các endpoint của ONT GW040-NSv2 (`login.cmd`, `accesscontrolcfg.json`, `wancfg.json`, `addwan.json`, `editwan.json`, `sendwan.cmd`, `gponpasswd.json`, `savelanv4cfg.cmd`, `wirelesscfg.json`, `wlrestart.json`, và **`logout.cmd`** **[Red Team RT-01]**).
  - **[Red Team RT-02]** Xác minh **thứ tự 7 bước & Dynamic Band Ordering**:
    - Khi `connectedBand == 2.4GHz`: Bước 6 cấu hình `wl_unit=1` (5G) + kiểm tra `wlrestart.json` + nghỉ ổn định driver, và Bước 7 cuối cùng cấu hình `wl_unit=0` (2.4G) + gọi `wlrestart.json`.
    - Khi `connectedBand == 5GHz`: Bước 6 cấu hình `wl_unit=0` (2.4G) và Bước 7 cuối cùng cấu hình `wl_unit=1` (5G).
  - **[Red Team RT-02]** Xác minh xử lý **`Commit Ambiguity`** ở Bước 7: mô phỏng server ngắt socket ngay sau khi nhận request `wirelesscfg.json` / `wlrestart.json` ở Bước 7 (`SocketPolicy.DISCONNECT_AT_END` / ngắt luồng phản hồi khi radio tắt sóng) $\rightarrow$ xác nhận `OntMobileDriver` chuyển sang `verifyNewSsidBroadcast` và trả về `SUCCESS`; ngược lại nếu lỗi kết nối trước khi gửi lệnh (`ConnectException`) thì báo lỗi chính xác.
  - **[Red Team RT-01]** Xác minh khối **`finally { logout.cmd }`**: khi xảy ra lỗi ở Bước 3, Bước 4 hoặc Bước 6, `POST /cgi-bin/luci/logout.cmd` luôn được gọi để giải phóng `sessionKey`, ngăn chặn lỗi `"user exist"` (khóa 60s) ở lần chạy tiếp theo.
  - **[Red Team RT-04 & RT-07]** Xác minh cơ chế bảo vệ chống khóa phần cứng 180s (`numberLogin >= 2` ném `OntAccountLockoutWarning`, và khi Retry trên ONT đã đổi pass thì tự động thử `Passwd2@` trước thay vì `admin/admin`), cùng cấu hình WAN lũy thừa gửi đúng `serviceId=1` cho `editwan.json`/`sendwan.cmd` khi `wancfg.json` chỉ chứa `"idx": 2, "vlanId": "11"` (không có trường `"serviceId"`).
- [x] **Đóng gói & Tài liệu hướng dẫn:**
  - **[Red Team RT-05]** Nhúng file mẫu giả lập an toàn `android-app/app/src/main/assets/sample-demo.xlsx` (3 dòng dữ liệu giả lập, không chứa thông tin thuê bao thật) để KTV trải nghiệm nhanh, và đặt `file-mau.xlsx` vào `android-app/app/src/test/resources/file-mau.xlsx` phục vụ kiểm thử tự động.
  - Cập nhật tài liệu hướng dẫn sử dụng trên Android cho KTV (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`).

## Related Code Files
- Create: `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`
- Create: `android-app/app/src/test/java/vn/vnpt/ontconfig/OntMobileDriverTest.kt`
- Create: `android-app/app/src/test/resources/file-mau.xlsx`
- Create: `android-app/app/src/main/assets/sample-demo.xlsx`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. Sao chép `file-mau.xlsx` vào `android-app/app/src/test/resources/file-mau.xlsx` (cho Unit Test) và tạo file `android-app/app/src/main/assets/sample-demo.xlsx` gồm 3 bản ghi mẫu đã ẩn danh (cho tính năng nạp thử trên App).
2. Viết `ExcelAndValidatorTest.kt` kiểm thử toàn diện việc parse 89 dòng từ `file-mau.xlsx`, kiểm tra 2 tầng (`validateOnImport` bắt 13 dòng thiếu PPPoE pass, `validateBeforeRun` bắt 5 dòng mật khẩu Wi-Fi 7 số), tìm kiếm theo `ma_tb`/`soDt` (chuẩn hóa số `0` đầu), merge giữ nguyên chỉnh sửa khi nạp lại file, và xuất báo cáo `.xlsx` giữ đủ 30 cột gốc + 3 cột kết quả.
3. Viết `OntMobileDriverTest.kt` với `MockWebServer` kiểm chứng:
   - Luồng 7 bước chuẩn khi kết nối qua 2.4GHz (`wl_unit=0` đổi ở bước cuối) và đảo băng tần khi kết nối qua 5GHz (`wl_unit=1` đổi ở bước cuối).
   - Kịch bản ngắt kết nối socket khi thực thi Bước 7 (`wirelesscfg.json` + `wlrestart.json`) và xác nhận sóng mới (`verifyNewSsidBroadcast`).
   - Kịch bản lỗi giữa chừng ở Bước 3–6 luôn gọi `POST /cgi-bin/luci/logout.cmd` trong `finally`.
   - Kịch bản Retry sau khi Bước 2 đã đổi mật khẩu tự động thử `Passwd2@` trước (không làm tăng `numberLogin` gây khóa phần cứng), và cấu hình WAN lũy thừa gửi đúng `serviceId=1` khi `wancfg.json` trả về bản ghi thực tế không có khóa `serviceId`.
4. Cập nhật `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` bổ sung mục hướng dẫn cài đặt & vận hành bản Android Native.
## Todo
- [x] Viết `ExcelAndValidatorTest.kt` kiểm thử parse 89 dòng `file-mau.xlsx` và validation
- [x] Viết `OntMobileDriverTest.kt` kiểm thử thứ tự 7 bước và kịch bản đứt sóng Wi-Fi bước cuối
- [x] Chạy kiểm thử tự động và cập nhật tài liệu hướng dẫn vận hành Android

## Success Criteria
- 100% bài kiểm thử trong `ExcelAndValidatorTest` và `OntMobileDriverTest` PASS.
- Chứng minh bằng thực nghiệm (log kiểm thử `MockWebServer`) thứ tự các request HTTP luôn đặt bước đổi Wi-Fi của băng tần đang kết nối ở cuối cùng và xử lý an toàn khi socket bị ngắt lúc restart radio.
