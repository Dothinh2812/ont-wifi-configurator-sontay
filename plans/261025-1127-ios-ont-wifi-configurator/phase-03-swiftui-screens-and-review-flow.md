---
phase: 3
title: "Phase 3: swiftui-screens-and-review-flow"
status: completed
priority: P1
effort: "3h"
dependencies: [1, 2]
---

# Phase 3: swiftui-screens-and-review-flow

## Overview
Xây dựng giao diện người dùng iOS Native bằng **SwiftUI** tối ưu cho thao tác 1 tay trên iPhone ngoài hiện trường: màn hình danh sách & tìm kiếm thuê bao (`SubscriberListView`), màn hình kiểm tra & chỉnh sửa cấu hình trước khi RUN (`ConfigReviewView`), hộp thoại hướng dẫn kết nối Wi-Fi ONT (`WifiGuideSheet`), hộp thoại tiến độ 7 bước (`ExecutionProgressSheet`), và thanh chữ ký cố định (`FooterSignatureBar`).

## Requirements
- [x] **[Red Team RT-01, RT-03, RT-08] Quản lý trạng thái trung tâm (`MainViewModel.swift` - `@MainActor ObservableObject`):**
  - Quản lý danh sách thuê bao (`allRecords`, `filteredRecords`), từ khóa tìm kiếm, bộ lọc trạng thái, bản ghi đang chọn (`selectedRecord`), cấu hình `GlobalSettings`, trạng thái kết nối Wi-Fi `en0` (`en0IpAddress`, `isOnOntSubnet`, `hasActiveVpn`, `isOntGatewayOnline`, `connectedBand`), và tiến trình 7 bước (`stepProgressList`, `isRunningSetup`, `setupResult`, `executionError`).
  - Mọi sự kiện thăm dò Gateway (vòng lặp nền, `NWPathMonitor`, hoặc `scenePhase == .active`) đều đi qua hàm duy nhất `performGatewayProbeOnce()` có kiểm tra `!isRunningSetup && probePauseMutex.tryLock()` (không chặn) và gọi `awaitWifiIpv4Ready` khi vừa chuyển mạng.
  - Khi bấm **RUN**:
    - Khóa `probePauseMutex`, xin quyền chạy nền ngắn hạn `UIApplication.shared.beginBackgroundTask(withName: "ONT7StepSetup")` (để iOS không đóng băng socket nếu KTV lỡ vuốt sang ứng dụng khác hoặc có cuộc gọi đến giữa chừng) và bật `UIApplication.shared.isIdleTimerDisabled = true`.
    - Khởi tạo `OntMobileDriver` với `settlingDelayMs = 200` (giữ kết nối ấm từ Bước 6 sang Bước 7) và truyền callback `onAdminPasswordChanged` để lưu ngay `lastKnownAdminPass` vào SQLite sau Bước 2.
    - Phát âm báo (`AudioServicesPlaySystemSound(1057)`) + rung Haptic (`UINotificationFeedbackGenerator`) khi hoàn tất, và giải phóng `endBackgroundTask` + `isIdleTimerDisabled = false` trong khối `defer`.
- [x] **Màn hình 1 — Danh Sách & Tìm Kiếm Thuê Bao (`SubscriberListView.swift`):**
  - Thanh trạng thái Wi-Fi ONT (`OntWifiStatusBanner`): hiển thị rõ trạng thái Gateway (`ONLINE - Sẵn sàng đổ cấu hình` màu xanh lá hoặc `Chưa kết nối ONT (192.168.1.1)`), địa chỉ IP Wi-Fi `en0` hiện tại, cảnh báo nếu phát hiện VPN đang bật, nút chọn băng tần đang kết nối (`2.4GHz` / `5GHz`), và nút **"Bắt Wi-Fi ONT"**.
  - Cụm nút thao tác Excel: **"Nạp Excel (.xlsx)"** (sử dụng `.fileImporter` của SwiftUI có `startAccessingSecurityScopedResource()`), **"Nạp Mẫu Demo"** (3 bản ghi mẫu ẩn danh từ Bundle), và **"Xuất Báo Cáo (.xlsx)"** (chia sẻ qua `UIActivityViewController` sang Zalo, AirDrop, Mail, Files và tự động xóa file tạm sau khi chia sẻ xong).
  - Ô tìm kiếm tức thì (`Account`, `SO_MAY`, `TEN_TB`, `SO_DT` có/không có số `0` đầu) và thanh Chip lọc theo trạng thái (`Tất cả`, `Chờ`, `Xong`, `Lỗi / Thiếu TT`).
- [x] **[Red Team RT-10] Màn hình 2 — Xác Nhận & Chỉnh Sửa Cấu Hình Trước Khi RUN (`ConfigReviewView.swift`):**
  - Hiển thị thông tin hành chính của thuê bao và cho phép sửa trực tiếp tất cả tham số dịch vụ (`SO_MAY`, `SLID / Mã MEN` kèm bộ đếm `x/10`, `Account PPPoE`, `Mật khẩu PPPoE` có nút ẩn/hiện mặc định ẩn, `SSID/Pass Wi-Fi 2.4G & 5G`, `Mật khẩu Admin mới`).
  - Toàn bộ ô nhập mã/mật khẩu tắt tự động sửa lỗi và viết hoa chữ cái đầu (`.autocorrectionDisabled(true)` & `.textInputAutocapitalization(.never)`).
  - Công tắc **"Tự động đồng bộ Wi-Fi 5G theo 2.4G"** (bật mặc định).
  - Nếu phát hiện mật khẩu Wi-Fi chỉ có 7 chữ số (5 dòng mất số `0` đầu trong `file-mau.xlsx`), hiển thị nút 1 chạm **"Thêm số 0 ở đầu (0...)"** để sửa và đồng bộ ngay lập tức.
  - **Thanh chọn băng tần Wi-Fi đang kết nối (`Đang bắt 2.4GHz` / `Đang bắt 5GHz`) đặt ngay phía trên nút RUN** để KTV luôn nhìn thấy và chọn đúng băng tần thực tế (đảm bảo Bước 7 luôn đổi băng tần đang kết nối ở cuối cùng).
  - Nút **"LƯU THAY ĐỔI"** và nút **"RUN - ĐỔ CẤU HÌNH ONT"** (chỉ cho phép bấm khi `validateBeforeRun` hợp lệ 100%).
