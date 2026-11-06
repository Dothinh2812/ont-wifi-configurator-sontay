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
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
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
            slid = "NEW_SLID01",
            soMay = "NEW_SLID01",
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
        assertEquals("NEW_SLID01", mergedFirst.slid)
        assertEquals("NEW_SLID01", mergedFirst.soMay)
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
            errorMessage = "Lỗi tại sendwan.cmd: pppPassword=6l6s8z2a&wl_wpa_psk=28121995&sessionKey=123456&data=slid01"
        )

        val outStream = ByteArrayOutputStream()
        ExcelManager.exportResults(ByteArrayInputStream(bytes), outStream, records)
        val exportedBytes = outStream.toByteArray()
        assertTrue(exportedBytes.isNotEmpty())

        // Re-parse the exported Excel file to verify all 30 original columns + 3 appended columns (33 total)
        val reParsed = ExcelManager.parseExcel(ByteArrayInputStream(exportedBytes))
        assertEquals(89, reParsed.size)
        assertEquals("NEW_SLID01", reParsed[0].soMay)
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
        assertFalse(secondNote.contains("slid01"))
        assertTrue(secondNote.contains("pppPassword=***"))
        assertTrue(secondNote.contains("wl_wpa_psk=***"))
        assertTrue(secondNote.contains("sessionKey=***"))
        assertTrue(secondNote.contains("data=***"))
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

    @Test
    fun `validateBeforeRun enforces firmware file extension and size bounds when enableFirmwareUpdate is true`() {
        val validRecord = SubscriberRecord(
            rowIndex = 2,
            stt = 1,
            soMay = "005501941",
            accountPppoe = "manh9653",
            passPppoe = "6l6s8z2a",
            maMen = "005501941",
            slid = "005501941",
            ssid24g = "Manh Ngoc",
            pass24g = "28121995",
            ssid5g = "Manh Ngoc 5G",
            pass5g = "28121995"
        )

        // 1. Missing firmware file when enableFirmwareUpdate = true
        val missingFwCheck = OntInputValidator.validateBeforeRun(
            record = validRecord,
            settings = GlobalSettings(enableFirmwareUpdate = true)
        )
        assertFalse(missingFwCheck.isValid)
        assertTrue(missingFwCheck.errors.any { it.contains("Firmware", ignoreCase = true) })

        // 2. Invalid extension (.txt)
        val badExtCheck = OntInputValidator.validateBeforeRun(
            record = validRecord,
            settings = GlobalSettings(
                enableFirmwareUpdate = true,
                firmwareFilePath = "/data/user/0/vn.vnpt.ontconfig/no_backup/firmware_cache/fw.txt",
                firmwareFileName = "fw.txt",
                firmwareFileSize = 1024 * 1024L
            )
        )
        assertFalse(badExtCheck.isValid)
        assertTrue(badExtCheck.errors.any { it.contains(".signed") || it.contains("Định dạng", ignoreCase = true) })

        // 3. Size too small (< 64 KB)
        val tooSmallCheck = OntInputValidator.validateBeforeRun(
            record = validRecord,
            settings = GlobalSettings(
                enableFirmwareUpdate = true,
                firmwareFilePath = "/data/user/0/vn.vnpt.ontconfig/no_backup/firmware_cache/fw.signed",
                firmwareFileName = "GW040_NSv2.signed",
                firmwareFileSize = 1024L
            )
        )
        assertFalse(tooSmallCheck.isValid)

        // 4. Size too large (> 64 MB)
        val tooLargeCheck = OntInputValidator.validateBeforeRun(
            record = validRecord,
            settings = GlobalSettings(
                enableFirmwareUpdate = true,
                firmwareFilePath = "/data/user/0/vn.vnpt.ontconfig/no_backup/firmware_cache/fw.signed",
                firmwareFileName = "GW040_NSv2.signed",
                firmwareFileSize = 65L * 1024L * 1024L
            )
        )
        assertFalse(tooLargeCheck.isValid)

        // 5. Valid .signed / .bin / .img within 64 KB .. 64 MB
        val validFwCheck = OntInputValidator.validateBeforeRun(
            record = validRecord,
            settings = GlobalSettings(
                enableFirmwareUpdate = true,
                firmwareFilePath = "/data/user/0/vn.vnpt.ontconfig/no_backup/firmware_cache/GW040_NSv2.signed",
                firmwareFileName = "GW040_NSv2.signed",
                firmwareFileSize = 24L * 1024L * 1024L
            )
        )
        assertTrue("Expected valid firmware config but got: ${validFwCheck.errors}", validFwCheck.isValid)
    }

    @Test
    fun `sanitizeFirmwareFilename and isValidFirmwareExtension handle special characters and JSON sessionKey masking`() {
        assertTrue(OntInputValidator.isValidFirmwareExtension("GW040-NSv2_V1.2.3.signed"))
        assertTrue(OntInputValidator.isValidFirmwareExtension("fw.signed.bin"))
        assertTrue(OntInputValidator.isValidFirmwareExtension("image_update.BIN"))
        assertTrue(OntInputValidator.isValidFirmwareExtension("rootfs.img"))
        assertFalse(OntInputValidator.isValidFirmwareExtension("notes.xlsx"))
        assertFalse(OntInputValidator.isValidFirmwareExtension(""))

        assertEquals(
            "GW040_NSv2_2026_.signed",
            OntInputValidator.sanitizeFirmwareFilename("GW040 NSv2(2026)\".signed")
        )
        assertEquals("firmware.signed", OntInputValidator.sanitizeFirmwareFilename("   "))

        // JSON sessionKey masking (including truncated JSON from parseJsonOrThrow)
        val rawJsonError = """Lỗi JSON: {"status":"invalid","sessionKey": "secret_token_998877"""
        val masked = OntInputValidator.maskSensitiveCredentials(rawJsonError)
        assertFalse("JSON sessionKey must be masked: $masked", masked.contains("secret_token_998877"))
        assertTrue(masked.contains("***"))
    }

    @Test
    fun `exportResults preserves custom note in Ghi chu chi tiet when non-blank on SUCCESS record`() {
        val bytes = loadSampleExcelBytes()
        val records = ExcelManager.parseExcel(ByteArrayInputStream(bytes)).toMutableList()
        val customNote = "Hoàn tất 9/9 bước: Đã đổi cả 2 Wi-Fi & nạp FW (GW040.signed)"
        records[0] = records[0].copy(
            passPppoe = "6l6s8z2a",
            status = SubscriberStatus.SUCCESS,
            startTime = "11:00:00",
            endTime = "11:01:30",
            errorMessage = customNote
        )

        val outStream = ByteArrayOutputStream()
        ExcelManager.exportResults(ByteArrayInputStream(bytes), outStream, records)
        val reParsed = ExcelManager.parseExcel(ByteArrayInputStream(outStream.toByteArray()))
        assertEquals("SUCCESS", reParsed[0].rawRowData["Trạng thái cấu hình"])
        assertEquals(customNote, reParsed[0].rawRowData["Ghi chú chi tiết"])
    }

    @Test
    fun `WifiSecurityMode maps all 6 modes and validateBeforeRun enforces password length only when requiresPassword is true`() {
        assertEquals(6, WifiSecurityMode.entries.size)
        assertEquals("aes", WifiSecurityMode.WPA2_WPA3_PSK.crypto)
        assertEquals("tkip+aes", WifiSecurityMode.WPA_WPA2_PSK.crypto)
        assertEquals("tkip+aes", WifiSecurityMode.WPA_PSK.crypto)
        assertEquals("disabled", WifiSecurityMode.OPEN.akmPsk)
        assertFalse(WifiSecurityMode.OPEN.requiresPassword)

        val shortPassRecord = SubscriberRecord(
            rowIndex = 2,
            soMay = "005501941",
            accountPppoe = "manh9653",
            passPppoe = "6l6s8z2a",
            slid = "005501941",
            ssid24g = "Manh Ngoc",
            pass24g = "3011965",
            ssid5g = "Manh Ngoc 5G",
            pass5g = "3011965"
        )

        // Fails in PSK mode
        val pskCheck = OntInputValidator.validateBeforeRun(shortPassRecord, GlobalSettings(wifiSecurityMode = "wpa2_psk"))
        assertFalse(pskCheck.isValid)
        assertTrue(pskCheck.canPrependZero24g)

        // Passes in Open mode and disables canPrependZero
        val openCheck = OntInputValidator.validateBeforeRun(shortPassRecord, GlobalSettings(wifiSecurityMode = "open"))
        assertTrue("Expected open mode to allow short/blank password: ${openCheck.errors}", openCheck.isValid)
        assertFalse(openCheck.canPrependZero24g)
        assertFalse(openCheck.canPrependZero5g)

        // Rejects control character 0x7F in validateOnImport and sanitizeInputMetacharacters
        val delCharRecord = shortPassRecord.copy(ssid24g = "VNPT\u007fInject", pass24g = "12345678", pass5g = "12345678")
        assertFalse(OntInputValidator.validateOnImport(delCharRecord).isValid)
    }

    @Test
    fun `GlobalSettings exposes long handover timeouts and maskSensitiveCredentials masks wirelesscfg NVRAM keys`() {
        val defaults = GlobalSettings()
        assertEquals(180_000L, defaults.wifiHandoverTimeoutMs)
        assertEquals(60_000L, defaults.gatewayPostHandoverTimeoutMs)
        assertEquals(90_000L, defaults.postSecondWifiSettleTimeoutMs)
        assertEquals(180_000L, defaults.firmwareUploadTimeoutMs)

        val rawWirelessCfgJson = """{"nvramWlSsid":"WIFI-5G","nvramWlWpaPsk":"MySecretWifiPass 2026","nvramWlRadiusKey":"radSecret99","nvramWlKey1":"wepKeyOne","nvramWlKey":"mainWepKey","sessionKey":"tok_abc123"}"""
        val maskedJson = OntInputValidator.maskSensitiveCredentials(rawWirelessCfgJson)
        assertFalse("nvramWlWpaPsk must be masked: $maskedJson", maskedJson.contains("MySecretWifiPass"))
        assertFalse("nvramWlRadiusKey must be masked: $maskedJson", maskedJson.contains("radSecret99"))
        assertFalse("nvramWlKey1 must be masked: $maskedJson", maskedJson.contains("wepKeyOne"))
        assertFalse("nvramWlKey must be masked: $maskedJson", maskedJson.contains("mainWepKey"))
        assertFalse("sessionKey must be masked: $maskedJson", maskedJson.contains("tok_abc123"))
        assertTrue("Non-sensitive SSID must remain readable: $maskedJson", maskedJson.contains("WIFI-5G"))

        val rawForm = "nvramWlWpaPsk=FormPass88&nvramWlRadiusKey=FormRad77&nvramWlKey2=FormWep22"
        val maskedForm = OntInputValidator.maskSensitiveCredentials(rawForm)
        assertFalse(maskedForm.contains("FormPass88"))
        assertFalse(maskedForm.contains("FormRad77"))
        assertFalse(maskedForm.contains("FormWep22"))

        val spacedJson = """{ "pass" : "Manh Ngoc@26", "passphrase": "My Secret 5G", "wpa_psk": "WpaKey 88", "preSharedKey": "Psk 99" }"""
        val maskedSpacedJson = OntInputValidator.maskSensitiveCredentials(spacedJson)
        assertFalse("JSON pass with spaces must be masked: $maskedSpacedJson", maskedSpacedJson.contains("Manh Ngoc@26"))
        assertFalse("JSON passphrase must be masked: $maskedSpacedJson", maskedSpacedJson.contains("My Secret 5G"))
        assertFalse("JSON wpa_psk must be masked: $maskedSpacedJson", maskedSpacedJson.contains("WpaKey 88"))
        assertFalse("JSON preSharedKey must be masked: $maskedSpacedJson", maskedSpacedJson.contains("Psk 99"))
    }

    @Test
    fun `OntWifiConnector validates OS security capability before Step 1 and enforces 4 scans per 120s sliding quota`() {
        // API 29+ rejects pure WPA-PSK (TKIP) for WifiNetworkSpecifier handover
        val wpaOnQ = vn.vnpt.ontconfig.core.wifi.OntWifiConnector.validateHandoverSecurityCapability(
            secMode = "wpa_psk",
            sdkInt = 29,
            isWpa3SaeSupported = true
        )
        assertNotNull("Pure WPA-PSK must be rejected on Android 10+ before Step 1", wpaOnQ)

        // API 28 allows pure WPA-PSK via legacy WifiConfiguration
        val wpaOnP = vn.vnpt.ontconfig.core.wifi.OntWifiConnector.validateHandoverSecurityCapability(
            secMode = "wpa_psk",
            sdkInt = 28,
            isWpa3SaeSupported = false
        )
        org.junit.Assert.assertNull("Pure WPA-PSK is supported on API 26-28", wpaOnP)

        // WPA3-PSK rejected when SAE unsupported or API < 29
        assertNotNull(
            vn.vnpt.ontconfig.core.wifi.OntWifiConnector.validateHandoverSecurityCapability(
                secMode = "wpa3_psk",
                sdkInt = 31,
                isWpa3SaeSupported = false
            )
        )
        assertNotNull(
            vn.vnpt.ontconfig.core.wifi.OntWifiConnector.validateHandoverSecurityCapability(
                secMode = "wpa3_psk",
                sdkInt = 28,
                isWpa3SaeSupported = true
            )
        )
        org.junit.Assert.assertNull(
            vn.vnpt.ontconfig.core.wifi.OntWifiConnector.validateHandoverSecurityCapability(
                secMode = "wpa3_psk",
                sdkInt = 31,
                isWpa3SaeSupported = true
            )
        )
        org.junit.Assert.assertNull(
            vn.vnpt.ontconfig.core.wifi.OntWifiConnector.validateHandoverSecurityCapability(
                secMode = "wpa2_wpa3_psk",
                sdkInt = 31,
                isWpa3SaeSupported = false
            )
        )

        // Sliding 120_000 ms window enforces max 4 startScan() calls
        val deque = ArrayDeque<Long>()
        assertTrue(vn.vnpt.ontconfig.core.wifi.OntWifiConnector.tryConsumeScanQuotaInWindow(deque, 1_000L))
        assertTrue(vn.vnpt.ontconfig.core.wifi.OntWifiConnector.tryConsumeScanQuotaInWindow(deque, 5_000L))
        assertTrue(vn.vnpt.ontconfig.core.wifi.OntWifiConnector.tryConsumeScanQuotaInWindow(deque, 10_000L))
        assertTrue(vn.vnpt.ontconfig.core.wifi.OntWifiConnector.tryConsumeScanQuotaInWindow(deque, 15_000L))
        assertFalse("5th scan within 120s must be throttled", vn.vnpt.ontconfig.core.wifi.OntWifiConnector.tryConsumeScanQuotaInWindow(deque, 50_000L))
        assertTrue("After 120s from 1st scan, 1 slot opens", vn.vnpt.ontconfig.core.wifi.OntWifiConnector.tryConsumeScanQuotaInWindow(deque, 121_500L))

        // Non-consuming quota check and progressive spaced backoff 5s -> 10s -> 15s -> 20s -> 20s
        assertFalse(vn.vnpt.ontconfig.core.wifi.OntWifiConnector.hasAvailableScanQuotaInWindow(deque, 122_000L))
        assertTrue(vn.vnpt.ontconfig.core.wifi.OntWifiConnector.hasAvailableScanQuotaInWindow(deque, 126_000L))
        assertEquals(5_000L, vn.vnpt.ontconfig.core.wifi.OntWifiConnector.computeReconnectBackoffMs(1))
        assertEquals(10_000L, vn.vnpt.ontconfig.core.wifi.OntWifiConnector.computeReconnectBackoffMs(2))
        assertEquals(15_000L, vn.vnpt.ontconfig.core.wifi.OntWifiConnector.computeReconnectBackoffMs(3))
        assertEquals(20_000L, vn.vnpt.ontconfig.core.wifi.OntWifiConnector.computeReconnectBackoffMs(4))
        assertEquals(20_000L, vn.vnpt.ontconfig.core.wifi.OntWifiConnector.computeReconnectBackoffMs(7))
    }

    @Test
    fun `MainUiState builds 8-step Dual-Wi-Fi and 9-step Final-Firmware lists and cleanupStaleExportFiles removes cached xlsx PII`() {
        val eightSteps = vn.vnpt.ontconfig.ui.MainUiState.defaultEightStepsDualWifi()
        assertEquals(8, eightSteps.size)
        assertTrue(eightSteps.all { it.totalSteps == 8 })
        assertTrue(eightSteps[6].title.contains("Bước 7/8"))
        assertTrue(eightSteps[7].title.contains("Bước 8/8"))

        val nineSteps = vn.vnpt.ontconfig.ui.MainUiState.defaultNineStepsWithFirmware()
        assertEquals(9, nineSteps.size)
        assertTrue(nineSteps.all { it.totalSteps == 9 })
        assertTrue(nineSteps[8].title.contains("Bước 9/9"))
        assertTrue(nineSteps[8].title.contains("Firmware"))

        val tempExportsDir = java.nio.file.Files.createTempDirectory("ont_exports_test").toFile()
        try {
            val stale1 = File(tempExportsDir, "BaoCao_CauHinh_ONT_20261104_100000.xlsx").apply { writeText("pii1") }
            val stale2 = File(tempExportsDir, "BaoCao_CauHinh_ONT_20261105_090000.xlsx").apply { writeText("pii2") }
            val staleLog = File(tempExportsDir, "ONT_Debug_Log_20261106_090500.txt").apply { writeText("debug_log") }
            assertTrue(stale1.exists() && stale2.exists() && staleLog.exists())
            vn.vnpt.ontconfig.ui.MainViewModel.cleanupStaleExportFiles(tempExportsDir)
            assertFalse(stale1.exists())
            assertFalse(stale2.exists())
            assertFalse("Stale ONT_Debug_Log_*.txt must be deleted on cleanup", staleLog.exists())

            val uiState = vn.vnpt.ontconfig.ui.MainUiState()
            assertFalse(uiState.showDiagnosticLogDialog)
            assertTrue(
                "MainUiState must NOT embed diagnosticLogs list to prevent Compose recomposition storms",
                vn.vnpt.ontconfig.ui.MainUiState::class.java.declaredFields.none {
                    it.name.contains("diagnosticLog", ignoreCase = true) && List::class.java.isAssignableFrom(it.type)
                }
            )

            val filePathsXml = File("src/main/res/xml/file_paths.xml").readText(Charsets.UTF_8)
            assertTrue(filePathsXml.contains("exported_reports"))
            assertFalse("file_paths.xml must NOT expose internal files-path", filePathsXml.contains("<files-path"))
        } finally {
            tempExportsDir.deleteRecursively()
        }
    }

    @Test
    fun `AppDiagnosticLogger scrubs raw and URL-encoded secrets synchronously preserves admin token rotates 512KB files and middle-masks PII on export`() {
        val tempLogDir = java.nio.file.Files.createTempDirectory("ont_diag_logger_test").toFile()
        val logger = vn.vnpt.ontconfig.core.logging.AppDiagnosticLogger(
            logDir = tempLogDir,
            maxFileBytes = 2_048L
        )
        try {
            logger.registerActiveSecrets(
                secrets = listOf("admin", "Passwd2@", "Manh&Ngoc#2026", "short"),
                subscriberIdentifiers = listOf("manh9653_st", "005501941")
            )
            val encodedAdminPass = OntInputValidator.urlEncodeFormValue("Passwd2@")
            val ontQuerySecret = OntInputValidator.ontEncodeQuery("Manh&Ngoc#2026")
            logger.log(
                vn.vnpt.ontconfig.core.logging.LogLevel.INFO,
                "AUTH",
                "Gửi user=admin với tài khoản admin token=$encodedAdminPass raw=Passwd2@ query=$ontQuerySecret bssid=AA:BB:CC:11:22:D8 acc=manh9653_st slid=005501941"
            )

            // Synchronous caller-thread check on RAM ring buffer before disk flush
            val ramEntry = logger.entriesFlow.value.single()
            assertFalse("Raw secret leaked in RAM: ${ramEntry.message}", ramEntry.message.contains("Passwd2@"))
            assertFalse("URL-encoded secret leaked in RAM: ${ramEntry.message}", ramEntry.message.contains("Passwd2%40", ignoreCase = true))
            assertFalse("ONT query secret leaked in RAM: ${ramEntry.message}", ramEntry.message.contains(ontQuerySecret))
            assertTrue("Reserved 'admin' keyword must NOT be corrupted: ${ramEntry.message}", ramEntry.message.contains("user=admin"))
            assertTrue("Vietnamese phrase 'tài khoản admin' must stay intact: ${ramEntry.message}", ramEntry.message.contains("tài khoản admin"))

            // Trigger synchronous rotation across ont_debug.log -> ont_debug.prev.log
            val filler = "X".repeat(180)
            repeat(12) { idx ->
                logger.log(vn.vnpt.ontconfig.core.logging.LogLevel.WARN, "HANDOVER", "Retry #$idx Passwd2%40 $filler")
            }
            logger.flushForTest()
            val curFile = File(tempLogDir, "ont_debug.log")
            val prevFile = File(tempLogDir, "ont_debug.prev.log")
            assertTrue("Expected current log file", curFile.exists())
            assertTrue("Expected rotated prev log file", prevFile.exists())

            val exported = logger.exportCombinedLogText(
                deviceHeader = "Device: TestPhone",
                maskSubscriberIdentifiers = true
            )
            assertTrue(exported.contains("Device: TestPhone"))
            assertFalse(exported.contains("Passwd2@"))
            assertFalse(exported.contains("Passwd2%40", ignoreCase = true))
            assertTrue("BSSID middle octets must be masked on export: $exported", exported.contains("AA:BB:CC:**:**:D8"))
            assertFalse("Raw PPPoE account must be middle-masked on export: $exported", exported.contains("manh9653_st"))
            assertFalse("Raw SLID must be middle-masked on export: $exported", exported.contains("005501941"))
            assertTrue("Admin token preserved in exported log: $exported", exported.contains("user=admin"))

            logger.clearLogs()
            assertTrue(logger.entriesFlow.value.isEmpty())
            assertFalse(prevFile.exists())
        } finally {
            logger.close()
            tempLogDir.deleteRecursively()
        }
    }
}
