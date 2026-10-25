---
title: "iOS Native ONT Wi-Fi Configurator & Unsigned IPA Pipeline"
date: 2026-10-25
summary: "Implemented Pure Swift + SwiftUI iOS ONT GW040-NSv2 wireless configurator in ios-app/ with raw DEFLATE Excel parser, serialized SQLite3, en0 routing, 7-step LuCI CGI driver, and GitHub Actions .ipa packaging"
---

# iOS Native ONT Wi-Fi Configurator & Unsigned IPA Pipeline

## What happened
Implemented the standalone iOS Native application (`ios-app/`, Pure Swift 5.9+ & SwiftUI, iOS 16.0+) achieving 100% feature and protocol parity with `android-app/` without modifying existing Android or Desktop code:
- **Phase 1 (Data & Validation):** Built `ZipArchive.swift` using C `zlib` raw DEFLATE (`windowBits = -MAX_WBITS = -15`, EOCD Central Directory parsing, and Zip Bomb / path traversal caps), `ExcelManager.swift` (`Foundation.XMLParser` with `shouldResolveExternalEntities = false`, 30-column `STANDARD_30_HEADERS` preservation, leading zero padding guard `!rawVal.isEmpty`, and masked 33-column report export), `OntInputValidator.swift` (two-stage validation, 11 LuCI shell metacharacters, ASCII control char rejection, 1-tap `0` prepend for 7-digit Wi-Fi passwords), and `AppDatabase.swift` (serialized `DispatchQueue` + `SQLITE_OPEN_FULLMUTEX` + `WAL` + atomic `BEGIN IMMEDIATE TRANSACTION` + `isExcludedFromBackup = true`).
- **Phase 2 (Wi-Fi `en0` Routing & 7-Step LuCI Driver):** Built `IosWifiMonitor.swift` (`getifaddrs("en0")` `192.168.1.x` check, `utun*`/`ipsec*`/`ppp*` VPN detection, `awaitWifiIpv4Ready`), `UnsafeSslSession.swift` (`allowsCellularAccess = false`, HTTP redirect blocking, self-signed TLS trust scoped to `192.168.1.1`/`localhost`, and `didSendBodyData` `totalBytesSent` tracking), and `OntMobileDriver.swift` (7-step LuCI CGI flow with connected Wi-Fi band configured last in Step 7, `settlingDelayMs = 200`, `ensureGatewayReadyBeforeStep7`, per-record password cache, and detached `logout.cmd` cleanup).
- **Phase 3 (SwiftUI Screens & Background Safety):** Built `MainViewModel.swift` (`beginBackgroundTask`, `isIdleTimerDisabled`, `probePauseMutex.tryLock()`, thread-safe `LockedBox` state), `SubscriberListView.swift`, `ConfigReviewView.swift` (inline `2.4GHz / 5GHz` selector above RUN and 1-tap zero prepend), `WifiGuideSheet.swift`, `ExecutionProgressSheet.swift` (expiring `.localOnly` pasteboard), `FooterSignatureBar.swift`, and `ONTWiFiConfiguratorApp.swift` (background privacy blur and `XCTestConfigurationFilePath` guard).
- **Phase 4 (Tests, Xcode Project & GitHub Actions `.ipa`):** Created `ExcelAndValidatorTests.swift`, `OntMobileDriverTests.swift` (`MockLuciURLProtocol` draining `httpBodyStream`), `ios-app/project.yml`, `ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj`, `ios-app/scripts/package_unsigned_ipa.sh`, `.github/workflows/build-ios-ipa.yml`, and `tests/test_ios_app_parity.py`.

## Decision
Used zero external dependencies (native `libz` + `libsqlite3` + `XMLParser` + `getifaddrs`) and zero paid Apple Developer entitlements so the resulting `ONT_WiFi_Configurator_Unsigned.ipa` can be sideloaded with a free Apple ID via Sideloadly, AltStore, or TrollStore.

## Next steps
Push branch or trigger `.github/workflows/build-ios-ipa.yml` on GitHub Actions (`macos-14`) to download the compiled `ONT_WiFi_Configurator_Unsigned.ipa` artifact for field iPhones.

> Historical work record — not durable authority. Prefer docs/specs/ADRs for current decisions.
