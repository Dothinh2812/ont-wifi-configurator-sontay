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
    assert "ensureGatewayReadyBeforeStep7" in driver_swift
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
    assert "macos-14" in wf_text
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
