---
phase: 1
title: "Tách Network Probe sang QThread Nền Độc Lập (ProbeWorker)"
status: pending
priority: P1
effort: "2h"
dependencies: []
---

# Phase 1: Tách Network Probe sang QThread Nền Độc Lập (ProbeWorker)

## Overview
Xây dựng lớp `ProbeWorker` kế thừa `QThread` đảm nhiệm toàn bộ việc thăm dò kết nối tới ONT Gateway (`https://192.168.1.1/`) trong một luồng nền độc lập. Gỡ bỏ hoàn toàn `QTimer` đang chạy đồng bộ trên Main GUI Thread, giao tiếp trạng thái kết nối độc quyền qua Qt Signals/Slots (`status_changed(bool, int)`), giải phóng 100% thời gian xử lý của Main Thread để đạt tốc độ phản hồi 60 FPS.

## Requirements
- Functional:
  - Cài đặt `ProbeWorker(QThread)` trong `app/ui/worker.py`:
    * Quản lý instance `NetworkProbe` và chu kỳ lặp thăm dò (khoảng cách 1.5s giữa các lần gọi `probe_once`).
    * Phát tín hiệu Qt Signal: `status_changed = pyqtSignal(bool, int)` (is_online, consecutive_success).
    * <!-- Updated: Red Team Review - Finding 4: Probe Pause Barrier -->
    * Hỗ trợ phương thức `pause()` có đồng bộ barrier: đợi request thăm dò đang dở dang hoàn tất trước khi `ONTExecutionWorker` gửi `login.cmd`, loại bỏ nguy cơ nghẽn socket uHTTPd sinh lỗi `"user exist"`.
    * Hỗ trợ phương thức `resume()` để tiếp tục thăm dò sau khi worker hoàn tất.
    * Hỗ trợ phương thức `stop()` kèm cờ `_is_stopped` để dừng vòng lặp ngầm khi thoát ứng dụng.
  - Tái cấu trúc `MainWindow` trong `app/ui/main_window.py`:
    * Loại bỏ `self.probe_timer` trên Main GUI Thread.
    * <!-- Updated: Red Team Review - Finding 1: Eliminate Cross-Thread UI Mutation -->
    * Ngắt kết nối callback đồng bộ cũ (`self.probe.callback = None`), ủy quyền toàn bộ việc cập nhật trạng thái cho Qt Queued Connection qua `status_changed`.
    * Khởi tạo `self.probe_worker = ProbeWorker(self.probe, parent=self)`.
    * Kết nối `self.probe_worker.status_changed.connect(self._on_probe_status_changed)`.
    * Khi worker bắt đầu (`RUN`): gọi `self.probe_worker.pause()`.
    * Khi worker kết thúc: gọi `self.probe_worker.resume()`.
    * <!-- Updated: Red Team Review - Finding 5: In-Thread Worker Cancel -->
    * Nút Cancel chỉ bật cờ `_is_cancelled = True`; việc gửi `logout.cmd` được thực hiện hoàn toàn trong chính worker thread, không gọi chéo luồng gây hỏng urllib3 session pool.
    * <!-- Updated: Red Team Review - Finding 2: Safe closeEvent Termination -->
    * Cài đặt phương thức `closeEvent(self, event)`: kiểm tra nếu `self.active_worker` đang chạy thì gọi `cancel()` và ngắt kết nối tín hiệu; đồng thời gọi `self.probe_worker.stop()`, đợi cả 2 luồng kết thúc sạch sẽ (`wait(2500)`) trước khi `event.accept()`, ngăn chặn triệt để lỗi crash SIGSEGV và tiến trình zombie.
  - <!-- Updated: Red Team Review - Finding 6: ONT Hardware Fingerprint Verification -->
  - Tăng cường an toàn trong `NetworkProbe.probe_once()` (`app/core/network_probe.py`):
    * Kiểm tra nội dung response HTTP 200 phải chứa chữ ký thiết bị ONT VNPT (`iGate`, `GW040` hoặc `VNPT`), ngăn ngừa trường hợp nhận nhầm Gateway của mạng Wi-Fi văn phòng khi máy tính có 2 card mạng.
