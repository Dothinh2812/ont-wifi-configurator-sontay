---
phase: 2
title: "Phase 2: wifi-en0-routing-and-ont-driver"
status: completed
priority: P1
effort: "4h"
dependencies: [1]
---

# Phase 2: wifi-en0-routing-and-ont-driver

## Overview
Xây dựng tầng giám sát và định tuyến mạng Wi-Fi nội bộ trên iOS (`IosWifiMonitor.swift`, `UnsafeSslSession.swift`) đảm bảo lưu lượng tới `https://192.168.1.1` luôn đi qua card Wi-Fi `en0` ngay cả khi iPhone đang bật 4G/5G, cùng driver giao thức LuCI CGI 7 bước (`OntMobileDriver.swift`) đặt bước đổi tên/mật khẩu Wi-Fi của băng tần đang kết nối ở cuối cùng.

## Requirements
- [x] **[Red Team RT-05 & RT-08] Giám sát Giao diện Wi-Fi `en0`, Phát hiện VPN & Chờ DHCP (`IosWifiMonitor.swift`):**
  - Sử dụng hàm hệ thống POSIX/Darwin `getifaddrs()` để kiểm tra trực tiếp các giao diện mạng trên iOS:
    - Trích xuất địa chỉ IPv4 hiện tại của `"en0"` (ví dụ `192.168.1.2`) và subnet mask (`255.255.255.0`).
    - Xác định trạng thái `isOnOntSubnet`: trả về `true` ngay khi `"en0"` đang `IFF_UP | IFF_RUNNING` và có IP thuộc dải `192.168.1.x` (khác `192.168.1.1`).
    - Kiểm tra các giao diện `utun*` / `ipsec*` / `ppp*` đang hoạt động (`hasActiveVpnInterface`) để cảnh báo KTV tắt VPN (như 1.1.1.1 / WireGuard) nếu VPN đang chặn định tuyến nội bộ `192.168.1.1`.
  - Cung cấp hàm `awaitWifiIpv4Ready(maxWaitMs: 1200)`: khi `NWPathMonitor(requiredInterfaceType: .wifi)` hoặc `scenePhase == .active` báo có thay đổi mạng, thăm dò `getifaddrs("en0")` mỗi `80ms` (tối đa `1200ms`) chờ DHCP cấp xong địa chỉ `192.168.1.x` trước khi gọi callback về `MainViewModel` (tuyệt đối không gọi `probeGateway()` trực tiếp bên trong `IosWifiMonitor`).
- [x] **[Red Team RT-01, RT-05 & RT-09] Phiên HTTP/TLS Chấp Nhận Chứng Chỉ Tự Ký, Chặn Redirect & Theo Dõi Byte Đã Gửi (`UnsafeSslSession.swift`):**
  - Tạo `URLSession` với `URLSessionConfiguration.ephemeral`:
    - Đặt **`allowsCellularAccess = false`** (ép CFNetwork/Darwin chỉ định tuyến qua giao diện Wi-Fi `en0` / loopback, tuyệt đối không đẩy lưu lượng `192.168.1.1` sang mạng di động 4G/5G `pdp_ip0` khi Wi-Fi ONT không có Internet).
    - Đặt `waitsForConnectivity = false`, `httpShouldSetCookies = false`, `requestCachePolicy = .reloadIgnoringLocalCacheData`, và hỗ trợ truyền `protocolClasses: [AnyClass] = []` cho **cả phiên chính lẫn phiên logout `1.5s`** phục vụ kiểm thử `MockLuciURLProtocol`.
  - Cài đặt `URLSessionTaskDelegate` & `URLSessionDelegate`:
    - **Chặn toàn bộ HTTP Redirect (`301/302/307/308`):** Trong `urlSession(_:task:willPerformHTTPRedirection:newRequest:completionHandler:)`, luôn gọi `completionHandler(nil)` để ngăn chặn tấn công chuyển hướng rò rỉ mật khẩu PPPoE/Admin.
    - **Xác thực chứng chỉ tự ký giới hạn host:** Chỉ chấp nhận `URLCredential(trust: serverTrust)` khi `protectionSpace.host` thuộc `["192.168.1.1", "localhost", "127.0.0.1"]` (hoặc `allowedHostOverride` trong kiểm thử).
    - **Theo dõi `didSendBodyData` (`totalBytesSent >= expectedBytes`):** Ghi nhận chính xác xem toàn bộ HTTP POST payload của mỗi `URLSessionTask` đã được đẩy qua socket thành công hay chưa, giúp Bước 7 phân biệt chắc chắn giữa lỗi timeout kết nối trước khi gửi (`TCP SYN timeout` $\rightarrow$ chưa gửi được byte nào) và lỗi ngắt/timeout đọc phản hồi sau khi radio ONT khởi động lại (`post-flush radio drop`).
