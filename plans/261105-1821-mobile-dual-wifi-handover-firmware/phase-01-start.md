---
phase: 1
title: "Phase 1: Android Wi-Fi Handover Engine & Two-Connection Firmware Driver"
status: completed
priority: P1
effort: "2.5h"
dependencies: []
---

# Phase 1: Android Wi-Fi Handover Engine & Two-Connection Firmware Driver

## Context Links
- Research & Red Team: [`research/researcher-01-android-and-luci-protocol.md`](./research/researcher-01-android-and-luci-protocol.md), [`reports/red-team-261105-mobile-dual-wifi-handover-firmware.md`](./reports/red-team-261105-mobile-dual-wifi-handover-firmware.md)
- Driver & TLS: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`, `UnsafeSslClient.kt`
- Wi-Fi Connector: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
- Settings & Validator: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`, `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`

## Overview
Tái thiết kế tầng kết nối Wi-Fi (`OntWifiConnector.kt`), ghim chứng chỉ TLS liên kết nối (`UnsafeSslClient.kt`) và trình điều khiển giao thức LuCI (`OntMobileDriver.kt`) trên Android để thực thi trọn vẹn quy trình **2 lần kết nối tự động + đổ Firmware mới ở bước cuối** với ngân sách timeout dài và bảo vệ tuyệt đối chống khóa phần cứng 180s.

## Key Insights
- Trên phần cứng GW040-NSv2 (`ont-040NSv2-setup.jsonl:152, 361`), `wireless.js` poll `wlrestart.json` tới `30,000 ms`, và băng tần 5GHz (`chanspec = auto`) thường chọn kênh DFS `100` hoặc `120` mất **15–65 giây** để phát beacon tên Wi-Fi mới sau Lần 1.
- **`[Red Team RT-01]`:** Sau Bước 2, mật khẩu Admin ONT đã chắc chắn là `settings.adminNewPass`. Mọi bước xác thực lại ở Bước 7, 8, 9 **tuyệt đối không được gọi `loginAndChangePassword` hay `reloginAfterRadioBounce` có `adminDefaultPass` (`"admin"`)**, vì bất kỳ lỗi rớt socket thoáng qua nào cũng sẽ làm vòng lặp rơi sang thử `"admin"` và chạm ngưỡng khóa phần cứng 180s (`numberLogin >= 2`).
- **`[Red Team RT-03, RT-04]`:** Ghim SHA-256 của chứng chỉ X.509 lá (`peerCertificates[0]`) ghi nhận ở Lần 1 vào `UnsafeSslClient` cho Lần 2 giúp chặn tức thì Evil-Twin AP hoặc bắt nhầm ONT hàng xóm ngay tại tầng bắt tay TLS trước khi gửi `login.cmd`! Đồng thời loại bỏ hoàn toàn shortcut `hasActiveOntSubnetWifi()` khi tắt GPS để Android không bao giờ bind nhầm lại `Band A` cũ.
- **`[Red Team RT-08]`:** Với máy Android đời cũ chỉ có phần cứng `2.4GHz` (`!is5GHzBandSupported()`), thay vì chặn cứng không cho KTV làm việc, Lần 1 cấu hình `5GHz` (`wl_unit=1`) rồi đổi luôn `2.4GHz` (`wl_unit=0`), sau đó Bước 7 tự ghép nối lại vào chính mạng `2.4GHz` vừa đổi tên (`wifi24Ssid`) để Lần 2 xác nhận NVRAM cả 2 băng tần và đổ Firmware ở Bước 9.

