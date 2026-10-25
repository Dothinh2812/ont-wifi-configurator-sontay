package vn.vnpt.ontconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import vn.vnpt.ontconfig.data.excel.ExcelManager
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.SubscriberStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ExcelAndValidatorTest {

    private fun loadSampleExcelBytes(): ByteArray {
        val resourceStream = javaClass.classLoader?.getResourceAsStream("file-mau.xlsx")
        if (resourceStream != null) {
            return resourceStream.use { it.readBytes() }
        }
        val fallbackFile = File("src/test/resources/file-mau.xlsx")
        return fallbackFile.readBytes()
    }

    @Test
    fun `parseExcel reads all 89 rows from file-mau xlsx and preserves leading zeros and 30 columns`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes))

        assertEquals(89, records.size)

        // Row 1 (STT 1): SO_MAY must preserve leading zeros "001560828"
        val first = records[0]
        assertEquals(1, first.stt)
        assertEquals("001560828", first.soMay)
        assertEquals("kimthitv878", first.accountPppoe)
        assertEquals("001560828", first.slid)
        assertEquals(30, first.rawRowData.size)

        // Row 3 (STT 3): empty Wi-Fi 2.4G should populate fallback VNPT_{SO_MAY} and 1234567890
        val third = records[2]
        assertEquals("005642219", third.soMay)
        assertEquals("VNPT_005642219", third.ssid24g)
        assertEquals("1234567890", third.pass24g)
        assertEquals("VNPT_005642219 5G", third.ssid5g)
        assertEquals("1234567890", third.pass5g)
    }

    @Test
    fun `validateOnImport detects exactly 13 rows missing PPPoE password in file-mau xlsx`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes))

        val invalidOnImport = records.filter { !it.isValid }
        assertEquals(13, invalidOnImport.size)
        assertTrue(invalidOnImport.all { it.passPppoe.isBlank() })
        assertTrue(invalidOnImport.all { it.status == SubscriberStatus.INVALID_DATA })
    }

    @Test
    fun `validateBeforeRun catches the 5 rows with 7-digit Wi-Fi passwords and supports 1-tap prepend 0`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes))
        val settings = GlobalSettings()

        // Filter records that passed import validation (have PPPoE password) but fail pre-run validation
        val validOnImport = records.filter { it.isValid }
        assertEquals(76, validOnImport.size)

        val failingBeforeRun = validOnImport.filter { !OntInputValidator.validateBeforeRun(it, settings).isValid }
        assertEquals(5, failingBeforeRun.size)

        val sevenDigitPasswords = failingBeforeRun.map { it.pass24g }.toSet()
        assertEquals(
            setOf("3011965", "3820275", "5091993", "4091981", "8042020"),
            sevenDigitPasswords
        )

        // Verify 1-tap Prepend '0' action fixes both 2.4G and 5G passwords and passes validateBeforeRun
        for (rec in failingBeforeRun) {
            val preRunResult = OntInputValidator.validateBeforeRun(rec, settings)
            assertTrue(preRunResult.canPrependZero24g)
            val fixed = OntInputValidator.prependLeadingZeroToWifiPass(rec, sync5g = true)
            assertEquals("0${rec.pass24g}", fixed.pass24g)
            assertEquals("0${rec.pass24g}", fixed.pass5g)
            val revalidated = OntInputValidator.validateBeforeRun(fixed, settings)
            assertTrue("Expected fixed record ${fixed.soMay} to pass validateBeforeRun: ${revalidated.errors}", revalidated.isValid)
        }
    }

    @Test
    fun `OntInputValidator blocks all 11 unsafe shell metacharacters including backslash and validates SLID and Admin pass`() {
        val expectedMetachars = setOf('&', '|', ';', '$', '>', '<', '`', '\\', '!', '\'', '"')
        assertEquals(11, OntInputValidator.UNSAFE_SHELL_METACHARS.size)
        assertEquals(expectedMetachars, OntInputValidator.UNSAFE_SHELL_METACHARS)

        for (ch in expectedMetachars) {
            val rec = SubscriberRecord(
                rowIndex = 2,
                soMay = "001560828",
                accountPppoe = "user_ok",
                passPppoe = "pass${ch}bad",
                slid = "001560828",
                ssid24g = "VNPT_Home",
                pass24g = "12345678",
                ssid5g = "VNPT_Home 5G",
                pass5g = "12345678"
            )
            val res = OntInputValidator.validateOnImport(rec)
            assertFalse("Expected metachar '$ch' to fail validateOnImport", res.isValid)
        }

        // SLID > 10 chars fails
        val longSlidRec = SubscriberRecord(
            rowIndex = 2,
            soMay = "001560828",
            accountPppoe = "user_ok",
            passPppoe = "pass1234",
            slid = "12345678901",
            ssid24g = "VNPT_Home",
            pass24g = "12345678"
        )
        assertFalse(OntInputValidator.validateOnImport(longSlidRec).isValid)

        // Admin password complexity: 8..32 printable ASCII without spaces, upper+lower+digit+special
        assertTrue(OntInputValidator.validatePasswordComplexity("Passwd2@"))
        assertFalse(OntInputValidator.validatePasswordComplexity("passwd2@")) // no uppercase
        assertFalse(OntInputValidator.validatePasswordComplexity("Passwd 2@")) // contains space
        assertFalse(OntInputValidator.validatePasswordComplexity("Pass2@")) // < 8 chars
        assertFalse(OntInputValidator.validatePasswordComplexity("A".repeat(30) + "a1@")) // > 32 chars

        // URL encoding & ontEncodeQuery
        assertEquals("A%20B%2BC%3DD%25E", OntInputValidator.urlEncodeFormValue("A B+C=D%E"))
        assertEquals("a=1\u007fb=2\u007fc=3", OntInputValidator.ontEncodeQuery("a=1&b=2&c=3"))
    }

    @Test
    fun `searchSubscribers finds records by Account, SO_MAY, TEN_TB, and SO_DT with or without leading zero`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes))

        // Search by ma_tb (Account)
        val byAccount = ExcelManager.searchSubscribers(records, "manh9653")
        assertEquals(1, byAccount.size)
        assertEquals("005501941", byAccount[0].soMay)

        // Search by SO_DT with leading 0 ("0978194878") and without leading 0 ("978194878")
        val byPhoneWithZero = ExcelManager.searchSubscribers(records, "0978194878")
        val byPhoneWithoutZero = ExcelManager.searchSubscribers(records, "978194878")
        assertEquals(1, byPhoneWithZero.size)
        assertEquals(1, byPhoneWithoutZero.size)
        assertEquals("kimthitv878", byPhoneWithZero[0].accountPppoe)
        assertEquals("kimthitv878", byPhoneWithoutZero[0].accountPppoe)
    }

    @Test
    fun `mergeRecordsPreservingEdits preserves SUCCESS status and manually entered PPPoE password on re-import`() {
        val bytes = loadSampleExcelBytes()
        val initial = ExcelManager.parseExcel(ByteArrayInputStream(bytes)).toMutableList()

        // Simulate KTV entering missing PPPoE password on row 0 (kimthitv878) and completing setup
        val editedFirst = initial[0].copy(
            passPppoe = "ktv_entered_123",
            ssid24g = "KimThu_New",
            pass24g = "88889999",
            ssid5g = "KimThu_New 5G",
            pass5g = "88889999",
            isValid = true,
            validationErrors = emptyList(),
            status = SubscriberStatus.SUCCESS,
            startTime = "09:00:00",
            endTime = "09:01:15",
            lastKnownAdminPass = "Passwd2@"
        )
        initial[0] = editedFirst

        // Re-import fresh Excel
        val reimported = ExcelManager.parseExcel(ByteArrayInputStream(bytes))
        val merged = ExcelManager.mergeRecordsPreservingEdits(initial, reimported)

        assertEquals(89, merged.size)
        val mergedFirst = merged[0]
        assertEquals(SubscriberStatus.SUCCESS, mergedFirst.status)
        assertEquals("ktv_entered_123", mergedFirst.passPppoe)
        assertEquals("KimThu_New", mergedFirst.ssid24g)
        assertEquals("88889999", mergedFirst.pass24g)
        assertEquals("Passwd2@", mergedFirst.lastKnownAdminPass)
    }

    @Test
    fun `exportResults preserves all 30 original columns, writes back edited fields, and adds 3 status columns with masked error logs`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes)).toMutableList()

        // Modify first record (add PPPoE pass, change SSID/Pass, mark SUCCESS)
        records[0] = records[0].copy(
            passPppoe = "updated_pppoe_pass",
            ssid24g = "Updated_SSID_24G",
            pass24g = "updated_wifi_pass",
            ssid5g = "Updated_SSID_24G 5G",
            pass5g = "updated_wifi_pass",
            slid = "NEW_SLID01",
            status = SubscriberStatus.SUCCESS,
            startTime = "10:00:00",
            endTime = "10:01:00",
            errorMessage = ""
        )

        // Modify second record to have an error message containing sensitive credentials
        records[1] = records[1].copy(
            status = SubscriberStatus.FAILED,
            startTime = "10:02:00",
            endTime = "10:02:30",
            errorMessage = "Lỗi tại sendwan.cmd: pppPassword=6l6s8z2a&wl_wpa_psk=28121995&sessionKey=123456"
        )

        val outStream = ByteArrayOutputStream()
        ExcelManager.exportResults(ByteArrayInputStream(bytes), outStream, records)
        val exportedBytes = outStream.toByteArray()
        assertTrue(exportedBytes.isNotEmpty())

        // Re-parse the exported Excel file to verify all 30 original columns + 3 appended columns (33 total)
        val reParsed = ExcelManager.parseExcel(ByteArrayInputStream(exportedBytes))
        assertEquals(89, reParsed.size)
        assertEquals("001560828", reParsed[0].soMay)
        assertEquals("updated_pppoe_pass", reParsed[0].passPppoe)
        assertEquals("Updated_SSID_24G", reParsed[0].ssid24g)
        assertEquals("updated_wifi_pass", reParsed[0].pass24g)
        assertEquals("NEW_SLID01", reParsed[0].slid)

        // Verify 33 columns in rawRowData (30 original + 3 status columns)
        assertEquals(33, reParsed[0].rawRowData.size)
        assertEquals("SUCCESS", reParsed[0].rawRowData["Trạng thái cấu hình"])
        assertEquals("10:00:00 -> 10:01:00", reParsed[0].rawRowData["Thời gian thực hiện"])
        assertEquals("OK", reParsed[0].rawRowData["Ghi chú chi tiết"])

        // Verify sensitive credentials are masked in "Ghi chú chi tiết"
        val secondNote = reParsed[1].rawRowData["Ghi chú chi tiết"]
        assertNotNull(secondNote)
        assertFalse(secondNote!!.contains("6l6s8z2a"))
        assertFalse(secondNote.contains("28121995"))
        assertFalse(secondNote.contains("123456"))
        assertTrue(secondNote.contains("pppPassword=***"))
        assertTrue(secondNote.contains("wl_wpa_psk=***"))
        assertTrue(secondNote.contains("sessionKey=***"))
    }

    @Test
    fun `SubscriberEntity preserves all 30 raw columns and normalized phone across SQLite round-trip`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes))
        val entity = vn.vnpt.ontconfig.data.local.SubscriberEntity.fromRecord(records[0])

        assertEquals("978194878", entity.soDtNormalized)
        val restored = entity.toRecord()
        assertEquals(records[0].soMay, restored.soMay)
        assertEquals(records[0].accountPppoe, restored.accountPppoe)
        assertEquals(30, restored.rawRowData.size)
        assertEquals(records[0].rawRowData, restored.rawRowData)
    }
}
