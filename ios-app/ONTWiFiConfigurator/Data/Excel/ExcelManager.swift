import Foundation

public enum ExcelManager {

    public static let sourceCacheFileName = "current_source.xlsx"
    public static let demoAssetFileName = "sample-demo"

    public static let STANDARD_30_HEADERS: [String] = [
        "STT", "Tổ địa bàn", "NVKT", "SA", "KETCUOI",
        "TEN_TB", "DIACHI_TB", "SO_DT", "SO_MAY", "Account",
        "Mật khẩu PPPoE", "Trạng thái tra WIFI", "Mã MEN", "Tên Model", "SSID Wi-Fi 2.4G",
        "Mật khẩu Wi-Fi 2.4G", "Bảo mật 2.4G", "Trạng thái sóng 2.4G", "SSID Wi-Fi 5G", "Mật khẩu Wi-Fi 5G",
        "Bảo mật 5G", "Trạng thái sóng 5G", "SSID Phụ (Guest)", "Mật khẩu Phụ", "VIDO",
        "KINHDO", "MOC_MODEL", "MOC_BANG_TAN", "HIEN_TAI_MODEL", "HIEN_TAI_BANG_TAN"
    ]

    public static let COL_STATUS = "Trạng thái cấu hình"
    public static let COL_TIME = "Thời gian thực hiện"
    public static let COL_NOTE = "Ghi chú chi tiết"

