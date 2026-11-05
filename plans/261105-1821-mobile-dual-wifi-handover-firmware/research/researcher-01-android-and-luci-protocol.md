# Research Report 01: Android Wi-Fi Handover & GW040-NSv2 LuCI CGI Protocol

## Summary
Analysis of `android-app/`, `app/core/ont_driver.py`, `ont-040NSv2-setup.jsonl`, `update_firmware_040nsv2.jsonl`, and git commit `01bcb18` (`01bcb18^` vs `HEAD`) to ground the 2-connection Dual-Wi-Fi + Final Firmware Update architecture.

## Key Findings

### 1. Why Previous Single-Connection & Old Band-Hopping Both Fell Short
1. **Current 8-Step Firmware Mode (`OntMobileDriver.kt:1368-1471`)**:
   - Uploads firmware at Step 7 while staying on initial `Band A`, leaving `Band A` unconfigured (`manualWifiStepPending = true` at `OntMobileDriver.kt:1460`) and requiring manual post-reboot Wi-Fi configuration (`MainViewModel.kt:978-1127`).
2. **Why Pre-`01bcb18` Band-Hopping Timed Out (`01bcb18^:OntWifiConnector.kt:390-526`)**:
   - Hardcoded `3.5s` scan poll + `25s` total `withTimeout`: on GW040-NSv2, 5GHz `chanspec = "auto"` frequently selects DFS channels `100` or `120` (`ont-040NSv2-setup.jsonl:152`, `update_firmware_040nsv2.jsonl:255`), where Broadcom `wlmngr` + `acsd` + DFS CAC takes **15–65s** before beacons appear.
   - Fired `wifiManager.startScan()` at `0s` before `wlmngr` restarted, burning Android 9+'s `4 scans / 2 min` foreground quota.
   - Registered `WifiNetworkSuggestion` simultaneously with `WifiNetworkSpecifier`, causing OS L2 association races (`onUnavailable()`) on Android 11–14.
   - `isSystemConnectedToTargetWifi` read only deprecated `wifiManager.connectionInfo?.frequency` (`-1` when GPS off), returning `false` and triggering destructive re-switches.
   - `MainViewModel.onRebindWifiNetwork` (`MainViewModel.kt:795, 834-840`) captured factory `effectiveSsid` before Step 1 and never updated it to the secondary SSID after handover.
   - Missing `!wifiManager.is5GHzBandSupported` guard and missing Android 8.0–9.0 (`API 26–28`) `WifiConfiguration` fallback (`minSdk = 26` at `android-app/app/build.gradle.kts:13`).

### 2. Measured Hardware Timings (`ont-040NSv2-setup.jsonl` & `update_firmware_040nsv2.jsonl`)
- `wirelesscfg.json` (`action=Apply`): `204–214 ms` HTTP response; `wireless.js` (`ont-040NSv2-setup.jsonl:361`) polls `wlrestart.json` every `1,000 ms` up to **`30,000 ms`** (`load_time = 30*1000`).
- `dhcpclient.json`: `309–627 ms`; `deviceinfo2.json`: `1,127–1,372 ms` (requires 5s `client`, never 1.5s `logoutClient`).
- `logout.cmd`: immediately frees single-admin session lock (`60s` idle TTL); accepts `lastActiveSessionKey` from any client IP/MAC on `br-lan`.
- `uploadsoftware.conf`: `11.74 s` over LAN (`update_firmware_040nsv2.jsonl:118-124`), ~15–45s over Wi-Fi; `180,000 ms` upload timeout provides safe margin for weak RSSI / 35 MB images.
- Post-upload flash + reboot: `136.7 s` (`2m 16.7s` to first HTTP 200 at `update_firmware_040nsv2.jsonl:143`, `~2m 25s` to full LuCI readiness at `:215`), preserving all NVRAM Wi-Fi/WAN/SLID settings across upgrade.

## Recommendations
1. Use a **120s** configurable handover budget (`wifiHandoverTimeoutMs = 120_000L`) split into throttle-safe beacon discovery + multi-attempt `WifiNetworkSpecifier` (API 29+) / `WifiConfiguration` (API 26–28) association + `5s` DHCP IPv4 wait + `45s` `probeGateway()` retry window.
2. Cleanly `logout()` at end of Connection 1 while `Band A` is still untouched, preserve `lastActiveSessionKey` for instant `"user exist"` release on Connection 2 (with `65s` idle-expiry fallback), and authenticate Connection 2 strictly with `settings.adminNewPass` (never retrying `adminDefaultPass`).
3. On Connection 2 (`Band B`), configure remaining `Band A`, run a **60s** `ensureGatewayReadyOnSecondaryBand` gate (rebinding `Band B`'s `SocketFactory` + refreshing session if `wlmngr` bounced radios), then execute `updateFirmware()` (`180s` upload timeout) as the final step and clear session keys immediately upon `"status": "valid"`.
