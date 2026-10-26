---
title: "Loại bỏ tham số Số máy trùng lặp và hợp nhất về SLID trên cả bản Windows và Di động (Android & iOS)"
description: "Bỏ cột/trường nhập liệu 'Số máy' dư thừa (vì Số máy chính là SLID) trên giao diện, bộ kiểm tra hợp lệ và luồng điều khiển của bản Desktop Windows (PyQt6) cũng như bản Di động (Android Jetpack Compose & iOS SwiftUI), giữ nguyên tương thích 100% với định dạng file Excel 30 cột gốc."
status: completed
priority: P1
effort: "3h"
issue: null
branch: main
tags: [windows, android, ios, pyqt6, compose, swiftui, slid, refactor]
blockedBy: []
blocks: []
created: 2026-10-25
---

# Kế Hoạch: Loại Bỏ Tham Số `Số máy` Trùng Lặp & Hợp Nhất Về `SLID` (Windows & Di Động)

## Overview
Trong nghiệp vụ cấu hình ONT **VNPT iGate GW040-NSv2**, tham số **`Số máy` (`SO_MAY`)** và **`SLID` (`Mã MEN` / GPON Password)** thực chất là cùng một mã định danh thiết bị ($\le 10$ ký tự ASCII, ví dụ `001560828`). Hiện tại, cả bản **Desktop Windows (`app/`)** lẫn bản **Di động (`android-app/` và `ios-app/`)** đang hiển thị và kiểm tra tách biệt cả hai trường `Số máy` và `SLID`, gây dư thừa giao diện và bất tiện khi kỹ thuật viên (KTV) phải đối chiếu hoặc sửa dữ liệu ở hai nơi.

