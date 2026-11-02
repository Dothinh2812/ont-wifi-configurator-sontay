---
title: "Bổ sung bước Upload & Cập nhật Firmware ONT trước bước đổi Wi-Fi kết nối chính trên Android"
date: 2026-10-28
summary: "Implemented 8-step firmware upload flow (updatefwsessioncheck.json -> imagesize.json -> uploadsoftware.conf) before the final connected Wi-Fi step in android-app with SAF caching, session/network safety, and post-reboot manual Wi-Fi finisher."
---

# Bổ sung bước Upload & Cập nhật Firmware ONT trước bước đổi Wi-Fi kết nối chính trên Android

## What happened
Implemented `plans/261028-1236-android-firmware-update-step/plan.md` across 3 phases using TDD and advisory supervision:
- **Phase 1 (Core Driver, Models & Validation):** Extended `GlobalSettings`, `OntInputValidator`, `MobileSetupResult`, and `OntMobileDriver` with `updateFirmware()`, 8-step execution branching before the final connected Wi-Fi step, `reloginAfterRadioBounce()` in `ensureGatewayReadyBeforeStep7()`, immediate `sessionKey = ""` clearing upon `"status": "valid"` (or post-flush radio drop), and `executeManualConnectedWifiOnly()`.
- **Phase 2 (SAF File Picker, ViewModel & Compose UI):** Added atomic `.tmp` SAF firmware caching in `noBackupFilesDir/firmware_cache`, `suppressAutoBindAfterFirmware` barrier to preserve 4G/5G while ONT flashes, Section 4 Firmware card on `ConfigReviewScreen`, and post-firmware guidance + 1-tap post-reboot Wi-Fi finisher on `ExecutionProgressSheet`.
- **Phase 3 (MockWebServer Unit Tests & Docs):** Added comprehensive unit tests in `OntMobileDriverTest.kt` and `ExcelAndValidatorTest.kt`, and updated `README.md` and `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Decision
Keep the current connected Wi-Fi band active after Step 6 when `enableFirmwareUpdate == true` so the 20-35 MB firmware image uploads over a stable link at Step 7/8, then immediately unbind ONT Wi-Fi and defer renaming the connected Wi-Fi band (Step 8/8) until after the ONT finishes its ~2-minute flash and reboot.

## Next steps
- Optional: Package release APK or test on physical Android 10-14 devices with live GW040-NSv2 firmware images.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
