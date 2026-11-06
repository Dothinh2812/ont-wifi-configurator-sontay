# VNPT ONT GW040-NSv2 Configurator

Ứng dụng Desktop (PyQt6) tự động hóa quy trình nạp cấu hình hàng loạt lần lượt từng thiết bị (**1-by-1**) cho dòng router ONT **VNPT iGate GW040-NSv2** từ file dữ liệu mẫu Excel (`file-mau.xlsx`).

---

## 1. Cấu Trúc Thư Mục Dự Án

```
ONT-config/
├── app/                            # Mã nguồn ứng dụng
│   ├── main.py                     # Entry point (xử lý High DPI & crash hook)
│   ├── core/                       # Tầng giao thức & driver ONT
│   │   ├── config_models.py        # Model cấu hình (DeviceConfig, GlobalConfig)
│   │   ├── network_probe.py        # Thăm dò gateway ONT (debounce 3s)
│   │   └── ont_driver.py           # LuCI CGI driver (10 bước cấu hình + tùy chọn Bước 11 Update Firmware)
│   ├── data/                       # Quản lý dữ liệu & Excel
│   │   ├── models.py               # Data models & trạng thái thiết bị
│   │   └── excel_manager.py        # Xử lý Excel (openpyxl), validation, checkpoint
│   └── ui/                         # Giao diện người dùng PyQt6
│       ├── main_window.py          # MainWindow điều phối sự kiện & luồng
│       ├── device_table_model.py   # QAbstractTableModel hiệu năng cao (60 FPS)
│       ├── worker.py               # ProbeWorker & ONTExecutionWorker (QThread)
│       ├── styles.py               # Stylesheet giao diện QSS
│       └── audio_alert.py          # Chuông báo hoàn tất đa nền tảng
├── tests/                          # Bộ kiểm thử tự động & benchmark
│   ├── test_excel_manager.py       # Kiểm tra đọc, ghi, validate Excel
│   ├── test_ont_driver_mock.py     # Kiểm tra 10/11 bước driver ONT & Update Firmware trên Mock LuCI
│   ├── test_ui_components.py       # Kiểm thử giao diện & tương tác người dùng
│   ├── test_e2e_workflow.py        # Kiểm thử tích hợp toàn trình (E2E)
│   ├── mock_luci_server.py         # Mock HTTP Server mô phỏng LuCI CGI ONT
│   └── benchmark_perf.py           # Benchmark hiệu năng giao diện và phản hồi
├── firmware_unpack/                # Bộ công cụ unpack & giải mã romfile firmware
│   ├── decrypt_romfile.py          # Script giải mã ctromfile.cfg (AES-256-CBC)
│   ├── unpack_tclinux.py           # Script unpack tclinux.bin.signed
│   └── README.md                   # Tài liệu phân tích firmware EN7523 / LuCI
├── file-mau.xlsx                   # File Excel mẫu dữ liệu thuê bao
├── ont-040NSv2-setup.jsonl         # Dữ liệu capture mẫu các gói tin LuCI CGI
├── requirements.txt                # Danh sách thư viện phụ thuộc Python
├── build_windows.py                # Script tự động đóng gói PyInstaller Windows
├── ont_setup.spec                  # Cấu hình spec PyInstaller tối ưu onedir
├── run_app.bat                     # File bat khởi chạy app nhanh trên Windows
├── build.bat                       # File bat thực thi đóng gói trên Windows
├── create_desktop_shortcut.bat     # File bat tạo shortcut ra Desktop Windows
└── HUONG_DAN_SU_DUNG_TOOL_ONT.md   # Hướng dẫn chi tiết vận hành & xử lý sự cố
```

---

## 2. Cài Đặt Môi Trường

Yêu cầu: **Python 3.9+** (khuyến nghị Python 3.10 hoặc 3.11).

```bash
pip install -r requirements.txt
```

Nội dung `requirements.txt`:
- `PyQt6>=6.4.0`
- `openpyxl>=3.0.0`
- `requests>=2.28.0`
- `urllib3>=1.26.0`
- `pyinstaller>=5.0.0`
- `pytest>=7.0.0`

