---
phase: 2
title: "Xây dựng Module Đọc/Ghi Dữ Liệu Excel & Validation Dữ Liệu Thiết Bị"
status: completed
priority: P1
effort: "3h"
dependencies: [1]
---

# Phase 2: Xây dựng Module Đọc/Ghi Dữ Liệu Excel & Validation Dữ Liệu Thiết Bị

## Overview
Xây dựng module `excel_manager.py` để phân tích cú pháp cấu trúc file Excel đầu vào (chuẩn theo mẫu `file-mau.xlsx`), ánh xạ chính xác các trường dữ liệu cho từng thiết bị ONT, kiểm tra tính hợp lệ nghiêm ngặt (chặn mật khẩu PPPoE rỗng, kiểm tra giới hạn 10 ký tự SLID, lọc 11 ký tự shell nguy hiểm), lưu checkpoint kết quả liên tục xuống đĩa và hỗ trợ xuất báo cáo an toàn tránh xung đột khóa file Windows.

## Requirements
- Functional:
  - Hỗ trợ nạp file Excel định dạng `.xlsx`, `.xls` thông qua thư viện `openpyxl`.
  - Ánh xạ tự động các cột từ file mẫu:
    - SLID (GPON Password) $\leftarrow$ Ưu tiên lấy từ cột `Mã MEN`; nếu `Mã MEN` trống thì lấy từ cột `SO_MAY`. Bắt buộc kiểm tra độ dài $\le 10$ ký tự ASCII hợp lệ (khớp `^[a-zA-Z0-9_\-\.]{1,10}$`).
    - PPPoE Account $\leftarrow$ Cột `Account`.
    - PPPoE Password $\leftarrow$ Cột `Mật khẩu PPPoE`.
    - SSID Wi-Fi 2.4G $\leftarrow$ Cột `SSID Wi-Fi 2.4G`.
    - Mật khẩu Wi-Fi 2.4G $\leftarrow$ Cột `Mật khẩu Wi-Fi 2.4G`.
    - Wi-Fi 5G (Quy tắc kế thừa từ 2.4G):
      * **Tên Wi-Fi 5G (SSID):** Nếu có dữ liệu trong cột `SSID Wi-Fi 5G` thì dùng giá trị đó; nếu không có, tự động lấy bằng tên Wi-Fi 2.4G hiện tại + ` 5G` (ví dụ: `Kim Thu` $\rightarrow$ `Kim Thu 5G`). Nếu cả 2 đều trống, fallback về giá trị mặc định cấu hình trên UI (`VNPT 5G`). Đảm bảo độ dài SSID $\le 32$ ký tự.
      * **Mật khẩu Wi-Fi 5G:** Nếu có dữ liệu trong cột `Mật khẩu Wi-Fi 5G` thì dùng giá trị đó; nếu không có, tự động lấy **giống hệt mật khẩu Wi-Fi 2.4G** (`pass_5g = pass_24g`). Nếu cả 2 đều trống, fallback về mật khẩu mặc định (`1234567890`).
  - <!-- Updated: Red Team Review - Strict Validation & Outage Prevention -->
  - Bộ kiểm tra tính hợp lệ nghiêm ngặt (Strict Validator):
    * **Chặn lỗi nghiêm trọng (Fatal Validation Error):** Nếu cột `Mật khẩu PPPoE` trống hoặc thiếu: BẮT BUỘC đánh dấu dòng lỗi / không hợp lệ, không cho phép ấn RUN cho dòng đó cho tới khi kỹ thuật viên nhập mật khẩu bổ sung trên UI. TUYỆT ĐỐI KHÔNG tự động gán mật khẩu PPPoE giả lập/dự phòng (vì sẽ gây lỗi xác thực PAP/CHAP trên BRAS và làm gián đoạn dịch vụ thuê bao).
    * Nếu cột `SO_MAY` hoặc `Account` trống: Báo lỗi dòng không hợp lệ vì đây là 2 trường định danh duy nhất của thuê bao.
    * Kiểm tra SLID: nếu độ dài $> 10$ ký tự hoặc chứa ký tự không hợp lệ, gắn cờ cảnh báo yêu cầu sửa đổi.
    * Kiểm tra an toàn ký tự (Metacharacter Sanitization): Kiểm tra và cảnh báo nếu bất kỳ trường nào chứa 11 ký tự shell metacharacter mà LuCI chặn: `& | ; $ > < \` ! ' " \`.
    * Làm sạch dữ liệu chuỗi (strip khoảng trắng thừa ở đầu/cuối ký tự).
    * Nếu cột `Mật khẩu Wi-Fi 2.4G` trống hoặc < 8 ký tự: Gán cờ cảnh báo; cho phép dùng dự phòng `1234567890` hoặc nhắc kỹ thuật viên kiểm tra lại.
  - Quản lý trạng thái và Checkpoint liên tục:
    * Trạng thái thiết bị: `CHỜ_CẤU_HÌNH` (Pending), `LỖI_DỮ_LIỆU` (InvalidData), `ĐANG_CẤU_HÌNH` (Running), `THÀNH_CÔNG` (Success), `THẤT_BẠI` (Failed), `BỎ_QUA` (Skipped).
    * Lưu vết: thời gian bắt đầu, thời gian hoàn thành, chi tiết bước lỗi (nếu có).
    * <!-- Updated: Red Team Review - Incremental State Persistence -->
    * Cơ chế lưu trạng thái liên tục (Incremental Persistence): Sau khi mỗi thiết bị hoàn thành (thành công hoặc thất bại), ghi ngay lập tức bản ghi trạng thái vào file checkpoint cục bộ (`.checkpoint_results.json` hoặc SQLite) để không bị mất kết quả đo kiểm nếu ứng dụng bị đóng đột ngột hoặc mất điện.
  - Xuất báo cáo Excel an toàn:
    * Lưu lại file Excel mới kèm các cột trạng thái: `Trạng thái cấu hình`, `Thời gian thực hiện`, `Ghi chú chi tiết`.
    * Bắt ngoại lệ khóa file Windows (`PermissionError` khi người dùng đang mở file Excel) và hiển thị hộp thoại nhắc đóng file hoặc chọn lưu tên file khác.
- Non-functional:
  - Tải file Excel kích thước hàng trăm dòng trong < 1 giây.
  - Đảm bảo định dạng kiểu dữ liệu số máy (ví dụ `001560828`) không bị ép kiểu thành số nguyên làm mất các chữ số 0 đầu.

## Architecture
```
┌──────────────────────────────────────────────┐
│ Input Excel File (file-mau.xlsx)             │
│ [STT, SO_MAY, Account, Pass PPPoE, WiFi,...] │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│ ExcelManager (Parser & Strict Validator)     │
│ 1. Raw Data Extraction (preserve strings)    │
│ 2. Column Mapping & Normalization            │
│ 3. Strict Validation:                        │
│    - Block missing PPPoE (Fatal)             │
│    - Validate SLID length <= 10 chars        │
│    - Filter 11 Shell Metacharacters          │
│ 4. Fallback Injection (WiFi defaults only)   │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│ DeviceRecord Observable Collection           │
│ - row_index, so_may, slid, ppp_account, ...  │
│ - status: PENDING | INVALID | SUCCESS | FAIL │
└──────────────────────┬───────────────────────┘
                       │
                       ├──────────────────────────────────┐
                       ▼ (After each ONT)                 ▼ (Manual / End)
