---
phase: 1
title: "Phase 1: Start"
status: completed
priority: P1
effort: "4h"
dependencies: []
---

# Phase 1: Start

## Overview
Khởi tạo cấu trúc dự án Android Native (`android-app/`, Kotlin + Jetpack Compose + Gradle Kotlin DSL), xây dựng tầng dữ liệu đọc/ghi file Excel (`file-mau.xlsx`), lưu trữ offline vào **Room SQLite Database**, và cài đặt bộ kiểm tra hợp lệ dữ liệu nghiêm ngặt (`OntInputValidator`).

## Requirements
- [x] Khởi tạo project Android (`minSdk = 26` Android 8.0, `targetSdk = 34` Android 14) sử dụng Kotlin, Jetpack Compose (Material 3), Coroutines, Room SQLite, OkHttp3.
- [x] <!-- Updated: Validation Session 1 - Use Native ZIP + Android XmlPullParser/XmlSerializer --> Xây dựng bộ đọc/xuất file `.xlsx` (`ExcelManager`) bằng **Native `ZipInputStream`/`ZipOutputStream` + Android `XmlPullParser`/`XmlSerializer`** (đọc `xl/sharedStrings.xml`, `xl/styles.xml`, `xl/worksheets/sheet1.xml` mà không phụ thuộc Apache POI, giúp APK `< 5MB`, không lỗi `javax.xml.stream` và đọc 89 dòng của `file-mau.xlsx` trong `< 15ms`), bảo toàn tuyệt đối toàn bộ 30 cột và số `0` ở đầu cột `SO_MAY`.
- [x] Áp dụng quy tắc nghiệp vụ chuẩn từ bản Windows (`app/data/excel_manager.py` & `app/core/ont_driver.py`):
  - Ưu tiên lấy `SLID` từ cột `Mã MEN` (Cột 13), nếu trống mới lấy `SO_MAY` (Cột 9).
  - **[Red Team RT-03]** Nếu `SSID Wi-Fi 2.4G` hoặc `Mật khẩu Wi-Fi 2.4G` trong Excel để trống (44 dòng trong `file-mau.xlsx`), tự động điền giá trị mặc định `SSID 2.4G = "VNPT_${soMay}"` và `Pass 2.4G = "1234567890"` (đồng bộ `app/core/ont_driver.py:414-415`), sau đó suy diễn `SSID Wi-Fi 5G = "{SSID Wi-Fi 2.4G} 5G"` (cắt gọn $\le 32$ ký tự) và `Mật khẩu Wi-Fi 5G = Mật khẩu Wi-Fi 2.4G`.