Kế hoạch này thực hiện loại bỏ tham số `Số máy` dư thừa và hợp nhất hoàn toàn về **`SLID`** trên toàn bộ các nền tảng:
1. **Bản Desktop Windows (`app/` & `tests/`):**
   - Loại bỏ cột `"Số máy"` khỏi bảng `DeviceTableModel` (`COLUMNS` rút gọn từ **11 cột xuống 10 cột**: `STT`, `SLID`, `Account PPPoE`, `Mật khẩu PPPoE`, `SSID 2.4G`, `Mật khẩu 2.4G`, `SSID 5G`, `Mật khẩu 5G`, `Trạng thái`, `Ghi chú / Chi tiết`).
   - Cập nhật chỉ số cột cho phép sửa trực tiếp (`editable_cols = {1, 3, 4, 5, 6, 7}` trong đó cột `1` là `SLID`) và căn giữa (`col in (0, 8)` cho `STT` và `Trạng thái`).
   - Loại bỏ kiểm tra trùng lặp `"Thiếu Số máy định danh"` và `("Số máy", ...)` trong `excel_manager.py` và `device_table_model.py`; chỉ kiểm tra duy nhất trên `SLID`, đồng thời sửa `UNSAFE_SHELL_METACHARS` trong `app/core/ont_driver.py` giữ đủ 11 ký tự (bao gồm `\`) và bổ sung che `data=` trong `app/ui/worker.py` (`[Red Team RT-04]`).
   - Hợp nhất khóa định danh thiết bị trong `ONTDriver` (fallback SSID 2.4G `VNPT_{slid}`), `MainWindow`, `ONTExecutionWorker`, hộp thoại hoàn tất/báo lỗi và file `.checkpoint_results.json` về `slid` (hỗ trợ khôi phục theo cả `slid`, `raw_row_data["SO_MAY"]` và `row_index` kèm các trường đã sửa inline `[Red Team RT-02]`), và cập nhật `export_results` ghi đè `rec.slid` vào cả cột `SO_MAY` (Cột 9) và `Mã MEN` (Cột 13) ngay cả khi mở từ `source_excel_path` (`[Red Team RT-01]`).
2. **Bản Di Động Android (`android-app/`) & iOS (`ios-app/`):**
   - Trên màn hình **Kiểm tra & Sửa cấu hình (`ConfigReviewScreen.kt` & `ConfigReviewView.swift`)**: xóa bỏ hoàn toàn ô nhập liệu `"Số máy (SO_MAY)"` dư thừa; chỉ giữ duy nhất ô nhập **`SLID`** ($\le 10$ ký tự ASCII), tự động đồng bộ `soMay = newSlid` và tự động cập nhật `ssid24g = "VNPT_${newSlid}"` (kèm `ssid5g` khi bật đồng bộ) nếu đang dùng SSID mặc định theo SLID cũ (`[Red Team RT-05]`).
   - Trên màn hình **Danh sách thuê bao (`SubscriberListScreen.kt` & `SubscriberListView.swift`)** và thanh tiêu đề: hiển thị `ma_tb` kèm `SLID` (không lặp lại cả `Số máy` và `SLID` trên cùng một thẻ thuê bao), cập nhật nhãn tìm kiếm thành tìm theo `Account`, `SLID`, `Tên TB`, `SĐT`.
   - Trong tầng dữ liệu & kiểm tra hợp lệ (`ExcelManager`, `OntInputValidator`, `SubscriberRecord`, `SubscriberEntity`, `SubscriberDao`, `AppDatabase`, `OntMobileDriver` trên Kotlin và Swift): tính `slid` từ `Mã MEN` hoặc `SO_MAY` trước khi kiểm tra dòng trống (`[Red Team RT-07]`), bỏ lỗi trùng lặp `"Thiếu Số máy định danh"`, dùng `slid` cho fallback `effectiveWifi24Ssid = "VNPT_${slid}"`, tìm kiếm theo `slid` có/không có số `0` đầu (`LTRIM(LOWER(slid), '0')` trong `SubscriberDao.kt` `[Red Team RT-07]`), bổ sung khóa dự phòng `(rowIndex, accountPppoe)` trong `mergeRecordsPreservingEdits` khi `SLID` đã được KTV sửa (`[Red Team RT-02]`), và ghi `rec.slid` ra cả hai cột `SO_MAY` và `Mã MEN` trong `exportResults`.

---

## Scope Challenge Summary
- **Existing code:** Đã có sẵn cơ chế đọc ưu tiên `slid = ma_men if ma_men else so_may` trong `app/data/excel_manager.py`, `ExcelManager.kt`, và `ExcelManager.swift`.
- **Requested scope:** Bỏ bớt tham số `Số máy` vì nó chính là `SLID` — sửa đồng bộ ở cả bản di động (Android + iOS) và bản Windows.
- **Complexity:** Chỉnh sửa 23 file hiện có trên 3 nền tảng + bộ test, không phát sinh class/service mới.
- **Selected mode:** `HOLD SCOPE` (`fast` mode — phạm vi rõ ràng, không có ẩn số kiến trúc).

---

## Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | Loại bỏ cột và tham số `Số máy` dư thừa trên bản Desktop Windows (`app/`), hợp nhất hiển thị/chỉnh sửa/kiểm tra hợp lệ về `SLID` | P1 |
| 2 | Loại bỏ ô nhập và hiển thị `Số máy` dư thừa trên bản Di động Android (`android-app/`) và iOS (`ios-app/`), hợp nhất về `SLID` | P1 |
| 3 | Đảm bảo tương thích ngược 100% khi đọc/xuất file Excel 30 cột gốc (`file-mau.xlsx` / `sample-demo.xlsx`) và vượt qua toàn bộ bộ kiểm thử tự động | P1 |

---

## Architecture & Data Flow

```mermaid
flowchart LR
    A[Excel .xlsx 30 Cột<br/>Cột 9: SO_MAY / Cột 13: Mã MEN] --> B[ExcelManager<br/>Windows / Android / iOS]
    B -->|slid = Mã MEN hoặc SO_MAY<br/>Giữ nguyên số 0 đầu| C[Model & Validator<br/>Chỉ kiểm tra duy nhất SLID <= 10 ASCII]
    C --> D1[Windows QTableView<br/>10 cột: STT, SLID, Account...]
    C --> D2[Android & iOS Review UI<br/>1 ô nhập SLID duy nhất]
    D1 --> E[ONT Driver & Export .xlsx<br/>Nạp GPON SLID, Fallback VNPT_SLID,<br/>Đồng bộ cột SO_MAY & Mã MEN khi xuất]
    D2 --> E
```

---

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Phase 1: Start (Desktop Windows Unification)](./phase-01-start.md) | Completed |
| 2 | [Phase 2: Mobile Android & iOS Unification](./phase-02-mobile-android-and-ios-unification.md) | Completed |

---

## Success Criteria

- [x] Bản Windows (`app/`): Bảng thiết bị hiển thị đúng **10 cột** (không còn cột `Số máy` tách rời, cột `1` là `SLID` cho phép sửa trực tiếp và tái kiểm tra hợp lệ tức thì).
- [x] Bản Android (`android-app/`) & iOS (`ios-app/`): Màn hình Review & Edit (`ConfigReviewScreen.kt`, `ConfigReviewView.swift`) không còn ô nhập `Số máy (SO_MAY)` riêng biệt; chỉ hiển thị và chỉnh sửa duy nhất trường `SLID`.
- [x] Bộ kiểm tra hợp lệ trên cả 3 nền tảng (`validate_device_record`, `OntInputValidator.kt`, `OntInputValidator.swift`) không còn sinh lỗi trùng lặp `"Thiếu Số máy định danh"`; chỉ kiểm tra hợp lệ duy nhất trên `SLID` ($\le 10$ ký tự ASCII, chặn 11 shell metacharacters).
- [x] Đọc/xuất file Excel 30 cột (`file-mau.xlsx`, `sample-demo.xlsx`) bảo toàn số `0` ở đầu `SLID` (`001560828`), tìm kiếm chính xác theo `SLID` (có hoặc không có số `0` đầu) và khi xuất báo cáo `.xlsx` cập nhật đồng bộ `SLID` vào cả cột `SO_MAY` và `Mã MEN`.
- [x] Toàn bộ test suite (`pytest` cho Desktop Windows & iOS parity, `./gradlew testDebugUnitTest` cho Android) chạy PASS 100%.

---

## Red Team Review

### Session — 2026-10-25
**Reviewers:** 2 Hostile Personas (`SecurityAdversary`, `AssumptionDestroyer`) — Light/Standard Verification Tier (`Fact Checker` + `Contract Verifier`)
**Findings:** 7 (7 accepted, 0 rejected — all backed by codebase `file:line` evidence)
**Severity breakdown:** 4 Critical, 2 High, 1 Medium

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| RT-01 | `ExcelManager.export_results` on Desktop Windows (`app/data/excel_manager.py:249-303`) Never Writes Edited `rec.slid` to `SO_MAY`/`Mã MEN` When `source_excel_path` Is Provided | Critical | Accept | `plan.md`, `phase-01` |
| RT-02 | Keying Re-Import Merge (`recordKey`) and Desktop Checkpoints Solely by Mutable `slid` Breaks `mergeRecordsPreservingEdits` & Checkpoint Restore When `SLID` Is Edited | Critical | Accept | `plan.md`, `phase-01`, `phase-02` |
| RT-03 | Setting Default `slid: str = ""` and `so_may: str = ""` on `DeviceConfig` (`app/core/config_models.py:29-35`) Without Defaulting Following Fields Raises `TypeError` | Critical | Accept | `phase-01` |
| RT-04 | Desktop `UNSAFE_SHELL_METACHARS` Drops Backslash (`\`) Due to Non-Raw String (`app/core/ont_driver.py:21`) & Missing `data=` SLID Credential Masking in `app/ui/worker.py` and `OntInputValidator.kt` | Critical | Accept | `plan.md`, `phase-01`, `phase-02` |
| RT-05 | Editing `SLID` on Mobile Review Screens (`ConfigReviewScreen.kt` & `ConfigReviewView.swift`) Leaves Pre-Populated Fallback `ssid24g` (`"VNPT_{oldSlid}"` or `""`) Stale | High | Accept | `plan.md`, `phase-02` |
| RT-06 | Fact-Check & Test Contract Alignment (`exportResults` Method Name, iOS-Only `recordCacheKey`, `ExcelAndValidatorTest.kt:228`, `OntMobileDriverTests.swift:394`) | High | Accept | `phase-02` |
| RT-07 | Empty-Row Skip Guards Check `SO_MAY` Before Reading `Mã MEN` & SQLite Hydration/Search Normalization (`SubscriberEntity.kt`, `AppDatabase.swift`, `SubscriberDao.kt`) | Medium | Accept | `plan.md`, `phase-01`, `phase-02` |

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-start.md`, `phase-02-mobile-android-and-ios-unification.md`
- **Decision deltas checked:** 7 (`RT-01` through `RT-07`)
- **Reconciled stale references:** 7 (updated `export_results` in `phase-01` to write `rec.slid` and edited fields in the `source_excel_path` branch; added fallback matching by `rowIndex`/`accountPppoe`/original `SO_MAY` in `mergeRecordsPreservingEdits` and Desktop checkpoint restore; added defaults for `account_pppoe` and `pass_pppoe` in `DeviceConfig`; fixed 11-char `UNSAFE_SHELL_METACHARS` in `app/core/ont_driver.py` and `data=` masking in `worker.py` + `OntInputValidator.kt`; added automatic `VNPT_{newSlid}` SSID update on live `SLID` edit; standardized method name to `exportResults` and added `SubscriberEntity.kt`, `AppDatabase.swift`, and `OntMobileDriverTests.swift` to `phase-02`)
- **Unresolved contradictions:** 0

<!-- slug: remove-redundant-so-may-unify-slid -->