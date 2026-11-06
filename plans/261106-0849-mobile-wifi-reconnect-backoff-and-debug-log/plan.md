---
title: "Tăng Thời Gian Chờ Sẵn Sàng ONT, Bổ Sung Retry Giãn Cách Khi Kết Nối Lại Wi-Fi & Hệ Thống Ghi Log Debug Trên App Mobile (Android & iOS)"
description: "Khắc phục lỗi thực địa tại Bước 7 (kết nối lại Wi-Fi sau khi đổi tên Lần 1) bằng cách nâng quỹ thời gian chờ, bổ sung cửa sổ ổn định sau khi thấy sóng (beacon != ONT ready), cơ chế retry giãn cách lũy tiến 2 tầng (OS Wi-Fi + Driver) và nhật ký tiến trình chi tiết trong ứng dụng phục vụ debug trên cả Android và iOS."
status: completed
priority: P1
effort: "6h"
issue: null
branch: main
tags: [android, ios, wifi, handover, retry-backoff, logging, debug, gw040-nsv2, bugfix, feature]
blockedBy: []
blocks: []
created: 2026-11-06
---

# Kế Hoạch: Tăng Thời Gian Chờ Sẵn Sàng ONT, Retry Giãn Cách Kết Nối Lại Wi-Fi & Ghi Log Debug Tiến Trình (Android & iOS)

## Overview

Sau khi kiểm thử thực tế trên điện thoại bản triển khai từ kế hoạch [`261105-1821-mobile-dual-wifi-handover-firmware`](../261105-1821-mobile-dual-wifi-handover-firmware/plan.md), quy trình vẫn gặp lỗi tại **Bước 7 (kết nối lại Wi-Fi mới sau khi đổi tên ở Lần 1)** do 4 nguyên nhân gốc rễ trên phần cứng Broadcom **VNPT iGate GW040-NSv2**:
1. **Phát hiện sóng (Beacon) chưa đồng nghĩa ONT đã sẵn sàng:** Driver `wl0`/`wl1` phát tên Wi-Fi mới trước khi tiến trình WPA2/WPA3 (`nas`), cầu nối `br0` và DHCP (`udhcpd`) khởi động xong (đồng thời `acsd` có thể nháy kênh lần 2 sau 3–8 giây). Hiện tại Android (`OntWifiConnector.kt:478-481`) gọi `requestNetwork` ngay ở `0 ms` sau beacon đầu tiên và cắt DHCP chỉ sau `5s` (`:551`), còn iOS (`MainViewModel.swift:685`) chỉ chờ `3.5s` rồi gọi `tryJoinWifiIfEntitled` đúng 1 lần duy nhất (và trả về ngay IP cũ của `Band A` ở `0 ms` `[Red Team RT-01]`).
2. **Quỹ thời gian chờ chưa đủ dài:** Các ngưỡng `initialSettleMs = 4s`, `awaitOntSubnetIpv4Ready = 5s`, `wifiHandoverTimeoutMs = 120s`, `gatewayPostHandoverTimeoutMs = 45s` (cùng `timeoutMs = 10s/25s` cứng tại `MainViewModel.kt:644, 896` `[Red Team RT-07]`) chưa đủ bao phủ chu kỳ khởi động lại kép của `wlmngr` + kênh 5GHz DFS + thời gian cấp DHCP + khởi động CGI `httpd`.
3. **Thiếu Retry Giãn Cách (`Spaced Backoff`) & Thiếu vòng lặp Retry tổng thể ở Bước 7 trên Android:** `OntMobileDriver.kt:1660-1727` chỉ gọi `onSwitchToSecondaryWifi` đúng 1 lần ngoài vòng lặp (nếu rớt sóng `onLost` ngay sau khi vừa bắt được hoặc trong vòng chờ `"user exist"`, driver gõ vào `SocketFactory` chết suốt 45–65s rồi báo lỗi mà không kết nối lại Wi-Fi `[Red Team RT-04]`); khoảng nghỉ giữa các lần thử trong `OntWifiConnector.kt:560` cố định `2.5s` (và `1.2s` trong `OntMobileDriver.swift:1701, 1719`), còn `verifySecondConnectionBandAndMac` ném lỗi cứng ngay khi `deviceinfo2.json` / `wlAssociatedDevice` đang bận cập nhật.
4. **Chưa có hệ thống ghi Log trong ứng dụng (`0` dòng log hiện tại):** Không có nhật ký thời gian thực hay xuất file `.txt` trên điện thoại để truy vết chính xác mốc thời gian, RSSI/tần số quét, mã phản hồi CGI, hay lý do rớt kết nối ngoài hiện trường.