## Requirements
- **Functional:**
  - **Trong `GlobalSettings.kt` (`:75-96`):**
    - Bổ sung `wifiHandoverTimeoutMs: Long = 120_000L` (120 giây đợi ONT khởi động lại sóng & cập nhật tên Wi-Fi sau Lần 1 + ghép nối sang Wi-Fi mới).
    - Bổ sung `gatewayPostHandoverTimeoutMs: Long = 45_000L` (45 giây chờ `probeGateway()` phản hồi sau khi chuyển sóng).
    - Bổ sung `postSecondWifiSettleTimeoutMs: Long = 60_000L` (60 giây đồng bộ/rebind sau khi đổi Wi-Fi thứ 2 trước khi upload Firmware).
    - Nâng `firmwareUploadTimeoutMs: Long = 180_000L` (180 giây cho mạng Wi-Fi yếu / file `.signed` 35 MB).
  - **Trong `OntInputValidator.kt` (`:32-42, 344-350`) `[Red Team RT-02]`:**
    - Mở rộng biểu thức che thông tin nhạy cảm `maskSensitiveCredentials` bổ sung `nvramWlWpaPsk`, `nvramWlRadiusKey`, và `nvramWlKey[1-4]?` để đọc `wirelesscfg.json` read-only không bao giờ lộ mật khẩu Wi-Fi trong thông báo lỗi hay file Excel.
  - **Trong `UnsafeSslClient.kt` (`:13-55`) `[Red Team RT-03, RT-04]`:**
    - Bổ sung hỗ trợ ghi nhận SHA-256 fingerprint của chứng chỉ X.509 lá (`peerCertificates.firstOrNull()`) ở Lần kết nối 1 và tùy chọn `pinnedCertSha256: String? = null` khi tạo `OkHttpClient` cho Lần kết nối 2 (nếu chứng chỉ khác với ONT ở Lần 1 thì ném `SSLPeerUnverifiedException` ngay ở bước bắt tay TLS, trước khi gửi bất kỳ mật khẩu hay `sessionKey` nào).
  - **Trong `OntWifiConnector.kt` (`:48-712`) `[Red Team RT-03, RT-06, RT-08, RT-09]`:**
    - Thêm `fun is5GHzBandSupported(): Boolean`, `fun isWpa3SaeSupported(): Boolean` (public wrapper của `isWpa3SaeSupportedSafe()`), `fun isLocationServiceEnabled(): Boolean`, và `fun validateHandoverSecurityCapability(secMode: String): String?` (chặn sớm `wpa3_psk` khi `SDK_INT < 29 || !isWpa3SaeSupported()`, chặn `wpa_psk` thuần trên `SDK_INT >= 29` kèm gợi ý chọn `wpa_wpa2_psk` hoặc `wpa2_psk`).
    - Quản lý hạn mức quét toàn tiến trình Android 9+ (`private val scanTimestampsMs = ArrayDeque<Long>()` trong cửa sổ trượt `120_000 ms`, tối đa 4 lần `startScan()`): mọi lời gọi `startScan()` trong `scanOntNetworks` và `switchToConfiguredSecondaryWifi` đều kiểm tra quota còn lại; xóa bỏ hàm chết `verifyNewSsidBroadcast` (`:565-605`).
    - Sửa lỗi `NetworkCallback.onLost(network)` (`:315-323`): đặt `bindProcessNetworkSafe(null)` **bên trong** khối `if (boundWifiNetwork == network)` để sự kiện `onLost` trễ của Lần 1 không gỡ nhầm bind của Lần 2; trên API 26–28 lưu `legacyAddedNetworkId` để gọi `wifiManager.removeNetwork(netId)` sạch sẽ trong `unbindOntWifi()`.
    - Cài đặt `suspend fun switchToConfiguredSecondaryWifi(targetSsid: String, passphrase: String, targetBand: WifiBand, securityMode: String, targetBssidHint: String? = null, excludedNetwork: Network? = boundWifiNetwork, timeoutMs: Long = 120_000L, initialSettleMs: Long = 4_000L, onStatusUpdate: ((String) -> Unit)? = null): Network`:
      1. Ghi nhớ `excludedNetwork` (mạng `Band A` của Lần 1), gỡ bind kết nối cũ (`unbindOntWifi(clearSelectedMetadata = false)`), cập nhật `lastSelectedSsid = targetSsid`, `lastSelectedBand = targetBand`, `lastSelectedPassphrase = passphrase`, `lastSelectedSecurityMode = securityMode`, `lastSelectedBssid = null`.
      2. **Tuyệt đối không dùng** `hasActiveOntSubnetWifi()`, `isSameOntBssid` hay fallback `onUnavailable() -> bindCurrentWifiNetworkIfConnected()` khi `currentSysSsid` rỗng (`<unknown ssid>` do tắt GPS); chỉ cho phép bind nhanh mạng hệ thống có sẵn nếu `network != excludedNetwork` **và** `currentSysSsid.equals(targetSsid, ignoreCase = true)`.
      3. **Pha A — Đợi ONT khởi động lại sóng & phát tên Wi-Fi mới (`targetSsid`):** Chờ `initialSettleMs` (`4s`). Nếu `isLocationServiceEnabled() == true`: lắng nghe `SCAN_RESULTS_AVAILABLE_ACTION` (gỡ trong `finally`), kiểm tra `scanResults` mỗi `1,500 ms` (khớp `SSID == targetSsid`, ưu tiên cùng BSSID `targetBssidHint` từ `deviceinfo2.json` nếu có), và chỉ gọi `startScan()` tối đa **1–2 lần** nếu còn quota toàn tiến trình trong cửa sổ `35_000 ms`; nếu tắt GPS (`isLocationServiceEnabled() == false`), chờ thêm `4_000 ms` (tổng `8s`) rồi chuyển thẳng sang Pha B.
      4. **Pha B — Ghép nối bền bỉ trong toàn bộ quỹ thời gian còn lại của `timeoutMs` (`120s`):**
         - **Android 10+ (`API >= 29`)**: chỉ dùng `WifiNetworkSpecifier` (không gọi `addNetworkSuggestions` song song), mỗi lượt `requestNetwork` cho tối đa `45_000 ms`, chờ `awaitWifiIpv4Ready(network, maxWaitMs = 5_000L)` cấp `192.168.1.x`; nếu `onUnavailable` (ví dụ kênh DFS đang CAC), nghỉ `2,500 ms` và tự thử lại đến hết `timeoutMs`.
         - **Android 8.0–9.0 (`API 26–28`)**: tạo `WifiConfiguration` (`SSID = "\"$targetSsid\""`, Open hoặc `preSharedKey`), `addNetwork` + `enableNetwork(netId, true)` + `reconnect()` lặp lại trong ngân sách `timeoutMs`, chờ `Network != excludedNetwork` + `awaitWifiIpv4Ready(..., 5_000L)`, và xóa `netId` khi `unbindOntWifi()`.
  - **Trong `OntMobileDriver.kt` (`:52-1696`) `[Red Team RT-01..RT-05, RT-08, RT-09]`:**
    - Bổ sung `data class WifiHandoverContext(val socketFactory: SocketFactory, val localWifiIpv4: String?)` (`socketFactory` non-null trên thực tế; hỗ trợ cờ test nội bộ cho `MockWebServer`).
    - Bổ sung các tham số constructor tách biệt hoàn toàn với `settlingDelayMs` (`[Red Team RT-05]`):
      - `private val wlRestartPollIntervalMs: Long = 1000L`
      - `private val maxWlRestartPolls: Int = 30`
      - `private val userExistPollIntervalMs: Long = 3000L`
      - `private val allowSingleBand24gFallback: Boolean = false` (bật khi `!wifiConnector.is5GHzBandSupported()` `[Red Team RT-08]`)
      - `private val onSwitchToSecondaryWifi: (suspend (ssid: String, password: String, band: WifiBand, secMode: String, bssidHint: String?, timeoutMs: Long, onDetail: (String) -> Unit) -> WifiHandoverContext)? = null` (ném `OntDriverException` fail-closed nếu `null` ở Bước 7).
    - Trong Step 2c (`resolveConnectedBandFromOnt`): luôn truy vấn `deviceinfo2.json` kể cả khi `localWifiIpv4` trống để luôn thu thập được `baseMacAddress` (cắt `\r\n`) và BSSID từng radio `wl0`/`wl1` từ `wirelessInfoList` (`[Red Team RT-02]`).
    - Cài đặt `private suspend fun authenticateSecondConnection(newAdminPass: String, previousSessionKey: String, userExistWaitBudgetMs: Long = 65_000L)`:
      - Chỉ gọi `login("admin", newAdminPass)` — không bao giờ gửi `adminDefaultPass` (`[Red Team RT-01]`).
      - Nếu gặp `OntUserExistError`: dùng `previousSessionKey` bắn `logout.cmd` giải phóng ngay rồi thử lại; nếu vẫn báo `"user exist"`, lặp chờ mỗi `userExistPollIntervalMs` (`3s`) tối đa `userExistWaitBudgetMs` (`65s`, hoặc ngân sách rút gọn từ `settings.gatewayPostHandoverTimeoutMs` trong test).
    - Thay thế hoàn toàn `ensureGatewayReadyBeforeStep7` / `reloginAfterRadioBounce` sau Bước 2 bằng `private suspend fun ensureGatewayReadyOnSecondaryBand(settings: GlobalSettings, timeoutBudgetMs: Long)`: chạy theo deadline thời gian thực, gọi `onRebindWifiNetwork` (trả về non-null `SocketFactory`), `probeGateway()`, kiểm tra `wlrestart.json` (`_wlrestart_ == "0"`) và xác thực lại **duy nhất qua `authenticateSecondConnection(settings.adminNewPass, lastActiveSessionKey)`** (`[Red Team RT-01]`).
    - Cài đặt `private fun verifySecondConnectionBandAndMac(localIpv4: String?, expectedBand: WifiBand, expectedOntBaseMac: String?)` (`[Red Team RT-02]`):
      - Đọc `deviceinfo2.json` xác nhận `baseMacAddress` khớp `expectedOntBaseMac` (fail-closed nếu sai lệch); đọc `dhcpclient.json` kiểm tra bảng trực tiếp `wlAssociatedDevice` khớp `expectedBand` **vô điều kiện** (kể cả khi `ssid24g == ssid5g`, không dùng `dhcpInfoList` cũ).
    - Cài đặt hàm kiểm tra NVRAM read-only `private fun readConfiguredBandSsidFromNvram(unit: Int): String?` gửi đúng `page=ssid.asp&wl_unit=$unit&wl_bssid=0&sessionKey=$encKey` (không có `action=Apply`) lấy `nvramWlSsid` (`[Red Team RT-02]`).
    - Cập nhật `executeMobileSetup()` sang trình tự **8 bước (`enableFirmwareUpdate == false`)** / **9 bước (`enableFirmwareUpdate == true`)**:
      - **Bước 1–5:** Probe Gateway (ghi nhận SHA-256 TLS cert của ONT) $\rightarrow$ Login & Đổi mật khẩu Admin + đọc `resolveConnectedBandFromOnt` (`Band A`, `baseMacAddress`, BSSID `Band B`) $\rightarrow$ WAN PPPoE $\rightarrow$ GPON SLID $\rightarrow$ LAN IPv4.
      - **Bước 6:** Cấu hình Wi-Fi phụ (`Band B`, poll `wlrestart.json` tối đa `maxWlRestartPolls = 30` × `wlRestartPollIntervalMs`). *(Nếu `allowSingleBand24gFallback == true` trên máy chỉ có 2.4GHz: đổi tiếp luôn `2.4GHz` `wl_unit=0` với `isConnectedBandFinalStep = true` rồi đặt mục tiêu Bước 7 là `(wifi24Ssid, wifi24Pass, BAND_2_4_GHZ)` `[Red Team RT-08]`).* Lưu `conn1SessionKey = lastActiveSessionKey` và gọi `logout()`.
      - **Bước 7:** Gọi `onSwitchToSecondaryWifi(...)` với `timeoutMs = settings.wifiHandoverTimeoutMs`, tạo client ghim `pinnedCertSha256` với `SocketFactory` mới, poll `probeGateway()` tối đa `settings.gatewayPostHandoverTimeoutMs` (`45s`), gọi `authenticateSecondConnection`, và kiểm tra `verifySecondConnectionBandAndMac`.
      - **Bước 8:** Đổi tên & cấu hình Wi-Fi còn lại (`Band A`, hoặc xác nhận lại NVRAM cả `wl0` & `wl1` trên nhánh `allowSingleBand24gFallback`). Nếu rớt gói trước khi rõ kết quả, chạy `ensureGatewayReadyOnSecondaryBand`, đọc `readConfiguredBandSsidFromNvram(primaryUnit)` (nếu đã khớp `primarySsid` thì bắn bổ sung `wlrestart.json`, nếu chưa khớp thì gửi lại `configureWifiBand` 1 lần `[Red Team RT-02]`).
      - **Bước 9 (khi `enableFirmwareUpdate == true` — Bước cuối):** Chạy `ensureGatewayReadyOnSecondaryBand(settings, settings.postSecondWifiSettleTimeoutMs)` (`60s`), gọi `updateFirmware(fwFile, settings.firmwareUploadTimeoutMs)` (có callback % byte qua `FlushTrackedRequestBody`), gọi `clearSessionKeys()` ngay khi `"status": "valid"` hoặc post-flush drop, gọi `onUnbindNetwork()` và hoàn tất `9/9` bước! (Khi `enableFirmwareUpdate == false`: gọi `logout()`, `onUnbindNetwork()` và hoàn tất `8/8` bước).
    - **Clean Cutover:** Xóa sạch `executeManualConnectedWifiOnly`, `applyFinalConnectedBandWithRetries`, `ensureGatewayReadyBeforeStep7`, `reloginAfterRadioBounce`, `onVerifyNewSsid`, `manualWifiStepPending`, `manualWifiUnit`, `manualWifiBandLabel`, `manualWifiSsid`, `manualWifiPass`, `alreadyConfiguredOppositeBand`.

