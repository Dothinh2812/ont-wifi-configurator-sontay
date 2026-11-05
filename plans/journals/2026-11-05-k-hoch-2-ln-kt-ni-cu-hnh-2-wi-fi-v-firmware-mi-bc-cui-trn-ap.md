---
title: Kế hoạch 2 lần kết nối cấu hình đủ 2 Wi-Fi và đổ Firmware mới bước cuối trên App Mobile
date: 2026-11-05
summary: "Thiết kế quy trình 2 lần kết nối tự động với timeout dài (120s-180s) để cấu hình trọn vẹn 2 băng tần Wi-Fi rồi nạp Firmware ở bước cuối trên Android & iOS, kèm Red Team RT-01..RT-10."
---

# Kế hoạch 2 lần kết nối cấu hình đủ 2 Wi-Fi và đổ Firmware mới bước cuối trên App Mobile

## What happened
Lập phương án kỹ thuật chi tiết (`ak-brainstorm` + `ak-plan --hard`) tại `plans/261105-1821-mobile-dual-wifi-handover-firmware` để tái kiến trúc App Mobile (`android-app/` & `ios-app/`) hoàn tất trọn vẹn cả 2 băng tần Wi-Fi và đổ Firmware mới ở bước cuối thông qua quy trình 2 lần kết nối tự động:
1. Lần kết nối 1 (trên Wi-Fi chính `Band A`): Probe Gateway (ghim chứng chỉ TLS SHA-256), đăng nhập & đổi mật khẩu Admin ONT mới, đọc `deviceinfo2.json` + `dhcpclient.json` xác thực `Band A` và `baseMacAddress`, cấu hình WAN PPPoE, GPON SLID, LAN IPv4, chỉ đổi tên & cấu hình Wi-Fi phụ (`Band B`, chờ `wlrestart.json` tối đa `30s`) rồi gọi `logout.cmd`.
2. Chuyển kết nối tự động sang Wi-Fi phụ vừa đổi (`Band B`) với timeout đủ dài (`120s` trên Android / `180s` wall-clock trên iOS) chờ ONT khởi động lại sóng / kênh 5GHz DFS (`100/120`) cập nhật tên Wi-Fi mới, kiểm tra chứng chỉ TLS đã ghim + `probeGateway()` (`45s`), đăng nhập lại chỉ bằng mật khẩu ONT mới (`adminNewPass`, xử lý `"user exist"` tối đa `65s`) và xác thực `wlAssociatedDevice == Band B`.
3. Lần kết nối 2 (trên `Band B`): đổi tên & lưu NVRAM Wi-Fi còn lại (`Band A`), đồng bộ `ensureGatewayReadyOnSecondaryBand` (`60s`), và thực hiện bước cuối cùng `updateFirmware()` (`180s` upload timeout), xóa sạch `sessionKey` khi nhận `"status": "valid"` và loại bỏ hoàn toàn bước đổi Wi-Fi thủ công sau reboot.

## Decision
- Chấp nhận và tích hợp toàn bộ 10 phát hiện từ 3 Hostile Red Team Reviewers (`RT-01`..`RT-10`: tách `ensureGatewayReadyOnSecondaryBand` không bao giờ thử lại `"admin"` cũ tránh khóa 180s; ghim chứng chỉ TLS lá SHA-256 giữa 2 lần kết nối; sửa read-only `wirelesscfg.json` có `wl_unit` và che `nvramWlWpaPsk`; chặn bám nhầm lại `Band A` khi tắt GPS; nhánh tương thích cho máy chỉ có 2.4GHz; bảo vệ nền & `completeUntilFirstUserAuthentication` trên iOS; và tách tham số poll riêng để 31 ca unit test Android/iOS chạy dưới 5 giây).

## Next steps
- Triển khai tuần tự 4 Phase theo `/ak:cook /home/vtst/ONT-config/plans/261105-1821-mobile-dual-wifi-handover-firmware/plan.md` hoặc chạy phỏng vấn xác thực `/ak:plan validate`.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
