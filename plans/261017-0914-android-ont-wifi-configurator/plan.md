---
title: "Ứng dụng Android Native cấu hình ONT VNPT iGate GW040-NSv2 qua Wi-Fi từ file Excel"
description: "Ứng dụng Android Native (Kotlin + Jetpack Compose) tự động bắt Wi-Fi mặc định của ONT, cho phép chọn đúng ma_tb từ file Excel, kiểm tra/chỉnh sửa tham số trước khi RUN, và tối ưu hóa đổi tên/mật khẩu Wi-Fi 2.4G ở bước cuối cùng kèm xử lý đứt kết nối thông minh."
status: completed
priority: P1
effort: "16h"
issue: null
branch: main
tags: [android, kotlin, jetpack-compose, wifi, ont, excel, luci]
blockedBy: []
blocks: []
created: 2026-10-17
---

# Kế Hoạch: Xây Dựng Ứng Dụng Android Native Cấu Hình ONT VNPT iGate GW040-NSv2 Qua Wi-Fi

## Overview
Xây dựng ứng dụng di động **Android Native (Kotlin + Jetpack Compose)** trong thư mục `android-app/`, giúp kỹ thuật viên (KTV) hiện trường cấu hình thiết bị ONT **VNPT iGate GW040-NSv2** hoàn toàn không dây qua sóng Wi-Fi mặc định của ONT sau khi khởi động.

Khác với bản Desktop Windows (chạy tuần tự 1-by-1 qua cáp mạng LAN), bản Android Native được thiết kế chuyên biệt cho thao tác cơ động tại nhà thuê bao:
1. **Nạp danh sách cấu hình từ file Excel (`.xlsx` qua SAF)** vào cơ sở dữ liệu nội bộ (**Room SQLite**, bảo toàn nguyên vẹn 30 cột gốc) để tra cứu offline tức thì (`< 5ms`), kèm bộ dữ liệu mẫu ẩn danh (`sample-demo.xlsx`) để trải nghiệm nhanh mà không lộ PII thuê bao thật trong gói APK.
2. **Tìm kiếm và chọn đích danh `ma_tb` (`Account` / `SO_MAY` / `TEN_TB` / `SO_DT` chuẩn hóa số `0` đầu)** cần đổ cấu hình.
3. **Hiển thị màn hình xác nhận & chỉnh sửa trực tiếp (Review & Edit)** toàn bộ tham số của `ma_tb` đã chọn (SLID/Mã MEN, Account/Pass PPPoE, SSID/Pass Wi-Fi 2.4G & 5G, Pass Admin ONT) kèm bộ kiểm tra hợp lệ 2 tầng (`validateOnImport` & `validateBeforeRun`) trước khi bấm **RUN (Đổ cấu hình)**.
4. **Tự động tìm bắt Wi-Fi mặc định của ONT (`GW040-NSv2_2.4G_XXXXXX` / `GW040-NSv2_5G_XXXXXX`)**, ép định tuyến socket HTTP qua giao diện Wi-Fi nội bộ (`TRANSPORT_WIFI` + `removeCapability(NET_CAPABILITY_INTERNET)`, bỏ qua mạng di động 4G/5G đang bật trên máy) và tự động gỡ `bindProcessToNetwork(null)` trong `finally`.
5. **Tối ưu hóa trình tự cấu hình qua Wi-Fi (Dynamic Band Ordering + Mandatory `finally { logout.cmd }`):** Đưa bước đổi tên & mật khẩu Wi-Fi của băng tần đang kết nối (2.4GHz) xuống **bước cuối cùng (Bước 7)** sau khi băng tần không kết nối (5GHz ở Bước 6) đã lưu và nghỉ ổn định driver `2.0s`, bảo vệ giải phóng phiên `logout.cmd` trong `finally` nếu lỗi giữa chừng (chống khóa `"user exist"` 60s) và xác thực hoàn tất qua **Passive Beacon Verification** tuân thủ giới hạn quét Wi-Fi của Android 10–14.
---

## Brainstorm Contract (Hợp Đồng Thiết Kế Đã Chốt)

