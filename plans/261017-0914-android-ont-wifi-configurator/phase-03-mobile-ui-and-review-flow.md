---
phase: 3
title: "Phase 3: mobile-ui-and-review-flow"
status: completed
priority: P1
effort: "4h"
dependencies: [1, 2]
---

# Phase 3: mobile-ui-and-review-flow

## Overview
Xây dựng giao diện người dùng Android Native bằng **Jetpack Compose (Material 3)** tối ưu cho thao tác 1 tay ngoài hiện trường: màn hình chọn `ma_tb` từ file Excel, màn hình xác nhận & chỉnh sửa cấu hình trước khi bấm `RUN`, thanh trạng thái kết nối Wi-Fi ONT, và hộp thoại theo dõi tiến độ 7 bước.

## Requirements
- [x] **Màn hình 1 - Danh Sách & Tìm Kiếm Thuê Bao (`SubscriberListScreen`):**
  - <!-- Updated: Validation Session 1 - Preserve updated password columns on .xlsx export for re-import, mask error logs --> Nút **"Nạp file Excel (.xlsx)"** trên thanh công cụ (qua SAF `ContentResolver`, tự động merge giữ lại các thuê bao đã `SUCCESS` hoặc đã nhập bổ sung mật khẩu PPPoE nếu nạp lại file) + nút **"Nạp dữ liệu mẫu Demo"** (3 bản ghi giả lập an toàn từ `assets/sample-demo.xlsx` để trải nghiệm giao diện mà không lộ PII thật trong APK, **[Red Team RT-05]**), và nút **"Xuất Báo Cáo (.xlsx)"** chia sẻ nhanh qua Zalo (giữ nguyên đủ 30 cột gốc + ghi đè các ô KTV đã sửa gồm cả cột mật khẩu PPPoE/Wi-Fi để file có thể nạp lại đồng bộ bản Windows + 3 cột kết quả đã che thông tin nhạy cảm trong log lỗi qua `maskSensitiveCredentials`, **[Red Team RT-09]**).
  - Ô tìm kiếm tức thì (`SearchBar`) cho phép gõ vài ký tự của `ma_tb` (`Account`), `SO_MAY`, `TEN_TB`, hoặc `SO_DT` (hỗ trợ gõ có hoặc không có số `0` ở đầu) để lọc ngay thuê bao cần lắp đặt.
  - Bộ lọc Chip theo trạng thái: `Tất cả`, `Chờ cấu hình`, `Thành công`, `Cần bổ sung TT / Lỗi`.
  - Mỗi thẻ thuê bao (`SubscriberCard`) hiển thị rõ: `Account (ma_tb)`, `SO_MAY`, `TEN_TB`, `DIACHI_TB`, `SSID 2.4G` và nhãn trạng thái màu sắc trực quan.
- [x] **Thanh Điều Khiển Wi-Fi ONT (`OntWifiStatusBanner`):**
  - Hiển thị trạng thái kết nối Wi-Fi hiện tại của điện thoại: tên SSID, băng tần (`2.4GHz` / `5GHz`), trạng thái Gateway (`192.168.1.1` Online/Offline đã xác thực chữ ký HTML `"iGate"`/`"GW040"`/`"VNPT"` với bộ chống nhiễu 3 lần liên tiếp).
  - **[Red Team RT-10] Cơ chế tạm dừng thăm dò nền (`Probe Pause Barrier`):** Khi KTV bấm `RUN`, `MainViewModel` tự động khóa `probeMutex` để tạm dừng ngay việc thăm dò ngầm của `OntWifiStatusBanner` và chờ request đang bay hoàn tất trước khi `OntMobileDriver` chạy Bước 1–7, sau đó mở lại trong khối `finally` (tránh xung đột socket trên web server đơn luồng của ONT).
  - Nút **"Tìm & Bắt Wi-Fi ONT"** mở danh sách các sóng `GW040-NSv2_2.4G_XXXXXX` / `GW040-NSv2_5G_XXXXXX` quét được gần nhất để KTV chạm 1 lần là kết nối và bind mạng.
