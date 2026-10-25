---
title: Kế hoạch tối ưu hóa hiệu năng và phản hồi giao diện Tool ONT GW040-NSv2
date: 2026-10-07
summary: Lập kế hoạch kỹ thuật 4 giai đoạn giải quyết triệt để vấn đề khởi động chậm (15-30s) và giật đơ giao diện bằng cách chuyển Network Probe sang QThread nền và đóng gói --onedir nén zip
---

# Kế hoạch tối ưu hóa hiệu năng và phản hồi giao diện Tool ONT GW040-NSv2

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

## Bối cảnh & Nguyên nhân xác thực

Sau khi ứng dụng hoạt động chính xác về chức năng 10 bước và validation dữ liệu Excel, người dùng phản ánh hiệu năng phản hồi rất chậm khi chạy bản đóng gói `python build_windows.py --onefile`. Qua phân tích kỹ thuật chuyên sâu, đã xác định 2 nguyên nhân cốt lõi:

1. **Khởi động chậm (Startup Latency 15-30s):** Do cơ chế tự bung nén ~90MB của PyInstaller `--onefile` vào `%TEMP%/_MEIxxxxxx` mỗi lần khởi chạy, kích hoạt Windows Defender Real-time scanning trên hàng trăm file DLL tạm.
2. **Đơ/lag giật giao diện (Runtime UI Stutter):** Do `QTimer` mỗi 1000ms gọi `probe_once()` thực hiện `requests.get()` đồng bộ với `timeout=1.5s` trực tiếp trên Main GUI Thread. Khi ONT chưa cắm hoặc mạng chập chờn, Qt Event Loop bị treo cứng.

## Kế hoạch hành động 4 giai đoạn

- **Phase 1: Tách Network Probe sang QThread Nền Độc Lập (`ProbeWorker`)**
  - Đưa socket HTTP `requests.get()` hoàn toàn sang Background QThread.
  - Giao tiếp với MainWindow độc quyền qua Qt Signals/Slots `status_changed(bool, int)`.
  - Phối hợp tự động pause/resume với `ONTExecutionWorker` và dọn dẹp luồng an toàn trong `closeEvent`.

- **Phase 2: Tối Ưu Hóa Bộ Đệm Màu Sắc & Hiển Thị Bảng Thiết Bị (`DeviceTableModel`)**
  - Caching tĩnh bảng màu/brush `STATUS_BRUSHES` cho toàn bộ các trạng thái thiết bị.
  - Triệt tiêu chi phí cấp phát bộ nhớ động liên tục trong hàm `data()` khi vẽ 89 hàng $\times$ 11 cột, đảm bảo 60 FPS khi cuộn bảng.

- **Phase 3: Tối Ưu Đóng Gói PyInstaller (`--onedir` & Tự Động Nén Zip)**
  - Chuyển chế độ mặc định từ `--onefile` sang `--onedir` để trích xuất sẵn DLL.
  - Thêm `excludes` loại bỏ các module Qt6 không sử dụng (`Qt6Qml`, `Qt6Quick`, `Qt6Pdf`).
  - Tự động nén thành `dist/ONT_040NS_Tool.zip` và tạo script `create_desktop_shortcut.bat`.

- **Phase 4: Đo Lường Hiệu Năng, Kiểm Thử Hồi Quy & Cập Nhật Tài Liệu**
  - Viết script benchmark đo thời gian khởi tạo ($< 1.5\text{s}$) và độ trễ sự kiện UI ($< 16\text{ms}$).
  - Chạy toàn bộ 44/44 bài test đảm bảo không có bất kỳ hồi quy nào.
  - Cập nhật hướng dẫn vận hành cho kỹ thuật viên.