Kế hoạch này giải quyết triệt để cả 4 vấn đề trên **Android (`android-app/`)** và **iOS (`ios-app/`)** với bộ kiểm thử tự động đầy đủ.

---

## Scope Challenge

- **Existing code:** Đã có khung 2 lần kết nối 8/9 bước (`OntMobileDriver.kt`/`.swift`), quản lý hạn mức quét 4 lần/120s (`OntWifiConnector.kt:1058`), bộ lọc che thông tin nhạy cảm (`OntInputValidator.maskSensitiveCredentials`), `FileProvider` `cacheDir/exports/` trên Android (`res/xml/file_paths.xml:3`) và luồng chia sẻ `UIActivityViewController` trên iOS (`SubscriberListView.swift`).
- **Requested scope:** (1) Tăng thời gian chờ ở các pha khởi động sóng / DHCP / Gateway; (2) Xử lý đúng trạng thái "tìm thấy Wi-Fi chưa có nghĩa ONT đã sẵn sàng" (cửa sổ ổn định sau beacon + hội tụ DHCP/CGI/`wlAssociatedDevice`); (3) Bổ sung retry kết nối lại Wi-Fi có giãn cách lũy tiến (`5s -> 10s -> 15s -> 20s`) ở cả tầng OS Wi-Fi và tầng Driver; (4) Bổ sung ghi log tiến trình chi tiết trong app (xem trực tiếp, sao chép, chia sẻ file `.txt` đã che mật khẩu & thông tin nhạy cảm) để phục vụ debug.
- **Complexity:** 4 file mới (`AppDiagnosticLogger.kt`, `DiagnosticLogDialog.kt`, `AppDiagnosticLogger.swift`, `DiagnosticLogSheet.swift`) + cập nhật các file Wi-Fi/Driver/ViewModel/UI/Tests hiện có, chia thành 4 Phase rõ ràng.
- **Selected mode:** `HOLD SCOPE` (`--hard` mode kèm Red Team Review & Whole-Plan Consistency Sweep).

---

## Architecture & Sequence Flow

```mermaid
sequenceDiagram
    autonumber
    participant App as Mobile App + AppDiagnosticLogger
    participant WC as OS Wi-Fi Connector (Android / iOS)
    participant ONT as ONT GW040-NSv2 (wlmngr + udhcpd + httpd)

    Note over App,ONT: Kết thúc Bước 6 (Lần 1): wirelesscfg.json + wlrestart.json + logout.cmd (ghi mốc step6CompletedMicros)
    App->>App: Ghi Log [STEP 6->7] Bắt đầu Handover (Ngân sách 180s Android / 240s iOS)
    loop Vòng lặp Bước 7 Tổng Thể (Spaced Retry: 5s -> 10s -> 15s -> 20s)
        App->>WC: Lượt 1 chờ lắng 8s (Lượt >=2 bỏ qua lắng đầu, giữ mốc step6CompletedMicros)
        WC->>ONT: Quét tìm SSID_B mới (thoát sớm Pha A sau 8s nếu cạn quota 4 lần/120s)
        ONT-->>WC: Thấy Beacon SSID_B (ghi log RSSI, tần số, BSSID đã che giữa)
        Note over WC,ONT: Cửa sổ ổn định sau khi thấy sóng (8s ở lượt 1 / 2s ở lượt >=2)<br/>Chờ nas (WPA2/WPA3) + br0 + udhcpd sẵn sàng
        loop Retry Ghép Nối Tầng Wi-Fi (Giãn cách 5s -> 10s -> 15s -> 20s, 30s/lượt)
            WC->>ONT: Ghép nối WPA2/WPA3 + Chờ DHCP 192.168.1.x tối đa 15s + Kiểm tra giữ link 1.5s
        end
        WC-->>App: Đã có SocketFactory / en0 IP 192.168.1.x ổn định
        App->>App: Chờ lắng sau DHCP 2.5s (postDhcpGatewaySettleMs)
        App->>ONT: Probe Gateway TLS Pinning (<=60s; thoát tức thì nếu rớt link onLost)
        App->>ONT: Login CHỈ bằng adminNewPass (xử lý user exist <=65s, thoát sớm nếu rớt link)
        App->>ONT: Poll hội tụ deviceinfo2.json + wlAssociatedDevice (<=15s, rethrow ngay nếu lệch TLS cert)
        alt Rớt socket tạm thời hoặc chưa sang đúng Band B (còn hạn định)
            App->>App: Ghi Log [WARN] -> logout() + unbind() + Nghỉ giãn cách -> Thử lại!
        else Xác thực khớp baseMacAddress & đứng vững trên Band B
            ONT-->>App: Hoàn tất Bước 7 -> Chuyển sang Bước 8 & Bước 9 (Đồng bộ <=90s + Upload FW)
        end
    end
```