┌──────────────────────────────────────────────┐   ┌──────────────────────────────┐
│ Incremental Checkpoint (.checkpoint.json)    │   │ Export Service -> Excel File │
│ (Crash Recovery & Data Protection)           │   │ (Handle Windows File Lock)   │
└──────────────────────────────────────────────┘   └──────────────────────────────┘
```

## Related Code Files
- Create: `app/data/excel_manager.py`
- Create: `app/data/models.py`
- Create: `tests/test_excel_manager.py`

## Implementation Steps
1. Xây dựng model dữ liệu `DeviceRecord` trong `app/data/models.py` chứa:
   - Các trường cấu hình ONT (`so_may`, `slid`, `ppp_account`, `ppp_pass`, `wifi24_ssid`, `wifi24_pass`, `wifi5_ssid`, `wifi5_pass`).
   - Các cờ kiểm tra hợp lệ (`is_valid`, `validation_errors`).
   - Các trường theo dõi trạng thái (`status`, `start_time`, `end_time`, `error_message`, `raw_row_data`).
2. Viết class `ExcelManager` trong `app/data/excel_manager.py`:
   - Hàm `load_file(file_path)`: dùng `openpyxl` đọc dữ liệu, ép kiểu cột `SO_MAY` thành chuỗi ký tự (`str`).
   - Hàm `map_and_validate(rows, default_wifi5_ssid, default_wifi5_pass)`:
     * Kiểm tra trường bắt buộc `Account`, `SO_MAY`.
     * Kiểm tra `Mật khẩu PPPoE`: nếu trống, gán `is_valid = False` và thông báo `"Thiếu mật khẩu PPPoE - Yêu cầu nhập tay"`.
     * Kiểm tra SLID: ưu tiên `Mã MEN`, nếu trống lấy `SO_MAY`. Nếu độ dài $> 10$ ký tự, cảnh báo hoặc cắt gọn hợp lệ.
     * Kiểm tra ký tự cấm `&|;$><`\!'"` trên mọi trường dữ liệu.
     * Quy tắc Wi-Fi 5G kế thừa Wi-Fi 2.4G (giới hạn độ dài $\le 32$).
   - Hàm `save_checkpoint(records, checkpoint_path)`: ghi JSON trạng thái sau mỗi thiết bị.
   - Hàm `load_checkpoint(checkpoint_path)`: khôi phục tiến độ khi mở lại ứng dụng.
   - Hàm `export_results(output_path, records)`: tạo file Excel kết quả, bọc trong khối `try ... except PermissionError` để cảnh báo nếu file đang bị mở.
