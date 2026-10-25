---
title: "Ứng dụng iOS Native (Swift + SwiftUI) cấu hình ONT VNPT iGate GW040-NSv2 qua Wi-Fi & Đóng gói .ipa tự động"
description: "Xây dựng ứng dụng iOS Native (Swift + SwiftUI) trong thư mục ios-app/ tương đương 100% tính năng bản Android Native: đọc/xuất file Excel 30 cột, lưu SQLite3 offline, ép định tuyến HTTP qua card Wi-Fi en0 khi bật 4G/5G, tối ưu hóa đổi tên/mật khẩu Wi-Fi ở bước cuối cùng (Bước 7/7), kèm GitHub Actions đóng gói file .ipa Sideload miễn phí."
status: completed
priority: P1
effort: "14h"
issue: null
branch: main
tags: [ios, swift, swiftui, wifi, ont, excel, luci, ipa, github-actions]
blockedBy: []
blocks: []
created: 2026-10-25
---

# Kế Hoạch: Xây Dựng Ứng Dụng iOS Native Cấu Hình ONT VNPT iGate GW040-NSv2 & Đóng Gói `.ipa`

## Overview
Xây dựng ứng dụng di động **iOS Native (Pure Swift 5.9+ & SwiftUI, iOS 16.0+)** độc lập hoàn toàn trong thư mục `ios-app/`, giữ nguyên 100% mã nguồn và gói cài đặt của `android-app/`. Ứng dụng giúp kỹ thuật viên (KTV) sử dụng iPhone tại hiện trường cấu hình thiết bị ONT **VNPT iGate GW040-NSv2** không dây qua sóng Wi-Fi mặc định của ONT:

1. **Đọc/Ghi file Excel (`.xlsx`) thuần Native (`ZipArchive` zlib raw DEFLATE `windowBits = -15` + `XMLParser`):** Không phụ thuộc bất kỳ thư viện bên thứ 3 nào (CocoaPods/SPM), đảm bảo gói `.app` đơn khối không chứa dynamic framework gây lỗi chữ ký khi Sideload bằng Apple ID miễn phí (Sideloadly / AltStore / TrollStore / ESign). Bảo toàn toàn bộ 30 cột gốc (theo thứ tự chuẩn `STANDARD_30_HEADERS`) và số `0` ở đầu của `SO_MAY`, `SO_DT`, `Mã MEN`.
2. **Lưu trữ nội bộ SQLite3 Native (`AppDatabase.swift`):** Tuần tự hóa truy cập qua hàng đợi riêng (`serial DispatchQueue` + `SQLITE_OPEN_FULLMUTEX` + `PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000`), đặt `URLResourceValues.isExcludedFromBackup = true` và `FileProtectionType.complete` (chống sao lưu dữ liệu thuê bao lên iCloud), hỗ trợ tìm kiếm tức thì (`< 5ms`) theo `Account` (`ma_tb`), `SO_MAY`, `TEN_TB`, `SO_DT` (chuẩn hóa số `0` đầu) và **Merge thông minh** trong `BEGIN IMMEDIATE TRANSACTION` giữ lại trạng thái `SUCCESS`, `lastKnownAdminPass` cùng các chỉnh sửa của KTV khi nạp lại file Excel.
3. **Màn hình xác nhận & chỉnh sửa trực tiếp (`ConfigReviewView`) với kiểm tra hợp lệ 2 tầng (`validateOnImport` & `validateBeforeRun`):** Tự động suy diễn `SSID 5G = {SSID 2.4G} 5G` và `Pass 5G = Pass 2.4G`, hỗ trợ nút 1 chạm **"Thêm số 0 ở đầu (0...)"** cho các mật khẩu Wi-Fi 7 chữ số bị mất số `0` đầu trong Excel, hiển thị chọn nhanh băng tần đang kết nối (`2.4GHz` / `5GHz`) ngay trên màn hình Review trước nút **RUN**, và khóa nút **RUN** nếu còn lỗi dữ liệu.
4. **Định tuyến mạng nội bộ qua card Wi-Fi `en0` vượt qua 4G/5G (`IosWifiMonitor` + `UnsafeSslSession`):** Kiểm tra trực tiếp địa chỉ IPv4 `192.168.1.x` trên giao diện mạng Wi-Fi `"en0"` (`getifaddrs`), cảnh báo nếu phát hiện VPN (`utun*`) đang chiếm quyền định tuyến, chờ DHCP cấp IP (`awaitWifiIpv4Ready` tối đa `1200ms`), và cấu hình `URLSessionConfiguration.ephemeral` với `allowsCellularAccess = false`, chặn toàn bộ HTTP Redirect (`301/302/307/308`) để ép lưu lượng `https://192.168.1.1` luôn đi qua sóng Wi-Fi ONT ngay cả khi iPhone đang bật dữ liệu di động 4G/5G.
5. **Trình tự 7 bước LuCI CGI tối ưu hóa không dây (`OntMobileDriver.swift`):** Xin quyền chạy nền ngắn hạn (`UIApplication.shared.beginBackgroundTask`), tạm dừng thăm dò nền qua `AsyncMutex.tryLock()`, bọc toàn trình trong khối đảm bảo gọi `logout.cmd` qua `Task.detached` khi lỗi giữa chừng (chống khóa `"user exist"` 60s), kiểm soát `numberLogin < 2` chống khóa phần cứng 180s + lưu `lastKnownAdminPass` theo từng bản ghi thuê bao (`rowIndex`, `soMay`), đặt bước đổi tên/mật khẩu Wi-Fi của băng tần đang kết nối ở **bước cuối cùng (Bước 7/7)** với `settlingDelayMs = 200ms` (giữ kết nối ấm từ Bước 6 kèm `ensureGatewayReadyBeforeStep7` khi retry), và theo dõi `totalBytesSent >= expectedBytes` qua `URLSessionTaskDelegate` để phân biệt chính xác hiện tượng rớt socket sau khi gửi lệnh radio reload ở Bước 7 với lỗi timeout kết nối trước khi gửi.
6. **Đóng gói tự động gói `.ipa` Sideload miễn phí qua GitHub Actions (`.github/workflows/build-ios-ipa.yml`):** Tự động sinh/kiểm tra `.xcodeproj`, chạy bộ Unit Test XCTest trên iOS Simulator, biên dịch bản `Release` (`iphoneos`, `CODE_SIGNING_ALLOWED=NO`), kiểm tra bảo mật đảm bảo không nhúng `file-mau.xlsx` (chứa PII thật) vào `Payload/ONTWiFiConfigurator.app`, ký ad-hoc Mach-O (`codesign --force --sign -`) và đóng gói thành `ONT_WiFi_Configurator_Unsigned.ipa` kèm `SHA256SUMS.txt`.

