---
phase: 2
title: "Kiểm thử tự động & Xác thực giao diện"
status: completed
priority: P1
effort: "30m"
dependencies: [1]
---

# Phase 2: Kiểm thử tự động & Xác thực giao diện

<!-- Updated: Validation Session 1 - Added unit tests for clipboard copy and click interaction -->

## Overview
Bổ sung kiểm thử tự động trong `tests/test_ui_components.py` nhằm xác thực sự hiện diện và tính chính xác của nhãn chữ ký tác giả/đơn vị phát triển, kiểm tra hành vi sao chép Clipboard khi click, đồng thời chạy kiểm tra toàn bộ bộ test suite (56+ tests) và benchmark hiệu năng nhằm bảo đảm không có hồi quy lỗi.

## Requirements
- Functional:
  - Test case headless xác nhận thuộc tính `lbl_footer_signature` tồn tại trên `MainWindow`.
  - Chuỗi text chứa đầy đủ 3 thành phần: `Trung tâm viễn thông Sơn Tây`, `Đỗ Xuân Thịnh`, và `0946827186`.
  - Test case mô phỏng sự kiện nhấp chuột `_on_signature_clicked` và kiểm tra clipboard hệ thống chứa chuỗi `"0946827186"`.
- Non-functional:
  - Toàn bộ 56 tests cũ tiếp tục PASS 100%.
  - Benchmark hiệu năng `benchmark_perf.py` tiếp tục đạt chuẩn $\le 16\text{ ms}$ (60 FPS).

## Architecture
Bổ sung 2 phương thức kiểm thử vào test fixture `TestMainWindowHeadless` trong `tests/test_ui_components.py`:
1. `test_footer_signature_credits_displayed`: kiểm tra text hiển thị của footer.
2. `test_footer_signature_click_copies_phone_to_clipboard`: kiểm tra tương tác click sao chép vào clipboard.

## Related Code Files
- Modify: `tests/test_ui_components.py` (thêm test cases kiểm tra footer và clipboard)

## Implementation Steps
1. Cập nhật `tests/test_ui_components.py`: Thêm phương thức kiểm tra nội dung nhãn footer và tương tác clipboard vào class `TestMainWindowHeadless`.
2. Thực thi kiểm thử:
   `PYTHONPATH=. pytest tests/test_ui_components.py`
3. Thực thi toàn bộ test suite:
   `PYTHONPATH=. pytest`
4. Thực thi kiểm thử benchmark:
   `PYTHONPATH=. python3 tests/benchmark_perf.py`

## Success Criteria
- [x] Test case `test_footer_signature_credits_displayed` PASS.
- [x] Test case `test_footer_signature_click_copies_phone_to_clipboard` PASS.
- [x] 58/58 tests PASS mà không có cảnh báo hoặc lỗi.
- [x] Benchmark hoàn thành mà không có suy giảm hiệu năng.

## Risk Assessment
- *Nguy cơ:* Trong môi trường Linux headless (offscreen), `QApplication.clipboard()` có thể trả về None hoặc buffer ảo.
- *Biện pháp giảm thiểu:* Sử dụng `QApplication.clipboard()` tiêu chuẩn của Qt6 đã hoạt động tốt trên instance `QApplication` hiện tại.
