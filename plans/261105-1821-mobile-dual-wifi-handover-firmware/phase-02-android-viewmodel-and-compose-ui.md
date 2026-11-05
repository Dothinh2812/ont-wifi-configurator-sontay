---
phase: 2
title: "Phase 2: Android ViewModel, Dynamic Rebind & Compose Progress UI"
status: completed
priority: P1
effort: "1.5h"
dependencies: [1]
---

# Phase 2: Android ViewModel, Dynamic Rebind & Compose Progress UI

## Context Links
- Red Team Report: [`reports/red-team-261105-mobile-dual-wifi-handover-firmware.md`](./reports/red-team-261105-mobile-dual-wifi-handover-firmware.md)
- ViewModel & Activity: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`, `android-app/app/src/main/java/vn/vnpt/ontconfig/MainActivity.kt`
- Screens & Components: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`, `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`

## Overview
Kết nối tầng giao diện Jetpack Compose và `MainViewModel.kt` với quy trình 2 lần kết nối mới (8 bước khi tắt Firmware / 9 bước khi bật Firmware), kiểm tra tương thích bảo mật Wi-Fi trước Bước 1 (`[Red Team RT-06]`), sửa hàm `rebindOntWifiSocketFactory` nhắm đúng `SSID_B` với `SocketFactory` non-null (`[Red Team RT-03]`), hỗ trợ mượt máy chỉ có 2.4GHz (`[Red Team RT-08]`), dọn file `.xlsx` tạm trong `cacheDir/exports/` (`[Red Team RT-10]`), và gỡ bỏ toàn bộ giao diện Bước 8 thủ công cũ.

## Key Insights
- **`[Red Team RT-03]`:** Hàm `rebindOntWifiSocketFactory` hiện tại (`MainViewModel.kt:715-738`) gọi `bindCurrentWifiNetworkIfConnected()` ở dòng 720 (dễ tóm nhầm lại mạng `Band A` xuất xưởng hoặc Wi-Fi nhà khi `specifierWifiNetwork` vừa rớt lúc reload `wlmngr`), bỏ quên `passphrase` & `securityMode`, và dùng `timeoutMs = 6000L` quá ngắn (làm đóng/mở lại popup hệ thống liên tục mỗi 6s). Cần truyền đầy đủ `(targetSsid, targetPass, targetSecMode, targetBand, timeoutMs = 25_000L)` và chỉ tái sử dụng `Network` đang có nếu trùng với `specifierWifiNetwork` hoặc có SSID đọc được khớp chính xác `targetSsid`.
- **`[Red Team RT-06]`:** Chặn sớm trước Bước 1 nếu KTV chọn `wpa_psk` thuần trên Android 10+ hoặc `wpa3_psk` trên máy không hỗ trợ SAE / Android 8–9, tránh đổi mật khẩu ONT ở Bước 1–6 rồi mới kẹt ở Bước 7.
- **`[Red Team RT-10]`:** Cần cập nhật cả điểm gọi `MainViewModel.kt:956` (`joinNewlyConfiguredWifi` đang kiểm tra `!res.manualWifiStepPending`), xóa `step7StartMicros` / `onVerifyNewSsid` (`:808, 819-825`), và xóa các file báo cáo `BaoCao_CauHinh_ONT_*.xlsx` cũ trong `cacheDir/exports/` khi khởi động app & trước mỗi lần xuất mới để không lưu tồn đọng PII thuê bao.