---

## Cross-Plan Dependencies

| Relationship | Plan | Status |
|-------------|------|--------|
| Follows (completed baseline) | `261105-1821-mobile-dual-wifi-handover-firmware` | completed |

---

## Goals

| # | Goal | Priority |
|---|------|----------|
| 1 | Nâng quỹ thời gian chờ (`180s`/`240s` handover, `8s` lắng đầu + `8s` ổn định sau khi thấy sóng ở lượt 1, `15s` DHCP, `60s` Gateway, `90s` trước Firmware), bổ sung retry giãn cách lũy tiến (`5s -> 10s -> 15s -> 20s`) ở cả `OntWifiConnector` lẫn vòng lặp Bước 7 tổng thể trong `OntMobileDriver`, và xây dựng `AppDiagnosticLogger` đồng bộ luồng ghi + lọc mật khẩu 2 lớp trên Android | P1 |
| 2 | Tích hợp luồng log tách biệt hiệu năng cao vào `MainViewModel.kt`, hiển thị khung Nhật ký tiến trình trực tiếp trên `ExecutionProgressSheet.kt` (tự bung khi lỗi) cùng hộp thoại xem/lọc/sao chép/chia sẻ file `.txt` (`DiagnosticLogDialog.kt`) qua `FileProvider` thu hẹp phạm vi | P1 |
| 3 | Đồng bộ toàn bộ cơ chế chờ sẵn sàng ONT, phân tách luồng `tryJoinWifiDetailed` / Sideloadly không kẹt `Band A`, retry giãn cách (`IosWifiMonitor.swift` + `OntMobileDriver.swift` + `MainViewModel.swift`), bộ ghi log xoay vòng `AppDiagnosticLogger.swift` và giao diện xem/chia sẻ log (`DiagnosticLogSheet.swift`, `ExecutionProgressSheet.swift`, `project.pbxproj`) sang bản iOS Native | P1 |
| 4 | Cập nhật tất cả điểm khởi tạo test (`OntMobileDriverTest.kt` + toàn bộ 6 điểm trong `OntMobileDriverTests.swift`), bổ sung Unit Tests kiểm chứng lịch giãn cách retry, ổn định sau beacon, hội tụ `wlAssociatedDevice`, lọc mật khẩu URL-encoded/JSON trong log, cập nhật `tests/test_ios_app_parity.py` và tài liệu hướng dẫn debug thực địa | P1 |

---

## Phases

| # | Phase | Status |
|---|-------|--------|
| 1 | [Phase 1: Android Diagnostic Logger, ONT Readiness Stabilization & Spaced-Backoff Reconnect Engine](./phase-01-start.md) | Pending |
| 2 | [Phase 2: Android ViewModel Instrumentation, Live Progress Log Panel & Shareable Debug Log UI](./phase-02-android-viewmodel-and-live-log-ui.md) | Pending |
| 3 | [Phase 3: iOS Native Readiness Budgets, Spaced-Backoff Handover & Diagnostic Log Parity](./phase-03-ios-reconnect-backoff-and-log-parity.md) | Pending |
| 4 | [Phase 4: Unit Tests, Cross-Platform Parity Suite & Field Debugging Documentation](./phase-04-tests-parity-and-debug-docs.md) | Pending |

