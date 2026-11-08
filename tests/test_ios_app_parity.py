import os
import zipfile
from pathlib import Path
import pytest

ROOT_DIR = Path(__file__).resolve().parent.parent
IOS_DIR = ROOT_DIR / "ios-app"
IOS_APP_DIR = IOS_DIR / "ONTWiFiConfigurator"
IOS_TESTS_DIR = IOS_DIR / "ONTWiFiConfiguratorTests"


def test_phase1_project_structure_and_fixtures():
    """Verify Phase 1 directory structure, Info.plist security flags, and Excel fixtures."""
    info_plist = IOS_APP_DIR / "Info.plist"
    assert info_plist.exists(), "Missing ios-app/ONTWiFiConfigurator/Info.plist"
    plist_text = info_plist.read_text(encoding="utf-8")
    assert "NSLocalNetworkUsageDescription" in plist_text
    assert "<key>UIFileSharingEnabled</key>" in plist_text
    assert "<key>ITSAppUsesNonExemptEncryption</key>" in plist_text

    demo_xlsx = IOS_APP_DIR / "Resources" / "sample-demo.xlsx"
    assert demo_xlsx.exists(), "Missing bundled sample-demo.xlsx"

    test_xlsx = IOS_TESTS_DIR / "Resources" / "file-mau.xlsx"
    assert test_xlsx.exists(), "Missing test fixture file-mau.xlsx in ONTWiFiConfiguratorTests/Resources"
    assert not (IOS_APP_DIR / "Resources" / "file-mau.xlsx").exists(), (
        "Real PII file-mau.xlsx must NEVER be placed in production ONTWiFiConfigurator/Resources"
    )


def test_phase1_zip_archive_and_excel_manager_contracts():
    """Verify ZipArchive raw DEFLATE (-MAX_WBITS / -15), Zip Bomb guards, and ExcelManager 30-column parity."""
    zip_swift = (IOS_APP_DIR / "Data" / "Excel" / "ZipArchive.swift").read_text(encoding="utf-8")
    assert "inflateInit2_" in zip_swift
    assert "deflateInit2_" in zip_swift
    assert "-MAX_WBITS" in zip_swift or "-15" in zip_swift
    assert "128" in zip_swift  # max entries cap
    assert ".." in zip_swift  # path traversal check

    excel_swift = (IOS_APP_DIR / "Data" / "Excel" / "ExcelManager.swift").read_text(encoding="utf-8")
    assert "STANDARD_30_HEADERS" in excel_swift
    assert "deriveWifi5Ssid" in excel_swift
    assert "shouldResolveExternalEntities = false" in excel_swift
    assert "!rawVal.isEmpty" in excel_swift
    assert "Trạng thái cấu hình" in excel_swift
    assert "Thời gian thực hiện" in excel_swift
    assert "Ghi chú chi tiết" in excel_swift


def test_phase1_validator_and_sqlite_contracts():
    """Verify Two-Stage Validation, 11 shell metacharacters, and serialized SQLite3 AppDatabase."""
    val_swift = (IOS_APP_DIR / "Core" / "Validation" / "OntInputValidator.swift").read_text(encoding="utf-8")
    assert "validateOnImport" in val_swift
    assert "validateBeforeRun" in val_swift
    assert "prependLeadingZeroToWifiPass" in val_swift
    assert "urlEncodeFormValue" in val_swift
    assert "ontEncodeQuery" in val_swift
    assert "maskSensitiveCredentials" in val_swift
    assert "Thiếu Số máy định danh" not in val_swift
    assert '("Số máy",' not in val_swift
    assert "0x20" in val_swift and "0x7E" in val_swift

    db_swift = (IOS_APP_DIR / "Data" / "Local" / "AppDatabase.swift").read_text(encoding="utf-8")
    assert "DispatchQueue(label:" in db_swift
    assert "SQLITE_OPEN_FULLMUTEX" in db_swift
    assert "PRAGMA journal_mode=WAL" in db_swift
    assert "BEGIN IMMEDIATE TRANSACTION" in db_swift
    assert "isExcludedFromBackup = true" in db_swift
    assert "lastKnownAdminPass" in db_swift
    assert "unifiedSlid" in db_swift

def test_phase2_wifi_en0_monitor_and_unsafe_ssl_session_contracts():
    """Verify IosWifiMonitor getifaddrs('en0'), VPN detection, awaitWifiIpv4Ready, and UnsafeSslSession."""
    wifi_swift = (IOS_APP_DIR / "Core" / "Wifi" / "IosWifiMonitor.swift").read_text(encoding="utf-8")
    assert "getifaddrs" in wifi_swift
    assert '"en0"' in wifi_swift
    assert "utun" in wifi_swift
    assert "awaitWifiIpv4Ready" in wifi_swift
    assert "192.168.1." in wifi_swift

    ssl_swift = (IOS_APP_DIR / "Core" / "Driver" / "UnsafeSslSession.swift").read_text(encoding="utf-8")
    assert "allowsCellularAccess = false" in ssl_swift
    assert "willPerformHTTPRedirection" in ssl_swift
    assert "completionHandler(nil)" in ssl_swift
    assert "didSendBodyData" in ssl_swift
    assert "totalBytesSent" in ssl_swift
    assert "protocolClasses" in ssl_swift