- [x] **[Red Team RT-01, RT-02, RT-03] Driver Giao Thức LuCI CGI Không Dây 7 Bước (`OntMobileDriver.swift`):**
  - Đồng bộ 100% trình tự và cơ chế bảo vệ an toàn với `OntMobileDriver.kt`:
    - **Khóa đồng bộ thăm dò nền (`ProbePauseMutex`):** Cung cấp cả `lock()`/`unlock()` (cho `executeMobileSetup`) và `tryLock()` không chặn (cho `MainViewModel.performGatewayProbeOnce()`) để tránh tranh chấp socket trên `uHTTPd` đơn luồng của ONT.
    - **Khối bảo vệ phiên bắt buộc (`Task.detached { logout() }`):** Đảm bảo nếu bất kỳ Bước 2–6 nào gặp lỗi hoặc bị hủy, `POST /cgi-bin/luci/logout.cmd` luôn được gửi qua `logoutSession` (`1.5s` timeout) trong một `Task.detached` không bị hủy theo Task cha để giải phóng `sessionKey`, triệt tiêu lỗi `"user exist"` (treo 60s).
    - **Chống khóa phần cứng 180s (`numberLogin < 2` & `recordPasswordCache` theo `(rowIndex, soMay)`):** Kiểm tra `numberLogin >= 2` dừng ngay và báo `OntAccountLockoutWarning`. Lưu mật khẩu đã đổi thành công vào `SubscriberRecord.lastKnownAdminPass` (qua callback `onAdminPasswordChanged` ghi xuống SQLite ngay sau Bước 2) và bộ nhớ đệm theo khóa `(record.rowIndex, record.soMay)` (tuyệt đối không dùng chung khóa `"192.168.1.1"` giữa các thuê bao khác nhau để tránh nhiễm chéo mật khẩu khi chuyển sang ONT mới).
    - **Bước 1 (Probe Gateway & Signature):** `GET https://192.168.1.1/` trả về `HTTP 200` và body chứa `"iGate"`, `"GW040"`, `"VNPT"`, hoặc `"login.js"`.
    - **Bước 2 (Smart Login & Đổi Pass Admin):** Đăng nhập thông minh (`newPass` trước nếu `record.lastKnownAdminPass` hoặc cache của bản ghi đó đã có, ngược lại `admin/admin` rồi fallback sang `newPass`); đổi mật khẩu qua `/cgi-bin/luci/accesscontrolcfg.json` nếu `firstlog == "1"` (`sessionKey = "factoryID"`) và đăng nhập lại bằng `newPass`.
    - **Bước 3 (WAN PPPoE VLAN 11 Idempotent):** Kiểm tra `wancfg.json`; nếu đã có `vlanId == "11"` (hoặc `vlanMuxId == "11"`), lấy `ifcStr` (mặc định `"veip0.11"`) và truyền **`serviceId=1`** vào `editwan.json` + `sendwan.cmd`. Nếu chưa có, truyền **`serviceId=0`** vào `addwan.json` + `sendwan.cmd`.
    - **Bước 4 (GPON SLID):** `POST /cgi-bin/luci/gponpasswd.json` (`<= 10` ký tự ASCII, URL-encoded).
    - **Bước 5 (LAN IPv4):** `POST /cgi-bin/luci/savelanv4cfg.cmd` (`192.168.1.1`, kiểm tra hợp lệ các trường IPv4/Mask).
    - **Bước 6 (Cấu hình Wi-Fi băng tần phụ trước + `settlingDelayMs = 200ms`):** Nếu đang kết nối trên 2.4GHz (`connectedBand == .band24GHz`), Bước 6 cấu hình 5GHz (`wl_unit=1`) + gọi `wlrestart.json` + nghỉ ngắn `settlingDelayMs` (`200ms` giống `MainViewModel.kt:538`) để Bước 7 Attempt 1 thực thi ngay khi kết nối HTTP còn ấm trước khi driver Broadcom kịp bounce radio.
    - **Bước 7 (Bước Cuối Cùng - Đổi tên & mật khẩu Wi-Fi băng tần đang kết nối + `ensureGatewayReadyBeforeStep7` & Xử lý rớt sóng):**
      - Cấu hình băng tần đang kết nối (`wl_unit=0` cho 2.4GHz, hoặc `wl_unit=1` cho 5GHz) ở bước cuối cùng kèm tối đa 3 lần thử.
      - Nếu Attempt 1 gặp lỗi kết nối hoặc rớt sóng do đợt restart trễ của Bước 6, gọi `ensureGatewayReadyBeforeStep7` (kiểm tra xem gateway cũ `192.168.1.1` còn phản hồi không và tự động `loginAndChangePassword` lại nếu phiên bị reset) để thử lại Attempt 2–3.
      - Chỉ phân loại ngoại lệ mạng ở Bước 7 (`networkConnectionLost`, `timedOut`, `ECONNRESET`, `EPIPE`, EOF) là `droppedDuringRadioReload = true` khi: (a) ứng dụng không bị đẩy xuống nền (`!isAppInBackground`), và (b) đối với `URLError.timedOut`, `UnsafeSslSession` xác nhận `didSendBodyData` đã truyền đi toàn bộ payload (`totalBytesSent >= expectedBytes`) hoặc lệnh `wirelesscfg.json` đã nhận `HTTP 200` trước khi `wlrestart.json` ngắt sóng.

