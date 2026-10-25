---
phase: 2
title: "Phase 2: wifi-binding-and-ont-driver"
status: completed
priority: P1
effort: "5h"
dependencies: [1]
---

# Phase 2: wifi-binding-and-ont-driver

## Overview
Xây dựng module kết nối Wi-Fi mặc định của ONT (`OntWifiConnector`) có khả năng định tuyến lưu lượng nội bộ vượt qua mạng di động 4G/5G, và xây dựng driver giao thức LuCI CGI (`OntMobileDriver`) tối ưu hóa cho kết nối không dây với **bước đổi tên & mật khẩu Wi-Fi 2.4G đặt ở cuối cùng**.

## Requirements
- [x] **Quét & Bắt Wi-Fi Mặc Định của ONT (`OntWifiConnector`):**
  - **[Red Team RT-10]** Quét danh sách Wi-Fi xung quanh, chỉ tự động lọc và ưu tiên các SSID khớp mẫu mặc định xuất xưởng của ONT sau khi boot: `^GW040-NSv2_(2\.4G|5G)_([0-9A-Fa-f]{6})$` (sắp xếp theo cường độ sóng RSSI mạnh nhất và đối chiếu 6 ký tự hex cuối của SSID với BSSID MAC của AP; không tự động kết nối vào các mạng gia đình chỉ có tiền tố `VNPT_` chung chung để tránh kết nối nhầm hoặc Evil Twin).
  - Kết nối vào Wi-Fi mặc định của ONT (hỗ trợ cả mạng **Open** mặc định xuất xưởng lẫn mạng WPA2 nếu có mật khẩu mặc định `1234567890`).
  - **[Red Team RT-06] Ép định tuyến qua Wi-Fi & Quản lý vòng đời kết nối (Network Binding Lifecycle):**
    - **Tuyệt đối không dùng `getActiveNetwork()`** (vì khi bật 4G/5G mà Wi-Fi ONT không có Internet, `getActiveNetwork()` trả về mạng 4G/5G). Phải dùng `ConnectivityManager.requestNetwork()` với `NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)` để lấy đúng đối tượng `wifiNetwork` trong `NetworkCallback.onAvailable()`.
    - Gắn `wifiNetwork.socketFactory` vào `OkHttpClient` dành riêng cho ONT. Nếu gọi `connectivityManager.bindProcessToNetwork(wifiNetwork)`, **bắt buộc phải gọi `connectivityManager.bindProcessToNetwork(null)` và `unregisterNetworkCallback()`** ngay trong `NetworkCallback.onLost()` và khối `finally` của phiên cấu hình để không làm đứt mạng 4G/5G khi chia sẻ báo cáo qua Zalo sau Bước 7.
- [x] **Tầng Giao Thức LuCI CGI Không Dây (`OntMobileDriver`):**
  - Cấu hình `OkHttpClient` bỏ qua kiểm tra chứng chỉ SSL tự ký (`X509TrustManager` + `HostnameVerifier` chỉ cho phép duy nhất host `192.168.1.1`), giữ nguyên headers chuẩn (`X-Requested-With: XMLHttpRequest`, `Referer: https://192.168.1.1/`, `Content-Type: application/x-www-form-urlencoded`).
  - **[Red Team RT-04] Bảo vệ chống khóa phần cứng 180s khi Retry (`lastKnownAdminPass`):** Kiểm tra `numberLogin >= 2` dừng ngay và `timeoutLogin > 0` báo thời gian khóa. Đặc biệt, lưu trạng thái mật khẩu Admin theo BSSID/thiết bị (`lastKnownAdminPass`): nếu ở lần chạy trước Bước 2 đã đổi mật khẩu sang `Passwd2@` thành công (nhưng rớt mạng ở Bước 3–6), hoặc nếu thử `admin/admin` đã trả về `numberLogin >= 1`, thì khi Retry phải **ưu tiên đăng nhập bằng `newPass` (`Passwd2@`) trước** thay vì thử lại `admin/admin` một cách mù quáng (tránh đẩy `numberLogin` lên `2` hoặc `3` gây khóa cứng thiết bị).
  - **[Red Team RT-07 & RT-08] Mã hóa tham số & Kiểm tra phản hồi JSON nghiêm ngặt:**
    - Mọi giá trị tham số đưa vào form body (`wl_ssid`, `data`, `pppUserName`, `pass`, `newPass`, `pppPassword`, `wl_wpa_psk`) phải được URL-encode (`URLEncoder.encode(v, "UTF-8").replace("+", "%20")`) và áp dụng `ontEncodeQuery` theo đúng `ont-040NSv2-setup.jsonl:13,174,313,361` để không bị hỏng dữ liệu khi mật khẩu/SSID chứa dấu `+`, `%`, `=` hoặc khoảng trắng.
    - Kiểm tra cả `HTTP 200` lẫn nội dung phản hồi JSON hợp lệ trên tất cả các bước (bắt lỗi nếu ONT trả về trang HTML login redirect hoặc HTTP 4xx/5xx; không bao giờ trả về giả định `{"status": "OK"}` khi HTTP khác 200).
