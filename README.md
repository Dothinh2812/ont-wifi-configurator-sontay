# VNPT ONT GW040-NSv2 Configurator Suite

Bộ công cụ đa nền tảng (**Desktop Windows/macOS/Linux**, **Android Native**, và **iOS Native**) tự động hóa toàn diện quy trình nạp cấu hình và nâng cấp firmware cho dòng router ONT **VNPT iGate GW040-NSv2** từ danh sách dữ liệu thuê bao Excel (`.xlsx`).

Dự án cung cấp 2 phương thức vận hành tối ưu cho từng bối cảnh công tác:
1. **Desktop App (Cáp mạng LAN RJ45):** Tối ưu cho quy trình nạp cấu hình hàng loạt lần lượt từng thiết bị (**1-by-1**) tại phòng kỹ thuật / trạm viễn thông với tốc độ xử lý cao, giao diện bảng 60 FPS, âm chuông báo hoàn tất và tự động chuyển hàng khi cắm thiết bị kế tiếp.
2. **Mobile App (Sóng Wi-Fi không dây):** Tối ưu cho kỹ thuật viên hiện trường thao tác độc lập trên điện thoại thông minh (**Android** hoặc **iPhone**) qua sóng Wi-Fi mặc định của ONT mà **không cần ngắt mạng di động 4G/5G**, tự động chuyển sóng 2 băng tần (Dual-band Wi-Fi Handover) và nạp Firmware streaming ở bước cuối.

---

## Bảng So Sánh Các Nền Tảng

| Tiêu chí | Desktop App (`app/`) | Android App (`android-app/`) | iOS App (`ios-app/`) |
| :--- | :--- | :--- | :--- |
| **Công nghệ** | Python 3.9+ / PyQt6 | Kotlin / Jetpack Compose / Room | Pure Swift 5.9+ / SwiftUI / SQLite3 |
| **Kênh kết nối ONT** | Cáp mạng LAN RJ45 (`LAN1`/`LAN2`) | Wi-Fi mặc định (`GW040-NSv2_*`) | Wi-Fi mặc định (`GW040-NSv2_*`) |
| **Định tuyến 4G/5G** | Không ảnh hưởng (card Ethernet) | `bindProcessToNetwork` (giữ 4G/5G) | `allowsCellularAccess = false` (giữ 4G/5G) |
| **Quy trình nạp** | **10 bước** (hoặc **11 bước** kèm FW) | **8 bước** (hoặc **9 bước** kèm FW) | **8 bước** (hoặc **9 bước** kèm FW) |
| **Xử lý Excel (`.xlsx`)** | `openpyxl`, bảo toàn 30 cột | Zero-POI native parser, 30 cột | Raw DEFLATE + XMLParser, 30 cột |
| **Bảo toàn số 0 đầu** | Có (`SLID`, `SO_DT`, ngày sinh) | Có (`SLID`, `SO_DT`, ngày sinh) | Có (`SLID`, `SO_DT`, ngày sinh) |
| **Lưu trữ ngoại tuyến** | Checkpoint JSON (`.checkpoint_results.json`) | Room SQLite Database offline | Native SQLite3 WAL (`AppDatabase`) |
| **Chuẩn bảo mật Wi-Fi** | Đầy đủ 6 chế độ firmware | Đầy đủ 6 chế độ firmware | Đầy đủ 6 chế độ firmware |
| **Nâng cấp Firmware** | HTTP Multi-part Form Upload | Streaming upload + đếm ngược reboot | Streaming upload + đếm ngược reboot |
| **Nhật ký & Chẩn đoán** | Bảng Log sự kiện thời gian thực | `AppDiagnosticLogger` (ẩn mật khẩu, gửi Zalo) | Diagnostic Log Sheet (ẩn mật khẩu, gửi Zalo) |
| **Hình thức đóng gói** | PyInstaller `--onedir` (`.exe` + `.zip`) | APK Debug / Release (`.apk`) | Unsigned Sideloadable IPA (`.ipa`) |

---

