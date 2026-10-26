---
title: Kế hoạch bỏ tham số Số máy trùng lặp và hợp nhất về SLID
date: 2026-10-25
summary: "Lập kế hoạch loại bỏ cột/ô nhập Số máy dư thừa trên bản Desktop Windows (PyQt6) và Di động (Android & iOS), hợp nhất hoàn toàn về SLID."
---

# Kế hoạch bỏ tham số Số máy trùng lặp và hợp nhất về SLID

## What happened
Người dùng yêu cầu bỏ bớt tham số `Số máy` (`SO_MAY`) vì thực chất trong nghiệp vụ cấu hình ONT VNPT iGate GW040-NSv2, `Số máy` chính là `SLID` (`Mã MEN` / GPON Password), và thực hiện đồng bộ ở cả bản Di động (`android-app/`, `ios-app/`) lẫn bản Desktop Windows (`app/`).

Qua rà soát mã nguồn và dữ liệu mẫu (`file-mau.xlsx` 89 dòng):
- Trên toàn bộ 89 dòng của `file-mau.xlsx`, cột `Mã MEN` (Cột 13) hoặc trùng khớp 100% với `SO_MAY` (Cột 9) hoặc để trống.
- Trên bản Windows (`app/ui/device_table_model.py`), bảng đang hiển thị tách biệt cả cột `Số máy` (cột 1) và `SLID` (cột 2), đồng thời kiểm tra lỗi trùng lặp ở cả hai trường.
- Trên bản Android (`ConfigReviewScreen.kt`) và iOS (`ConfigReviewView.swift`), màn hình Review đang hiển thị 2 ô nhập riêng biệt cho `Số máy (SO_MAY)` và `SLID / Mã MEN`.

## Decision
Thiết lập kế hoạch triển khai gồm 2 Phase tại `plans/261025-1716-remove-redundant-so-may-unify-slid/`:
1. **Phase 1 (Desktop Windows):** Rút gọn bảng `DeviceTableModel` từ 11 cột xuống 10 cột (bỏ cột `Số máy`, đưa `SLID` lên cột 1 cho phép sửa inline), bỏ kiểm tra lỗi `Thiếu Số máy định danh` trùng lặp, dùng `slid` cho fallback `VNPT_{slid}`, log, hộp thoại hoàn tất và đồng bộ cột `SO_MAY` + `Mã MEN` khi xuất báo cáo Excel.
2. **Phase 2 (Mobile Android & iOS):** Xóa ô nhập `Số máy (SO_MAY)` trên `ConfigReviewScreen.kt` và `ConfigReviewView.swift`, hợp nhất hiển thị/tìm kiếm/kiểm tra hợp lệ về `SLID`, và ghi đồng bộ `slid` vào cả `SO_MAY` lẫn `Mã MEN` khi xuất file Excel 30 cột.

## Next steps
Thực thi kế hoạch qua `/ak:cook /home/vtst/ONT-config/plans/261025-1716-remove-redundant-so-may-unify-slid/plan.md`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