- [x] **Trình Tự 7 Bước Tối Ưu Hóa Đổi Wi-Fi 2.4G Ở Bước Cuối Cùng (Dynamic Band Ordering + Mandatory `finally { logout() }`):**
  - **[Red Team RT-01] Khối bảo vệ phiên `try ... finally { withContext(NonCancellable) { logout() } }`:** Toàn bộ hàm `executeMobileSetup()` phải nằm trong khối `try ... finally`. Trong `finally`, nếu `sessionKey.isNotEmpty()`, luôn gọi `POST /cgi-bin/luci/logout.cmd` (`status=user want to logout&user=admin&sessionKey={sessionKey}`) với timeout ngắn (`1.5s`, bỏ qua lỗi socket nếu sóng Wi-Fi đã tắt sau Bước 7). Điều này đảm bảo nếu bất kỳ Bước 2–6 nào gặp lỗi hoặc bị hủy giữa chừng, phiên `admin` được giải phóng ngay lập tức, không bao giờ gây lỗi `"user exist"` (khóa 60s) khi KTV bấm chạy lại.
  - **Bước 1 (Probe Gateway & Xác Thực Chữ Ký ONT):** **[Red Team RT-10]** Tạm dừng (`pause`) coroutine thăm dò nền của `OntWifiStatusBanner` qua `Mutex` barrier (đợi request probe đang bay hoàn tất để tránh tranh chấp socket trên `uHTTPd` đơn luồng của ONT, đồng bộ `app/ui/worker.py:72,139-151`). Kiểm tra `GET https://192.168.1.1/` qua `wifiNetwork` trả về `HTTP 200` **và** body chứa chữ ký nhận diện ONT (`"iGate"`, `"GW040"`, `"VNPT"`, hoặc `"login.js"`, đồng bộ `app/core/network_probe.py:66`).
  - **Bước 2 (Login & Đổi Pass Admin Thông Minh):** Đăng nhập theo thứ tự ưu tiên thông minh (`newPass` trước nếu ONT đã qua Bước 2 trước đó, ngược lại `admin/admin` rồi fallback 1 lần sang `newPass`); nếu `firstlog == "1"` (`sessionKey = "factoryID"`), gọi `accesscontrolcfg.json` đổi mật khẩu, đánh dấu BSSID đã đổi pass và đăng nhập lại bằng `newPass` để lấy `sessionKey` chính thức.
  - **Bước 3 (WAN PPPoE VLAN 11 Idempotent):** **[Red Team RT-07]** Tra cứu `wancfg.json`; nếu đã có dòng `vlanId == "11"` (hoặc `vlanMuxId == "11"`), lấy `wanIfName = entry["ifcStr"] ?: "veip0.11"` và **luôn truyền `serviceId=1`** (chế độ Edit theo đúng `wan.js` trong `ont-040NSv2-setup.jsonl:174,248`, không lấy `entry["idx"]` vì `idx=2` trong thực tế sẽ làm lỗi lệnh Edit) vào `editwan.json` + `sendwan.cmd`. Nếu chưa có VLAN 11, truyền `serviceId=0` vào `addwan.json` + `sendwan.cmd`.
  - **Bước 4 (GPON SLID):** Gửi `POST /cgi-bin/luci/gponpasswd.json` với `sessionKey={sessionKey}&data={urlEncodedSlid}` ($\le 10$ ký tự ASCII).
  - **Bước 5 (LAN IPv4):** Gửi `POST /cgi-bin/luci/savelanv4cfg.cmd` (mặc định giữ nguyên `192.168.1.1` để không mất route).
  - **Bước 6 (Cấu Hình Băng Tần Không Kết Nối Trước - Mặc định Wi-Fi 5GHz `wl_unit=1`):**
    - Kiểm tra tần số Wi-Fi hiện tại của điện thoại (`WifiInfo.frequency`): nếu điện thoại đang kết nối trên 2.4GHz (`< 3000 MHz`), Bước 6 sẽ cấu hình **Wi-Fi 5GHz (`wl_unit=1`)**; ngược lại nếu điện thoại đang kết nối trên 5GHz (`> 4900 MHz`), Bước 6 sẽ cấu hình **Wi-Fi 2.4GHz (`wl_unit=0`)**.
    - **[Red Team RT-02]** Gửi `POST /cgi-bin/luci/wirelesscfg.json` (`action=Apply`), kiểm tra `wlrestart.json` (`_wlrestart_ == "0"`) và **nghỉ đúng `2.0s` (Broadcom radio driver settling pause, đồng bộ `app/core/ont_driver.py:331-332`)** để driver không dây ổn định hoàn toàn trước khi sang Bước 7.
  - **Bước 7 (Bước Cuối Cùng - Đổi Tên & Mật Khẩu Wi-Fi 2.4GHz `wl_unit=0` Đang Kết Nối & Xử Lý Đứt Sóng):**
    - **[Red Team RT-02 & RT-06]** Gửi `POST /cgi-bin/luci/wirelesscfg.json` (`action=Apply&wl_unit=0...`) và `POST /cgi-bin/luci/wlrestart.json` để áp dụng cấu hình cho băng tần đang nuôi kết nối (2.4GHz).
    - **Xử lý `Commit Ambiguity` chuẩn xác khi radio 2.4GHz khởi động lại:** Phân biệt rõ lỗi không gửi được request (`ConnectException` trước khi ghi socket $\rightarrow$ báo lỗi thật) với hiện tượng ngắt socket sau khi dữ liệu đã đẩy đi (`EOFException` / `SocketException` / `SocketTimeoutException` khi đọc phản hồi của `wirelesscfg.json` hoặc `wlrestart.json` do radio 2.4G tắt sóng). Khi phát hiện sóng mặc định ngắt sau khi gửi lệnh Step 7:
      1. <!-- Updated: Validation Session 1 - Auto-unbind to 4G/5G immediately after Step 7 --> Giải phóng ngay `bindProcessToNetwork(null)` trong `finally` để điện thoại lập tức khôi phục định tuyến Internet qua mạng di động 4G/5G (phục vụ chia sẻ báo cáo Zalo ngay lập tức).
      2. Chuyển sang `verifyNewSsidBroadcast()` (chờ `3.5s` cho radio khởi động lại rồi lắng nghe kết quả quét có `ScanResult.timestamp` mới hơn thời điểm bắt đầu Bước 7, tối đa 1 lần gọi `startScan()` để không chạm ngưỡng giới hạn 4 lần quét / 2 phút của Android 10–14). Nếu tìm thấy SSID mới phát sóng (hoặc `wirelesscfg.json` đã trả `HTTP 200 {"status": "OK"}` và request `wlrestart.json` đã flush thành công trước khi bị hạn chế quét Wi-Fi của OS), kết luận **THÀNH CÔNG**.
    - <!-- Updated: Validation Session 1 - Optional Join New Wi-Fi button without forced system dialog --> Cung cấp nút tùy chọn **"Kết nối vào Wi-Fi mới để kiểm tra mạng"** trên thẻ hoàn tất (sử dụng `WifiNetworkSuggestion` / `Settings.Panel.ACTION_WIFI` khi KTV chủ động bấm, không tự động bật hộp thoại ép kết nối sau mỗi lần chạy).

