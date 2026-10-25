---
phase: 3
title: "Xây dựng Giao Diện Desktop Windows (PyQt6/PySide6) & Luồng Tương Tác 1-by-1"
status: completed
priority: P1
effort: "4h"
dependencies: [1, 2]
---

# Phase 3: Xây dựng Giao Diện Desktop Windows (PyQt6/PySide6) & Luồng Tương Tác 1-by-1

## Overview
Xây dựng giao diện ứng dụng Desktop hoàn chỉnh bằng PyQt6/PySide6 tương thích Windows 10/11, hỗ trợ nạp và hiển thị bảng tham số từ Excel, bảng cấu hình thông số mặc định (cho phép chỉnh sửa an toàn với khóa IP LAN), luồng kiểm soát cắm/rút thiết bị từng con một (1-by-1), đồng bộ hóa luồng mạng (tự động tạm dừng probe khi đang đổ cấu hình), chống rung giật trạng thái kết nối (debounce 3s), bảo vệ đa nền tảng cho âm thanh thông báo và hộp thoại nhắc đổi ONT tiếp theo.

## Requirements
- Functional:
  - Giao diện người dùng đồ họa hiện đại, trực quan, không bị đơ/treo (Non-blocking UI thông qua `QThread` / `QObject` Worker):
    - **Thanh công cụ (Header Toolbar):**
      - Nút "Nạp File Excel": Mở hộp thoại chọn file `.xlsx`, tự động hiển thị số lượng bản ghi tìm thấy.
      - Nút "Xuất Báo Cáo": Cho phép lưu file Excel kết quả trạng thái sau khi chạy, bắt lỗi khóa file khi đang mở trong Excel.
      - Nhãn đếm tổng quát: Tổng số thiết bị, Đã xong (Thành công/Thất bại), Lỗi dữ liệu cần bổ sung, Còn lại.
    - **Bảng danh sách thiết bị (Device Table View):**
      - Hiển thị danh sách thiết bị từ Excel: Cột STT, Số máy, SLID, Account PPPoE, Pass PPPoE, SSID 2.4G, Pass 2.4G, SSID 5G, Pass 5G, Trạng thái, Chi tiết log.
      - Đổi màu dòng trạng thái: Chờ cấu hình (Trắng/Xám), Lỗi dữ liệu/Thiếu pass (Vàng cam), Đang cấu hình (Xanh dương), Thành công (Xanh lá), Thất bại (Đỏ).
      - Hỗ trợ sửa trực tiếp (inline edit) ô thiếu mật khẩu PPPoE ngay trên bảng trước khi bấm RUN.
      - Hỗ trợ chọn nhanh một dòng bất kỳ hoặc tự động trỏ dòng tiếp theo sau khi thiết bị trước hoàn tất.
    - **Bảng cài đặt tham số mặc định (Config Panel):**
      - Mật khẩu quản trị thiết bị mới: Ô nhập liệu (mặc định `Passwd2@`) kèm validator kiểm tra độ phức tạp chuẩn ONT (tối thiểu 8 ký tự, có chữ hoa, chữ thường, chữ số và ký tự đặc biệt).
      - Cấu hình Wi-Fi 5G: Quy tắc tự động lấy Tên Wi-Fi 5G = `{SSID_2.4G} 5G` và Mật khẩu 5G = `{Pass_2.4G}`; cung cấp ô nhập giá trị fallback khi thiếu 2.4G (mặc định `VNPT 5G` và `1234567890`), cho phép chỉnh sửa trực tiếp trên UI.
      - <!-- Updated: Red Team Review - LAN IP Route Protection -->
      - Cấu hình IPv4 LAN:
        - Mặc định khóa (read-only): Group Name: `br-lan`, IP: `192.168.1.1`, Subnet: `255.255.255.0`, DHCP: `192.168.1.2` - `192.168.1.254`, lease: 1440.
        - Cung cấp nút gạt "Mở khóa chỉnh sửa nâng cao" kèm cảnh báo rõ ràng: *"Đổi dải IP LAN sẽ làm mất kết nối gateway giữa máy tính và ONT"*.
      - Cấu hình WAN: VLAN ID (`11`), MTU (`1492`).
    - **Khu vực điều khiển và giám sát thiết bị (Operation & Monitor Panel):**
      - <!-- Updated: Red Team Review - Debounced Probe & False ONLINE Protection -->
      - Đèn báo trạng thái kết nối ONT (ONT Link Status):
        - Xanh lá (ONLINE - Đã xác nhận kết nối ổn định qua 3 lần probe liên tiếp thành công tại 192.168.1.1).
        - Đỏ/Xám (OFFLINE - Chưa cắm cáp, đang đàm phán DHCP Windows hoặc ONT đang khởi động).
      - <!-- Updated: Red Team Review - Thread Synchronization -->
      - Nút thao tác lớn "RUN - ĐỔ CẤU HÌNH": Kích hoạt luồng chạy thiết bị đang chọn (hỗ trợ phím tắt Space / Enter). Nút chỉ sáng khi ONT ở trạng thái ONLINE và dòng dữ liệu không bị lỗi thiếu pass PPPoE. Khi ấn RUN, lập tức tạm dừng luồng Network Probe để tránh gửi đồng thời nhiều request làm sập uHTTPd của ONT.
      - Nút "BỎ QUA (SKIP)": Chuyển sang thiết bị kế tiếp khi thiết bị lỗi/hỏng vật lý.
      - Nút "THỬ LẠI (RETRY)": Chạy lại thiết bị thất bại (vận hành an toàn nhờ cơ chế WAN Idempotent và chống lockout).
      - Nút "HỦY (CANCEL)": Cho phép dừng khẩn cấp tiến trình đang chạy và tự động kích hoạt `logout.cmd` dọn dẹp session.
      - Thanh tiến trình tổng thể (Progress Bar 0% $\rightarrow$ 100%) hiển thị nhãn từng bước (Ví dụ: "Bước 3/10: Cấu hình PPPoE WAN...").
      - Khung console xem log trực tiếp (Log Output View): Hiển thị chi tiết từng bước với mốc thời gian, tự động che mờ mật khẩu nhạy cảm trên log (ví dụ `pass=***`).
    - **Cơ chế thông báo hoàn tất để thay thiết bị:**
      - Sau khi cấu hình thành công:
        - <!-- Updated: Red Team Review - Cross-Platform Audio Guard -->
        - Phát âm thanh thông báo: Sử dụng wrapper an toàn, nếu trên Windows dùng `winsound.MessageBeep(winsound.MB_ICONASTERISK)`, trên Linux/Mac fallback sang `QApplication.beep()` hoặc `QSoundEffect` để không gây lỗi `ModuleNotFoundError`.
        - Hiển thị hộp thoại Modal hoặc Banner thông báo nổi bật: *"ĐÃ HOÀN TẤT CẤU HÌNH THIẾT BỊ [SO_MAY]! VUI LÒNG RÚT CÁP VÀ CẮM THIẾT BỊ TIẾP THEO."*
        - Khi đóng thông báo, con trỏ tự động chọn dòng thiết bị tiếp theo trong danh sách.