## Architecture
```
[iPhone đang bật cả 4G/5G (pdp_ip0) và kết nối Wi-Fi ONT (en0: 192.168.1.x)]
        │
        ├─► IosWifiMonitor:
        │     1. getifaddrs("en0") -> Kiểm tra IP 192.168.1.x + Phát hiện VPN (utun*)
        │     2. awaitWifiIpv4Ready(1200ms) -> Báo về MainViewModel.performGatewayProbeOnce()
        │
        ├─► UnsafeSslSession:
        │     1. allowsCellularAccess = false + Block HTTP Redirects
        │     2. Trust 192.168.1.1 Self-Signed TLS Certificate
        │     3. Track didSendBodyData (totalBytesSent >= expectedBytes)
        │
        ▼
[OntMobileDriver: Chuỗi 7 bước qua https://192.168.1.1]
  ├── Step 1: Lock ProbePauseMutex -> GET / (HTTP 200 + "iGate"/"GW040"/"VNPT")
  ├── Step 2: POST /cgi-bin/luci/login.cmd (Smart Retry per Record) & accesscontrolcfg.json
  ├── Step 3: POST /cgi-bin/luci/wancfg.json -> editwan(serviceId=1)/addwan(serviceId=0) -> sendwan.cmd
  ├── Step 4: POST /cgi-bin/luci/gponpasswd.json (SLID <= 10 ASCII chars)
  ├── Step 5: POST /cgi-bin/luci/savelanv4cfg.cmd (192.168.1.1)
  ├── Step 6: POST /cgi-bin/luci/wirelesscfg.json (Băng tần phụ) + settlingDelayMs (200ms)
  ├── Step 7: POST /cgi-bin/luci/wirelesscfg.json (Băng tần kết nối) + wlrestart.json
  │           └──► Radio Reload -> Verify Post-Flush Drop & ensureGatewayReadyBeforeStep7 -> SUCCESS!
  └── Cleanup (Task.detached): POST /cgi-bin/luci/logout.cmd (1.5s) + Unlock Mutex
```

