# Research Report 02: iOS Native Architecture, Sideload Constraints & Cross-Platform Parity

## Summary
Analysis of `ios-app/`, `.github/workflows/build-ios-ipa.yml`, `ios-app/scripts/package_unsigned_ipa.sh`, `tests/test_ios_app_parity.py`, `README.md`, and `HUONG_DAN_SU_DUNG_TOOL_ONT.md`.

## Key Findings

### 1. Firmware Update Gap on `ios-app/`
- `ios-app/` currently implements only the 7-step single-connection setup (`OntMobileDriver.swift:1117-1305`, `MainViewModel.swift:22-32`) and has **no** firmware fields in `GlobalSettings.swift`, no firmware validator rules in `OntInputValidator.swift`, no streaming multipart uploader in `UnsafeSslSession.swift`, and no firmware UI in `ConfigReviewView.swift` / `ExecutionProgressSheet.swift`.
- `UnsafeSslSession.swift:40-61` currently uses `session.dataTask` with fixed `5,000 ms` request/resource timeouts. Uploading 16–35 MB `.signed` firmware images requires streaming assembly to a temporary multipart file (`32 KB` chunks, explicit `Content-Length` to avoid chunked-encoding issues on `uHTTPd`, `defer` cleanup) via `URLSession.uploadTask(with:fromFile:)` with `180,000 ms` timeout and `allowsCellularAccess = false`.

### 2. Unsigned `.ipa` Entitlement Constraints & Hybrid Handover Strategy
- `ios-app/scripts/package_unsigned_ipa.sh:22-31, 50` and `.github/workflows/build-ios-ipa.yml:44-63` build with `CODE_SIGNING_ALLOWED=NO` and bare ad-hoc `codesign --force --sign -`. Embedding paid `com.apple.developer.networking.HotspotConfiguration` in `.entitlements` would break Sideloadly/AltStore free-Apple-ID installation (`0xe8008016`).
- Linking public `NetworkExtension.framework` in `ios-app/project.yml` **without** restricted `.entitlements` compiles cleanly with `CODE_SIGNING_ALLOWED=NO`:
  - Best-effort `NEHotspotConfigurationManager.shared.apply` works when signed with TrollStore / Paid Developer profile, and returns an immediate `NSError` (`<10ms`) on free Sideloadly/AltStore builds without crashing.
  - On fallback (or user cancel), `ExecutionProgressSheet.swift` transitions into an **Auto-Detect Handover Card** with a **180s wall-clock (`Date()`) timeout** + instant wake hook in `MainViewModel.onScenePhaseActive()` (`MainViewModel.swift:147-152`), 1-tap Copy Password + Open iOS Wi-Fi Settings (`IosWifiMonitor.swift:147-153`), and a 5-check ground-truth loop (`en0` subnet $\rightarrow$ `probeGateway` $\rightarrow$ `login("admin", adminNewPass)` $\rightarrow$ `dhcpclient.json` live `wlAssociatedDevice` == `secondaryBand` + `deviceinfo2.json` `baseMacAddress` match) so Connection 2 **never** fires prematurely while still associated with `Band A`.

### 3. Parity Test & Documentation Surfaces
- `tests/test_ios_app_parity.py:250-350` (`test_phase6_mobile_wifi_keepalive_band_ground_truth_and_save_before_disconnect_parity`) explicitly asserts absence of band-hopping symbols and doc phrases from commit `01bcb18`; Phase 4 must update these assertions to enforce the new 2-connection + final firmware parity across `android-app/`, `ios-app/`, `README.md` (`5B`, `5C`), and `HUONG_DAN_SU_DUNG_TOOL_ONT.md` (`6B`, `6C`).