## 1. Cấu Trúc Thư Mục Dự Án

```
ONT-config/
├── app/                                 # ỨNG DỤNG DESKTOP (PyQt6)
│   ├── main.py                          # Entry point (xử lý High DPI, exception & crash hook)
│   ├── core/                            # Tầng điều khiển giao thức & driver ONT
│   │   ├── config_models.py             # Data models cấu hình (DeviceConfig, GlobalConfig)
│   │   ├── network_probe.py             # Thăm dò gateway ONT (bộ lọc debounce 3s)
│   │   └── ont_driver.py                # LuCI CGI driver (10 bước chuẩn + Bước 11 nâng cấp FW)
│   ├── data/                            # Tầng dữ liệu & xử lý Excel
│   │   ├── models.py                    # Trạng thái thiết bị & kết quả kiểm thử
│   │   └── excel_manager.py             # Đọc/ghi Excel (openpyxl), checkpoint, bảo toàn 30 cột
│   └── ui/                              # Giao diện người dùng đồ họa PyQt6
│       ├── main_window.py               # MainWindow điều phối luồng, sự kiện & phím tắt
│       ├── device_table_model.py        # QAbstractTableModel hiệu năng cao (đáp ứng 60 FPS)
│       ├── worker.py                    # ProbeWorker & ONTExecutionWorker (QThread độc lập)
│       ├── styles.py                    # Bảng định kiểu QSS giao diện hiện đại
│       └── audio_alert.py               # Chuông âm thanh báo hoàn tất đa nền tảng
├── android-app/                         # ỨNG DỤNG DI ĐỘNG ANDROID NATIVE
│   ├── build.gradle.kts                 # Cấu hình Gradle Root
│   ├── settings.gradle.kts              # Cấu hình module Android
│   └── app/
│       ├── build.gradle.kts             # Dependencies (Compose BOM, Room, OkHttp3, KSP)
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml  # Khai báo quyền Wi-Fi, mạng & file sharing
│           │   ├── java/vn/vnpt/ontconfig/
│           │   │   ├── MainActivity.kt  # Compose host activity
│           │   │   ├── core/            # Driver ONT, Wi-Fi connector, SSL socket, validator
│           │   │   ├── data/            # Room SQLite database, entity, DAO & native Excel parser
│           │   │   └── ui/              # ViewModel, màn hình danh sách, màn hình review & dialogs
│           │   └── assets/sample-demo.xlsx # Dữ liệu mẫu demo tích hợp sẵn
│           └── test/                    # Bộ kiểm thử đơn vị JVM (Driver, Excel, Validator)
├── ios-app/                             # ỨNG DỤNG DI ĐỘNG iOS NATIVE (Swift 5.9 / SwiftUI)
│   ├── project.yml                      # Cấu hình dự án XcodeGen (0 thư viện ngoài bên thứ 3)
│   ├── ONTWiFiConfigurator/             # Mã nguồn Swift ứng dụng iOS
│   │   ├── ONTWiFiConfiguratorApp.swift # SwiftUI App lifecycle
│   │   ├── Core/                        # Driver ONT, Wi-Fi monitor card en0, SSL session, validator
│   │   ├── Data/                        # Native SQLite3, raw DEFLATE ZipArchive, XMLParser Excel
│   │   ├── UI/                          # MainViewModel, danh sách thuê bao, review sheet & log
│   │   └── Resources/sample-demo.xlsx   # Dữ liệu mẫu demo tích hợp sẵn
│   ├── ONTWiFiConfiguratorTests/        # Bộ kiểm thử XCTest cho iOS
│   ├── scripts/package_unsigned_ipa.sh  # Script đóng gói file .ipa unsigned phục vụ Sideload
│   ├── ONTWiFiConfigurator_Unsigned.ipa # File .ipa đã đóng gói sẵn sàng cài đặt
│   └── SHA256SUMS.txt                   # Checksum SHA-256 xác thực file .ipa
├── tests/                               # BỘ KIỂM THỬ TỰ ĐỘNG & BENCHMARK
│   ├── test_excel_manager.py            # Kiểm thử đọc, ghi, validate Excel & bảo toàn số 0 đầu
│   ├── test_ont_driver_mock.py          # Kiểm thử 10/11 bước LuCI driver trên Mock LuCI Server
│   ├── test_ui_components.py            # Kiểm thử giao diện PyQt6, bảng dữ liệu & phím tắt
│   ├── test_e2e_workflow.py             # Kiểm thử tích hợp toàn trình (E2E)
│   ├── test_ios_app_parity.py           # Kiểm thử tính nhất quán kiến trúc giữa Desktop, Android & iOS
│   ├── mock_luci_server.py              # Mock HTTP Server mô phỏng firmware LuCI CGI ONT
│   └── benchmark_perf.py                # Benchmark độ trễ sự kiện giao diện (<16ms, 60 FPS)
├── .github/workflows/
│   └── build-ios-ipa.yml                # CI/CD tự động chạy XCTest và đóng gói iOS IPA trên macOS
├── file-mau.xlsx                        # File Excel mẫu dữ liệu thuê bao chuẩn 30 cột
├── requirements.txt                     # Danh sách thư viện Python phụ thuộc
├── build_windows.py                     # Script Python tự động đóng gói PyInstaller Windows
├── ont_setup.spec                       # Cấu hình spec PyInstaller tối ưu hóa onedir
├── run_app.bat                          # File bat khởi chạy app nhanh trên Windows
├── build.bat                            # File bat thực thi đóng gói nhanh trên Windows
├── create_desktop_shortcut.bat          # File bat tạo phím tắt ứng dụng ra màn hình Desktop
└── HUONG_DAN_SU_DUNG_TOOL_ONT.md        # Hướng dẫn chi tiết vận hành & xử lý sự cố thực địa
```

