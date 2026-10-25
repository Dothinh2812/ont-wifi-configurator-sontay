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
import vn.vnpt.ontconfig.core.driver.OntMobileDriver
import vn.vnpt.ontconfig.core.driver.OntNetworkError
import vn.vnpt.ontconfig.core.wifi.OntWifiConnector
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.core.wifi.WifiScanCandidate
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
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
        val verifyCalled = AtomicBoolean(false)
        val unbindCalled = AtomicBoolean(false)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_drop"}""")
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        wirelessCallCount++
                        if (wirelessCallCount == 2) {
                            // Step 7: simulate ONT 2.4GHz radio dropping socket immediately upon receiving Apply
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
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
    fun `OntWifiConnector detectBandFromFrequency uses SSID hint when frequency is unavailable on Android 10+`() {
        assertEquals(WifiBand.BAND_5_GHZ, OntWifiConnector.detectBandFromFrequency(-1, "GW040-NSv2_5G_60BCD8"))
        assertEquals(WifiBand.BAND_2_4_GHZ, OntWifiConnector.detectBandFromFrequency(-1, "GW040-NSv2_2.4G_60BCD8"))
        assertEquals(WifiBand.BAND_5_GHZ, OntWifiConnector.detectBandFromFrequency(5180, null))
        assertEquals(WifiBand.BAND_2_4_GHZ, OntWifiConnector.detectBandFromFrequency(2412, null))
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
}