---

## Success Criteria

- [x] **Chờ ONT sẵn sàng thực sự sau khi thấy sóng:** Cả Android và iOS chờ lắng đầu `8s` sau Bước 6 ở lượt đầu, bảo toàn mốc `step6CompletedMicros` xuyên suốt các vòng retry (`[Red Team RT-02]`), chờ thêm `8s` ổn định dịch vụ `nas`/`br0`/`udhcpd` sau khi phát hiện `SSID_B` (thoát sớm Pha A nếu cạn quota quét `4 / 120s`), chờ cấp DHCP `192.168.1.x` tới `15s` + kiểm tra giữ kết nối `1.5s`, và chờ `2.5s` trước khi gọi HTTPS Gateway.
- [x] **Retry giãn cách lũy tiến (`5s -> 10s -> 15s -> 20s`) ở cả 2 tầng:** Khi ghép nối Wi-Fi thất bại hoặc khi rớt socket (`onLost` trong `probeGateway` / `authenticateSecondConnection` / `verifySecondConnectionBandAndMacWithRetry` `[Red Team RT-04]`) ở Bước 7 (và khi rebind `35s` trước Bước 9 `[Red Team RT-07]`), ứng dụng tự động dọn kết nối cũ, đếm ngược giãn cách tăng dần rồi kết nối lại từ đầu trong tổng ngân sách `180s` (Android) / `240s` (iOS); trên iOS không trả sớm IP cũ của `Band A` làm treo phiên (`[Red Team RT-01]`); tuyệt đối không thử lại khi sai `adminNewPass`, lệch TLS cert SHA-256 (kể cả trong `postOptionalSessionJson` `[Red Team RT-04]`) hay sai `baseMacAddress` đã xác nhận.
- [x] **Ghi Log tiến trình & Debug an toàn tuyệt đối:** `AppDiagnosticLogger` lọc mật khẩu đồng bộ ngay trên luồng gọi (hỗ trợ cả chuỗi nguyên văn, URL-encoded `%XX`, JSON có dấu cách mà không làm hỏng từ khóa `admin` `[Red Team RT-03]`), đồng bộ hóa luồng ghi xoay vòng trước khi xuất/xóa (`[Red Team RT-06]`), tách luồng UI `200ms` chống giật lag (`[Red Team RT-08]`), bảo vệ clipboard nhạy cảm + thu hẹp `file_paths.xml` (`[Red Team RT-09]`), và cho phép xem trực tiếp trên `ExecutionProgressSheet` hoặc chia sẻ file `.txt` qua Zalo/Telegram.
- [x] **Kiểm thử tự động & Tài liệu:** `./gradlew testDebugUnitTest assembleDebug` (Android) chạy nhanh `< 15s` và PASS 100%; toàn bộ 6 điểm khởi tạo trong `OntMobileDriverTests.swift` được gắn tham số test nhanh (`[Red Team RT-05]`); `PYTHONPATH=. pytest -v` (bao gồm `tests/test_ios_app_parity.py`) PASS 100%; `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` cập nhật đầy đủ.

## Dependencies

- Không thêm bất kỳ thư viện ngoài (zero new Gradle/SPM/CocoaPods dependencies) và không thêm `.entitlements` trả phí trên iOS (`CODE_SIGNING_ALLOWED=NO`).

---

## Red Team Review

