---
title: "Research 01: Real-Device Failure Analysis — ONT Radio Readiness, Post-Beacon Stabilization & Spaced-Backoff Reconnect"
date: 2026-11-06
status: completed
---

# Research 01: Phân Tích Nguyên Nhân Lỗi Kết Nối Lại Wi-Fi Sau Lần 1 Trên Thiết Bị Thật & Thiết Kế Cơ Chế Chờ Sẵn Sàng + Retry Giãn Cách

## Summary

Qua đối chiếu kết quả kiểm thử thực tế trên điện thoại với mã nguồn vừa triển khai từ kế hoạch `261105-1821-mobile-dual-wifi-handover-firmware`, bước **kết nối lại Wi-Fi mới sau khi đổi tên ở Lần 1 (Bước 7)** thất bại do **4 điểm nghẽn kiến trúc và thời gian chờ** trên phần cứng Broadcom **VNPT iGate GW040-NSv2**.

## Findings (Root Causes with Exact `file:line` Citations)

### 1. Tìm thấy SSID trong kết quả quét KHÔNG đồng nghĩa ONT đã sẵn sàng (`OntWifiConnector.kt:436-509`, `MainViewModel.swift:685-690`)
- Trên Broadcom `wlmngr` của GW040-NSv2, sau khi `wirelesscfg.json` (`action=Apply`) + `wlrestart.json` kết thúc ở Bước 6:
  1. Driver radio `wl0`/`wl1` phát khung **802.11 Beacon** tên Wi-Fi mới (`SSID_B`) **trước khi** tiến trình xác thực WPA2/WPA3 (`nas` / `eapd`), cầu nối `br0` (STP/FDB forwarding), và máy chủ DHCP (`udhcpd`) khởi động xong.
  2. Tiến trình `acsd` (Auto Channel Selection / 5GHz DFS CAC) thường đổi kênh và **khởi động lại radio lần thứ 2** trong khoảng `3–8 giây` sau khi beacon đầu tiên xuất hiện.
- **Lỗi hiện tại trên Android (`OntWifiConnector.kt:436, 477-481, 586`):**
  - `initialSettleMs = 4_000L` (`:413`) quá ngắn; bộ lọc `sr.timestamp >= minTimestampMicros - 5_000_000L` (`:586`) còn chấp nhận cả kết quả quét cũ trước khi Bước 6 hoàn tất.
  - Ngay khoảnh khắc `isTargetSsidVisibleInScanResults` thấy beacon đầu tiên (`:478`), code `break` khỏi Pha A với **độ trễ ổn định `0 ms`** và gọi ngay `requestSpecifierNetworkOnce()` (`ConnectivityManager.requestNetwork`). Khi `nas` chưa sẵn sàng, bắt tay 4 bước EAPOL thất bại (`ASSOC_REJECT` / `4WAY_HANDSHAKE_TIMEOUT`) làm hộp thoại hệ thống Android báo lỗi `onUnavailable()`.
- **Lỗi hiện tại trên iOS (`MainViewModel.swift:683-690`):**
  - Chỉ ngủ cố định `3.5s` (`3_500_000_000 ns`) rồi gọi `tryJoinWifiIfEntitled` **đúng 1 lần duy nhất** (`if attemptIdx == 1`). Ở giây thứ `3.5`, sóng mới của ONT chưa kịp phát hoặc `nas` chưa chạy nên `NEHotspotConfigurationManager.shared.apply` luôn thất bại ở lần đầu và không bao giờ được gọi lại.

### 2. Cắt đứt DHCP IPv4 quá sớm (`5s`) và thiếu thời gian lắng trước khi gọi HTTP/TLS (`OntWifiConnector.kt:551, 780`, `IosWifiMonitor.swift:175`)
- Sau khi điện thoại bắt tay WPA2/WPA3 thành công (`onAvailable` trên Android / `apply` trên iOS), `awaitOntSubnetIpv4Ready` chỉ chờ tối đa **`5_000 ms`** (`OntWifiConnector.kt:551, 780`; `IosWifiMonitor.swift:175`), còn `connectToOntWifi` chỉ chờ **`1_200 ms`** (`OntWifiConnector.kt:311, 393`).
- Thực tế sau `wlrestart`, `udhcpd` trên `br0` thường mất **`6–12 giây`** mới cấp xong địa chỉ `192.168.1.x`. Việc cắt ở `5s` rồi gọi `unbindOntWifi()` (`OntWifiConnector.kt:559`) đã tự hủy kết nối Wi-Fi hợp lệ ngay khi điện thoại đang xin IP!

