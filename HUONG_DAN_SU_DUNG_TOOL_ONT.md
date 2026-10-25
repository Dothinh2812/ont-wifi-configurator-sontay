# HƯỚNG DẪN VẬN HÀNH ỨNG DỤNG ĐỔ CẤU HÌNH TỰ ĐỘNG ONT VNPT iGATE GW040-NSv2

---

## 1. Giới Thiệu & Mục Đích

Ứng dụng **VNPT ONT GW040-NSv2 Configurator** là phần mềm Desktop đồ họa (PyQt6) độc lập, giúp kỹ thuật viên tự động hóa toàn bộ quy trình cài đặt và cấu hình thiết bị ONT iGate GW040-NSv2 theo phương thức **1-by-1** (lần lượt từng thiết bị) từ file dữ liệu mẫu Excel (`file-mau.xlsx`).

### 10 Bước Cấu Hình Tự Động Hóa Chuẩn:
1. **Probe Gateway:** Kiểm tra kết nối ổn định tới `https://192.168.1.1/` (yêu cầu 3 lần HTTP 200 liên tiếp với bộ lọc debounce 3s).
2. **Đăng nhập mặc định:** Đăng nhập `admin` / `admin`, kiểm tra `firstlog == "1"` và kiểm soát `numberLogin` chống khóa phần cứng.
3. **Đổi mật khẩu quản trị:** Đổi mật khẩu sang mật khẩu mới (mặc định `Passwd2@`, hỗ trợ tùy chỉnh trên giao diện) kèm kiểm tra độ phức tạp.
4. **Đăng nhập lại:** Xác thực bằng mật khẩu mới để lấy `sessionKey` phiên làm việc chính thức.
5. **Cài đặt WAN PPPoE Lũy Thừa (Idempotent):** Kiểm tra `wancfg.json`, nếu đã có VLAN 11 gọi `editwan.json`, nếu chưa có gọi `addwan.json` và `sendwan.cmd` (VLAN 11, MTU 1492, NAT=1, Firewall=1).
6. **Cài đặt GPON Password (SLID):** Tự động nạp `Mã MEN` (hoặc `SO_MAY`), kiểm tra độ dài $\le 10$ ký tự ASCII và lọc ký tự đặc biệt nguy hiểm.
7. **Cấu hình Wi-Fi 2.4 GHz:** Nạp SSID và Mật khẩu từ Excel, bảo mật WPA2/WPA3 PSK AES, restart driver Wi-Fi và dừng nghỉ 2 giây cho driver Broadcom ổn định.
8. **Cấu hình Wi-Fi 5 GHz (Kế thừa thông minh):** Tự động tạo tên Wi-Fi 5G bằng `{SSID_2.4G} 5G` (độ dài $\le 32$ ký tự) và mật khẩu lấy giống hệt Wi-Fi 2.4G (fallback `VNPT 5G` và `1234567890`).
9. **Xác nhận LAN IPv4:** Cấu hình mạng LAN `192.168.1.1`, mask `255.255.255.0`, DHCP range `192.168.1.2` - `192.168.1.254` (mặc định khóa chỉnh sửa để bảo vệ gateway).
10. **Đăng xuất & Dọn dẹp phiên (Logout):** Luôn gửi lệnh `POST /cgi-bin/luci/logout.cmd` trong khối `finally` để giải phóng phiên, triệt tiêu lỗi `"user exist"`.

---

## 2. Chuẩn Bị Thiết Bị & Kết Nối Vật Lý

### Sơ Đồ Kết Nối:
```
┌─────────────────┐               Dây Cáp Mạng LAN (RJ45)              ┌───────────────────────────┐
│ Máy tính Laptop │ ◄────────────────────────────────────────────────► │ ONT iGate GW040-NSv2      │
│ (Cổng Ethernet) │                                                    │ (Cổng LAN1 hoặc LAN2)     │
└─────────────────┘                                                    └───────────────────────────┘
```

