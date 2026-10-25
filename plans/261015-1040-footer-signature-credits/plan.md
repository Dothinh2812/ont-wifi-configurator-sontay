---
title: "Thêm thông tin đơn vị tạo app và chữ ký tác giả vào giao diện ONT Configurator"
description: "Bổ sung thanh Footer cố định ở đáy ứng dụng hiển thị thông tin đơn vị phát triển và chữ ký hỗ trợ kỹ thuật: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186, hỗ trợ click sao chép nhanh SĐT."
status: completed
priority: P2
effort: "1h"
tags: [ui, pyqt6, footer, signature, credits]
blockedBy: []
blocks: []
created: 2026-10-15
---

# Kế Hoạch: Thêm Thông Tin Đơn Vị Tạo App & Chữ Ký Tác Giả Vào Cuối App

## Overview
Yêu cầu từ người dùng: Bổ sung thông tin đơn vị tạo app và chữ ký vào phía cuối của ứng dụng Desktop:
> **"Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186"**

Giải pháp triển khai:
Tạo một thanh Footer (`footer_layout`) cố định ở cuối `main_layout` bên dưới cụm điều khiển vận hành (`op_group`). Footer được thiết kế tinh tế với Fluent UI styling theo bố cục 2 bên: nhãn phiên bản bên trái và thông tin đơn vị / chữ ký tác giả bên phải. Nhãn chữ ký hỗ trợ nhấp chuột một chạm để tự động sao chép số điện thoại kỹ thuật vào Clipboard hệ thống. Đi kèm unit test tự động để đảm bảo nhãn luôn hiện diện, đúng nội dung và clipboard hoạt động chuẩn xác.

---

## Goals

| # | Mục tiêu | Độ ưu tiên |
|---|----------|------------|
| 1 | Thêm nhãn chữ ký và thông tin đơn vị phát triển vào đáy cửa sổ MainWindow theo bố cục 2 bên | P1 |
| 2 | Hỗ trợ nhấp chuột một chạm để sao chép nhanh số điện thoại vào Clipboard và hiển thị tooltip | P1 |
| 3 | Áp dụng styling QSS chuyên nghiệp, gọn gàng, hiệu ứng hover, không làm co hẹp bảng dữ liệu 89 hàng | P1 |
| 4 | Bổ sung unit test headless kiểm tra sự tồn tại chữ ký và tính năng copy clipboard trong `tests/test_ui_components.py` | P1 |
| 5 | Chạy toàn bộ 58 tests và benchmark đảm bảo giao diện đạt 60 FPS, không gây tác dụng phụ | P1 |

---

## Architecture & UI Layout

```
┌────────────────────────────────────────────────────────┐
│                   MainWindow (PyQt6)                   │
├────────────────────────────────────────────────────────┤
│ 1. Header Toolbar (Nạp Excel, Xuất BC, Chips, ONLINE)  │
├────────────────────────────────────────────────────────┤
│ 2. Splitter (Bảng dữ liệu 89 dòng | Cấu hình WAN/WiFi) │
├────────────────────────────────────────────────────────┤
│ 3. Operation Group (RUN, SKIP, RETRY, CANCEL, Bar, Log)│
├────────────────────────────────────────────────────────┤
│ 4. Footer Bar (MỚI - Bố cục 2 bên):                    │
│    [VNPT iGate GW040-NSv2 Configurator v1.1]           │
│    ─────────────────────────── (addStretch) ────────── │
│    [Đơn vị phát triển: Trung tâm viễn thông Sơn Tây -  │
│     Đỗ Xuân Thịnh - 0946827186 📋] (Click to copy)     │
└────────────────────────────────────────────────────────┘
```

---

## Phases

| # | Phase | Trạng thái | Ước tính | Phụ thuộc |
|---|-------|------------|----------|-----------|
| 1 | [Phase 1: Xây dựng thành phần Footer UI & QSS Styling](./phase-01-start.md) | Completed | 30m | Không |
| 2 | [Phase 2: Kiểm thử tự động & Xác thực giao diện](./phase-02-test-and-verification.md) | Completed | 30m | Phase 1 |