### Session — 2026-11-06
**Reviewers:** 3 Hostile Personas (`SecurityAdversary`, `FailureModeAnalyst`, `AssumptionDestroyer`) — Standard Verification Tier (`Fact Checker` + `Contract Verifier`)
**Report:** [`reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md`](./reports/red-team-261106-mobile-wifi-reconnect-backoff-and-debug-log.md)
**Findings:** 9 (9 accepted, 0 rejected — 100% backed by codebase `file:line` evidence)
**Severity breakdown:** 5 Critical, 4 High

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| RT-01 | iOS `onSwitchToSecondaryWifi` (`MainViewModel.swift:735-743`) returns stale `Band A` IP at `0ms`/`3s`, blocking hotspot retry & locking LuCI session; `NEHotspotConfigurationError` code `8`/`13` & Open profile cleanup before DHCP | Critical | Accept | `plan.md`, `phase-03`, `phase-04` |
| RT-02 | Android Phase A resets `handoverStartMicros` & cold-boot `8s+8s` waits on outer retry, stalls `45s` when `4/120s` scan quota exhausted, clobbers `excludedNetwork = null` (`OntWifiConnector.kt:421`), and loops on `wl0` when `ssid24g == ssid5g` | Critical | Accept | `plan.md`, `phase-01`, `phase-02`, `phase-04` |
| RT-03 | URL-encoded passwords (`Passwd2%40`), Kotlin/Swift regex asymmetry (`OntInputValidator.kt:32-46`), `"admin"` substring over-redaction, missing `effectiveWifi*Pass`/scanner pass, and async `clearActiveRunSecrets()` teardown race | Critical | Accept | `plan.md`, `phase-01`, `phase-02`, `phase-03`, `phase-04` |
| RT-04 | Dead-socket `65s` hang & uncaught `OntUserExistError` in `authenticateSecondConnection`, swallowed TLS-pin mismatch in `postOptionalSessionJson`, frozen `observedPeerCertSha256`, and Step 2c `deviceinfo2.json` retry | Critical | Accept | `plan.md`, `phase-01`, `phase-03`, `phase-04` |
| RT-05 | `OntNetworkError` is `final` in `OntExceptions.kt:21` (breaking subclassing), and 5 standalone `OntMobileDriver(...)` XCTest call sites (`OntMobileDriverTests.swift:675,799,899,958,1160`) keep `2.5s` defaults against `150ms` deadline | Critical | Accept | `phase-01`, `phase-03`, `phase-04` |
| RT-06 | `AppDiagnosticLogger` async writer queue races with `exportCombinedLogText()` / `clearLogs()` on `Dispatchers.IO` / `@MainActor` and leaks executor on `MainViewModel.onCleared()` | High | Accept | `phase-01`, `phase-02`, `phase-03`, `phase-04` |
| RT-07 | Hardcoded `timeoutMs = 10000L` (`MainViewModel.kt:644`) & `25_000L` (`:896`) bypass new `30s/35s` defaults and `connectToOntWifi` lacks per-attempt `requestNetwork` slicing | High | Accept | `phase-01`, `phase-02` |
| RT-08 | `diagnosticLogs` inside `MainUiState` causes Compose recomposition storms; unordered iOS `Task { @MainActor }` + eager `VStack` + dual `.sheet` collision on `SubscriberListView` / `ExecutionProgressSheet` | High | Accept | `phase-02`, `phase-03` |
| RT-09 | Missing Android `EXTRA_IS_SENSITIVE` clipboard flag, missing iOS `180s` pasteboard expiration on log copy, over-broad `<files-path>` in `res/xml/file_paths.xml:4`, and temp `.txt` cleanup | High | Accept | `phase-02`, `phase-03`, `phase-04` |

### Whole-Plan Consistency Sweep
- **Files reread:** `plan.md`, `phase-01-start.md`, `phase-02-android-viewmodel-and-live-log-ui.md`, `phase-03-ios-reconnect-backoff-and-log-parity.md`, `phase-04-tests-parity-and-debug-docs.md`
- **Decision deltas checked:** 9 (`RT-01` through `RT-09`)
- **Reconciled stale references:** 9 (added `OntExceptions.kt` to Phase 1 and `res/xml/file_paths.xml` to Phase 2 `Related Code Files`; removed `diagnosticLogs` from `MainUiState` in favor of decoupled `200ms`-coalesced `entriesFlow` collection in Phase 2 and single-root/local `.sheet` + `LazyVStack` in Phase 3; unified synchronous caller-thread 2-layer redaction with URL-encoded forms, `length >= 6` unkeyed rule, reserved-token allowlist, and PII/MAC middle-masking on `.txt` export across Phases 1–4; propagated `step6CompletedMicros` + `outerAttemptIndex` + `lockToBssidHint` across Phases 1–2; fixed iOS manual vs entitled Step 7 flow in Phase 3; and updated all 6 `OntMobileDriverTests.swift` constructor sites + `400ms` test handover budget in Phase 4)
- **Unresolved contradictions:** 0

<!-- slug: mobile-wifi-reconnect-backoff-and-debug-log -->