### Cấu Hình Card Mạng Máy Tính:
1. Đặt card mạng Ethernet ở chế độ **Nhận IP tự động (DHCP)** để nhận địa chỉ từ ONT (ví dụ `192.168.1.204`), HOẶC đặt IP tĩnh:
   - IP address: `192.168.1.100`
   - Subnet mask: `255.255.255.0`
   - Default gateway: `192.168.1.1`
2. **CẢNH BÁO XUNG ĐỘT MẠNG (QUAN TRỌNG):**
   - Nếu máy tính của kỹ thuật viên đang kết nối vào mạng Wi-Fi văn phòng mà mạng Wi-Fi đó cũng cấp dải IP `192.168.1.x`, Windows sẽ bị xung đột định tuyến 2 card mạng (multi-homed routing conflict).
   - **Khuyến nghị:** Tạm thời tắt hoặc ngắt kết nối Wi-Fi trên máy tính trong suốt quá trình cắm dây đổ cấu hình cho ONT.

---

## 3. Chuẩn Bị & Kiểm Tra Dữ Liệu File Excel

### Cấu Trúc File Mẫu:
- File mẫu mặc định: `file-mau.xlsx` (gồm 89 dòng dữ liệu thuê bao).
- Giữ nguyên định dạng chuỗi số máy (ví dụ `001560828`), không để mất các chữ số `0` ở đầu.

### Xử Lý Dòng Thiếu Mật Khẩu PPPoE (13 Dòng Lỗi):
- Trong dữ liệu thực tế, có 13 dòng thiếu thông tin `Mật khẩu PPPoE`.
- **Chính sách an toàn tuyệt đối:** Ứng dụng **chặn hoàn toàn** không cho bấm RUN đối với các dòng thiếu mật khẩu PPPoE và đánh dấu màu vàng cam `LỖI_DỮ_LIỆU`. TUYỆT ĐỐI KHÔNG tự động gán mật khẩu giả lập để tránh làm rớt mạng hoặc lỗi xác thực thuê bao trên BRAS.
- **Cách khắc phục:**
  1. Kỹ thuật viên nhấp đúp chuột (Double Click) vào ô `Mật khẩu PPPoE` tương ứng của dòng đó trên bảng.
  2. Gõ mật khẩu PPPoE chính xác được cấp từ hệ thống điều hành OneBSS.
  3. Nhấn `Enter`. Ô dữ liệu sẽ được lưu, hàng tự động kiểm tra lại, chuyển sang trạng thái `CHỜ_CẤU_HÌNH` và nút `RUN` sáng lên cho phép thực thi.

---

## 4. Quy Trình Thao Tác 1-by-1 (Dành Cho Kỹ Thuật Viên)

### Bước 1: Khởi động Ứng dụng & Tạo Phím Tắt
1. **Tải và Giải nén file ứng dụng:**
   - Tải file nén `ONT_040NS_Tool.zip` về máy tính.
   - **QUAN TRỌNG - BẮT BUỘC GIẢI NÉN:** Nhấp chuột phải vào file `ONT_040NS_Tool.zip` chọn **`Extract All...` (Giải nén tất cả)** sang một thư mục cụ thể (ví dụ `C:\VNPT_Tools\ONT_040NS_Tool`).
   - *Lưu ý:* Tuyệt đối không nhấp đúp chạy trực tiếp từ bên trong trình duyệt zip của Windows để tránh lỗi thiếu file mẫu `file-mau.xlsx`.
2. **Tạo Shortcut ra Desktop (Khuyến nghị):**
   - Bên trong thư mục vừa giải nén, nhấp đúp file **`create_desktop_shortcut.bat`**.
   - Script sẽ tự động tạo biểu tượng **`ONT GW040-NSv2 Configurator`** ngay trên màn hình Desktop của bạn với đầy đủ thư mục làm việc chuẩn xác.