- **Outcome:** App Android Native (`android-app/`) đọc file Excel mẫu, cho phép KTV chọn đích danh `ma_tb`, xem/sửa tham số trên giao diện, tự động bắt Wi-Fi mặc định của ONT và nạp cấu hình an toàn với bước đổi Wi-Fi 2.4G ở cuối cùng.
- **Constraints:**
  - Wi-Fi mặc định của ONT GW040-NSv2 khi boot là mạng **Open** (`GW040-NSv2_2.4G_<6-hex-MAC>` và `GW040-NSv2_5G_<6-hex-MAC>`), không có Internet nên phải dùng `WifiNetworkSpecifier` + `NetworkRequest` (`removeCapability(NET_CAPABILITY_INTERNET)`) + `wifiNetwork.socketFactory` và gỡ `bindProcessToNetwork(null)` trong `finally`.
  - Tuân thủ nghiêm ngặt giao thức LuCI CGI của GW040-NSv2: kiểm soát `numberLogin < 2` chống khóa phần cứng 180s (và nhớ trạng thái đã đổi pass theo BSSID để thử `Passwd2@` trước khi Retry), giải phóng phiên `logout.cmd` trong `finally` (`NonCancellable`), SLID $\le 10$ ký tự ASCII, chặn đủ 11 shell metacharacters (`&|;$><`\!'"` bao gồm cả `\`), URL-encode + `ontEncodeQuery` cho các tham số form, cấu hình WAN PPPoE lũy thừa (`wancfg.json` $\rightarrow$ `editwan.json` với `serviceId=1` / `addwan.json` với `serviceId=0` $\rightarrow$ `sendwan.cmd`).
  - Không triển khai tính năng Upgrade Firmware từ file có sẵn trong phạm vi lần này.
- **Non-goals:**
  - Không chạy batch tự động tuần tự toàn bộ danh sách như bản Windows.
  - Không phụ thuộc server trung gian ngoài Internet (hoạt động 100% offline tại nhà khách hàng).
- **Acceptance Criteria:**
  - Import thành công `file-mau.xlsx` (89 dòng, phát hiện đúng 13 dòng thiếu `Pass PPPoE` ở `validateOnImport`), giữ nguyên số `0` ở đầu `SO_MAY` (`001560828`), tìm kiếm tức thì theo `Account` (`ma_tb`), `SO_MAY`, `TEN_TB`, `SO_DT`.
  - Hiển thị đầy đủ cấu hình của `ma_tb` khi chọn; cho phép sửa mọi trường dịch vụ và chặn nút `RUN` qua `validateBeforeRun` nếu thiếu `Pass PPPoE`, vi phạm độ dài `SLID`, hoặc `Pass Wi-Fi < 8` ký tự (như 5 dòng mật khẩu 7 số trong `file-mau.xlsx`).
  - Khi bấm `RUN`, tạm dừng thăm dò nền qua `Mutex`, giữ sáng màn hình (`FLAG_KEEP_SCREEN_ON`) và thực thi thành công 7 bước qua Wi-Fi trong khối `try ... finally { logout.cmd }`: (1) Probe Gateway & Check HTML Signature $\rightarrow$ (2) Smart Login & Đổi Pass Admin $\rightarrow$ (3) WAN PPPoE VLAN 11 (`serviceId=1` khi Edit) $\rightarrow$ (4) GPON SLID $\rightarrow$ (5) LAN IPv4 $\rightarrow$ (6) Cấu hình Wi-Fi băng tần không kết nối (5G) + nghỉ ổn định driver `2.0s` $\rightarrow$ (7) Cấu hình Wi-Fi băng tần đang kết nối (2.4G) ở bước cuối cùng + xử lý đứt sóng & xác nhận SSID mới.
  - Lưu trạng thái hoàn tất vào Room DB (không bị mất khi nạp lại file Excel) và xuất lại được file báo cáo `.xlsx` giữ đủ 30 cột gốc + ghi đè các ô KTV đã sửa + 3 cột kết quả (đã che mật khẩu nhạy cảm) để chia sẻ qua Zalo/Email.
---

## Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | Xây dựng tầng dữ liệu Excel & Room SQLite trên Android, hỗ trợ import `file-mau.xlsx`, tìm kiếm nhanh theo `ma_tb` và xuất báo cáo `.xlsx` | P1 |
| 2 | Xây dựng `OntWifiConnector` & `OntMobileDriver` hỗ trợ bắt Wi-Fi mặc định ONT, bind socket nội bộ (bypass 4G/5G) và tối ưu hóa đổi Wi-Fi 2.4G ở bước cuối cùng | P1 |
| 3 | Xây dựng giao diện Jetpack Compose trực quan: chọn `ma_tb`, màn hình xác nhận/sửa cấu hình trước khi RUN, theo dõi tiến độ 7 bước và cảnh báo âm thanh/rung | P1 |
| 4 | Xây dựng bộ Unit Test + MockWebServer mô phỏng LuCI CGI (bao gồm kịch bản đứt kết nối Wi-Fi ở bước cuối) và cấu hình đóng gói APK | P1 |

---

## System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                 Android Native UI (Jetpack Compose)                  │
├──────────────────────────────────┬───────────────────────────────────┤
│ 1. SubscriberListScreen          │ 2. ConfigReviewScreen             │
│ - Import file-mau.xlsx (SAF)     │ - Load đúng cấu hình của ma_tb    │
│ - Search: Account/SO_MAY/TEN_TB  │ - Cho phép sửa trực tiếp:         │
│ - Filter: Chờ / Xong / Lỗi       │   SLID, PPPoE, Wi-Fi 2.4G & 5G    │
│ - Chọn 1 ma_tb để cấu hình       │ - Validate realtime trước khi RUN │
├──────────────────────────────────┴───────────────────────────────────┤
│ 3. ExecutionBottomSheet & Wi-Fi Scanner Bar                          │
│ - Tự động tìm & bắt Wi-Fi mặc định: GW040-NSv2_2.4G_XXXXXX           │
│ - Tiến độ 7 bước + Rung/Chuông báo hoàn tất + Xuất Excel qua Zalo    │
│ - Footer: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186  │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ (Kotlin Coroutines / StateFlow)
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│               Core Mobile Networking & ONT Protocol Layer            │
├──────────────────────────────────────────────────────────────────────┤
│ A. OntWifiConnector (Android ConnectivityManager + WifiManager)      │
│    - Quét SSID mặc định ^GW040-NSv2_(2\.4G|5G)_([0-9A-Fa-f]{6})$     │
│    - WifiNetworkSpecifier + wifiNetwork.socketFactory + Unbind Safe  │
│    - Scan-Throttle-Safe Beacon Verification (Xác nhận SSID mới)      │
│ B. OntMobileDriver (OkHttpClient + Unsafe SSL + LuCI CGI Protocol)   │
│    - Bọc toàn trình trong try ... finally { logout.cmd (NonCancel) } │
│    - Bước 1: Pause UI Probe Mutex + Probe 192.168.1.1 (Signature)    │
│    - Bước 2: Smart Login (Ưu tiên Passwd2@ nếu Retry) & Đổi Pass     │
│    - Bước 3: WAN PPPoE VLAN 11 Idempotent (editwan serviceId=1)      │
│    - Bước 4: GPON SLID (gponpasswd.json, <= 10 ASCII, URL-encoded)   │
│    - Bước 5: LAN IPv4 (savelanv4cfg.cmd, khóa mặc định 192.168.1.1)  │
│    - Bước 6: Cấu hình Wi-Fi 5G (wl_unit=1) + nghỉ 2.0s ổn định radio │
│    - Bước 7: Cấu hình Wi-Fi 2.4G (wl_unit=0) ở cuối cùng -> Xử lý   │
│              đứt sóng khi reload radio & xác thực SSID mới           │
└──────────────────────────────────────────────────────────────────────┘

---

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Phase 1: Start](./phase-01-start.md) | Completed |
| 2 | [Phase 2: wifi-binding-and-ont-driver](./phase-02-wifi-binding-and-ont-driver.md) | Completed |
| 3 | [Phase 3: mobile-ui-and-review-flow](./phase-03-mobile-ui-and-review-flow.md) | Completed |
| 4 | [Phase 4: testing-and-apk-packaging](./phase-04-testing-and-apk-packaging.md) | Completed |

