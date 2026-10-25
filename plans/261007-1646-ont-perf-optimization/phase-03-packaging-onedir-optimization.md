---
phase: 3
title: "Tối Ưu Đóng Gói PyInstaller (--onedir & Tự Động Nén Zip)"
status: pending
priority: P1
effort: "2h"
dependencies: [1, 2]
---

# Phase 3: Tối Ưu Đóng Gói PyInstaller (--onedir & Tự Động Nén Zip)

## Overview
Tái cấu trúc toàn diện quy trình đóng gói PyInstaller cho ứng dụng trên Windows. Chuyển đổi định dạng phân phối mặc định từ `--onefile` (nén tự bung vào thư mục tạm `%TEMP%` gây chậm 15-30s) sang định dạng thư mục độc lập `--onedir`, loại trừ các module Qt6 không sử dụng để giảm dung lượng, và tự động nén thư mục thành file `ONT_040NS_Tool.zip` cho kỹ thuật viên tải về giải nén dùng ngay.

## Requirements
- Functional:
  - Cập nhật `ont_setup.spec`:
    * Thêm các module loại trừ `excludes`: `PyQt6.QtQml`, `PyQt6.QtQuick`, `PyQt6.QtPdf`, `PyQt6.QtNetworkAuth`, `PyQt6.QtSql`, `PyQt6.QtSvgWidgets`, `tkinter`, `matplotlib`, `numpy`, `pandas`.
    * Đảm bảo giữ lại đầy đủ: `PyQt6.sip`, `PyQt6.QtCore`, `PyQt6.QtWidgets`, `PyQt6.QtGui`, `openpyxl`, `requests`, `urllib3`.
    * Cấu hình `upx=False` tránh bị Windows Defender báo nhận diện nhầm (false-positive).
  - Cập nhật `build_windows.py`:
    * Chế độ mặc định là `--onedir` (thư mục độc lập `dist/ONT_040NS_Tool/`).
    * Sau khi build hoàn tất, sử dụng thư viện `shutil.make_archive` để tự động nén thư mục `dist/ONT_040NS_Tool/` thành file `dist/ONT_040NS_Tool.zip`.
    * Vẫn duy trì cờ tùy chọn `--onefile` nếu người dùng thực sự có nhu cầu đặc biệt.
  - <!-- Updated: Red Team Review - Finding 3: Base Directory Resolution (get_app_path) -->
  - Xây dựng hàm tiện ích `get_app_path(relative_path: str) -> str` trong `app/core/config_models.py`:
    * Định vị tài nguyên chính xác dựa trên `sys.executable` (khi chạy dưới dạng đóng gói PyInstaller frozen) hoặc `__file__` (khi chạy mã nguồn dev), thay vì dựa dẫm vào `os.getcwd()`.
    * Áp dụng `get_app_path` cho việc nạp file mẫu mặc định `file-mau.xlsx`, ghi file `.checkpoint_results.json` và xuất file `crash_dump.log`. Loại trừ hoàn toàn lỗi thất bại khi người dùng khởi động ứng dụng từ Desktop Shortcut (nơi CWD bị Windows trỏ về Desktop).
  - <!-- Updated: Red Team Review - Finding 8: Hardened Desktop Shortcut Script -->
  - Tạo script tạo shortcut tự động: `create_desktop_shortcut.bat`:
    * Bọc nháy kép toàn bộ các biến đường dẫn `%~dp0` để hỗ trợ an toàn các thư mục có dấu cách hoặc ký tự tiếng Việt.
    * Sử dụng PowerShell gọi `WScript.Shell` tạo file shortcut `.lnk` chuẩn, thiết lập rõ ràng cả `TargetPath` lẫn `WorkingDirectory` trỏ về thư mục cài đặt app.
- Non-functional:
  - Thời gian khởi động ứng dụng sau khi nhấp đúp file `.exe` trên Windows 10/11 $\le 1.5\text{ giây}$ (thay vì 15-30 giây).
  - Dung lượng file nén `ONT_040NS_Tool.zip` giảm từ ~85MB xuống còn ~45-55MB.
  - Hoạt động ổn định trên các máy tính cài đặt phần mềm diệt virus doanh nghiệp (không bị chặn ở thư mục `%TEMP%`).

## Architecture
```
┌────────────────────────────────────────────────────────┐
│ build_windows.py Pipeline                              │
├────────────────────────────────────────────────────────┤
│ 1. Clean build/ & dist/                                │
│ 2. Run PyInstaller ont_setup.spec (--onedir mode)      │
│    - Exclude unused Qt6 modules (Qml, Quick, Pdf,...)  │
│ 3. Output Directory: dist/ONT_040NS_Tool/              │
│    - ONT_040NS_Tool.exe (Pre-extracted portable binary)│
│    - Qt6 runtime & plugins (Scanned ONCE on unzip)     │
│ 4. Auto-Compress: dist/ONT_040NS_Tool.zip              │
│ 5. Generate: create_desktop_shortcut.bat               │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Modify: `ont_setup.spec`
- Modify: `build_windows.py`
- Create: `create_desktop_shortcut.bat`

## Implementation Steps
1. Tinh chỉnh `ont_setup.spec`:
   - Mở rộng mảng `excludes` để loại bỏ các dynamic library Qt6 không dùng.
2. Nâng cấp `build_windows.py`:
   - Mặc định `--onedir`.
   - Thêm bước nén zip tự động `shutil.make_archive(zip_path, 'zip', dist_folder)`.
   - Hiển thị hướng dẫn rõ ràng ở màn hình kết thúc build.
3. <!-- Updated: Red Team Review - Finding 8: Hardened Desktop Shortcut Script -->
   Tạo file `create_desktop_shortcut.bat` với cú pháp bọc nháy kép an toàn, thiết lập `WorkingDirectory` chuẩn xác cho Shortcut.
4. <!-- Updated: Red Team Review - Finding 3: Base Directory Resolution -->
   Cài đặt hàm `get_app_path()` và đồng bộ đường dẫn nạp file mẫu tại `app/ui/main_window.py` và `app/main.py`.
5. Chạy thử nghiệm lệnh build và kiểm tra thời gian tạo gói cũng như tính đầy đủ của thư mục output.

## Success Criteria
- [x] Lệnh `python build_windows.py` chạy thành công và tạo ra cả thư mục `dist/ONT_040NS_Tool/` lẫn file `dist/ONT_040NS_Tool.zip`.
- [x] Không thiếu bất kỳ thư viện Qt6 thiết yếu nào (`qwindows.dll`, `QtCore`, `QtWidgets`, `QtGui`).
- [x] Dung lượng file `.zip` gọn nhẹ, mở ứng dụng nhanh chóng tức thì.

## Risk Assessment
- **Risk:** Kỹ thuật viên không biết cách giải nén file `.zip` mà nhấp đúp chạy trực tiếp từ trong giao diện zip của Windows dẫn đến lỗi thiếu đường dẫn file mẫu.
  - *Observable Signal:* Thông báo "Không tìm thấy file mẫu file-mau.xlsx".
  - *Mitigation:* Trong tài liệu và thông báo build, ghi rõ "Bắt buộc giải nén (Extract All) trước khi chạy", đồng thời trong code tự động tạo file mẫu fallback nếu file không tồn tại.