---

## 2. Quy Trình Cấu Hình Tự Động (Protocol Workflows)

### A. Quy Trình Desktop Qua Cáp Mạng LAN (10 hoặc 11 Bước)

Ứng dụng kết nối tới ONT qua địa chỉ mặc định `https://192.168.1.1/` trên cổng cáp mạng LAN:

```
┌─────────────┐               Cáp mạng LAN RJ45              ┌───────────────────────┐
│ Máy tính PC │ ◄──────────────────────────────────────────► │ ONT iGate GW040-NSv2  │
│ (Desktop)   │                                              │ (Cổng LAN1 hoặc LAN2) │
└─────────────┘                                              └───────────────────────┘
```

1. **Probe Gateway:** Thăm dò định kỳ tới `https://192.168.1.1/` với cơ chế debounce 3s (yêu cầu phản hồi HTTP 200 ổn định 3 lần liên tiếp trước khi báo `ONLINE`).
2. **Đăng nhập mặc định:** Đăng nhập tài khoản `admin` / `admin`, kiểm tra cờ `firstlog == "1"` và giám sát biến `numberLogin` chống kích hoạt cơ chế khóa thiết bị 180s.
3. **Đổi mật khẩu quản trị:** Đổi mật khẩu sang mật khẩu mới (mặc định `Passwd2@`, hỗ trợ tùy chỉnh trên giao diện) kèm kiểm tra độ phức tạp.
4. **Đăng nhập lại:** Xác thực bằng mật khẩu quản trị mới để nhận `sessionKey` phiên làm việc chính thức.
5. **Cài đặt WAN PPPoE Idempotent:** Truy vấn `wancfg.json`; nếu đã tồn tại VLAN 11 thì thực hiện `editwan.json`, nếu chưa có thực hiện `addwan.json` và gọi `sendwan.cmd` (VLAN 11, MTU 1492, kích hoạt NAT và Firewall).
6. **Cài đặt GPON Password (SLID):** Nạp mã `SLID` định danh GPON (lấy từ cột `Mã MEN` hoặc `SO_MAY`), kiểm tra độ dài $\le 10$ ký tự ASCII và loại bỏ ký tự shell injection nguy hiểm.
7. **Cấu hình Wi-Fi 2.4 GHz:** Nạp Tên Wi-Fi (SSID) và Mật khẩu, cấu hình 1 trong 6 chuẩn bảo mật Wi-Fi, khởi động lại driver Wi-Fi (`wlrestart.json`) và nghỉ 2 giây cho driver ổn định.
8. **Cấu hình Wi-Fi 5 GHz:** Tự động sinh tên Wi-Fi 5G dạng `{SSID_2.4G} 5G` ($\le 32$ ký tự), đồng bộ chuẩn bảo mật và mật khẩu giống băng tần 2.4GHz.
9. **Cấu hình LAN IPv4:** Thiết lập địa chỉ IP LAN `192.168.1.1`, subnet mask `255.255.255.0`, dải DHCP `192.168.1.2` - `192.168.1.254`.
10. **Đăng xuất / Hoàn tất (Chế độ 10 bước mặc định):** Gửi lệnh `POST /cgi-bin/luci/logout.cmd` trong khối `finally` để giải phóng phiên làm việc, xóa triệt để lỗi `"user exist"`.
11. **Nâng cấp Firmware tự động (Tùy chọn bước 11):** Khi tích chọn `"Update Firmware ở bước cuối cùng"` và chọn file firmware (`.signed` / `.bin` / `.img`), ứng dụng kiểm tra phiên (`updatefwsessioncheck.json`), kiểm tra RAM trống (`imagesize.json`), tải lên file firmware (`uploadsoftware.conf`). Ngay khi ONT phản hồi `"status": "valid"`, ONT tự động ghi Flash và khởi động lại ngầm, cho phép kỹ thuật viên **rút cáp mạng ngay lập tức** để chuyển sang ONT tiếp theo.