def test_phase2_ont_mobile_driver_7step_protocol_contracts():
    """Verify 7-step LuCI CGI sequence, serviceId=1/0, per-record password cache, and mandatory logout."""
    driver_swift = (IOS_APP_DIR / "Core" / "Driver" / "OntMobileDriver.swift").read_text(encoding="utf-8")
    assert "serviceId=1" in driver_swift
    assert "serviceId=0" in driver_swift
    assert "/cgi-bin/luci/login.cmd" in driver_swift
    assert "/cgi-bin/luci/accesscontrolcfg.json" in driver_swift
    assert "/cgi-bin/luci/wancfg.json" in driver_swift
    assert "/cgi-bin/luci/editwan.json" in driver_swift
    assert "/cgi-bin/luci/addwan.json" in driver_swift
    assert "/cgi-bin/luci/sendwan.cmd" in driver_swift
    assert "/cgi-bin/luci/gponpasswd.json" in driver_swift
    assert "/cgi-bin/luci/savelanv4cfg.cmd" in driver_swift
    assert "/cgi-bin/luci/wirelesscfg.json" in driver_swift
    assert "/cgi-bin/luci/wlrestart.json" in driver_swift
    assert "/cgi-bin/luci/logout.cmd" in driver_swift
    assert "ensureGatewayReadyOnSecondaryBand" in driver_swift
    assert "Task.detached" in driver_swift
    assert "recordPasswordCache" in driver_swift
    assert "recordCacheKey(rowIndex: record.rowIndex, soMay: record.slid)" in driver_swift

def test_phase3_swiftui_screens_and_viewmodel_contracts():
    """Verify MainViewModel background task, ConfigReviewView band selector & 1-tap 0, and Privacy Blur."""
    vm_swift = (IOS_APP_DIR / "UI" / "MainViewModel.swift").read_text(encoding="utf-8")
    assert "beginBackgroundTask" in vm_swift
    assert "isIdleTimerDisabled = true" in vm_swift
    assert "probePauseMutex.tryLock()" in vm_swift
    assert "settlingDelayMs: 200" in vm_swift

    list_swift = (IOS_APP_DIR / "UI" / "Screens" / "SubscriberListView.swift").read_text(encoding="utf-8")
    assert "startAccessingSecurityScopedResource" in list_swift
    assert "stopAccessingSecurityScopedResource" in list_swift
    assert "SLID:" in list_swift

    review_swift = (IOS_APP_DIR / "UI" / "Screens" / "ConfigReviewView.swift").read_text(encoding="utf-8")
    assert "2.4GHz" in review_swift and "5GHz" in review_swift
    assert "Thêm số 0 ở đầu" in review_swift
    assert "autocorrectionDisabled(true)" in review_swift
    assert "Số máy (SO_MAY)" not in review_swift
    assert "SLID / Mã MEN" in review_swift
    sheet_swift = (IOS_APP_DIR / "UI" / "Components" / "ExecutionProgressSheet.swift").read_text(encoding="utf-8")
    assert "interactiveDismissDisabled" in sheet_swift
    assert "localOnly" in sheet_swift

    footer_swift = (IOS_APP_DIR / "UI" / "Components" / "FooterSignatureBar.swift").read_text(encoding="utf-8")
    assert "Trung tâm viễn thông Sơn Tây - Đỗ Xuân Thịnh - 0946827186" in footer_swift

    app_swift = (IOS_APP_DIR / "ONTWiFiConfiguratorApp.swift").read_text(encoding="utf-8")
    assert "XCTestConfigurationFilePath" in app_swift
    assert "blur" in app_swift


