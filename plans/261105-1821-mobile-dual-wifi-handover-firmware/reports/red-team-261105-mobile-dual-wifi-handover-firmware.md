# Red Team Adjudication Report — 2026-11-05

## Summary
- **Plan:** `plans/261105-1821-mobile-dual-wifi-handover-firmware`
- **Reviewers:** 3 Hostile Personas (`SecurityAdversary`, `FailureModeAnalyst`, `AssumptionDestroyer`) — Standard Verification Tier (`Fact Checker` + `Contract Verifier`)
- **Total Deduplicated Findings:** 10 (10 Accepted, 0 Rejected — 100% backed by codebase & `.jsonl` `file:line` evidence)
- **Severity Breakdown:** 6 Critical, 3 High, 1 Medium

## Adjudicated Findings (`RT-01` .. `RT-10`)

1. **RT-01 (Critical — Accept):** Reusing `ensureGatewayReadyBeforeStep7` (`OntMobileDriver.kt:897 -> 799 -> 347`, `OntMobileDriver.swift:925 -> 836 -> 348`) in Connection 2 falls back to `adminDefaultPass` (`"admin"`) on socket glitches, risking `numberLogin >= 2` / 180s hardware lockout before Step 9 Firmware Upload.
   - *Applied fix:* Replace post-handover readiness gates with `ensureGatewayReadyOnSecondaryBand` calling `authenticateSecondConnection(newAdminPass = settings.adminNewPass, previousSessionKey = lastActiveSessionKey)` exclusively (never sending `adminDefaultPass`).

2. **RT-02 (Critical — Accept):** Read-only `wirelesscfg.json` (`ont-040NSv2-setup.jsonl:361, 436, 456`) defaults to `wl_unit=0` without `page=ssid.asp&wl_unit=<0|1>&wl_bssid=0`, contains unmasked `nvramWlWpaPsk`, iOS `resolveConnectedBandFromOnt` (`OntMobileDriver.swift:738-823`) omits `baseMacAddress`, and `distinctSsids` / `nvramWlSsid` shortcuts falsely pass Step 7 while still associated with `Band A`.
   - *Applied fix:* Unconditionally query `deviceinfo2.json` in Connection 1 on Android & iOS to capture trimmed `baseMacAddress` + radio BSSIDs; unconditionally enforce `wlAssociatedDevice == expectedBand` at Step 7 (except 2.4GHz-only fallback where `wl0` SSID is already renamed); post `page=ssid.asp&wl_unit=$unit&wl_bssid=0&sessionKey=$encKey` for read-only NVRAM checks (followed by `wlrestart.json` if needed); mask `nvramWlWpaPsk`, `nvramWlRadiusKey`, `nvramWlKey[1-4]?`.

3. **RT-03 (Critical — Accept):** Android GPS-off `hasActiveOntSubnetWifi()` shortcut (`OntWifiConnector.kt:256-268`), `onUnavailable` fallback (`:296-302`), unconditional `bindProcessNetworkSafe(null)` in `onLost` (`:322`), nullable `SocketFactory?` cellular leak (`UnsafeSslClient.kt:47-51`), and `rebindOntWifiSocketFactory` (`MainViewModel.kt:715-738`) hijack Connection 2 back to `Band A` or 4G/5G.
   - *Applied fix:* Remove `hasActiveOntSubnetWifi()` / `isSameOntBssid` / `bindCurrentWifiNetworkIfConnected()` shortcuts from `switchToConfiguredSecondaryWifi` unless `currentSysSsid.equals(targetSsid, ignoreCase = true)` and `network != connection1Network`; guard `bindProcessNetworkSafe(null)` inside `if (boundWifiNetwork == network)`; remove temporary API 26–28 `WifiConfiguration` on cleanup; enforce non-null `SocketFactory` on Android; pass `(ssid, passphrase, securityMode, band, timeoutMs = 25_000L)` into `rebindOntWifiSocketFactory`.

4. **RT-04 (Critical — Accept):** Trust-all TLS (`UnsafeSslClient.kt:17-21`, `UnsafeSslSession.swift:152-171`) transmits `adminNewPass` and `conn1SessionKey` during Step 7 before `baseMacAddress` is verified, exposing credentials to Evil-Twin APs or incrementing `numberLogin` on neighbor ONTs; `joinOnce = false` persists Open profiles on iOS.
   - *Applied fix:* Capture leaf X.509 SHA-256 fingerprint in Connection 1 and pin it in Connection 2's TLS trust check (`pinnedCertSha256`) so rogue/neighbor ONTs fail at TLS handshake before `login.cmd`; set `NEHotspotConfiguration.joinOnce = (securityMode == "open")` (and `removeConfiguration(forSSID:)` on cleanup when `open`).