---

## Success Criteria

- [x] Khởi tạo thành công project Android Native (`android-app/`) với Kotlin, Jetpack Compose, Room Database, OkHttp3 và bộ đọc/ghi `.xlsx` không vướng lỗi Scoped Storage trên Android 10–14.
- [x] Đọc chính xác 100% dữ liệu từ `file-mau.xlsx` (89 bản ghi), bảo toàn số `0` đầu `SO_MAY`, tự động suy diễn `SSID 5G = {SSID 2.4G} 5G` và `Pass 5G = Pass 2.4G`.
- [x] Cho phép KTV tìm kiếm nhanh và chọn đúng 1 `ma_tb`, hiển thị đầy đủ tham số để xác nhận/chỉnh sửa trực tiếp trên màn hình, chặn bấm `RUN` nếu còn lỗi validation (`Pass PPPoE` rỗng, `SLID > 10` ký tự, chứa shell metacharacters).
- [x] Tìm bắt thành công Wi-Fi mặc định của ONT (`GW040-NSv2_2.4G_XXXXXX`) và định tuyến toàn bộ request `https://192.168.1.1` qua giao diện Wi-Fi ngay cả khi điện thoại đang bật dữ liệu di động 4G/5G.
- [x] Thực thi chuẩn xác chuỗi 7 bước cấu hình trong khối `try ... finally { logout.cmd }` với bước đổi tên & mật khẩu Wi-Fi 2.4G đặt ở **cuối cùng** (Dynamic Band Ordering + nghỉ `2.0s` ổn định driver giữa 2 băng tần), không báo lỗi giả khi radio Wi-Fi khởi động lại.
- [x] Pass toàn bộ bài kiểm thử tự động (Unit Tests cho Excel parser, Validator và MockWebServer LuCI CGI mô phỏng đứt kết nối ở bước cuối).

