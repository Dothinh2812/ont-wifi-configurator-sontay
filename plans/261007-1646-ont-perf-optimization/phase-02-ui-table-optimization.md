---
phase: 2
title: "Tối Ưu Hóa Bộ Đệm Màu Sắc & Hiển Thị Bảng Thiết Bị (DeviceTableModel)"
status: pending
priority: P2
effort: "1h"
dependencies: []
---

# Phase 2: Tối Ưu Hóa Bộ Đệm Màu Sắc & Hiển Thị Bảng Thiết Bị (DeviceTableModel)

## Overview
Tối ưu hóa hiệu năng vẽ lại và cuộn bảng của `DeviceTableModel` trong `app/ui/device_table_model.py`. Khai báo và sử dụng bảng tra cứu tĩnh `STATUS_BRUSHES` cho toàn bộ các trạng thái thiết bị (`INVALID_DATA`, `RUNNING`, `SUCCESS`, `FAILED`, `SKIPPED`), triệt tiêu việc cấp phát bộ nhớ động `QBrush(QColor(...))` liên tục trên mỗi frame render của 89 dòng $\times$ 11 cột ($979\text{ ô}$), đảm bảo thao tác cuộn bảng luôn đạt 60 FPS mượt mà.

## Requirements
- Functional:
  - <!-- Updated: Red Team Review - Finding 7: Lazy-load QBrush Cache -->
  - Triển khai bộ đệm màu tĩnh thông qua hàm helper Lazy-Load `get_status_brush(status: DeviceStatus) -> Tuple[Optional[QBrush], Optional[QBrush]]`:
    * Trì hoãn việc khởi tạo `QBrush`/`QColor` cho tới lần đầu tiên được gọi khi `QApplication` đã tồn tại và hệ thống đồ họa Qt đã được thiết lập đầy đủ.
    * Loại bỏ rủi ro crash `0xC0000005` (Access Violation) hoặc `WerFault.exe` trên Windows khi thoát ứng dụng do các biến toàn cục C++ `QBrush` bị hủy sau khi `QApplication` đã bị giải phóng.
  - Tái cấu trúc hàm `data(index, role)`:
    * Tra cứu trực tiếp từ hàm `get_status_brush(status)` khi `role == Qt.ItemDataRole.BackgroundRole` hoặc `ForegroundRole`.
    * Không gọi bất kỳ hàm tạo `QBrush()` hay `QColor()` nào bên trong `data()`.
  - Giữ nguyên 100% logic inline editing, reactive re-validation cho cột mật khẩu PPPoE.
- Non-functional:
  - Giảm thiểu 100% chi phí Garbage Collection (GC pressure) liên quan đến vẽ bảng.
  - Tốc độ phản hồi của bảng khi cuộn trang hoặc chọn dòng $\le 5\text{ ms}$.

## Architecture
```
┌────────────────────────────────────────────────────────┐
│ DeviceTableModel.data(index, role)                     │
├────────────────────────────────────────────────────────┤
│ if role == BackgroundRole:                             │
│     return STATUS_BRUSHES.get(status, (None, None))[0] │
│                                                        │
│ if role == ForegroundRole:                             │
│     return STATUS_BRUSHES.get(status, (None, None))[1] │
└──────────────────────────▲─────────────────────────────┘
                           │ Direct Dictionary Lookup (O(1))
┌──────────────────────────┴─────────────────────────────┐
│ STATUS_BRUSHES (Pre-allocated Static Cache)            │
│ - INVALID_DATA: (#FFF3CD, #856404)                     │
│ - RUNNING:      (#D1ECF1, #0C5460)                     │
│ - SUCCESS:      (#D4EDDA, #155724)                     │
│ - FAILED:       (#F8D7DA, #721C24)                     │
│ - SKIPPED:      (#E2E3E5, #383D41)                     │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Modify: `app/ui/device_table_model.py`
- Modify: `tests/test_ui_components.py`

## Implementation Steps
1. <!-- Updated: Red Team Review - Finding 7: Lazy-load QBrush Cache -->
   Xây dựng hàm `get_status_brush(status)` với bộ đệm `_STATUS_BRUSH_CACHE: Dict[DeviceStatus, Tuple[QBrush, QBrush]]` được khởi tạo lười (lazy-initialized) bên trong `app/ui/device_table_model.py`.
2. Sửa đổi phương thức `data()` của `DeviceTableModel` để tra cứu từ bộ đệm lười.
3. Chạy lại test `tests/test_ui_components.py` xác nhận màu sắc, trạng thái hiển thị và logic inline edit không bị sai lệch.
## Success Criteria
- [x] Hàm `data()` không tạo mới bất kỳ đối tượng `QBrush` hoặc `QColor` nào trong chu kỳ render.
- [x] Màu sắc hiển thị các dòng (Vàng lỗi, Xanh dương đang chạy, Xanh lá thành công, Đỏ thất bại, Xám bỏ qua) giữ nguyên độ tương phản trực quan chuẩn.
- [x] Thao tác cuộn bảng và chọn dòng phản hồi tức thì không có độ trễ giật lag.
- [x] Toàn bộ unit test của `DeviceTableModel` tiếp tục PASS.

## Risk Assessment
- **Risk:** Qt Application chưa khởi tạo thì việc gọi `QColor` ở cấp độ module (module-level import) có thể gây lỗi trên một số môi trường không có Display server.
  - *Observable Signal:* Lỗi `ImportError` hoặc `QColor: Must construct a QApplication before a QColor`.
  - *Mitigation:* Khởi tạo dict brush theo kiểu Lazy-load hoặc bọc trong hàm helper `get_status_brush(status)` tự động cache khi `QApplication` đã sẵn sàng.
