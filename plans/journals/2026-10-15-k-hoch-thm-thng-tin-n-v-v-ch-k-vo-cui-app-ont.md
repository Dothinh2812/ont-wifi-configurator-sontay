---
title: Kế hoạch thêm thông tin đơn vị và chữ ký vào cuối app ONT
date: 2026-10-15
summary: Hoàn tất lập kế hoạch và xác thực phỏng vấn bổ sung footer chữ ký tác giả và đơn vị phát triển
---

# Kế hoạch thêm thông tin đơn vị và chữ ký vào cuối app ONT

## Context
Người dùng yêu cầu bổ sung thông tin đơn vị tạo ứng dụng và chữ ký: **"Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186"** vào phía cuối của giao diện ứng dụng Desktop VNPT ONT GW040-NSv2 Configurator.

## Decided Solution
- Thiết kế thanh Footer 2 bên ở đáy `MainWindow`:
  - Góc trái: Tên ứng dụng & phiên bản (`VNPT iGate GW040-NSv2 Configurator v1.1`).
  - Góc phải: Badge thông tin đơn vị & tác giả (`Đơn vị phát triển: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186`).
- Tương tác tiện ích: Hỗ trợ nhấp chuột một chạm (click) vào chữ ký để tự động sao chép số điện thoại `"0946827186"` vào Clipboard hệ thống kèm tooltip báo đã copy.
- Styling Fluent UI: Font size 11px gọn gàng, không làm co hẹp bảng dữ liệu 89 hàng hoặc vùng log điều khiển.
- Kiểm thử tự động: Bổ sung 2 test cases headless trong `tests/test_ui_components.py` xác thực hiển thị chữ ký và tương tác sao chép clipboard.

## Deliverables
- Kế hoạch chi tiết: `plans/261015-1040-footer-signature-credits/plan.md`
- Phase 1: `plans/261015-1040-footer-signature-credits/phase-01-start.md`
- Phase 2: `plans/261015-1040-footer-signature-credits/phase-02-test-and-verification.md`
- Đã hoàn thành Validation Interview và Whole-Plan Consistency Sweep (0 xung đột).

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
