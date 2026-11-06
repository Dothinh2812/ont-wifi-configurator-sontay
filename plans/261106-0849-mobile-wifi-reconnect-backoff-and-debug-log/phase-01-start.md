---
phase: 1
title: "Phase 1: Android Diagnostic Logger, ONT Readiness Stabilization & Spaced-Backoff Reconnect Engine"
status: pending
priority: P1
effort: "2h"
dependencies: []
---

# Phase 1: Android Diagnostic Logger, ONT Readiness Stabilization & Spaced-Backoff Reconnect Engine

## Context Links
- Research & Red Team: [`research/researcher-01-wifi-readiness-and-spaced-backoff.md`](./research/researcher-01-wifi-readiness-and-spaced-backoff.md), [`research/researcher-02-mobile-diagnostic-logging-and-security.md`](./research/researcher-02-mobile-diagnostic-logging-and-security.md), [`reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md`](./reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md)
- Predecessor Plan: [`../261105-1821-mobile-dual-wifi-handover-firmware/plan.md`](../261105-1821-mobile-dual-wifi-handover-firmware/plan.md)
- Target Core Files:
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/core/logging/AppDiagnosticLogger.kt` (new)
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntExceptions.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
  - `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`

## Overview
Xây dựng bộ ghi nhật ký chẩn đoán an toàn mật khẩu (`AppDiagnosticLogger.kt`), nâng ngân sách chờ phần cứng ONT, bổ sung cửa sổ ổn định sau khi phát hiện sóng mới (`postBeaconReadyDelayMs`), lịch retry giãn cách lũy tiến (`5s -> 10s -> 15s -> 20s`) tại tầng OS Wi-Fi (`OntWifiConnector.kt`) và vòng lặp retry tổng thể Bước 7 + hội tụ xác thực trạm (`OntMobileDriver.kt`).

## Key Insights
- **Beacon $\neq$ ONT Sẵn sàng & Bảo toàn hạn mức quét `4 / 120s` (`[Red Team RT-02]`):** Khi `wlmngr` khởi động lại radio sau Bước 6, khung beacon `SSID_B` phát ra trước khi `nas` (WPA EAPOL), `br0` và `udhcpd` sẵn sàng, đồng thời `acsd` hay đổi kênh nháy sóng lần 2 sau 3–6 giây. Phải ghi mốc `step6CompletedMicros` **một lần duy nhất** ngay khi xong Bước 6 để bảo toàn kết quả quét hợp lệ qua các lượt retry ngoài (ở lượt `outerAttemptIndex >= 2` bỏ qua `initialSettleMs = 8s` và rút gọn `postBeaconReadyDelayMs = 2s`); đồng thời nếu hạn mức quét `4 / 120s` của tiến trình đã cạn mà không có `SCAN_RESULTS_AVAILABLE_ACTION` trong `8s` thì thoát sớm Pha A sang Pha B (để `WifiNetworkSpecifier` kích hoạt quét đặc quyền của hệ thống Android).
- **Thiếu vòng lặp Bước 7 tổng thể & Kẹt socket chết trên Android (`[Red Team RT-04]`):** Hiện Android gọi `switchHandler.invoke(...)` chỉ 1 lần duy nhất (`OntMobileDriver.kt:1661`), còn `authenticateSecondConnection` (`:894-938`) nuốt `OntNetworkError` suốt `65s` rồi ném `OntUserExistError` không được retry. Phải kiểm tra `isNetworkTransportAlive?.invoke() == false` xuyên suốt `probeGateway`, `authenticateSecondConnection` và `verifySecondConnectionBandAndMacWithRetry` để thoát tức thì khi rớt sóng (`onLost`) và chuyển sang kết nối lại Wi-Fi có giãn cách.
- **Chống lộ mật khẩu URL-encoded & Chống báo nhầm lệch TLS/MAC (`[Red Team RT-03, RT-04]`):** Đồng bộ biểu thức chính quy `OntInputValidator.kt` với bản Swift (che cả URL-form, JSON có dấu cách và `key: value`), lọc mật khẩu **đồng bộ ngay trên luồng gọi** (gồm cả dạng nguyên văn, `%XX` URL-encoded và `\u007f`, loại trừ từ khóa hệ thống `"admin"`), và rethrow ngay lỗi lệch chứng chỉ TLS SHA-256 bên trong `postOptionalSessionJson`.
- **Tương thích biên dịch & tốc độ Unit Test (`[Red Team RT-05]`):** Mở `open class OntNetworkError` trong `OntExceptions.kt:21` để kế thừa hợp lệ; tuyệt đối không rẽ nhánh theo `settlingDelayMs >= 100` (`tests/test_ios_app_parity.py:378`).

## Requirements

### Functional
1. **`AppDiagnosticLogger.kt` (tạo mới tại `core/logging/AppDiagnosticLogger.kt`) `[Red Team RT-03, RT-06]`:**
   - Định nghĩa `enum class LogLevel { INFO, WARN, ERROR }` và `data class DiagnosticLogEntry(val seq: Long, val timestamp: String, val level: LogLevel, val tag: String, val message: String)` với hàm `formatLine(): String` (`[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [TAG] message`).
   - Lưu bộ đệm vòng trong RAM tối đa `MAX_MEMORY_ENTRIES = 1_500` dòng (`ArrayDeque`), xuất `val entriesFlow: StateFlow<List<DiagnosticLogEntry>>` độc lập (không nhét vào `MainUiState` `[Red Team RT-08]`).
   - **Thực thi 2 lớp che thông tin nhạy cảm ĐỒNG BỘ ngay trên luồng gọi `log(...)` trước khi đưa vào RAM/hàng đợi đĩa (`[Red Team RT-03]`):**
     - Lớp 1: `OntInputValidator.maskSensitiveCredentials(rawMessage)`.
     - Lớp 2: `registerActiveSecrets(secrets: Collection<String>)` / `clearActiveSecrets()` tự động sinh cả 3 biến thể (`raw`, `OntInputValidator.urlEncodeFormValue(raw)`, `OntInputValidator.ontEncodeQuery(raw)`), loại bỏ các từ khóa giao thức vô hại (`"admin"`, `"root"`, `"open"`, `"null"`, `"true"`, `"false"`, `"192.168.1.1"`) và chỉ thay thế chuỗi nguyên văn có độ dài `>= 6` (sắp xếp giảm dần theo độ dài) bằng `"***"`.
   - Ghi đĩa trên `SingleThreadExecutor` vào `File(logDir, "ont_debug.log")` (`File(context.noBackupFilesDir, "logs")`, cho phép truyền `logDir: File` khi test) với xoay vòng sang `ont_debug.prev.log` ở ngưỡng `512 * 1024` bytes (`512 KB`, tối đa `1 MB`).
   - **Đồng bộ hóa đọc/xóa/đóng (`[Red Team RT-06]`):** `exportCombinedLogText(deviceHeader: String = "", maskSubscriberIdentifiers: Boolean = true): String`, `flushForTest()`, và `clearLogs()` đều chạy tuần tự qua `executor.submit(Callable { ... }).get(3, TimeUnit.SECONDS)` để đảm bảo mọi dòng `[ERROR]` cuối cùng và thao tác đổi tên file xoay vòng đã hoàn tất trước khi đọc/xóa; khi `maskSubscriberIdentifiers == true`, che giữa địa chỉ MAC/BSSID (`AA:BB:CC:**:**:D8`) cùng chuỗi định danh đã đăng ký (`accountPppoe`, `slid` giữ 2 ký tự đầu/cuối) để an toàn khi gửi qua Zalo/Telegram; bổ sung `close()` giải phóng executor.
   - Phản chiếu sang Logcat qua `runCatching { android.util.Log.println(...) }` để an toàn trên JVM test thuần.

2. **`GlobalSettings.kt` (`:75-99`), `OntInputValidator.kt` (`:32-46, 348-354`) & `OntExceptions.kt` (`:21`) `[Red Team RT-03, RT-05]`:**
   - Trong `GlobalSettings.kt`: nâng mặc định `wifiHandoverTimeoutMs: Long = 180_000L`, `gatewayPostHandoverTimeoutMs: Long = 60_000L`, `postSecondWifiSettleTimeoutMs: Long = 90_000L`.
   - Trong `OntInputValidator.kt`: đồng bộ hoàn toàn `SENSITIVE_PATTERNS` với bản Swift cho mọi khóa (`pppPassword`, `pass`, `newPass`, `oldPass`, `wl_wpa_psk`, `nvramWlWpaPsk`, `nvramWlRadiusKey`, `nvramWlKey[1-4]?`, `password`, `passphrase`, `wpa_psk`, `preSharedKey`, `sessionKey`, `data`) trên cả 3 định dạng: URL query (`key=...`), JSON có dấu nháy & khoảng trắng (`"key": "..."`), và `key: value`.
   - Trong `OntExceptions.kt:21`: đổi `class OntNetworkError` thành `open class OntNetworkError` và bổ sung `class StillOnPrimaryBandException(message: String) : OntNetworkError(message)`.

3. **`OntWifiConnector.kt` (`:48-1075`) `[Red Team RT-02, RT-07, RT-08]`:**
   - Bổ sung `private val onLog: ((LogLevel, String, String) -> Unit)? = null` vào constructor `OntWifiConnector` (chỉ ghi log trong `bindCurrentWifiNetworkIfConnected()` khi `boundWifiNetwork` thực sự thay đổi để tránh spam log nền `[Red Team RT-08]`).
   - Bổ sung trong `companion object`:
     - `fun computeReconnectBackoffMs(attemptNumber: Int, initialBackoffMs: Long = 5_000L, maxBackoffMs: Long = 20_000L): Long` (`5s -> 10s -> 15s -> 20s`).
     - `fun hasAvailableProcessScanQuota(nowMs: Long = SystemClock.elapsedRealtime()): Boolean` (kiểm tra còn slot trong cửa sổ `4 / 120_000 ms` mà không tiêu tốn slot).
   - Sửa lỗi `OntWifiConnector.kt:421`: đổi `val prevNetwork = excludedNetwork ?: boundWifiNetwork` thành `val prevNetwork = excludedNetwork` (giữ nguyên mặc định tham số `excludedNetwork: Network? = boundWifiNetwork`, nhưng bảo toàn giá trị `null` tường minh trên máy 2.4GHz-only `[Red Team RT-02]`).
   - Nâng cấp `switchToConfiguredSecondaryWifi(...)`:
     - Tham số: `timeoutMs: Long = 180_000L`, `minScanTimestampMicros: Long = -1L`, `outerAttemptIndex: Int = 1`, `lockToBssidHint: Boolean = false`, `initialSettleMs: Long = 8_000L`, `postBeaconReadyDelayMs: Long = 8_000L`, `perAttemptTimeoutMs: Long = 30_000L`, `dhcpWaitTimeoutMs: Long = 15_000L`, `linkStabilityMs: Long = 1_500L`, `retryInitialBackoffMs: Long = 5_000L`, `retryMaxBackoffMs: Long = 20_000L`.
     - Tính `effectiveMinScanMicros = if (minScanTimestampMicros > 0L) minScanTimestampMicros else (SystemClock.elapsedRealtimeNanos() / 1_000L)`; ở `outerAttemptIndex == 1` chờ `initialSettleMs` (`8s`) và `postBeaconReadyDelayMs` (`8s`), còn ở `outerAttemptIndex >= 2` đặt `effectiveInitialSettle = 0L` và `effectivePostBeaconDelay = minOf(2_000L, postBeaconReadyDelayMs)` (`[Red Team RT-02]`).
     - **Pha A:** Nếu `isLocationServiceEnabled()`: tìm `ScanResult` khớp `targetSsid` với `sr.timestamp <= 0L || sr.timestamp >= effectiveMinScanMicros`; nếu `tryRequestStartScan()` bị từ chối do hết quota (`!hasAvailableProcessScanQuota()`) và sau `8_000L` không có broadcast `SCAN_RESULTS_AVAILABLE_ACTION` mới nào thì ghi log `INFO` và thoát sớm sang Pha B để `WifiNetworkSpecifier` tự quét hệ thống (`[Red Team RT-02]`). Khi thấy beacon ở `firstSeenMicros`, ghi log (`SSID`, `BSSID`, `frequencyMhz`, `rssi`), chờ `effectivePostBeaconDelay` (kiểm tra `findAlreadyConnectedTargetNetwork` mỗi `500 ms`), và chỉ coi là rớt sóng kép nếu trong lúc chờ có bản quét mới hơn (`sr.timestamp > firstSeenMicros`) mà `targetSsid` biến mất. Khi tắt GPS: chờ `effectiveInitialSettle + effectivePostBeaconDelay`.
     - **Pha B (Retry Giãn Cách `5s -> 10s -> 15s -> 20s`):** Mỗi lượt `requestSpecifierNetworkOnce` giới hạn `minOf(perAttemptTimeoutMs, remainingMs)` (`30s`; nếu `lockToBssidHint && !targetBssidHint.isNullOrBlank()` thì gắn `MacAddress.fromString(targetBssidHint)` vào `WifiNetworkSpecifier.Builder` để tránh bám nhầm `wl0` khi 2 băng tần đặt trùng tên `[Red Team RT-02]`). Mọi nhánh kết nối (kể cả `findAlreadyConnectedTargetNetwork`) đều phải vượt qua `awaitOntSubnetIpv4Ready(net, maxWaitMs = dhcpWaitTimeoutMs)` (`15s`) + kiểm tra giữ kết nối `linkStabilityMs` (`1.5s`). Khi thất bại, gọi `unbindOntWifi(clearSelectedMetadata = false)` và đếm ngược `computeReconnectBackoffMs(attempt, ...)` theo nhịp `500 ms` (kiểm tra `findAlreadyConnectedTargetNetwork` + quét bổ sung nếu qua `>= 15s` và còn quota).
   - Nâng cấp `connectToOntWifi(...)` (`:264-395`) `[Red Team RT-07]`: đặt `timeoutMs: Long = 35_000L`, `perAttemptTimeoutMs: Long = 15_000L`, `awaitWifiIpv4Ready(maxWaitMs = 8_000L)`, `maxAttempts: Int = 3`, `retryInitialBackoffMs: Long = 3_000L` (`3s -> 6s`), bọc từng lượt `requestNetwork` bằng `withTimeoutOrNull(minOf(perAttemptTimeoutMs, remainingMs))` để lượt 1 bị treo vẫn nhường thời gian cho lượt 2 và 3.

4. **`OntMobileDriver.kt` (`:80-1938`) `[Red Team RT-02, RT-04]`:**
   - Bổ sung tham số constructor (mặc định thực tế, ghi đè nhanh trong test):
     - `private val postDhcpGatewaySettleMs: Long = 2_500L`
     - `private val handoverRetryInitialBackoffMs: Long = 5_000L`
     - `private val handoverRetryMaxBackoffMs: Long = 20_000L`
     - `private val bandVerifyPollTimeoutMs: Long = 15_000L`
     - `private val bandVerifyPollIntervalMs: Long = 2_000L`
     - `private val isNetworkTransportAlive: (() -> Boolean)? = null`
     - `private val onLog: ((LogLevel, String, String) -> Unit)? = null`
   - Mở rộng chữ ký `onSwitchToSecondaryWifi` thêm `minScanTimestampMicros: Long`, `outerAttempt: Int`, `lockToBssidHint: Boolean` (hoặc truyền mặc định sạch tại 2 nơi gọi duy nhất là `MainViewModel.kt:899` và `OntMobileDriverTest.kt:116`).
   - Cập nhật `observedPeerCertSha256` trên **mọi** lần bắt tay TLS (trong khi `pinnedPeerCertSha256` giữ cố định từ Bước 1) để log đúng chứng chỉ thực tế của Lần 2; trong Step 2c thử đọc `deviceinfo2.json` tối đa 3 lần nếu lần đầu `null`; và trong `postOptionalSessionJson` rethrow ngay `OntDriverException` nếu gặp `hasTlsCertMismatchCause(e)` (`[Red Team RT-04]`).
   - Trong `authenticateSecondConnection`: kiểm tra `isNetworkTransportAlive?.invoke() == false` ở mỗi nhịp chờ `"user exist"` để ném ngay `OntNetworkError` khi mất sóng Wi-Fi, đồng thời lưu `lastNetworkError` để nếu hết `65s` do lỗi mạng liên tiếp thì ném `OntNetworkError` (kích hoạt kết nối lại Wi-Fi) thay vì `OntUserExistError` (`[Red Team RT-04]`).
   - Cài đặt `suspend fun verifySecondConnectionBandAndMacWithRetry(...)` (`15s`, mỗi `2s`): kiểm tra `isNetworkTransportAlive`; ném ngay `OntDriverException` không retry khi `!actualBaseMac.isNullOrBlank() && !expectedOntBaseMac.isNullOrBlank() && !actualBaseMac.equals(expectedOntBaseMac.trim(), ignoreCase = true)`; ném `StillOnPrimaryBandException` sau 2 lần đọc liên tiếp thấy đứng nhầm `Band A` (bật `preferBssidLock = true` cho lượt thử sau `[Red Team RT-02]`); giới hạn tối đa 2 lượt retry ngoài nếu `deviceinfo2.json` hoàn toàn không phản hồi (`[Red Team RT-04]`).
   - Bọc toàn bộ Bước 7 trong vòng lặp tổng thể theo `settings.wifiHandoverTimeoutMs` (`180s`) với mốc `step6CompletedMicros` cố định và lịch nghỉ giãn cách `computeReconnectBackoffMs(outerAttempt, handoverRetryInitialBackoffMs, handoverRetryMaxBackoffMs)` (`5s -> 10s -> 15s -> 20s`).

## Related Code Files
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/logging/AppDiagnosticLogger.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntExceptions.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`

## Implementation Steps
1. Tạo `AppDiagnosticLogger.kt` với lọc mật khẩu 2 lớp đồng bộ trên luồng gọi (hỗ trợ URL-encoded `%XX`, loại trừ `"admin"`), ring buffer `1_500` dòng, luồng ghi xoay vòng `512 KB` đồng bộ hóa `exportCombinedLogText()` / `clearLogs()` / `close()`.
2. Cập nhật `GlobalSettings.kt` (`180s`, `60s`, `90s`), đồng bộ biểu thức che mật khẩu trong `OntInputValidator.kt` và mở `open class OntNetworkError` + `StillOnPrimaryBandException` trong `OntExceptions.kt`.
3. Nâng cấp `OntWifiConnector.kt`: thêm `computeReconnectBackoffMs`, bảo toàn `minScanTimestampMicros`, thoát sớm Pha A khi hết quota `4/120s`, chờ ổn định sau beacon `8s`, chờ DHCP `15s` + giữ link `1.5s`, khóa BSSID khi trùng tên 2 băng tần, và cắt lát từng lượt thử trong `connectToOntWifi`.
4. Nâng cấp `OntMobileDriver.kt`: vòng lặp Bước 7 tổng thể có retry giãn cách, thoát tức thì khi `isNetworkTransportAlive == false`, rethrow TLS mismatch trong `postOptionalSessionJson`, hội tụ `verifySecondConnectionBandAndMacWithRetry` và ghi log toàn diện.

## Todo List
- [x] Tạo `AppDiagnosticLogger.kt` đồng bộ hóa luồng ghi xoay vòng `512 KB` và lọc mật khẩu 2 lớp đồng bộ trên luồng gọi
- [x] Cập nhật `GlobalSettings.kt` (`180s`, `60s`, `90s`), `OntInputValidator.kt` (đồng bộ regex JSON/space) và `OntExceptions.kt` (`open class OntNetworkError`)
- [x] Cập nhật `OntWifiConnector.kt` với bảo toàn `minScanTimestampMicros`, ổn định sau beacon `8s`, DHCP `15s`, giữ link `1.5s`, retry giãn cách `5s -> 10s -> 15s -> 20s` và cắt lát `connectToOntWifi`
- [x] Cập nhật `OntMobileDriver.kt` với vòng lặp Bước 7 tổng thể, kiểm tra `isNetworkTransportAlive`, rethrow TLS pin mismatch và hội tụ `verifySecondConnectionBandAndMacWithRetry`

## Success Criteria
- [x] Khi thấy sóng `targetSsid`, `OntWifiConnector` chờ đủ cửa sổ ổn định ở lượt 1, không lặp lại `8s+8s` vô ích ở lượt retry ngoài, và không kẹt `45s` khi cạn quota quét `4/120s`.
- [x] Khi rớt mạng (`onLost`) ở bất kỳ bước con nào của Bước 7 (`probeGateway`, `authenticateSecondConnection`, `verifySecondConnectionBandAndMacWithRetry`), Driver thoát ngay khỏi socket chết và thực hiện retry kết nối lại giãn cách `5s -> 10s -> 15s -> 20s`.
- [x] Mọi dòng log trong `AppDiagnosticLogger` che sạch 100% mật khẩu nguyên văn lẫn URL-encoded (`%XX`) mà không làm hỏng từ khóa `"admin"`.

## Risk Assessment
- **Rủi ro:** Hộp thoại `WifiNetworkSpecifier` bị hệ thống từ chối nếu gọi đè quá nhanh.
  - *Giải pháp định sẵn:* Luôn gọi `unbindOntWifi(clearSelectedMetadata = false)` gỡ sạch callback cũ trước khi đếm ngược giãn cách tối thiểu `5_000 ms`.

## Security Considerations
- File log lưu trong `context.noBackupFilesDir/logs`; che mật khẩu thực hiện đồng bộ trước khi đẩy xuống hàng đợi đĩa nên không bao giờ rò mật khẩu khi hủy/lỗi giữa chừng (`[Red Team RT-03, RT-06]`).

## Next Steps
- Chuyển sang Phase 2 để tích hợp luồng Log hiệu năng cao vào `MainViewModel.kt`, `ExecutionProgressSheet.kt` và `DiagnosticLogDialog.kt`.
