---
title: "Kế hoạch Tối ưu Hóa Hiệu Năng & Phản Hồi Giao Diện Tool ONT GW040-NSv2"
description: "Khắc phục triệt để hiện tượng khởi động chậm (15-30s) và giật đơ giao diện bằng cách tách Network Probe sang QThread nền độc lập, tối ưu Color Caching cho QTableView và chuyển đổi đóng gói PyInstaller sang --onedir kèm nén zip tự động."
status: completed
priority: P1
effort: "6h"
issue: null
branch: main
tags: [performance, pyqt6, multithreading, pyinstaller, onedir, optimization]
blockedBy: []
blocks: []
created: 2026-10-07
---

# Kế Hoạch: Tối Ưu Hóa Hiệu Năng & Phản Hồi Giao Diện Tool ONT GW040-NSv2

## Overview
Dự án nhằm giải quyết triệt để vấn đề phản hồi chậm và giật đơ giao diện của Tool Đổ Cấu Hình ONT VNPT iGate GW040-NSv2 sau khi đóng gói trên môi trường Windows. Kế hoạch tập trung xử lý hai nguyên nhân gốc rễ đã được xác thực:
1. **Giải phóng Main GUI Thread:** Chuyển đổi cơ chế Network Probe từ `QTimer` chạy đồng bộ `requests.get()` với timeout 1.5s trên Main Thread sang một luồng ngầm độc lập `ProbeWorker(QThread)`, giao tiếp phi chặn bằng Qt Signals/Slots.
2. **Loại bỏ độ trễ khởi động của `--onefile`:** Chuyển đổi phương thức phân phối từ đóng gói 1 file nén tự bung sang định dạng thư mục độc lập `--onedir`, loại trừ các module Qt6 không sử dụng và tự động nén `.zip` để giải nén chạy tức thì dưới 1.5 giây mà không bị Windows Defender quét lặp lại.

## Goals

| # | Mục tiêu | Độ ưu tiên | Tiêu chí đo lường |
|---|----------|------------|-------------------|
| 1 | Triệt tiêu 100% hiện tượng đơ/khựng thao tác trên giao diện | P1 | Main thread hoàn toàn không chứa socket I/O; độ trễ thao tác UI $\le 16\text{ ms}$ (đạt chuẩn 60 FPS) kể cả khi ONT chưa cắm cáp hoặc mạng bị ngắt |
| 2 | Giảm thời gian khởi động mở ứng dụng trên Windows | P1 | Thời gian mở cửa sổ ứng dụng từ 15-30 giây giảm xuống $\le 1.5\text{ giây}$ |
| 3 | Tối ưu hóa hiệu năng render bảng dữ liệu 89 thiết bị | P2 | Cache tĩnh bảng màu và brush của `DeviceTableModel`, loại bỏ $100\%$ heap allocation lặp lại khi cuộn danh sách |
| 4 | Tối ưu hóa dung lượng gói đóng gói Windows | P2 | Exclude các module Qt6 không dùng (`Qt6Qml`, `Qt6Quick`, `Qt6Pdf`), giảm dung lượng gói build ~40% |
| 5 | Bảo toàn 100% tính đúng đắn nghiệp vụ | P1 | Toàn bộ 44/44 bài kiểm thử hiện có tiếp tục PASS mà không sửa đổi logic cấu hình ONT |

## System Architecture

```
┌────────────────────────────────────────────────────────┐
│                   Main GUI Thread                      │
│ ┌────────────────────────────────────────────────────┐ │
│ │ MainWindow (PyQt6 Event Loop - 60 FPS)             │ │
│ │ - Table View (Static QBrush Cache - Instant Scroll)│ │
│ │ - UI Controls (Run / Skip / Retry / Cancel)        │ │
│ │ - Realtime Log Console & Progress Bar              │ │
│ └───────────────────────▲────────────────────────────┘ │
└─────────────────────────┼──────────────────────────────┘
                          │ Qt Signals / Slots (Non-blocking)
         ┌────────────────┴────────────────┐
         │                                 │
┌────────┴──────────────┐       ┌──────────┴───────────────┐
│ ProbeWorker (QThread) │       │ ONTExecutionWorker       │
│ - Continuous GET /    │       │ - Sequential 10-step     │
│ - Debounce 3s tracker │       │ - Pauses ProbeWorker     │
│ - Sleep 1.5s in loop  │       │ - Resumes Probe on exit  │
└───────────────────────┘       └──────────────────────────┘
```

## Phases

