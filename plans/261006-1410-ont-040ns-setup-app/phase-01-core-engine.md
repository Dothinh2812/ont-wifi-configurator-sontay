---
phase: 1
title: "Xây dựng Core Automation Engine & Protocol Driver cho ONT 040NSv2"
status: completed
priority: P1
effort: "4h"
dependencies: []
---

# Phase 1: Xây dựng Core Automation Engine & Protocol Driver cho ONT 040NSv2

## Overview
Xây dựng module `ont_driver.py` và `engine.py` đóng gói toàn bộ giao thức HTTP/LuCI CGI của thiết bị ONT VNPT iGate GW040-NSv2 được trích xuất từ dữ liệu thực tế `ont-040NSv2-setup.jsonl`, cung cấp API tự động hóa quy trình cấu hình tuần tự 10 bước (bổ sung bước 10 dọn dẹp và đóng phiên `logout.cmd`), xử lý chống khóa thiết bị (lockout DoS), kiểm soát tính lũy thừa cấu hình WAN (idempotency), encoding ký tự đặc biệt (`\x7f`), khoảng nghỉ ổn định driver Wi-Fi và probe kết nối thiết bị.

## Requirements
- Functional:
  - Tự động kiểm tra trạng thái kết nối ONT (probe `https://192.168.1.1/` với SSL bypass `verify=False` và cảnh báo chứng chỉ tự ký).
  - Đăng nhập tài khoản quản trị:
    * Kiểm tra trường `firstlog` trong phản hồi của `/cgi-bin/luci/login.cmd`: nếu `firstlog == "1"` gán `sessionKey = "factoryID"`; nếu `firstlog == "0"` bóc tách `sessionKey` từ payload JSON.
    * Đọc và theo dõi tham số `numberLogin` và `timeoutLogin`: giới hạn tối đa 1 lần thử cho mỗi mật khẩu; nếu `numberLogin >= 2` lập tức dừng luồng và báo lỗi yêu cầu bấm nút Reset cứng để tránh bị ONT khóa phần cứng 180 giây (`timeoutLogin=180`).
    * Bắt và xử lý trạng thái `status == "user exist"` (tài khoản đang bận phiên khác): tự động gọi probe xác thực lại hoặc chờ phiên cũ giải phóng.
  - Xử lý đổi mật khẩu lần đầu: gọi API `/cgi-bin/luci/accesscontrolcfg.json` với đầy đủ tham số CGI chuẩn (`undefined&sessionKey=factoryID&username=admin&oldPass=&newPass={new_pass}&user_cur_log=admin`). Kiểm tra mật khẩu mới thỏa mãn quy tắc độ phức tạp (`strong_password`: tối thiểu 8 ký tự, có chữ hoa, chữ thường, số và ký tự đặc biệt).
  - Đăng nhập lại với mật khẩu mới qua `/cgi-bin/luci/login.cmd`, bóc tách `sessionKey` phiên làm việc chính thức.
  - Cấu hình WAN PPPoE an toàn & lũy thừa (Idempotent WAN Setup):
    * Gọi `/cgi-bin/luci/wancfg.json` kiểm tra danh sách WAN hiện có.
    * Nếu đã tồn tại interface VLAN 11 (`veip0.11`), gọi `/cgi-bin/luci/editwan.json` để cập nhật thay vì tạo mới.
    * Nếu chưa có, gọi `/cgi-bin/luci/addwan.json` và `/cgi-bin/luci/sendwan.cmd` với VLAN ID 11, MTU 1492, NAT=1, Firewall=1, IPv4 Only, PPPoE username & password.
  - Cấu hình GPON Password / SLID (Mã MEN):
    * Kiểm tra định dạng SLID: độ dài tối đa $\le 10$ ký tự ASCII hợp lệ (khớp `^[a-zA-Z0-9_\-\.]{1,10}$`).
    * Gọi `/cgi-bin/luci/gponpasswd.json` với `data={slid}`.
  - Xử lý mã hóa tham số form đặc thù ONT (`encode_query`):
    * Tái lập hàm `encode_query` của ONT firmware: thay thế ký tự `&` trong mọi trường mật khẩu/tham số bằng byte `\x7f` (`String.fromCharCode(0x7F)`) tránh bị cắt cụt chuỗi dữ liệu POST.
    * Kiểm tra và loại bỏ 11 ký tự shell metacharacter nguy hiểm (`&|;$><`\!'"`).
  - Cấu hình Wi-Fi 2.4 GHz & 5 GHz:
    * Cấu hình Wi-Fi 2.4 GHz: gọi `/cgi-bin/luci/wirelesscfg.json` (`wl_unit=0`, SSID, pass WPA2/WPA3 PSK AES) và kích hoạt `/cgi-bin/luci/wlrestart.json`.
    * Chèn khoảng nghỉ ổn định (settling pause) tối thiểu 2 giây để Broadcom wireless driver nạp xong trước khi cấu hình băng tần kế tiếp.
    * Cấu hình Wi-Fi 5 GHz: gọi `/cgi-bin/luci/wirelesscfg.json` (`wl_unit=1`, SSID, pass) và kích hoạt `/cgi-bin/luci/wlrestart.json`.
  - Xác nhận cấu hình LAN / IPv4: gọi `/cgi-bin/luci/savelanv4cfg.cmd` với các tham số mạng LAN (`br-lan`, `192.168.1.1`, mask `255.255.255.0`, DHCP start `192.168.1.2`, end `192.168.1.254`, lease 1440). Khóa mặc định `192.168.1.1`; nếu phát hiện `lanIpChanged == "true"` xử lý re-route hoặc cảnh báo mất kết nối gateway.
  - Bước 10 - Đăng xuất và dọn dẹp phiên (Logout & Session Cleanup): Luôn gửi `POST /cgi-bin/luci/logout.cmd` với payload `status=user want to logout&user=admin&sessionKey={session_key}` trong khối `finally` của mọi kịch bản (kể cả khi thành công, thất bại hoặc bị hủy) để tránh rò rỉ session gây lỗi `"user exist"`.
  - Bắn sự kiện / callback tiến độ từng bước (`step_callback(step_name, current_step, total_steps, status, detail)`).
- Non-functional:
  - Thời gian thực thi toàn bộ luồng cấu hình một thiết bị < 25 giây khi mạng ổn định.
  - Xử lý timeout linh hoạt (mặc định 5s cho request, retry giới hạn an toàn để không kích hoạt lockout phần cứng 3 phút).
  - Không làm crash ứng dụng khi ONT bị rút cáp đột ngột giữa chừng.

## Architecture
Kiến trúc luồng xử lý của Engine:
```
[ONT Physical Connection]
        │
        ▼ (Debounced Probe 192.168.1.1 - 3 consecutive OKs)
┌────────────────────────────────────────────────────────┐
│ ONTDriver (Session Management & Protocol Engine)       │
│ - requests.Session với custom Adapter & Timeout (5s)   │
│ - encode_query (& -> \x7f) & Metacharacter Validator   │
├────────────────────────────────────────────────────────┤
│ 1. Step Probe: Check HTTP 200 (Stable Handshake)       │
│ 2. Step Login Initial:                                 │
│    - Read numberLogin/timeoutLogin (Avoid Lockout DoS) │
│    - If firstlog==1 -> sessionKey="factoryID"          │
│    - If firstlog==0 -> sessionKey=resp["sessionKey"]   │
│ 3. Step Change Pass: accesscontrolcfg.json             │
│    (sessionKey=factoryID, oldPass="", user_cur_log)    │
│ 4. Step Re-login (admin/Passwd2@) -> Get Final Token   │
│ 5. Step WAN Setup: Idempotent (Check wancfg -> edit/add│
│ 6. Step GPON SLID: Validate len <= 10 -> gponpasswd    │
│ 7. Step WiFi 2.4G: wl_unit=0 -> wlrestart              │
│    (2s Settling Pause for Wireless Driver Reload)      │
│ 8. Step WiFi 5G: wl_unit=1 -> wlrestart                │
│ 9. Step LAN IPv4: savelanv4cfg (Default 192.168.1.1)   │
│ 10. Step Logout & Cleanup: logout.cmd in finally block │
└────────────────────────────────────────────────────────┘
        │
        ▼ (Callback)
[Event Emitter / Progress Handler]
```

## Related Code Files
- Create: `app/core/ont_driver.py`
- Create: `app/core/config_models.py`
- Create: `app/core/network_probe.py`
- Create: `tests/test_ont_driver_mock.py`

## Implementation Steps
1. Định nghĩa dataclass `DeviceConfig` và `GlobalConfig` trong `app/core/config_models.py` chứa toàn bộ trường tham số:
   - DeviceConfig: `so_may`, `slid`, `account_pppoe`, `pass_pppoe`, `ssid_24g`, `pass_24g`, `ssid_5g`, `pass_5g`.
   - GlobalConfig: `admin_user`, `admin_default_pass`, `admin_new_pass`, `vlan_id`, `mtu`, `lan_ip`, `lan_mask`, `dhcp_start`, `dhcp_end`, `dhcp_lease`, `br_name`.
2. Tạo module `app/core/network_probe.py` kiểm tra kết nối tới IP gateway (mặc định `192.168.1.1:443` hoặc `80`) với cơ chế debounce 3 lần liên tiếp thành công trước khi báo ONLINE.
3. Cài đặt lớp `ONTDriver` trong `app/core/ont_driver.py`:
   - Quản lý `requests.Session()`, cấu hình headers chuẩn từ log capture (`User-Agent`, `Referer`, Content-Type `application/x-www-form-urlencoded`).
   - Cài đặt hàm tiện ích `ont_encode_query(data_dict)`: thay thế `&` bằng byte `\x7f` theo đúng đặc tả `encode_query` của firmware.
   - <!-- Updated: Red Team Review - Lockout Protection & Session Contract -->
   - Hàm `login(user, password)`:
     * Gửi request `POST /cgi-bin/luci/login.cmd`.
     * Phân tích phản hồi JSON: nếu `numberLogin >= 2`, lập tức raise ngoại lệ `ONTAccountLockoutWarning("Cảnh báo: ONT đã đăng nhập sai " + numberLogin + " lần. Vui lòng dừng để tránh bị khóa 180s và thực hiện Reset cứng thiết bị.")`.
     * Nếu `status == "user exist"`, xử lý giải phóng session hoặc hướng dẫn người dùng đợi 60s.
     * Nếu `firstlog == "1"`, gán `self.session_key = "factoryID"`. Nếu `firstlog == "0"`, gán `self.session_key = resp["sessionKey"]`.
   - Hàm `login_and_change_password(default_pass, new_pass)`:
     * Bước 2a: Thử đăng nhập `admin` / `default_pass` (`admin/admin`). Nếu thành công và `firstlog == "1"`, gọi `accesscontrolcfg.json` với payload đầy đủ (`username=admin&oldPass=&newPass={new_pass}&user_cur_log=admin&sessionKey=factoryID`).
     * Bước 2b: Nếu bước 2a thất bại (mật khẩu mặc định sai), chỉ thử DUY NHẤT 1 lần với `new_pass` (`admin/Passwd2@`) phòng trường hợp ONT này đã được cấu hình trước đó. Không retry mù để tránh chạm ngưỡng khóa `numberLogin >= 3`.
     * Bước 2c: Nếu cả 2 đều thất bại, báo lỗi rõ ràng yêu cầu kỹ thuật viên bấm nút Reset cứng trên ONT.
   - Hàm `configure_wan_idempotent(vlan_id, ppp_user, ppp_pass, mtu)`:
     * Gọi `wancfg.json`, kiểm tra xem interface có `vlanMuxId == vlan_id` đã tồn tại chưa.
     * Nếu có, gọi `editwan.json` để cập nhật username/password; nếu chưa, gọi `addwan.json` và `sendwan.cmd`.
   - Hàm `set_gpon_password(slid_value)`:
     * Kiểm tra `len(slid_value) <= 10` và không chứa ký tự cấm `&|;$><`\!'"` trước khi gọi `gponpasswd.json`.
   - Hàm `configure_wifi(unit, ssid, password)`:
     * Cấu hình băng tần, gọi `wirelesscfg.json` và `wlrestart.json`.
     * Nếu là băng tần 2.4G (`unit=0`), thực hiện `time.sleep(2.0)` để driver Wi-Fi nạp xong trước khi cấu hình 5G (`unit=1`).
   - Hàm `configure_lan(...)`:
     * Thực thi `savelanv4cfg.cmd`. Kiểm tra kết quả phản hồi `lanIpChanged`.
   - Hàm `logout()`:
     * Gửi `POST /cgi-bin/luci/logout.cmd` với `status=user want to logout&user=admin&sessionKey={session_key}`.
   - Hàm tổng thể `execute_full_setup(device_cfg, global_cfg, progress_cb)` điều phối toàn bộ chuỗi 10 bước trong cấu trúc `try ... finally: self.logout()`.
4. Viết unit test giả lập HTTP Server LuCI trong `tests/test_ont_driver_mock.py` kiểm thử các kịch bản thành công, lockout DoS, firstlog=1, idempotency WAN, ký tự đặc biệt và ngoại lệ mạng.

## Success Criteria
- [x] Lớp `ONTDriver` vượt qua toàn bộ test kịch bản mô phỏng tương thích 100% với trace HTTP trong `ont-040NSv2-setup.jsonl`.
- [x] Bắt và chặn kịp thời nguy cơ khóa phần cứng 180s (`numberLogin`), không retry mù khi sai mật khẩu.
- [x] Tự động hóa an toàn 10 bước bao gồm đóng phiên bằng `logout.cmd`, không để lại session treo gây lỗi `user exist`.
- [x] Cấu hình WAN mang tính lũy thừa (idempotent), cho phép chạy RETRY trên thiết bị cấu hình dở dang mà không sinh lỗi xung đột interface.
- [x] Chuẩn hóa chuỗi ký tự mật khẩu qua `encode_query` (`\x7f`) và chặn 11 shell metacharacter.

## Risk Assessment
- **Risk:** ONT bị khóa 180s do nhập sai mật khẩu quá 3 lần từ trước đó ngoài hiện trường.
  - *Observable Signal:* Phản hồi `login.cmd` trả về `timeoutLogin > 0` và `numberLogin >= 3`.
  - *Mitigation:* Bắt ngay cảnh báo, hiển thị đồng hồ đếm ngược thời gian khóa hoặc hướng dẫn bấm nút Reset cứng trên thân ONT.
- **Risk:** Session cũ chưa giải phóng khi kỹ thuật viên cắm lại cáp kiểm tra (`user exist`).
  - *Observable Signal:* Phản hồi `login.cmd` trả về `status: "user exist"`.
  - *Mitigation:* Gọi `logout.cmd` trong khối `finally` ở mọi tình huống kết thúc, và cung cấp nút "Buộc giải phóng / Đăng nhập lại" trên UI.
- **Risk:** Rung lắc cáp mạng trong quá trình cấu hình Wi-Fi làm gián đoạn request.
  - *Observable Signal:* Timeout kết nối trong khoảng 2-3s khi driver Wi-Fi reload.
  - *Mitigation:* Bố trí khoảng dừng 2s sau `wlrestart.json` và xử lý retry an toàn với các bước không làm thay đổi trạng thái xác thực.
