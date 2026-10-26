package vn.vnpt.ontconfig.data.excel

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.SubscriberStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ExcelManager {

    const val SOURCE_CACHE_FILE = "current_source.xlsx"
    const val DEMO_ASSET_FILE = "sample-demo.xlsx"

    val STANDARD_30_HEADERS = listOf(
        "STT", "Tổ địa bàn", "NVKT", "SA", "KETCUOI",
        "TEN_TB", "DIACHI_TB", "SO_DT", "SO_MAY", "Account",
        "Mật khẩu PPPoE", "Trạng thái tra WIFI", "Mã MEN", "Tên Model", "SSID Wi-Fi 2.4G",
        "Mật khẩu Wi-Fi 2.4G", "Bảo mật 2.4G", "Trạng thái sóng 2.4G", "SSID Wi-Fi 5G", "Mật khẩu Wi-Fi 5G",
        "Bảo mật 5G", "Trạng thái sóng 5G", "SSID Phụ (Guest)", "Mật khẩu Phụ", "VIDO",
        "KINHDO", "MOC_MODEL", "MOC_BANG_TAN", "HIEN_TAI_MODEL", "HIEN_TAI_BANG_TAN"
    )

    const val COL_STATUS = "Trạng thái cấu hình"
    const val COL_TIME = "Thời gian thực hiện"
    const val COL_NOTE = "Ghi chú chi tiết"

    fun deriveWifi5Config(
        ssid24g: String,
        pass24g: String,
        rawSsid5g: String? = "",
        rawPass5g: String? = "",
        defaultSsid5g: String = "VNPT 5G",
        defaultPass5g: String = "1234567890"
    ): Pair<String, String> {
        val cleanRawSsid5 = (rawSsid5g ?: "").trim()
        val cleanRawPass5 = (rawPass5g ?: "").trim()
        val cleanSsid24 = ssid24g.trim()
        val cleanPass24 = pass24g.trim()

        var ssid5g = when {
            cleanRawSsid5.isNotEmpty() -> cleanRawSsid5
            cleanSsid24.isNotEmpty() -> "$cleanSsid24 5G"
            else -> defaultSsid5g
        }
        if (ssid5g.length > 32) {
            ssid5g = ssid5g.substring(0, 32).trim()
        }

        val pass5g = when {
            cleanRawPass5.isNotEmpty() -> cleanRawPass5
            cleanPass24.isNotEmpty() -> cleanPass24
            else -> defaultPass5g
        }

        return ssid5g to pass5g
    }

    fun deriveWifi5Ssid(
        ssid24g: String,
        rawSsid5g: String? = "",
        defaultSsid5g: String = "VNPT 5G"
    ): String = deriveWifi5Config(
        ssid24g = ssid24g,
        pass24g = "",
        rawSsid5g = rawSsid5g,
        rawPass5g = "",
        defaultSsid5g = defaultSsid5g
    ).first

    /**
     * Parse .xlsx archive using Native ZipInputStream + XmlPullParser.
     * Preserves leading zeros in SO_MAY / SO_DT / Mã MEN and all 30 original columns.
     */
    fun parseExcel(
        inputStream: InputStream,
        defaultWifi5Ssid: String = "VNPT 5G",
        defaultWifi5Pass: String = "1234567890"
    ): List<SubscriberRecord> {
        val zipEntries = readZipEntries(inputStream)
        val sharedStringsXml = zipEntries["xl/sharedStrings.xml"]
        val stylesXml = zipEntries["xl/styles.xml"]
        val sheetXml = zipEntries["xl/worksheets/sheet1.xml"]
            ?: zipEntries.entries.firstOrNull { it.key.startsWith("xl/worksheets/sheet") && it.key.endsWith(".xml") }?.value
            ?: throw IllegalArgumentException("File Excel (.xlsx) không hợp lệ: thiếu xl/worksheets/sheet1.xml")

        val sharedStrings = if (sharedStringsXml != null) {
            parseSharedStrings(sharedStringsXml)
        } else {
            emptyList()
        }

        val zeroPadWidthsByStyle = if (stylesXml != null) {
            parseZeroPadStyles(stylesXml)
        } else {
            emptyMap()
        }

        val rawRows = parseSheetRows(sheetXml, sharedStrings, zeroPadWidthsByStyle)
        if (rawRows.isEmpty()) return emptyList()

        val headerRow = rawRows[1] ?: rawRows.entries.minByOrNull { it.key }?.value ?: return emptyList()
        val headersByCol = mutableMapOf<Int, String>()
        val colByHeader = mutableMapOf<String, Int>()
        val maxHeaderCol = headerRow.keys.maxOrNull() ?: 30

        for (col in 1..maxHeaderCol) {
            val h = headerRow[col]?.trim().orEmpty()
            if (h.isNotEmpty()) {
                headersByCol[col] = h
                colByHeader[h] = col
            }
        }

        fun getCell(rowMap: Map<Int, String>, headerName: String, fallbackCol: Int): String {
            val col = colByHeader[headerName] ?: fallbackCol
            return rowMap[col]?.trim().orEmpty()
        }

        val records = mutableListOf<SubscriberRecord>()
        val sortedRowNums = rawRows.keys.filter { it >= 2 }.sorted()

        for (rowNum in sortedRowNums) {
            val rowMap = rawRows[rowNum] ?: continue
            val sttVal = getCell(rowMap, "STT", 1)
            val soMay = getCell(rowMap, "SO_MAY", 9)
            val maMen = getCell(rowMap, "Mã MEN", 13)
            val accountPppoe = getCell(rowMap, "Account", 10)
            val slid = maMen.ifBlank { soMay }

            if (sttVal.isEmpty() && slid.isEmpty() && accountPppoe.isEmpty()) {
                continue
            }

            val stt = sttVal.toIntOrNull() ?: sttVal.toDoubleOrNull()?.toInt()
            val toDiaBan = getCell(rowMap, "Tổ địa bàn", 2)
            val nvkt = getCell(rowMap, "NVKT", 3)
            val tenTb = getCell(rowMap, "TEN_TB", 6)
            val diachiTb = getCell(rowMap, "DIACHI_TB", 7)
            val soDt = getCell(rowMap, "SO_DT", 8)
            val passPppoe = getCell(rowMap, "Mật khẩu PPPoE", 11)

            val rawSsid24g = getCell(rowMap, "SSID Wi-Fi 2.4G", 15)
            val rawPass24g = getCell(rowMap, "Mật khẩu Wi-Fi 2.4G", 16)

            // [Red Team RT-03 & RT-07] Populate fallback VNPT_{SLID} and 1234567890 if blank in Excel
            val ssid24g = rawSsid24g.ifBlank { if (slid.isNotBlank()) "VNPT_$slid" else "" }
            val pass24g = rawPass24g.ifBlank { "1234567890" }

            val rawSsid5g = getCell(rowMap, "SSID Wi-Fi 5G", 19)
            val rawPass5g = getCell(rowMap, "Mật khẩu Wi-Fi 5G", 20)

            val (ssid5g, pass5g) = deriveWifi5Config(
                ssid24g = ssid24g,
                pass24g = pass24g,
                rawSsid5g = rawSsid5g,
                rawPass5g = rawPass5g,
                defaultSsid5g = defaultWifi5Ssid,
                defaultPass5g = defaultWifi5Pass
            )

            // Preserve all columns from header row in rawRowData
            val rawRowData = linkedMapOf<String, String>()
            for ((colIdx, hdrName) in headersByCol.entries.sortedBy { it.key }) {
                rawRowData[hdrName] = rowMap[colIdx].orEmpty()
            }

            val draftRecord = SubscriberRecord(
                rowIndex = rowNum,
                stt = stt,
                toDiaBan = toDiaBan,
                nvkt = nvkt,
                tenTb = tenTb,
                diachiTb = diachiTb,
                soDt = soDt,
                soMay = slid,
                accountPppoe = accountPppoe,
                passPppoe = passPppoe,
                maMen = maMen,
                slid = slid,
                ssid24g = ssid24g,
                pass24g = pass24g,
                ssid5g = ssid5g,
                pass5g = pass5g,
                rawRowData = rawRowData
            )

            val validation = OntInputValidator.validateOnImport(draftRecord)
            val status = if (validation.isValid) SubscriberStatus.PENDING else SubscriberStatus.INVALID_DATA

            records.add(
                draftRecord.copy(
                    isValid = validation.isValid,
                    validationErrors = validation.errors,
                    status = status
                )
            )
        }

        return records
    }

    /**
     * [Red Team RT-09] Smart merge on re-import:
     * Preserves SUCCESS state, timestamps, lastKnownAdminPass, and KTV's manual edits
     * (PPPoE password, SLID, Wi-Fi SSID/password) when matching by soMay or accountPppoe.
     */
    fun mergeRecordsPreservingEdits(
        existingRecords: List<SubscriberRecord>,
        incomingRecords: List<SubscriberRecord>
    ): List<SubscriberRecord> {
        if (existingRecords.isEmpty()) return incomingRecords

        val byKey = existingRecords.associateBy { recordKey(it) }
        val byRowAndAccount = existingRecords.associateBy {
            "${it.rowIndex}|${it.accountPppoe.trim().lowercase()}"
        }
        val byRawSoMayAndAccount = existingRecords
            .filter { !it.rawRowData["SO_MAY"].isNullOrBlank() || !it.rawRowData["Mã MEN"].isNullOrBlank() }
            .associateBy {
                val origSlid = it.rawRowData["Mã MEN"]?.trim()?.ifBlank { it.rawRowData["SO_MAY"]?.trim().orEmpty() }
                    ?: it.rawRowData["SO_MAY"]?.trim().orEmpty()
                "${origSlid.lowercase()}|${it.accountPppoe.trim().lowercase()}"
            }
        return incomingRecords.map { incoming ->
            val prev = byKey[recordKey(incoming)]
                ?: byRowAndAccount["${incoming.rowIndex}|${incoming.accountPppoe.trim().lowercase()}"]
                ?: byRawSoMayAndAccount[recordKey(incoming)]
            if (prev == null) {
                incoming
            } else {
                val mergedPassPppoe = prev.passPppoe.ifBlank { incoming.passPppoe }
                val hasCustomEdits = prev.status == SubscriberStatus.SUCCESS ||
                    prev.passPppoe != incoming.passPppoe ||
                    prev.ssid24g != incoming.ssid24g ||
                    prev.pass24g != incoming.pass24g ||
                    prev.slid != incoming.slid

                val mergedSlid = if (hasCustomEdits && prev.slid.isNotBlank()) prev.slid else incoming.slid
                val mergedDraft = incoming.copy(
                    passPppoe = mergedPassPppoe,
                    slid = mergedSlid,
                    soMay = mergedSlid,
                    ssid24g = if (hasCustomEdits && prev.ssid24g.isNotBlank()) prev.ssid24g else incoming.ssid24g,
                    pass24g = if (hasCustomEdits && prev.pass24g.isNotBlank()) prev.pass24g else incoming.pass24g,
                    ssid5g = if (hasCustomEdits && prev.ssid5g.isNotBlank()) prev.ssid5g else incoming.ssid5g,
                    pass5g = if (hasCustomEdits && prev.pass5g.isNotBlank()) prev.pass5g else incoming.pass5g,
                    lastKnownAdminPass = prev.lastKnownAdminPass.ifBlank { incoming.lastKnownAdminPass }
                )

                val validation = OntInputValidator.validateOnImport(mergedDraft)
                val finalStatus = when {
                    prev.status == SubscriberStatus.SUCCESS -> SubscriberStatus.SUCCESS
                    validation.isValid -> SubscriberStatus.PENDING
                    else -> SubscriberStatus.INVALID_DATA
                }

                mergedDraft.copy(
                    isValid = validation.isValid,
                    validationErrors = validation.errors,
                    status = finalStatus,
                    startTime = prev.startTime,
                    endTime = prev.endTime,
                    errorMessage = if (finalStatus == SubscriberStatus.SUCCESS) "" else prev.errorMessage
                )
            }
        }
    }

    private fun recordKey(record: SubscriberRecord): String {
        return "${record.slid.trim().lowercase()}|${record.accountPppoe.trim().lowercase()}"
    }

    /**
     * Normalize phone numbers by stripping leading '0' and whitespace so searching
     * "0978194878" or "978194878" matches regardless of how Excel formatted SO_DT.
     */
    fun normalizePhoneForSearch(phone: String): String {
        val digitsOnly = phone.trim().filter { it.isDigit() }
        return digitsOnly.trimStart('0').ifEmpty { phone.trim().lowercase() }
    }

    fun searchSubscribers(
        records: List<SubscriberRecord>,
        query: String,
        statusFilter: SubscriberStatus? = null
    ): List<SubscriberRecord> {
        val statusFiltered = when (statusFilter) {
            null -> records
            SubscriberStatus.INVALID_DATA -> records.filter {
                it.status == SubscriberStatus.INVALID_DATA || it.status == SubscriberStatus.FAILED
            }
            else -> records.filter { it.status == statusFilter }
        }

        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return statusFiltered

        val qLower = cleanQuery.lowercase()
        val qPhoneNorm = normalizePhoneForSearch(cleanQuery)
        val qNoLeadingZeros = cleanQuery.trimStart('0').lowercase()

        return statusFiltered.filter { rec ->
            val recPhoneNorm = normalizePhoneForSearch(rec.soDt)
            val recSlidNoZero = rec.slid.trimStart('0').lowercase()

            rec.accountPppoe.lowercase().contains(qLower) ||
                rec.slid.lowercase().contains(qLower) ||
                (qNoLeadingZeros.isNotEmpty() && recSlidNoZero.contains(qNoLeadingZeros)) ||
                rec.tenTb.lowercase().contains(qLower) ||
                rec.diachiTb.lowercase().contains(qLower) ||
                rec.nvkt.lowercase().contains(qLower) ||
                rec.soDt.lowercase().contains(qLower) ||
                (qPhoneNorm.length >= 3 && recPhoneNorm.contains(qPhoneNorm))
        }
    }

    /**
     * [Red Team RT-09] Export updated .xlsx preserving all 30 original columns,
     * overwriting edited subscriber fields in their respective columns for re-import compatibility,
     * and appending 3 status columns with masked error notes.
     */
    fun exportResults(
        sourceExcelStream: InputStream?,
        outputStream: OutputStream,
        records: List<SubscriberRecord>
    ) {
        val baseHeaders = linkedSetOf<String>()
        if (records.isNotEmpty() && records.first().rawRowData.isNotEmpty()) {
            baseHeaders.addAll(records.first().rawRowData.keys)
        } else {
            baseHeaders.addAll(STANDARD_30_HEADERS)
        }
        baseHeaders.remove(COL_STATUS)
        baseHeaders.remove(COL_TIME)
        baseHeaders.remove(COL_NOTE)

        val finalHeaders = baseHeaders.toList() + listOf(COL_STATUS, COL_TIME, COL_NOTE)
        val sheetBytes = buildWorksheetXml(finalHeaders, records)

        if (sourceExcelStream != null) {
            val zipEntries = readZipEntries(sourceExcelStream).toMutableMap()
            zipEntries["xl/worksheets/sheet1.xml"] = sheetBytes
            ZipOutputStream(outputStream).use { zos ->
                for ((name, bytes) in zipEntries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        } else {
            writeStandaloneXlsx(outputStream, sheetBytes)
        }
    }

    fun cacheSourceExcel(context: Context, bytes: ByteArray): File {
        val file = File(context.filesDir, SOURCE_CACHE_FILE)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    fun getCachedSourceExcelStream(context: Context): InputStream? {
        val file = File(context.filesDir, SOURCE_CACHE_FILE)
        return if (file.exists()) file.inputStream() else null
    }

    // ---- Internal XML Parsing & Serialization Helpers ----

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        bos.write(buffer, 0, read)
                    }
                    entries[entry.name] = bos.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var eventType = parser.eventType
        var inSi = false
        var inT = false
        val currentSi = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> {
                            inSi = true
                            currentSi.setLength(0)
                        }
                        "t" -> if (inSi) inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi && inT) {
                        currentSi.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inT = false
                        "si" -> {
                            if (inSi) {
                                result.add(currentSi.toString())
                                inSi = false
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseZeroPadStyles(xmlBytes: ByteArray): Map<Int, Int> {
        val numFmtZeroWidths = mutableMapOf<Int, Int>()
        val styleZeroWidths = mutableMapOf<Int, Int>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
            var eventType = parser.eventType
            var inCellXfs = false
            var xfIndex = 0
            val zeroOnlyRegex = Regex("^0+$")

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "numFmt" -> {
                                val id = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                                val code = parser.getAttributeValue(null, "formatCode")?.trim().orEmpty()
                                if (id != null && code.length >= 2 && zeroOnlyRegex.matches(code)) {
                                    numFmtZeroWidths[id] = code.length
                                }
                            }
                            "cellXfs" -> {
                                inCellXfs = true
                                xfIndex = 0
                            }
                            "xf" -> if (inCellXfs) {
                                val numFmtId = parser.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                                val padWidth = if (numFmtId != null) numFmtZeroWidths[numFmtId] else null
                                if (padWidth != null) {
                                    styleZeroWidths[xfIndex] = padWidth
                                }
                                xfIndex++
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "cellXfs") inCellXfs = false
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
        }
        return styleZeroWidths
    }

    private fun parseSheetRows(
        xmlBytes: ByteArray,
        sharedStrings: List<String>,
        zeroPadWidthsByStyle: Map<Int, Int>
    ): Map<Int, Map<Int, String>> {
        val rows = linkedMapOf<Int, MutableMap<Int, String>>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var eventType = parser.eventType
        var currentRowNum = 0
        var currentColNum = 0
        var currentCellType: String? = null
        var currentCellStyle: Int? = null
        var inV = false
        var inIsT = false
        val cellText = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> {
                            currentRowNum = parser.getAttributeValue(null, "r")?.toIntOrNull()
                                ?: (currentRowNum + 1)
                            rows.putIfAbsent(currentRowNum, linkedMapOf())
                            currentColNum = 0
                        }
                        "c" -> {
                            val ref = parser.getAttributeValue(null, "r")
                            currentColNum = if (ref != null) {
                                columnRefToIndex(ref)
                            } else {
                                currentColNum + 1
                            }
                            currentCellType = parser.getAttributeValue(null, "t")
                            currentCellStyle = parser.getAttributeValue(null, "s")?.toIntOrNull()
                            cellText.setLength(0)
                        }
                        "v" -> inV = true
                        "t" -> inIsT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inV || inIsT) {
                        cellText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> inV = false
                        "t" -> inIsT = false
                        "c" -> {
                            val rawVal = cellText.toString()
                            val resolved = when (currentCellType) {
                                "s" -> {
                                    val idx = rawVal.trim().toIntOrNull()
                                    if (idx != null && idx in sharedStrings.indices) {
                                        sharedStrings[idx]
                                    } else {
                                        rawVal
                                    }
                                }
                                "inlineStr", "str" -> rawVal
                                else -> {
                                    val padWidth = if (currentCellStyle != null) {
                                        zeroPadWidthsByStyle[currentCellStyle]
                                    } else {
                                        null
                                    }
                                    if (padWidth != null && rawVal.all { it.isDigit() }) {
                                        rawVal.padStart(padWidth, '0')
                                    } else {
                                        rawVal
                                    }
                                }
                            }
                            if (currentRowNum > 0 && currentColNum > 0) {
                                rows.getOrPut(currentRowNum) { linkedMapOf() }[currentColNum] = resolved
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return rows
    }

    private fun buildWorksheetXml(
        headers: List<String>,
        records: List<SubscriberRecord>
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        val serializer: XmlSerializer = XmlPullParserFactory.newInstance().newSerializer()
        serializer.setOutput(bos, "UTF-8")
        serializer.startDocument("UTF-8", true)

        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        serializer.setPrefix("", ns)
        serializer.startTag(ns, "worksheet")
        serializer.startTag(ns, "sheetData")

        // Row 1: Headers
        serializer.startTag(ns, "row")
        serializer.attribute(null, "r", "1")
        for ((idx, header) in headers.withIndex()) {
            val colIdx = idx + 1
            val cellRef = "${columnIndexToRef(colIdx)}1"
            writeInlineStringCell(serializer, ns, cellRef, header)
        }
        serializer.endTag(ns, "row")

        // Data Rows (2..N+1)
        for ((rOffset, rec) in records.withIndex()) {
            val xmlRowNum = rOffset + 2
            serializer.startTag(ns, "row")
            serializer.attribute(null, "r", xmlRowNum.toString())

            val timeStr = when {
                rec.startTime.isNotBlank() && rec.endTime.isNotBlank() -> "${rec.startTime} -> ${rec.endTime}"
                rec.endTime.isNotBlank() -> rec.endTime
                else -> ""
            }
            val currentErrors = OntInputValidator.validateOnImport(rec).errors
            val rawNote = when {
                rec.errorMessage.isNotBlank() -> rec.errorMessage
                rec.status == SubscriberStatus.SUCCESS -> "OK"
                currentErrors.isNotEmpty() -> currentErrors.joinToString(", ")
                else -> "OK"
            }
            val maskedNote = OntInputValidator.maskSensitiveCredentials(rawNote)

            for ((cOffset, header) in headers.withIndex()) {
                val colIdx = cOffset + 1
                val cellRef = "${columnIndexToRef(colIdx)}$xmlRowNum"
                val value = when (header) {
                    "STT" -> (rec.stt ?: (rOffset + 1)).toString()
                    "Tổ địa bàn" -> rec.toDiaBan.ifBlank { rec.rawRowData[header].orEmpty() }
                    "NVKT" -> rec.nvkt.ifBlank { rec.rawRowData[header].orEmpty() }
                    "TEN_TB" -> rec.tenTb.ifBlank { rec.rawRowData[header].orEmpty() }
                    "DIACHI_TB" -> rec.diachiTb.ifBlank { rec.rawRowData[header].orEmpty() }
                    "SO_DT" -> rec.soDt.ifBlank { rec.rawRowData[header].orEmpty() }
                    "SO_MAY" -> rec.slid
                    "Account" -> rec.accountPppoe
                    "Mật khẩu PPPoE" -> rec.passPppoe
                    "Mã MEN" -> rec.slid
                    "SSID Wi-Fi 2.4G" -> rec.ssid24g
                    "Mật khẩu Wi-Fi 2.4G" -> rec.pass24g
                    "SSID Wi-Fi 5G" -> rec.ssid5g
                    "Mật khẩu Wi-Fi 5G" -> rec.pass5g
                    COL_STATUS -> rec.status.name
                    COL_TIME -> timeStr
                    COL_NOTE -> maskedNote
                    else -> rec.rawRowData[header].orEmpty()
                }
                writeInlineStringCell(serializer, ns, cellRef, value)
            }

            serializer.endTag(ns, "row")
        }

        serializer.endTag(ns, "sheetData")
        serializer.endTag(ns, "worksheet")
        serializer.endDocument()
        serializer.flush()
        return bos.toByteArray()
    }

    private fun writeInlineStringCell(
        serializer: XmlSerializer,
        ns: String,
        cellRef: String,
        value: String
    ) {
        serializer.startTag(ns, "c")
        serializer.attribute(null, "r", cellRef)
        serializer.attribute(null, "t", "inlineStr")
        serializer.startTag(ns, "is")
        serializer.startTag(ns, "t")
        serializer.text(value)
        serializer.endTag(ns, "t")
        serializer.endTag(ns, "is")
        serializer.endTag(ns, "c")
    }

    private fun writeStandaloneXlsx(outputStream: OutputStream, sheetBytes: ByteArray) {
        ZipOutputStream(outputStream).use { zos ->
            fun putXml(name: String, xml: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            putXml(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                |<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                |<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                |<Default Extension="xml" ContentType="application/xml"/>
                |<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                |<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                |</Types>""".trimMargin()
            )
            putXml(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                |</Relationships>""".trimMargin()
            )
            putXml(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                |<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                |<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                |</workbook>""".trimMargin()
            )
            putXml(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                |<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                |<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                |</Relationships>""".trimMargin()
            )
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheetBytes)
            zos.closeEntry()
        }
    }

    internal fun columnRefToIndex(cellRef: String): Int {
        var col = 0
        for (ch in cellRef) {
            if (ch.isLetter()) {
                col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return col
    }

    internal fun columnIndexToRef(colIndex: Int): String {
        var n = colIndex
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }
}