---

## 3. Khởi Chạy Ứng Dụng

- **Trên Windows:**
  Nhấp đúp vào `run_app.bat` hoặc mở terminal chạy:
  ```cmd
  python -m app.main
  ```

- **Trên Linux/macOS:**
  ```bash
  python3 -m app.main
  ```

---

## 4. Chạy Kiểm Thử (Unit & E2E Tests)

Chạy toàn bộ 58 test cases:
```bash
PYTHONPATH=. pytest tests/test_excel_manager.py tests/test_ont_driver_mock.py tests/test_ui_components.py tests/test_e2e_workflow.py
```

Chạy kiểm tra benchmark hiệu năng (đáp ứng giao diện 60 FPS, độ trễ < 16ms):
```bash
PYTHONPATH=. python3 tests/benchmark_perf.py
```

---

## 5. Đóng Gói Ứng Dụng Cho Windows (`.exe`)

Chạy lệnh đóng gói (trên máy Windows):
```cmd
build.bat
```
hoặc:
```cmd
python build_windows.py
```

Mặc định ứng dụng sẽ được đóng gói dạng thư mục độc lập `--onedir` vào thư mục `dist/ONT_040NS_Tool` và tự động nén thành `ONT_040NS_Tool.zip` kèm file mẫu `file-mau.xlsx` để phân phối trực tiếp cho kỹ thuật viên.

---

## 5B. Ứng Dụng Di Động Android Native (`android-app/`) — Cấu Hình Qua Wi-Fi

Bên cạnh bản Desktop Windows chạy qua cáp LAN, dự án cung cấp ứng dụng **Android Native (Kotlin + Jetpack Compose)** tại thư mục `android-app/` giúp kỹ thuật viên hiện trường cấu hình ONT **VNPT iGate GW040-NSv2** hoàn toàn không dây qua sóng Wi-Fi mặc định:

### Tính Năng Nổi Bật (Bản Android)
1. **Đọc/Xuất `.xlsx` Native (Zero-POI Bloat) & Room SQLite Offline:**
   - Nạp file Excel (`.xlsx` qua SAF) hoặc bộ dữ liệu mẫu ẩn danh (`assets/sample-demo.xlsx`), bảo toàn nguyên vẹn **30 cột gốc** và số `0` ở đầu `SLID` (`Mã MEN` / `SO_MAY`, ví dụ `001560828`).
   - Tìm kiếm tức thì (`< 5ms`) theo `ma_tb` (`Account`), `SLID`, `TEN_TB`, `SO_DT` (tự động chuẩn hóa có/không có số `0` đầu).
   - Merge thông minh khi nạp lại file Excel (giữ nguyên trạng thái `SUCCESS`, `SLID` và mật khẩu PPPoE đã nhập tay).
