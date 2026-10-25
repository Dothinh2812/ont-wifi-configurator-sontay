---
title: Ke hoach xay dung ung dung iOS Native cau hinh ONT GW040-NSv2 va dong goi IPA
date: 2026-10-25
summary: "Lap ke hoach 4 giai doan cho ung dung iOS Native (Swift + SwiftUI) trong ios-app/ doc/ghi Excel 30 cot, dinh tuyen en0 vuot 4G/5G, 7 buoc LuCI CGI va GitHub Actions dong goi Unsigned IPA"
---

# Kế hoạch xây dựng ứng dụng iOS Native cấu hình ONT GW040-NSv2 và đóng gói IPA

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.

## 1. Bối cảnh & Quyết định kiến trúc
- Xây dựng phiên bản **iOS Native (`ios-app/`, Pure Swift 5.9+ & SwiftUI, iOS 16+)** độc lập hoàn toàn với `android-app/` để kỹ thuật viên sử dụng iPhone có thể cấu hình không dây thiết bị ONT **VNPT iGate GW040-NSv2** tại nhà thuê bao.
- Lựa chọn đóng gói **`ONT_WiFi_Configurator_Unsigned.ipa`** (không dùng Entitlement trả phí của Apple) kèm workflow **GitHub Actions `macos-14` (`.github/workflows/build-ios-ipa.yml`)** để cài đặt miễn phí qua **Sideloadly / AltStore / TrollStore / ESign**.

## 2. Giải pháp kỹ thuật trọng tâm trên iOS
- **Không phụ thuộc thư viện ngoài (0 CocoaPods / 0 SPM):** Dùng trực tiếp `zlib` (`ZipArchive.swift`) + `XMLParser` (`ExcelManager.swift`) + `SQLite3` (`AppDatabase.swift`) giúp gói `.app` đơn khối, tránh lỗi chữ ký dynamic framework khi Sideload.
- **Định tuyến mạng nội bộ vượt 4G/5G (`IosWifiMonitor` + `UnsafeSslSession`):** Kiểm tra IPv4 `192.168.1.x` trên card Wi-Fi `"en0"` qua `getifaddrs()` và ép `URLSessionConfiguration.allowsCellularAccess = false` để lưu lượng `https://192.168.1.1` luôn đi qua Wi-Fi ONT ngay cả khi iPhone bật 4G/5G.
- **Chuỗi 7 bước LuCI CGI không dây (`OntMobileDriver.swift`):** Đồng bộ 100% với bản Android (`AsyncMutex` tạm dừng probe nền, `Task.detached` gọi `logout.cmd` bắt buộc khi thoát/lỗi chống khóa `"user exist"` 60s, `numberLogin < 2` chống khóa phần cứng 180s, và đổi Wi-Fi băng tần đang kết nối ở Bước 7 cuối cùng).

## 3. Danh sách 4 giai đoạn (`plans/261025-1127-ios-ont-wifi-configurator/`)
- **Phase 1 (`phase-01-start.md`):** Khởi tạo `ios-app/`, `ZipArchive`, `ExcelManager`, `OntInputValidator`, `AppDatabase`.
- **Phase 2 (`phase-02-wifi-en0-routing-and-ont-driver.md`):** `IosWifiMonitor`, `UnsafeSslSession`, `OntMobileDriver` (7 bước LuCI CGI).
- **Phase 3 (`phase-03-swiftui-screens-and-review-flow.md`):** Giao diện SwiftUI (`SubscriberListView`, `ConfigReviewView`, `WifiGuideSheet`, `ExecutionProgressSheet`, `FooterSignatureBar`).
- **Phase 4 (`phase-04-testing-and-github-actions-ipa.md`):** Bộ kiểm thử `XCTest`, `ONTWiFiConfigurator.xcodeproj`, `package_unsigned_ipa.sh` và `.github/workflows/build-ios-ipa.yml`.
