---
phase: 4
title: "Đo Lường Hiệu Năng, Kiểm Thử Hồi Quy & Cập Nhật Tài Liệu"
status: pending
priority: P1
effort: "1h"
dependencies: [1, 2, 3]
---

# Phase 4: Đo Lường Hiệu Năng, Kiểm Thử Hồi Quy & Cập Nhật Tài Liệu

## Overview
Tiến hành đo lường thực tế các chỉ số hiệu năng (thời gian khởi động ứng dụng, độ trễ phản hồi UI khi rút/cắm cáp ONT), chạy toàn bộ bộ kiểm thử tự động (44/44 unit/integration tests và 69/69 toàn repo) để khẳng định không có hồi quy nghiệp vụ, và cập nhật tài liệu `HUONG_DAN_SU_DUNG_TOOL_ONT.md` với hướng dẫn giải nén file `.zip` và tạo Shortcut Desktop.

## Requirements
- Functional:
  - Viết script kiểm thử hiệu năng tự động `tests/benchmark_perf.py`:
    * Đo thời gian khởi tạo và render cửa sổ MainWindow (yêu cầu $< 1.0\text{s}$).
    * Mô phỏng ngắt kết nối gateway trong lúc người dùng thao tác giao diện: chứng minh Main Thread không bị block (thời gian xử lý sự kiện Qt $< 16\text{ms}$).
  - Chạy toàn bộ test suite hiện có:
    * `tests/test_ont_driver_mock.py` (19 tests)
    * `tests/test_excel_manager.py` (10 tests)
    * `tests/test_ui_components.py` (11+ tests sau khi bổ sung ProbeWorker)
    * `tests/test_e2e_workflow.py` (4 tests)
  - Cập nhật tài liệu vận hành `HUONG_DAN_SU_DUNG_TOOL_ONT.md`:
    * Hướng dẫn tải và giải nén file `ONT_040NS_Tool.zip`.
    * Hướng dẫn chạy file `create_desktop_shortcut.bat` để đưa icon ra màn hình Desktop.
    * Giải thích cơ chế luồng ngầm ProbeWorker giúp giao diện luôn mượt mà.
- Non-functional:
  - $100\%$ bài test PASS không có cảnh báo nghiêm trọng.
  - Số liệu đo lường hiệu năng minh bạch, có thể tái lập.

## Architecture
```
┌────────────────────────────────────────────────────────┐
│ Benchmark & Quality Assurance Suite                    │
├────────────────────────────────────────────────────────┤
│ 1. benchmark_perf.py                                   │
│    - Measure MainWindow init & show time (< 1.0s)      │
│    - Measure UI event processing latency (< 16ms)      │
│ 2. Automated Regression Tests                          │
│    - Driver Protocol: 10 steps LuCI verification       │
│    - Excel Parser: 13 missing PPPoE rows blocked       │
│    - UI Model & Worker: Thread isolation proof         │
│    - Mock LuCI E2E: Full 89 rows workflow              │
│ 3. Updated Documentation                               │
│    - HUONG_DAN_SU_DUNG_TOOL_ONT.md                     │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Create: `tests/benchmark_perf.py`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`
- Test: `tests/test_ui_components.py`

## Implementation Steps
1. Xây dựng script `tests/benchmark_perf.py` sử dụng `time.perf_counter()` đo lường:
   - Tốc độ khởi tạo `MainWindow` khi có file dữ liệu 89 hàng.
   - Tốc độ phản hồi của Qt Event Loop khi `probe_once()` gặp lỗi timeout ở background.
2. Chạy `pytest tests/` xác nhận 100% test pass.
3. Cập nhật các mục tương ứng trong file `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Success Criteria
- [x] Script benchmark xác nhận thời gian xử lý sự kiện UI luôn duy trì $\le 16\text{ ms}$ ngay cả khi ONT đang OFFLINE.
- [x] Toàn bộ 44+ test case trong bộ kiểm thử của dự án đều vượt qua.
- [x] Tài liệu `HUONG_DAN_SU_DUNG_TOOL_ONT.md` được bổ sung đầy đủ hướng dẫn giải nén file zip và tạo shortcut.

## Risk Assessment
- **Risk:** Máy tính môi trường test không có màn hình thật (Headless Linux CI) có thể làm sai lệch kết quả benchmark thời gian render.
  - *Observable Signal:* Lỗi Qt platform plugin xcb/wayland.
  - *Mitigation:* Thiết lập biến môi trường `QT_QPA_PLATFORM=offscreen` trong script benchmark để đảm bảo kết quả đo đạc chính xác và chạy được trên mọi môi trường.