- [x] **Màn hình 2 - Xác Nhận & Chỉnh Sửa Cấu Hình Trước Khi RUN (`ConfigReviewScreen`):**
  - Khi KTV chạm vào 1 `ma_tb`, mở màn hình chi tiết hiển thị toàn bộ thông tin của thuê bao đó:
    - **Khối Thông tin khách hàng (Read-only):** Tên thuê bao, Địa chỉ, SĐT, Tổ địa bàn, NVKT.
    - **Khối Tham số Dịch vụ & GPON (Cho phép sửa trực tiếp):**
      - `Số máy (SO_MAY)` & `SLID / Mã MEN` (hiển thị bộ đếm ký tự `x/10`, báo đỏ ngay nếu $> 10$ ký tự hoặc chứa ký tự ngoài bảng ASCII).
      - `Account PPPoE (ma_tb)` & `Mật khẩu PPPoE` (nút 👁️ ẩn/hiện mật khẩu; nếu dòng Excel bị khuyết mật khẩu PPPoE thì tô viền đỏ và tự động focus để KTV nhập bổ sung).
    - **Khối Tham số Wi-Fi (Sẽ thực thi ở bước cuối cùng):**
      - `Tên Wi-Fi 2.4G (SSID)` & `Mật khẩu Wi-Fi 2.4G`.
      - <!-- Updated: Validation Session 1 - Keep raw 7-char value on import, provide 1-tap Prepend '0' button on Review Screen --> **[Red Team RT-03]** Kiểm tra `validateBeforeRun()` realtime: giữ nguyên dữ liệu gốc khi import, nếu mật khẩu Wi-Fi chỉ có 7 ký tự chữ số do Excel mất số `0` đầu ngày tháng năm sinh (như 5 dòng `3011965`, `3820275`, `5091993`, `4091981`, `8042020` trong `file-mau.xlsx`), hiển thị cảnh báo đỏ `7/8 ký tự (chuẩn WPA2 yêu cầu >= 8 ký tự)` kèm nút gợi ý 1 chạm **"Thêm số 0 ở đầu (0...)"** (tự động điền `03011965` và đồng bộ sang 5G) để KTV xác nhận sửa tức thì trước khi bấm `RUN`.
      - Công tắc **"Tự động đồng bộ Wi-Fi 5G theo 2.4G"** (bật mặc định: khi KTV sửa `SSID 2.4G` hoặc `Pass 2.4G`, ô `SSID 5G` tự động cập nhật thành `{SSID 2.4G} 5G` $\le 32$ ký tự và `Pass 5G` cập nhật theo `Pass 2.4G`; KTV có thể tắt công tắc để nhập riêng).
      - Ghi chú rõ trên giao diện: *"⚡ Lưu ý: Bước đổi tên & mật khẩu Wi-Fi của băng tần đang kết nối (2.4G) sẽ được thực hiện ở bước cuối cùng (Bước 7/7) để tránh ngắt kết nối giữa chừng."*
    - **Cấu hình bàn phím an toàn:** Toàn bộ ô nhập mã/mật khẩu phải tắt `autoCorrect` và tắt `autoCapitalization` (`KeyboardCapitalization.None`).
  - Nút **"LƯU THAY ĐỔI"** (cập nhật vào Room DB) và nút lớn **"RUN - ĐỔ CẤU HÌNH ONT"** (chỉ bật khi `validateBeforeRun()` hợp lệ 100% và đã kết nối tới ONT).
