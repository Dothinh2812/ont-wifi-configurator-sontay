---
phase: 2
title: "Phase 2: mobile-android-and-ios-unification"
status: completed
priority: P1
effort: "1.5h"
dependencies: [1]
---

# Phase 2: Bản Di Động Android (`android-app/`) & iOS (`ios-app/`) — Bỏ Tham Số `Số máy`, Hợp Nhất Về `SLID`

## Overview
Loại bỏ hoàn toàn ô nhập liệu `"Số máy (SO_MAY)"` và các nhãn hiển thị/kiểm tra hợp lệ `"Số máy"` dư thừa trên cả hai ứng dụng di động **Android Native (Kotlin + Jetpack Compose)** và **iOS Native (Swift + SwiftUI)**, hợp nhất về tham số duy nhất là **`SLID`** đồng bộ 100% với bản Desktop Windows.

## Requirements
- **Functional:**
  - **Màn hình Kiểm tra & Sửa cấu hình (`ConfigReviewScreen.kt` trên Android & `ConfigReviewView.swift` trên iOS):**
    - Xóa bỏ ô nhập liệu `"Số máy (SO_MAY)"` khỏi phần thông số GPON & WAN PPPoE.
    - **[Red Team RT-05]** Chỉ giữ lại duy nhất ô nhập **`SLID / Mã MEN (GPON Password)`** ($\le 10$ ký tự ASCII). Khi KTV chỉnh sửa `SLID` từ `oldSlid` sang `newSlid`, tự động cập nhật đồng bộ `slid = newSlid`, `soMay = newSlid`, và nếu `ssid24g` đang rỗng hoặc đang mang giá trị mặc định cũ (`"VNPT_${oldSlid}"` / `"VNPT_${record.soMay}"`) thì tự động cập nhật `ssid24g = "VNPT_${newSlid.trim()}"` (đồng thời đồng bộ sang `ssid5g` nếu `autoSyncWifi5g` đang bật).
    - Thanh tiêu đề phụ (`TopAppBar` trên Android) và điều kiện kiểm tra SSID mặc định (`isUsingDefaultMenSsid`) hiển thị/so khớp theo `record.slid` (`ma_tb: ${record.accountPppoe} • SLID: ${record.slid}`).
  - **Màn hình Danh sách Thuê bao (`SubscriberListScreen.kt` trên Android & `SubscriberListView.swift` trên iOS):**
    - Thẻ thuê bao hiển thị `ma_tb` cùng `SLID` (`record.slid`), không hiển thị lặp lại cả `Số máy` và `SLID`.
    - Ô tìm kiếm cập nhật nhãn và gợi ý thành `"Tìm ma_tb, SLID, Tên TB, SĐT..."` / `"Nhập Account, SLID hoặc SĐT (có/không số 0)"`.
  - **Tầng Dữ liệu, Kiểm tra Hợp lệ & Driver (`ExcelManager`, `OntInputValidator`, `SubscriberRecord`, `SubscriberEntity`, `SubscriberDao`, `AppDatabase`, `OntMobileDriver` trên Android & iOS):**
    - **[Red Team RT-04]** `OntInputValidator.validateOnImport`: xóa kiểm tra trùng lặp `"Thiếu Số máy định danh"` và xóa `("Số máy", record.soMay)` khỏi `fieldsToCheck`; chỉ kiểm tra trên `SLID`. Bổ sung che `data=` vào `SENSITIVE_PATTERNS` trong `OntInputValidator.kt` đồng bộ với iOS.
    - **[Red Team RT-07]** `ExcelManager.parseExcel`: đọc `maMen` (Cột 13) và `soMay` (Cột 9), tính `slid = maMen.ifBlank { soMay }` và gán `soMay = slid` **trước** khi kiểm tra bỏ qua dòng trống (`sttVal.isEmpty() && slid.isEmpty() && accountPppoe.isEmpty()`), tạo fallback SSID 2.4G bằng `"VNPT_$slid"`.
    - **[Red Team RT-02 & RT-07]** `ExcelManager.searchSubscribers`, `recordKey`, `mergeRecordsPreservingEdits`, `SubscriberEntity.kt`, `AppDatabase.swift`, và `SubscriberDao.kt`: tìm kiếm theo `slid` có/không có số `0` đầu (dùng `LTRIM(LOWER(slid), '0')` trong `SubscriberDao.kt`); chuẩn hóa `slid` và `soMay` khi đọc/ghi SQLite; trong `mergeRecordsPreservingEdits` bổ sung tra cứu dự phòng theo `(rowIndex, accountPppoe.lowercase())` và `rawRowData["SO_MAY"]` khi KTV đã sửa `SLID`, đồng thời gán `soMay = merged.slid`.
    - **[Red Team RT-06]** `ExcelManager.exportResults`: khi xuất file `.xlsx` 30 cột gốc, ghi `rec.slid` vào cả hai cột `"SO_MAY"` và `"Mã MEN"` để file xuất ra luôn đồng nhất.
    - **[Red Team RT-06]** `SubscriberRecord.effectiveWifi24Ssid` (Android & iOS) và `OntMobileDriver.recordCacheKey` (trên iOS `OntMobileDriver.swift`; bản Android cache theo BSSID): sử dụng `slid`.
  - **Tài liệu & Kiểm thử (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`, Unit Tests):**
    - Cập nhật tài liệu hướng dẫn và bộ kiểm thử của Android, iOS và parity test.
- **Non-functional:**
  - Giữ nguyên cấu trúc bảng SQLite (`subscribers`) trên Room (Android) và SQLite3 (iOS) để không gây lỗi migration dữ liệu đang lưu trên máy của KTV.

## Architecture
```
┌──────────────────────────────────────────────────────────────────────────┐
│                    Bản Di Động (Android & iOS Native)                    │
├──────────────────────────────────────────────────────────────────────────┤
│ 1. ExcelManager (Kotlin & Swift)                                         │
│    - Đọc: slid = Mã MEN (Cột 13) nếu có, ngược lại lấy SO_MAY (Cột 9)    │
│    - Đồng bộ: soMay = slid, fallback SSID 2.4G = "VNPT_{slid}"           │
│    - Xuất: Ghi rec.slid vào cả cột "SO_MAY" và "Mã MEN"                  │
├──────────────────────────────────────────────────────────────────────────┤
│ 2. OntInputValidator (Kotlin & Swift)                                    │
│    - Chỉ kiểm tra duy nhất SLID (1..10 ASCII, không shell metachar)      │
│    - Loại bỏ kiểm tra trùng lặp "Thiếu Số máy định danh"                 │
├──────────────────────────────────────────────────────────────────────────┤
│ 3. Giao Diện KTV (SubscriberList & ConfigReview)                         │
│    - SubscriberList: Tìm & hiển thị theo Account (ma_tb) + SLID          │
│    - ConfigReview: Bỏ ô "Số máy (SO_MAY)", chỉ giữ 1 ô "SLID" duy nhất  │
└──────────────────────────────────────────────────────────────────────────┘
```

## Related Code Files
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/ConfigReviewScreen.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/ui/screens/SubscriberListScreen.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/model/SubscriberRecord.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/excel/ExcelManager.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/local/SubscriberDao.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/data/local/SubscriberEntity.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/validation/OntInputValidator.kt`
- Modify: `android-app/app/src/main/java/vn/vnpt/ontconfig/core/driver/OntMobileDriver.kt`
- Modify: `android-app/app/src/test/java/vn/vnpt/ontconfig/ExcelAndValidatorTest.kt`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Screens/ConfigReviewView.swift`
- Modify: `ios-app/ONTWiFiConfigurator/UI/Screens/SubscriberListView.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Data/Model/SubscriberRecord.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Data/Local/AppDatabase.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Data/Excel/ExcelManager.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Validation/OntInputValidator.swift`
- Modify: `ios-app/ONTWiFiConfigurator/Core/Driver/OntMobileDriver.swift`
- Modify: `ios-app/ONTWiFiConfiguratorTests/ExcelAndValidatorTests.swift`
- Modify: `ios-app/ONTWiFiConfiguratorTests/OntMobileDriverTests.swift`
- Modify: `tests/test_ios_app_parity.py`
- Modify: `README.md`
- Modify: `HUONG_DAN_SU_DUNG_TOOL_ONT.md`

## Implementation Steps
1. **[Red Team RT-02, RT-04, RT-05, RT-06, RT-07] Cập nhật Bản Android (`android-app/`):**
   - Trong `ConfigReviewScreen.kt`: xóa `OutlinedTextField` của `"Số máy (SO_MAY)"`; giữ lại ô nhập `"SLID / Mã MEN (${record.slid.length}/10)"` (khi sửa từ `oldSlid` sang `newSlid`, cập nhật `slid = newSlid, soMay = newSlid` và nếu `record.ssid24g.isBlank() || record.ssid24g == "VNPT_${oldSlid}" || record.ssid24g == "VNPT_${record.soMay}"` thì cập nhật `ssid24g = "VNPT_${newSlid.trim()}"` kèm `ssid5g` khi `autoSyncWifi5g = true`); đổi subtitle trên `TopAppBar` thành `"ma_tb: ${record.accountPppoe} • SLID: ${record.slid}"` và `isUsingDefaultMenSsid` so khớp `"VNPT_${record.slid}"`.
   - Trong `SubscriberListScreen.kt`: đổi dòng tiêu đề thẻ thuê bao thành `"ma_tb: ${record.accountPppoe} • SLID: ${record.slid}"`, dòng thông tin Wi-Fi bên dưới hiển thị `"Wi-Fi 2.4G: ${record.effectiveWifi24Ssid}  •  5G: ${record.effectiveWifi5Ssid}"`, và cập nhật nhãn tìm kiếm.
   - Trong `SubscriberRecord.kt`, `SubscriberEntity.kt`, `ExcelManager.kt`, `SubscriberDao.kt`, `OntInputValidator.kt`: tính `slid` trước khi kiểm tra dòng trống, dùng `slid` cho `effectiveWifi24Ssid`, `recordKey` (kèm fallback `(rowIndex, accountPppoe)` trong `mergeRecordsPreservingEdits` và đồng bộ `soMay = merged.slid`), tìm kiếm có/không có số `0` đầu (`LTRIM(LOWER(slid), '0')` trong `SubscriberDao.kt`), chuẩn hóa `soMay = slid` trong `SubscriberEntity`, bỏ kiểm tra `"Thiếu Số máy định danh"` trong `validateOnImport`, thêm regex che `data=` trong `OntInputValidator.kt`, và khi `exportResults` ghi `rec.slid` vào cả `"SO_MAY"` lẫn `"Mã MEN"`.
2. **[Red Team RT-02, RT-05, RT-06, RT-07] Cập nhật Bản iOS (`ios-app/`):**
   - Trong `ConfigReviewView.swift`: xóa khối `VStack` của `"Số máy (SO_MAY)"` (dòng 64–81); trong binding của ô `"SLID / Mã MEN (GPON Password)"`, khi sửa `newVal` thì gán cả `updated.slid = newVal`, `updated.soMay = newVal`, và tự động cập nhật `updated.ssid24g = "VNPT_\(newVal.trimmingCharacters(in: .whitespacesAndNewlines))"` (kèm `ssid5g` nếu đang bật đồng bộ) khi `ssid24g` đang rỗng hoặc bằng `"VNPT_\(oldSlid)"`.
   - Trong `SubscriberListView.swift`: đổi tiêu đề dòng thuê bao thành `"\(record.accountPppoe.isEmpty ? "(Thiếu Account)" : record.accountPppoe) — SLID: \(record.slid)"` và cập nhật placeholder tìm kiếm thành `"Tìm Account, SLID (0015...), Tên TB, SĐT..."`.
   - Trong `SubscriberRecord.swift`, `AppDatabase.swift`, `ExcelManager.swift`, `OntInputValidator.swift`, `OntMobileDriver.swift`: tính `slid` trước kiểm tra dòng trống, dùng `slid` cho `effectiveWifi24Ssid`, `recordKey` (kèm fallback `(rowIndex, accountPppoe)` trong `mergeRecordsPreservingEdits` và đồng bộ `merged.soMay = merged.slid`), `recordCacheKey`, tìm kiếm `searchSubscribers`, bỏ lỗi `"Thiếu Số máy định danh"` trong `validateOnImport`, và khi `exportResults` ghi `rec.slid` vào cả `"SO_MAY"` lẫn `"Mã MEN"`.
3. **[Red Team RT-06] Cập nhật Kiểm thử & Tài liệu (`ExcelAndValidatorTest.kt`, `ExcelAndValidatorTests.swift`, `OntMobileDriverTests.swift`, `tests/test_ios_app_parity.py`, `README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`):**
   - Cập nhật `ExcelAndValidatorTest.kt:228` kiểm tra `assertEquals("NEW_SLID01", reParsed[0].soMay)` (xác nhận cả `SO_MAY` và `Mã MEN` đều được cập nhật thành `NEW_SLID01` khi xuất file), cập nhật `OntMobileDriverTests.swift:394` gán `freshDifferentRecord.slid = "009999999"`, và cập nhật tài liệu hướng dẫn.

## Todo
- [x] Loại bỏ ô nhập `Số máy` và hợp nhất logic `SLID` trên Android (`ConfigReviewScreen.kt`, `SubscriberListScreen.kt`, `ExcelManager.kt`, `OntInputValidator.kt`, `SubscriberRecord.kt`)
- [x] Loại bỏ ô nhập `Số máy` và hợp nhất logic `SLID` trên iOS (`ConfigReviewView.swift`, `SubscriberListView.swift`, `ExcelManager.swift`, `OntInputValidator.swift`, `SubscriberRecord.swift`)
- [x] Cập nhật Unit Tests (`ExcelAndValidatorTest.kt`, `ExcelAndValidatorTests.swift`, `tests/test_ios_app_parity.py`) và tài liệu (`README.md`, `HUONG_DAN_SU_DUNG_TOOL_ONT.md`)
- [x] Chạy kiểm thử xác nhận trên toàn bộ dự án

## Success Criteria
- [x] Màn hình `ConfigReviewScreen.kt` (Android) và `ConfigReviewView.swift` (iOS) chỉ còn duy nhất ô nhập `SLID`, không còn ô `Số máy (SO_MAY)`.
- [x] Sửa `SLID` trên màn hình Review và xuất lại file `.xlsx` cập nhật đúng giá trị `SLID` mới vào cả cột `SO_MAY` và `Mã MEN`.
- [x] Bộ kiểm thử `pytest` (bao gồm `tests/test_ios_app_parity.py`) và Android unit test chạy PASS.

## Risk Assessment
- **Rủi ro lệch dữ liệu khi KTV sửa `SLID` rồi xuất lại file Excel:**
  - *Tín hiệu nhận biết:* Cột `Mã MEN` trong file xuất ra có giá trị mới nhưng cột `SO_MAY` vẫn giữ giá trị cũ, khiến lần nạp lại sau bị lệch nếu cột `Mã MEN` bị xóa.
  - *Đối sách định sẵn:* Khi người dùng sửa `SLID` trên UI, cập nhật đồng thời `soMay = newSlid` và trong `exportResults` ghi `rec.slid` cho cả hai cột `"SO_MAY"` và `"Mã MEN"`.
