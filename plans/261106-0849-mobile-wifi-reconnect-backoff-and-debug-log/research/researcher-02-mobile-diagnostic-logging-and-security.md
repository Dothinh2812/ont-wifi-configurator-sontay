---
title: "Research 02: Mobile In-App Diagnostic Logger, Credential Redaction & Field Export Architecture"
date: 2026-11-06
status: completed
---

# Research 02: Thiết Kế Hệ Thống Ghi Log Tiến Trình Trong Ứng Dụng (Android & iOS) Phục Vụ Debug Thực Địa

## Summary

Hiện tại toàn bộ mã nguồn `android-app/` và `ios-app/` **hoàn toàn chưa có hệ thống ghi log** (`0` lời gọi `android.util.Log`, `OSLog`, hay lưu file nhật ký). Khi kỹ thuật viên (KTV) chạy thử trên điện thoại thật ngoài hiện trường không cắm cáp ADB/Xcode, các ngoại lệ mạng/Wi-Fi bị nén thành 1 dòng thông báo cuối cùng nên không thể biết chính xác ONT rớt ở pha quét beacon, pha xin DHCP, pha bắt tay TLS, hay pha đọc `wlAssociatedDevice`.

## Findings & Design Constraints

1. **Hạ tầng lưu trữ & chia sẻ file sẵn có trong dự án:**
   - **Android (`android-app/`):**
     - `AndroidManifest.xml:21-23` đặt `android:allowBackup="false"`. Thư mục nội bộ an toàn cho dữ liệu bền vững là `File(context.noBackupFilesDir, "logs")`.
     - `res/xml/file_paths.xml:3` đã khai báo `<cache-path name="exported_reports" path="exports/" />` dùng cho `FileProvider` (`${applicationId}.fileprovider` tại `MainViewModel.kt:1088-1092`). Xuất file `ONT_Debug_Log_<timestamp>.txt` vào `File(appContext.cacheDir, "exports")` dùng lại ngay `FileProvider` hiện có mà không cần mở rộng phạm vi chia sẻ thư mục nhạy cảm!
     - `MainViewModel.cleanupStaleExportFiles` (`:1148-1158`) hiện chỉ xóa `*.xlsx` — cần mở rộng xóa cả file tạm `ONT_Debug_Log_*.txt` khi khởi động / xuất mới.
   - **iOS (`ios-app/`):**
     - `AppDatabase.swift` và `MainViewModel.swift:457-465` lưu dữ liệu bền vững dưới `Application Support` với `FileProtectionType.completeUntilFirstUserAuthentication` và `isExcludedFromBackup = true` (tránh lỗi I/O khi màn hình khóa ở Bước 7 `[Red Team RT-07]`).
     - Xuất file chia sẻ tạm thời tại `FileManager.default.temporaryDirectory.appendingPathComponent("ONT_Debug_Log_<timestamp>.txt")` với `.completeFileProtectionUntilFirstUserAuthentication`, chia sẻ qua `UIActivityViewController` (giống `shareExcelReport` trong `SubscriberListView.swift`) và dọn dẹp trong `cleanupStaleExportFiles()` (`MainViewModel.swift:898-910`).
     - Mọi file `.swift` mới tạo trong `ios-app/ONTWiFiConfigurator/` bắt buộc phải đăng ký đồng bộ vào `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj` (`PBXBuildFile`, `PBXFileReference`, `PBXGroup`, `PBXSourcesBuildPhase`) và không thêm `.entitlements` trả phí (`tests/test_ios_app_parity.py:154-161, 389-394`).

2. **Bảo mật thông tin nhạy cảm trong Log (`PII & Credential Redaction`):**
   - KTV thường chia sẻ file `.txt` log qua Zalo/Telegram để nhờ hỗ trợ kỹ thuật.
   - Do đó `AppDiagnosticLogger` phải áp dụng **2 lớp che mật khẩu tự động** trước khi lưu RAM/ghi đĩa:
     1. Gọi `OntInputValidator.maskSensitiveCredentials(rawMessage)` (che mọi cặp key-value/JSON `pppPassword`, `pass`, `newPass`, `oldPass`, `wl_wpa_psk`, `nvramWlWpaPsk`, `nvramWlRadiusKey`, `nvramWlKey*`, `password`, `sessionKey`, `data`).
     2. Cơ chế `registerActiveSecrets(Collection<String>)` / `clearActiveSecrets()`: đăng ký các giá trị mật khẩu thực tế của phiên chạy (`adminDefaultPass`, `adminNewPass`, `passPppoe`, `wifi24Pass`, `wifi5Pass` có độ dài `>= 4`) để thay thế mọi chuỗi khớp trực tiếp bằng `"***"`, đồng thời **giữ nguyên** các thông tin chẩn đoán cần thiết (`SSID`, `BSSID`, 12 ký tự đầu SHA-256 cert, `IP 192.168.1.x`, kênh/tần số/RSSI, mã HTTP, thời gian phản hồi `ms`).

3. **Hiệu năng & Tương thích Unit Test:**
   - Bộ đệm vòng trong RAM (`ArrayDeque` tối đa `1_500` dòng `DiagnosticLogEntry`) kết hợp ghi đĩa bất đồng bộ xoay vòng 2 file (`ont_debug.log` + `ont_debug.prev.log`, tối đa `512 KB`/file = `1 MB` tổng dung lượng).
   - Trên Android, bọc lời gọi `android.util.Log.d/w/e` trong `runCatching` để các bài JUnit chạy trên JVM thuần (`./gradlew testDebugUnitTest` không có `Robolectric`) không bao giờ ném `RuntimeException: Method ... in android.util.Log not mocked`.
   - Tích hợp vào `OntMobileDriver`, `OntWifiConnector`, và `IosWifiMonitor` qua callback tùy chọn có giá trị mặc định `onLog: ((LogLevel, String, String) -> Unit)? = null` (hoặc closure tương đương trên Swift) để không phá vỡ bất kỳ lời gọi khởi tạo nào trong bộ test hiện hữu.

## Recommendations (UI & UX for Field Debugging)

1. **Ngay trên `ExecutionProgressSheet` (Android & iOS):**
   - Khối mở rộng **"Nhật ký tiến trình chi tiết (Debug Log)"** hiển thị trực tiếp các dòng log thời gian thực (`HH:mm:ss.SSS [TAG] ...`) bằng font monospace; tự động bung mở khi có lỗi (`executionError != null`) để thấy ngay nguyên nhân gốc.
   - Hàng nút thao tác nhanh: **"Chép Log"** và **"Chia sẻ Log (.txt)"** ngay trong màn hình tiến trình/báo lỗi.
2. **Trên thanh công cụ màn hình chính & màn hình cấu hình (`SubscriberList` & `ConfigReview`):**
   - Nút mở hộp thoại/sheet **`DiagnosticLogDialog` (Android) / `DiagnosticLogSheet` (iOS)** hỗ trợ lọc nhanh (`Tất cả`, `Cảnh báo & Lỗi`, `Wi-Fi & Handover`), sao chép toàn bộ, xuất/chia sẻ file `.txt` kèm thông tin thiết bị (OS, hỗ trợ 5GHz/WPA3-SAE, GPS, IP `en0`/VPN), và xóa sạch nhật ký.

## Unresolved Questions
None.