---

## Success Criteria

- [x] Cửa sổ ứng dụng hiển thị rõ ràng thông tin: `Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186` ở góc cuối màn hình.
- [x] Nhấp vào chữ ký sao chép thành công chuỗi `"0946827186"` vào Clipboard hệ thống kèm thông báo tooltip.
- [x] Giao diện co giãn mượt mà, không bị vỡ bố cục khi thu nhỏ/phóng to cửa sổ hoặc thay đổi tỷ lệ DPI.
- [x] Test cases trong `tests/test_ui_components.py` kiểm tra nhãn chữ ký và clipboard copy PASS 100%.
- [x] Toàn bộ 58/58 tests PASS, benchmark duy trì độ trễ Main Thread $\le 16\text{ ms}$.

---

## Validation Log

### Session 1 — 2026-10-15
**Trigger:** Yêu cầu xác thực kế hoạch chi tiết từ người dùng (`/ak:plan validate`)  
**Questions asked:** 2

#### Verification Results
- **Tier:** Light (2 phases)
- **Claims checked:** 5
- **Verified:** 5 | **Failed:** 0 | **Unverified:** 0

#### Questions & Answers

1. **[Architecture]** Bạn muốn bố trí thông tin chữ ký ở đáy ứng dụng theo phong cách nào?
   - Options: Thanh Footer 2 bên (Trái: Version, Phải: Đơn vị & Tác giả) (Recommended) | Căn giữa toàn bộ thông tin | Tích hợp vào QStatusBar hệ thống
   - **Answer:** Thanh Footer 2 bên (Trái: Version, Phải: Đơn vị & Tác giả)
   - **Rationale:** Phân tách rõ ràng giữa thông tin định danh phiên bản ứng dụng và thông tin hỗ trợ kỹ thuật, giữ giao diện cân đối và chuyên nghiệp.

2. **[Tradeoffs]** Bạn có muốn thêm tính năng tương tác nhanh với số điện thoại/chữ ký không?
   - Options: Text tĩnh cho phép chọn/copy (TextSelectableByMouse) | Tự động Copy SĐT khi Click vào chữ ký (Recommended) | Mở liên kết gọi điện (tel:0946827186)
   - **Answer:** Tự động Copy SĐT khi Click vào chữ ký
   - **Rationale:** Tiết kiệm thao tác cho kỹ thuật viên khi cần liên hệ hỗ trợ, chỉ cần 1 click là có ngay số điện thoại trong bộ nhớ tạm để dán vào Zalo/Viber hoặc trình gọi điện.

#### Confirmed Decisions
- **Bố cục Footer:** Thanh 2 bên đặt dưới `op_group`, bên trái hiển thị Version, bên phải hiển thị badge chữ ký và SĐT.
- **Tương tác Click:** Click vào nhãn chữ ký sẽ tự động copy `"0946827186"` vào clipboard hệ thống, hiển thị con trỏ bàn tay `PointingHandCursor` và tooltip thông báo.

#### Action Items
- [x] Cập nhật `phase-01-start.md`: Thêm handler `_on_signature_clicked`, con trỏ bàn tay `PointingHandCursor` và styling QSS hover.
- [x] Cập nhật `phase-02-test-and-verification.md`: Bổ sung test cases kiểm tra hiển thị footer và clipboard copy.

#### Impact on Phases
- Phase 1: Mở rộng tính năng tương tác click sao chép clipboard và QSS hover.
- Phase 2: Bổ sung 2 unit tests (nâng tổng số test lên 58).

### Whole-Plan Consistency Sweep
- Files reread: `plan.md`, `phase-01-start.md`, `phase-02-test-and-verification.md`
- Decision deltas checked: 2 (bố cục 2 bên, tương tác click copy clipboard)
- Reconciled stale references: 0
- Unresolved contradictions: 0

<!-- slug: footer-signature-credits -->