---

### B. Quy Trình Mobile Qua Sóng Wi-Fi (Android & iOS — 8 hoặc 9 Bước)

Dành cho kỹ thuật viên hiện trường không mang máy tính, thao tác hoàn toàn qua điện thoại:

```
┌────────────────┐                Sóng Wi-Fi Không Dây               ┌───────────────────────┐
│ Điện thoại     │ ◄················································► │ ONT iGate GW040-NSv2  │
│ (Android / iOS)│       Ép định tuyến nội bộ, không tắt 4G/5G       │ (Wi-Fi 2.4G & 5G)     │
└────────────────┘                                                   └───────────────────────┘
```

- **Lần kết nối 1 (Bước 1–6 — Trên sóng Wi-Fi mặc định ban đầu):**
  - Kiểm tra chữ ký và ghim vân tay chứng chỉ TLS SHA-256 của ONT (`192.168.1.1`).
  - Đăng nhập mặc định và đổi mật khẩu Admin sang `Passwd2@`.
  - Tự động nhận diện băng tần kết nối thực tế (`wl0` 2.4GHz hay `wl1` 5GHz) từ `dhcpclient.json` và `deviceinfo2.json`.
  - Cấu hình WAN PPPoE VLAN 11, GPON SLID, LAN IPv4.
  - **Bước 6:** Chỉ đổi tên và cấu hình **Wi-Fi băng tần phụ** (`Band B`, chờ `wlrestart.json` tối đa 30s) rồi đăng xuất sạch phiên `logout.cmd`. *(Với điện thoại cũ chỉ có Wi-Fi 2.4GHz, ứng dụng tự động cấu hình cả 5GHz và 2.4GHz rồi kết nối lại vào sóng 2.4GHz mới)*.
- **Chuyển sóng tự động & Handover (Bước 7):**
  - Ngân sách chờ mở rộng: **180s** (Android) hoặc **240s** (iOS).
  - Áp dụng cơ chế **chờ dịch vụ ổn định**: 8s lắng đầu sau Bước 6 + 8s cửa sổ ổn định dịch vụ (`nas`/`br0`/`udhcpd`) sau khi phát hiện beacon, chờ cấp IP DHCP `192.168.1.x` tới 15s và kiểm tra giữ link ổn định.
  - **Retry giãn cách lũy tiến 2 tầng:** Lịch thử lại tăng dần (`5s -> 10s -> 15s -> 20s`) ở cả tầng hệ điều hành và tầng HTTP Driver, tự dọn phiên cũ khi rớt mạng.
  - Đăng nhập lại **chỉ bằng mật khẩu Admin mới** và xác thực hội tụ đúng băng tần phụ.