3. Viết unit test trong `tests/test_excel_manager.py` đọc trực tiếp file mẫu `file-mau.xlsx` kiểm tra phát hiện 13 dòng thiếu pass PPPoE và 51 dòng SLID từ SO_MAY.

## Success Criteria
- [x] Đọc thành công file `file-mau.xlsx` với 89 dòng, giữ nguyên định dạng số máy có số 0 ở đầu (ví dụ `001560828`).
- [x] Phát hiện chính xác 13 dòng thiếu mật khẩu PPPoE trong `file-mau.xlsx` và chặn không cho chạy với mật khẩu rác.
- [x] Kiểm tra chuẩn xác độ dài SLID $\le 10$ ký tự và loại trừ các ký tự shell nguy hiểm.
- [x] Cơ chế checkpoint ghi nhận tức thì sau từng máy, khôi phục 100% dữ liệu khi khởi động lại.
- [x] Xử lý mượt mà ngoại lệ khóa file khi xuất báo cáo ra Excel.

## Risk Assessment
- **Risk:** Kỹ thuật viên không có mật khẩu PPPoE cho các dòng thiếu trong file Excel.
  - *Observable Signal:* Hàng được đánh dấu màu vàng cam `LỖI_DỮ_LIỆU (Thiếu pass PPPoE)`.
  - *Mitigation:* Cung cấp tính năng chỉnh sửa nhanh (inline edit) ô mật khẩu PPPoE trực tiếp trên bảng hoặc nút "Bỏ qua dòng này" để tiếp tục cấu hình các dòng hợp lệ khác.
- **Risk:** File Excel xuất báo cáo bị trùng tên và đang được mở trong Microsoft Excel.
  - *Observable Signal:* Ngoại lệ `PermissionError: [Errno 13] Permission denied`.
  - *Mitigation:* Bắt lỗi, hiển thị hộp thoại gợi ý: "File đang mở trong Excel. Vui lòng đóng file hoặc lưu với tên khác: [Tên file]_1.xlsx".