- Non-functional:
  - UI mượt mà, tốc độ phản hồi < 100ms.
  - Sử dụng giao diện chuẩn Fluent/Flat hiện đại, dễ thao tác trong môi trường phòng kỹ thuật / dây chuyền cài đặt thiết bị.

## Architecture
```
┌────────────────────────────────────────────────────────┐
│ MainWindow (PyQt6)                                     │
├────────────────────────────────────────────────────────┤
│ [Load Excel]  [Export Results]   [Counters: 1/89 Done] │
├──────────────────────────┬─────────────────────────────┤
│ Device Table             │ Settings Panel              │
│ - Row 1: 001560828 (ERR) │ - Admin Pass: Passwd2@ (OK) │
│ - Row 2: 005501941 (RUN) │ - WiFi 5G: VNPT 5G/12345... │
│ - Row 3: 005642219 (...) │ - LAN IP: [🔒 Locked 1.1]   │
├──────────────────────────┴─────────────────────────────┤
│ ONT Status: [● ONLINE (Sustained 3s)]                  │
│ [► RUN (ĐỔ CẤU HÌNH)]    [SKIP]   [RETRY]   [CANCEL]   │
│ Progress: [████████████░░░░░░░░] 60% - GPON Password   │
├────────────────────────────────────────────────────────┤
│ Real-time Log Console (Timestamp, Masked Passwords)    │
└────────────────────────────────────────────────────────┘
          │ (Signals/Slots)
          ▼
┌────────────────────────────────────────────────────────┐
│ Worker Thread & Probe Coordination                     │
│ - Connection Poller (QTimer -> Debounced Probe)        │
│   * PAUSED when ONTExecutionWorker is active!          │
│ - Execution Worker (ONTDriver -> steps 1..10)          │
│ - Safe Audio Alert (Cross-platform winsound guard)     │
│ - Incremental Checkpoint Writer                        │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Create: `app/ui/main_window.py`
- Create: `app/ui/device_table_model.py`
- Create: `app/ui/settings_dialog.py`
- Create: `app/ui/worker.py`
- Create: `app/ui/styles.py`
- Create: `app/ui/audio_alert.py`
- Create: `app/main.py`

## Implementation Steps
1. Xây dựng module `app/ui/audio_alert.py` bọc an toàn âm thanh thông báo:
   ```python
   import sys
   def play_alert_sound():
       if sys.platform == "win32":
           try:
               import winsound
               winsound.MessageBeep(winsound.MB_ICONASTERISK)
               return
           except Exception:
               pass
       from PyQt6.QtWidgets import QApplication
       QApplication.beep()
   ```
2. Xây dựng `DeviceTableModel` kế thừa `QAbstractTableModel` để hiển thị danh sách thiết bị hiệu năng cao:
   - Cho phép sửa trực tiếp ô mật khẩu PPPoE nếu dòng bị thiếu.
   - Tô màu nổi bật dòng thiếu dữ liệu để kỹ thuật viên nhận diện trước khi chạy.
3. Xây dựng `ONTExecutionWorker` kế thừa `QThread`:
   - Chạy toàn bộ thao tác HTTP với ONT trong luồng ngầm.
   - Đảm bảo trong `finally:` luôn gọi `driver.logout()` và phát tín hiệu `finished`.
   - Kết nối tín hiệu bắt đầu/kết thúc để tạm dừng (`pause()`) và tiếp tục (`resume()`) QTimer polling của `network_probe`.
4. Xây dựng giao diện chính `MainWindow`:
   - Kết nối bộ đếm debounce cho network probe: chỉ kích hoạt trạng thái ONLINE sau khi nhận 3 response HTTP 200 liên tiếp.
   - Thêm nút HỦY (CANCEL) ngắt kết nối an toàn.
   - Che mờ mật khẩu trong log view.
   - Bắt ngoại lệ khóa file khi xuất Excel và hiển thị thông báo thân thiện.
5. Thử nghiệm tương tác giao diện và phím tắt tiện lợi (Space / Enter để RUN nhanh).

## Success Criteria
- [x] Giao diện khởi động mượt mà trên Windows và Linux/macOS trong môi trường phát triển (không crash do `winsound`).
- [x] Đèn báo ONLINE hoạt động chính xác, không bị nhấp nháy ảo trong quá trình Windows đàm phán DHCP.
- [x] Luồng probe tự động dừng khi bấm RUN, không gây xung đột tài nguyên mạng trên uHTTPd của ONT.
- [x] Dòng thiếu mật khẩu PPPoE bị khóa nút RUN và hiển thị cờ cảnh báo rõ ràng.
- [x] Khi cấu hình xong, phát âm thanh và hiển thị thông báo yêu cầu đổi thiết bị, tự động chọn hàng kế tiếp.

## Risk Assessment
- **Risk:** Giao diện bị đơ (Not Responding) khi thiết bị ONT bị ngắt kết nối giữa chừng hoặc request bị treo.
  - *Observable Signal:* Chuột quay tròn, tiêu đề cửa sổ hiển thị "(Not Responding)".
  - *Mitigation:* Bắt buộc chạy toàn bộ network requests trong `QThread` riêng biệt, đặt timeout tường minh cho mọi request HTTP (5s) và cung cấp nút Hủy (Cancel/Abort).
- **Risk:** Kỹ thuật viên ấn RUN liên tục khi đèn vừa chớp xanh.
  - *Observable Signal:* Request gửi tới khi ONT chưa khởi động xong dịch vụ CGI.
  - *Mitigation:* Cơ chế debounce 3s bắt buộc giữ ổn định trước khi enable nút RUN.
