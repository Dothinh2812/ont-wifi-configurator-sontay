---
title: "Hoàn tất loại bỏ tham số Số máy trùng lặp và hợp nhất về SLID trên Windows, Android và iOS"
date: 2026-10-25
summary: "Đã xóa cột/ô nhập Số máy dư thừa trên bản Desktop Windows (PyQt6) và Di động (Android & iOS), hợp nhất toàn bộ luồng định danh, kiểm tra hợp lệ và xuất báo cáo Excel về SLID."
---

# Hoàn tất loại bỏ tham số Số máy trùng lặp và hợp nhất về SLID trên Windows, Android và iOS

## What happened
Đã triển khai hoàn chỉnh kế hoạch `plans/261025-1716-remove-redundant-so-may-unify-slid` (bao gồm toàn bộ 7 phát hiện từ Red Team `RT-01`..`RT-07`):
1. **Bản Desktop Windows (`app/` & `tests/`):**
   - Loại bỏ cột `("Số máy", "so_may")` khỏi `DeviceTableModel` (`app/ui/device_table_model.py`), rút gọn bảng từ 11 cột xuống **10 cột** (`0: STT`, `1: SLID`, `2: Account PPPoE`, `3: Mật khẩu PPPoE`, `4..7: Wi-Fi 2.4G/5G`, `8: Trạng thái`, `9: Ghi chú / Chi tiết`), cập nhật `editable_cols = {1, 3, 4, 5, 6, 7}` và căn giữa `col in (0, 8)`.
   - Xóa kiểm tra trùng lặp `"Thiếu Số máy định danh"` và `("Số máy", ...)` trong `app/data/excel_manager.py` và `app/ui/device_table_model.py`; bổ sung dấu `\` vào `UNSAFE_SHELL_METACHARS` (đủ 11 ký tự) trong `app/core/ont_driver.py` và che `data=` trong `app/ui/worker.py`.
   - Cập nhật `export_results` (`app/data/excel_manager.py`) ghi đè `rec.slid` vào cả hai cột `SO_MAY` (Cột 9) và `Mã MEN` (Cột 13) cùng các trường đã sửa inline ngay cả khi xuất từ `source_excel_path`.
2. **Bản Di Động Android (`android-app/`) & iOS (`ios-app/`):**
   - Xóa hoàn toàn ô nhập liệu `"Số máy (SO_MAY)"` trên `ConfigReviewScreen.kt` (Android) và `ConfigReviewView.swift` (iOS); giữ lại duy nhất ô nhập `SLID / Mã MEN` kèm tự động đồng bộ `soMay = newSlid` và tự động cập nhật SSID mặc định `"VNPT_{newSlid}"` (kèm 5G khi bật đồng bộ).
   - Cập nhật màn hình danh sách thuê bao (`SubscriberListScreen.kt`, `SubscriberListView.swift`), tìm kiếm (`SubscriberDao.kt` hỗ trợ `LTRIM(LOWER(slid), '0')`, `ExcelManager.searchSubscribers`), `mergeRecordsPreservingEdits` (bổ sung fallback `(rowIndex, accountPppoe)` và `rawRowData["SO_MAY"]`), và `exportResults` ghi `rec.slid` vào cả `SO_MAY` lẫn `Mã MEN`.
3. **Kiểm thử:**
   - `QT_QPA_PLATFORM=offscreen python3 -m pytest`: **73/73 passed**.
   - `cd android-app && ./gradlew testDebugUnitTest --no-daemon`: **BUILD SUCCESSFUL**.

## Decision
Hợp nhất hoàn toàn tham số `Số máy` về `SLID` trên tầng giao diện và nghiệp vụ của cả 3 nền tảng, nhưng vẫn giữ tương thích 100% với cấu trúc bảng SQLite (`subscribers`) và 30 cột gốc của file Excel (`SO_MAY` Cột 9 và `Mã MEN` Cột 13).

## Next steps
Sẵn sàng đóng gói và bàn giao sử dụng tại hiện trường.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