    public static func deriveWifi5Config(
        ssid24g: String,
        pass24g: String,
        rawSsid5g: String? = "",
        rawPass5g: String? = "",
        defaultSsid5g: String = "VNPT 5G",
        defaultPass5g: String = "1234567890"
    ) -> (String, String) {
        let cleanRawSsid5 = (rawSsid5g ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanRawPass5 = (rawPass5g ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSsid24 = ssid24g.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanPass24 = pass24g.trimmingCharacters(in: .whitespacesAndNewlines)

        var ssid5g: String
        if !cleanRawSsid5.isEmpty {
            ssid5g = cleanRawSsid5
        } else if !cleanSsid24.isEmpty {
            ssid5g = "\(cleanSsid24) 5G"
        } else {
            ssid5g = defaultSsid5g
        }
        if ssid5g.count > 32 {
            ssid5g = String(ssid5g.prefix(32)).trimmingCharacters(in: .whitespacesAndNewlines)
        }

        let pass5g: String
        if !cleanRawPass5.isEmpty {
            pass5g = cleanRawPass5
        } else if !cleanPass24.isEmpty {
            pass5g = cleanPass24
        } else {
            pass5g = defaultPass5g
        }

        return (ssid5g, pass5g)
    }

    public static func deriveWifi5Ssid(
        ssid24g: String,
        rawSsid5g: String? = "",
        defaultSsid5g: String = "VNPT 5G"
    ) -> String {
        return deriveWifi5Config(
            ssid24g: ssid24g,
            pass24g: "",
            rawSsid5g: rawSsid5g,
            rawPass5g: "",
            defaultSsid5g: defaultSsid5g
        ).0
    }

    /// Parse .xlsx archive using Native ZipArchive + XMLParser (shouldResolveExternalEntities = false).
    /// Preserves leading zeros in SO_MAY / SO_DT / Mã MEN and all 30 original columns.
    public static func parseExcel(
        data: Data,
        defaultWifi5Ssid: String = "VNPT 5G",
        defaultWifi5Pass: String = "1234567890"
    ) throws -> [SubscriberRecord] {
        let zipEntries = try ZipArchive.extractEntries(from: data)
        let sharedStringsXml = zipEntries["xl/sharedStrings.xml"]
        let stylesXml = zipEntries["xl/styles.xml"]
        let sheetXml = zipEntries["xl/worksheets/sheet1.xml"]
            ?? zipEntries.first(where: { $0.key.hasPrefix("xl/worksheets/sheet") && $0.key.hasSuffix(".xml") })?.value

        guard let validSheetXml = sheetXml else {
            throw NSError(
                domain: "ExcelManager",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "File Excel (.xlsx) không hợp lệ: thiếu xl/worksheets/sheet1.xml"]
            )
        }

        let sharedStrings: [String] = sharedStringsXml.map { parseSharedStrings(from: $0) } ?? []
        let zeroPadWidthsByStyle: [Int: Int] = stylesXml.map { parseZeroPadStyles(from: $0) } ?? [:]
        let rawRows = parseSheetRows(
            from: validSheetXml,
            sharedStrings: sharedStrings,
            zeroPadWidthsByStyle: zeroPadWidthsByStyle
        )
        if rawRows.isEmpty { return [] }

        let minRowKey = rawRows.keys.min() ?? 1
        guard let headerRow = rawRows[1] ?? rawRows[minRowKey] else { return [] }

        var headersByCol: [Int: String] = [:]
        var colByHeader: [String: Int] = [:]
        let maxHeaderCol = headerRow.keys.max() ?? 30

        if maxHeaderCol >= 1 {
            for col in 1...maxHeaderCol {
                let h = (headerRow[col] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if !h.isEmpty {
                    headersByCol[col] = h
                    colByHeader[h] = col
                }
            }
        }

        func getCell(_ rowMap: [Int: String], _ headerName: String, fallbackCol: Int) -> String {
            let col = colByHeader[headerName] ?? fallbackCol
            return (rowMap[col] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }

        var records: [SubscriberRecord] = []
        let sortedRowNums = rawRows.keys.filter { $0 >= 2 }.sorted()

        for rowNum in sortedRowNums {
            guard let rowMap = rawRows[rowNum] else { continue }
            let sttVal = getCell(rowMap, "STT", fallbackCol: 1)
            let soMay = getCell(rowMap, "SO_MAY", fallbackCol: 9)
            let maMen = getCell(rowMap, "Mã MEN", fallbackCol: 13)
            let accountPppoe = getCell(rowMap, "Account", fallbackCol: 10)
            let slid = maMen.isEmpty ? soMay : maMen

            if sttVal.isEmpty && slid.isEmpty && accountPppoe.isEmpty {
                continue
            }

            let stt = Int(sttVal) ?? Double(sttVal).map { Int($0) }
            let toDiaBan = getCell(rowMap, "Tổ địa bàn", fallbackCol: 2)
            let nvkt = getCell(rowMap, "NVKT", fallbackCol: 3)
            let tenTb = getCell(rowMap, "TEN_TB", fallbackCol: 6)
            let diachiTb = getCell(rowMap, "DIACHI_TB", fallbackCol: 7)
            let soDt = getCell(rowMap, "SO_DT", fallbackCol: 8)
            let passPppoe = getCell(rowMap, "Mật khẩu PPPoE", fallbackCol: 11)

            let rawSsid24g = getCell(rowMap, "SSID Wi-Fi 2.4G", fallbackCol: 15)
            let rawPass24g = getCell(rowMap, "Mật khẩu Wi-Fi 2.4G", fallbackCol: 16)

            let ssid24g = rawSsid24g.isEmpty ? (slid.isEmpty ? "" : "VNPT_\(slid)") : rawSsid24g
            let pass24g = rawPass24g.isEmpty ? "1234567890" : rawPass24g

            let rawSsid5g = getCell(rowMap, "SSID Wi-Fi 5G", fallbackCol: 19)
            let rawPass5g = getCell(rowMap, "Mật khẩu Wi-Fi 5G", fallbackCol: 20)

            let (ssid5g, pass5g) = deriveWifi5Config(
                ssid24g: ssid24g,
                pass24g: pass24g,
                rawSsid5g: rawSsid5g,
                rawPass5g: rawPass5g,
                defaultSsid5g: defaultWifi5Ssid,
                defaultPass5g: defaultWifi5Pass
            )

            var rawRowData: [String: String] = [:]
            for colIdx in headersByCol.keys.sorted() {
                if let hdrName = headersByCol[colIdx] {
                    rawRowData[hdrName] = rowMap[colIdx] ?? ""
                }
            }

            var draft = SubscriberRecord(
                rowIndex: rowNum,
                stt: stt,
                toDiaBan: toDiaBan,
                nvkt: nvkt,
                tenTb: tenTb,
                diachiTb: diachiTb,
                soDt: soDt,
                soMay: slid,
                accountPppoe: accountPppoe,
                passPppoe: passPppoe,
                maMen: maMen,
                slid: slid,
                ssid24g: ssid24g,
                pass24g: pass24g,
                ssid5g: ssid5g,
                pass5g: pass5g,
                rawRowData: rawRowData
            )

            let validation = OntInputValidator.validateOnImport(draft)
            draft.isValid = validation.isValid
            draft.validationErrors = validation.errors
            draft.status = validation.isValid ? .pending : .invalidData

            records.append(draft)
        }

        return records
    }

    /// Smart merge on re-import: preserves SUCCESS state, timestamps, lastKnownAdminPass, and KTV edits.
    public static func mergeRecordsPreservingEdits(
        existingRecords: [SubscriberRecord],
        incomingRecords: [SubscriberRecord]
    ) -> [SubscriberRecord] {
        if existingRecords.isEmpty { return incomingRecords }

        var byKey: [String: SubscriberRecord] = [:]
        var byRowAndAccount: [String: SubscriberRecord] = [:]
        var byRawSlidAndAccount: [String: SubscriberRecord] = [:]
        for rec in existingRecords {
            byKey[recordKey(rec)] = rec
            let acc = rec.accountPppoe.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            byRowAndAccount["\(rec.rowIndex)|\(acc)"] = rec
            let rawMen = (rec.rawRowData["Mã MEN"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let rawSoMay = (rec.rawRowData["SO_MAY"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let origSlid = (rawMen.isEmpty ? rawSoMay : rawMen).lowercased()
            if !origSlid.isEmpty {
                byRawSlidAndAccount["\(origSlid)|\(acc)"] = rec
            }
        }

        return incomingRecords.map { incoming in
            let acc = incoming.accountPppoe.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let key = recordKey(incoming)
            guard let prev = byKey[key] ?? byRowAndAccount["\(incoming.rowIndex)|\(acc)"] ?? byRawSlidAndAccount[key] else {
                return incoming
            }

            let mergedPassPppoe = prev.passPppoe.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? incoming.passPppoe
                : prev.passPppoe
            let hasCustomEdits = prev.status == .success ||
                prev.passPppoe != incoming.passPppoe ||
                prev.ssid24g != incoming.ssid24g ||
                prev.pass24g != incoming.pass24g ||
                prev.ssid5g != incoming.ssid5g ||
                prev.pass5g != incoming.pass5g ||
                prev.slid != incoming.slid

            var merged = incoming
            merged.passPppoe = mergedPassPppoe
            if hasCustomEdits && !prev.slid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.slid = prev.slid
            }
            merged.soMay = merged.slid
            if hasCustomEdits && !prev.ssid24g.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.ssid24g = prev.ssid24g
            }
            if hasCustomEdits && !prev.pass24g.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.pass24g = prev.pass24g
            }
            if hasCustomEdits && !prev.ssid5g.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.ssid5g = prev.ssid5g
            }
            if hasCustomEdits && !prev.pass5g.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.pass5g = prev.pass5g
            }
            if !prev.lastKnownAdminPass.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                merged.lastKnownAdminPass = prev.lastKnownAdminPass
            }

            let validation = OntInputValidator.validateOnImport(merged)
            let finalStatus: SubscriberStatus
            if prev.status == .success {
                finalStatus = .success
            } else if validation.isValid {
                finalStatus = .pending
            } else {
                finalStatus = .invalidData
            }

            merged.isValid = validation.isValid
            merged.validationErrors = validation.errors
            merged.status = finalStatus
            merged.startTime = prev.startTime
            merged.endTime = prev.endTime
            merged.errorMessage = (finalStatus == .success) ? "" : prev.errorMessage
            return merged
        }
    }

    private static func recordKey(_ record: SubscriberRecord) -> String {
        let sl = record.slid.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let acc = record.accountPppoe.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return "\(sl)|\(acc)"
    }

    public static func normalizePhoneForSearch(_ phone: String) -> String {
        let digitsOnly = String(phone.trimmingCharacters(in: .whitespacesAndNewlines).filter { $0.isNumber && $0.isASCII })
        let stripped = String(digitsOnly.drop(while: { $0 == "0" }))
        return stripped.isEmpty ? phone.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() : stripped
    }

    public static func searchSubscribers(
        records: [SubscriberRecord],
        query: String,
        statusFilter: SubscriberStatus? = nil
    ) -> [SubscriberRecord] {
        let statusFiltered: [SubscriberRecord]
        switch statusFilter {
        case nil:
            statusFiltered = records
        case .invalidData:
            statusFiltered = records.filter { $0.status == .invalidData || $0.status == .failed }
        case let filter?:
            statusFiltered = records.filter { $0.status == filter }
        }

        let cleanQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanQuery.isEmpty { return statusFiltered }

        let qLower = cleanQuery.lowercased()
        let qPhoneNorm = normalizePhoneForSearch(cleanQuery)
        let qNoLeadingZeros = String(cleanQuery.drop(while: { $0 == "0" })).lowercased()

        return statusFiltered.filter { rec in
            let recPhoneNorm = normalizePhoneForSearch(rec.soDt)
            let recSlidNoZero = String(rec.slid.drop(while: { $0 == "0" })).lowercased()

            return rec.accountPppoe.lowercased().contains(qLower) ||
                rec.slid.lowercased().contains(qLower) ||
                (!qNoLeadingZeros.isEmpty && recSlidNoZero.contains(qNoLeadingZeros)) ||
                rec.tenTb.lowercased().contains(qLower) ||
                rec.diachiTb.lowercased().contains(qLower) ||
                rec.nvkt.lowercased().contains(qLower) ||
                rec.soDt.lowercased().contains(qLower) ||
                (qPhoneNorm.count >= 3 && recPhoneNorm.contains(qPhoneNorm))
        }
    }

    /// Export updated .xlsx anchored to STANDARD_30_HEADERS order + 3 status columns with masked error notes.
    public static func exportResults(
        sourceExcelData: Data?,
        records: [SubscriberRecord]
    ) throws -> Data {
        var orderedHeaders: [String] = []
        var seen: Set<String> = [COL_STATUS, COL_TIME, COL_NOTE]

        for h in STANDARD_30_HEADERS {
            if !seen.contains(h) {
                orderedHeaders.append(h)
                seen.insert(h)
            }
        }
        if let firstRaw = records.first?.rawRowData {
            for extraKey in firstRaw.keys.sorted() where !seen.contains(extraKey) {
                orderedHeaders.append(extraKey)
                seen.insert(extraKey)
            }
        }

        let finalHeaders = orderedHeaders + [COL_STATUS, COL_TIME, COL_NOTE]
        let sheetBytes = buildWorksheetXml(headers: finalHeaders, records: records)

        if let srcData = sourceExcelData,
           var zipEntries = try? ZipArchive.extractEntries(from: srcData) {
            zipEntries["xl/worksheets/sheet1.xml"] = sheetBytes
            return try ZipArchive.createArchive(entries: zipEntries)
        } else {
            return try buildStandaloneXlsx(sheetBytes: sheetBytes)
        }
    }

    // MARK: - XML Parsing & Generation Helpers

    private static func parseSharedStrings(from xmlData: Data) -> [String] {
        let parser = XMLParser(data: xmlData)
        parser.shouldResolveExternalEntities = false
        let delegate = SharedStringsXmlDelegate()
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.strings
    }

    private static func parseZeroPadStyles(from xmlData: Data) -> [Int: Int] {
        let parser = XMLParser(data: xmlData)
        parser.shouldResolveExternalEntities = false
        let delegate = StylesXmlDelegate()
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.styleZeroWidths
    }

    private static func parseSheetRows(
        from xmlData: Data,
        sharedStrings: [String],
        zeroPadWidthsByStyle: [Int: Int]
    ) -> [Int: [Int: String]] {
        let parser = XMLParser(data: xmlData)
        parser.shouldResolveExternalEntities = false
        let delegate = WorksheetXmlDelegate(
            sharedStrings: sharedStrings,
            zeroPadWidthsByStyle: zeroPadWidthsByStyle
        )
        parser.delegate = delegate
        _ = parser.parse()
        return delegate.rows
    }

    public static func escapeXmlText(_ text: String) -> String {
        var out = String()
        out.reserveCapacity(text.count)
        for scalar in text.unicodeScalars {
            let v = scalar.value
            // Filter out invalid XML 1.0 control characters
            let isValidXmlChar = (v == 0x09 || v == 0x0A || v == 0x0D || (v >= 0x20 && v <= 0xD7FF) || (v >= 0xE000 && v <= 0xFFFD) || (v >= 0x10000 && v <= 0x10FFFF))
            guard isValidXmlChar else { continue }
            switch scalar {
            case "&": out.append("&amp;")
            case "<": out.append("&lt;")
            case ">": out.append("&gt;")
            case "\"": out.append("&quot;")
            case "'": out.append("&apos;")
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out
    }

    private static func buildWorksheetXml(
        headers: [String],
        records: [SubscriberRecord]
    ) -> Data {
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        xml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        xml.append("<sheetData>")

        // Row 1: Headers
        xml.append("<row r=\"1\">")
        for (idx, header) in headers.enumerated() {
            let cellRef = "\(columnIndexToRef(idx + 1))1"
            xml.append("<c r=\"\(cellRef)\" t=\"inlineStr\"><is><t>\(escapeXmlText(header))</t></is></c>")
        }
        xml.append("</row>")

        // Data Rows
        for (rOffset, rec) in records.enumerated() {
            let xmlRowNum = rOffset + 2
            xml.append("<row r=\"\(xmlRowNum)\">")

            let timeStr: String
            if !rec.startTime.isEmpty && !rec.endTime.isEmpty {
                timeStr = "\(rec.startTime) -> \(rec.endTime)"
            } else if !rec.endTime.isEmpty {
                timeStr = rec.endTime
            } else {
                timeStr = ""
            }

            let currentErrors = OntInputValidator.validateOnImport(rec).errors
            let rawNote: String
            if !rec.errorMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                rawNote = rec.errorMessage
            } else if rec.status == .success {
                rawNote = "OK"
            } else if !currentErrors.isEmpty {
                rawNote = currentErrors.joined(separator: ", ")
            } else {
                rawNote = "OK"
            }
            let maskedNote = OntInputValidator.maskSensitiveCredentials(rawNote)

            for (cOffset, header) in headers.enumerated() {
                let cellRef = "\(columnIndexToRef(cOffset + 1))\(xmlRowNum)"
                let value: String
                switch header {
                case "STT":
                    value = String(rec.stt ?? (rOffset + 1))
                case "Tổ địa bàn":
                    value = rec.toDiaBan.isEmpty ? (rec.rawRowData[header] ?? "") : rec.toDiaBan
                case "NVKT":
                    value = rec.nvkt.isEmpty ? (rec.rawRowData[header] ?? "") : rec.nvkt
                case "TEN_TB":
                    value = rec.tenTb.isEmpty ? (rec.rawRowData[header] ?? "") : rec.tenTb
                case "DIACHI_TB":
                    value = rec.diachiTb.isEmpty ? (rec.rawRowData[header] ?? "") : rec.diachiTb
                case "SO_DT":
                    value = rec.soDt.isEmpty ? (rec.rawRowData[header] ?? "") : rec.soDt
                case "SO_MAY":
                    value = rec.slid
                case "Account":
                    value = rec.accountPppoe
                case "Mật khẩu PPPoE":
                    value = rec.passPppoe
                case "Mã MEN":
                    value = rec.slid
                case "SSID Wi-Fi 2.4G":
                    value = rec.ssid24g
                case "Mật khẩu Wi-Fi 2.4G":
                    value = rec.pass24g
                case "SSID Wi-Fi 5G":
                    value = rec.ssid5g
                case "Mật khẩu Wi-Fi 5G":
                    value = rec.pass5g
                case COL_STATUS:
                    value = rec.status.rawValue
                case COL_TIME:
                    value = timeStr
                case COL_NOTE:
                    value = maskedNote
                default:
                    value = rec.rawRowData[header] ?? ""
                }
                xml.append("<c r=\"\(cellRef)\" t=\"inlineStr\"><is><t>\(escapeXmlText(value))</t></is></c>")
            }

            xml.append("</row>")
        }

        xml.append("</sheetData></worksheet>")
        return Data(xml.utf8)
    }

    private static func buildStandaloneXlsx(sheetBytes: Data) throws -> Data {
        let contentTypes = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
        """
        let rels = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
        """
        let workbook = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
        """
        let workbookRels = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        </Relationships>
        """
        return try ZipArchive.createArchive(entries: [
            "[Content_Types].xml": Data(contentTypes.utf8),
            "_rels/.rels": Data(rels.utf8),
            "xl/workbook.xml": Data(workbook.utf8),
            "xl/_rels/workbook.xml.rels": Data(workbookRels.utf8),
            "xl/worksheets/sheet1.xml": sheetBytes
        ])
    }

    internal static func columnRefToIndex(_ cellRef: String) -> Int {
        var col = 0
        for scalar in cellRef.unicodeScalars {
            if scalar.value >= 0x41 && scalar.value <= 0x5A {
                col = col * 26 + Int(scalar.value - 0x41 + 1)
            } else if scalar.value >= 0x61 && scalar.value <= 0x7A {
                col = col * 26 + Int(scalar.value - 0x61 + 1)
            } else {
                break
            }
        }
        return col
    }

    internal static func columnIndexToRef(_ colIndex: Int) -> String {
        var n = colIndex
        var chars: [Character] = []
        while n > 0 {
            let rem = (n - 1) % 26
            chars.append(Character(UnicodeScalar(UInt32(0x41 + rem))!))
            n = (n - 1) / 26
        }
        return String(chars.reversed())
    }
}

// MARK: - XMLParser Delegates

private final class SharedStringsXmlDelegate: NSObject, XMLParserDelegate {
    var strings: [String] = []
    private var inSi = false
    private var inT = false
    private var inRPh = false
    private var currentText = ""

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        switch elementName {
        case "si":
            inSi = true
            currentText = ""
        case "rPh":
            inRPh = true
        case "t":
            if inSi && !inRPh {
                inT = true
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inSi && inT && !inRPh {
            currentText.append(string)
        }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        switch elementName {
        case "t":
            inT = false
        case "rPh":
            inRPh = false
        case "si":
            if inSi {
                strings.append(currentText)
                inSi = false
            }
        default:
            break
        }
    }
}

private final class StylesXmlDelegate: NSObject, XMLParserDelegate {
    var styleZeroWidths: [Int: Int] = [:]
    private var numFmtZeroWidths: [Int: Int] = [:]
    private var inCellXfs = false
    private var xfIndex = 0

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        switch elementName {
        case "numFmt":
            if let idStr = attributeDict["numFmtId"],
               let id = Int(idStr),
               let code = attributeDict["formatCode"]?.trimmingCharacters(in: .whitespacesAndNewlines),
               code.count >= 2,
               code.allSatisfy({ $0 == "0" }) {
                numFmtZeroWidths[id] = code.count
            }
        case "cellXfs":
            inCellXfs = true
            xfIndex = 0
        case "xf":
            if inCellXfs {
                if let numFmtIdStr = attributeDict["numFmtId"],
                   let numFmtId = Int(numFmtIdStr),
                   let padWidth = numFmtZeroWidths[numFmtId] {
                    styleZeroWidths[xfIndex] = padWidth
                }
                xfIndex += 1
            }
        default:
            break
        }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        if elementName == "cellXfs" {
            inCellXfs = false
        }
    }
}

private final class WorksheetXmlDelegate: NSObject, XMLParserDelegate {
    let sharedStrings: [String]
    let zeroPadWidthsByStyle: [Int: Int]
    var rows: [Int: [Int: String]] = [:]

    private var currentRowNum = 0
    private var currentColNum = 0
    private var currentCellType: String?
    private var currentCellStyle: Int?
    private var inV = false
    private var inT = false
    private var inRPh = false
    private var cellText = ""

    init(sharedStrings: [String], zeroPadWidthsByStyle: [Int: Int]) {
        self.sharedStrings = sharedStrings
        self.zeroPadWidthsByStyle = zeroPadWidthsByStyle
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        switch elementName {
        case "row":
            if let rStr = attributeDict["r"], let r = Int(rStr) {
                currentRowNum = r
            } else {
                currentRowNum += 1
            }
            if rows[currentRowNum] == nil {
                rows[currentRowNum] = [:]
            }
            currentColNum = 0
        case "c":
            if let ref = attributeDict["r"] {
                currentColNum = ExcelManager.columnRefToIndex(ref)
            } else {
                currentColNum += 1
            }
            currentCellType = attributeDict["t"]
            currentCellStyle = attributeDict["s"].flatMap { Int($0) }
            cellText = ""
        case "rPh":
            inRPh = true
        case "v":
            inV = true
        case "t":
            if !inRPh {
                inT = true
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if (inV || inT) && !inRPh {
            cellText.append(string)
        }
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        switch elementName {
        case "v":
            inV = false
        case "t":
            inT = false
        case "rPh":
            inRPh = false
        case "c":
            let rawVal = cellText
            let resolved: String
            switch currentCellType {
            case "s":
                if let idx = Int(rawVal.trimmingCharacters(in: .whitespacesAndNewlines)),
                   idx >= 0 && idx < sharedStrings.count {
                    resolved = sharedStrings[idx]
                } else {
                    resolved = rawVal
                }
            case "inlineStr", "str":
                resolved = rawVal
            default:
                if let styleIdx = currentCellStyle,
                   let padWidth = zeroPadWidthsByStyle[styleIdx],
                   !rawVal.isEmpty && rawVal.allSatisfy({ $0.isNumber && $0.isASCII }) {
                    if rawVal.count < padWidth {
                        resolved = String(repeating: "0", count: padWidth - rawVal.count) + rawVal
                    } else {
                        resolved = rawVal
                    }
                } else {
                    resolved = rawVal
                }
            }
            if currentRowNum > 0 && currentColNum > 0 {
                rows[currentRowNum, default: [:]][currentColNum] = resolved
            }
        default:
            break
        }
    }
}