- [x] **[Red Team RT-10] Hộp Thoại Hướng Dẫn Bắt Wi-Fi ONT (`WifiGuideSheet.swift`), Tiến Độ 7 Bước (`ExecutionProgressSheet.swift`) & Bảo Mật Màn Hình:**
  - `WifiGuideSheet`: Hiển thị trạng thái IP `en0` thời gian thực, bộ chọn băng tần `2.4GHz / 5GHz`, hướng dẫn KTV chọn sóng `GW040-NSv2_2.4G_XXXXXX` (hoặc quét QR đáy modem bằng Camera iOS), nút **"Mở Cài Đặt Wi-Fi iOS"** và nút **"Kiểm tra lại kết nối Gateway (192.168.1.1)"**.
  - `ExecutionProgressSheet`: Khóa vuốt đóng khi đang chạy (`.interactiveDismissDisabled(isRunningSetup)`), hiển thị trực quan từng bước `1/7 .. 7/7`, thẻ tổng kết Wi-Fi mới kèm nút **"Sao chép mật khẩu Wi-Fi mới"** (sao chép vào `UIPasteboard.general` với cờ bảo mật `.localOnly: true` và thời hạn tự xóa `120s` để không phát tán qua Universal Clipboard).
  - Tự động làm mờ màn hình (`.blur(radius: 12)` / `.privacySensitive()`) khi `scenePhase != .active` để iOS không chụp ảnh mật khẩu thuê bao vào App Switcher Snapshot.
- [x] **Thanh Chữ Ký Cố Định (`FooterSignatureBar.swift`):**
  - Cố định ở đáy màn hình: `"Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186"`, chạm để sao chép SĐT vào `UIPasteboard.general` hoặc gọi điện hỗ trợ.

## Related Code Files
- Create: `ios-app/ONTWiFiConfigurator/ONTWiFiConfiguratorApp.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/MainViewModel.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/Screens/SubscriberListView.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/Components/WifiGuideSheet.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/Components/ExecutionProgressSheet.swift`
- Create: `ios-app/ONTWiFiConfigurator/UI/Components/FooterSignatureBar.swift`

## Implementation Steps
1. Xây dựng `MainViewModel.swift` kết nối `AppDatabase`, `ExcelManager`, `IosWifiMonitor`, và `OntMobileDriver` (với `beginBackgroundTask`, `probePauseMutex.tryLock()`, và `settlingDelayMs = 200`).
2. Xây dựng `FooterSignatureBar.swift`, `WifiGuideSheet.swift`, và `ExecutionProgressSheet.swift` (kèm local-only expiring pasteboard).
3. Xây dựng `SubscriberListView.swift` và `ConfigReviewView.swift` (hiển thị chọn băng tần `2.4GHz / 5GHz` ngay trên màn hình Review, nút thêm số `0` đầu cho mật khẩu 7 số, `.fileImporter` và xuất báo cáo `.xlsx`).
4. Xây dựng entry point `ONTWiFiConfiguratorApp.swift` (bỏ qua khởi tạo `MainViewModel` khi chạy trong môi trường `XCTestConfigurationFilePath` để tránh tranh chấp DB/mạng khi test, và bật lớp phủ làm mờ khi `scenePhase != .active`).

## Todo
- [x] Xây dựng `MainViewModel.swift` quản lý trạng thái UI, thăm dò Gateway và thực thi 7 bước
- [x] Xây dựng `SubscriberListView.swift` và `ConfigReviewView.swift`
- [x] Xây dựng `WifiGuideSheet.swift`, `ExecutionProgressSheet.swift`, `FooterSignatureBar.swift` và `ONTWiFiConfiguratorApp.swift`

## Success Criteria
- [x] Khi nạp file `.xlsx` từ ứng dụng Tệp (Files / Zalo trên iOS), hàm `url.startAccessingSecurityScopedResource()` được gọi và đóng đúng cách trong `defer { url.stopAccessingSecurityScopedResource() }`.
- [x] Sửa `SSID 2.4G` hoặc bấm nút `"Thêm số 0 ở đầu"` tự động đồng bộ sang `5G` và mở khóa nút `RUN` ngay khi dữ liệu hợp lệ.
- [x] Màn hình iPhone giữ sáng suốt quá trình chạy 7 bước (`isIdleTimerDisabled = true` + `beginBackgroundTask`), hộp thoại tiến trình không bị vuốt đóng nhầm khi đang chạy, và tự động làm mờ khi chuyển sang App Switcher.

## Risk Assessment
- **Risk:** Khi KTV chuyển sang Cài đặt Wi-Fi hoặc có cuộc gọi đến đúng lúc đang chạy Bước 2–7, iOS có thể đóng băng tiến trình nếu không đăng ký `beginBackgroundTask`.
  - *Observable Signal:* Socket bị ngắt với `ECONNABORTED` và `logout.cmd` không kịp gửi đi.
  - *Mitigation:* Đăng ký `UIApplication.shared.beginBackgroundTask(withName: "ONT7StepSetup")` trước khi gọi `executeMobileSetup()` và giải phóng sau khi `logout()` trong khối `defer` hoàn tất.