2. **Màn Hình Kiểm Tra & Chỉnh Sửa Trước Khi RUN (`ConfigReviewScreen`):**
   - Hợp nhất tham số định danh GPON về duy nhất **`SLID / Mã MEN`** (loại bỏ ô nhập `Số máy` trùng lặp), cho phép KTV kiểm tra và sửa trực tiếp `SLID`, `Account / Mật khẩu PPPoE`, `SSID / Pass Wi-Fi 2.4G & 5G` (tự động đồng bộ SSID mặc định `VNPT_{SLID}` và 5G theo 2.4G, đồng thời ghi đồng bộ vào cả cột `SO_MAY` và `Mã MEN` khi xuất file Excel).
   - Kiểm tra hợp lệ 2 tầng (`validateOnImport` & `validateBeforeRun`): chặn đủ 11 ký tự shell metacharacters (`&|;$><`\!'"`), kiểm tra `SLID <= 10` ký tự ASCII, và cung cấp nút **1 chạm "Thêm số 0 ở đầu"** cho các mật khẩu Wi-Fi 7 chữ số (do Excel mất số `0` đầu ngày sinh).
   - **Tùy chọn 6 chuẩn bảo mật Wi-Fi (`sec_mode`):** Hỗ trợ chọn đầy đủ 6 chế độ bảo mật trên cả Windows, Android và iOS khớp với firmware GW040-NSv2 (`wpa2_wpa3_psk` mặc định, `wpa2_psk` tương thích rộng/Mesh, `wpa_wpa2_psk` thiết bị cũ/Mesh, `wpa3_psk`, `wpa_psk`, `open` không mật khẩu).
3. **Quy Trình 2 Lần Kết Nối Tự Động — Cấu Hình Trọn Vẹn Cả 2 Wi-Fi & Đổ Firmware Mới Ở Bước Cuối (8 hoặc 9 Bước):**
   - Tự động lọc sóng xuất xưởng `GW040-NSv2_2.4G_XXXXXX` / `GW040-NSv2_5G_XXXXXX` (đối chiếu hậu tố MAC với BSSID), kiểm tra tương thích bảo mật hệ điều hành ngay trước Bước 1, ghim chữ ký chứng chỉ TLS SHA-256 của ONT và ép định tuyến HTTP qua `boundWifiNetwork.socketFactory` ngay cả khi máy đang bật 4G/5G.
   - **Lần kết nối 1 (Bước 1–6):** Ngay sau Bước 2 đăng nhập & đổi mật khẩu Admin ONT mới, ứng dụng truy vấn `dhcpclient.json` (`wlAssociatedDevice`) và `deviceinfo2.json` (`wirelessInfoList`, `baseMacAddress`) để xác thực băng tần thực tế (`2.4GHz` `wl0` hay `5GHz` `wl1`), đổ WAN PPPoE VLAN 11, GPON SLID, LAN IPv4, **chỉ đổi tên & cấu hình Wi-Fi phụ (`Band B`, chờ `wlrestart.json` tối đa `30s`)**, rồi đóng sạch phiên `logout.cmd` *(trên điện thoại Android đời cũ chỉ hỗ trợ 2.4GHz, ứng dụng tự động cấu hình cả 5GHz & 2.4GHz rồi kết nối lại vào 2.4GHz mới)*.
   - **Chuyển sóng tự động & Lần kết nối 2 (Bước 7–9):** Bước 7 nâng ngân sách chờ lên **`180s`** với cơ chế **chờ ONT sẵn sàng thực sự sau khi thấy sóng** (`8s` lắng đầu sau Bước 6 + `8s` cửa sổ ổn định dịch vụ `nas`/`br0`/`udhcpd` sau khi phát hiện beacon, chờ cấp DHCP `192.168.1.x` tới `15s` + giữ link `1.5s`, lắng `2.5s` trước khi gọi Gateway `60s`) và **retry giãn cách lũy tiến 2 tầng (`5s -> 10s -> 15s -> 20s`)** cả ở tầng hệ điều hành (`WifiNetworkSpecifier`, tự thoát sớm Pha A nếu cạn hạn mức quét `4 lần/120s`, tự khóa BSSID khi 2 băng tần trùng tên) lẫn vòng lặp Bước 7 của Driver (thoát tức thì khi rớt mạng `onLost`), đăng nhập lại **chỉ bằng mật khẩu Admin ONT mới** và hội tụ xác thực `wlAssociatedDevice` + `baseMacAddress` (`15s`). Sau đó **Bước 8 đổi nốt Wi-Fi còn lại (`Band A`)** (có kiểm tra NVRAM read-only `nvramWlSsid`).
   - **Bước 9/9 — Nâng cấp Firmware ONT ở bước cuối cùng (Tùy chọn):** Khi KTV chọn file Firmware (`.signed`, `.bin`, `.img`, $64\text{ KB} \dots 64\text{ MB}$), sau khi cả 2 băng tần Wi-Fi đã lưu xong 100%, ứng dụng đồng bộ kết nối (`ensureGatewayReadyOnSecondaryBand`, ngân sách `90s`) rồi thực hiện **Bước 9/9: Tải lên & Cập nhật Firmware** (`updatefwsessioncheck.json` $\rightarrow$ `imagesize.json` $\rightarrow$ `uploadsoftware.conf` streaming `180s`), xóa ngay `sessionKey` khi nhận `"status": "valid"`, khôi phục 4G/5G ngay lập tức kèm đồng hồ đếm ngược tham khảo `150s` chờ ONT tự ghi flash & khởi động lại (không cần bất kỳ bước đổi Wi-Fi thủ công nào sau reboot!).
4. **Nhật Ký Tiến Trình & Chẩn Đoán Lỗi Thực Địa (`AppDiagnosticLogger`):**
   - Tích hợp khung **Nhật ký tiến trình & Debug** trực tiếp trong bảng tiến trình (tự động bung mở ngay khi xảy ra lỗi, gộp nhịp `200ms` chống giật giao diện) cùng hộp thoại xem toàn màn hình trên thanh tiêu đề (lọc theo `Tất cả` / `Cảnh báo & Lỗi` / `Wi-Fi & Handover`).
   - Che mật khẩu **2 lớp đồng bộ ngay trên luồng ghi** (bao gồm cả mật khẩu nguyên văn, URL-encoded `%XX` và JSON có khoảng trắng mà không làm hỏng từ khóa `admin`), gắn cờ Clipboard nhạy cảm (`EXTRA_IS_SENSITIVE`), và hỗ trợ xuất/chia sẻ file **`ONT_Debug_Log_<timestamp>.txt`** (đã tự động ẩn giữa MAC/BSSID và mã thuê bao) qua **Zalo / Telegram** để truy vết nhanh ngoài hiện trường.
### Chạy Unit Test & Đóng Gói APK (`android-app/`)
```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
File APK sau khi đóng gói nằm tại: `android-app/app/build/outputs/apk/debug/app-debug.apk`.


---

## 5C. Ứng Dụng Di Động iOS Native (`ios-app/`) & Cài Đặt File `.ipa` Sideload Miễn Phí

Dự án cung cấp thêm phiên bản **iOS Native (Pure Swift 5.9+ & SwiftUI, iOS 16.0+)** độc lập hoàn toàn trong thư mục `ios-app/` (giữ nguyên 100% mã nguồn `android-app/`), tương thích với các công cụ cài đặt Sideload miễn phí (**Sideloadly**, **AltStore**, **TrollStore**, **ESign**):

### Tính Năng Nổi Bật (Bản iOS Native)
1. **Đọc/Xuất `.xlsx` Thuần Native (`ZipArchive` raw DEFLATE `windowBits = -15` + `XMLParser`) & SQLite3 Offline:**
   - Không phụ thuộc bất kỳ thư viện bên thứ 3 nào (CocoaPods/SPM), đảm bảo gói `.app` đơn khối không gây lỗi chữ ký dynamic framework khi Sideload bằng Apple ID miễn phí.
   - Bảo toàn nguyên vẹn **30 cột gốc** (`STANDARD_30_HEADERS`) và số `0` ở đầu `SLID` (`001560828`), tìm kiếm tức thì theo `Account`, `SLID`, `TEN_TB`, `SO_DT` và Merge thông minh trong giao dịch SQLite `BEGIN IMMEDIATE TRANSACTION`.
2. **Ép Định Tuyến `https://192.168.1.1` Qua Card Wi-Fi `en0` Không Cần Tắt 4G/5G (`IosWifiMonitor` + `UnsafeSslSession`):**
   - Kiểm tra trực tiếp IPv4 `192.168.1.x` trên giao diện Wi-Fi `"en0"` (`getifaddrs`), phát hiện cảnh báo VPN (`utun*`), chờ DHCP cấp IP (`awaitWifiIpv4Ready`) và đặt `allowsCellularAccess = false` + chặn toàn bộ HTTP Redirect (`301/302/307/308`).
3. **Quy Trình 2 Lần Kết Nối & Nạp Firmware Streaming Bước Cuối (`OntMobileDriver.swift` — 8 hoặc 9 Bước):**
   - Hỗ trợ chọn & sao chép streaming file Firmware (`.signed`, `.bin`, `.img`) với `FileProtectionType.completeUntilFirstUserAuthentication` (không lỗi khi khóa màn hình), làm mới `beginBackgroundTask` khi quay lại ứng dụng, ghim chứng chỉ TLS SHA-256 liên kết nối và đối chiếu `dhcpclient.json` + `deviceinfo2.json` ngay sau Bước 2.
   - Thực thi đồng bộ **quy trình 2 lần kết nối**: Lần 1 (Bước 1–6) đổi Wi-Fi phụ & đóng phiên `logout.cmd` $\rightarrow$ Bước 7 chuyển kết nối sang Wi-Fi phụ mới với ngân sách **`240s`** theo đồng hồ thực `Date()`, lắng đầu `8s`, chờ DHCP `15s` + giữ link ổn định, phân tách thông minh giữa luồng tự động ghép nối có quyền (`NEHotspotConfigurationManager` retry giãn cách `6s -> 12s -> 18s`) và luồng cài Sideloadly miễn phí (chỉ nhận kết nối khi IP `en0` đổi hoặc KTV quay lại từ Cài đặt iOS, không kẹt phiên trên băng tần cũ, tự chép mật khẩu `.localOnly` `120s`) cùng retry giãn cách `5s -> 10s -> 15s -> 20s` ở tầng Driver $\rightarrow$ Lần 2 đăng nhập bằng mật khẩu Admin mới, **Bước 8 đổi nốt Wi-Fi còn lại**, đồng bộ `90s` và **Bước 9/9 tải lên Firmware streaming** (`uploadTask(withStreamedRequest:)`, `180s`).
4. **Nhật Ký Tiến Trình & Chia Sẻ File `ONT_Debug_Log_*.txt` Đối Xứng Trên iOS (`DiagnosticLogSheet`):**
   - Ghi log xoay vòng `512 KB x 2` (`ont_debug.log` / `ont_debug.prev.log`, mã hóa `completeUntilFirstUserAuthentication`), tự động bung khung nhật ký khi gặp lỗi, sao chép an toàn (`.localOnly`, tự xóa sau `180s`) hoặc chia sẻ file `ONT_Debug_Log_<timestamp>.txt` qua Zalo/Telegram và tự dọn sạch file tạm ngay sau khi chia sẻ.
### Đóng Gói & Cài Đặt File `ONT_WiFi_Configurator_Unsigned.ipa`
- **Tự động trên GitHub Actions:** Workflow `.github/workflows/build-ios-ipa.yml` tự động chạy Unit Tests XCTest trên `macos-14` và xuất Artifact `ONT_WiFi_Configurator_Unsigned.ipa` kèm `SHA256SUMS.txt`.
- **Đóng gói thủ công trên macOS:**
  ```bash
  cd ios-app
  ./scripts/package_unsigned_ipa.sh
  ```
- **Cài đặt lên iPhone (Sideload miễn phí):**
  - **Sideloadly (Windows / macOS):** Cắm cáp iPhone vào máy tính, mở Sideloadly, kéo thả file `ONT_WiFi_Configurator_Unsigned.ipa`, nhập Apple ID cá nhân miễn phí và nhấn **Start** (sau đó vào *Cài đặt -> Cài đặt chung -> Quản lý VPN & Thiết bị* trên iPhone để bấm **Tin cậy / Trust**).
  - **AltStore / TrollStore:** Mở file `ONT_WiFi_Configurator_Unsigned.ipa` trực tiếp bằng AltStore hoặc TrollStore trên iPhone để cài đặt.
---

## 6. Đơn Vị Phát Triển & Hỗ Trợ Kỹ Thuật

- **Đơn vị phát triển:** Trung tâm viễn thông Sơn Tây - VNPT Hà Nội
- **Tác giả:** Đỗ Xuân Thịnh
- **Hotline / Zalo hỗ trợ kỹ thuật:** `0946827186`
- **Giao diện người dùng:** Hỗ trợ nhấp chuột một chạm vào thanh Footer ở đáy ứng dụng để tự động sao chép số điện thoại hỗ trợ kỹ thuật vào Clipboard.
