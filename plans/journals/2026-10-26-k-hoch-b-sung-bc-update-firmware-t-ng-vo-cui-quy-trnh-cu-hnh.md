---
title: Kế hoạch bổ sung bước Update Firmware tự động vào cuối quy trình cấu hình ONT GW040-NSv2 trên Windows
date: 2026-10-26
summary: Thiết kế phương án 3 phase tích hợp tùy chọn Update Firmware ở bước cuối cùng của app Windows (PyQt6) với cơ chế rút cáp nóng làm tiếp ngay.
---

# Kế hoạch bổ sung bước Update Firmware tự động vào cuối quy trình cấu hình ONT GW040-NSv2 trên Windows

## What happened
- Phân tích file log giao thức `update_firmware_040nsv2.jsonl` của thiết bị VNPT iGate GW040-NSv2 (nâng cấp từ `GX40E5VN0T0201` lên `GX40E5VN0R0207`).
- Xác định chuỗi 3 API LuCI CGI trong `update_software.js`:
  1. `POST /cgi-bin/luci/updatefwsessioncheck.json` (`sessionKey=<token>`) -> `{"status": "OK"}`
  2. `POST /cgi-bin/luci/imagesize.json` (`sessionKey=<token>`) -> `{"ramFree": "251696", "sessionKey": "<token>"}`
  3. `POST /cgi-bin/luci/uploadsoftware.conf` (`multipart/form-data` gồm `file` và `sessionKey`) -> `{"status": "valid", "sessionKey": "<token>"}`
- Lập kế hoạch kỹ thuật 3 Phase tại `plans/261026-1420-windows-firmware-update-step/` để tích hợp bước tùy chọn Update Firmware vào cuối quy trình của bản Desktop Windows (`app/`).

## Decision
- **Fire-and-Forget sau khi `status == "valid"`**: Ngay khi `uploadsoftware.conf` xác nhận `"status": "valid"` (~11.7 giây truyền file), ONT tự động ghi flash và khởi động lại ngầm (~2 phút 17 giây). Ứng dụng kết thúc ngay ở Bước 11/11, xóa `session_key` cục bộ (bỏ qua `logout.cmd` thừa) và thông báo cho KTV rút cáp mạng cắm sang ONT tiếp theo làm tiếp ngay lập tức.
- **Xử lý `Content-Type` trong `requests.Session`**: Truyền `headers={"Content-Type": None, ...}` khi gọi `uploadsoftware.conf` để ghi đè header mặc định `application/x-www-form-urlencoded` của session, cho phép `requests` tự sinh `multipart/form-data; boundary=...`.
- **Chống tự động chạy đè khi quên rút cáp**: Bổ sung `is_same_device_as_last_reset` dựa trên ARP MAC trong `NetworkProbe` để không tự động chạy dòng tiếp theo nếu ONT vừa nâng cấp tự khởi động lại xong sau 2 phút mà KTV vẫn chưa rút cáp.

## Next steps
- Thực thi kế hoạch bằng `/ak:cook /home/vtst/ONT-config/plans/261026-1420-windows-firmware-update-step/plan.md`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
