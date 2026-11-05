---
title: "Hoàn tất cấu hình cả 2 Wi-Fi và đổ Firmware mới bước cuối qua 2 lần kết nối tự động trên App Mobile (Android & iOS)"
date: 2026-11-05
summary: "Hoàn thiện luồng 2 lần kết nối tự động (8/9 bước) đổi đủ cả 2 Wi-Fi và nạp Firmware bước cuối trên Android & iOS Native, ghim TLS SHA-256, chống khóa 180s và đạt 100% pass (29 JUnit + 92 pytest, Code Review 9.5/10)."
---

# Hoàn tất cấu hình cả 2 Wi-Fi và đổ Firmware mới bước cuối qua 2 lần kết nối tự động trên App Mobile (Android & iOS)

## What happened
- Thực thi trọn vẹn kế hoạch `plans/261105-1821-mobile-dual-wifi-handover-firmware/plan.md` theo quy trình `--tdd --advice --auto` qua 4 Phase trên cả Android (`android-app/`) và iOS (`ios-app/`):
  1. **Phase 1 (Android Handover Engine, TLS Pinning & 2-Connection Driver):** Mở rộng ngân sách timeout phần cứng (`wifiHandoverTimeoutMs = 120s`, `gatewayPostHandoverTimeoutMs = 45s`, `postSecondWifiSettleTimeoutMs = 60s`, `firmwareUploadTimeoutMs = 180s`), che khóa NVRAM (`nvramWlWpaPsk`, `nvramWlRadiusKey`, `nvramWlKey[1-4]?`), ghim chữ ký SHA-256 chứng chỉ X.509 lá giữa 2 kết nối (`UnsafeSslClient.kt`), cài đặt `switchToConfiguredSecondaryWifi` (loại trừ `excludedNetwork` chống bám nhầm `Band A` khi tắt GPS, quản lý quota `4 scans / 120s`, hỗ trợ API 26–34 và nhánh `allowSingleBand24gFallback` cho máy chỉ có 2.4GHz), và tái kiến trúc `OntMobileDriver.kt` sang 8/9 bước (`authenticateSecondConnection` & `ensureGatewayReadyOnSecondaryBand` chỉ dùng `adminNewPass`, kiểm tra NVRAM read-only `nvramWlSsid`, đổ Firmware ở Bước 9 cuối cùng).
  2. **Phase 2 (Android ViewModel, Dynamic Rebind & Compose UI):** Chặn sớm trước Bước 1 nếu hệ điều hành không tương thích chuẩn bảo mật Wi-Fi (`RT-06`), giữ nguyên `specifierWifiNetwork` sống ở Lần 2 khi rebind dưới `<unknown ssid>`, cập nhật giao diện 8/9 bước kèm thẻ nhắc hộp thoại Bước 7 + đếm ngược `150s` chờ ONT ghi flash/reboot, dọn dẹp `.xlsx` PII trong `cacheDir/exports/`, và xóa sạch toàn bộ luồng đổi Wi-Fi thủ công cũ.
  3. **Phase 3 (iOS Native Parity & Unsigned `.ipa` Safety):** Liên kết `NetworkExtension.framework` đồng bộ trên `project.yml` & `project.pbxproj` (không dùng `.entitlements` trả phí, giữ nguyên Sideloadly/AltStore), nâng cấp `AppDatabase.swift` & các tệp đệm sang `completeUntilFirstUserAuthentication`, cài đặt `uploadMultipartFile` streaming `32 KB` qua `uploadTask(withStreamedRequest:)` có lưu `protocolClasses` + `finishTasksAndInvalidate()`, bổ sung `tryJoinWifiIfEntitled` (`joinOnce = true` khi Open) kết hợp Auto-Detect `180s` bền bỉ trước lỗi rớt socket khi chuyển Wi-Fi trong Cài đặt iOS, và xóa phiên bất kể trạng thái nền khi đã flush 100% Firmware.
  4. **Phase 4 (Unit Tests, Mock LuCI, Cross-Platform Parity & Docs):** Nâng cấp `mock_luci_server.py` tách rõ `action == "Apply"` và đọc NVRAM read-only, cập nhật toàn bộ `OntMobileDriverTest.kt`, `ExcelAndValidatorTest.kt`, `OntMobileDriverTests.swift`, `ExcelAndValidatorTests.swift`, `tests/test_ios_app_parity.py`, `README.md` (`5B`, `5C`) và `HUONG_DAN_SU_DUNG_TOOL_ONT.md` (`6B`, `6C`).

## Decision
- Đặt bước **Update Firmware ở bước cuối cùng (Bước 9/9)** trên **Lần kết nối 2 (đứng trên Wi-Fi phụ `Band B` vừa đổi)** sau khi Bước 8 đã lưu hoàn tất Wi-Fi còn lại (`Band A`) vào NVRAM: triệt tiêu hoàn toàn giới hạn vật lý của quy trình 1 kết nối mà không cần KTV chờ ONT reboot ~2m20s để đổi nốt Wi-Fi thủ công.
- Tuyệt đối không bao giờ thử lại `adminDefaultPass` (`"admin"`) sau Bước 2 (`RT-01`), và ghim chứng chỉ TLS SHA-256 + đối chiếu `baseMacAddress` + `wlAssociatedDevice` để chặn cả Evil-Twin AP lẫn kết nối nhầm ONT hàng xóm hoặc kẹt lại ở `Band A`.

## Verification
- `./gradlew testDebugUnitTest assembleDebug`: **BUILD SUCCESSFUL in 14s** (`29/29` JUnit tests passed in `1.79s`, `app-debug.apk` built).
- `PYTHONPATH=. pytest -v`: **92/92 passed** (bao gồm kiểm chứng trực tiếp `MockLuCIServer` read-only NVRAM và đối xứng đa nền tảng).
- Kongming Advisory Checkpoints (Phases 1–4) + Mandatory Code Review (2 cycles): **9.5/10 (`critical_count: 0`, `HARD-GATE-NO-SIDE-EFFECTS: PASS` — Auto-approved)**.

## Next steps
- Workflow `.github/workflows/build-ios-ipa.yml` trên nhánh `main` sẽ tự động chạy bộ XCTest mới trên `macos-14` Simulator và đóng gói `ONT_WiFi_Configurator_Unsigned.ipa` kèm `SHA256SUMS.txt`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
