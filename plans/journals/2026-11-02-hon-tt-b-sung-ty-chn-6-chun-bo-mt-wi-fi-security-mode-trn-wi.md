---
title: "Hoàn tất bổ sung tùy chọn 6 chuẩn bảo mật Wi-Fi (Security Mode) trên Windows, Android và iOS"
date: 2026-11-02
summary: "Triển khai đầy đủ 6 chế độ bảo mật Wi-Fi (wpa2_wpa3_psk, wpa2_psk, wpa_wpa2_psk, wpa3_psk, wpa_psk, open) kèm 7 kiểm soát Red Team RT-01..RT-07 trên cả 3 nền tảng Windows, Android và iOS."
---

# Hoàn tất bổ sung tùy chọn 6 chuẩn bảo mật Wi-Fi (Security Mode) trên Windows, Android và iOS

## What happened
- Triển khai kế hoạch `plans/261102-2049-wifi-security-mode-selection/plan.md` theo quy trình `--tdd --advice --auto` qua 3 Phase:
  1. **Phase 1 (Desktop Windows PyQt6 & MockLuCIServer):** Thêm `wifi_security_mode` vào `GlobalConfig`, bảng ánh xạ 6 chế độ `WIFI_SECURITY_MODES`, hàm `url_encode_form_value` và `validate_wifi_ssid_and_pass` kiểm tra pre-flight ngay trước Bước 1 (`RT-02`, `RT-04`), xử lý `conflict_mesh` (`RT-01`), thêm `QComboBox` chọn chuẩn bảo mật trên `MainWindow`, cập nhật regex che mật khẩu `\x7f` trong `worker.py` và `MockLuCIServer` (`keep_blank_values=True`, `RT-07`).
  2. **Phase 2 (Android Jetpack Compose):** Bổ sung enum `WifiSecurityMode` vào `GlobalSettings.kt`, đồng bộ trim và `effectiveWifi24Pass(secMode)` trong `SubscriberRecord.kt` (`RT-05`, `RT-06`), cập nhật `OntInputValidator.kt`, `OntMobileDriver.kt` (lưu `wifiSecurityMode` vào `MobileSetupResult`, không retry 3 lần khi gặp `conflict_mesh` ở Bước 7, đồng bộ Bước 8 thủ công sau nạp Firmware), `OntWifiConnector.kt` & `MainViewModel.kt` (`hasSwitchedToSecondaryBand`, hỗ trợ Open/WPA3-SAE ở cả 2 điểm gọi `switchToConfiguredOntWifi` `RT-03`), và giao diện `ConfigReviewScreen.kt` + `ExecutionProgressSheet.kt`.
  3. **Phase 3 (iOS SwiftUI & Docs):** Đồng bộ `WifiSecurityMode` và custom `init(from decoder:)` trong `GlobalSettings.swift` (`RT-07`), cập nhật `OntInputValidator.swift`, `OntMobileDriver.swift` (bắt riêng `OntDriverException` trước nhánh `catch` mạng và dừng ngay khi gặp `conflict_mesh`), `ConfigReviewView.swift`, `ExecutionProgressSheet.swift`, cùng tài liệu `README.md` và `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Decision
- Giữ mặc định `wpa2_wpa3_psk` trên cả 3 nền tảng để tương thích ngược 100%.
- Thực hiện kiểm tra hợp lệ mật khẩu Wi-Fi ở pre-flight trước Bước 1 trên cả Windows, Android và iOS để không bao giờ làm thay đổi mật khẩu Admin/WAN/SLID khi mật khẩu Wi-Fi không hợp lệ.

## Verification
- `PYTHONPATH=. pytest`: **91/91 passed**.
- `cd android-app && ./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL (100% passed)**.
- Kongming Advisory Checkpoints (Phases 1, 2, 3) & Mandatory Code Review: **9.9/10 (GO)**.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
