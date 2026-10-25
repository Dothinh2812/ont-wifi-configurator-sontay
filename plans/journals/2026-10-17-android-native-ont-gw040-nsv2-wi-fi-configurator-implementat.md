---
title: Android Native ONT GW040-NSv2 Wi-Fi Configurator Implementation
date: 2026-10-17
summary: "Implemented Android Native ONT GW040-NSv2 Wi-Fi Configurator (Kotlin, Compose, Room, Native XLSX, 7-step wireless LuCI driver with Step 7 2.4GHz band ordering)"
---

# Android Native ONT GW040-NSv2 Wi-Fi Configurator Implementation

## What happened
Implemented the Android Native ONT Wi-Fi Configurator (`android-app/`) for VNPT iGate GW040-NSv2 field technicians following `/ak:cook ... --tdd --advice --auto`:
- **Phase 1 (Data & Validation):** Built `ExcelManager.kt` using native `ZipInputStream`/`ZipOutputStream` + `XmlPullParser`/`XmlSerializer` (zero Apache POI bloat), preserving leading zeros (`SO_MAY = 001560828`), all 30 original Excel columns (`rawRowData`/`rawRowJson` in Room `SubscriberEntity`), smart re-import merge (`mergeRecordsPreservingEdits`), normalized phone search (`SO_DT` with or without leading `0`), and 30+3 column `.xlsx` report export with `maskSensitiveCredentials`. Built `OntInputValidator.kt` with two-stage validation (`validateOnImport` catching 13 rows missing PPPoE passwords; `validateBeforeRun` catching 5 rows with 7-digit Wi-Fi passwords plus 1-tap leading-zero fix and blocking all 11 unsafe shell metacharacters `&|;$><`\!'"`).
- **Phase 2 (Wi-Fi Binding & LuCI CGI Wireless Driver):** Built `OntWifiConnector.kt` (`^GW040-NSv2_(2\.4G|5G)_([0-9A-Fa-f]{6})$` SSID/BSSID suffix verification, `WifiNetworkSpecifier` + `TRANSPORT_WIFI` without `NET_CAPABILITY_INTERNET`, bound `socketFactory`, scan-throttle-safe beacon verification) and `OntMobileDriver.kt` (7-step wireless flow inside `try ... finally { withContext(NonCancellable) { logout(); unbind() } }`, `probePauseMutex` barrier, smart retry order with immediate `onAdminPasswordChanged` Room persistence, `serviceId=1` on WAN PPPoE edit, and Dynamic Band Ordering placing the connected 2.4GHz band at Step 7 LAST after Step 6 5GHz + `2.0s` settling delay).
- **Phase 3 & 4 (Compose UI, Testing & APK Packaging):** Built `MainViewModel.kt`, `SubscriberListScreen.kt`, `ConfigReviewScreen.kt`, `OntWifiScannerDialog.kt`, `ExecutionProgressSheet.kt`, `FooterSignatureBar.kt`, and `MainActivity.kt` (`FLAG_KEEP_SCREEN_ON`), embedded anonymized `assets/sample-demo.xlsx` while keeping `file-mau.xlsx` in `src/test/resources/`, and verified `15/15` Android unit tests + `assembleDebug` APK + `33/33` Python desktop tests.

## Decision
- Chose Native ZIP + `XmlPullParser`/`XmlSerializer` over Apache POI to avoid 15MB dependency bloat and `javax.xml.stream` conflicts on Android 8–14.
- Enforced Dynamic Band Ordering (Step 6 configures non-connected 5GHz band + `2.0s` Broadcom radio settling pause; Step 7 configures connected 2.4GHz band last) and immediate `bindProcessToNetwork(null)` unbind after Step 7 so 4G/5G cellular restores immediately for Zalo report sharing.
- Wired `onAdminPasswordChanged` callback in Step 2 so `lastKnownAdminPass` is persisted to Room immediately before Steps 3–7 run, preventing 180s hardware lockout on mid-flight retry.

## Next steps
- Install `android-app/app/build/outputs/apk/debug/app-debug.apk` on field technicians' Android devices and test live against physical VNPT iGate GW040-NSv2 units.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