### 3. Thiếu vòng lặp Retry tổng thể ở Bước 7 trên Android & Thiếu giãn cách lũy tiến (`Backoff`) trên cả 2 nền tảng
- **Lỗi nghiêm trọng trong `OntMobileDriver.kt:1660-1727` (Android):**
  - Khác với iOS (`OntMobileDriver.swift:1630-1726`), Android gọi `switchHandler.invoke(...)` **đúng 1 lần ngoài vòng lặp** (`:1661`).
  - Nếu điện thoại vừa kết nối vào `SSID_B` rồi radio ONT nháy lần 2 (`onLost` tại `OntWifiConnector.kt:679` xóa `boundWifiNetwork = null`), `OntMobileDriver.kt:1688-1703` tiếp tục gọi `probeGateway()` trên `handoverCtx.socketFactory` **đã chết** suốt 45 giây rồi văng lỗi dừng hẳn Bước 7 mà không hề gọi lại `onSwitchToSecondaryWifi`!
- **Thiếu Retry giãn cách (`Spaced Backoff`):**
  - Trong `OntWifiConnector.kt:560`, khoảng nghỉ giữa các lần `requestSpecifierNetworkOnce` cố định cứng **`2_500 ms` (`2.5s`)**, còn mỗi lượt giữ dialog tới `45_000 ms` (`:531`) trên tổng quỹ `120_000 ms` (`GlobalSettings.kt:96`) — vừa gây xung đột hộp thoại hệ thống Android khi gọi dồn dập `2.5s`, vừa chỉ kịp thử ~2 lượt là hết giờ.
  - Trong `OntMobileDriver.swift:1701, 1719`, khoảng nghỉ giữa các lần thử lại Bước 7 cố định `recoveryRetryDelayMs = 1_200 ms` (`1.2s`), chưa có giãn cách tăng dần.
  - Trong `OntWifiConnector.connectToOntWifi` (`:264-395`, dùng cho cả nút `Bắt Wi-Fi ONT` và `rebindOntWifiSocketFactory` trước Bước 9 Firmware), `timeoutMs = 15_000L` và **không có retry**.

### 4. Lỗi kết luận nhầm "bắt nhầm ONT" hoặc sai băng tần khi CGI/bảng trạm chưa kịp cập nhật (`OntMobileDriver.kt:1070-1167`, `OntMobileDriver.swift:1083-1174`)
- Ngay sau khi đăng nhập Lần 2, LuCI CGI đang tải cao nên `deviceinfo2.json` hoặc `dhcpclient.json` có thể trả về `null`/rỗng trong vài giây đầu, hoặc bảng `wlAssociatedDevice` chưa kịp ghi nhận MAC/IP mới trên `wl1`/`wl0`.
- Hiện tại:
  - Nếu `devJson == null` làm `actualBaseMac == null/""`, cả Android (`:1079-1082`) và iOS (`:1092-1095`) lập tức ném **`OntDriverException` vĩnh viễn** ("nghi ngờ bắt nhầm ONT khác") — trên iOS ngoại lệ này không lọt vào `catch StillOnPrimaryBandError` hay `catch OntNetworkError` nên làm sập Bước 7 ngay tức khắc!
  - Trên Android (`:1151-1166`), nếu `wlAssociatedDevice` chưa kịp cập nhật (`matchedAssocIfc.isEmpty()`) hoặc còn lưu dòng cũ vài giây, hàm ném `OntDriverException` không có bất kỳ bước poll hội tụ hay retry kết nối lại nào.

## Recommendations (Architecture)