- Non-functional:
  - Độ trễ phản hồi thao tác trên giao diện (click chuột, gõ phím, cuộn danh sách) $\le 16\text{ ms}$ (60 FPS).
  - Không bao giờ xảy ra hiện tượng đứng hình (freeze/stutter) kể cả khi ONT chưa cắm nguồn hoặc cáp mạng LAN bị rút đột ngột.

## Architecture
```
┌────────────────────────────────────────────────────────┐
│                   Main GUI Thread                      │
│ ┌────────────────────────────────────────────────────┐ │
│ │ MainWindow                                         │ │
│ │ - Slot: _on_probe_status_changed(is_online, count) │ │
│ │ - Updates LED UI & sets btn_run.setEnabled()       │ │
│ └───────────────────────▲────────────────────────────┘ │
└─────────────────────────┼──────────────────────────────┘
                          │ Qt Signal: status_changed(bool, int)
                          │ (Thread-safe queued connection)
┌─────────────────────────┴──────────────────────────────┐
│ ProbeWorker (Background QThread)                       │
│ - while not self._is_stopped:                          │
│     if not paused:                                     │
│         online = probe.probe_once()                    │
│         status_changed.emit(online, count)             │
│     self.msleep(1500)                                  │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Modify: `app/ui/worker.py`
- Modify: `app/ui/main_window.py`
- Modify: `tests/test_ui_components.py`

## Implementation Steps
1. Xây dựng class `ProbeWorker(QThread)` trong `app/ui/worker.py`:
   - Khai báo signal: `status_changed = pyqtSignal(bool, int)`.
   - Vòng lặp `run()` với kiểm tra `_is_stopped` và `_is_paused`.
   - Gọi `msleep(1500)` để kiểm soát chu kỳ thăm dò không gây tải CPU.
2. Cập nhật `MainWindow` trong `app/ui/main_window.py`:
   - Gỡ bỏ `QTimer` thăm dò cũ.
   - Khởi tạo và kích hoạt `ProbeWorker.start()`.
   - Kết nối cơ chế pause/resume với `ONTExecutionWorker`.
   - Cài đặt `closeEvent` để dọn dẹp luồng an toàn.
3. Bổ sung unit test trong `tests/test_ui_components.py` kiểm thử:
   - `ProbeWorker` phát tín hiệu `status_changed`.
   - Khả năng tạm dừng `pause()`, tiếp tục `resume()`, và dừng sạch sẽ `stop()`.

## Success Criteria
- [x] Giao diện MainWindow không có bất kỳ lệnh gọi HTTP nào trên Main Thread.
- [x] `self.probe.callback` được gỡ bỏ hoàn toàn, 100% cập nhật UI qua Qt Queued Signal.
- [x] Đèn báo trạng thái kết nối ONT (ONLINE/OFFLINE) cập nhật chính xác theo chu kỳ của `ProbeWorker` và chỉ sáng ONLINE khi khớp chữ ký phần cứng iGate/GW040.
- [x] Khi rút dây mạng ra khỏi máy tính, giao diện vẫn cuộn và nhận thao tác gõ phím tức thì không bị đứng hình $1.5\text{s}$.
- [x] Đóng ứng dụng khi đang cấu hình hoặc khi đang probe không bị crash SIGSEGV và không để lại tiến trình treo trong Task Manager.
- [x] Hủy cấu hình (Cancel) không gây xung đột socket cross-thread trên requests.Session.
- [x] Toàn bộ unit test liên quan tới worker và probe tiếp tục PASS.

## Risk Assessment
- **Risk:** Luồng `ProbeWorker` không kết thúc kịp khi đóng cửa sổ làm ứng dụng bị treo ở background.
  - *Observable Signal:* Cửa sổ đã biến mất nhưng tiến trình vẫn còn trong Task Manager.
  - *Mitigation:* Trong `closeEvent`, gọi `probe_worker.stop()`, sau đó gọi `probe_worker.wait(2000)` kèm cờ cưỡng chế ngắt timeout để đảm bảo thoát hoàn toàn.