- [x] **Hộp Thoại Tiến Độ 7 Bước & Thông Báo Hoàn Tất (`ExecutionSheet`):**
  - **[Red Team RT-06] Giữ sáng màn hình & Khóa đóng hộp thoại khi đang chạy:** Khi đang thực thi Bước 1–7, bật cờ `FLAG_KEEP_SCREEN_ON` và chặn thao tác vuốt đóng / bấm ra ngoài (`confirmValueChange = { !isRunning }`) để hệ điều hành Android 10–14 không thu hồi kết nối `WifiNetworkSpecifier` giữa chừng.
  - Hiển thị danh sách 7 bước với icon trạng thái trực quan (`⏳ Đang chờ`, `🔄 Đang chạy`, `✅ Thành công`, `❌ Lỗi`).
  - <!-- Updated: Validation Session 1 - Auto-unbind to 4G/5G + optional Join New Wi-Fi button --> Khi hoàn tất Bước 7 (đổi Wi-Fi 2.4G & restart radio), tự động gỡ `bindProcessToNetwork(null)` để khôi phục 4G/5G ngay lập tức, phát âm thanh báo hiệu (`ToneGenerator`) + rung xúc giác (`Vibrator`), hiển thị thẻ tổng kết tên Wi-Fi mới & mật khẩu kèm nút tùy chọn **"Kết nối vào Wi-Fi mới để kiểm tra mạng"** (qua `WifiNetworkSuggestion` / `Settings.Panel.ACTION_WIFI` khi KTV bấm).
  - Cố định ở cuối màn hình chính: `"Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186"` hỗ trợ chạm để sao chép SĐT vào Clipboard hoặc mở quay số nhanh.

## Related Code Files
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/SubscriberListScreen.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/OntWifiScannerDialog.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/FooterSignatureBar.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/MainActivity.kt`

## Implementation Steps
1. Xây dựng `MainViewModel.kt` quản lý `StateFlow` cho danh sách thuê bao, từ khóa tìm kiếm, bản ghi đang chọn để review/edit, trạng thái kết nối Wi-Fi ONT và tiến độ 7 bước cấu hình.
2. Xây dựng `SubscriberListScreen.kt` với thanh tìm kiếm đa năng, bộ lọc trạng thái, thanh trạng thái Wi-Fi ONT và Footer chữ ký.
3. Xây dựng `ConfigReviewScreen.kt` cho phép KTV kiểm tra, chỉnh sửa trực tiếp các tham số, đồng bộ realtime `Wi-Fi 2.4G -> 5G`, kiểm tra lỗi tức thì và kích hoạt nút `RUN`.
4. Xây dựng `OntWifiScannerDialog.kt` và `ExecutionProgressSheet.kt` hiển thị tiến trình 7 bước, xử lý âm thanh/rung hoàn tất và nút kết nối nhanh vào Wi-Fi mới sau khi đổi tên.

## Todo
- [x] Xây dựng `MainViewModel.kt` kết nối Room DB, `ExcelManager`, `OntWifiConnector` và `OntMobileDriver`
- [x] Xây dựng `SubscriberListScreen.kt` và `OntWifiScannerDialog.kt`
- [x] Xây dựng `ConfigReviewScreen.kt` (Review, Inline Edit, Auto-sync 5G, Pre-RUN Validation)
- [x] Xây dựng `ExecutionProgressSheet.kt`, cảnh báo âm thanh/rung và `FooterSignatureBar.kt`

## Success Criteria
- Chọn 1 `ma_tb` bất kỳ từ danh sách nạp đúng 100% thông tin lên màn hình Review; sửa `SSID 2.4G` tự động cập nhật `SSID 5G`.
- Nút `RUN` tự động khóa kèm thông báo rõ nguyên nhân nếu `validateBeforeRun()` phát hiện `Pass PPPoE` trống, `SLID > 10` ký tự, hoặc `Pass Wi-Fi < 8` ký tự (kèm nút thêm nhanh số `0` ở đầu cho 5 dòng mật khẩu 7 số trong `file-mau.xlsx`).
- Khi bấm `RUN`, tiến trình thăm dò nền của `OntWifiStatusBanner` tự động tạm dừng qua `Mutex`, màn hình giữ sáng (`FLAG_KEEP_SCREEN_ON`), tiến trình 7 bước hiển thị mượt mà và tự động gỡ `bindProcessToNetwork(null)` sau khi hoàn tất Bước 7.
