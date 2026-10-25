---
title: Hoàn tất ứng dụng Desktop cấu hình tự động ONT VNPT iGate GW040-NSv2 từ Excel
date: 2026-10-06
summary: Triển khai hoàn chỉnh toàn bộ 4 giai đoạn ứng dụng Desktop PyQt6, xử lý 10 bước CGI LuCI, kiểm soát nghiêm ngặt 13 dòng thiếu pass PPPoE, E2E Mock Server và đóng gói Windows
---

# Hoàn tất ứng dụng Desktop cấu hình tự động ONT VNPT iGate GW040-NSv2 từ Excel

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

## Bối cảnh & Kết quả

Đã hoàn thành toàn bộ 4 giai đoạn theo kế hoạch `plans/261006-1410-ont-040ns-setup-app/plan.md`:

1. **Phase 1: Core Automation Engine & Protocol Driver (`app/core/`)**
   - Đóng gói giao thức CGI LuCI của VNPT iGate GW040-NSv2 theo 10 bước chuẩn.
   - Cơ chế mã hóa form đặc thù firmware `ont_encode_query` (thay thế `&` bằng `\x7f`).
   - Lọc và chặn 11 shell metacharacter nguy hiểm (`&|;$><`\!'"`).
   - Kiểm soát số lần đăng nhập `numberLogin` tránh bị khóa phần cứng 180s.
   - Cài đặt WAN PPPoE lũy thừa (Idempotent: tra cứu `wancfg.json` trước khi `editwan`/`addwan`).
   - Kiểm tra giới hạn SLID $\le 10$ ký tự ASCII.
   - Thêm khoảng nghỉ 2.0s cho driver Wi-Fi Broadcom nạp xong giữa băng tần 2.4G và 5G.
   - Bắt buộc gọi `logout.cmd` trong khối `finally` ở mọi kịch bản để tránh lỗi `"user exist"`.

2. **Phase 2: Module Đọc/Ghi Dữ Liệu Excel & Validation (`app/data/`)**
   - Đọc chính xác 89 dòng từ `file-mau.xlsx`, bảo toàn định dạng chuỗi số máy `SO_MAY` có số 0 ở đầu (`001560828`).
   - Strict Validator: Phát hiện chính xác 13 dòng thiếu mật khẩu PPPoE, đánh dấu lỗi nghiêm trọng (`INVALID_DATA`) và chặn bấm RUN để tránh làm rớt mạng thuê bao.
   - Kế thừa Wi-Fi 5G: SSID 5G = `{SSID_2.4G} 5G` ($\le 32$ ký tự), Pass 5G = Pass 2.4G.
   - Ghi checkpoint liên tục (`.checkpoint_results.json`) với cơ chế ghi nguyên tử (atomic write qua `.tmp`).
   - Bắt ngoại lệ khóa file Windows (`PermissionError`) khi xuất báo cáo kết quả ra Excel.

3. **Phase 3: Giao Diện Desktop Windows & Luồng Tương Tác 1-by-1 (`app/ui/`, `app/main.py`)**
   - Giao diện PyQt6 Fluent/Flat hiện đại, non-blocking UI qua `QThread` (`ONTExecutionWorker`).
   - Bảng thiết bị hỗ trợ chỉnh sửa trực tiếp (inline edit) ô mật khẩu PPPoE và tự động xác thực lại (chuyển sang `PENDING` và bật sáng nút RUN).
   - Khóa mặc định chỉnh sửa IP LAN `192.168.1.1` kèm hộp thoại cảnh báo nguy hiểm khi mở khóa.
   - Bộ lọc debounce 3s cho trạng thái kết nối ONT ONLINE.
   - Che mờ mật khẩu nhạy cảm (`pass=***`) trên khung log trực tiếp.
   - Wrapper âm thanh an toàn `audio_alert.py` (bảo vệ `winsound` trên Windows, fallback `QApplication.beep()` trên Linux/macOS).
   - Thông báo hoàn tất pop-up và tự động trỏ thiết bị kế tiếp.

4. **Phase 4: Đóng Gói Windows, Kiểm Thử Tích Hợp & Tài Liệu Vận Hành**
   - Xây dựng `tests/mock_luci_server.py` mô phỏng đầy đủ chuỗi phản hồi LuCI CGI và các tình huống biên.
   - Bộ kiểm thử tích hợp E2E tự động (`tests/test_e2e_workflow.py`).
   - Tổng cộng 44/44 test mới (và 69/69 toàn repo) đều PASS 100%.
   - Kịch bản đóng gói `build_windows.py`, `ont_setup.spec`, `build.bat` sử dụng PyInstaller (hỗ trợ cả `--onedir` chống false-positive Defender và `--onefile`).
   - Tài liệu hướng dẫn kỹ thuật viên chi tiết `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.