def test_phase4_xcode_project_ipa_script_and_github_actions_workflow():
    """Verify project.yml, project.pbxproj, package_unsigned_ipa.sh, and build-ios-ipa.yml."""
    project_yml = IOS_DIR / "project.yml"
    assert project_yml.exists(), "Missing ios-app/project.yml"
    yml_text = project_yml.read_text(encoding="utf-8")
    assert "ONTWiFiConfigurator" in yml_text
    assert "ONTWiFiConfiguratorTests" in yml_text
    assert "sample-demo.xlsx" in yml_text
    assert "sqlite3" in yml_text and "z" in yml_text
    assert "BUNDLE_EXECUTABLE_PATH_NA" not in yml_text
    assert "ONTWiFiConfigurator.app/ONTWiFiConfigurator" in yml_text

    pbxproj = IOS_DIR / "ONTWiFiConfigurator.xcodeproj" / "project.pbxproj"
    assert pbxproj.exists(), "Missing ios-app/ONTWiFiConfigurator.xcodeproj/project.pbxproj"
    pbx_text = pbxproj.read_text(encoding="utf-8")
    assert "ONTWiFiConfigurator" in pbx_text
    assert "SubscriberListView.swift" in pbx_text
    assert "PBXTargetDependency" in pbx_text
    assert "OntMobileDriver.swift" in pbx_text

    ipa_script = IOS_DIR / "scripts" / "package_unsigned_ipa.sh"
    assert ipa_script.exists(), "Missing ios-app/scripts/package_unsigned_ipa.sh"
    script_text = ipa_script.read_text(encoding="utf-8")
    assert "CODE_SIGNING_ALLOWED=NO" in script_text
    assert "file-mau.xlsx" in script_text
    assert "codesign --force --sign -" in script_text
    assert "ONT_WiFi_Configurator_Unsigned.ipa" in script_text
    assert "SHA256SUMS.txt" in script_text

    workflow = ROOT_DIR / ".github" / "workflows" / "build-ios-ipa.yml"
    assert workflow.exists(), "Missing .github/workflows/build-ios-ipa.yml"
    wf_text = workflow.read_text(encoding="utf-8")
    assert "macos-latest" in wf_text or "macos-14" in wf_text
    assert "permissions:" in wf_text and "contents: read" in wf_text
    assert "xcrun simctl list devices available" in wf_text
    assert "package_unsigned_ipa.sh" in wf_text
    assert "ONT_WiFi_Configurator_Unsigned.ipa" in wf_text


def test_phase4_dual_excel_fixtures_and_demo_bundle_integrity():
    """Verify both root file-mau.xlsx (sharedStrings+styles) and test file-mau.xlsx (inlineStr) + sample-demo.xlsx."""
    root_xlsx = ROOT_DIR / "file-mau.xlsx"
    test_xlsx = IOS_TESTS_DIR / "Resources" / "file-mau.xlsx"
    demo_xlsx = IOS_APP_DIR / "Resources" / "sample-demo.xlsx"

    with zipfile.ZipFile(root_xlsx, "r") as zf:
        names = set(zf.namelist())
        assert "xl/sharedStrings.xml" in names
        assert "xl/styles.xml" in names
        assert "xl/worksheets/sheet1.xml" in names

    with zipfile.ZipFile(test_xlsx, "r") as zf:
        names = set(zf.namelist())
        assert "xl/worksheets/sheet1.xml" in names
        sheet_xml = zf.read("xl/worksheets/sheet1.xml").decode("utf-8")
        assert "001560828" in sheet_xml
        assert "kimthitv878" in sheet_xml
        for seven_digit in ["3011965", "3820275", "5091993", "4091981", "8042020"]:
            assert seven_digit in sheet_xml

    with zipfile.ZipFile(demo_xlsx, "r") as zf:
        sheet_xml = zf.read("xl/worksheets/sheet1.xml").decode("utf-8")
        assert "kimthitv878" not in sheet_xml, "Demo Excel must not contain real customer PII"


def test_phase4_documentation_includes_ios_sideload_and_operation_guide():
    """Verify README.md and HUONG_DAN_SU_DUNG_TOOL_ONT.md include iOS .ipa Sideload guide."""
    readme = (ROOT_DIR / "README.md").read_text(encoding="utf-8")
    huong_dan = (ROOT_DIR / "HUONG_DAN_SU_DUNG_TOOL_ONT.md").read_text(encoding="utf-8")
    assert "ONT_WiFi_Configurator_Unsigned.ipa" in readme
    assert "Sideloadly" in readme
    assert "ONT_WiFi_Configurator_Unsigned.ipa" in huong_dan
    assert "Sideloadly" in huong_dan


def test_phase5_wifi_security_mode_cross_platform_parity():
    """Verify 6 Wi-Fi security modes parity across iOS GlobalSettings, Validator, Driver, SwiftUI, and Docs."""
    settings_swift = (IOS_APP_DIR / "Data" / "Model" / "GlobalSettings.swift").read_text(encoding="utf-8")
    assert "public enum WifiSecurityMode: String, Codable, CaseIterable, Sendable" in settings_swift
    for mode_val in ["wpa2_wpa3_psk", "wpa2_psk", "wpa_wpa2_psk", "wpa3_psk", "wpa_psk", "open"]:
        assert f'"{mode_val}"' in settings_swift
    assert "tkip+aes" in settings_swift
    assert "decodeIfPresent(String.self, forKey: .wifiSecurityMode)" in settings_swift

    val_swift = (IOS_APP_DIR / "Core" / "Validation" / "OntInputValidator.swift").read_text(encoding="utf-8")
    assert "WifiSecurityMode(rawValue:" in val_swift
    assert "requiresPassword" in val_swift

    driver_swift = (IOS_APP_DIR / "Core" / "Driver" / "OntMobileDriver.swift").read_text(encoding="utf-8")
    assert "public let wifiSecurityMode: String" in driver_swift
    assert "conflict_mesh" in driver_swift
    assert "catch let err as OntDriverException" in driver_swift
    assert "secMode: String" in driver_swift

    review_swift = (IOS_APP_DIR / "UI" / "Screens" / "ConfigReviewView.swift").read_text(encoding="utf-8")
    assert "Chuẩn bảo mật Wi-Fi" in review_swift
    assert "WifiSecurityMode.allCases" in review_swift

    sheet_swift = (IOS_APP_DIR / "UI" / "Components" / "ExecutionProgressSheet.swift").read_text(encoding="utf-8")
    assert "wifiSecurityMode" in sheet_swift
    assert "Không mật khẩu" in sheet_swift

    readme = (ROOT_DIR / "README.md").read_text(encoding="utf-8")
    huong_dan = (ROOT_DIR / "HUONG_DAN_SU_DUNG_TOOL_ONT.md").read_text(encoding="utf-8")
    assert "wpa2_wpa3_psk" in readme and "wpa2_psk" in readme
    assert "Chuẩn bảo mật Wi-Fi" in huong_dan