---

## Brainstorm Contract (Hợp Đồng Thiết Kế Đã Chốt)

- **Outcome:** Dự án `ios-app/` (Pure Swift + SwiftUI) đầy đủ tính năng tương đương 100% bản Android Native (`android-app/`) kèm workflow `.github/workflows/build-ios-ipa.yml` xuất file `ONT_WiFi_Configurator_Unsigned.ipa` cài đặt miễn phí qua Sideloadly / AltStore / TrollStore / ESign.
- **Constraints:**
  - Không xâm phạm hay chỉnh sửa mã nguồn của `android-app/`.
  - Không dùng Entitlement trả phí (`NEHotspotConfiguration` / `wifi-info`) trong cấu hình mặc định để tương thích 100% với công cụ Sideload bằng Apple ID miễn phí.
  - Không dùng thư viện ngoài (CocoaPods/SPM) để tránh lỗi ký nhúng dynamic framework khi Sideload và đảm bảo tốc độ build CI `< 2 phút`.
  - Ép định tuyến `https://192.168.1.1` qua card Wi-Fi `en0` (`allowsCellularAccess = false` + xác thực `en0` subnet `192.168.1.x` + chặn HTTP Redirect), chấp nhận chứng chỉ SSL tự ký duy nhất cho host `192.168.1.1` / `localhost` / `127.0.0.1`.
  - Tuân thủ nghiêm ngặt giao thức LuCI CGI của GW040-NSv2: `numberLogin < 2`, `logout.cmd` bắt buộc khi thoát/lỗi, chặn đủ 11 shell metacharacters (`&|;$><`\!'"`) + chặn ký tự điều khiển ASCII (`< 0x20` hoặc `0x7F`), `SLID <= 10` ký tự ASCII, WAN PPPoE `serviceId=1` khi Edit / `serviceId=0` khi Add, đổi Wi-Fi băng tần đang kết nối ở Bước 7 cuối cùng (`settlingDelayMs = 200ms` + `ensureGatewayReadyBeforeStep7` + xác nhận `totalBytesSent`).
- **Non-goals:**
  - Không triển khai tính năng nạp Firmware (`tclinux.bin.signed`) qua Wi-Fi.
  - Không chạy batch tự động hàng loạt nhiều thiết bị.
- **Acceptance Criteria:**
  - Parse chính xác cả 2 định dạng `file-mau.xlsx` (`xl/sharedStrings.xml` + `xl/styles.xml` zero-pad và `t="inlineStr"` trong `ONTWiFiConfiguratorTests/Resources/file-mau.xlsx`: 89 bản ghi, phát hiện đúng 13 dòng thiếu `Pass PPPoE` ở `validateOnImport`, bắt đúng 5 dòng mật khẩu Wi-Fi 7 số ở `validateBeforeRun`, giữ nguyên số `0` đầu `SO_MAY` như `001560828`).
  - Pass 100% bộ kiểm thử tự động (`ExcelAndValidatorTests`, `OntMobileDriverTests` với `MockLuciURLProtocol`, và `tests/test_ios_app_parity.py`).
  - Dự án Xcode (`ios-app/ONTWiFiConfigurator.xcodeproj`), script `ios-app/scripts/package_unsigned_ipa.sh` và workflow `.github/workflows/build-ios-ipa.yml` hợp lệ, sẵn sàng xuất file `ONT_WiFi_Configurator_Unsigned.ipa`.

---

## Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | Xây dựng cấu trúc dự án iOS Native (`ios-app/`), bộ đọc/ghi `.xlsx` thuần Native (`ZipArchive` raw DEFLATE `windowBits = -15` + `XMLParser`), cơ sở dữ liệu `AppDatabase` (SQLite3 tuần tự hóa an toàn) và `OntInputValidator` 2 tầng | P1 |
| 2 | Xây dựng `IosWifiMonitor` (kiểm tra IP `en0`, phát hiện VPN `utun*`, chờ DHCP `awaitWifiIpv4Ready`), `UnsafeSslSession` (chặn Redirect, theo dõi `totalBytesSent`) và `OntMobileDriver` thực thi 7 bước LuCI CGI với bước đổi Wi-Fi đang kết nối ở cuối cùng | P1 |
| 3 | Xây dựng giao diện SwiftUI (`SubscriberListView`, `ConfigReviewView`, `ExecutionProgressSheet`, `WifiGuideSheet`, `FooterSignatureBar`) có bảo vệ `beginBackgroundTask`, che mờ màn hình nhạy cảm khi chạy nền, và chọn nhanh băng tần `2.4GHz / 5GHz` | P1 |
| 4 | Xây dựng bộ Unit Test (`ExcelAndValidatorTests`, `OntMobileDriverTests`), bài test kiểm chứng `tests/test_ios_app_parity.py` trên Linux, cấu hình `.xcodeproj` / `project.yml` và GitHub Actions đóng gói `.ipa` | P1 |

---

## System Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                   iOS Native UI (SwiftUI - iOS 16+)                  │
├──────────────────────────────────┬───────────────────────────────────┤
│ 1. SubscriberListView            │ 2. ConfigReviewView               │
│ - Import .xlsx (fileImporter)    │ - Xem & sửa tham số của ma_tb     │
│ - Nạp dữ liệu mẫu Demo ẩn danh   │ - Nút 1 chạm thêm '0' cho Pass 7  │
│ - Search: Account/SO_MAY/TEN_TB  │ - Chọn băng tần: 2.4GHz / 5GHz    │
│ - Filter: Chờ / Xong / Lỗi       │ - Validate realtime trước khi RUN │
├──────────────────────────────────┴───────────────────────────────────┤
│ 3. ExecutionProgressSheet & WifiGuideSheet                           │
│ - Kiểm tra IP Wi-Fi en0 (192.168.1.x), cảnh báo VPN & mở Cài đặt iOS │
│ - beginBackgroundTask + isIdleTimerDisabled + Privacy Blur           │
│ - Tiến độ 7 bước + Âm báo/Haptic + Share .xlsx (Zalo, tự xóa tmp)    │
│ - Footer: Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186  │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ (Swift Concurrency async/await + @MainActor)
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│               Core iOS Networking & ONT Protocol Layer               │
├──────────────────────────────────────────────────────────────────────┤
│ A. IosWifiMonitor (Darwin getifaddrs("en0") + NWPathMonitor)         │
│    - Phát hiện IP 192.168.1.x trên card Wi-Fi en0 + awaitWifiIpv4Ready│
│    - allowsCellularAccess = false + Block HTTP Redirect              │
│    - Theo dõi totalBytesSent qua URLSessionTaskDelegate              │
│ B. OntMobileDriver (URLSession + UnsafeSslDelegate + LuCI CGI)       │
│    - Bọc toàn trình trong Task.detached { logout.cmd } khi kết thúc  │
│    - Bước 1: Pause UI Probe (tryLock) + Probe 192.168.1.1 (Signature)│
│    - Bước 2: Smart Login (lastKnownAdminPass theo bản ghi) & Đổi Pass│
│    - Bước 3: WAN PPPoE VLAN 11 Idempotent (editwan serviceId=1)      │
│    - Bước 4: GPON SLID (gponpasswd.json, <= 10 ASCII, URL-encoded)   │
│    - Bước 5: LAN IPv4 (savelanv4cfg.cmd, khóa mặc định 192.168.1.1)  │
│    - Bước 6: Cấu hình Wi-Fi băng tần phụ + settlingDelayMs (200ms)   │
│    - Bước 7: Cấu hình Wi-Fi băng tần đang kết nối ở cuối cùng        │
│              + ensureGatewayReadyBeforeStep7 + Xử lý rớt sóng        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Phase 1: Start](./phase-01-start.md) | Completed |
| 2 | [Phase 2: wifi-en0-routing-and-ont-driver](./phase-02-wifi-en0-routing-and-ont-driver.md) | Completed |
| 3 | [Phase 3: swiftui-screens-and-review-flow](./phase-03-swiftui-screens-and-review-flow.md) | Completed |
| 4 | [Phase 4: testing-and-github-actions-ipa](./phase-04-testing-and-github-actions-ipa.md) | Completed |