1. **Tăng quỹ thời gian chờ phần cứng (`GlobalSettings` & Connector):**
   - `wifiHandoverTimeoutMs`: nâng từ `120_000L` $\rightarrow$ **`180_000L` (180s)** trên Android; từ `180_000` $\rightarrow$ **`240_000` (240s)** trên iOS.
   - `gatewayPostHandoverTimeoutMs`: nâng từ `45_000` $\rightarrow$ **`60_000` (60s)**.
   - `postSecondWifiSettleTimeoutMs`: nâng từ `60_000` $\rightarrow$ **`90_000` (90s)**.
   - `initialSettleMs` sau Bước 6: nâng từ `4s`/`3.5s` $\rightarrow$ **`8_000 ms` (8s)**.
   - **Cửa sổ ổn định sau khi thấy sóng (`postBeaconReadyDelayMs = 8_000 ms`):** Khi quét thấy `targetSsid` mới (`sr.timestamp >= handoverStartMicros`), bắt buộc chờ thêm `8s` (kiểm tra lại beacon không bị biến mất do `acsd` nháy sóng lần 2) rồi mới phát lệnh ghép nối đầu tiên; khi tắt GPS chờ tổng `14s` trước Pha B.
   - `awaitOntSubnetIpv4Ready`: nâng từ `5_000 ms` $\rightarrow$ **`15_000 ms` (15s)** kèm kiểm tra ổn định liên kết `1_500 ms` (không rớt `onLost` tức thì); nâng `connectToOntWifi` lên `timeoutMs = 30_000L` và `awaitWifiIpv4Ready = 8_000L`.
   - `postDhcpGatewaySettleMs = 2_500 ms` sau khi có IP `192.168.1.x` trước khi mở kết nối TLS tới `192.168.1.1`.
2. **Cơ chế Retry Giãn Cách Lũy Tiến (`Spaced Backoff`):**
   - Lịch giãn cách xác định (tùy biến được qua tham số constructor để Unit Test chạy `< 15s`, tuyệt đối không rẽ nhánh theo `settlingDelayMs`):
     $$\text{backoff}(k) = \min(\text{maxBackoffMs},\; \text{initialBackoffMs} \times k) \implies 5\text{s} \rightarrow 10\text{s} \rightarrow 15\text{s} \rightarrow 20\text{s}$$
   - Áp dụng ở **2 tầng**:
     - **Tầng OS Wi-Fi (`OntWifiConnector` & `IosWifiMonitor`/`MainViewModel.swift`):** Giới hạn mỗi lượt `requestSpecifierNetworkOnce` ở `30_000 ms` để đủ chỗ cho `4–6` lượt thử trong `180s`; giữa các lượt hủy sạch `NetworkCallback` cũ, đếm ngược giãn cách `5s/10s/15s/20s` (vẫn kiểm tra `findAlreadyConnectedTargetNetwork` mỗi `500 ms` để bắt kịp nếu OS tự nối ngầm) và quét làm mới cache kênh nếu đã qua `>= 15s` và còn quota `4 / 120s`. Trên iOS, phân biệt lỗi thiếu entitlement (`Sideloadly` $\rightarrow$ không gọi lặp `apply`) với lỗi mạng tạm thời (`TrollStore/Dev cert` $\rightarrow$ retry `apply` giãn cách `6s -> 12s -> 18s`).
     - **Tầng Driver Bước 7 (`OntMobileDriver.kt` & `OntMobileDriver.swift`):** Bọc trọn chu trình `(onSwitchToSecondaryWifi -> postDhcpGatewaySettle -> probeGateway -> authenticateSecondConnection -> verifySecondConnectionBandAndMac)` trong vòng lặp tổng thể theo `wifiHandoverTimeoutMs`. Nếu socket rớt giữa chừng (`onLost` / `OntNetworkError` không phải lệch TLS pin) hoặc bảng trạm chưa chuyển băng tần, tự động `logout()` + `onUnbindNetwork()` + nghỉ giãn cách `5s -> 10s -> 15s -> 20s` rồi thực hiện ghép nối lại từ đầu.
3. **Hội tụ xác thực `verifySecondConnectionBandAndMac`:**
   - Chỉ ném lỗi nghiêm trọng dừng ngay khi `actualBaseMac` **khác rỗng** và **khác `expectedOntBaseMac`** (hoặc sai mật khẩu `adminNewPass` / lệch chứng chỉ TLS).
   - Khi `deviceinfo2.json` / `dhcpclient.json` tạm thời `null` hoặc `wlAssociatedDevice` đang cập nhật, lặp kiểm tra mỗi `bandVerifyPollIntervalMs` (`2_000 ms`) trong tối đa `bandVerifyPollTimeoutMs` (`15_000 ms`) trước khi kích hoạt retry kết nối lại.

## Unresolved Questions
None.
