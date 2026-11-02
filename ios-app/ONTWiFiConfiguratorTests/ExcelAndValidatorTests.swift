import XCTest
@testable import ONTWiFiConfigurator

final class ExcelAndValidatorTests: XCTestCase {

    private func loadTestExcelData() throws -> Data {
        let bundle = Bundle(for: type(of: self))
        if let url = bundle.url(forResource: "file-mau", withExtension: "xlsx") {
            return try Data(contentsOf: url)
        }
        let fallbackURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("Resources/file-mau.xlsx")
        return try Data(contentsOf: fallbackURL)
    }

    func testParseExcelReadsAll89RowsPreservesLeadingZerosAnd30Columns() throws {
        let data = try loadTestExcelData()
        let records = try ExcelManager.parseExcel(data: data)

        XCTAssertEqual(records.count, 89)

        // Row 1 (STT 1): SO_MAY must preserve leading zeros "001560828"
        let first = records[0]
        XCTAssertEqual(first.stt, 1)
        XCTAssertEqual(first.soMay, "001560828")
        XCTAssertEqual(first.accountPppoe, "kimthitv878")
        XCTAssertEqual(first.slid, "001560828")
        XCTAssertEqual(first.rawRowData.count, 30)

        // Row 3 (STT 3): empty Wi-Fi 2.4G should populate fallback VNPT_{SO_MAY} and 1234567890
        let third = records[2]
        XCTAssertEqual(third.soMay, "005642219")
        XCTAssertEqual(third.ssid24g, "VNPT_005642219")
        XCTAssertEqual(third.pass24g, "1234567890")
        XCTAssertEqual(third.ssid5g, "VNPT_005642219 5G")
        XCTAssertEqual(third.pass5g, "1234567890")
    }

