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
3. **Tự Động Bắt Wi-Fi Mặc Định ONT & Tối Ưu Hóa Đổi Wi-Fi 2.4G Ở Bước Cuối (Bước 7/7):**
   - Tự động lọc sóng xuất xưởng `GW040-NSv2_2.4G_XXXXXX` / `GW040-NSv2_5G_XXXXXX` (đối chiếu hậu tố MAC với BSSID) và định tuyến HTTP qua `wifiNetwork.socketFactory` ngay cả khi máy đang bật 4G/5G.
   - **Dynamic Band Ordering & Band-Hopping Handover (Bước 6 $\rightarrow$ Bước 7):** Cấu hình băng tần phụ (ví dụ 5GHz `wl_unit=1`) ở Bước 6 kèm `wlrestart.json` và giải phóng phiên `logout.cmd`; sau đó **tự động chuyển kết nối Wi-Fi của máy sang chính tên Wi-Fi phụ vừa đổi thành công** (điền sẵn mật khẩu WPA2), xác thực lại `192.168.1.1` rồi mới **đổi tên & mật khẩu băng tần còn lại ở Bước 7 cuối cùng**, đảm bảo 100% lệnh lưu NVRAM (`wirelesscfg.json`) và `wlrestart.json` thực thi thành công trên đường truyền sống trước khi khôi phục 4G/5G để chia sẻ báo cáo `.xlsx` qua Zalo.

4. **Nâng Cấp Firmware ONT Trước Bước Đổi Wi-Fi Kết Nối Chính (Tùy chọn 8 Bước):**
   - Cho phép KTV bật công tắc **"Nâng cấp Firmware trước bước đổi Wi-Fi kết nối chính"** và chọn file image (`.signed`, `.bin`, `.img`, kích thước $64\text{ KB} \dots 64\text{ MB}$) qua Android SAF (tự động lưu đệm an toàn trong `noBackupFilesDir/firmware_cache/`).
   - Khi bật: ứng dụng giữ nguyên kết nối Wi-Fi hiện tại sau Bước 6 (đã lưu xong WAN, SLID, LAN, Wi-Fi băng tần phụ), thực hiện **Bước 7/8: Upload & Cập nhật Firmware** (`updatefwsessioncheck.json` $\rightarrow$ `imagesize.json` $\rightarrow$ `uploadsoftware.conf` multipart streaming), xóa ngay `sessionKey` khi nhận `"status": "valid"` (không bắn `logout.cmd` làm gián đoạn quá trình ghi flash), khôi phục 4G/5G ngay lập tức và chuyển **Bước 8/8 (Đổi Wi-Fi kết nối chính)** sang hoàn tất thủ công (hoặc bấm nút tiện ích **"2. Đổi nốt Wi-Fi chính"** ngay trên bảng tiến trình) sau khi ONT khởi động lại xong (~2 phút).
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
3. **Trình Tự 7 Bước LuCI CGI Tối Ưu Không Dây (`OntMobileDriver.swift`):**
   - Xin quyền `beginBackgroundTask` + giữ sáng màn hình, chọn nhanh băng tần đang kết nối (`2.4GHz` / `5GHz`) ngay trên màn hình Review trước nút **RUN**, đặt bước đổi tên/mật khẩu Wi-Fi của băng tần đang kết nối ở **bước cuối cùng (Bước 7/7)**, kiểm tra `totalBytesSent` và luôn gọi `logout.cmd` qua `Task.detached` khi thoát/lỗi.

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
