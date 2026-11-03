package vn.vnpt.ontconfig

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import vn.vnpt.ontconfig.core.driver.OntAccountLockoutWarning
import vn.vnpt.ontconfig.core.driver.OntDriverException
import vn.vnpt.ontconfig.core.driver.OntMobileDriver
import vn.vnpt.ontconfig.core.driver.OntNetworkError
import vn.vnpt.ontconfig.core.driver.StepProgress
import vn.vnpt.ontconfig.core.wifi.OntWifiConnector
import vn.vnpt.ontconfig.core.wifi.WifiBand
import java.io.File
import vn.vnpt.ontconfig.core.wifi.WifiScanCandidate
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class OntMobileDriverTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    private val sampleRecord = SubscriberRecord(
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

    @Before
    fun setUp() {
        OntMobileDriver.clearCachedPasswordsForTest()
        server = MockWebServer()
        server.start()
        baseUrl = server.url("").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `OntWifiConnector filters factory default GW040-NSv2 SSIDs and verifies BSSID hex suffix`() {
        assertTrue(OntWifiConnector.isDefaultOntSsid("GW040-NSv2_2.4G_A1B2C3", "00:11:22:A1:B2:C3"))
        assertTrue(OntWifiConnector.isDefaultOntSsid("GW040-NSv2_5G_A1B2C3", "00:11:22:A1:B2:C4")) // 5G virtual BSSID offset
        assertFalse(OntWifiConnector.isDefaultOntSsid("GW040-NSv2_2.4G_A1B2C3", "00:11:22:FF:EE:DD")) // Mismatched BSSID
        assertFalse(OntWifiConnector.isDefaultOntSsid("VNPT_Home_WiFi", "00:11:22:A1:B2:C3"))

        val candidates = listOf(
            WifiScanCandidate("VNPT_Cust", "00:11:22:33:44:55", -40, 2412, false),
            WifiScanCandidate("GW040-NSv2_2.4G_112233", "AA:BB:CC:11:22:33", -65, 2437, true),
            WifiScanCandidate("GW040-NSv2_5G_AABBCC", "AA:BB:CC:AA:BB:CC", -50, 5180, true)
        )
        val ranked = OntWifiConnector.filterAndRankOntNetworks(candidates)
        assertEquals(2, ranked.size)
        assertEquals("GW040-NSv2_5G_AABBCC", ranked[0].ssid)
        assertEquals("GW040-NSv2_2.4G_112233", ranked[1].ssid)
    }

    @Test
    fun `executeMobileSetup on 2_4GHz configures 5GHz in Step 6 and 2_4GHz LAST in Step 7 with serviceId=1 and calls logout`() = runBlocking {
        val recordedPaths = CopyOnWriteArrayList<Pair<String, String>>()
        val unbindCalled = AtomicBoolean(false)
        val probeMutex = Mutex()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                recordedPaths.add(path to body)

                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html><title>VNPT iGate GW040-NSv2</title></html>")
                    "/cgi-bin/luci/login.cmd" -> {
                        if (body.contains("pass=admin")) {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"1","numberLogin":"0","timeoutLogin":"0"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_999","numberLogin":"0","timeoutLogin":"0"}""")
                        }
                    }
                    "/cgi-bin/luci/accesscontrolcfg.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody(
                        // [Red Team RT-07] Realistic wancfg.json with idx=2 and no serviceId field
                        """{"wanCfg":[{"idx":2,"vlanId":"11","ifcStr":"veip0.11","protocal":"PPPoE"}]}"""
                    )
                    "/cgi-bin/luci/editwan.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/sendwan.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/gponpasswd.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/savelanv4cfg.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/wlrestart.json" -> MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                    "/cgi-bin/luci/logout.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            probePauseMutex = probeMutex,
            onUnbindNetwork = { unbindCalled.set(true) },
            onVerifyNewSsid = { _, _ -> true }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ,
            bssid = "AA:BB:CC:11:22:33"
        )

        assertTrue(result.success)
        assertTrue(unbindCalled.get())
        assertFalse("Probe mutex must be unlocked in finally", probeMutex.isLocked)

        // [Red Team RT-07] Verify editwan.json and sendwan.cmd sent serviceId=1 (not idx=2)
        val editWanBody = recordedPaths.first { it.first == "/cgi-bin/luci/editwan.json" }.second
        val sendWanBody = recordedPaths.first { it.first == "/cgi-bin/luci/sendwan.cmd" }.second
        assertTrue("Expected serviceId=1 in editwan.json but got: $editWanBody", editWanBody.contains("serviceId=1"))
        assertTrue("Expected serviceId=1 in sendwan.cmd but got: $sendWanBody", sendWanBody.contains("serviceId=1"))

        // [Red Team RT-02] Verify Dynamic Band Ordering: when connected on 2.4GHz,
        // Step 6 configures wl_unit=1 (5GHz) first, and Step 7 configures wl_unit=0 (2.4GHz) LAST!
        val wirelessCalls = recordedPaths.filter { it.first == "/cgi-bin/luci/wirelesscfg.json" }
        assertEquals(2, wirelessCalls.size)
        assertTrue("Step 6 must configure 5GHz (wl_unit=1) first", wirelessCalls[0].second.contains("wl_unit=1"))
        assertTrue("Step 7 must configure 2.4GHz (wl_unit=0) LAST", wirelessCalls[1].second.contains("wl_unit=0"))

        // [Red Team RT-01] Verify logout.cmd was called in finally
        assertEquals("/cgi-bin/luci/logout.cmd", recordedPaths.last().first)
    }

    @Test
    fun `executeMobileSetup on 5GHz reverses band order so 2_4GHz is Step 6 and 5GHz is Step 7 LAST`() = runBlocking {
        val wirelessBodies = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (path == "/cgi-bin/luci/wirelesscfg.json") {
                    wirelessBodies.add(body)
                }
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>iGate GW040</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_5g"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onVerifyNewSsid = { _, _ -> true }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_5_GHZ
        )

        assertTrue(result.success)
        assertEquals(2, wirelessBodies.size)
        assertTrue("When connected on 5GHz, Step 6 must configure wl_unit=0 (2.4G) first", wirelessBodies[0].contains("wl_unit=0"))
        assertTrue("When connected on 5GHz, Step 7 must configure wl_unit=1 (5G) LAST", wirelessBodies[1].contains("wl_unit=1"))
    }

    @Test
    fun `Step 7 Commit Ambiguity handles socket disconnect during radio reload by verifying new SSID beacon`() = runBlocking {
        var wirelessCallCount = 0
        val step7Dropped = AtomicBoolean(false)
        val verifyCalled = AtomicBoolean(false)
        val unbindCalled = AtomicBoolean(false)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (step7Dropped.get()) {
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                }
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_drop"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        request.body.readUtf8()
                        wirelessCallCount++
                        if (wirelessCallCount == 2) {
                            // Step 7: simulate ONT 2.4GHz radio dropping socket immediately upon receiving Apply
                            step7Dropped.set(true)
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onUnbindNetwork = { unbindCalled.set(true) },
            onVerifyNewSsid = { ssid24, _ ->
                verifyCalled.set(ssid24 == "Manh Ngoc")
                true
            }
        )
        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ
        )

        assertTrue("Expected SUCCESS when Step 7 socket drops during radio restart and beacon is verified", result.success)
        assertTrue(verifyCalled.get())
        assertTrue(unbindCalled.get())
    }

    @Test
    fun `mid-flight error at Step 4 still invokes logout cmd in finally block to prevent user exist lockout`() = runBlocking {
        val logoutCalled = AtomicBoolean(false)
        val unbindCalled = AtomicBoolean(false)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_err"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/gponpasswd.json" -> MockResponse().setResponseCode(500).setBody("Internal Error")
                    "/cgi-bin/luci/logout.cmd" -> {
                        logoutCalled.set(true)
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onUnbindNetwork = { unbindCalled.set(true) }
        )

        try {
            driver.executeMobileSetup(
                record = sampleRecord,
                settings = GlobalSettings(baseUrl = baseUrl)
            )
            fail("Expected exception at Step 4")
        } catch (_: Exception) {
        }

        assertTrue("Mandatory finally { logout.cmd } must execute on mid-flight failure", logoutCalled.get())
        assertTrue("Mandatory unbind must execute on mid-flight failure", unbindCalled.get())
    }

    @Test
    fun `Smart Retry prioritizes Passwd2@ when BSSID already changed password and stops at numberLogin 2`() = runBlocking {
        val loginAttempts = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>GW040</html>")
                    "/cgi-bin/luci/login.cmd" -> {
                        loginAttempts.add(body)
                        if (body.contains("pass=Passwd2%40") || body.contains("pass=Passwd2@")) {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_retry","numberLogin":"0"}""")
                        } else {
                            // If admin/admin were tried first on an already-changed ONT with numberLogin=1, it would hit numberLogin=2!
                            MockResponse().setResponseCode(200).setBody("""{"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}""")
                        }
                    }
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onVerifyNewSsid = { _, _ -> true }
        )

        // Record carries lastKnownAdminPass = "Passwd2@" from a prior interrupted attempt
        val retryRecord = sampleRecord.copy(lastKnownAdminPass = "Passwd2@")
        val result = driver.executeMobileSetup(
            record = retryRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            bssid = "AA:BB:CC:DD:EE:FF"
        )

        assertTrue(result.success)
        assertEquals(1, loginAttempts.size)
        assertTrue("Expected first login attempt on retry to use Passwd2@", loginAttempts[0].contains("Passwd2"))

        // Also verify numberLogin >= 2 raises OntAccountLockoutWarning immediately
        loginAttempts.clear()
        val freshRecord = sampleRecord.copy(lastKnownAdminPass = "")
        try {
            driver.executeMobileSetup(
                record = freshRecord,
                settings = GlobalSettings(baseUrl = baseUrl),
                bssid = "11:22:33:44:55:66"
            )
            fail("Expected OntAccountLockoutWarning when numberLogin >= 2")
        } catch (e: OntAccountLockoutWarning) {
            assertTrue(e.message!!.contains("2"))
        }
    }

    @Test
    fun `Step 1 rejects gateway response that lacks ONT HTML signature`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setBody("<html><body>Captive Portal Not ONT</body></html>")
            }
        }

        val driver = OntMobileDriver(baseUrl = baseUrl)
        try {
            driver.executeMobileSetup(sampleRecord, GlobalSettings(baseUrl = baseUrl))
            fail("Expected OntNetworkError when gateway HTML lacks ONT signature")
        } catch (e: OntNetworkError) {
            assertTrue(e.message!!.contains("GW040") || e.message!!.contains("ONT"))
        }
    }

    @Test
    fun `Step 6 and Step 7 each run wlrestart and Step 7 retries after transient Step 6 radio bounce`() = runBlocking {
        val requestOrder = CopyOnWriteArrayList<String>()
        var step7WirelessAttempts = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (path == "/cgi-bin/luci/wirelesscfg.json") {
                    val unit = if (body.contains("wl_unit=1")) "1" else "0"
                    requestOrder.add("wirelesscfg:unit=$unit")
                    if (unit == "0") {
                        step7WirelessAttempts++
                        if (step7WirelessAttempts == 1) {
                            // Simulate transient socket drop on first Step 7 attempt while radio settles from Step 6
                            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                    }
                } else if (path == "/cgi-bin/luci/wlrestart.json") {
                    requestOrder.add("wlrestart")
                    if (step7WirelessAttempts >= 2) {
                        // Simulate radio restarting and dropping socket when wlrestart.json is called at Step 7
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    }
                }
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_ok"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onVerifyNewSsid = { _, _ -> false },
            onRebindWifiNetwork = { null }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ
        )

        assertTrue("Setup must succeed after Step 7 retries and saves to NVRAM even if beacon scan is throttled", result.success)
        assertEquals(2, step7WirelessAttempts)
        assertTrue(requestOrder.contains("wirelesscfg:unit=1"))
        assertTrue(requestOrder.contains("wirelesscfg:unit=0"))
    }

    @Test
    fun `OntWifiConnector detectBandFromFrequency uses SSID hint and configured subscriber SSIDs when frequency is unavailable on Android 10+`() {
        assertEquals(WifiBand.BAND_5_GHZ, OntWifiConnector.detectBandFromFrequency(-1, "GW040-NSv2_5G_60BCD8"))
        assertEquals(WifiBand.BAND_2_4_GHZ, OntWifiConnector.detectBandFromFrequency(-1, "GW040-NSv2_2.4G_60BCD8"))
        assertEquals(WifiBand.BAND_5_GHZ, OntWifiConnector.detectBandFromFrequency(5180, null))
        assertEquals(WifiBand.BAND_2_4_GHZ, OntWifiConnector.detectBandFromFrequency(2412, null))

        // [Red Team RT-07] Custom 5GHz SSID without "5G" suffix must still be recognized as BAND_5_GHZ when frequency == -1
        assertEquals(
            WifiBand.BAND_5_GHZ,
            OntWifiConnector.detectBandFromFrequency(
                frequencyMhz = -1,
                ssidHint = "Manh Ngoc Fast",
                configured24Ssid = "Manh Ngoc",
                configured5Ssid = "Manh Ngoc Fast"
            )
        )
    }

    @Test
    fun `OntWifiConnector isSameOntBssid matches exact and virtual-offset ONT BSSIDs for instant Wi-Fi binding`() {
        assertTrue(OntWifiConnector.isSameOntBssid("a4:f4:c2:60:bc:d8", "A4:F4:C2:60:BC:D8"))
        assertTrue(OntWifiConnector.isSameOntBssid("A4:F4:C2:60:BC:D8", "A4:F4:C2:60:BC:D9"))
        assertFalse(OntWifiConnector.isSameOntBssid("A4:F4:C2:60:BC:D8", "00:11:22:33:44:55"))
    }

    @Test
    fun `Step 7 succeeds when renaming connected Wi-Fi immediately drops connection and old SSID disappears even if beacon scan is throttled`() = runBlocking {
        val wirelessPayloads = CopyOnWriteArrayList<String>()
        val step7Dropped = AtomicBoolean(false)
        val unbindCalled = AtomicBoolean(false)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (step7Dropped.get()) {
                    // Once Step 7 renamed the connected Wi-Fi band, the old SSID is gone so gateway is unreachable
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                }
                if (path == "/cgi-bin/luci/wirelesscfg.json") {
                    wirelessPayloads.add(body)
                    if (body.contains("wl_unit=0")) {
                        // Connected 2.4GHz band in Step 7: ONT receives Apply, renames SSID, and drops Wi-Fi before HTTP 200 returns
                        step7Dropped.set(true)
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                }
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_real"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onUnbindNetwork = { unbindCalled.set(true) },
            onVerifyNewSsid = { _, _ -> false }, // Simulate Android OS scan throttle / Location off
            onRebindWifiNetwork = { null }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ
        )

        assertTrue("Step 7 must succeed when connected Wi-Fi drops upon saving new SSID/Pass", result.success)
        assertTrue(unbindCalled.get())
        assertEquals(2, wirelessPayloads.size)
        assertTrue(wirelessPayloads[0].contains("wl_unit=1") && wirelessPayloads[0].contains("wl_ssid=Manh%20Ngoc%205G"))
        assertTrue(wirelessPayloads[1].contains("wl_unit=0") && wirelessPayloads[1].contains("wl_ssid=Manh%20Ngoc"))
    }

    @Test
    fun `Step 6 keeps current Wi-Fi session alive without logout and Step 7 saves connected band last before final logout and unbind`() = runBlocking {
        val eventLog = CopyOnWriteArrayList<String>()
        val verifyCalled = AtomicBoolean(false)
        var loginCount = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                return when (path) {
                    "/" -> {
                        eventLog.add("probe")
                        MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    }
                    "/cgi-bin/luci/login.cmd" -> {
                        loginCount++
                        val sess = "sess_$loginCount"
                        eventLog.add("login:$sess")
                        MockResponse().setResponseCode(200).setBody(
                            """{"status":"OK","firstlog":"0","sessionKey":"$sess","numberLogin":"0","timeoutLogin":"0"}"""
                        )
                    }
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        val unit = if (body.contains("wl_unit=1")) "1" else "0"
                        val usedSess = Regex("sessionKey=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                        eventLog.add("wirelesscfg:unit=$unit:sess=$usedSess")
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    "/cgi-bin/luci/wlrestart.json" -> {
                        eventLog.add("wlrestart")
                        MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                    }
                    "/cgi-bin/luci/logout.cmd" -> {
                        val outSess = Regex("sessionKey=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                        eventLog.add("logout:$outSess")
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            onUnbindNetwork = { eventLog.add("unbind") },
            onVerifyNewSsid = { _, _ ->
                verifyCalled.set(true)
                true
            }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ,
            bssid = "AA:BB:CC:11:22:33"
        )

        assertTrue(result.success)
        assertTrue(result.beaconVerified)
        assertFalse("onVerifyNewSsid must be skipped when Step 7 wirelesscfg.json returned HTTP 200 OK", verifyCalled.get())
        assertEquals("Must authenticate only ONCE across Steps 1..7 when session stays valid", 1, loginCount)

        val step6CfgIdx = eventLog.indexOf("wirelesscfg:unit=1:sess=sess_1")
        val step7CfgIdx = eventLog.indexOf("wirelesscfg:unit=0:sess=sess_1")
        val logoutIndices = eventLog.mapIndexedNotNull { idx, ev -> if (ev.startsWith("logout:")) idx else null }
        val unbindIdx = eventLog.indexOf("unbind")

        assertTrue("Step 6 must configure secondary band unit=1 on sess_1", step6CfgIdx >= 0)
        assertTrue("Step 7 must configure connected band unit=0 on the SAME sess_1", step7CfgIdx > step6CfgIdx)
        assertEquals("Must only logout once at the end of Step 7 (never between Step 6 and Step 7)", 1, logoutIndices.size)
        assertTrue("Final logout must occur AFTER Step 7 NVRAM save", logoutIndices.single() > step7CfgIdx)
        assertTrue("Unbind must occur AFTER Step 7 logout", unbindIdx > logoutIndices.single())
        val betweenStep6And7 = eventLog.subList(step6CfgIdx + 1, step7CfgIdx)
        assertTrue("ensureGatewayReadyBeforeStep7 must probe gateway before Step 7", betweenStep6And7.contains("probe"))
        assertTrue("ensureGatewayReadyBeforeStep7 must verify wlrestart.json before Step 7", betweenStep6And7.count { it == "wlrestart" } >= 2)
    }

    @Test
    fun `resolveConnectedBandFromOnt overrides misdetected 2_4GHz client band using real 2D wirelessInfoList and wlAssociatedDevice schema`() = runBlocking {
        val callSequence = CopyOnWriteArrayList<String>()
        val resolvedCallbacks = CopyOnWriteArrayList<Pair<WifiBand, String?>>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                callSequence.add(path)
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"OK","firstlog":"0","sessionKey":"sess_band","numberLogin":"0","timeoutLogin":"0"}"""
                    )
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(
                        """{"dhcpInfoList":["192.168.1.2|DHCP||aa:bb:cc:dd:ee:ff|phone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.2|1|wl1.1|-45"]}"""
                    )
                    "/cgi-bin/luci/deviceinfo2.json" -> MockResponse().setResponseCode(200).setBody(
                        """{"wirelessInfoList":[["wl0|1|100|10\n|GW040-NSv2_2.4G_60BCD8\n|c4:2c:7b:60:bc:d9\n","GW040-NSv2_2.4G_60BCD8\n|wl0"],["wl1|1|100|120\n|GW040-NSv2_5G_60BCD8\n|c4:2c:7b:60:bc:da\n","GW040-NSv2_5G_60BCD8\n|wl1"]],"baseMacAddress":"C4:2C:7B:60:BC:D8\n"}"""
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        val unit = if (body.contains("wl_unit=1")) "1" else "0"
                        callSequence.add("wirelesscfg:unit=$unit")
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ, // Misdetected as 2.4GHz when GPS is off
            bssid = null,
            localWifiIpv4 = "192.168.1.2",
            onBandResolved = { band, baseMac ->
                resolvedCallbacks.add(band to baseMac)
            }
        )

        assertTrue(result.success)
        assertEquals(WifiBand.BAND_5_GHZ, result.resolvedConnectedBand)
        assertEquals(listOf(WifiBand.BAND_5_GHZ to "C4:2C:7B:60:BC:D8"), resolvedCallbacks)

        // Verify band resolution ran immediately after Step 2 (BEFORE Step 3 wancfg.json & Step 5 savelanv4cfg.cmd)
        val loginIdx = callSequence.indexOf("/cgi-bin/luci/login.cmd")
        val dhcpIdx = callSequence.indexOf("/cgi-bin/luci/dhcpclient.json")
        val devInfoIdx = callSequence.indexOf("/cgi-bin/luci/deviceinfo2.json")
        val wanIdx = callSequence.indexOf("/cgi-bin/luci/wancfg.json")
        val lanIdx = callSequence.indexOf("/cgi-bin/luci/savelanv4cfg.cmd")
        assertTrue(dhcpIdx > loginIdx)
        assertTrue(devInfoIdx > dhcpIdx)
        assertTrue("resolveConnectedBandFromOnt must run before Step 3/5 dnsmasq reload", wanIdx > devInfoIdx && lanIdx > devInfoIdx)

        // Verify wl1 (5GHz) was prioritized over stale dhcpInfoList wl0 so Step 6 configures unit=0 and Step 7 configures unit=1 LAST
        val wirelessUnits = callSequence.filter { it.startsWith("wirelesscfg:unit=") }
        assertEquals(listOf("wirelesscfg:unit=0", "wirelesscfg:unit=1"), wirelessUnits)
    }

    @Test
    fun `ensureGatewayReadyBeforeStep7 recovers from fail authen and user exist using dynamic lastActiveSessionKey without double login`() = runBlocking {
        var wlRestartCalls = 0
        var reloginCalls = 0
        val releasedSessions = CopyOnWriteArrayList<String>()
        val step7SessionKeys = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> {
                        if (wlRestartCalls == 0) {
                            MockResponse().setResponseCode(200).setBody(
                                """{"status":"OK","firstlog":"0","sessionKey":"sess_initial","numberLogin":"0","timeoutLogin":"0"}"""
                            )
                        } else {
                            reloginCalls++
                            if (reloginCalls == 1 && !releasedSessions.contains("sess_initial")) {
                                MockResponse().setResponseCode(200).setBody("""{"status":"user exist"}""")
                            } else {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"status":"OK","firstlog":"0","sessionKey":"sess_recovered","numberLogin":"0","timeoutLogin":"0"}"""
                                )
                            }
                        }
                    }
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody(
                        // Include placeholder sessionKey="0" to verify RT-05 ignores "0"
                        """{"wanCfg":[],"sessionKey":"0"}"""
                    )
                    "/cgi-bin/luci/wlrestart.json" -> {
                        wlRestartCalls++
                        if (wlRestartCalls == 2) {
                            // Gate before Step 7 receives LuCI JSON expiry {"status":"fail authen"}
                            MockResponse().setResponseCode(200).setBody("""{"status":"fail authen"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                        }
                    }
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        if (body.contains("wl_unit=0")) {
                            val usedSess = Regex("sessionKey=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                            step7SessionKeys.add(usedSess)
                        }
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    "/cgi-bin/luci/logout.cmd" -> {
                        val outSess = Regex("sessionKey=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                        releasedSessions.add(outSess)
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
            settings = GlobalSettings(baseUrl = baseUrl),
            connectedBand = WifiBand.BAND_2_4_GHZ
        )

        assertTrue(result.success)
        assertTrue("Must release dynamic lastActiveSessionKey 'sess_initial' on user exist", releasedSessions.contains("sess_initial"))
        assertEquals(2, reloginCalls)
        assertEquals(listOf("sess_recovered"), step7SessionKeys)

        // [Red Team RT-01] Verify no double-login if reloginAfterRadioBounce hits numberLogin == 2
        var lockoutReloginCalls = 0
        var gateTriggered = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> {
                        if (!gateTriggered) {
                            MockResponse().setResponseCode(200).setBody(
                                """{"status":"OK","firstlog":"0","sessionKey":"sess_lock","numberLogin":"0","timeoutLogin":"0"}"""
                            )
                        } else {
                            lockoutReloginCalls++
                            MockResponse().setResponseCode(200).setBody(
                                """{"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}"""
                            )
                        }
                    }
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wlrestart.json" -> {
                        if (!gateTriggered) {
                            gateTriggered = true
                            MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"status":"fail authen"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                }
            }
        }

        try {
            driver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = GlobalSettings(baseUrl = baseUrl),
                connectedBand = WifiBand.BAND_2_4_GHZ
            )
            fail("Expected OntAccountLockoutWarning on numberLogin == 2")
        } catch (_: OntAccountLockoutWarning) {
        }
        assertEquals("Must NEVER double-call reloginAfterRadioBounce when numberLogin == 2", 1, lockoutReloginCalls)
    }

    @Test
    fun `Step 7 retries all 3 attempts and throws OntNetworkError without false success when old gateway stays online or pre-flush fails`() = runBlocking {
        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L
        )

        // Direct unit verification of FlushTrackedIOException unwrap & byte-flush guard ([Red Team RT-04])
        assertFalse(
            "Unflushed SocketTimeoutException must NOT be treated as post-flush radio drop",
            driver.isPostFlushRadioDrop(
                OntMobileDriver.FlushTrackedIOException(java.net.SocketTimeoutException("timeout"), allBytesFlushed = false)
            )
        )
        assertTrue(
            "Flushed SocketTimeoutException (read timeout) IS a post-flush radio drop",
            driver.isPostFlushRadioDrop(
                OntMobileDriver.FlushTrackedIOException(java.net.SocketTimeoutException("Read timed out"), allBytesFlushed = true)
            )
        )
        assertTrue(
            "Wrapped ConnectException must still be recognized as pre-send connect failure",
            driver.isPreSendConnectFailure(
                OntMobileDriver.FlushTrackedIOException(java.net.ConnectException("Connection refused"), allBytesFlushed = false)
            )
        )

        var unit0Attempts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (path == "/cgi-bin/luci/wirelesscfg.json" && body.contains("wl_unit=0")) {
                    unit0Attempts++
                    // Drop before HTTP 200 on all 3 attempts while old gateway remains online!
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"OK","firstlog":"0","sessionKey":"sess_ok","numberLogin":"0","timeoutLogin":"0"}"""
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        try {
            driver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = GlobalSettings(baseUrl = baseUrl),
                connectedBand = WifiBand.BAND_2_4_GHZ
            )
            fail("Expected OntNetworkError when Step 7 drops on all 3 attempts while old Wi-Fi stays online")
        } catch (_: OntNetworkError) {
        }
        assertEquals("Must try all 3 Step 7 attempts before failing", 3, unit0Attempts)

        // Also verify executeManualConnectedWifiOnly rejects false success when old Wi-Fi stays online
        try {
            driver.executeManualConnectedWifiOnly(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = GlobalSettings(baseUrl = baseUrl),
                targetUnit = 0,
                targetBandLabel = "2.4GHz",
                targetSsid = "Manh Ngoc",
                targetPass = "28121995"
            )
            fail("Expected OntNetworkError when manual Step 8 drops while old Wi-Fi stays online")
        } catch (_: OntNetworkError) {
        }
    }
    private fun createTempFirmwareFile(sizeBytes: Int = 80 * 1024): File {
        val file = File.createTempFile("fw_test_GW040_", ".signed")
        val payload = ByteArray(sizeBytes) { idx ->
            when (idx % 4) {
                0 -> 0x7f.toByte()
                1 -> 0xff.toByte()
                2 -> 0x00.toByte()
                else -> 0x42.toByte()
            }
        }
        file.writeBytes(payload)
        return file
    }

    @Test
    fun `executeMobileSetup with enableFirmwareUpdate=true uploads firmware at Step 7 before connected Wi-Fi step and skips automatic connected Wi-Fi rename`() = runBlocking {
        val fwFile = createTempFirmwareFile(72 * 1024)
        try {
            val recordedRequests = CopyOnWriteArrayList<Triple<String, String, ByteArray>>()
            val unbindCalled = AtomicBoolean(false)
            val progressUpdates = CopyOnWriteArrayList<StepProgress>()
            var wlRestartCount = 0
            var ensureRelogAttempts = 0

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val contentType = request.getHeader("Content-Type").orEmpty()
                    val referer = request.getHeader("Referer").orEmpty()
                    val rawBytes = request.body.readByteArray()
                    val bodyStr = String(rawBytes, Charsets.ISO_8859_1)
                    recordedRequests.add(Triple(path, "$contentType|$referer", rawBytes))

                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> {
                            if (wlRestartCount == 0) {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"status":"OK","firstlog":"0","sessionKey":"sess_initial","numberLogin":"0","timeoutLogin":"0"}"""
                                )
                            } else {
                                // [Red Team RT-02] Simulate user exist on first re-login after Step 6 radio restart, then succeed after old session logout
                                ensureRelogAttempts++
                                if (ensureRelogAttempts == 1) {
                                    MockResponse().setResponseCode(200).setBody("""{"status":"user exist"}""")
                                } else {
                                    MockResponse().setResponseCode(200).setBody(
                                        """{"status":"OK","firstlog":"0","sessionKey":"sess_after_step6","numberLogin":"0","timeoutLogin":"0"}"""
                                    )
                                }
                            }
                        }
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/wlrestart.json" -> {
                            wlRestartCount++
                            if (wlRestartCount == 2) {
                                // [Red Team RT-02] Simulate Step 6 wlrestart expiring session (returning HTML login page)
                                MockResponse().setResponseCode(200).setBody("<html>login.js</html>")
                            } else {
                                MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                            }
                        }
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> {
                            assertTrue("Expected sessionKey=sess_after_step6 in updatefwsessioncheck", bodyStr.contains("sessionKey=sess_after_step6"))
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","sessionKey":"sess_fw_chk"}""")
                        }
                        "/cgi-bin/luci/imagesize.json" -> {
                            assertTrue("Expected rotated sessionKey=sess_fw_chk in imagesize", bodyStr.contains("sessionKey=sess_fw_chk"))
                            MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696","sessionKey":"sess_fw_img"}""")
                        }
                        "/cgi-bin/luci/uploadsoftware.conf" -> {
                            MockResponse().setResponseCode(200).setBody("""{"status":"valid","sessionKey":"sess_fw_done"}""")
                        }
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                }
            }

            val driver = OntMobileDriver(
                baseUrl = baseUrl,
                settlingDelayMs = 10L,
                recoveryRetryDelayMs = 20L,
                onUnbindNetwork = { unbindCalled.set(true) }
            )

            val settings = GlobalSettings(
                baseUrl = baseUrl,
                enableFirmwareUpdate = true,
                firmwareFilePath = fwFile.absolutePath,
                firmwareFileName = fwFile.name,
                firmwareFileSize = fwFile.length()
            )

            val result = driver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = settings,
                connectedBand = WifiBand.BAND_2_4_GHZ,
                bssid = "AA:BB:CC:11:22:33",
                onProgress = { progressUpdates.add(it) }
            )

            assertTrue(result.success)
            assertTrue(result.firmwareUpdated)
            assertEquals(fwFile.name, result.firmwareFileName)
            assertTrue("Manual Wi-Fi step must be pending when firmware is updated", result.manualWifiStepPending)
            assertEquals(0, result.manualWifiUnit)
            assertEquals("2.4GHz", result.manualWifiBandLabel)
            assertEquals("Manh Ngoc", result.manualWifiSsid)
            assertEquals("28121995", result.manualWifiPass)
            assertEquals("5GHz", result.configuredSecondaryBandLabel)
            assertEquals("Manh Ngoc 5G", result.configuredSecondarySsid)
            assertTrue(unbindCalled.get())
            // Verify only secondary Wi-Fi (wl_unit=1) was configured in Step 6, NOT connected Wi-Fi (wl_unit=0)
            val wirelessRequests = recordedRequests.filter { it.first == "/cgi-bin/luci/wirelesscfg.json" }
            assertEquals(1, wirelessRequests.size)
            assertTrue(String(wirelessRequests[0].third, Charsets.UTF_8).contains("wl_unit=1"))

            // Verify 3-step firmware sequence order
            val paths = recordedRequests.map { it.first }
            val chkIdx = paths.indexOf("/cgi-bin/luci/updatefwsessioncheck.json")
            val imgIdx = paths.indexOf("/cgi-bin/luci/imagesize.json")
            val upIdx = paths.indexOf("/cgi-bin/luci/uploadsoftware.conf")
            assertTrue(chkIdx > 0)
            assertTrue(imgIdx > chkIdx)
            assertTrue(upIdx > imgIdx)

            // Verify multipart upload headers and binary content (0x7f, 0xff) + rotated sessionKey
            val uploadReq = recordedRequests[upIdx]
            assertTrue(uploadReq.second.contains("multipart/form-data; boundary="))
            assertTrue(uploadReq.second.endsWith("$baseUrl/update_software.html"))
            val uploadBodyIso = String(uploadReq.third, Charsets.ISO_8859_1)
            assertTrue(uploadBodyIso.contains("name=\"file\"; filename=\"${fwFile.name}\""))
            assertTrue(uploadBodyIso.contains("name=\"sessionKey\""))
            assertTrue(uploadBodyIso.contains("sess_fw_img"))
            assertTrue("Binary byte 0x7f must be preserved in multipart body", uploadReq.third.contains(0x7f.toByte()))
            assertTrue("Binary byte 0xff must be preserved in multipart body", uploadReq.third.contains(0xff.toByte()))

            // [Red Team RT-01 / RT-03] Crucial: NO logout.cmd after uploadsoftware.conf returns status=valid!
            val postUploadRequests = recordedRequests.drop(upIdx + 1)
            assertFalse(
                "logout.cmd must NEVER be sent after uploadsoftware.conf returns status=valid",
                postUploadRequests.any { it.first == "/cgi-bin/luci/logout.cmd" }
            )

            // Verify all progress updates used totalSteps = 8
            assertTrue(progressUpdates.isNotEmpty() && progressUpdates.all { it.totalSteps == 8 })
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `updateFirmware fails when ONT ramFree is insufficient or zero and still calls logout in finally`() = runBlocking {
        val fwFile = createTempFirmwareFile(80 * 1024) // 80 KB
        try {
            val logoutPayloads = CopyOnWriteArrayList<String>()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val body = request.body.readUtf8()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                            """{"status":"OK","firstlog":"0","sessionKey":"sess_start","numberLogin":"0","timeoutLogin":"0"}"""
                        )
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody(
                            """{"status":"OK","sessionKey":"sess_chk_rot"}"""
                        )
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody(
                            """{"ramFree":"20","sessionKey":"sess_img_rot"}""" // Only 20 KB free < 80 KB file
                        )
                        "/cgi-bin/luci/logout.cmd" -> {
                            logoutPayloads.add(body)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 10L, recoveryRetryDelayMs = 20L)
            val settings = GlobalSettings(
                baseUrl = baseUrl,
                enableFirmwareUpdate = true,
                firmwareFilePath = fwFile.absolutePath,
                firmwareFileName = fwFile.name,
                firmwareFileSize = fwFile.length()
            )

            try {
                driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = settings
                )
                fail("Expected OntDriverException when ramFree is insufficient")
            } catch (e: OntDriverException) {
                assertTrue(e.message!!.contains("Đã đổ xong cấu hình (Bước 1-6"))
                assertTrue(e.message!!.contains("RAM trống"))
            }

            assertEquals(1, logoutPayloads.size)
            assertTrue("Must logout rotated sessionKey on RAM failure", logoutPayloads[0].contains("sessionKey=sess_img_rot"))
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `updateFirmware fails when uploadsoftware returns invalid status and rotates sessionKey for clean logout`() = runBlocking {
        val fwFile = createTempFirmwareFile(70 * 1024)
        try {
            val logoutPayloads = CopyOnWriteArrayList<String>()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val body = request.body.readUtf8()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                            """{"status":"OK","firstlog":"0","sessionKey":"sess_1","numberLogin":"0","timeoutLogin":"0"}"""
                        )
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696"}""")
                        "/cgi-bin/luci/uploadsoftware.conf" -> MockResponse().setResponseCode(200).setBody(
                            """{"status":"invalid","sessionKey":"sess_rot_99"}"""
                        )
                        "/cgi-bin/luci/logout.cmd" -> {
                            logoutPayloads.add(body)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 10L, recoveryRetryDelayMs = 20L)
            val settings = GlobalSettings(
                baseUrl = baseUrl,
                enableFirmwareUpdate = true,
                firmwareFilePath = fwFile.absolutePath,
                firmwareFileName = fwFile.name,
                firmwareFileSize = fwFile.length()
            )

            try {
                driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = settings
                )
                fail("Expected OntDriverException when uploadsoftware returns status=invalid")
            } catch (e: OntDriverException) {
                assertTrue(e.message!!.contains("status=invalid"))
            }

            assertEquals(1, logoutPayloads.size)
            assertTrue("Must logout with rotated sessionKey sess_rot_99", logoutPayloads[0].contains("sessionKey=sess_rot_99"))
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `executeManualConnectedWifiOnly probes gateway, logs in, and renames the remaining connected Wi-Fi band with radio drop tolerance after ONT reboot`() = runBlocking {
        val recordedPaths = CopyOnWriteArrayList<Pair<String, String>>()
        val unbindCalled = AtomicBoolean(false)
        val probeMutex = Mutex()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                recordedPaths.add(path to body)
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"OK","firstlog":"0","sessionKey":"sess_post_reboot","numberLogin":"0","timeoutLogin":"0"}"""
                    )
                    "/cgi-bin/luci/wirelesscfg.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    "/cgi-bin/luci/wlrestart.json" -> {
                        // Simulate radio restarting and dropping connection upon applying final Wi-Fi band
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                }
            }
        }

        val driver = OntMobileDriver(
            baseUrl = baseUrl,
            settlingDelayMs = 10L,
            recoveryRetryDelayMs = 20L,
            probePauseMutex = probeMutex,
            onUnbindNetwork = { unbindCalled.set(true) }
        )

        val result = driver.executeManualConnectedWifiOnly(
            record = sampleRecord,
            settings = GlobalSettings(baseUrl = baseUrl),
            targetUnit = 0,
            targetBandLabel = "2.4GHz",
            targetSsid = "Manh Ngoc",
            targetPass = "28121995",
            bssid = "AA:BB:CC:11:22:33"
        )

        assertTrue(result.success)
        assertFalse(result.manualWifiStepPending)
        assertTrue(unbindCalled.get())
        assertFalse(probeMutex.isLocked)
        assertEquals("/", recordedPaths[0].first)
        assertEquals("/cgi-bin/luci/login.cmd", recordedPaths[1].first)
        val wirelessCall = recordedPaths.first { it.first == "/cgi-bin/luci/wirelesscfg.json" }.second
        assertTrue(wirelessCall.contains("wl_unit=0"))
        assertTrue(wirelessCall.contains("wl_ssid=Manh%20Ngoc"))
    }

    @Test
    fun `updateFirmware clears sessionKey on post-flush socket drop and preserves OntNetworkError subtype`() = runBlocking {
        val fwFile = createTempFirmwareFile(68 * 1024)
        try {
            val logoutCalled = AtomicBoolean(false)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    request.body.readByteArray()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                            """{"status":"OK","firstlog":"0","sessionKey":"sess_flush","numberLogin":"0","timeoutLogin":"0"}"""
                        )
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696"}""")
                        "/cgi-bin/luci/uploadsoftware.conf" -> {
                            // [Red Team RT-03] Simulate socket dropping after 100% of multipart body is flushed
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        "/cgi-bin/luci/logout.cmd" -> {
                            logoutCalled.set(true)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 10L, recoveryRetryDelayMs = 20L)
            val settings = GlobalSettings(
                baseUrl = baseUrl,
                enableFirmwareUpdate = true,
                firmwareFilePath = fwFile.absolutePath,
                firmwareFileName = fwFile.name,
                firmwareFileSize = fwFile.length()
            )

            try {
                driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = settings
                )
                fail("Expected OntNetworkError when uploadsoftware drops socket before HTTP 200")
            } catch (e: OntNetworkError) {
                assertTrue(e.message!!.contains("Đã đổ xong cấu hình (Bước 1-6"))
            }

            assertFalse(
                "logout.cmd must NOT be called if socket dropped after 100% firmware bytes were flushed",
                logoutCalled.get()
            )
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `executeMobileSetup with enableFirmwareUpdate=true and alreadyConfiguredOppositeBand=true marks manualWifiStepPending false`() = runBlocking {
        val fwFile = createTempFirmwareFile(68 * 1024)
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    request.body.readByteArray()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                            """{"status":"OK","firstlog":"0","sessionKey":"sess_opp","numberLogin":"0","timeoutLogin":"0"}"""
                        )
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696"}""")
                        "/cgi-bin/luci/uploadsoftware.conf" -> MockResponse().setResponseCode(200).setBody("""{"status":"valid"}""")
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 10L, recoveryRetryDelayMs = 20L)
            val settings = GlobalSettings(
                baseUrl = baseUrl,
                enableFirmwareUpdate = true,
                firmwareFilePath = fwFile.absolutePath,
                firmwareFileName = fwFile.name,
                firmwareFileSize = fwFile.length()
            )

            val result = driver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = settings,
                connectedBand = WifiBand.BAND_5_GHZ,
                alreadyConfiguredOppositeBand = true
            )

            assertTrue(result.success)
            assertTrue(result.firmwareUpdated)
            assertFalse("manualWifiStepPending must be false when opposite band was already configured", result.manualWifiStepPending)
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `configureWifiBand sends exact sec_mode akm_psk akm_psk2 and wl_crypto for all 6 security modes`() = runBlocking {
        val wirelessBodies = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (path == "/cgi-bin/luci/wirelesscfg.json") {
                    wirelessBodies.add(body)
                }
                return MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
            }
        }

        val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 5L, recoveryRetryDelayMs = 20L)
        for (mode in WifiSecurityMode.entries) {
            wirelessBodies.clear()
            val pw = if (mode.requiresPassword) "28121995" else ""
            driver.configureWifiBand(
                unit = 0,
                ssid = "Manh Ngoc",
                password = pw,
                secMode = mode.formValue,
                isConnectedBandFinalStep = false
            )
            assertEquals(1, wirelessBodies.size)
            val body = wirelessBodies[0]
            assertTrue(body.contains("sec_mode=${mode.formValue}"))
            assertTrue(body.contains("wl_akm_psk=${mode.akmPsk}"))
            assertTrue(body.contains("wl_akm_psk2=${mode.akmPsk2}"))
            assertTrue(body.contains("wl_crypto=${mode.crypto}"))
        }
    }

    @Test
    fun `Step 7 conflict_mesh throws OntDriverException immediately without retrying 3 times`() = runBlocking {
        var step7Attempts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"OK","firstlog":"0","sessionKey":"sess_mesh","numberLogin":"0","timeoutLogin":"0"}"""
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        if (body.contains("wl_unit=0")) {
                            step7Attempts++
                            MockResponse().setResponseCode(200).setBody("""{"status":"conflict_mesh"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 5L, recoveryRetryDelayMs = 20L)
        try {
            driver.executeMobileSetup(
                record = sampleRecord.copy(pass24g = "", pass5g = ""),
                settings = GlobalSettings(baseUrl = baseUrl, wifiSecurityMode = "open"),
                connectedBand = WifiBand.BAND_2_4_GHZ
            )
            fail("Expected OntDriverException on conflict_mesh")
        } catch (e: OntDriverException) {
            assertTrue(e.message!!.contains("Mesh", ignoreCase = true))
        }
        assertEquals("Deterministic conflict_mesh must NOT retry 3 times in Step 7", 1, step7Attempts)
    }

    @Test
    fun `executeMobileSetup with open mode stores empty passwords instead of 1234567890 fallback and preserves wifiSecurityMode in MobileSetupResult`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"OK","firstlog":"0","sessionKey":"sess_open","numberLogin":"0","timeoutLogin":"0"}"""
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = OntMobileDriver(baseUrl = baseUrl, settlingDelayMs = 5L, recoveryRetryDelayMs = 20L, onVerifyNewSsid = { _, _ -> true })
        val blankPassRecord = sampleRecord.copy(pass24g = "", pass5g = "")
        val result = driver.executeMobileSetup(
            record = blankPassRecord,
            settings = GlobalSettings(baseUrl = baseUrl, wifiSecurityMode = "open"),
            connectedBand = WifiBand.BAND_2_4_GHZ
        )
        assertTrue(result.success)
        assertEquals("open", result.wifiSecurityMode)
        assertEquals("", result.newPass24g)
        assertEquals("", result.newPass5g)
    }
}