## Architecture
```
[Điện thoại Android đang bật cả 4G/5G và Wi-Fi]
        │
        ├─► OntWifiConnector:
        │     1. Scan & Filter SSID: GW040-NSv2_2.4G_XXXXXX
        │     2. Connect via WifiNetworkSpecifier / System Wi-Fi
        │     3. Bind OkHttpClient to wifiNetwork.socketFactory
        │
        ▼
[OntMobileDriver: Chuỗi 7 bước qua https://192.168.1.1 (trong khối try ... finally { logout.cmd })]
  ├── Step 1: Pause UI Probe Mutex -> GET / (Check HTTP 200 + "iGate"/"GW040"/"VNPT")
  ├── Step 2: POST /cgi-bin/luci/login.cmd (Smart Retry Order) & accesscontrolcfg.json
  ├── Step 3: POST /cgi-bin/luci/wancfg.json -> editwan(serviceId=1)/addwan(serviceId=0) -> sendwan.cmd
  ├── Step 4: POST /cgi-bin/luci/gponpasswd.json (SLID <= 10 ASCII chars, URL-encoded)
  ├── Step 5: POST /cgi-bin/luci/savelanv4cfg.cmd (192.168.1.1)
  ├── Step 6: POST /cgi-bin/luci/wirelesscfg.json (Non-connected band: 5GHz wl_unit=1) + 2.0s settling pause
  ├── Step 7: POST /cgi-bin/luci/wirelesscfg.json (Connected band: 2.4GHz wl_unit=0) + wlrestart.json
  │           └──► Radio Reload -> Handle Post-Flush Socket Drop -> Scan-Throttle-Safe Beacon Verify -> SUCCESS!
  └── finally (NonCancellable): POST /cgi-bin/luci/logout.cmd (1.5s timeout) + bindProcessToNetwork(null)

## Related Code Files
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/wifi/OntWifiConnector.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntExceptions.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/UnsafeSslClient.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`