- [x] **[Red Team RT-03 & RT-08]** Cài đặt `OntInputValidator` tách bạch **2 tầng kiểm tra (Two-Stage Validation)**:
  - **Tầng 1 — `validateOnImport(record)` (Khi nạp file Excel):** Đồng bộ 100% với `app/data/excel_manager.py:146-187`: kiểm tra `Mật khẩu PPPoE` không rỗng, `SO_MAY` và `Account PPPoE` không rỗng, `SLID` độ dài `1..10` ký tự ASCII khớp `^[a-zA-Z0-9_\-\.]+$`, và chặn đủ **11 ký tự shell metacharacters** (`&|;$><`\!'"` — bao gồm cả dấu gạch chéo ngược `\`, sửa lỗi thoát chuỗi đơn trong `app/core/ont_driver.py:19` theo đúng `ont-040NSv2-setup.jsonl:53`). Đảm bảo `file-mau.xlsx` (89 dòng) phát hiện đúng **13 dòng thiếu mật khẩu PPPoE**.
  - **Tầng 2 — `validateBeforeRun(record, globalSettings)` (Trên màn hình `ConfigReviewScreen` trước khi bấm `RUN`):** Kiểm tra toàn bộ điều kiện Tầng 1 cộng thêm: `SSID 2.4G/5G` dài `1..32` ký tự, `Mật khẩu Wi-Fi 2.4G/5G` dài `8..63` ký tự (bắt chính xác **5 dòng có mật khẩu Wi-Fi 7 ký tự** `3011965`, `3820275`, `5091993`, `4091981`, `8042020` trong `file-mau.xlsx` để KTV thêm số `0` ở đầu hoặc sửa trước khi `RUN`), và mật khẩu Admin mới dài `8..32` ký tự ASCII in được (`0x21..0x7E`, không chứa khoảng trắng, gồm chữ hoa, chữ thường, số, ký tự đặc biệt theo `ont-040NSv2-setup.jsonl:56`).
  - Cài đặt hàm `maskSensitiveCredentials(text)` (đồng bộ `app/ui/worker.py:16-34`) để che mật khẩu (`pppPassword=`, `pass=`, `newPass=`, `oldPass=`, `wl_wpa_psk=`, `sessionKey=`) trước khi lưu `errorMessage` hoặc xuất báo cáo.
- [x] **[Red Team RT-05 & RT-09]** Lưu trữ danh sách thuê bao vào Room Database (`SubscriberDao`, `SubscriberEntity`) và bảo toàn nguyên vẹn 30 cột Excel:
  - Sao lưu bản gốc file `.xlsx` vừa nạp qua SAF vào `context.filesDir/current_source.xlsx` đồng thời lưu `rawRowJson` (toàn bộ 30 cột gốc) trong `SubscriberEntity`.
  - Khi nạp lại file Excel trùng `soMay`/`accountPppoe`, thực hiện **Merge thông minh**: giữ nguyên trạng thái `SUCCESS` và các mật khẩu PPPoE / tham số mà KTV đã nhập tay trước đó (tránh ghi đè mất tiến độ ca làm việc).
  - Tìm kiếm tức thì (`< 5ms`) theo `Account` (`ma_tb`), `SO_MAY`, `TEN_TB`, `SO_DT` (tự động chuẩn hóa bỏ số `0` đầu khi tìm `SO_DT` vì `file-mau.xlsx` lưu dạng `978194878`), `DIACHI_TB`, hoặc lọc theo `NVKT` và trạng thái cấu hình.
  - <!-- Updated: Validation Session 1 - Preserve password columns for re-import while masking error logs --> Khi xuất báo cáo `.xlsx` mới: giữ nguyên đủ **30 cột gốc**, **ghi đè các tham số KTV đã chỉnh sửa** (`SO_MAY`, `Account`, `Mật khẩu PPPoE`, `Mã MEN`, `SSID/Pass 2.4G & 5G` giữ nguyên giá trị thực để file xuất ra có thể nạp lại/lưu trữ đồng bộ với bản Windows) vào đúng cột tương ứng, thêm 3 cột (`Trạng thái cấu hình`, `Thời gian thực hiện`, `Ghi chú chi tiết` đã qua `maskSensitiveCredentials`), và chia sẻ qua Zalo/Email bằng `FileProvider` (cấu hình `android:allowBackup="false"` trong `AndroidManifest.xml`).
## Architecture
```
[file-mau.xlsx (Uri via Storage Access Framework)]
        │ (InputStream on Dispatchers.IO)
        ▼
┌────────────────────────────────────────────────────────┐
│ ExcelParser & Exporter                                 │
│ - Đọc header linh hoạt theo tên cột (fallback index)   │
│ - Bảo toàn chuỗi SO_MAY & Mã MEN                       │
│ - Suy diễn tự động SSID 5G & Pass 5G                   │
└───────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────┐
│ OntInputValidator (Two-Stage Validation)               │
│ - Stage 1 (Import): PPPoE Pass, SLID <= 10, 11 Metachar│
│ - Stage 2 (Pre-RUN): Wi-Fi SSID (1..32) & Pass (8..63),│
│   Admin Pass (8..32 ASCII, no spaces)                  │
│ - maskSensitiveCredentials() cho log & báo cáo lỗi     │
└───────────────────────┬────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────┐
│ Room SQLite Database (AppDatabase / SubscriberDao)     │
│ - Lưu 16 trường chính + rawRowJson (đủ 30 cột gốc)     │
│ - Merge giữ trạng thái SUCCESS & chỉnh sửa khi nạp lại │
│ - Search chuẩn hóa số 0 đầu cho SO_DT & SO_MAY         │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Create: `android-app/settings.gradle.kts`
- Create: `android-app/build.gradle.kts`
- Create: `android-app/app/build.gradle.kts`
- Create: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/SubscriberRecord.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/GlobalSettings.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/local/SubscriberEntity.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/local/SubscriberDao.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/local/AppDatabase.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/excel/ExcelManager.kt`
- Create: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`

## Implementation Steps
1. Tạo cấu trúc Gradle project trong `android-app/` với Kotlin 1.9+, Jetpack Compose Material 3, Room, Coroutines, OkHttp3 và thư viện xử lý `.xlsx`.
2. Định nghĩa `SubscriberEntity` và `SubscriberRecord` chứa đầy đủ các trường nghiệp vụ (`rowIndex`, `stt`, `toDiaBan`, `nvkt`, `tenTb`, `diachiTb`, `soDt`, `soMay`, `accountPppoe` (`ma_tb`), `passPppoe`, `maMen`, `slid`, `ssid24g`, `pass24g`, `ssid5g`, `pass5g`, `status`, `startTime`, `endTime`, `errorMessage`, `lastKnownAdminPass`) kèm trường `rawRowJson: String` bảo toàn toàn bộ 30 cột của `file-mau.xlsx`. Cấu hình `AndroidManifest.xml` với `android:allowBackup="false"` và `android:dataExtractionRules`.
3. Cài đặt `OntInputValidator.kt` gồm `validateOnImport()`, `validateBeforeRun()`, `maskSensitiveCredentials()`, `urlEncodeFormValue()`, và `ontEncodeQuery()` (chặn đủ 11 ký tự `&|;$><`\!'"` kể cả `\`).
4. Cài đặt `ExcelManager.kt` đọc file `.xlsx` từ `InputStream` (lưu bản đệm vào `context.filesDir/current_source.xlsx`; ở môi trường thực tế KTV chọn file qua SAF hoặc nạp file demo 3 dòng giả lập `assets/sample-demo.xlsx` đã ẩn thông tin nhạy cảm, **tuyệt đối không nhúng dữ liệu thật `file-mau.xlsx` vào APK production**) và xuất báo cáo `.xlsx` giữ đủ 30 cột gốc + cập nhật các ô KTV đã sửa + 3 cột kết quả.
5. Cài đặt `SubscriberDao` với truy vấn tìm kiếm đa trường (chuẩn hóa `soDt` có/không có số `0` ở đầu) và cơ chế `upsertPreservingEdits()` khi nạp lại file Excel.
## Todo
- [x] Khởi tạo Gradle project `android-app/` và khai báo dependencies
- [x] Xây dựng `OntInputValidator.kt` và hàm `ontEncodeQuery`
- [x] Xây dựng `ExcelManager.kt` đọc/ghi `.xlsx` và suy diễn Wi-Fi 5G
- [x] Xây dựng `SubscriberEntity`, `SubscriberDao`, `AppDatabase` (Room SQLite)

## Success Criteria
- Parse chính xác 89 dòng của `file-mau.xlsx` trong bộ kiểm thử (`src/test/resources/file-mau.xlsx`), bảo toàn số `0` đầu `SO_MAY` (`001560828`), phát hiện đúng 13 dòng thiếu mật khẩu PPPoE ở `validateOnImport()`, và bắt đúng 5 dòng có mật khẩu Wi-Fi 7 ký tự ở `validateBeforeRun()`.
- Truy vấn tìm kiếm theo `ma_tb` (`Account`, `SO_MAY`, hoặc `SO_DT` có/không có số `0` đầu) trả về kết quả chính xác trong `< 5ms`.
- Cập nhật chỉnh sửa tham số của 1 thuê bao lưu bền vững vào Room SQLite, không bị mất khi nạp lại file Excel, và xuất ra file `.xlsx` giữ nguyên đủ 30 cột gốc + 3 cột trạng thái.

## Risk Assessment
- **Risk:** Thư viện Apache POI đầy đủ gây nặng APK và lỗi xung đột `javax.xml.stream`; hoặc bộ đọc XML tự viết bỏ sót định dạng số có số `0` ở đầu (`xl/styles.xml`) và làm rơi 14/30 cột khi xuất file.
  - *Observable Signal:* Lỗi `Duplicate class` khi build APK, hoặc cột `SO_MAY` bị đọc thành `1560828` thay vì `001560828`, hoặc file xuất ra thiếu cột.
  - *Mitigation:* <!-- Updated: Validation Session 1 - Confirmed Native ZIP + XmlPullParser/XmlSerializer engine --> Sử dụng quyết định kiến trúc **Native ZIP + Android `XmlPullParser`/`XmlSerializer`** kết hợp lưu bản sao file `.xlsx` gốc vào `context.filesDir/current_source.xlsx` và `rawRowJson` (30 cột) trong Room; xử lý đầy đủ `sharedStrings.xml` + `styles.xml` (giữ nguyên chuỗi gốc/formatting cho `SO_MAY`) và clone ZIP gốc để cập nhật `sheet1.xml` khi xuất, bảo toàn 100% 30 cột gốc.