---

## Red Team Review

### Session — 2026-10-17
**Reviewers:** 3 Hostile Personas (`SecurityAdversary`, `AssumptionDestroyer`, `FailureModeAnalyst`) — Standard Verification Tier (`Fact Checker` + `Contract Verifier`)
**Findings:** 10 (10 accepted, 0 rejected — all backed by codebase `file:line` evidence)
**Severity breakdown:** 5 Critical, 5 High

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | **RT-01:** Thiếu khối bắt buộc `try ... finally { logout.cmd }` (`withContext(NonCancellable)`) khiến các lỗi/hủy giữa chừng ở Bước 2–6 làm treo phiên `admin` và gây lỗi `"user exist"` (khóa 60s) khi KTV bấm chạy lại (`app/core/ont_driver.py:147-151,357-369,454-456`) | Critical | Accept | Phase 2 & Phase 4 |
| 2 | **RT-02:** Giả định `wirelesscfg.json` (`action=Apply`) chỉ ghi NVRAM không chạm tới radio và bỏ qua thời gian nghỉ `2.0s` ổn định driver Broadcom giữa 2 băng tần (`ont-040NSv2-setup.jsonl:361,425`, `app/core/ont_driver.py:325-333`), cùng rủi ro báo `SUCCESS` giả nếu lỗi kết nối trước khi gửi lệnh ở Bước 7 | Critical | Accept | Phase 2, Phase 4 & `plan.md` |
| 3 | **RT-03:** Xung đột giữa quy tắc kiểm tra mật khẩu Wi-Fi `8..63` ký tự khi nạp file và dữ liệu thực tế của `file-mau.xlsx` (có 44 dòng trống mật khẩu Wi-Fi 2.4G và 5 dòng mật khẩu 7 ký tự `3011965`, `3820275`, `5091993`, `4091981`, `8042020`, trong khi `app/data/excel_manager.py:146-187` chỉ báo 13 dòng lỗi thiếu PPPoE Pass) | Critical | Accept | Phase 1, Phase 3 & Phase 4 |
| 4 | **RT-04:** Nguy cơ khóa phần cứng 180s khi Retry ở Bước 2: nếu lần chạy trước đã đổi pass sang `Passwd2@` rồi rớt mạng ở Bước 3–6, việc thử lại `admin/admin` trước sẽ tăng `numberLogin` lên `2` và ném `OntAccountLockoutWarning` trước khi kịp thử `Passwd2@` (`app/core/ont_driver.py:162-166,188-226`) | Critical | Accept | Phase 2 & Phase 3 |
| 5 | **RT-05:** Đóng gói trực tiếp `file-mau.xlsx` (chứa PII, địa chỉ, SĐT, mật khẩu PPPoE và SLID thật của 89 thuê bao) vào `src/main/assets/` của file APK phát hành và thiếu `android:allowBackup="false"` + `maskSensitiveCredentials` (`app/ui/worker.py:16-34`) | Critical | Accept | Phase 1, Phase 3 & Phase 4 |
| 6 | **RT-06:** Lỗi vòng đời mạng Android 10–14: dùng `getActiveNetwork()` trả về mạng 4G/5G thay vì Wi-Fi không Internet, quên gỡ `bindProcessToNetwork(null)` trong `finally` gây mất mạng khi chia sẻ Zalo, sập `WifiNetworkSpecifier` khi tắt màn hình, và chạm giới hạn 4 lần `startScan()` / 2 phút | High | Accept | Phase 2 & Phase 3 |
| 7 | **RT-07:** Lỗi hợp đồng `editwan.json` / `sendwan.cmd` kế thừa từ `app/core/ont_driver.py:260`: phản hồi `wancfg.json` thực tế (`ont-040NSv2-setup.jsonl:248`) không có trường `serviceId` mà chỉ có `idx: 2`, khiến code gửi `serviceId=2` thay vì `serviceId=1` (`ont-040NSv2-setup.jsonl:174`), kèm lỗi nuốt HTTP 4xx/5xx | High | Accept | Phase 2 & Phase 4 |
| 8 | **RT-08:** Thiếu ký tự backslash `\` trong tập 11 ký tự cấm `UNSAFE_SHELL_METACHARS` (do lỗi thoát chuỗi đơn trong `app/core/ont_driver.py:19` so với `ont-040NSv2-setup.jsonl:53`), chưa ràng buộc pass Admin `8..32` ký tự ASCII không khoảng trắng (`ont-040NSv2-setup.jsonl:56`), và chưa URL-encode giá trị form chứa `+`, `%`, `=` | High | Accept | Phase 1, Phase 2 & Phase 4 |
| 9 | **RT-09:** Mất 14/30 cột gốc của `file-mau.xlsx` khi xuất báo cáo từ `SubscriberEntity`, chưa ghi đè các tham số KTV đã sửa vào ô Excel xuất ra (`app/data/excel_manager.py:287-304`), ghi đè mất trạng thái `SUCCESS` khi nạp lại file Excel, và chưa chuẩn hóa số `0` đầu cho `SO_DT` | High | Accept | Phase 1, Phase 3 & Phase 4 |
| 10 | **RT-10:** Xung đột socket đồng thời giữa tiến trình thăm dò nền của `OntWifiStatusBanner` và `OntMobileDriver` trên `uHTTPd` đơn luồng (thiếu `ProbeWorker.pause()` barrier từ `app/ui/worker.py:72,139-151`) và thiếu kiểm tra chữ ký HTML `"iGate"`/`"GW040"`/`"VNPT"` (`app/core/network_probe.py:66`) ở Bước 1 | High | Accept | Phase 2 & Phase 3 |

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-start.md`, `phase-02-wifi-binding-and-ont-driver.md`, `phase-03-mobile-ui-and-review-flow.md`, `phase-04-testing-and-apk-packaging.md`
- **Decision deltas checked:** 10 (`RT-01` through `RT-10`)
- **Reconciled stale references:** 14
- **Unresolved contradictions:** 0
- **Key reconciliations across all files:**
  1. **Xử lý `file-mau.xlsx` & Dữ liệu mẫu (`RT-03`, `RT-05`, `RT-09`):** Đồng bộ giữa `plan.md`, `phase-01`, `phase-03`, `phase-04`: `file-mau.xlsx` (89 dòng thật) được đặt tại `android-app/app/src/test/resources/file-mau.xlsx` cho Unit Test, còn trong APK chỉ nhúng `assets/sample-demo.xlsx` (3 dòng giả lập ẩn danh). Bộ kiểm tra hợp lệ được tách thành 2 tầng (`validateOnImport` bắt đúng 13 dòng thiếu PPPoE Pass; `validateBeforeRun` bắt 5 dòng mật khẩu Wi-Fi 7 số trên màn hình Review kèm nút thêm nhanh số `0` ở đầu). Toàn bộ 30 cột gốc được bảo toàn qua `rawRowJson` + bản lưu `current_source.xlsx`.
  2. **Giao thức LuCI CGI Không Dây & Khối `finally { logout.cmd }` (`RT-01`, `RT-02`, `RT-04`, `RT-07`, `RT-08`, `RT-10`):** Đồng bộ giữa `plan.md`, `phase-02`, `phase-03`, `phase-04`: toàn bộ 7 bước chạy trong `try ... finally { withContext(NonCancellable) { logout() } }`, tạm dừng thăm dò nền qua `Mutex` trước Bước 1, kiểm tra chữ ký HTML `"iGate"`/`"GW040"`/`"VNPT"`, ưu tiên thử `Passwd2@` trước khi Retry trên ONT đã đổi mật khẩu, truyền đúng `serviceId=1` khi Edit WAN VLAN 11, nghỉ `2.0s` ổn định driver sau Bước 6, và xử lý đứt sóng + gỡ `bindProcessToNetwork(null)` sau Bước 7.