def test_phase6_two_connection_dual_wifi_handover_and_final_firmware_cross_platform_parity():
    """Verify 2-connection Dual-Wi-Fi handover + final-step Firmware Update parity across Android, iOS, mock server, XCTests, and docs."""
    android_driver = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "driver"
        / "OntMobileDriver.kt"
    ).read_text(encoding="utf-8")
    android_ssl = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "driver"
        / "UnsafeSslClient.kt"
    ).read_text(encoding="utf-8")
    android_wifi = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "wifi"
        / "OntWifiConnector.kt"
    ).read_text(encoding="utf-8")
    android_vm = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "ui"
        / "MainViewModel.kt"
    ).read_text(encoding="utf-8")
    ios_driver = (IOS_APP_DIR / "Core" / "Driver" / "OntMobileDriver.swift").read_text(encoding="utf-8")
    ios_ssl = (IOS_APP_DIR / "Core" / "Driver" / "UnsafeSslSession.swift").read_text(encoding="utf-8")
    ios_wifi = (IOS_APP_DIR / "Core" / "Wifi" / "IosWifiMonitor.swift").read_text(encoding="utf-8")
    ios_vm = (IOS_APP_DIR / "UI" / "MainViewModel.swift").read_text(encoding="utf-8")
    ios_tests = (IOS_TESTS_DIR / "OntMobileDriverTests.swift").read_text(encoding="utf-8")
    ios_val_tests = (IOS_TESTS_DIR / "ExcelAndValidatorTests.swift").read_text(encoding="utf-8")

    # 1. Clean cutover: zero legacy manual Wi-Fi / single-connection bounce symbols on BOTH platforms
    for removed_symbol in [
        "manualWifiStepPending",
        "executeManualConnectedWifiOnly",
        "verifyNewSsidBroadcast",
        "onVerifyNewSsid",
        "alreadyConfiguredOppositeBand",
        "ensureGatewayReadyBeforeStep7",
        "reloginAfterRadioBounce",
        "applyFinalConnectedBandWithRetries",
        "runManualConnectedWifiStep",
        "manualWifiError",
        "onSwitchToConfiguredWifi",
        "switchToSecondaryWifiAndAuthenticate",
        "switchToConfiguredOntWifi",
        "isSystemConnectedToTargetWifi",
        "hasSwitchedToSecondaryBand",
    ]:
        assert removed_symbol not in android_driver, f"Obsolete symbol {removed_symbol} still in OntMobileDriver.kt"
        assert removed_symbol not in android_wifi, f"Obsolete symbol {removed_symbol} still in OntWifiConnector.kt"
        assert removed_symbol not in android_vm, f"Obsolete symbol {removed_symbol} still in MainViewModel.kt"
        assert removed_symbol not in ios_driver, f"Obsolete symbol {removed_symbol} still in OntMobileDriver.swift"
        assert removed_symbol not in ios_vm, f"Obsolete symbol {removed_symbol} still in MainViewModel.swift"

    # 2. Shared 2-connection + final firmware + RT-01..RT-05 contracts on BOTH platforms
    for shared_token in [
        "resolveConnectedBandFromOnt",
        "authenticateSecondConnection",
        "ensureGatewayReadyOnSecondaryBand",
        "verifySecondConnectionBandAndMac",
        "readConfiguredBandSsidFromNvram",
        "onSwitchToSecondaryWifi",
        "wlRestartPollIntervalMs",
        "maxWlRestartPolls",
        "userExistPollIntervalMs",
        "recoveryRetryDelayMs",
        "lastActiveSessionKey",
        "/cgi-bin/luci/dhcpclient.json",
        "/cgi-bin/luci/deviceinfo2.json",
        "/cgi-bin/luci/updatefwsessioncheck.json",
        "/cgi-bin/luci/imagesize.json",
        "/cgi-bin/luci/uploadsoftware.conf",
        "wlAssociatedDevice",
        "wirelessInfoList",
        "nvramWlSsid",
        "fail authen",
        "localWifiIpv4",
        "onBandResolved",
    ]:
        assert shared_token in android_driver, f"Missing {shared_token} in OntMobileDriver.kt"
        assert shared_token in ios_driver, f"Missing {shared_token} in OntMobileDriver.swift"

    assert "pinnedCertSha256" in android_ssl and "pinnedCertSha256" in ios_ssl
    assert "switchToConfiguredSecondaryWifi" in android_wifi
    assert "validateHandoverSecurityCapability" in android_wifi
    assert "tryJoinWifiIfEntitled" in ios_wifi and "joinOnce" in ios_wifi
    assert "defaultEightStepsDualWifi" in android_vm and "defaultEightStepsDualWifi" in ios_vm
    assert "defaultNineStepsWithFirmware" in android_vm and "defaultNineStepsWithFirmware" in ios_vm

    # 3. iOS specific RT-05, RT-07, RT-08, RT-10 invariants
    assert "defer { probePauseMutex?.unlock() }" in ios_driver
    assert "IosWifiMonitor" not in ios_driver, "OntMobileDriver.swift must never call IosWifiMonitor directly"
    assert "NSURLErrorSecureConnectionFailed" not in ios_driver
    assert "settlingDelayMs >= 500" not in ios_driver and "settlingDelayMs >= 100" not in ios_driver
    assert "settlingDelayMs >= 500" not in android_driver and "settlingDelayMs >= 100" not in android_driver
    assert "settlingDelayMs: 200" in ios_vm
    assert "isOnOntSubnet" in ios_vm
    assert "suppressProbeAfterCompletion" in ios_vm
    assert "completeFileProtectionUntilFirstUserAuthentication" in ios_vm
    assert ".completeFileProtection]" not in ios_vm

    db_swift = (IOS_APP_DIR / "Data" / "Local" / "AppDatabase.swift").read_text(encoding="utf-8")
    assert "completeUntilFirstUserAuthentication" in db_swift
    assert "FileProtectionType.complete]" not in db_swift

    yml_text = (IOS_DIR / "project.yml").read_text(encoding="utf-8")
    pbx_text = (IOS_DIR / "ONTWiFiConfigurator.xcodeproj" / "project.pbxproj").read_text(encoding="utf-8")
    assert "NetworkExtension.framework" in yml_text and "NetworkExtension" in pbx_text
    assert "CODE_SIGN_ENTITLEMENTS" not in yml_text and "CODE_SIGN_ENTITLEMENTS" not in pbx_text
    assert not list(IOS_DIR.rglob("*.entitlements")), "Paid .entitlements must never be added to unsigned Sideloadly .ipa"

    # 4. Live MockLuCIServer read-only NVRAM wirelesscfg.json behavioral check + XCTest coverage
    import json
    import urllib.request
    from tests.mock_luci_server import run_mock_server

    srv, _thread, base_url = run_mock_server()
    try:
        apply_req = urllib.request.Request(
            f"{base_url}/cgi-bin/luci/wirelesscfg.json",
            data=b"page=ssid.asp&action=Apply&wl_unit=1&wl_ssid=VNPT_5G_Test&wl_wpa_psk=12345678&sec_mode=wpa2_wpa3_psk",
            method="POST",
        )
        with urllib.request.urlopen(apply_req, timeout=3) as resp:
            assert json.loads(resp.read().decode("utf-8"))["status"] == "OK"

        read_req = urllib.request.Request(
            f"{base_url}/cgi-bin/luci/wirelesscfg.json",
            data=b"page=ssid.asp&wl_unit=1&wl_bssid=0&sessionKey=sess1",
            method="POST",
        )
        with urllib.request.urlopen(read_req, timeout=3) as resp:
            read_json = json.loads(resp.read().decode("utf-8"))
            assert read_json.get("nvramWlSsid") == "VNPT_5G_Test"
            assert srv.wifi_configs["1"]["ssid"] == "VNPT_5G_Test", "Read-only query must NOT clobber saved SSID"
    finally:
        srv.shutdown()
        srv.server_close()

    assert "TestLockedBox" in ios_tests
    assert "rawData: Data" in ios_tests
    for xctest_name in [
        "testResolveConnectedBandFromOntParses2DWirelessInfoListAndOverridesMisdetectedBand",
        "testNineStepDualWifiAndStreamedBinaryFirmwareUploadFlow",
        "testStep7RejectsPrematureSecondConnectionWhileStillOnPrimaryBandWl0AndRejectsMismatchedBaseMac",
        "testUpdateFirmwarePostFlushDropWhileAppInBackgroundClearsSessionWithoutSendingLogoutCmd",
        "testStep7DistinctSsidsSucceedsEvenWhenDhcpInfoListReportsWl0AndWlAssociatedDeviceIsDelayed",
    ]:
        assert xctest_name in ios_tests, f"Missing {xctest_name} in OntMobileDriverTests.swift"
    assert "testGlobalSettingsFirmwareTimeoutCodableAndNvramCredentialMasking" in ios_val_tests

    # 5. Field documentation reflects 2-connection Dual-Wi-Fi + Final Firmware Update on both Android & iOS
    readme = (ROOT_DIR / "README.md").read_text(encoding="utf-8")
    huong_dan = (ROOT_DIR / "HUONG_DAN_SU_DUNG_TOOL_ONT.md").read_text(encoding="utf-8")
    for required_doc_phrase in ["2 lần kết nối", "Bước 9/9", "dhcpclient.json", "deviceinfo2.json"]:
        assert required_doc_phrase in readme, f"Missing '{required_doc_phrase}' in README.md"
        assert required_doc_phrase in huong_dan, f"Missing '{required_doc_phrase}' in HUONG_DAN_SU_DUNG_TOOL_ONT.md"
    for stale_doc_phrase in [
        "ensureGatewayReadyBeforeStep7",
        "Đổi nốt Wi-Fi chính",
        "thủ công sau khi ONT reboot",
    ]:
        assert stale_doc_phrase not in readme, f"Stale manual-step phrase '{stale_doc_phrase}' still in README.md"
        assert stale_doc_phrase not in huong_dan, f"Stale manual-step phrase '{stale_doc_phrase}' still in HUONG_DAN_SU_DUNG_TOOL_ONT.md"


