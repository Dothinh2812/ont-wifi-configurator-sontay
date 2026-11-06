---
phase: 2
title: "Phase 2: Android ViewModel Instrumentation, Live Progress Log Panel & Shareable Debug Log UI"
status: pending
priority: P1
effort: "1.5h"
dependencies: [1]
---

# Phase 2: Android ViewModel Instrumentation, Live Progress Log Panel & Shareable Debug Log UI

## Context Links
- Depends on: [`phase-01-start.md`](./phase-01-start.md), [`reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md`](./reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md)
- Target Android UI, ViewModel & Config Files:
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/MainActivity.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/DiagnosticLogDialog.kt` (new)
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/SubscriberListScreen.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
  - `android-app/app/src/main/res/xml/file_paths.xml`

## Overview
Kết nối `AppDiagnosticLogger` xuyên suốt `MainViewModel.kt` với kiến trúc luồng `StateFlow` tách biệt chống giật giao diện (`[Red Team RT-08]`), sửa các ngưỡng `timeoutMs` ghi đè cứng (`[Red Team RT-07]`), bổ sung khung **Nhật ký tiến trình trực tiếp** bên trong `ExecutionProgressSheet.kt` (tự bung mở khi lỗi) và hộp thoại `DiagnosticLogDialog.kt` cho phép lọc, sao chép nhạy cảm (`EXTRA_IS_SENSITIVE`) hoặc chia sẻ file `ONT_Debug_Log_<timestamp>.txt` qua `FileProvider` thu hẹp phạm vi (`[Red Team RT-09]`).

## Key Insights
- **Tách luồng Log khỏi `MainUiState` để chống Recomposition Storm (`[Red Team RT-08]`):** Nếu nhét `List<DiagnosticLogEntry>` trực tiếp vào `MainUiState`, mỗi dòng log HTTP/Wi-Fi sẽ ép `MainActivity.kt:62` vẽ lại toàn bộ cây `ConfigReviewScreen` (các ô nhập liệu + gọi Binder IPC `validateHandoverSecurityCapability`). Thay vào đó, giữ `diagnosticLogger.entriesFlow` riêng biệt và chỉ `collectAsState` (gộp nhịp `200ms` qua `sample`/`conflate` hoặc biến đếm dòng) bên trong khối Log đang mở của `ExecutionProgressSheet` và `DiagnosticLogDialog`.
- **Sửa các lời gọi truyền `timeoutMs` cứng trong `MainViewModel.kt` (`[Red Team RT-07]`):** `MainViewModel.kt:644` (`connectToOntCandidate`) đang truyền cứng `timeoutMs = 10000L` và `:896` (`onRebindWifiNetwork`) đang truyền cứng `timeoutMs = 25_000L`, làm vô hiệu hóa mặc định mới. Phải cập nhật cả 2 điểm này lên `timeoutMs = 35_000L`.
- **Bảo mật Clipboard & Thu hẹp `file_paths.xml` (`[Red Team RT-09]`):** Gắn cờ `PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }` khi chép log vào `ClipboardManager` để bàn phím Gboard/Samsung không đồng bộ đám mây; đồng thời gỡ bỏ dòng thừa `<files-path name="internal_files" path="." />` trong `res/xml/file_paths.xml:4` để `FileProvider` chỉ được phép đọc duy nhất thư mục tạm `cacheDir/exports/`.

## Requirements

### Functional
1. **`MainViewModel.kt` (`:53-1160`) `[Red Team RT-02, RT-03, RT-06, RT-07, RT-08, RT-09]`:**
   - Khởi tạo `val diagnosticLogger = AppDiagnosticLogger(appContext)` và truyền `onLog = diagnosticLogger::log` vào `OntWifiConnector` (`:108`) và `driver` chính trong `runOntConfiguration()` (`:870`). Không gắn `onLog` lặp cho `probeDriver` chạy nền (chỉ ghi 1 dòng log khi trạng thái `isOntGatewayOnline` chuyển đổi `false <-> true`).
   - Trong `MainUiState` (`:53-77`): chỉ thêm `val showDiagnosticLogDialog: Boolean = false` (KHÔNG đưa `List<DiagnosticLogEntry>` vào `MainUiState` `[Red Team RT-08]`).
   - Trong `connectToOntCandidate` (`:635-680`): đăng ký mật khẩu Wi-Fi gốc của ONT (`passphrase` nếu khác rỗng) vào `diagnosticLogger.registerActiveSecrets(...)`, nâng `timeoutMs = 35_000L` tại `:644` (`[Red Team RT-07]`) và ghi log kết quả bắt sóng ban đầu.
   - Trong `rebindOntWifiSocketFactory` (`:729-786`) và lời gọi tại `:896`: nâng `timeoutMs` lên `35_000L` (`[Red Team RT-07]`) và ghi log từng lần rebind.
   - Trong `runOntConfiguration()` (`:791-1036`):
     - Trước Bước 1: gọi `diagnosticLogger.registerActiveSecrets(...)` với đầy đủ `settings.adminDefaultPass`, `settings.adminNewPass`, `record.lastKnownAdminPass`, `record.passPppoe`, `record.effectiveWifi24Pass(settings.wifiSecurityMode)`, `record.effectiveWifi5Pass(settings.wifiSecurityMode)`, `settings.defaultWifi5Pass` và đăng ký định danh thuê bao (`record.accountPppoe`, `record.slid`) để che giữa khi xuất file `.txt`; chỉ gọi `diagnosticLogger.clearActiveSecrets()` ở **cuối khối `finally` sau khi khối `catch` đã ghi xong toàn bộ dòng log lỗi** (`[Red Team RT-03]`).
     - Truyền `isNetworkTransportAlive = { wifiConnector.boundWifiNetwork != null }` và chuyển tiếp đầy đủ `(minScanTimestampMicros, outerAttempt, lockToBssidHint)` vào `wifiConnector.switchToConfiguredSecondaryWifi(...)` (`:899-918` `[Red Team RT-02]`).
   - Bổ sung các hàm thao tác Log:
     - `fun openDiagnosticLogDialog()` / `fun dismissDiagnosticLogDialog()`
     - `fun clearDiagnosticLogs()`
     - `fun copyDiagnosticLogsToClipboard()`: chép văn bản log đã che định danh vào `ClipboardManager` kèm `ClipDescription` gắn `PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }` (`[Red Team RT-09]`).
     - `fun exportAndShareDiagnosticLog()`: nếu `!_uiState.value.isRunningSetup` thì gọi `wifiConnector.unbindOntWifi(clearSelectedMetadata = false)`, dọn file cũ trong `exportsDir`, xuất `diagnosticLogger.exportCombinedLogText(buildDeviceDiagnosticHeader(), maskSubscriberIdentifiers = true)` ra `File(exportsDir, "ONT_Debug_Log_$timestamp.txt")` và mở `Intent.createChooser` qua `FileProvider`.
   - Trong `onCleared()` (`:1141-1145`): dọn `File(appContext.cacheDir, "exports")` và gọi `diagnosticLogger.close()` (`[Red Team RT-06, RT-09]`).
   - Cập nhật `cleanupStaleExportFiles(exportsDir: File)` (`:1148-1158`) xóa cả `.xlsx` và `.txt` bắt đầu bằng `ONT_Debug_Log_`.

2. **`res/xml/file_paths.xml` (`:1-5`) `[Red Team RT-09]`:**
   - Xóa dòng `<files-path name="internal_files" path="." />` không sử dụng, chỉ giữ lại duy nhất `<cache-path name="exported_reports" path="exports/" />`.

3. **`ExecutionProgressSheet.kt` (`:64-509`) `[Red Team RT-08]`:**
   - Cập nhật hướng dẫn Bước 7 (`:150`) phản ánh đúng ngân sách `180s`, chờ ổn định sóng sau khi thấy tên Wi-Fi và tự động retry giãn cách.
   - Nhận `logEntriesFlow: StateFlow<List<DiagnosticLogEntry>>`, `onOpenFullLogDialog: () -> Unit = {}`, `onCopyDiagnosticLog: () -> Unit = {}`, `onShareDiagnosticLog: () -> Unit = {}`.
   - Thêm thẻ mở rộng **`📋 Nhật ký tiến trình & Debug`**:
     - Tự động bung mở (`expanded = true`) khi `!errorMessage.isNullOrBlank()`; chỉ `collectAsState` (lấy `200` dòng cuối) khi `expanded == true` để không tốn hiệu năng lúc thu gọn (`[Red Team RT-08]`).
     - Tích hợp hàng nút **`Mở rộng`**, **`Chép Log`**, **`Chia sẻ Log (.txt)`**.

4. **`DiagnosticLogDialog.kt` (tạo mới), `SubscriberListScreen.kt`, `ConfigReviewScreen.kt` & `MainActivity.kt`:**
   - Tạo `DiagnosticLogDialog.kt` hiển thị danh sách log bằng font `FontFamily.Monospace` (`11.sp`), bộ lọc `Tất cả` / `Cảnh báo & Lỗi` / `Wi-Fi & Handover`, cùng các nút `Cuộn cuối`, `Chép Log`, `Chia sẻ (.txt)`, `Xóa Log`, `Đóng`.
   - Thêm nút biểu tượng mở Nhật ký Debug trên `TopAppBar` của `SubscriberListScreen.kt` (đồng thời sửa phụ đề cũ `:100` thành `"iGate GW040-NSv2 • Tự động đổi 2 Wi-Fi & Firmware"`) và `ConfigReviewScreen.kt`; kết nối đầy đủ trong `MainActivity.kt` (dọn thêm `cacheDir/exports/` trong `onStop` nếu không đang chia sẻ).

## Related Code Files
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/DiagnosticLogDialog.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/MainActivity.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/SubscriberListScreen.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
- Modify: `android-app/app/src/main/res/xml/file_paths.xml`

## Implementation Steps
1. Thu hẹp `res/xml/file_paths.xml` chỉ còn `cache-path` `exports/`.
2. Cập nhật `MainViewModel.kt`: nối `AppDiagnosticLogger` (đăng ký đầy đủ `effectiveWifi*Pass` / scanner pass, xóa sau `catch`), sửa `timeoutMs = 35_000L` tại `:644` và `:896`, nối `isNetworkTransportAlive` + tham số handover mới, cài đặt chép log `EXTRA_IS_SENSITIVE`, chia sẻ `.txt` và `diagnosticLogger.close()` trong `onCleared()`.
3. Tạo `DiagnosticLogDialog.kt` và nâng cấp `ExecutionProgressSheet.kt` thu thập `entriesFlow` độc lập khi mở rộng (tự mở khi lỗi).
4. Thêm nút Nhật ký Debug trên `SubscriberListScreen.kt` & `ConfigReviewScreen.kt` và kết nối trong `MainActivity.kt`.

## Todo List
- [x] Thu hẹp `res/xml/file_paths.xml` và tích hợp `AppDiagnosticLogger`, `timeoutMs = 35_000L`, `isNetworkTransportAlive` cùng xuất/chép log an toàn trong `MainViewModel.kt`
- [x] Tạo `DiagnosticLogDialog.kt` hỗ trợ lọc `Tất cả / Lỗi / Handover`, sao chép nhạy cảm, chia sẻ `.txt` và xóa log
- [x] Bổ sung khung Nhật ký tiến trình độc lập (tự mở khi lỗi, không gây recomposition toàn màn hình) vào `ExecutionProgressSheet.kt`
- [x] Thêm nút mở Nhật ký Debug trên `SubscriberListScreen.kt`, `ConfigReviewScreen.kt` và kết nối trong `MainActivity.kt`

## Success Criteria
- [x] Gõ chữ trên `ConfigReviewScreen` và chạy cấu hình hoàn toàn mượt mà, không bị recomposition toàn màn hình khi có dòng log mới.
- [x] Khi gặp lỗi ở Bước 7 trên máy thật, khung Log tự động bung ra hiển thị đầy đủ lịch sử quét/retry và chia sẻ được ngay file `.txt` qua Zalo/Telegram.

## Risk Assessment
- **Rủi ro:** Người dùng bấm chia sẻ `.txt` trong khi đang đứng trên mạng Wi-Fi ONT không có Internet.
  - *Giải pháp định sẵn:* Khi `!isRunningSetup`, `exportAndShareDiagnosticLog()` tự động gọi `wifiConnector.unbindOntWifi(clearSelectedMetadata = false)` để hệ điều hành khôi phục đường truyền 4G/5G ngay trước khi mở hộp thoại chia sẻ Zalo/Telegram.

## Security Considerations
- Cờ `EXTRA_IS_SENSITIVE = true` chặn lộ log qua lịch sử bàn phím Android 13+; `file_paths.xml` không còn mở toàn bộ `filesDir`.

## Next Steps
- Chuyển sang Phase 3 để triển khai đối xứng toàn bộ cải tiến lên ứng dụng iOS Native (`ios-app/`).