## Requirements
- **Functional:**
  - **Trong `MainViewModel.kt` (`:51-1209`):**
    - Cập nhật `MainUiState`: thay `defaultSevenSteps()` / `defaultEightStepsWithFirmware()` bằng `defaultEightStepsDualWifi()` (8 bước) và `defaultNineStepsWithFirmware()` (9 bước có Bước 9 Update Firmware cuối cùng); thêm `is5GHzSupported: Boolean = true`; xóa `manualWifiError` và `runManualConnectedWifiStep()`.
    - Cập nhật `rebindOntWifiSocketFactory(ssid: String, passphrase: String?, securityMode: String, band: WifiBand, timeoutMs: Long = 25_000L): SocketFactory?` (`[Red Team RT-03]`): truyền `bssid = null`, kiểm tra mạng hiện có chỉ khi khớp `specifierWifiNetwork` hoặc tên SSID khớp chính xác `ssid`, ngược lại gọi `wifiConnector.connectToOntWifi(ssid = ssid, bssid = null, passphrase = passphrase, timeoutMs = timeoutMs, securityMode = securityMode)`.
    - Trong `runOntConfiguration()`:
      - Kiểm tra `wifiConnector.validateHandoverSecurityCapability(settings.wifiSecurityMode)` **trước Bước 1** (`[Red Team RT-06]`); nếu không tương thích thì hiển thị thông báo lỗi ngay mà không chạm vào ONT.
      - Duy trì trạng thái mục tiêu động (`currentTargetSsid`, `currentTargetPass`, `currentTargetBand`, `currentTargetSecMode`): ở Lần 1 giữ thông tin mạng ban đầu; ngay trong `onSwitchToSecondaryWifi` cập nhật sang `(ssid, password, band, secMode)` để mọi lời gọi `onRebindWifiNetwork` ở Bước 8–9 luôn rebind đúng mạng của Lần 2 và từ chối nếu `SocketFactory == null` (`[Red Team RT-03]`).
      - Truyền `allowSingleBand24gFallback = !wifiConnector.is5GHzBandSupported()` xuống `OntMobileDriver` để máy chỉ có 2.4GHz tự động chạy nhánh đổi `5GHz` + `2.4GHz` ở Lần 1 rồi kết nối lại vào `2.4GHz` mới ở Bước 7 (`[Red Team RT-08]`).
      - Xóa `step7StartMicros` và callback `onVerifyNewSsid` (`[Red Team RT-09]`).
      - Khi hoàn tất: giữ `suppressAutoBindAfterCompletion = true`, lưu `SubscriberStatus.SUCCESS` với `errorMessage = ""`.
    - Cập nhật `joinNewlyConfiguredWifi` (`MainViewModel.kt:951-972`) bỏ kiểm tra `!res.manualWifiStepPending`, và dọn dẹp thư mục `cacheDir/exports/` khi `init` + trước khi tạo file báo cáo mới trong `exportAndShareExcelReport()` (`MainViewModel.kt:1133-1178`) (`[Red Team RT-10]`).
  - **Trong `MainActivity.kt` (`:209-228`):**
    - Gỡ bỏ `manualWifiError`, `onRunManualConnectedWifi` và nhánh điều kiện `if (uiState.setupResult?.manualWifiStepPending != true)` trong `onOpenWifiScanner`.
  - **Trong `ConfigReviewScreen.kt` (`:760-876`):**
    - Cập nhật tiêu đề & chú thích **Section 4**: *"4. Cập Nhật Firmware ONT (Bước cuối sau khi đổi xong cả 2 Wi-Fi)"* mô tả rõ trình tự tự động 2 lần kết nối.
    - Nếu `!uiState.is5GHzSupported`, hiển thị ghi chú thông tin nhẹ nhàng rằng máy chỉ có Wi-Fi 2.4GHz nên ứng dụng sẽ tự động kết nối lại qua sóng 2.4GHz mới ở Lần 2 (`[Red Team RT-08]`).
  - **Trong `ExecutionProgressSheet.kt` (`:61-561`):**
    - Xóa toàn bộ UI `manualWifiStepPending` / `manualWifiError` / `onRunManualConnectedWifi`.
    - Ở Bước 7 đang `RUNNING`, hiển thị thẻ nhắc KTV bấm **"Kết nối / Connect"** khi hộp thoại ghép nối hệ thống của Android hiện lên (kèm thông tin đang chờ ONT cập nhật phát sóng tên Wi-Fi mới sau Lần 1).
    - Khi hoàn tất với `setupResult.firmwareUpdated == true`, hiển thị thẻ **"🔄 ONT Đang Tự Ghi Flash & Khởi Động Lại (~2 phút 20 giây)"** kèm đồng hồ đếm ngược tham khảo `150s -> 0s` không chặn thao tác Xuất Excel gửi Zalo.

## Related Code Files
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/MainActivity.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`

## Implementation Steps
1. Cập nhật `MainViewModel.kt`: danh sách 8/9 bước, kiểm tra `validateHandoverSecurityCapability` trước Bước 1, sửa `rebindOntWifiSocketFactory` (`25s`, có `passphrase` & `securityMode`), nối `onSwitchToSecondaryWifi` + `allowSingleBand24gFallback`, sửa `joinNewlyConfiguredWifi` (`:956`), xóa `runManualConnectedWifiStep()` / `onVerifyNewSsid`, và dọn `cacheDir/exports/`.
2. Cập nhật `MainActivity.kt` gỡ bỏ các tham số thủ công cũ.
3. Cập nhật `ConfigReviewScreen.kt` mô tả luồng mới và hiển thị ghi chú tự động tương thích trên máy `2.4GHz-only`.
4. Cập nhật `ExecutionProgressSheet.kt` hiển thị hướng dẫn popup Bước 7, đồng hồ đếm ngược tham khảo `150s` chờ ONT reboot sau Bước 9, và xóa giao diện Bước 8 thủ công.

## Todo List
- [x] Cập nhật `MainViewModel.kt` (8/9 bước, pre-run security check, `rebindOntWifiSocketFactory` 25s, dynamic target, `joinNewlyConfiguredWifi`, xóa `runManualConnectedWifiStep`, dọn `cacheDir/exports/`)
- [x] Cập nhật `MainActivity.kt` và `ConfigReviewScreen.kt`
- [x] Cập nhật `ExecutionProgressSheet.kt` (gợi ý popup Bước 7 + đếm ngược chờ ONT reboot `150s`, gỡ sạch UI thủ công cũ)

## Success Criteria
- [x] Biên dịch sạch 100% không còn tham chiếu tới `manualWifiStepPending`, `manualWifiError`, `runManualConnectedWifiStep` hay `onVerifyNewSsid`.
- [x] Ở Lần kết nối 2, `rebindOntWifiSocketFactory` luôn dùng đúng `SSID_B`, mật khẩu và chế độ bảo mật mới với `timeoutMs = 25_000L`, không bao giờ bám nhầm lại mạng `Band A` cũ hay rò qua 4G/5G.

## Risk Assessment
- **Rủi ro — Hộp thoại hệ thống `WifiNetworkSpecifier` bị tự đóng nếu timeout mỗi lần gọi quá ngắn:**
  - *Observable signal:* KTV chưa kịp bấm *Kết nối* trong 6 giây thì hộp thoại biến mất và hiện lại.
  - *Pre-decided response:* Nâng mỗi lượt `requestNetwork` lên `45s` ở Bước 7 (và `25s` khi rebind ở Bước 8/9) trong tổng ngân sách `120s` / `60s`.