3. **Khởi chạy ứng dụng:**
   - Nhấp đúp vào Shortcut trên Desktop hoặc chạy file `ONT_040NS_Tool.exe`.
   - Ứng dụng khởi động tức thì ($\le 1.5\text{ giây}$) nhờ định dạng `--onedir` được tối ưu hóa.
   - File mẫu `file-mau.xlsx` sẽ tự động được nạp. Nếu muốn nạp file Excel khác, bấm nút **📁 Nạp File Excel** trên thanh công cụ.

> **Cơ Chế Đa Luồng Độc Lập (ProbeWorker 60 FPS):**
> Ứng dụng vận hành việc thăm dò mạng ONT bằng luồng ngầm `ProbeWorker (QThread)` riêng biệt và bộ nhớ đệm màu `STATUS_BRUSHES`. Giao diện luôn duy trì độ mượt mà 60 FPS (độ trễ thao tác $\le 16\text{ms}$), không bao giờ bị đơ, giật lag ngay cả khi rút cáp mạng đột ngột hoặc ONT chưa cắm nguồn.
### Bước 2: Cắm Thiết Bị & Chờ Đèn ONLINE
- Cắm nguồn cho ONT, cắm cáp mạng LAN từ cổng Ethernet PC vào cổng LAN1/LAN2 của ONT.
- Chờ khoảng 15 - 25 giây cho ONT boot xong.
- Quan sát đèn báo trạng thái trên thanh công cụ:
  - Khi đèn báo chuyển sang màu xanh: **`● ONLINE (Đã giữ ổn định 3s)`**, ONT đã sẵn sàng.

### Bước 3: Ấn RUN - Đổ Cấu Hình
- Bấm nút to màu xanh **`▶ RUN - ĐỔ CẤU HÌNH`** hoặc nhấn phím tắt **`Space`** hoặc **`Enter`**.
- Ứng dụng sẽ tự động tạm dừng luồng quét mạng (để không làm quá tải chip xử lý của ONT) và tiến hành nạp 10 bước.
- Tiến độ và thông tin log được hiển thị trực tiếp theo thời gian thực (các mật khẩu nhạy cảm đều được tự động che mờ `***` trên màn hình).

### Bước 4: Hoàn Tất & Thay Thiết Bị Kế Tiếp
- Khi nạp xong:
  - Loa máy tính sẽ phát âm thanh báo chuông (Beep).
  - Hộp thoại thông báo xuất hiện:
    > *"ĐÃ HOÀN TẤT CẤU HÌNH THIẾT BỊ [SO_MAY]! VUI LÒNG RÚT CÁP VÀ CẮM THIẾT BỊ TIẾP THEO."*
  - Kết quả được ghi nhận tức thì vào file checkpoint.
- Kỹ thuật viên:
  1. Rút cáp mạng khỏi ONT vừa làm xong.
  2. Cắm cáp mạng vào ONT tiếp theo.
  3. Bấm `OK` trên hộp thoại (hoặc nhấn phím `Enter`). Con trỏ trên bảng sẽ tự động nhảy đến thiết bị tiếp theo trong danh sách.

---

## 5. Hướng Dẫn Xử Lý Sự Cố Thường Gặp

| Sự cố | Nguyên nhân | Cách xử lý |
|-------|-------------|------------|
| Đèn báo mãi ở trạng thái `○ OFFLINE` | Cáp mạng chưa cắm chặt, ONT chưa khởi động xong, hoặc IP card mạng máy tính bị sai dải | Kiểm tra đèn LAN trên ONT xem có sáng xanh không. Đặt lại card mạng PC về DHCP hoặc IP tĩnh `192.168.1.100`. Tắt Wi-Fi máy tính nếu đang trùng dải `192.168.1.x`. |
| Cảnh báo khóa thiết bị 180s (`timeoutLogin=180`) | Thiết bị trước đó đã bị ai đó nhập sai mật khẩu admin quá 3 lần | Bấm và giữ nút **Reset cứng** (nút nhỏ thụt vào sau thân máy ONT) bằng tăm trong 10 giây để ONT khởi động lại về mặc định xuất xưởng. |
| Báo lỗi `user exist` | Phiên làm việc trước đó chưa được đăng xuất do rút cáp đột ngột | Đợi 60 giây để ONT tự động timeout phiên cũ, hoặc bấm khởi động lại ONT. Ứng dụng luôn tự động gọi `logout.cmd` trong mọi kịch bản. |
| Không ấn được nút `RUN` | Thiết bị đang OFFLINE hoặc dòng dữ liệu bị thiếu pass PPPoE | Kiểm tra đèn báo ONLINE và kiểm tra cột `Mật khẩu PPPoE`. Nếu thiếu, nhập trực tiếp trên bảng. |
| Báo lỗi `PermissionError` khi xuất Excel | File Excel báo cáo đang được mở trong phần mềm Microsoft Excel | Đóng file Excel trên máy tính lại, sau đó bấm nút "Xuất Báo Cáo Kết Quả" một lần nữa. |