---

## Validation Log

### Session 1 — 2026-10-17
**Trigger:** User invoked `/ak:plan validate /home/vtst/ONT-config/plans/261017-0914-android-ont-wifi-configurator/` after Red Team Review.
**Questions asked:** 4

#### Verification Results
- **Tier:** Standard (4 phases — `Fact Checker` + `Contract Verifier`, guard applied post-Red Team Review)
- **Claims checked:** 40 (10 claims/phase verified across `app/core/ont_driver.py`, `app/data/excel_manager.py`, `app/core/network_probe.py`, `app/ui/worker.py`, `ont-040NSv2-setup.jsonl`, and `file-mau.xlsx`)
- **Verified:** 40 | **Failed:** 0 | **Unverified:** 0
- **Remaining `[UNVERIFIED]` tags:** 0

#### Questions & Answers

1. **[Architecture]** `[Architecture] Which .xlsx parser/exporter strategy should Phase 1 use on Android (minSdk 26)?`
   - Options: `Native ZIP + Android XmlPullParser/XmlSerializer (Recommended)` | `Apache POI (poi-ooxml-lite / fastexcel)` | `Dual Export (.xlsx + .csv)`
   - **Answer:** `Native ZIP + Android XmlPullParser/XmlSerializer`
   - **Rationale:** Avoids 15MB Apache POI / XMLBeans bloat and `javax.xml.stream` runtime conflicts on Android 8–14 while parsing all 89 rows and 30 columns of `file-mau.xlsx` (including `xl/sharedStrings.xml` and `xl/styles.xml` zero-padded `SO_MAY` formatting) in `< 15ms` and cloning the original `.xlsx` ZIP archive to patch edited cells + 3 status columns on export.