## Related Code Files
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/UnsafeSslClient.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`

## Implementation Steps
1. Mở rộng `GlobalSettings.kt` (các hằng số timeout `120s`, `45s`, `60s`, `180s`), `OntInputValidator.kt` (che `nvramWlWpaPsk` / `nvramWlRadiusKey` / `nvramWlKey`), và `UnsafeSslClient.kt` (ghi nhận & ghim SHA-256 leaf cert `pinnedCertSha256`).
2. Cập nhật `OntWifiConnector.kt`: thêm `validateHandoverSecurityCapability`, bộ đếm trượt 4 lần `startScan()`/120s toàn tiến trình, sửa `onLost`, cài đặt `switchToConfiguredSecondaryWifi` (loại trừ `excludedNetwork` và không dùng `hasActiveOntSubnetWifi` khi tắt GPS), và xóa `verifyNewSsidBroadcast`.
3. Cập nhật `OntMobileDriver.kt`: tách tham số `wlRestartPollIntervalMs` / `maxWlRestartPolls` / `userExistPollIntervalMs`, cài đặt `authenticateSecondConnection`, `ensureGatewayReadyOnSecondaryBand`, `verifySecondConnectionBandAndMac`, `readConfiguredBandSsidFromNvram`, nhánh `allowSingleBand24gFallback`, luồng 8/9 bước 2 lần kết nối + Firmware cuối, và xóa toàn bộ mã chết cũ.

## Todo List
- [x] Cập nhật `GlobalSettings.kt`, `OntInputValidator.kt` (`nvramWlWpaPsk` masking) và `UnsafeSslClient.kt` (leaf cert SHA-256 pinning)
- [x] Cài đặt `switchToConfiguredSecondaryWifi`, quota `startScan()` toàn tiến trình, sửa `onLost` và xóa `verifyNewSsidBroadcast` trong `OntWifiConnector.kt`
- [x] Cài đặt máy trạng thái 2 lần kết nối (`authenticateSecondConnection`, `ensureGatewayReadyOnSecondaryBand`, `verifySecondConnectionBandAndMac`, nhánh 2.4GHz-only, Bước 9 `updateFirmware`) và dọn mã chết trong `OntMobileDriver.kt`

## Success Criteria
- [x] Không còn bất kỳ lời gọi nào gửi `adminDefaultPass` sau Bước 2; ghim chứng chỉ TLS SHA-256 chặn kết nối nhầm sang AP lạ hoặc ONT khác trước khi gửi `login.cmd`.
- [x] `OntWifiConnector.switchToConfiguredSecondaryWifi` không bám nhầm lại `Band A` khi tắt GPS, không vượt quá hạn mức 4 lần `startScan()`/120s của tiến trình, và hỗ trợ cả máy chỉ có 2.4GHz.
- [x] Cả 2 băng tần Wi-Fi đều được lưu NVRAM hoàn tất trước khi Bước 9 nạp Firmware và xóa sạch `sessionKey` khi nhận `"status": "valid"`.

## Risk Assessment
- **Rủi ro 1 — Kênh 5GHz nhảy vào DFS (`100`/`120`) mất 30–60s CAC mới phát sóng `SSID_B`:**
  - *Observable signal:* Chưa thấy `SSID_B` trong 20s đầu hoặc lần ghép nối `WifiNetworkSpecifier` đầu tiên trả về `onUnavailable`.
  - *Pre-decided response:* Ngân sách `wifiHandoverTimeoutMs = 120_000L` chờ beacon tiết kiệm quota rồi tự động lặp lại `requestNetwork` nhiều vòng cho đến hết 120s.
- **Rủi ro 2 — `wlmngr` làm gián đoạn nhẹ `Band B` ngay sau Bước 8 trước khi Bước 9 upload Firmware:**
  - *Observable signal:* Socket trên `Band B` bị reset hoặc `wlrestart.json` trả về `"fail authen"`.
  - *Pre-decided response:* `ensureGatewayReadyOnSecondaryBand` (`60s`) tự gọi `onRebindWifiNetwork` vào đúng `SSID_B` và xác thực lại duy nhất bằng `adminNewPass` trước khi gửi `updatefwsessioncheck.json`.

## Security Considerations
- Ghim SHA-256 chứng chỉ TLS lá của ONT từ Lần 1 sang Lần 2 (`[Red Team RT-04]`).
- Che đầy đủ `nvramWlWpaPsk`, `nvramWlRadiusKey`, `nvramWlKey` trong mọi phản hồi lỗi (`[Red Team RT-02]`).
- Dọn sạch `WifiConfiguration` tạm thời trên Android 8–9 khi ngắt kết nối (`[Red Team RT-04]`).
