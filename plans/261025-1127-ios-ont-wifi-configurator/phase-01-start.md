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
Khởi tạo cấu trúc dự án iOS Native (`ios-app/`, Swift 5.9+ & SwiftUI, iOS 16.0+), xây dựng bộ giải nén/nén ZIP thuần Native bằng `zlib` (`ZipArchive.swift`), trình đọc/xuất file Excel 30 cột (`ExcelManager.swift`), cơ sở dữ liệu nội bộ **SQLite3 Native (`AppDatabase.swift`)**, và bộ kiểm tra hợp lệ 2 tầng (`OntInputValidator.swift`).

## Requirements
- [x] Khởi tạo cấu trúc thư mục `ios-app/ONTWiFiConfigurator/` và `ios-app/ONTWiFiConfiguratorTests/` cùng cấu hình `Info.plist` (khai báo `NSLocalNetworkUsageDescription`, `UIFileSharingEnabled = false`, `ITSAppUsesNonExemptEncryption = false`).
- [x] **[Red Team RT-06]** Xây dựng `ZipArchive.swift` sử dụng thư viện C `zlib` có sẵn trong iOS SDK (`inflateInit2_`, `inflate`, `deflateInit2_`, `deflate`, `crc32`) với **`windowBits = -MAX_WBITS` (`-15`, raw RFC 1951 DEFLATE không có zlib header)** để đọc và ghi các entry trong file `.xlsx` (hỗ trợ cả phương thức nén `STORED = 0` và `DEFLATE = 8` đọc từ Central Directory EOCD). Áp dụng giới hạn an toàn chống Zip Bomb và Path Traversal: tối đa `128` entries, tối đa `16 MB` mỗi entry giải nén, tổng dung lượng giải nén tối đa `32 MB`, và từ chối mọi entry chứa `..` hoặc bắt đầu bằng `/`.
- [x] **[Red Team RT-04 & RT-06]** Xây dựng `ExcelManager.swift` bằng `ZipArchive` + `Foundation.XMLParser` (`shouldResolveExternalEntities = false`):
  - Đọc `xl/sharedStrings.xml` (bỏ qua thẻ phiên âm `<rPh>` và ghép nối đầy đủ các đoạn `foundCharacters` khi gặp numeric XML character entities như `&#7893;`), `xl/styles.xml` (nhận diện `numFmtId` hoặc `formatCode` có số `0` đầu như `000000000`) và `xl/worksheets/sheet1.xml` (hỗ trợ cả `t="s"`, `t="inlineStr"` và ô số có zero-pad style với điều kiện `!rawVal.isEmpty && rawVal.allSatisfy(\.isNumber)` để không biến ô rỗng thành `"000000000"`).
  - Bảo toàn tuyệt đối toàn bộ **30 cột gốc** và các chuỗi có số `0` ở đầu (`SO_MAY` như `001560828`, `SO_DT`, `Mã MEN`).
  - Áp dụng đúng quy tắc nghiệp vụ chuẩn từ `android-app`:
    - Ưu tiên lấy `SLID` từ cột `Mã MEN` (Cột 13), nếu trống mới lấy `SO_MAY` (Cột 9).
    - Nếu `SSID Wi-Fi 2.4G` hoặc `Mật khẩu Wi-Fi 2.4G` trống, sử dụng `effectiveWifi24Ssid = "VNPT_\(soMay)"` và `effectiveWifi24Pass = "1234567890"` (tự động `.trimmingCharacters(in: .whitespacesAndNewlines)`), đồng thời suy diễn `SSID Wi-Fi 5G = "{SSID Wi-Fi 2.4G} 5G"` ($\le 32$ ký tự) và `Mật khẩu Wi-Fi 5G = Mật khẩu Wi-Fi 2.4G`.
  - Khi xuất báo cáo `.xlsx` (`exportExcelReport`): neo thứ tự cột theo danh sách cố định **`STANDARD_30_HEADERS`** (không phụ thuộc thứ tự ngẫu nhiên của `Dictionary.keys` trong Swift), ghi đè các trường KTV đã chỉnh sửa (`SO_MAY`, `Account`, `Mật khẩu PPPoE`, `Mã MEN`, `SSID/Pass 2.4G & 5G`) để file xuất ra có thể nạp lại đồng bộ với bản Android/Windows, thêm **3 cột kết quả** (`Trạng thái cấu hình`, `Thời gian thực hiện`, `Ghi chú chi tiết` đã qua `maskSensitiveCredentials`), và thoát ký tự XML đặc biệt (`escapeXmlText`: `&`, `<`, `>`, `"`, `'`, lọc bỏ ký tự điều khiển XML không hợp lệ).