2. **[Assumptions]** `[Assumptions] file-mau.xlsx has 5 rows with 7-digit Wi-Fi passwords (dates missing leading '0', e.g. '3011965'), which WPA2-PSK rejects (< 8 chars). How should the app handle them?`
   - Options: `1-Tap 'Prepend 0' button on Review Screen (Recommended)` | `Auto-pad leading '0' on Excel Import` | `Fallback to '1234567890'`
   - **Answer:** `1-Tap 'Prepend 0' button on Review Screen`
   - **Rationale:** Preserves 100% data fidelity during Excel import (`validateOnImport` only flags the 13 rows missing PPPoE password, matching `app/data/excel_manager.py`), while `validateBeforeRun` highlights the 7-digit Wi-Fi password in red on `ConfigReviewScreen` (`7/8 ký tự`) with a 1-tap **"Thêm số 0 ở đầu (0...)"** button that updates both 2.4G and 5G passwords before enabling `RUN`.

3. **[Tradeoffs]** `[Tradeoffs] After Step 7 changes the 2.4GHz Wi-Fi name/password and restarts the ONT radio, how should the phone transition its network connection?`
   - Options: `Auto-unbind to 4G/5G + Optional 'Join New Wi-Fi' button (Recommended)` | `Auto-prompt connection to new Wi-Fi SSID` | `Unbind only (manual system Wi-Fi)`
   - **Answer:** `Auto-unbind to 4G/5G + Optional 'Join New Wi-Fi' button`
   - **Rationale:** Automatically calling `bindProcessToNetwork(null)` as soon as Step 7 completes restores the phone's 4G/5G cellular connection immediately (allowing instant Zalo report export without a dead-network blackhole), while offering an optional **"Kết nối vào Wi-Fi mới để kiểm tra mạng"** button on the completion sheet if the technician wants to test customer Internet.