## Related Code Files
- Create: `ios-app/ONTWiFiConfigurator/Core/Driver/OntExceptions.swift`
- Create: `ios-app/ONTWiFiConfigurator/Core/Driver/AsyncMutex.swift`
- Create: `ios-app/ONTWiFiConfigurator/Core/Driver/UnsafeSslSession.swift`
- Create: `ios-app/ONTWiFiConfigurator/Core/Wifi/IosWifiMonitor.swift`
- Create: `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`

## Implementation Steps
1. Xây dựng `OntExceptions.swift` và `AsyncMutex.swift` (hỗ trợ cả `lock()` bất đồng bộ và `tryLock()` tức thì không chặn).
2. Xây dựng `UnsafeSslSession.swift` bọc `URLSession` với `allowsCellularAccess = false`, chặn HTTP Redirect, hỗ trợ `protocolClasses`, xác thực chứng chỉ tự ký cho `192.168.1.1`, và ghi nhận `didSendBodyData` (`totalBytesSent >= expectedBytes`).
3. Xây dựng `IosWifiMonitor.swift` đọc IPv4 trên `"en0"` và kiểm tra VPN `"utun*"` qua `getifaddrs()`, chờ DHCP `awaitWifiIpv4Ready`, theo dõi mạng qua `NWPathMonitor` và mở nhanh Cài đặt Wi-Fi hệ thống.
4. Xây dựng `OntMobileDriver.swift` cài đặt đầy đủ chuỗi 7 bước LuCI CGI, `ensureGatewayReadyBeforeStep7`, xử lý `Commit Ambiguity` khi radio khởi động lại ở Bước 7 và đảm bảo luôn gọi `logout.cmd` qua `Task.detached` khi kết thúc hoặc gặp lỗi.

## Todo
- [x] Cài đặt `OntExceptions.swift`, `AsyncMutex.swift` và `UnsafeSslSession.swift`
- [x] Cài đặt `IosWifiMonitor.swift` (`getifaddrs("en0")`, `hasActiveVpnInterface`, `awaitWifiIpv4Ready`)
- [x] Cài đặt `OntMobileDriver.swift` (7 bước LuCI CGI + `ensureGatewayReadyBeforeStep7` + Mandatory Logout)

## Success Criteria
- [x] Request tới `https://192.168.1.1` luôn đặt `allowsCellularAccess = false`, chặn Redirect và chấp nhận chứng chỉ tự ký của ONT.
- [x] Khi xảy ra lỗi ở bất kỳ Bước 2–6 nào, `POST /cgi-bin/luci/logout.cmd` luôn được gọi để giải phóng `sessionKey`.
- [x] Bước 6 cấu hình băng tần phụ; Bước 7 cấu hình băng tần đang kết nối ở cuối cùng, phân biệt chính xác lỗi timeout trước khi gửi với hiện tượng rớt socket sau khi truyền lệnh khởi động lại radio.

## Risk Assessment
- **Risk:** `URLError.timedOut` (`-1001`) trên iOS xảy ra cho cả lỗi không kết nối được TCP SYN lẫn lỗi timeout chờ phản hồi sau khi đã gửi POST body.
  - *Observable Signal:* Bước 7 báo thành công giả nếu mạng đứt trước khi gửi `wirelesscfg.json`.
  - *Mitigation:* Theo dõi `urlSession(_:task:didSendBodyData:totalBytesSent:totalBytesExpectedToSend:)` trong `UnsafeSslSession.swift`; chỉ coi `URLError.timedOut` ở Bước 7 là rớt sóng sau khi nạp nếu `totalBytesSent >= expectedBytes` (hoặc `wirelesscfg.json` đã trả `HTTP 200` trước khi gọi `wlrestart.json`).