def test_phase7_reconnect_readiness_spaced_backoff_and_diagnostic_logger_parity():
    """Verify ONT readiness stabilization, spaced-backoff reconnect, and synchronous diagnostic logger parity."""
    android_logger_path = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "logging"
        / "AppDiagnosticLogger.kt"
    )
    android_dialog_path = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "ui"
        / "components"
        / "DiagnosticLogDialog.kt"
    )
    ios_logger_path = IOS_APP_DIR / "Core" / "Logging" / "AppDiagnosticLogger.swift"
    ios_sheet_path = IOS_APP_DIR / "UI" / "Components" / "DiagnosticLogSheet.swift"

    assert android_logger_path.exists(), "Missing Android AppDiagnosticLogger.kt"
    assert android_dialog_path.exists(), "Missing Android DiagnosticLogDialog.kt"
    assert ios_logger_path.exists(), "Missing iOS AppDiagnosticLogger.swift"
    assert ios_sheet_path.exists(), "Missing iOS DiagnosticLogSheet.swift"

    android_logger = android_logger_path.read_text(encoding="utf-8")
    ios_logger = ios_logger_path.read_text(encoding="utf-8")
    for logger_token in [
        "DiagnosticLogEntry",
        "registerActiveSecrets",
        "clearActiveSecrets",
        "exportCombinedLogText",
        "clearLogs",
        "flushForTest",
        "ont_debug.log",
        "ont_debug.prev.log",
        "urlEncodeFormValue",
        "ontEncodeQuery",
        '"admin"',
    ]:
        assert logger_token in android_logger, f"Missing {logger_token} in AppDiagnosticLogger.kt"
        assert logger_token in ios_logger, f"Missing {logger_token} in AppDiagnosticLogger.swift"

    assert "completeUntilFirstUserAuthentication" in ios_logger
    assert "isExcludedFromBackup = true" in ios_logger
    assert "FileProtectionType.complete]" not in ios_logger

    ios_settings = (IOS_APP_DIR / "Data" / "Model" / "GlobalSettings.swift").read_text(encoding="utf-8")
    assert "wifiHandoverTimeoutMs: Int = 240_000" in ios_settings
    assert "gatewayPostHandoverTimeoutMs: Int = 60_000" in ios_settings
    assert "postSecondWifiSettleTimeoutMs: Int = 90_000" in ios_settings
    assert "?? 240_000" in ios_settings
    assert "?? 60_000" in ios_settings
    assert "?? 90_000" in ios_settings

    android_validator = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "validation"
        / "OntInputValidator.kt"
    ).read_text(encoding="utf-8")
    ios_validator = (IOS_APP_DIR / "Core" / "Validation" / "OntInputValidator.swift").read_text(encoding="utf-8")
    for key_token in ['"passphrase"', '"wpa_psk"', '"preSharedKey"']:
        assert key_token in android_validator, f"Missing {key_token} in OntInputValidator.kt"
        assert key_token in ios_validator, f"Missing {key_token} in OntInputValidator.swift"

    android_wifi = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "wifi"
        / "OntWifiConnector.kt"
    ).read_text(encoding="utf-8")
    ios_wifi = (IOS_APP_DIR / "Core" / "Wifi" / "IosWifiMonitor.swift").read_text(encoding="utf-8")
    assert "computeReconnectBackoffMs" in android_wifi
    assert "computeReconnectBackoffMs" in ios_wifi
    assert "HotspotJoinResult" in ios_wifi
    assert "tryJoinWifiDetailed" in ios_wifi
    assert "alreadyAssociated" in ios_wifi
    assert "entitlementUnavailable" in ios_wifi
    assert "transientFailure" in ios_wifi

    android_driver = (
        ROOT_DIR
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "vn"
        / "vnpt"
        / "ontconfig"
        / "core"
        / "driver"
        / "OntMobileDriver.kt"
    ).read_text(encoding="utf-8")
    ios_driver = (IOS_APP_DIR / "Core" / "Driver" / "OntMobileDriver.swift").read_text(encoding="utf-8")
    for driver_token in [
        "postDhcpGatewaySettleMs",
        "handoverRetryInitialBackoffMs",
        "handoverRetryMaxBackoffMs",
        "bandVerifyPollTimeoutMs",
        "bandVerifyPollIntervalMs",
        "isNetworkTransportAlive",
        "onLog",
    ]:
        assert driver_token in android_driver, f"Missing {driver_token} in OntMobileDriver.kt"
        assert driver_token in ios_driver, f"Missing {driver_token} in OntMobileDriver.swift"

    assert "StillOnPrimaryBandException" in android_driver
    assert "StillOnPrimaryBandError" in ios_driver
    assert "IosWifiMonitor" not in ios_driver, "OntMobileDriver.swift must never reference IosWifiMonitor directly"
    assert "HttpFormResponse" not in ios_driver, "Kotlin type HttpFormResponse must never appear in OntMobileDriver.swift"

    file_paths_xml = (
        ROOT_DIR / "android-app" / "app" / "src" / "main" / "res" / "xml" / "file_paths.xml"
    ).read_text(encoding="utf-8")
    assert "<files-path" not in file_paths_xml
    assert 'path="exports/"' in file_paths_xml

    ios_vm = (IOS_APP_DIR / "UI" / "MainViewModel.swift").read_text(encoding="utf-8")
    assert "exportDiagnosticLogToTempFile" in ios_vm
    assert "copyDiagnosticLogsToPasteboard" in ios_vm
    assert "addingTimeInterval(180)" in ios_vm
    assert "ONT_Debug_Log_" in ios_vm

    ios_progress_sheet = (IOS_APP_DIR / "UI" / "Components" / "ExecutionProgressSheet.swift").read_text(encoding="utf-8")
    ios_diag_sheet = ios_sheet_path.read_text(encoding="utf-8")
    ios_list_view = (IOS_APP_DIR / "UI" / "Screens" / "SubscriberListView.swift").read_text(encoding="utf-8")
    ios_review_view = (IOS_APP_DIR / "UI" / "Screens" / "ConfigReviewView.swift").read_text(encoding="utf-8")
    assert "LazyVStack" in ios_progress_sheet
    assert "LazyVStack" in ios_diag_sheet
    assert "showDiagnosticLogSheet" in ios_list_view
    assert ".sheet(isPresented: $viewModel.showDiagnosticLogSheet)" not in ios_review_view

    pbx_text = (IOS_DIR / "ONTWiFiConfigurator.xcodeproj" / "project.pbxproj").read_text(encoding="utf-8")
    assert pbx_text.count("AppDiagnosticLogger.swift") >= 4
    assert pbx_text.count("DiagnosticLogSheet.swift") >= 4
    assert "B100001A0000000000000001" in pbx_text
    assert "B100001B0000000000000001" in pbx_text

    ios_driver_tests = (IOS_TESTS_DIR / "OntMobileDriverTests.swift").read_text(encoding="utf-8")
    ios_val_tests = (IOS_TESTS_DIR / "ExcelAndValidatorTests.swift").read_text(encoding="utf-8")
    assert "testStep7ConvergencePollingAndTlsPinRethrowInPostOptionalSessionJson" in ios_driver_tests
    assert "testReconnectBackoffAndAppDiagnosticLoggerSynchronousScrubbingAndRotation" in ios_val_tests

    # Every OntMobileDriver(...) constructor call site in OntMobileDriverTests.swift must pass fast overrides (RT-05)
    import re

    ctor_blocks = [
        ios_driver_tests[m.start() : m.start() + 650]
        for m in re.finditer(r"\bOntMobileDriver\s*\(", ios_driver_tests)
    ]
    assert len(ctor_blocks) >= 8, f"Expected >= 8 OntMobileDriver(...) sites in OntMobileDriverTests.swift, got {len(ctor_blocks)}"
    for idx, block in enumerate(ctor_blocks, start=1):
        assert "postDhcpGatewaySettleMs:" in block, f"OntMobileDriver(...) site #{idx} missing postDhcpGatewaySettleMs fast override"
        assert "handoverRetryInitialBackoffMs:" in block, f"OntMobileDriver(...) site #{idx} missing handoverRetryInitialBackoffMs fast override"

    readme = (ROOT_DIR / "README.md").read_text(encoding="utf-8")
    huong_dan = (ROOT_DIR / "HUONG_DAN_SU_DUNG_TOOL_ONT.md").read_text(encoding="utf-8")
    for debug_doc_phrase in ["180s", "240s", "5s", "ONT_Debug_Log_"]:
        assert debug_doc_phrase in readme, f"Missing '{debug_doc_phrase}' in README.md"
        assert debug_doc_phrase in huong_dan, f"Missing '{debug_doc_phrase}' in HUONG_DAN_SU_DUNG_TOOL_ONT.md"