4. **[Scope]** `[Scope] When exporting the .xlsx report to share via Zalo/Email, how should the PPPoE and Wi-Fi password columns be exported?`
   - Options: `Preserve columns for re-import + mask error logs (Recommended)` | `Toggleable PPPoE masking on Export dialog` | `Always mask all passwords in exported .xlsx`
   - **Answer:** `Preserve columns for re-import + mask error logs`
   - **Rationale:** Keeps the exported `.xlsx` 100% compatible with the desktop Windows workflow (`app/data/excel_manager.py`) so exported files preserve all 30 columns plus KTV's updated `Mật khẩu PPPoE` and `Mật khẩu Wi-Fi 2.4G/5G` for re-import/archival, while masking sensitive credentials inside `Ghi chú chi tiết` error messages via `maskSensitiveCredentials()`.

#### Confirmed Decisions
- **Excel Engine:** Native `ZipInputStream`/`ZipOutputStream` + Android `XmlPullParser`/`XmlSerializer` over `xl/sharedStrings.xml`, `xl/styles.xml`, and `xl/worksheets/sheet1.xml` — lightweight APK (`< 5MB`), zero POI conflicts, preserves all 30 columns and `SO_MAY` leading zeros.
- **7-Digit Wi-Fi Passwords (`file-mau.xlsx` Rows 11, 40, 45, 52, 67):** Keep raw cell value on import; block `RUN` via `validateBeforeRun()` on `ConfigReviewScreen` with a 1-tap **"Thêm số 0 ở đầu"** action.
- **Post-Step 7 Network Transition:** Immediately unbind `bindProcessToNetwork(null)` to restore 4G/5G cellular connectivity upon Step 7 completion, and present an optional **"Kết nối vào Wi-Fi mới để kiểm tra mạng"** button on `ExecutionProgressSheet`.
- **Exported `.xlsx` Password Columns:** Write updated `Mật khẩu PPPoE` and `Mật khẩu Wi-Fi 2.4G/5G` in their original columns for re-import compatibility, while masking credentials in `Ghi chú chi tiết` via `maskSensitiveCredentials()`.

#### Action Items
- [x] Update Phase 1 (`phase-01-start.md`) to mandate Native ZIP + `XmlPullParser`/`XmlSerializer` instead of optional Apache POI, and confirm cleartext column write-back + masked `Ghi chú chi tiết` on export.
- [x] Update Phase 2 (`phase-02-wifi-binding-and-ont-driver.md`) to confirm immediate `bindProcessToNetwork(null)` unbind to 4G/5G after Step 7 and optional new-SSID connection action.
- [x] Update Phase 3 (`phase-03-mobile-ui-and-review-flow.md`) to confirm the 1-tap **"Thêm số 0 ở đầu"** button on `ConfigReviewScreen`, post-Step 7 auto-unbind to 4G/5G, and re-import-compatible `.xlsx` export.

#### Impact on Phases
- **Phase 1 (`phase-01-start.md`):** Locked in Native ZIP + `XmlPullParser`/`XmlSerializer` for `.xlsx` read/write and confirmed password column preservation + error-log masking on export.
- **Phase 2 (`phase-02-wifi-binding-and-ont-driver.md`):** Locked in automatic `bindProcessToNetwork(null)` restoration of 4G/5G after Step 7 + optional customer Wi-Fi connection helper.
- **Phase 3 (`phase-03-mobile-ui-and-review-flow.md`):** Locked in 1-tap leading-zero fix for 7-digit Wi-Fi passwords on `ConfigReviewScreen` and optional post-Step 7 Wi-Fi join button on `ExecutionProgressSheet`.

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-start.md`, `phase-02-wifi-binding-and-ont-driver.md`, `phase-03-mobile-ui-and-review-flow.md`, `phase-04-testing-and-apk-packaging.md`
- **Decision deltas checked:** 4 (Validation Session 1 Q1–Q4) + 10 (Red Team `RT-01`–`RT-10`)
- **Reconciled stale references:** 4
- **Unresolved contradictions:** 0

<!-- slug: android-ont-wifi-configurator -->