## Implementation Steps
1. Xây dựng `UnsafeSslClient.kt` tạo `OkHttpClient` chấp nhận chứng chỉ tự ký của `192.168.1.1` và cho phép truyền `SocketFactory` của mạng Wi-Fi vào.
2. Xây dựng `OntWifiConnector.kt`:
   - Hàm `scanOntNetworks()`: quét và lọc các mạng Wi-Fi khớp mẫu `^GW040-NSv2_(2\.4G|5G)_([0-9A-Fa-f]{6})$`, đối chiếu hậu tố MAC với BSSID.
   - Hàm `connectToOntWifi(ssid, bssid?, password?)`: sử dụng `WifiNetworkSpecifier` + `NetworkRequest` (`TRANSPORT_WIFI` + `removeCapability(NET_CAPABILITY_INTERNET)`) để lấy `Network` từ `onAvailable()`, và hàm `unbindOntWifi()` gọi `bindProcessToNetwork(null)` + `unregisterNetworkCallback()`.
   - Hàm `detectConnectedBand()`: đọc tần số `WifiInfo.frequency` để xác định điện thoại đang bắt sóng 2.4GHz hay 5GHz.
   - Hàm `verifyNewSsidBroadcast(expectedSsid24g, expectedSsid5g, step7StartElapsedMicros, timeoutMs)`: chờ `3.5s` sau khi reload radio, kiểm tra `ScanResult` có `timestamp > step7StartElapsedMicros` và gọi tối đa 1 lần `startScan()` (tuân thủ giới hạn 4 lần quét / 2 phút của Android 9+).
3. Xây dựng `OntMobileDriver.kt` cài đặt đầy đủ các API LuCI CGI (với URL-encoding, `serviceId=1` cho `editwan.json`, kiểm tra JSON status, nghỉ `2.0s` giữa 2 băng tần) và hàm `executeMobileSetup(...)` bọc trong `try ... finally { withContext(NonCancellable) { logout() } }`.

## Todo
- [x] Cài đặt `UnsafeSslClient.kt` và `OntExceptions.kt`
- [x] Cài đặt `OntWifiConnector.kt` (Scan, Connect, Bind Network Socket, Verify Beacon)
- [x] Cài đặt `OntMobileDriver.kt` với luồng 7 bước tối ưu hóa đổi Wi-Fi 2.4G ở bước cuối

## Success Criteria
- `OkHttpClient` gọi tới `192.168.1.1` luôn đi qua `wifiNetwork.socketFactory` và tự động gỡ `bindProcessToNetwork(null)` trong `finally` sau khi kết thúc.
- Nếu bất kỳ Bước 2–6 nào gặp lỗi hoặc bị hủy, `logout.cmd` luôn được gọi trong `finally` để không gây lỗi `"user exist"` (khóa 60s) ở lần chạy kế tiếp.
- Bước 6 cấu hình băng tần không kết nối (5G) kèm nghỉ `2.0s` ổn định driver mà không làm rớt kết nối 2.4G; Bước 7 cấu hình băng tần đang kết nối (2.4G) ở cuối cùng và xử lý chuẩn xác hiện tượng đứt kết nối Wi-Fi khi radio khởi động lại.