---

## 6. Hướng Dẫn Đóng Gói File Chạy `.exe` Cho Windows

Để đóng gói ứng dụng thành file chạy không cần cài đặt Python:
1. Mở cửa sổ Command Prompt (`cmd`) hoặc PowerShell tại thư mục dự án.
2. Cài đặt các thư viện phụ thuộc:
   ```bash
   pip install PyQt6 openpyxl requests urllib3 pyinstaller
   ```
3. Chạy lệnh đóng gói:
   - Dạng thư mục độc lập `--onedir` (mặc định, khởi động cực nhanh $\le 1.5\text{s}$, tự động nén zip):
     ```cmd
     build.bat
     ```
     hoặc:
     ```bash
     python build_windows.py
     ```
     *Sau khi chạy xong, kịch bản sẽ tự động đóng gói kèm file `create_desktop_shortcut.bat` và nén thành `dist/ONT_040NS_Tool.zip` sẵn sàng bàn giao.*
   - Dạng 1 file `.exe` duy nhất (tùy chọn):
     ```bash
     python build_windows.py --onefile
     ```
4. **Bàn giao cho kỹ thuật viên:**
   - Chỉ cần gửi file `dist/ONT_040NS_Tool.zip` (dung lượng đã tối ưu ~45-55MB sau khi exclude các module Qt6 thừa).
   - Kỹ thuật viên giải nén `ONT_040NS_Tool.zip`, chạy `create_desktop_shortcut.bat` là có thể sử dụng ngay mà không bị Windows Defender quét lặp lại.

---

## 6B. Hướng Dẫn Sử Dụng Bản Di Động Android Native (Cấu Hình Không Dây Qua Wi-Fi)

Dành cho kỹ thuật viên đi lắp đặt/đổi ONT cơ động tại nhà thuê bao chỉ với điện thoại Android:

### Quy Trình 4 Bước Trên Điện Thoại Android:
1. **Nạp danh sách Excel & Chọn đúng `ma_tb`:**
   - Mở ứng dụng **Cấu Hình ONT VNPT Qua Wi-Fi** trên điện thoại Android.
   - Bấm **"Nạp Excel (.xlsx)"** để chọn file danh sách giao việc trên máy (hoặc bấm **"Nạp Mẫu Demo"** để trải nghiệm 3 bản ghi mẫu ẩn danh).
   - Gõ vài ký tự của `ma_tb` (`Account`), `SO_MAY`, `TEN_TB`, hoặc `SO_DT` vào ô tìm kiếm và chạm vào thẻ thuê bao cần cấu hình.
2. **Bắt Wi-Fi Mặc Định Của ONT (`GW040-NSv2_2.4G_XXXXXX`):**
   - Cắm nguồn cho ONT GW040-NSv2 và chờ đèn Wi-Fi sáng ổn định.
   - Bấm nút **"Bắt Wi-Fi ONT"** trên thanh trạng thái: ứng dụng sẽ tự động lọc đúng sóng mặc định `GW040-NSv2_2.4G_XXXXXX` (hoặc `5G_XXXXXX`) và khóa luồng dữ liệu nội bộ vào `192.168.1.1` mà **không cần tắt mạng 4G/5G** trên điện thoại.