5. **RT-05 (Critical — Accept):** Direct `en0` check inside `OntMobileDriver.swift`, missing `protocolClasses` / `finishTasksAndInvalidate()` / streamed upload support in `UnsafeSslSession.uploadMultipartFile`, `!isAppInBackground()` check in `isPostFlushRadioDrop` (`OntMobileDriver.swift:972`), `settlingDelayMs` branching (`tests/test_ios_app_parity.py:328`), and `120s-180s` `GlobalSettings` defaults hang JUnit/XCTest and exceed GitHub Actions' 25-min timeout (`.github/workflows/build-ios-ipa.yml:22`).
   - *Applied fix:* Inject `onSwitchToSecondaryWifi` into `OntMobileDriver.swift` (mirroring Android; fail-closed if `null` in production, fast stub across all 20 Kotlin + 11 Swift test callsites); add dedicated constructor params `wlRestartPollIntervalMs = 1000`, `maxWlRestartPolls = 30`, `userExistPollIntervalMs = 3000`; store `protocolClasses` + use `uploadTask(withStreamedRequest:)` + `finishTasksAndInvalidate()` in `UnsafeSslSession.swift`; ignore `isAppInBackground()` when checking `didFlushFullRequestBody` in `updateFirmware`.

6. **RT-06 (Critical — Accept):** `wpa_psk` on Android 10+ (`OntWifiConnector.kt:189`), `wpa3_psk` on non-SAE / API 26–28 (`:181`), and `open` in iOS `NEHotspotConfiguration` fail at Step 7 after Steps 1–6 already mutated the ONT.
   - *Applied fix:* Validate OS Wi-Fi handover capability **before Step 1** in `MainViewModel.runOntConfiguration()`; branch `securityMode == "open"` to `NEHotspotConfiguration(ssid: ssid)` on iOS.

7. **RT-07 (High — Accept):** iOS Step 7 background polling thrashes `Band A` session lock (`MainViewModel.swift:345`), expired `beginBackgroundTask` is not renewed for Step 9, `FileProtectionType.complete` (`AppDatabase.swift:25`) locks files 10s after screen lock in iOS Settings, and pasteboard copy lacked `@MainActor` + `.localOnly: true` (`ExecutionProgressSheet.swift:202`, `test_ios_app_parity.py:132`).
   - *Applied fix:* Pause background `login.cmd` polling once `Band A` is seen until `en0` IPv4 changes / `NWPathMonitor` / `onScenePhaseActive()`; renew `beginBackgroundTask` on `onScenePhaseActive()`; use `FileProtectionType.completeUntilFirstUserAuthentication` + `withTaskCancellationHandler` + `startAccessingSecurityScopedResource()`; copy password on `@MainActor` with `[.localOnly: true, .expirationDate: Date().addingTimeInterval(120)]` only when `requiresPassword && !entitledJoin`.

8. **RT-08 (High — Accept):** Rejecting `!is5GHzBandSupported()` (`build.gradle.kts:13` `minSdk = 26`) bricks 100% of 2.4GHz-only Android phones, and missing `defer` on `probePauseMutex` in `OntMobileDriver.swift:1144` risks mutex deadlock.
   - *Applied fix:* On 2.4GHz-only Android phones (`!is5GHzBandSupported()`), Connection 1 configures `5GHz` (`wl_unit=1`) then renames `2.4GHz` (`wl_unit=0`), Step 7 reconnects to the newly renamed `2.4GHz` SSID (`wifi24Ssid`), Connection 2 logs in with `adminNewPass`, verifies both bands in NVRAM, and runs Step 9 `updateFirmware()`; wrap `probePauseMutex.unlock()` in `defer` on iOS.

9. **RT-09 (High — Accept):** Process-wide Android `startScan()` quota (`4 / 120s`) is exhausted across `OntWifiScannerDialog` (`MainViewModel.kt:593-609`), Phase A, and dead `verifyNewSsidBroadcast` (`OntWifiConnector.kt:565-605`).
   - *Applied fix:* Track `startScan()` timestamps in a process-wide 120s sliding window in `OntWifiConnector`, cap Phase A active scans to remaining quota (max 1–2 calls), and remove dead `onVerifyNewSsid`, `verifyNewSsidBroadcast`, and `step7StartMicros`.

10. **RT-10 (Medium — Accept):** Omitted callers & files: `MainViewModel.kt:956` (`!res.manualWifiStepPending`), `WifiGuideSheet.swift:120`, `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`, `tests/mock_luci_server.py:212`, `tests/test_ios_app_parity.py:106, 336-341`, and Android `cacheDir/exports/` PII `.xlsx` cleanup (`MainViewModel.kt:1141-1144`).
    - *Applied fix:* Added all cited files and callers to Phases 2, 3, and 4, guarded `action == "Apply"` in `mock_luci_server.py:212`, and added `cacheDir/exports/` cleanup on Android startup/export.
