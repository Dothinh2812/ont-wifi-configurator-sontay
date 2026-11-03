---
phase: 1
title: "Phase 1: Android Single-Connection Keep-Alive, Band Ground-Truth & Save-Before-Disconnect"
status: completed
priority: P1
effort: "2h"
dependencies: []
---

# Phase 1: Android Single-Connection Keep-Alive, Band Ground-Truth & Save-Before-Disconnect

## Context Links
- `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`
- `android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
- `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`
- `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
- `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/SubscriberListScreen.kt`
- `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`
- Capture tham chiếu: `ont-040NSv2-setup.jsonl` (`seq: 4` `jquery.min.js`, `seq: 69` `summary.js`, `seq: 73` `deviceinfo2.json`, `seq: 74` `dhcpclient.json`, `seq: 181` `wireless.js`, `seq: 214–228` `wirelesscfg.json` & `wlrestart.json`)

## Overview
Loại bỏ triệt để cơ chế tự `logout()` và nhảy sóng phụ (`Band-Hopping Handover`) giữa Bước 6 và Bước 7 trên bản Android; giữ nguyên kết nối Wi-Fi hiện tại từ Bước 1 đến hết Bước 7; bổ sung xác thực băng tần kết nối thực tế từ phía ONT ngay sau Bước 2 (`dhcpclient.json` + `deviceinfo2.json` mảng 2 chiều) kết hợp `NetworkCapabilities.transportInfo` trên Android 12+; tách độc lập `recoveryRetryDelayMs = 1200L`; sửa dứt điểm lỗi gọi đúp login / bỏ sót `"fail authen"` trong `ensureGatewayReadyBeforeStep7`; và đảm bảo chỉ báo hoàn tất Bước 7 sau khi lệnh Save (`wirelesscfg.json`) đã lưu NVRAM hoặc đẩy hết dữ liệu và sóng cũ thực sự tắt.