| # | Giai đoạn | Trạng thái | Ước tính | Phụ thuộc |
|---|-----------|------------|----------|-----------|
| 1 | [Phase 1: Tách Network Probe sang QThread Nền Độc Lập](./phase-01-start.md) | Completed | 2h | Không |
| 2 | [Phase 2: Tối Ưu Hóa Bộ Đệm Màu Sắc & Hiển Thị Bảng Thiết Bị](./phase-02-ui-table-optimization.md) | Completed | 1h | Không |
| 3 | [Phase 3: Tối Ưu Đóng Gói PyInstaller (--onedir & Nén Zip)](./phase-03-packaging-onedir-optimization.md) | Completed | 2h | Phase 1, Phase 2 |
| 4 | [Phase 4: Đo Lường Hiệu Năng, Kiểm Thử Hồi Quy & Cập Nhật Tài Liệu](./phase-04-benchmark-and-verification.md) | Completed | 1h | Phase 1, Phase 2, Phase 3 |

## Success Criteria

- [x] `ProbeWorker` vận hành trong luồng ngầm riêng, gửi tín hiệu trạng thái về `MainWindow` mà không block UI.
- [x] Khi kỹ thuật viên bấm `RUN`, `ProbeWorker` tự động tạm dừng và tự động tiếp tục khi kết thúc thiết bị.
- [x] Giao diện MainWindow xử lý sự kiện đóng cửa sổ `closeEvent` sạch sẽ, dừng luồng probe an toàn không để lại tiến trình treo.
- [x] `DeviceTableModel` sử dụng bộ đệm tĩnh `STATUS_BRUSHES`, cuộn bảng 89 hàng mượt mà 60 FPS.
- [x] Kịch bản `build_windows.py` đóng gói mặc định dạng `--onedir` và tự động tạo file `ONT_040NS_Tool.zip`.
- [x] Ứng dụng khởi động trên Windows trong vòng $\le 1.5\text{ giây}$.
- [x] Toàn bộ test suite ($44/44$ và các test mới) đều vượt qua 100%.

## Red Team Review

### Session — 2026-10-07 (Adversarial Review)
**Reviewers:** Security Adversary, Failure Mode Analyst, Assumption Destroyer
**Findings:** 8 (8 accepted, 0 rejected)
**Severity breakdown:** 3 Critical, 4 High, 1 Medium

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Cross-Thread UI Mutation via Synchronous Probe Callback | Critical | Accept | Phase 1 (`phase-01-start.md`) |
| 2 | closeEvent SIGSEGV Crash & Zombie Thread Termination | Critical | Accept | Phase 1 (`phase-01-start.md`) |
| 3 | Relative Path Failure when Launched via Desktop Shortcut | Critical | Accept | Phase 3 (`phase-03-packaging-onedir-optimization.md`) |
| 4 | In-flight Probe Socket Contention during Worker Startup | High | Accept | Phase 1 (`phase-01-start.md`) |
| 5 | Concurrent Cross-Thread Socket Access on Worker Cancel | High | Accept | Phase 1 (`phase-01-start.md`) |
| 6 | Multi-homed Office Wi-Fi False Positive Gateway Detection | High | Accept | Phase 1 (`phase-01-start.md`) |
| 7 | Early QColor/QBrush Module Initialization & Exit Crash | High | Accept | Phase 2 (`phase-02-ui-table-optimization.md`) |
| 8 | Unquoted Paths & Execution Vulnerability in Shortcut Batch | Medium | Accept | Phase 3 (`phase-03-packaging-onedir-optimization.md`) |

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-start.md`, `phase-02-ui-table-optimization.md`, `phase-03-packaging-onedir-optimization.md`, `phase-04-benchmark-and-verification.md`
- **Decision deltas checked:** 8
- **Reconciled stale references:** 8
- **Unresolved contradictions:** 0
- **Summary of Reconciliations:**
  1. Đồng bộ cơ chế Pause Barrier và triệt tiêu cross-thread UI mutation (`probe.callback = None`) vào Phase 1.
  2. Bổ sung kiểm tra fingerprint ONT (`iGate`/`GW040`/`VNPT`) vào Phase 1 để ngăn chặn nhận nhầm gateway Wi-Fi văn phòng.
  3. Chuyển đổi cơ chế hủy worker an toàn trong luồng (in-thread cancel) vào Phase 1.
  4. Chuyển đổi cache `STATUS_BRUSHES` sang hàm lazy-load `get_status_brush()` trong Phase 2, loại bỏ nguy cơ crash 0xC0000005 khi thoát app.
  5. Bổ sung hàm tiện ích `get_app_path()` giải quyết triệt để lỗi đường dẫn khi chạy từ Desktop Shortcut vào Phase 3.
  6. Chuẩn hóa bảo mật bọc nháy kép và thiết lập `WorkingDirectory` cho file `create_desktop_shortcut.bat` trong Phase 3.
  7. Cập nhật các tiêu chí thành công và kịch bản benchmark trong Phase 4 tương thích với các quyết định trên.

<!-- slug: ont-perf-optimization -->