3. **Kiểm Tra / Chỉnh Sửa Tham Số Trước Khi RUN:**
   - Trên màn hình **Kiểm Tra & Sửa Cấu Hình Trước Khi RUN**, đối chiếu:
     - `SLID / Mã MEN` ($\le 10$ ký tự ASCII).
     - `Account PPPoE (ma_tb)` và `Mật khẩu PPPoE` (nếu file Excel bị khuyết mật khẩu PPPoE, ô này sẽ viền đỏ để KTV nhập bổ sung).
     - `Tên Wi-Fi 2.4G` & `Pass Wi-Fi 2.4G` (tự động đồng bộ sang `Wi-Fi 5G`; nếu mật khẩu trong Excel chỉ có 7 chữ số do mất số `0` đầu ngày sinh, bấm nút 1 chạm **"Thêm số 0 ở đầu"** để đủ 8 ký tự chuẩn WPA2).
4. **Bấm `RUN - ĐỔ CẤU HÌNH ONT` (7 Bước Tự Động):**
   - Ứng dụng tự động giữ sáng màn hình, tạm dừng luồng thăm dò nền và thực thi 7 bước:
     - **Bước 1/7:** Kiểm tra kết nối & xác thực chữ ký ONT (`192.168.1.1`).
     - **Bước 2/7:** Đăng nhập thông minh & đổi mật khẩu Admin (`Passwd2@`).
     - **Bước 3/7:** Cấu hình WAN PPPoE VLAN 11 (`serviceId=1` khi sửa).
     - **Bước 4/7:** Nạp GPON Password (`SLID / Mã MEN`).
     - **Bước 5/7:** Lưu cấu hình LAN IPv4 (`192.168.1.1`).
     - **Bước 6/7:** Cấu hình Wi-Fi băng tần phụ (`5GHz`) + nghỉ `2.0s` ổn định driver.
     - **Bước 7/7 (Bước cuối):** Đổi tên & mật khẩu Wi-Fi băng tần đang kết nối (`2.4GHz`) ở bước cuối cùng, tự động xử lý khi sóng mặc định tắt để khởi động lại radio mới.
   - Ngay khi hoàn tất Bước 7, máy phát âm báo + rung, tự động khôi phục mạng 4G/5G để KTV bấm **"Xuất & Chia Sẻ Báo Cáo (.xlsx) Qua Zalo"** (giữ nguyên đủ 30 cột gốc + cập nhật các trường đã sửa + 3 cột kết quả) hoặc bấm **"Kết nối vào Wi-Fi mới để kiểm tra mạng"**.


---

## 6C. Hướng Dẫn Cài Đặt File `.ipa` & Vận Hành Trên Điện Thoại iPhone (iOS Native)

Dành cho kỹ thuật viên sử dụng iPhone (iOS 16.0 trở lên):

### A. Cách Cài Đặt File `ONT_WiFi_Configurator_Unsigned.ipa` Lên iPhone:
1. **Cách 1 — Qua Sideloadly trên máy tính Windows/macOS (Apple ID miễn phí):**
   - Tải file `ONT_WiFi_Configurator_Unsigned.ipa` từ mục **Actions -> Build iOS Unsigned IPA** trên GitHub (hoặc nhận từ quản trị viên).
   - Cắm cáp iPhone vào máy tính, mở phần mềm **Sideloadly**, chọn file `ONT_WiFi_Configurator_Unsigned.ipa`, nhập tài khoản Apple ID cá nhân và bấm **Start**.
   - Sau khi cài xong, trên iPhone vào **Cài đặt (Settings) -> Cài đặt chung (General) -> Quản lý VPN & Thiết bị (VPN & Device Management)**, chọn email Apple ID của bạn và bấm **Tin cậy (Trust)**.
   - Trên iOS 16+, bật **Chế độ nhà phát triển (Developer Mode)** trong *Cài đặt -> Quyền riêng tư & Bảo mật -> Chế độ nhà phát triển* nếu hệ thống yêu cầu.