## Requirements
- **Functional:**
  - **Clean Cutover — Gỡ bỏ Band-Hopping Handover (`[Red Team RT-09]`):**
    - Trong `OntMobileDriver.kt`: xóa tham số `onSwitchToConfiguredWifi` (`dòng 85`), xóa hàm `switchToSecondaryWifiAndAuthenticate()` (`dòng 831-908`), xóa đoạn gọi `logout()` sau Bước 6 (`dòng 1230-1233`), xóa nhánh chuyển sóng phụ ở đầu Bước 7 (`dòng 1241-1265`) và biến `switchedToSecondaryBand` (`dòng 1269`).
    - Trong `OntWifiConnector.kt`: xóa hàm `switchToConfiguredOntWifi()` (`dòng 390-526`) và `isSystemConnectedToTargetWifi()` (`dòng 335-378`).
    - Trong `MainViewModel.kt`: xóa `activeTargetSsid`, `activeTargetPass`, `activeTargetBand`, `hasSwitchedToSecondaryBand` (`dòng 751-754`), xóa toàn bộ nhánh `if (hasSwitchedToSecondaryBand) { ... }` bên trong `onRebindWifiNetwork` (`dòng 780-817`, chỉ giữ lại phần thân `else` `dòng 818-837`), và xóa callback `onSwitchToConfiguredWifi` (`dòng 840-861`). Đổi tiêu đề Bước 7 trong `MainUiState.defaultSevenSteps()` (`dòng 82`) thành `"Bước 7/7: Đổi tên & mật khẩu Wi-Fi kết nối chính (Bước cuối)"`.
  - **Tách `recoveryRetryDelayMs` khỏi `settlingDelayMs` (`[Red Team RT-03]`):**
    - Thêm tham số khởi tạo `private val recoveryRetryDelayMs: Long = 1200L` vào `OntMobileDriver.kt` (trong unit test truyền `recoveryRetryDelayMs = 20L`, còn chạy thật giữ mặc định `1200L`).
    - Thay thế toàn bộ biểu thức `if (settlingDelayMs >= 500L) 1200L else 20L` / `50L` (`OntMobileDriver.kt:1111, 1285, 1318`) bằng `recoveryRetryDelayMs`, đồng thời đặt `settlingDelayMs = 400L` trong `MainViewModel.kt` mà không làm co ngắn cửa sổ hồi phục sóng 9.6s ở cả luồng 7 bước lẫn luồng 8 bước nạp Firmware.
  - **Xác định chính xác 100% băng tần đang kết nối & Bảo vệ định tuyến Wi-Fi (`[Red Team RT-02, RT-06, RT-08, RT-09]`):**
    - Trong `OntWifiConnector.kt`:
      - Thêm hàm `getBoundWifiIpv4Address(): String?` lấy địa chỉ IPv4 thuộc dải `192.168.1.x` (`!= 192.168.1.1`) từ `LinkProperties` của `boundWifiNetwork` (hoặc mạng `TRANSPORT_WIFI` đang hoạt động).
      - Cập nhật `detectConnectedBand()`: trên Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`), đọc tần số từ `(connectivityManager.getNetworkCapabilities(boundWifiNetwork ?: activeWifiNet)?.transportInfo as? WifiInfo)?.frequency` trước khi fallback sang `wifiManager.connectionInfo?.frequency`.
      - Lưu đệm `lastSelectedPassphrase`, `lastSelectedSecurityMode`, và bổ sung `frequencyMhz: Int = -1` vào `connectToOntWifi(...)` (giữ nguyên `lastSelectedBand` nếu `frequencyMhz <= 0 && lastSelectedSsid.equals(ssid, ignoreCase = true)`) để khi `onRebindWifiNetwork` gọi lại `connectToOntWifi` sau đợt chớp sóng Bước 6, nó tự động dùng lại `lastSelectedPassphrase`, `lastSelectedSecurityMode` và `lastSelectedBand` mà **không** đăng ký `WifiNetworkSuggestion` tạm thời cho sóng xuất xưởng của ONT (tránh hộp thoại thu hồi quyền `CHANGE_WIFI_STATE` trên Android 11+ và tránh lưu profile Open tự động kết nối).
    - Trong `MainViewModel.kt`, `SubscriberListScreen.kt` & `ConfigReviewScreen.kt`:
      - Trong `performGatewayProbeOnce()`, `runOntConfiguration()` và `runManualConnectedWifiStep()`: nếu sau khi gọi `wifiConnector.bindCurrentWifiNetworkIfConnected()` mà `wifiConnector.boundWifiNetwork == null`, dừng ngay và trả về `false` / báo lỗi hướng dẫn KTV kết nối Wi-Fi ONT trước (không bao giờ truyền `socketFactory = null` để `UnsafeSslClient` rò lưu lượng `192.168.1.1` qua mạng di động 4G/5G hoặc VPN `[Red Team RT-08]`).
      - Khi `isOntGatewayOnline == true && connectedWifiSsid.isBlank()` (do Android tắt GPS ẩn `<unknown ssid>`), hiển thị `"Wi-Fi ONT Đang Kết Nối"` thay vì `"Chưa bắt Wi-Fi ONT"` trên `SubscriberListScreen.kt:381` và `ConfigReviewScreen.kt`.
    - Trong `OntMobileDriver.kt`:
      - Đặt các tham số mới `localWifiIpv4: String? = null` và `onBandResolved: ((WifiBand, String?) -> Unit)? = null` **trước** tham số lambda cuối `onProgress: ((StepProgress) -> Unit)? = null` trên `executeMobileSetup(...)` để giữ tương thích cú pháp trailing-lambda tại `MainViewModel.kt:865` và các lời gọi vị trí tại `OntMobileDriverTest.kt:348` (`[Red Team RT-09]`).
      - Cài đặt `resolveConnectedBandFromOnt(localIpv4: String?, fallbackBand: WifiBand): Pair<WifiBand, String?>` và gọi **ngay sau Bước 2 (`loginAndChangePassword`)** — trước khi Bước 5 (`savelanv4cfg.cmd`) reload `br-lan`/`dnsmasq` (`[Red Team RT-02]`):
        1. Dùng HTTP client chính (`timeoutMs`, mặc định `5000ms`, vì `deviceinfo2.json` mất ~`1,372 ms` trên phần cứng thật theo `seq: 73`).
        2. Nếu `localIpv4` hợp lệ (`192.168.1.x`), gọi `POST /cgi-bin/luci/dhcpclient.json` (`undefined&sessionKey=$encKey`): tìm dòng trong `dhcpInfoList` có `parts[0].trim() == localIpv4.trim()` để lấy `clientMac = parts.getOrNull(3)?.trim().orEmpty()` và `leaseIfc = parts.getOrNull(7)?.trim().orEmpty()`; sau đó đối chiếu `clientMac` trong `wlAssociatedDevice` (`assocParts[0].trim().equals(clientMac, ignoreCase = true)`) để ưu tiên lấy giao diện không dây thời gian thực `assocParts.getOrNull(3)?.trim()` (chuẩn hóa tiền tố phụ `wl0.1` / `wl0_1` $\rightarrow$ `wl0`, `wl1.1` $\rightarrow$ `wl1`).
        3. Gọi `POST /cgi-bin/luci/deviceinfo2.json` (`undefined&sessionKey=$encKey`): trích xuất `baseMacAddress` (làm khóa dự phòng cho `bssidPasswordCache` khi Android tắt GPS ẩn BSSID) và duyệt mảng 2 chiều `wirelessInfoList` (`outerArr.optJSONArray(i)?.optString(0)` theo đúng `ont-040NSv2-setup.jsonl:152`), `.trim()` từng trường phân tách bởi `|`, trích số đầu của kênh `sub_data[3]` (`Regex("^(\\d+)")`): kênh `1..14` $\rightarrow$ `BAND_2_4_GHZ`, kênh `>= 36` $\rightarrow$ `BAND_5_GHZ`; nếu kênh là `"0"` (`auto`) hoặc vắng mặt thì ánh xạ `wl0` $\rightarrow$ `BAND_2_4_GHZ` (`unit=0`), `wl1` $\rightarrow$ `BAND_5_GHZ` (`unit=1`).
        4. Báo `onBandResolved?.invoke(resolvedBand, ontBaseMac)` để `MainViewModel` đồng bộ ngay `connectedBand` trên UI và lưu vào `MobileSetupResult.resolvedConnectedBand`. Mọi lỗi mạng/parse đều được nuốt an toàn và giữ nguyên `fallbackBand`.
  - **Cổng kiểm tra `ensureGatewayReadyBeforeStep7` & Quản lý `lastActiveSessionKey` chống khóa 180s/60s (`[Red Team RT-01, RT-05]`):**
    - Duy trì biến `lastActiveSessionKey` trong `OntMobileDriver.kt`: chỉ cập nhật khi `key.isNotEmpty() && key != "0" && key != "factoryID"` (bỏ qua giá trị giữ chỗ `"sessionKey": "0"` của LuCI). Nếu `reloginAfterRadioBounce` thất bại sau khi `logout()` xóa `sessionKey`, khôi phục `sessionKey = lastActiveSessionKey` để khối `finally { logout() }` vẫn gửi giải phóng phiên lần cuối.
    - Trong `ensureGatewayReadyBeforeStep7(...)`:
      - Chỉ bọc `try { postForm("/cgi-bin/luci/wlrestart.json", ...) } catch (e: IOException)` quanh lệnh HTTP; **không** bọc `reloginAfterRadioBounce()` hay `delay()` trong `catch (_: Exception)` để không bao giờ gọi đúp login gây tăng `numberLogin` từ `2` lên `3` (khóa phần cứng 180s) và không nuốt `CancellationException`.
      - Kiểm tra phản hồi của `wlrestart.json`: nếu `code != 200 || body.trim().startsWith("<")` **hoặc** `wlJson == null || wlJson.optString("status").equals("fail authen", ignoreCase = true) || !wlJson.has("_wlrestart_")`, gọi đúng 1 lần `reloginAfterRadioBounce(settings, bssid, previousSessionKey = lastActiveSessionKey)`!
    - Ngay sau Bước 6 + `delay(settlingDelayMs)` (ở cả nhánh 7 bước và nhánh 8 bước), gọi `ensureGatewayReadyBeforeStep7(...)` trước khi chạy Bước 7 Attempt 1. Nếu trả về `false`, ném ngay `OntNetworkError` báo Bước 1–6 đã lưu nhưng mất kết nối trước Bước 7.
  - **Đảm bảo Lưu Xong Bước 7 Mới Ngắt Kết Nối & Bảo Toàn 4G/5G Sau Hoàn Tất (`[Red Team RT-04, RT-07]`):**
    - Trong `OntMobileDriver.postForm()` (`dòng 165-186`): bọc `RequestBody` bằng `FlushTrackedRequestBody` gọi `sink.flush()` trước `allBytesFlushed.set(true)`, và bọc `IOException` bằng `FlushTrackedIOException(cause, allBytesFlushed.get())`. Ở đầu `isPreSendConnectFailure(e)` và `isPostFlushRadioDrop(e)`, **bắt buộc gỡ bọc** `val raw = (e as? FlushTrackedIOException)?.cause ?: e` để giữ đúng toàn bộ phép kiểm tra kiểu `raw is ConnectException` / `SocketTimeoutException` / `EOFException` / `SSLException`, đồng thời yêu cầu `flushed == true` trong `isPostFlushRadioDrop(e)`.
    - Trong `configureWifiBand` & vòng lặp Bước 7 (`for (step7Attempt in 1..3)` và cả trong `executeManualConnectedWifiOnly` cho Bước 8 thủ công):
      - Chụp `lastActiveSessionKey` ở đầu mỗi lần thử; nếu `wirelesscfg.json` trả về `status == "fail authen"`, coi là hết hạn phiên khả hồi phục qua `ensureGatewayReadyBeforeStep7` rồi thử lại (còn `conflict_mesh` vẫn ném ngay không retry).
      - Khi `cfgSavedToNvram == true` (`HTTP 200 {"status": "OK"}`), gọi `wlrestart.json` với `useShortLogoutTimeout = true` (`1.5s`), sau đó gọi `logout()`, `onUnbindNetwork()`, và **bỏ qua** 4.7s chờ `onVerifyNewSsid` (chỉ gọi `onVerifyNewSsid` khi `cfgSavedToNvram == false && droppedDuringRadioReload == true`).
      - Khi `wirelesscfg.json` rớt mạng sau khi đã flush (`droppedDuringRadioReload == true` và `cfgSavedToNvram == false`), chạy `val stillOnOldWifi = ensureGatewayReadyBeforeStep7(..., maxAttempts = 3, retryDelayMs = recoveryRetryDelayMs)` trên **cả 3 lần thử (`1..3`, bao gồm cả `step7Attempt == 3`)**: ở lần thử 1..2 nếu `stillOnOldWifi == true` thì `continue` thử lại; ở lần thử 3 nếu `stillOnOldWifi == true` (nghĩa là sóng cũ vẫn đang phát bình thường, ONT chưa hề áp dụng tên mới!), ép `droppedDuringRadioReload = false` và ném `OntNetworkError` — chặn đứng 100% trường hợp báo `SUCCESS` giả!
    - Trong `MainViewModel.kt` & `ExecutionProgressSheet.kt`:
      - Đổi `suppressAutoBindAfterFirmware` thành `suppressAutoBindAfterCompletion`, bật `true` khi `executeMobileSetup()` hoặc `executeManualConnectedWifiOnly()` hoàn tất, và **GIỮ NGUYÊN `true` kể cả khi gọi `dismissExecutionSheet()`** (`[Red Team RT-07]`) để bảo vệ cách ly flash Firmware 2 phút (`[RT-01]`) cũng như giữ mạng 4G/5G khi KTV đóng sheet ra màn hình danh sách bấm *"Xuất Báo Cáo"* gửi Zalo. Chỉ đặt lại `suppressAutoBindAfterCompletion = false` khi KTV chủ động thao tác phiên mới: `selectSubscriber(record != null)`, `openWifiScanner()`, `connectToOntCandidate()`, `bindExistingWifiConnection()`, `joinNewlyConfiguredWifi()`, `runOntConfiguration()`, hoặc `runManualConnectedWifiStep()`.
      - Trong `ExecutionProgressSheet.kt` & `OntWifiConnector.promptJoinCustomerWifi()`: chọn SSID/mật khẩu ưu tiên theo băng tần kết nối thực tế (`resolvedConnectedBand`) và đăng ký `WifiNetworkSuggestion` cho cả 2 băng tần (`newSsid24g` & `newSsid5g`) khi KTV chủ động bấm nút *"Kết nối vào Wi-Fi mới để kiểm tra mạng"*.
- **Non-functional:**
  - Tổng thời gian hoàn tất 7 bước trên thiết bị thật đạt ~6–9 giây (nhờ bỏ Band-Hopping 25s và bỏ 4.7s chờ quét beacon thừa khi `cfgSavedToNvram == true`).
  - Giữ nguyên 100% tính năng nạp Firmware 8 bước và 6 chuẩn bảo mật Wi-Fi.

## Architecture
- Luồng thực thi 7 bước trên Android:
  `Step 1 (Probe)` $\rightarrow$ `Step 2 (Login & Admin Pass, lưu lastActiveSessionKey)` $\rightarrow$ `resolveConnectedBandFromOnt(localWifiIpv4, connectedBand)` (đồng bộ `connectedBand` & `baseMac`) $\rightarrow$ `Step 3..5 (WAN, SLID, LAN)` $\rightarrow$ `Step 6: configureWifiBand(step6Unit, isConnectedBandFinalStep=false)` + `delay(400ms)` $\rightarrow$ `ensureGatewayReadyBeforeStep7(recoveryRetryDelayMs=1200ms)` $\rightarrow$ `Step 7: configureWifiBand(step7Unit, isConnectedBandFinalStep=true)` (kiểm tra `cfgSavedToNvram` hoặc `allBytesFlushed && !stillOnOldWifi` trên cả `1..3`) $\rightarrow$ `logout(1.5s)` $\rightarrow$ `onUnbindNetwork()` + `suppressAutoBindAfterCompletion = true`.

## Related Code Files
- Modify: `/home/vtst/ONT-config/android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`
- Modify: `/home/vtst/ONT-config/android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
- Modify: `/home/vtst/ONT-config/android-app/app/src/main/java/vn/vnpt/ontconfig/ui/MainViewModel.kt`
- Modify: `/home/vtst/ONT-config/android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
- Modify: `/home/vtst/ONT-config/android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/SubscriberListScreen.kt`
- Modify: `/home/vtst/ONT-config/android-app/app/src/main/java/vn/vnpt/ontconfig/ui/components/ExecutionProgressSheet.kt`

## Implementation Steps
1. **Cập nhật `OntWifiConnector.kt`:**
   - Xóa `switchToConfiguredOntWifi()` và `isSystemConnectedToTargetWifi()`.
   - Thêm `getBoundWifiIpv4Address(): String?`, nâng cấp `detectConnectedBand()` đọc `NetworkCapabilities.transportInfo` (`API >= 31`), và lưu đệm `lastSelectedPassphrase` / `lastSelectedSecurityMode` / `frequencyMhz` trong `connectToOntWifi(...)` + hỗ trợ đăng ký cả 2 băng tần trong `promptJoinCustomerWifi(...)`.
2. **Cập nhật `OntMobileDriver.kt`:**
   - Xóa `onSwitchToConfiguredWifi` và `switchToSecondaryWifiAndAuthenticate()`; thêm `recoveryRetryDelayMs: Long = 1200L` và `resolvedConnectedBand: WifiBand` trong `MobileSetupResult`.
   - Thêm `FlushTrackedRequestBody` (`sink.flush()`) + `FlushTrackedIOException` kèm gỡ bọc `.cause` ở đầu `isPreSendConnectFailure` và `isPostFlushRadioDrop`.
   - Cài đặt `lastActiveSessionKey` (bỏ qua `"0"` / `"factoryID"`), sửa `ensureGatewayReadyBeforeStep7` bắt `"fail authen"` và không gọi đúp `reloginAfterRadioBounce`, cài đặt `resolveConnectedBandFromOnt` ngay sau Bước 2 (mảng 2 chiều `wirelessInfoList`), và kiểm tra `stillOnOldWifi` ở cả 3 lần thử Bước 7 cũng như trong `executeManualConnectedWifiOnly()`.
3. **Cập nhật `MainViewModel.kt`, `ConfigReviewScreen.kt`, `SubscriberListScreen.kt` & `ExecutionProgressSheet.kt`:**
   - Xóa toàn bộ nhánh `hasSwitchedToSecondaryBand` (`MainViewModel.kt:780-817, 840-861`), kiểm tra `boundWifiNetwork != null` trước khi probe/RUN, đặt `settlingDelayMs = 400L`, giữ `suppressAutoBindAfterCompletion = true` qua `dismissExecutionSheet()`, đồng bộ nhãn `"Wi-Fi ONT Đang Kết Nối"` khi ẩn SSID và cập nhật nút kết nối Wi-Fi mới trên `ExecutionProgressSheet.kt`.

## Todo
- [x] Xóa mã chết Band-Hopping và bổ sung `getBoundWifiIpv4Address`, `transportInfo` (`API >= 31`), lưu đệm passphrase/secMode/frequency trong `OntWifiConnector.kt`
- [x] Cài đặt `recoveryRetryDelayMs`, `lastActiveSessionKey`, `resolveConnectedBandFromOnt` (sau Bước 2, 2D `wirelessInfoList`), `FlushTrackedIOException` unwrap và kiểm tra `stillOnOldWifi` cho cả Attempt `1..3` trong `OntMobileDriver.kt`
- [x] Cập nhật `MainViewModel.kt` (`settlingDelayMs = 400L`, `suppressAutoBindAfterCompletion` giữ qua `dismissExecutionSheet`, chặn null `boundWifiNetwork`), `ConfigReviewScreen.kt`, `SubscriberListScreen.kt` và `ExecutionProgressSheet.kt`

## Success Criteria
- [x] Không còn bất kỳ lệnh `logout()` hay `unbindOntWifi()` nào chạy giữa Bước 6 và Bước 7 trên bản Android.
- [x] `resolveConnectedBandFromOnt` chạy ngay sau Bước 2, đọc chính xác mảng 2 chiều `wirelessInfoList` của `deviceinfo2.json` và không bị timeout bởi ngưỡng `1.5s`.
- [x] `ensureGatewayReadyBeforeStep7` phát hiện `"fail authen"`, không bao giờ gọi đúp login gây khóa 180s, và Bước 7 Attempt 3 không bao giờ báo `SUCCESS` giả nếu sóng cũ vẫn còn phản hồi.

## Risk Assessment
- **Risk 1:** Payload nhỏ (~380 bytes) của `wirelesscfg.json` có thể đi vào send buffer cục bộ trước khi socket đứt khiến `allBytesFlushed == true` dù ONT chưa commit NVRAM.
  - **Signal:** Cả 3 lần thử Bước 7 đều gặp `SocketException` / `SocketTimeoutException` trong khi sóng cũ của ONT vẫn phát bình thường.
  - **Mitigation:** Chạy `stillOnOldWifi = ensureGatewayReadyBeforeStep7(maxAttempts = 3, retryDelayMs = recoveryRetryDelayMs)` trên **cả 3 lần thử (`1..3`)**; nếu ở lần thử thứ 3 sóng cũ vẫn sống (`stillOnOldWifi == true`), ép `droppedDuringRadioReload = false` và ném `OntNetworkError` (`[Red Team RT-04]`).
- **Risk 2:** KTV đóng `ExecutionProgressSheet` ngay sau khi chạy xong (hoặc trong lúc chờ 2 phút ONT ghi flash Firmware) để bấm nút Xuất Báo Cáo gửi Zalo.
  - **Signal:** Vòng lặp probe 1s tự gọi `bindProcessToNetwork(ontWifi)` làm rớt mạng 4G/5G hoặc bắn HTTP vào ONT đang ghi flash.
  - **Mitigation:** Giữ nguyên `suppressAutoBindAfterCompletion = true` khi đóng sheet; chỉ mở lại khi KTV chủ động chọn thuê bao tiếp theo, mở quét Wi-Fi, hoặc bấm RUN (`[Red Team RT-07]`).