    func testValidateOnImportDetectsExactly13RowsMissingPppoePassword() throws {
        let data = try loadTestExcelData()
        let records = try ExcelManager.parseExcel(data: data)

        let invalidOnImport = records.filter { !$0.isValid }
        XCTAssertEqual(invalidOnImport.count, 13)
        XCTAssertTrue(invalidOnImport.allSatisfy { $0.passPppoe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
        XCTAssertTrue(invalidOnImport.allSatisfy { $0.status == .invalidData })
    }

    func testValidateBeforeRunCatches5RowsWith7DigitWifiPasswordsAndSupportsPrependZero() throws {
        let data = try loadTestExcelData()
        let records = try ExcelManager.parseExcel(data: data)
        let settings = GlobalSettings()

        let validOnImport = records.filter { $0.isValid }
        XCTAssertEqual(validOnImport.count, 76)

        let failingBeforeRun = validOnImport.filter { !OntInputValidator.validateBeforeRun($0, settings: settings).isValid }
        XCTAssertEqual(failingBeforeRun.count, 5)

        let sevenDigitPasswords = Set(failingBeforeRun.map { $0.pass24g })
        let expected: Set<String> = ["3011965", "3820275", "5091993", "4091981", "8042020"]
        XCTAssertEqual(sevenDigitPasswords, expected)

        for rec in failingBeforeRun {
            let preRunResult = OntInputValidator.validateBeforeRun(rec, settings: settings)
            XCTAssertTrue(preRunResult.canPrependZero24g)
            let fixed = OntInputValidator.prependLeadingZeroToWifiPass(rec, sync5g: true)
            XCTAssertEqual(fixed.pass24g, "0\(rec.pass24g)")
            XCTAssertEqual(fixed.pass5g, "0\(rec.pass24g)")
            let revalidated = OntInputValidator.validateBeforeRun(fixed, settings: settings)
            XCTAssertTrue(revalidated.isValid, "Expected fixed record \(fixed.soMay) to pass validateBeforeRun: \(revalidated.errors)")
        }
    }

    func testValidatorBlocksAll11ShellMetacharsControlCharsSlidAndAdminComplexity() throws {
        let expectedMetachars: Set<Character> = ["&", "|", ";", "$", ">", "<", "`", "\\", "!", "'", "\""]
        XCTAssertEqual(OntInputValidator.unsafeShellMetachars.count, 11)
        XCTAssertEqual(OntInputValidator.unsafeShellMetachars, expectedMetachars)

        for ch in expectedMetachars {
            let rec = SubscriberRecord(
                rowIndex: 2,
                soMay: "001560828",
                accountPppoe: "user_ok",
                passPppoe: "pass\(ch)bad",
                slid: "001560828",
                ssid24g: "VNPT_Home",
                pass24g: "12345678",
                ssid5g: "VNPT_Home 5G",
                pass5g: "12345678"
            )
            let res = OntInputValidator.validateOnImport(rec)
            XCTAssertFalse(res.isValid, "Expected metachar '\(ch)' to fail validateOnImport")
        }

        // Control characters (\r, \n, \0, 0x7F) must fail
        for ctrl in ["\r", "\n", "\u{0000}", "\u{007f}"] {
            let rec = SubscriberRecord(
                rowIndex: 2,
                soMay: "001560828",
                accountPppoe: "user_ok",
                passPppoe: "pass\(ctrl)bad",
                slid: "001560828",
                ssid24g: "VNPT_Home",
                pass24g: "12345678"
            )
            XCTAssertFalse(OntInputValidator.validateOnImport(rec).isValid)
        }

        // SLID > 10 chars fails
        let longSlidRec = SubscriberRecord(
            rowIndex: 2,
            soMay: "001560828",
            accountPppoe: "user_ok",
            passPppoe: "pass1234",
            slid: "12345678901",
            ssid24g: "VNPT_Home",
            pass24g: "12345678"
        )
        XCTAssertFalse(OntInputValidator.validateOnImport(longSlidRec).isValid)

        // Admin password complexity
        XCTAssertTrue(OntInputValidator.validatePasswordComplexity("Passwd2@"))
        XCTAssertFalse(OntInputValidator.validatePasswordComplexity("passwd2@"))
        XCTAssertFalse(OntInputValidator.validatePasswordComplexity("Passwd 2@"))
        XCTAssertFalse(OntInputValidator.validatePasswordComplexity("Pass2@"))
        XCTAssertFalse(OntInputValidator.validatePasswordComplexity(String(repeating: "A", count: 30) + "a1@"))

        // URL encoding & ontEncodeQuery & credential masking
        XCTAssertEqual(OntInputValidator.urlEncodeFormValue("A B+C=D%E"), "A%20B%2BC%3DD%25E")
        XCTAssertEqual(OntInputValidator.ontEncodeQuery("a=1&b=2&c=3"), "a=1\u{007f}b=2\u{007f}c=3")

        let rawLog = "pppPassword=secret123&wl_wpa_psk=wifi8888\u{007f}sessionKey=sess99&data=slid01"
        let masked = OntInputValidator.maskSensitiveCredentials(rawLog)
        XCTAssertFalse(masked.contains("secret123"))
        XCTAssertFalse(masked.contains("wifi8888"))
        XCTAssertFalse(masked.contains("sess99"))
        XCTAssertFalse(masked.contains("slid01"))
        XCTAssertTrue(masked.contains("pppPassword=***"))
        XCTAssertTrue(masked.contains("wl_wpa_psk=***"))
        XCTAssertTrue(masked.contains("sessionKey=***"))
        XCTAssertTrue(masked.contains("data=***"))
    }

    func testSharedStringsAndStylesZeroPadWithoutCorruptingEmptyCells() throws {
        let sharedStringsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
          <si><t>SO_MAY</t></si>
          <si><t>Account</t></si>
          <si><t>Mã MEN</t></si>
          <si><t>manh9653</t></si>
        </sst>
        """.data(using: .utf8)!

        let stylesXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <numFmts count="1">
            <numFmt numFmtId="164" formatCode="000000000"/>
          </numFmts>
          <cellXfs count="2">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
            <xf numFmtId="164" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/>
          </cellXfs>
        </styleSheet>
        """.data(using: .utf8)!

        // Row 2 has numeric SO_MAY (I2) with style s="1" -> "001560828", and empty Mã MEN (M2) with style s="1" -> must stay ""!
        let sheetXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1">
              <c r="I1" t="s"><v>0</v></c>
              <c r="J1" t="s"><v>1</v></c>
              <c r="M1" t="s"><v>2</v></c>
            </row>
            <row r="2">
              <c r="I2" s="1"><v>1560828</v></c>
              <c r="J2" t="s"><v>3</v></c>
              <c r="M2" s="1"/>
            </row>
          </sheetData>
        </worksheet>
        """.data(using: .utf8)!

        let archiveData = try ZipArchive.createArchive(entries: [
            "xl/sharedStrings.xml": sharedStringsXml,
            "xl/styles.xml": stylesXml,
            "xl/worksheets/sheet1.xml": sheetXml
        ])

        let records = try ExcelManager.parseExcel(data: archiveData)
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records[0].soMay, "001560828")
        XCTAssertEqual(records[0].maMen, "")
        XCTAssertEqual(records[0].slid, "001560828")
    }

    func testAppDatabaseUpsertPreservingEditsAndExportResults() throws {
        let data = try loadTestExcelData()
        var records = try ExcelManager.parseExcel(data: data)

        let tempDbURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("test_ont_\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: tempDbURL) }

        let db = AppDatabase(databaseURL: tempDbURL)
        _ = db.upsertPreservingEdits(records: records)

        var editedFirst = records[0]
        editedFirst.passPppoe = "ktv_entered_123"
        editedFirst.slid = "NEW_SLID01"
        editedFirst.soMay = "NEW_SLID01"
        editedFirst.ssid24g = "KimThu_New"
        editedFirst.pass24g = "88889999"
        editedFirst.ssid5g = "KimThu_New 5G"
        editedFirst.pass5g = "88889999"
        editedFirst.status = .success
        editedFirst.lastKnownAdminPass = "Passwd2@"
        editedFirst.startTime = "09:00:00"
        editedFirst.endTime = "09:01:15"
        db.update(record: editedFirst)

        // Re-import fresh Excel and verify edits + SUCCESS + lastKnownAdminPass survive
        let reimported = try ExcelManager.parseExcel(data: data)
        let merged = db.upsertPreservingEdits(records: reimported)
        XCTAssertEqual(merged.count, 89)
        XCTAssertEqual(merged[0].status, .success)
        XCTAssertEqual(merged[0].passPppoe, "ktv_entered_123")
        XCTAssertEqual(merged[0].ssid24g, "KimThu_New")
        XCTAssertEqual(merged[0].lastKnownAdminPass, "Passwd2@")

        // Export and re-parse to verify 30 original headers order + 3 status columns
        records[0] = merged[0]
        records[1].status = .failed
        records[1].errorMessage = "Lỗi tại sendwan.cmd: pppPassword=6l6s8z2a&wl_wpa_psk=28121995&sessionKey=123456"

        let exportedData = try ExcelManager.exportResults(sourceExcelData: data, records: records)
        let reParsed = try ExcelManager.parseExcel(data: exportedData)
        XCTAssertEqual(reParsed.count, 89)
        XCTAssertEqual(reParsed[0].soMay, "NEW_SLID01")
        XCTAssertEqual(reParsed[0].slid, "NEW_SLID01")
        XCTAssertEqual(reParsed[0].passPppoe, "ktv_entered_123")
        XCTAssertEqual(reParsed[0].rawRowData.count, 33)
        XCTAssertEqual(reParsed[0].rawRowData["Trạng thái cấu hình"], "SUCCESS")
        XCTAssertEqual(reParsed[0].rawRowData["Thời gian thực hiện"], "09:00:00 -> 09:01:15")
        XCTAssertEqual(reParsed[0].rawRowData["Ghi chú chi tiết"], "OK")

        let secondNote = reParsed[1].rawRowData["Ghi chú chi tiết"] ?? ""
        XCTAssertFalse(secondNote.contains("6l6s8z2a"))
        XCTAssertTrue(secondNote.contains("pppPassword=***"))
    }

    func testWifiSecurityModeCodableBackwardCompatibilityAndOpenModeValidation() throws {
        XCTAssertEqual(WifiSecurityMode.allCases.count, 6)
        XCTAssertEqual(WifiSecurityMode.wpaWpa2Psk.crypto, "tkip+aes")
        XCTAssertEqual(WifiSecurityMode.wpaPsk.crypto, "tkip+aes")
        XCTAssertEqual(WifiSecurityMode.open.akmPsk, "disabled")
        XCTAssertFalse(WifiSecurityMode.open.requiresPassword)

        // Legacy JSON without wifiSecurityMode must decode with default wpa2_wpa3_psk (RT-07)
        let legacyJson = """
        {"baseUrl":"https://192.168.1.1","adminDefaultPass":"admin","adminNewPass":"Passwd2@","vlanId":11,"mtu":1492,"defaultWifi5Ssid":"VNPT 5G","defaultWifi5Pass":"1234567890","lanIp":"192.168.1.1","lanMask":"255.255.255.0","dhcpStart":"192.168.1.2","dhcpEnd":"192.168.1.254","dhcpLease":1440,"brName":"br-lan","requestTimeoutMs":5000}
        """.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(GlobalSettings.self, from: legacyJson)
        XCTAssertEqual(decoded.wifiSecurityMode, "wpa2_wpa3_psk")

        let shortPassRec = SubscriberRecord(
            rowIndex: 2,
            soMay: "005501941",
            accountPppoe: "manh9653",
            passPppoe: "6l6s8z2a",
            slid: "005501941",
            ssid24g: "Manh Ngoc",
            pass24g: "3011965",
            ssid5g: "Manh Ngoc 5G",
            pass5g: "3011965"
        )
        let openCheck = OntInputValidator.validateBeforeRun(
            shortPassRec,
            settings: GlobalSettings(wifiSecurityMode: "open")
        )
        XCTAssertTrue(openCheck.isValid, "Open mode must allow short/empty password: \(openCheck.errors)")
        XCTAssertFalse(openCheck.canPrependZero24g)
        XCTAssertFalse(openCheck.canPrependZero5g)
    }
}
