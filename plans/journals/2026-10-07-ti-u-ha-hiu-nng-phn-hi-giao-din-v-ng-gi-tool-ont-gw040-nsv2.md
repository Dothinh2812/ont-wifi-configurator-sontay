---
title: Tối ưu hóa hiệu năng phản hồi giao diện và đóng gói Tool ONT GW040-NSv2
date: 2026-10-07
summary: "Tách Network Probe sang QThread độc lập (ProbeWorker), lazy-load color caching cho QTableView, chuyển đổi PyInstaller sang --onedir kèm nén zip tự động"
---

# Tối ưu hóa hiệu năng phản hồi giao diện và đóng gói Tool ONT GW040-NSv2

## Bối cảnh & Vấn đề
Tool Đổ Cấu Hình ONT VNPT iGate GW040-NSv2 gặp hai vấn đề hiệu năng sau khi đóng gói:
1. Thao tác giao diện bị khựng đơ lặp lại theo chu kỳ 1.0s do `NetworkProbe.probe_once()` thực hiện `requests.get()` với timeout 1.5s đồng bộ trên Main GUI Thread.
2. Ứng dụng đóng gói dạng `--onefile` mất từ 15-30 giây để bung nén vào `%TEMP%` mỗi lần khởi chạy và bị Windows Defender quét lặp lại.
3. Khi render bảng 89 hàng x 11 cột, `DeviceTableModel.data()` cấp phát liên tục hàng ngàn đối tượng `QBrush(QColor(...))` trên mỗi frame cuộn gây áp lực Garbage Collection.

## Giải pháp & Thay đổi đã thực hiện
1. **Tách Network Probe sang luồng ngầm độc lập (Phase 1):**
   - Xây dựng `ProbeWorker(QThread)` trong `app/ui/worker.py` với cơ chế tạm dừng có đồng bộ barrier (`_probe_lock.acquire(timeout=3.0)`), phát tín hiệu Qt `status_changed`.
   - Loại bỏ `QTimer` và callback đồng bộ trên `MainWindow`.
   - Chuyển `ONTExecutionWorker.cancel()` sang in-thread flag (không gọi `driver.logout()` chéo luồng gây hỏng urllib3 connection pool).
   - Bổ sung xác thực phần cứng ONT (`iGate`, `GW040`, `VNPT`) trong `NetworkProbe.probe_once()` để tránh nhận nhầm gateway Wi-Fi văn phòng.
   - Thêm `closeEvent` an toàn đợi dọn dẹp cả 2 worker threads (`wait(2500)`).
2. **Lazy-Loaded Color Caching (Phase 2):**
   - Xây dựng helper `get_status_brush(status)` với bộ đệm `_STATUS_BRUSH_CACHE` lazy-load trong `app/ui/device_table_model.py`.
   - Triệt tiêu 100% heap allocation của `QBrush`/`QColor` trong chu kỳ render `data()`, tránh lỗi crash `0xC0000005` khi thoát ứng dụng.
3. **Tối ưu Đóng gói PyInstaller & Phím tắt Desktop (Phase 3):**
   - Bổ sung `get_app_base_dir()` và `get_app_path()` trong `app/core/config_models.py` để định vị tài nguyên độc lập với thư mục CWD khi chạy từ Desktop Shortcut.
   - Loại trừ các dynamic libraries Qt6 không sử dụng trong `ont_setup.spec` (`QtQml`, `QtQuick`, `QtPdf`, `QtWebEngineCore`,...).
   - Cập nhật `build_windows.py` mặc định dạng `--onedir` và tự động nén thư mục thành file `ONT_040NS_Tool.zip` via `shutil.make_archive`.
   - Tạo script `create_desktop_shortcut.bat` bọc nháy kép biến đường dẫn và thiết lập `WorkingDirectory` chuẩn bằng PowerShell.
4. **Benchmark & Tài liệu (Phase 4):**
   - Tạo `tests/benchmark_perf.py` đo lường khởi tạo UI (< 400ms, ngưỡng < 1s), độ trễ Qt Event Loop (avg 0.02ms / max 0.08ms, ngưỡng <= 16ms / 60 FPS), và thông lượng cache màu (< 3.5ms cho 10k items).
   - Cập nhật tài liệu `HUONG_DAN_SU_DUNG_TOOL_ONT.md` với lưu ý bắt buộc giải nén `Extract All...` và hướng dẫn tạo shortcut.

## Kết quả Kiểm thử
- Toàn bộ 59/59 bài kiểm thử ONT và 80/81 bài kiểm thử toàn repo PASS 100%.
- Không có bất kỳ lỗi hồi quy nghiệp vụ nào.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