---

## Success Criteria

- [x] Khởi tạo hoàn chỉnh dự án iOS Native trong `ios-app/` (gồm đầy đủ mã nguồn Swift, `Info.plist`, `sample-demo.xlsx`, `project.yml` và `ONTWiFiConfigurator.xcodeproj` chuẩn Xcode 15/16) mà không phụ thuộc thư viện bên thứ 3.
- [x] Bộ đọc/ghi `.xlsx` (`ZipArchive.swift` với `windowBits = -15` + `ExcelManager.swift`) đọc chính xác 89 bản ghi của `file-mau.xlsx` (hỗ trợ cả `xl/sharedStrings.xml` + `xl/styles.xml` lẫn `t="inlineStr"`), bảo toàn số `0` đầu `SO_MAY` (`001560828`), điền đúng fallback Wi-Fi 2.4G/5G và xuất lại file `.xlsx` giữ đúng thứ tự 30 cột gốc (`STANDARD_30_HEADERS`) + 3 cột kết quả (đã che mật khẩu nhạy cảm).
- [x] `OntInputValidator.swift` kiểm tra chính xác 2 tầng (`validateOnImport` bắt 13 dòng thiếu `Pass PPPoE`; `validateBeforeRun` bắt 5 dòng mật khẩu Wi-Fi 7 số, chặn `SLID > 10` ký tự, chặn ký tự điều khiển ASCII và đủ 11 ký tự shell metacharacters).
- [x] `IosWifiMonitor.swift` và `OntMobileDriver.swift` định tuyến toàn bộ request `https://192.168.1.1` qua giao diện Wi-Fi (`allowsCellularAccess = false` + xác thực subnet `en0`), thực thi chuẩn xác chuỗi 7 bước với bước đổi Wi-Fi đang kết nối ở cuối cùng (`settlingDelayMs = 200ms`, `ensureGatewayReadyBeforeStep7`, kiểm tra `totalBytesSent`) và luôn gọi `logout.cmd` khi lỗi giữa chừng.
- [x] Workflow `.github/workflows/build-ios-ipa.yml` và script đóng gói `ios-app/scripts/package_unsigned_ipa.sh` tự động chạy Unit Tests, kiểm tra loại trừ `file-mau.xlsx` khỏi bundle production và đóng gói thành công `ONT_WiFi_Configurator_Unsigned.ipa` kèm `SHA256SUMS.txt`.

