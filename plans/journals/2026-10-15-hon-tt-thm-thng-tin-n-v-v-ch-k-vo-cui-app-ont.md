---
title: Hoàn tất thêm thông tin đơn vị và chữ ký vào cuối app ONT
date: 2026-10-15
summary: "Triển khai thành công thanh Footer chữ ký tác giả Đỗ Xuân Thịnh 0946827186, sao chép Clipboard 1-chạm và 58/58 test cases PASS"
---

# Hoàn tất thêm thông tin đơn vị và chữ ký vào cuối app ONT

# Hoàn tất thêm thông tin đơn vị và chữ ký vào cuối app ONT

## Context
Thực thi kế hoạch `plans/261015-1040-footer-signature-credits/plan.md` theo phương thức TDD (Test-Driven Development) dưới sự giám sát cố vấn của Kongming (`--advice`) và chế độ tự động hóa (`--auto`).

## Changes Implemented
1. **Giao diện & Thành phần Footer (`app/ui/main_window.py`):**
   - Khởi tạo thanh `footer_layout` (QHBoxLayout) đặt ở cuối `main_layout` bên dưới `op_group`.
   - Phía trái: `lbl_footer_left` hiển thị `VNPT iGate GW040-NSv2 Configurator v1.1`.
   - Phía phải: `lbl_footer_signature` hiển thị `Đơn vị phát triển: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186`.
   - Thiết lập con trỏ `PointingHandCursor` và tooltip hướng dẫn nhấp chuột.
   - Thêm phương thức `_on_signature_clicked` xử lý click chuột trái: sao chép số điện thoại `0946827186` vào Clipboard hệ thống và hiển thị popup thông báo `QToolTip.showText`. Các nút chuột khác được bảo lưu hành vi mặc định.

2. **Định dạng Stylesheet QSS (`app/ui/styles.py`):**
   - Định nghĩa quy tắc cho `QLabel#lblFooterLeft` (màu xám trung tính `#6B7280`, 11px).
   - Định nghĩa badge `QLabel#lblFooterSignature` với nền sáng `#F3F4F6`, bo viền `#D1D5DB`, bo góc 4px.
   - Hiệu ứng `:hover` tương tác đổi màu nền `#E5E7EB` và màu viền `#9CA3AF`.
   - Tổng chiều cao dọc chiếm dụng chỉ 20px (thỏa mãn chỉ tiêu $\le 24\text{px}$), hoàn toàn không làm co hẹp bảng dữ liệu 89 hàng.

3. **Kiểm thử tự động theo TDD (`tests/test_ui_components.py`):**
   - Viết trước (Red phase) 2 unit tests: `test_footer_signature_credits_displayed` và `test_footer_signature_click_copies_phone_to_clipboard`.
   - Xác nhận Red: tests fail do thiếu thuộc tính.
   - Triển khai code (Green phase): tests pass 100%.
   - Mở rộng kiểm thử: kiểm tra nhấp chuột phải không ghi đè clipboard.

4. **Tài liệu hướng dẫn (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`):**
   - Cập nhật số lượng test cases từ 56 lên 58.
   - Bổ sung mục Đơn vị phát triển & Hỗ trợ kỹ thuật.

## Verification & Metrics
- Unit & E2E Tests: 58/58 tests PASS (100%).
- GUI Thread Latency Benchmark: Avg=0.02ms, Max=0.12ms (vượt chuẩn 60 FPS / 16ms).
- Init Latency: 346.99ms với 89 dòng dữ liệu.
- Subagent Code Review: `reviewer` phê duyệt hoàn toàn (`overall_status: approved`, `criteria_met: true`, `regression_detected: false`).
- Advisory Supervision: `Kongming` đánh giá Go cho cả 2 phases.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