- [x] **[Red Team RT-10]** Xây dựng `OntInputValidator.swift` tách bạch **2 tầng kiểm tra (Two-Stage Validation)** đồng bộ 100% với `OntInputValidator.kt`:
  - **Tầng 1 — `validateOnImport(record)`:** Kiểm tra `passPppoe` không rỗng, `soMay` và `accountPppoe` không rỗng, `slid` dài `1..10` ký tự ASCII khớp `^[a-zA-Z0-9_\-\.]+$`, chặn mọi ký tự điều khiển ASCII (`< 0x20` hoặc `0x7F`), và chặn đủ **11 ký tự shell metacharacters** (`&|;$><`\!'"` bao gồm cả `\`). Phát hiện đúng **13 dòng thiếu mật khẩu PPPoE** trên `file-mau.xlsx` (89 dòng).
  - **Tầng 2 — `validateBeforeRun(record, settings)`:** Kiểm tra toàn bộ điều kiện Tầng 1 cộng thêm: `SSID 2.4G/5G` dài `1..32` ký tự ASCII in được, `Mật khẩu Wi-Fi 2.4G/5G` dài `8..63` ký tự ASCII in được (`0x20..0x7E`, bắt đúng **5 dòng mật khẩu Wi-Fi 7 ký tự** `3011965`, `3820275`, `5091993`, `4091981`, `8042020` và bật cờ `canPrependZero24g`/`canPrependZero5g`), cùng kiểm tra độ phức tạp mật khẩu Admin mới (`8..32` ký tự ASCII `0x21..0x7E` không khoảng trắng, đủ chữ hoa, chữ thường, số, ký tự đặc biệt).
  - Cung cấp các hàm `ontEncodeQuery(_:)` (thay `&` bằng `\u{007f}`), `urlEncodeFormValue(_:)` (percent-encode nghiêm ngặt chỉ giữ ký tự unreserved `A-Za-z0-9-._~`, mã hóa cả `+`, `&`, `=`, `/`, `?`, khoảng trắng thành `%20`), và `maskSensitiveCredentials(_:)` (che `pppPassword=`, `pass=`, `newPass=`, `oldPass=`, `wl_wpa_psk=`, `password=`, `sessionKey=`, `data=` và các trường JSON nhạy cảm kể cả khi phân tách bằng `&` hoặc `\u{007f}`).
- [x] **[Red Team RT-02 & RT-07]** Xây dựng `AppDatabase.swift` sử dụng thư viện C `SQLite3` có sẵn của iOS:
  - Tuần tự hóa mọi truy cập SQLite qua một hàng đợi riêng `DispatchQueue(label: "vn.vnpt.ontconfig.sqlite")`, mở kết nối với `SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX`, cấu hình `PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=5000;`, và hỗ trợ truyền đường dẫn file tùy chỉnh (hoặc `:memory:`) phục vụ Unit Test độc lập.
  - Lưu trữ bảng `subscribers` (bao gồm cả trường `lastKnownAdminPass`) trong `Application Support/ont_subscribers.sqlite`, đặt `URLResourceValues.isExcludedFromBackup = true` và `FileProtectionType.complete` cho thư mục lưu trữ.
  - Cài đặt `upsertPreservingEdits(records:)` bọc trong giao dịch nguyên tử `BEGIN IMMEDIATE TRANSACTION ... COMMIT` (có `ROLLBACK` nếu lỗi): khi nạp lại file Excel trùng khóa thuê bao, giữ nguyên trạng thái `SUCCESS`, `lastKnownAdminPass` và các mật khẩu PPPoE / Wi-Fi / SLID mà KTV đã sửa tay trước đó.
  - Hỗ trợ tìm kiếm đa trường tức thì theo `Account` (`ma_tb`), `SO_MAY`, `TEN_TB`, `SO_DT` (tự động khớp cả khi KTV gõ có hoặc không có số `0` ở đầu).

## Architecture
```
[file-mau.xlsx (Security-Scoped URL từ SwiftUI fileImporter)]
        │
        ▼
┌────────────────────────────────────────────────────────┐
│ ZipArchive (zlib windowBits=-15) + ExcelManager        │
│ - Giải nén xl/sharedStrings.xml, styles.xml, sheet1.xml│
│ - Bảo toàn số 0 đầu (SO_MAY, SO_DT, Mã MEN) & 30 cột  │
│ - Neo thứ tự xuất file theo STANDARD_30_HEADERS        │
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
│ AppDatabase (Serial Queue SQLite3, WAL, Excluded iCloud│
│ - Lưu 16 trường chính + lastKnownAdminPass + rawRowJson│
│ - Atomic BEGIN IMMEDIATE TRANSACTION khi Merge Excel   │
│ - Search chuẩn hóa số 0 đầu cho SO_DT & SO_MAY         │
└────────────────────────────────────────────────────────┘
```

## Related Code Files
- Create: `ios-app/ONTWiFiConfigurator/Info.plist`
- Create: `ios-app/ONTWiFiConfigurator/Resources/sample-demo.xlsx`
- Create: `ios-app/ONTWiFiConfigurator/Data/Model/SubscriberRecord.swift`
- Create: `ios-app/ONTWiFiConfigurator/Data/Model/GlobalSettings.swift`
- Create: `ios-app/ONTWiFiConfigurator/Core/Validation/OntInputValidator.swift`
- Create: `ios-app/ONTWiFiConfigurator/Data/Excel/ZipArchive.swift`
- Create: `ios-app/ONTWiFiConfigurator/Data/Excel/ExcelManager.swift`
- Create: `ios-app/ONTWiFiConfigurator/Data/Local/AppDatabase.swift`

## Implementation Steps
1. Tạo cấu trúc thư mục `ios-app/ONTWiFiConfigurator` và khai báo `Info.plist` kèm `sample-demo.xlsx` (3 bản ghi mẫu ẩn danh từ `android-app/app/src/main/assets/sample-demo.xlsx`).
2. Định nghĩa `SubscriberRecord.swift` (bao gồm `lastKnownAdminPass` và các thuộc tính `effectiveWifi*` đã `.trimmingCharacters(in: .whitespacesAndNewlines)`), `SubscriberStatus`, và `GlobalSettings.swift`.
3. Cài đặt `OntInputValidator.swift` với đầy đủ các quy tắc kiểm tra `validateOnImport`, `validateBeforeRun`, `prependLeadingZeroToWifiPass`, `urlEncodeFormValue`, `ontEncodeQuery`, và `maskSensitiveCredentials`.
4. Cài đặt `ZipArchive.swift` (raw DEFLATE `windowBits = -15`, giới hạn chống Zip Bomb) và `ExcelManager.swift` (`XMLParser` với `shouldResolveExternalEntities = false`, bảo toàn zero-pad cho ô không rỗng, và xuất `.xlsx` theo `STANDARD_30_HEADERS`).
5. Cài đặt `AppDatabase.swift` bằng `SQLite3` C API chạy trên serial `DispatchQueue` với `WAL` mode, `BEGIN IMMEDIATE TRANSACTION` cho `upsertPreservingEdits`, tìm kiếm chuẩn hóa số `0` đầu và khóa `isExcludedFromBackup = true`.

## Todo
- [x] Khởi tạo cấu trúc `ios-app/ONTWiFiConfigurator`, `Info.plist` và `sample-demo.xlsx`
- [x] Xây dựng `SubscriberRecord.swift`, `GlobalSettings.swift` và `OntInputValidator.swift`
- [x] Xây dựng `ZipArchive.swift` (`windowBits = -15`) và `ExcelManager.swift` đọc/ghi `.xlsx` 30 cột
- [x] Xây dựng `AppDatabase.swift` (Serial Queue SQLite3 + `upsertPreservingEdits` + `isExcludedFromBackup`)

## Success Criteria
- [x] Đọc chính xác 89 dòng của cả 2 bản `file-mau.xlsx` (`sharedStrings.xml` và `inlineStr`), bảo toàn số `0` đầu `SO_MAY` (`001560828`), phát hiện đúng 13 dòng thiếu mật khẩu PPPoE ở `validateOnImport()`, và bắt đúng 5 dòng mật khẩu Wi-Fi 7 ký tự ở `validateBeforeRun()`.
- [x] Tìm kiếm theo `ma_tb` (`Account`, `SO_MAY`, `TEN_TB`, hoặc `SO_DT` có/không có số `0` đầu) trả về kết quả chính xác tức thì.
- [x] Xuất báo cáo `.xlsx` giữ nguyên đúng thứ tự 30 cột gốc (`STANDARD_30_HEADERS`) + cập nhật các ô KTV đã sửa + 3 cột trạng thái (che mật khẩu trong ghi chú lỗi).

## Risk Assessment
- **Risk:** `zlib` mặc định (`windowBits = 15`) kỳ vọng header 2-byte zlib nên sẽ báo lỗi `Z_DATA_ERROR (-3)` khi giải nén raw DEFLATE trong `.xlsx`; hoặc `"".allSatisfy(\.isNumber)` biến các ô trống có style số thành `"000000000"`.
  - *Observable Signal:* Lỗi giải nén file `.xlsx` hoặc cột `Mã MEN` trống bị điền `"000000000"`.
  - *Mitigation:* Truyền `windowBits = -MAX_WBITS` (`-15`) vào `inflateInit2_` / `deflateInit2_` trong `ZipArchive.swift`, và luôn kiểm tra `!rawVal.isEmpty && rawVal.allSatisfy(\.isNumber)` trước khi zero-pad trong `ExcelManager.swift`.