2. **Cách 2 — Qua AltStore hoặc TrollStore:**
   - Gửi file `ONT_WiFi_Configurator_Unsigned.ipa` qua Zalo / AirDrop vào iPhone, chọn **Chia sẻ -> Mở bằng AltStore / TrollStore** để cài đặt trực tiếp.

### B. Quy Trình 4 Bước Cấu Hình ONT Trên iPhone:
1. **Nạp File Excel Giao Việc (`.xlsx`):**
   - Mở ứng dụng **ONT WiFi Config**, bấm **"Nạp Excel (.xlsx)"** để chọn file từ ứng dụng **Tệp (Files)** hoặc file tải về từ **Zalo** (hoặc bấm **"Nạp Mẫu Demo"**).
   - Tìm nhanh thuê bao theo `Account`, `SO_MAY`, `TEN_TB`, hoặc `SO_DT` (có hoặc không có số `0` đầu).
2. **Kết Nối Sóng Wi-Fi Mặc Định Của ONT (`GW040-NSv2_2.4G_XXXXXX`):**
   - Quét mã QR Wi-Fi dưới đáy modem bằng Camera iPhone hoặc bấm **"Bắt Wi-Fi ONT" -> "Mở Cài Đặt Wi-Fi iOS"** để chọn sóng mặc định của ONT.
   - Quay lại ứng dụng: thanh trạng thái sẽ nhận diện IP `192.168.1.x` trên card Wi-Fi `en0` và báo **`ONLINE - Sẵn sàng đổ cấu hình (192.168.1.1)`** (không cần tắt 4G/5G; nếu đang bật VPN 1.1.1.1/WireGuard, ứng dụng sẽ nhắc tắt VPN).
3. **Kiểm Tra Tham Số & Chọn Băng Tần Đang Bắt (`2.4GHz` / `5GHz`):**
   - Kiểm tra `SLID / Mã MEN` ($\le 10$ ký tự), nhập bổ sung `Mật khẩu PPPoE` nếu file Excel bị khuyết, hoặc bấm nút 1 chạm **"Thêm số 0 ở đầu"** nếu mật khẩu Wi-Fi chỉ có 7 số.
   - Chọn đúng băng tần Wi-Fi ONT mà iPhone đang kết nối (**`Đang bắt 2.4GHz`** hoặc **`Đang bắt 5GHz`**) ngay phía trên nút **RUN**.
4. **Bấm `RUN - ĐỔ CẤU HÌNH ONT (7 BƯỚC)` & Chia Sẻ Báo Cáo Qua Zalo:**
   - Ứng dụng giữ sáng màn hình, xin quyền chạy nền ngắn hạn (`beginBackgroundTask`), cấu hình băng tần phụ ở Bước 6 và **đổi tên/mật khẩu băng tần đang kết nối ở Bước 7 cuối cùng**.
   - Sau khi hoàn tất 7/7 bước (có âm báo + rung Haptic), bấm **"Sao chép mật khẩu Wi-Fi mới"** (tự động xóa khỏi bộ nhớ tạm sau 120s bảo mật) hoặc bấm **"Xuất & Chia Sẻ Báo Cáo Excel (.xlsx)"** để gửi kết quả qua Zalo.
---

## 7. Thông Tin Đơn Vị Phát Triển & Hỗ Trợ Kỹ Thuật

- **Đơn vị phát triển:** Trung tâm viễn thông Sơn Tây - VNPT Hà Nội
- **Tác giả:** Đỗ Xuân Thịnh
- **Hotline / Zalo hỗ trợ kỹ thuật:** `0946827186`
- **Tính năng sao chép nhanh:** Kỹ thuật viên có thể nhấp chuột trực tiếp vào thanh Footer ở đáy ứng dụng để sao chép nhanh số điện thoại hỗ trợ kỹ thuật vào Clipboard.