- **Lần kết nối 2 & Hoàn tất (Bước 8–9):**
  - **Bước 8:** Đổi tên và cấu hình cho **Wi-Fi băng tần còn lại** (`Band A`), kiểm tra NVRAM read-only `nvramWlSsid`. Cả 2 băng tần Wi-Fi hoàn tất 100%. Nếu không nâng cấp Firmware, quy trình kết thúc trọn vẹn ở **Bước 8/8**.
  - **Bước 9/9 (Tùy chọn nâng cấp Firmware):** Đồng bộ kết nối (`ensureGatewayReadyOnSecondaryBand`, ngân sách 90s), kiểm tra phiên nâng cấp, kiểm tra RAM và stream file firmware (`uploadsoftware.conf`, timeout 180s). Ngay khi ONT nhận `"status": "valid"`, ứng dụng xóa phiên, tự động ngắt kết nối Wi-Fi ONT để khôi phục kết nối 4G/5G ngay lập tức và hiển thị đồng hồ đếm ngược tham khảo ~150s chờ ONT tự ghi flash & khởi động lại.

---

## 3. Quy Chuẩn Kỹ Thuật & Tính Năng Nổi Bật

1. **Hợp nhất định danh GPON về duy nhất `SLID / Mã MEN`:**
   - Hợp nhất dữ liệu giữa `Mã MEN` và `SO_MAY`, loại bỏ hoàn toàn ô nhập `Số máy` trùng lặp trên giao diện cả 3 nền tảng.
   - Kiểm tra định dạng nghiêm ngặt: độ dài $\le 10$ ký tự ASCII, loại bỏ toàn bộ 11 ký tự shell metacharacters (`&|;$><`\!'"`).
   - Tự động sinh tên Wi-Fi mặc định `VNPT_{SLID}` khi nạp dữ liệu.
2. **Hỗ trợ đầy đủ 6 chuẩn bảo mật Wi-Fi (`sec_mode`):**
   - Khớp 100% với tham số firmware iGate GW040-NSv2:
     - `wpa2_wpa3_psk`: WPA2-PSK / WPA3-PSK (Mặc định khuyến nghị).
     - `wpa2_psk`: WPA2-PSK (Tương thích rộng / mạng EasyMesh).
     - `wpa_wpa2_psk`: WPA-PSK / WPA2-PSK (Hỗ trợ thiết bị cũ).
     - `wpa3_psk`: WPA3-SAE Pure.
     - `wpa_psk`: WPA-Personal.
     - `open`: Mạng mở (Không mật khẩu).
3. **Nút tiện ích 1 chạm "Thêm số 0 ở đầu" cho mật khẩu Wi-Fi:**
   - Tự động phát hiện mật khẩu Wi-Fi 7 chữ số (nguyên nhân do Excel tự động ép kiểu số làm mất số `0` ở đầu ngày sinh, ví dụ `01051995` thành `1051995`).
   - Cung cấp nút bấm 1 chạm thêm số `0` để đạt độ dài tối thiểu 8 ký tự chuẩn WPA2/WPA3.
4. **Bảo toàn dữ liệu Excel 30 cột & Số 0 đầu chuỗi:**
   - Bảo toàn cấu trúc 30 cột gốc của file phân công giao việc OneBSS.
   - Giữ nguyên số `0` ở đầu của `SLID`, `SO_DT`, và ngày sinh (ví dụ `001560828`).
   - Ghi thêm 3 cột kết quả chuẩn hóa: `Trạng thái cấu hình`, `Thời gian thực hiện`, `Ghi chú chi tiết`.
5. **Chính sách an toàn dữ liệu PPPoE:**
   - **Chặn hoàn toàn** không cho bấm RUN đối với các hàng dữ liệu thiếu mật khẩu PPPoE.
   - **Tuyệt đối không** tự động gán mật khẩu giả lập để tránh làm rớt mạng hoặc khóa tài khoản khách hàng trên BRAS.
6. **Nhật ký tiến trình & Bảo mật thông tin chẩn đoán:**
   - Khung log tiến trình tự động bung mở khi gặp lỗi.
   - Cơ chế che mờ thông tin nhạy cảm 2 lớp (mật khẩu Admin, PPPoE, Wi-Fi, byte giữa của MAC/BSSID và mã thuê bao) trên cả giao diện lẫn file log xuất ra.
   - Hỗ trợ xuất và chia sẻ file `ONT_Debug_Log_<timestamp>.txt` trực tiếp qua Zalo / Telegram để hỗ trợ từ xa.

---

## 4. Hướng Dẫn Sử Dụng & Triển Khai Bản Desktop (`app/`)

### Cài Đặt Môi Trường
Yêu cầu: **Python 3.9+** (khuyến nghị Python 3.10 hoặc 3.11).

```bash
pip install -r requirements.txt
```

Nội dung gói phụ thuộc (`requirements.txt`):
- `PyQt6>=6.4.0`
- `openpyxl>=3.0.0`
- `requests>=2.28.0`
- `urllib3>=1.26.0`
- `pyinstaller>=5.0.0`
- `pytest>=7.0.0`

### Khởi Chạy Ứng Dụng
- **Trên Windows:** Nhấp đúp vào file `run_app.bat` hoặc mở terminal chạy:
  ```cmd
  python -m app.main
  ```
- **Trên Linux/macOS:**
  ```bash
  python3 -m app.main
  ```

### Chạy Bộ Kiểm Thử Tự Động (93 Test Cases)
Dự án được bảo vệ bởi bộ kiểm thử tự động toàn diện gồm 93 test cases:

```bash
python3 -m pytest tests/
```

Danh mục kiểm thử:
- `tests/test_excel_manager.py`: Kiểm thử đọc, ghi, validate Excel & bảo toàn số 0 đầu.
- `tests/test_ont_driver_mock.py`: Kiểm thử 10/11 bước cấu hình LuCI và nạp Firmware trên Mock Server.
- `tests/test_ui_components.py`: Kiểm thử giao diện người dùng, phím tắt, bảng dữ liệu & inline editing.
- `tests/test_e2e_workflow.py`: Kiểm thử tích hợp toàn trình luồng nạp cấu hình 1-by-1.
- `tests/test_ios_app_parity.py`: Kiểm thử đối chiếu tính nhất quán giữa bản Desktop, Android và iOS.

### Chạy Benchmark Hiệu Năng Giao Diện (60 FPS)
```bash
PYTHONPATH=. python3 tests/benchmark_perf.py
```
*Kết quả: Độ trễ giao diện GUI $\le 0.14\text{ms}$, thời gian nạp và render bảng 89 hàng $\approx 380\text{ms}$, tốc độ tra cứu cache màu $\le 3.3\text{ms} / 10.000\text{ lần}$.*

### Đóng Gói Ứng Dụng Cho Windows (`.exe`)
Trên máy tính Windows, thực thi lệnh:
```cmd
build.bat
```
hoặc:
```cmd
python build_windows.py
```

Kịch bản sẽ:
1. Đóng gói ứng dụng dạng thư mục độc lập `--onedir` vào `dist/ONT_040NS_Tool/` (khởi động cực nhanh $\le 1.5\text{s}$, không bị quét lặp bởi Windows Defender).
2. Tự động sao chép file mẫu `file-mau.xlsx` và file tạo phím tắt `create_desktop_shortcut.bat`.
3. Nén thành gói phân phối `dist/ONT_040NS_Tool.zip` (~45-55MB) sẵn sàng bàn giao cho kỹ thuật viên.

Kỹ thuật viên sau khi tải về chỉ cần **Giải nén (Extract All)** và nhấp đúp file **`create_desktop_shortcut.bat`** để tạo biểu tượng mở app trực tiếp từ Desktop.

---

## 5. Hướng Dẫn Phát Triển & Đóng Gói Bản Android (`android-app/`)

### Môi Trường Phát Triển
- **JDK:** Java 17 LTS.
- **Android SDK:** Compile SDK 34, Min SDK 26 (Android 8.0+), Target SDK 34.
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`), Compose BOM 2024.02.00, Room 2.6.1, KSP.

### Chạy Unit Test Trên JVM
```bash
cd android-app
./gradlew testDebugUnitTest
```

### Đóng Gói File Cài Đặt APK
```bash
cd android-app
./gradlew assembleDebug
```
File APK xuất xưởng nằm tại:
`android-app/app/build/outputs/apk/debug/app-debug.apk`

### Quy Trình Thao Tác Hiện Trường (Android)
1. Mở app, bấm **"Nạp Excel (.xlsx)"** hoặc bấm **"Nạp Mẫu Demo"** để nạp danh sách thuê bao.
2. Tìm kiếm nhanh theo `ma_tb` (`Account`), `SLID`, `TEN_TB` hoặc `SO_DT`.
3. Bấm **"Bắt Wi-Fi ONT"** để kết nối vào sóng mặc định `GW040-NSv2_2.4G_XXXXXX` (ứng dụng tự động ghim luồng dữ liệu vào ONT mà không cần tắt 4G/5G).
4. Tại màn hình Review: kiểm tra `SLID`, bổ sung mật khẩu PPPoE nếu thiếu, chỉnh chuẩn bảo mật Wi-Fi hoặc chọn file Firmware nếu cần nâng cấp.
5. Bấm **`RUN - ĐỔ CẤU HÌNH ONT`**: ứng dụng thực thi tự động quy trình 2 lần kết nối. Khi hoàn tất, bấm **"Xuất & Chia Sẻ Báo Cáo (.xlsx)"** qua Zalo.

---

## 6. Hướng Dẫn Phát Triển & Cài Đặt Bản iOS (`ios-app/`)

### Môi Trường Phát Triển
- **Hệ điều hành & Công cụ:** macOS Sonoma / Sequoia, Xcode 15+, Swift 5.9+.
- **Hệ điều hành thiết bị:** iOS 16.0 trở lên.
- **Kiến trúc:** 100% Pure Swift & SwiftUI, sử dụng native `libz` và `libsqlite3`, **hoàn toàn không dùng CocoaPods hay Swift Package Manager** để đảm bảo gói `.app` đơn khối tương thích tuyệt đối với các công cụ Sideload miễn phí.

### Tạo Dự Án Xcode & Chạy XCTest
```bash
cd ios-app
xcodegen generate
xcodebuild test -project ONTWiFiConfigurator.xcodeproj -scheme ONTWiFiConfigurator -destination "platform=iOS Simulator,name=iPhone 15"
```

### Đóng Gói File `ONT_WiFi_Configurator_Unsigned.ipa` Thủ Công
```bash
cd ios-app
./scripts/package_unsigned_ipa.sh
```
Kịch bản tự động biên dịch Release, xác thực loại trừ dữ liệu PII nhạy cảm, ký mã ad-hoc và đóng gói file `ONT_WiFi_Configurator_Unsigned.ipa` kèm mã băm `SHA256SUMS.txt`.

### Tự Động Hóa CI/CD Trên GitHub Actions
Workflow `.github/workflows/build-ios-ipa.yml` tự động kích hoạt trên mỗi commit:
- Khởi tạo môi trường `macos-14`, cài đặt `xcodegen`.
- Chạy toàn bộ bộ kiểm thử XCTest trên iOS Simulator.
- Chụp ảnh màn hình nghiệm thu giao diện tự động (`ios-simulator-screenshot.png`).
- Đóng gói và phát hành Artifact `ONT_WiFi_Configurator_Unsigned.ipa` kèm mã băm SHA-256.

### Hướng Dẫn Cài Đặt Lên iPhone (Sideload Miễn Phí)
1. **Qua phần mềm Sideloadly (Khuyến nghị trên Windows & Mac):**
   - Cắm iPhone vào máy tính qua cáp USB, mở Sideloadly.
   - Kéo thả file `ONT_WiFi_Configurator_Unsigned.ipa` vào Sideloadly.
   - Nhập tài khoản Apple ID cá nhân (miễn phí) và bấm **Start**.
   - Sau khi cài xong, trên iPhone vào: *Cài đặt -> Cài đặt chung -> Quản lý VPN & Thiết bị*, chọn Apple ID của bạn và bấm **Tin cậy (Trust)**.
   - *(Trên iOS 16+)* Vào *Cài đặt -> Quyền riêng tư & Bảo mật -> Chế độ nhà phát triển (Developer Mode)* và bật On.
2. **Qua AltStore / TrollStore / ESign:**
   - Mở file `ONT_WiFi_Configurator_Unsigned.ipa` trực tiếp bằng AltStore hoặc TrollStore trên thiết bị để cài đặt.

---

## 7. Hướng Dẫn Xử Lý Sự Cố Thường Gặp

| Hiện tượng | Nguyên nhân | Biện pháp xử lý |
| :--- | :--- | :--- |
| **Đèn báo mãi ở trạng thái `OFFLINE`** | Cáp mạng chưa cắm chặt, ONT chưa boot xong hoặc IP card mạng sai dải | Kiểm tra đèn LAN trên ONT. Đặt card mạng về DHCP hoặc IP tĩnh `192.168.1.100`. Tạm thời tắt Wi-Fi máy tính nếu mạng Wi-Fi đó cũng cấp dải `192.168.1.x`. |
| **Cảnh báo khóa thiết bị (`timeoutLogin=180`)** | Nhập sai mật khẩu admin quá 3 lần liên tiếp trước đó | Dùng tăm nhấn giữ nút **Reset cứng** ở sau thân modem trong 10 giây để đưa ONT về cấu hình xuất xưởng. |
| **Báo lỗi `user exist` khi đăng nhập** | Phiên làm việc trước đó chưa được đăng xuất (do rút cáp đột ngột) | Đợi 60 giây để ONT tự giải phóng phiên cũ, hoặc khởi động lại ONT. Ứng dụng luôn tự động gửi `logout.cmd` trong khối `finally`. |
| **Nút `RUN` bị mờ không bấm được** | ONT đang OFFLINE hoặc hàng dữ liệu bị thiếu Mật khẩu PPPoE | Chờ ONT báo `ONLINE` và kiểm tra cột `Mật khẩu PPPoE`. Nếu thiếu, nhấp đúp chuột vào ô trên bảng để nhập bổ sung. |
| **Lỗi `PermissionError` khi xuất Excel** | File báo cáo Excel đang được mở bởi ứng dụng khác | Đóng file Excel đang mở trên máy tính, sau đó nhấn nút "Xuất Báo Cáo" lại một lần nữa. |
| **Mobile rớt kết nối ở Bước 7 Handover** | ONT khởi động lại tiến trình Wi-Fi (`nas`) mất thời gian | Ứng dụng tự động retry giãn cách 4 lần (`5s -> 10s -> 15s -> 20s`) trong ngân sách 180s/240s. Giữ điện thoại gần ONT để sóng ổn định. |

---

## 8. Đơn Vị Phát Triển & Hỗ Trợ Kỹ Thuật

- **Đơn vị phát triển:** Trung tâm Viễn thông Sơn Tây - VNPT Hà Nội
- **Tác giả:** Đỗ Xuân Thịnh
- **Hotline / Zalo hỗ trợ kỹ thuật:** `0946827186`
- **Tiện ích trên giao diện:** Người dùng có thể nhấp chuột hoặc chạm một lần vào thanh Footer ở đáy ứng dụng để tự động sao chép số điện thoại hỗ trợ kỹ thuật vào Clipboard.