def test_phase8_android_to_ios_feature_parity():
    """Verify Android to iOS feature parity: post-setup Wi-Fi connection, top status banner,
    security capability validation, default MEN SSID alert, sticky bottom action bar,
    admin pass toggle, and quick factory ONT connector.
    """
    android_connector = (
        ROOT_DIR / "android-app" / "app" / "src" / "main" / "java" / "vn" / "vnpt" / "ontconfig" / "core" / "wifi" / "OntWifiConnector.kt"
    ).read_text(encoding="utf-8")
    android_vm = (
        ROOT_DIR / "android-app" / "app" / "src" / "main" / "java" / "vn" / "vnpt" / "ontconfig" / "ui" / "MainViewModel.kt"
    ).read_text(encoding="utf-8")
    android_review = (
        ROOT_DIR / "android-app" / "app" / "src" / "main" / "java" / "vn" / "vnpt" / "ontconfig" / "ui" / "screens" / "ConfigReviewScreen.kt"
    ).read_text(encoding="utf-8")
    android_sheet = (
        ROOT_DIR / "android-app" / "app" / "src" / "main" / "java" / "vn" / "vnpt" / "ontconfig" / "ui" / "components" / "ExecutionProgressSheet.kt"
    ).read_text(encoding="utf-8")

    ios_monitor = (IOS_APP_DIR / "Core" / "Wifi" / "IosWifiMonitor.swift").read_text(encoding="utf-8")
    ios_vm = (IOS_APP_DIR / "UI" / "MainViewModel.swift").read_text(encoding="utf-8")
    ios_review = (IOS_APP_DIR / "UI" / "Screens" / "ConfigReviewView.swift").read_text(encoding="utf-8")
    ios_progress_sheet = (IOS_APP_DIR / "UI" / "Components" / "ExecutionProgressSheet.swift").read_text(encoding="utf-8")
    ios_guide_sheet = (IOS_APP_DIR / "UI" / "Components" / "WifiGuideSheet.swift").read_text(encoding="utf-8")
    ios_tests = (IOS_TESTS_DIR / "ExcelAndValidatorTests.swift").read_text(encoding="utf-8")

    # 1. validateHandoverSecurityCapability parity
    assert "validateHandoverSecurityCapability" in android_connector
    assert "validateHandoverSecurityCapability" in ios_monitor
    assert "wpa3Psk" in ios_monitor or "wpa3_psk" in ios_monitor
    assert "wpaPsk" in ios_monitor or "wpa_psk" in ios_monitor
    assert "validateHandoverSecurityCapability" in android_vm
    assert "validateHandoverSecurityCapability" in ios_vm

    # 2. Pre-flight check in runOntConfiguration
    assert "validateHandoverSecurityCapability" in ios_vm
    assert "Chưa thể RUN" in ios_vm

    # 3. joinNewlyConfiguredWifi contracts
    assert "joinNewlyConfiguredWifi" in android_vm
    assert "joinNewlyConfiguredWifi" in ios_vm
    assert "isJoiningCustomerWifi" in ios_vm
    assert "customerWifiJoinStatus" in ios_vm
    assert "connectedWifiSsid" in ios_vm
    assert "joinOnce: false" in ios_vm or "joinOnce = false" in ios_vm
    assert "registerActiveSecrets" in ios_vm

    # 4. quickConnectOntWifi and normalizeQuickOntSsid
    assert "quickConnectOntWifi" in ios_vm
    assert "normalizeQuickOntSsid" in ios_monitor
    assert "lastQuickConnectedFactorySsid" in ios_vm
    assert "removeTemporaryOpenWifiIfNeeded" in ios_vm

    # 5. ConfigReviewView parity: top banner, default MEN SSID alert, eye toggles, sticky bottom bar
    assert "topWifiStatusBanner" in ios_review
    assert "isUsingDefaultMenSsid" in android_review
    assert "isUsingDefaultMenSsid" in ios_review
    assert "securityCapabilityWarning" in ios_review
    assert "showWifi24Pass" in ios_review
    assert "showWifi5Pass" in ios_review
    assert "showAdminPass" in ios_review
    assert "StickyBottomBar" in ios_review or "stickyBottomActionBar" in ios_review
    assert "ignoresSafeArea(.keyboard" in ios_review

    # 6. ExecutionProgressSheet parity: customer Wi-Fi join button, firmware reboot window, admin pass reveal, nested guide sheet
    assert "Kết nối vào Wi-Fi mới" in android_sheet
    assert "Kết nối vào Wi-Fi mới" in ios_progress_sheet
    assert "joinNewlyConfiguredWifi" in ios_progress_sheet
    assert "showAdminPass" in android_sheet
    assert "showAdminPass" in ios_progress_sheet
    assert "rebootCountdownSec" in ios_progress_sheet
    assert "showNestedWifiGuideSheet" in ios_progress_sheet

    # 7. WifiGuideSheet quick ONT connector
    assert "quickConnectCard" in ios_guide_sheet
    assert "ontSsidInput" in ios_guide_sheet
    assert "selectedQuickBand" in ios_guide_sheet
    assert "normalizeQuickOntSsid" in ios_guide_sheet
    assert "quickConnectOntWifi" in ios_guide_sheet

    # 8. Swift Unit Tests
    assert "testValidateHandoverSecurityCapabilityParity" in ios_tests
    assert "testNormalizeQuickOntSsid" in ios_tests
    assert "testDefaultMenSsidDetectionLogic" in ios_tests