---

## Red Team Review

### Session — 2026-10-25
**Reviewers:** 3 Hostile Personas (`SecurityAdversary`, `AssumptionDestroyer`, `FailureModeAnalyst`) — Standard Verification Tier (`Fact Checker` + `Contract Verifier`)
**Findings:** 10 (10 accepted, 0 rejected — all backed by codebase `file:line` evidence)
**Severity breakdown:** 5 Critical, 5 High

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| RT-01 | Step 6/7 Radio Reload Timing (`settlingDelayMs = 200ms`), `ensureGatewayReadyBeforeStep7`, and `URLSessionTaskDelegate` `totalBytesSent` Verification for `URLError.timedOut` | Critical | Accept | `plan.md`, `phase-02` |
| RT-02 | Scope In-Memory Password Cache by `(rowIndex, soMay)` (Never Global IP `192.168.1.1`) & Persist `lastKnownAdminPass` in `AppDatabase.swift` to Prevent 180s Lockout | Critical | Accept | `plan.md`, `phase-01`, `phase-02` |
| RT-03 | Wrap 7-Step Setup in `UIApplication.beginBackgroundTask` & Guard Step 7 Against Background Socket Aborts to Prevent 60s `"user exist"` Lock and False Success | Critical | Accept | `plan.md`, `phase-02`, `phase-03` |
| RT-04 | Bundle Test Fixture `ONTWiFiConfiguratorTests/Resources/file-mau.xlsx` (`inlineStr` 7-digit Passwords) AND Verify Root `file-mau.xlsx` (`sharedStrings.xml` + `styles.xml`) | Critical | Accept | `plan.md`, `phase-01`, `phase-04` |
| RT-05 | Block HTTP Redirects (`301/302/307/308`) in `UnsafeSslSession`, Restrict TLS Trust Host Override to Localhost/Tests, and Detect Active VPN (`utun*`) | Critical | Accept | `plan.md`, `phase-02` |
| RT-06 | `ZipArchive.swift` Raw-DEFLATE `windowBits = -15`, Decompression Caps, `XMLParser` `shouldResolveExternalEntities = false`, Non-Empty Zero-Pad Guard, and `STANDARD_30_HEADERS` Export Order | High | Accept | `plan.md`, `phase-01` |
| RT-07 | Serialize Raw C `SQLite3` Calls via Serial `DispatchQueue` + `SQLITE_OPEN_FULLMUTEX` + `WAL` + Atomic `BEGIN IMMEDIATE TRANSACTION` in `AppDatabase.swift` | High | Accept | `plan.md`, `phase-01` |
| RT-08 | Guard `NWPathMonitor` / `scenePhase` Auto-Probes via Non-Blocking `probePauseMutex.tryLock()` & Poll `awaitWifiIpv4Ready` (`1200ms`) on `"en0"` | High | Accept | `plan.md`, `phase-02`, `phase-03` |
| RT-09 | Inject `protocolClasses` into Both Primary & `1.5s` Logout Ephemeral `URLSession`s, Drain `httpBodyStream` in `MockLuciURLProtocol`, and Isolate `XCTest` Host App Launch | High | Accept | `plan.md`, `phase-02`, `phase-04` |
| RT-10 | Surface `2.4GHz / 5GHz` Selector on `ConfigReviewView`, Enforce Printable ASCII & Trimmed `effectiveWifi*`, Use Expiring Local-Only Pasteboard + Background Blur, and Assert `file-mau.xlsx` Exclusion in `.ipa` | High | Accept | `plan.md`, `phase-01`, `phase-03`, `phase-04` |

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-start.md`, `phase-02-wifi-en0-routing-and-ont-driver.md`, `phase-03-swiftui-screens-and-review-flow.md`, `phase-04-testing-and-github-actions-ipa.md`
- **Decision deltas checked:** 10 (`RT-01` through `RT-10`)
- **Reconciled stale references:** 10 (replaced `settlingDelayMs = 2.5s` default in `MainViewModel` with `200ms` warm-session execution + `ensureGatewayReadyBeforeStep7`; replaced global `gatewayPasswordCache` with per-record `(rowIndex, soMay)` + persisted `lastKnownAdminPass`; added `totalBytesSent` verification before classifying `URLError.timedOut` as post-flush radio drop; synchronized `windowBits = -15`, `STANDARD_30_HEADERS` column ordering, `SQLite3` serial queue + transaction, `protocolClasses` injection, and dual `file-mau.xlsx` test coverage across all phases)
- **Unresolved contradictions:** 0

<!-- slug: ios-ont-wifi-configurator -->
