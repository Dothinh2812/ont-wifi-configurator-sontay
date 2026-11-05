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
import vn.vnpt.ontconfig.core.driver.UnsafeSslClient
import vn.vnpt.ontconfig.core.driver.WifiHandoverContext
import vn.vnpt.ontconfig.core.wifi.OntWifiConnector
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.core.wifi.WifiScanCandidate
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SSLPeerUnverifiedException

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

    private fun testSettings(
        wifiSecurityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
        enableFirmwareUpdate: Boolean = false,
        firmwareFile: File? = null
    ): GlobalSettings = GlobalSettings(
        baseUrl = baseUrl,
        wifiSecurityMode = wifiSecurityMode,
        enableFirmwareUpdate = enableFirmwareUpdate,
        firmwareFilePath = firmwareFile?.absolutePath.orEmpty(),
        firmwareFileName = firmwareFile?.name.orEmpty(),
        firmwareFileSize = firmwareFile?.length() ?: 0L,
        wifiHandoverTimeoutMs = 150L,
        gatewayPostHandoverTimeoutMs = 150L,
        postSecondWifiSettleTimeoutMs = 150L,
        firmwareUploadTimeoutMs = 5_000L
    )

    private fun assocDhcpBody(band: WifiBand, ip: String = "192.168.1.3"): String {
        val ifc = if (band == WifiBand.BAND_5_GHZ) "wl1" else "wl0"
        return """{"dhcpInfoList":["$ip|DHCP||aa:bb:cc:dd:ee:ff|phone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|$ip|1|$ifc|-42"]}"""
    }

    private fun createTestDriver(
        activeBandRef: AtomicReference<WifiBand>,
        probeMutex: Mutex? = null,
        allowSingleBand24gFallback: Boolean = false,
        onUnbind: (() -> Unit)? = null,
        onRebind: (suspend () -> SocketFactory?)? = null,
        onHandover: ((String, WifiBand, String?) -> Unit)? = null
    ): OntMobileDriver = OntMobileDriver(
        baseUrl = baseUrl,
        timeoutMs = 1500L,
        settlingDelayMs = 5L,
        recoveryRetryDelayMs = 15L,
        wlRestartPollIntervalMs = 10L,
        maxWlRestartPolls = 3,
        userExistPollIntervalMs = 20L,
        allowSingleBand24gFallback = allowSingleBand24gFallback,
        probePauseMutex = probeMutex,
        onUnbindNetwork = onUnbind,
        onRebindWifiNetwork = onRebind,
        onSwitchToSecondaryWifi = { ssid, _, band, _, bssidHint, _, _ ->
            activeBandRef.set(band)
            onHandover?.invoke(ssid, band, bssidHint)
            WifiHandoverContext(
                socketFactory = SocketFactory.getDefault(),
                localWifiIpv4 = "192.168.1.3"
            )
        }
    )

    private fun createTempFirmwareFile(sizeBytes: Int = 72 * 1024): File {
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

    private fun stubX509CertWithBytes(derBytes: ByteArray): X509Certificate {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        return object : X509Certificate() {
            override fun getEncoded(): ByteArray = derBytes
            override fun verify(key: PublicKey?) {}
            override fun verify(key: PublicKey?, sigProvider: String?) {}
            override fun toString(): String = "StubX509"
            override fun getPublicKey(): PublicKey = kp.public
            override fun hasUnsupportedCriticalExtension(): Boolean = false
            override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
            override fun getExtensionValue(oid: String?): ByteArray? = null
            override fun checkValidity() {}
            override fun checkValidity(date: Date?) {}
            override fun getVersion(): Int = 3
            override fun getSerialNumber(): BigInteger = BigInteger.ONE
            @Suppress("DEPRECATION")
            override fun getIssuerDN(): Principal = Principal { "CN=ONT" }
            @Suppress("DEPRECATION")
            override fun getSubjectDN(): Principal = Principal { "CN=ONT" }
            override fun getNotBefore(): Date = Date()
            override fun getNotAfter(): Date = Date()
            override fun getTBSCertificate(): ByteArray = derBytes
            override fun getSignature(): ByteArray = derBytes
            override fun getSigAlgName(): String = "SHA256withRSA"
            override fun getSigAlgOID(): String = "1.2.840.113549.1.1.11"
            override fun getSigAlgParams(): ByteArray? = null
            override fun getIssuerUniqueID(): BooleanArray? = null
            override fun getSubjectUniqueID(): BooleanArray? = null
            override fun getKeyUsage(): BooleanArray? = null
            override fun getBasicConstraints(): Int = -1
        }
    }

    @Test
    fun `OntWifiConnector filters factory default GW040-NSv2 SSIDs and verifies BSSID hex suffix`() {
        assertTrue(OntWifiConnector.isDefaultOntSsid("GW040-NSv2_2.4G_A1B2C3", "00:11:22:A1:B2:C3"))
        assertTrue(OntWifiConnector.isDefaultOntSsid("GW040-NSv2_5G_A1B2C3", "00:11:22:A1:B2:C4"))
        assertFalse(OntWifiConnector.isDefaultOntSsid("GW040-NSv2_2.4G_A1B2C3", "00:11:22:FF:EE:DD"))
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
    fun `OntWifiConnector detectBandFromFrequency and isSameOntBssid handle Android 10+ frequency=-1 and virtual BSSID offsets`() {
        assertEquals(WifiBand.BAND_5_GHZ, OntWifiConnector.detectBandFromFrequency(-1, "GW040-NSv2_5G_60BCD8"))
        assertEquals(WifiBand.BAND_2_4_GHZ, OntWifiConnector.detectBandFromFrequency(-1, "GW040-NSv2_2.4G_60BCD8"))
        assertEquals(WifiBand.BAND_5_GHZ, OntWifiConnector.detectBandFromFrequency(5180, null))
        assertEquals(WifiBand.BAND_2_4_GHZ, OntWifiConnector.detectBandFromFrequency(2412, null))
        assertEquals(
            WifiBand.BAND_5_GHZ,
            OntWifiConnector.detectBandFromFrequency(
                frequencyMhz = -1,
                ssidHint = "Manh Ngoc Fast",
                configured24Ssid = "Manh Ngoc",
                configured5Ssid = "Manh Ngoc Fast"
            )
        )

        assertTrue(OntWifiConnector.isSameOntBssid("a4:f4:c2:60:bc:d8", "A4:F4:C2:60:BC:D8"))
        assertTrue(OntWifiConnector.isSameOntBssid("A4:F4:C2:60:BC:D8", "A4:F4:C2:60:BC:D9"))
        assertFalse(OntWifiConnector.isSameOntBssid("A4:F4:C2:60:BC:D8", "00:11:22:33:44:55"))
    }

    @Test
    fun `executeMobileSetup 8-step on 2_4GHz configures 5GHz in Step 6 switches to 5GHz and configures 2_4GHz in Step 8 with serviceId=1`() = runBlocking {
        val recordedPaths = CopyOnWriteArrayList<Pair<String, String>>()
        val unbindCalled = AtomicBoolean(false)
        val adminChanged = AtomicBoolean(false)
        val probeMutex = Mutex()
        val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                recordedPaths.add(path to body)

                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html><title>VNPT iGate GW040-NSv2</title></html>")
                    "/cgi-bin/luci/login.cmd" -> {
                        if (body.contains("pass=admin") && !adminChanged.get()) {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"1","numberLogin":"0","timeoutLogin":"0"}""")
                        } else if (body.contains("Passwd2")) {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_999","numberLogin":"0","timeoutLogin":"0"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}""")
                        }
                    }
                    "/cgi-bin/luci/accesscontrolcfg.json" -> {
                        adminChanged.set(true)
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    "/cgi-bin/luci/deviceinfo2.json" -> MockResponse().setResponseCode(200).setBody("""{"baseMacAddress":"AA:BB:CC:11:22:33\r\n"}""")
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody(
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

        val driver = createTestDriver(
            activeBandRef = activeBand,
            probeMutex = probeMutex,
            onUnbind = { unbindCalled.set(true) }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord,
            settings = testSettings(),
            connectedBand = WifiBand.BAND_2_4_GHZ,
            bssid = "AA:BB:CC:11:22:33"
        )

        assertTrue(result.success)
        assertFalse(result.firmwareUpdated)
        assertTrue(unbindCalled.get())
        assertFalse("Probe mutex must be unlocked in finally", probeMutex.isLocked)

        val editWanBody = recordedPaths.first { it.first == "/cgi-bin/luci/editwan.json" }.second
        val sendWanBody = recordedPaths.first { it.first == "/cgi-bin/luci/sendwan.cmd" }.second
        assertTrue(editWanBody.contains("serviceId=1"))
        assertTrue(sendWanBody.contains("serviceId=1"))

        val wirelessApplies = recordedPaths.filter {
            it.first == "/cgi-bin/luci/wirelesscfg.json" && it.second.contains("action=Apply")
        }
        assertEquals(2, wirelessApplies.size)
        assertTrue("Connection 1 Step 6 configures 5GHz (wl_unit=1) first", wirelessApplies[0].second.contains("wl_unit=1"))
        assertTrue("Connection 2 Step 8 configures 2.4GHz (wl_unit=0)", wirelessApplies[1].second.contains("wl_unit=0"))
        assertEquals("Clean logout on both Connection 1 and Connection 2", 2, recordedPaths.count { it.first == "/cgi-bin/luci/logout.cmd" })
        assertEquals("/cgi-bin/luci/logout.cmd", recordedPaths.last().first)
    }

    @Test
    fun `executeMobileSetup on 5GHz reverses band order so 2_4GHz is Step 6 handover target and 5GHz is Step 8`() = runBlocking {
        val wirelessApplies = CopyOnWriteArrayList<String>()
        val activeBand = AtomicReference(WifiBand.BAND_5_GHZ)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (path == "/cgi-bin/luci/wirelesscfg.json" && body.contains("action=Apply")) {
                    wirelessApplies.add(body)
                }
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>iGate GW040</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_5g"}""")
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = createTestDriver(activeBand)
        val result = driver.executeMobileSetup(
            record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
            settings = testSettings(),
            connectedBand = WifiBand.BAND_5_GHZ
        )

        assertTrue(result.success)
        assertEquals(2, wirelessApplies.size)
        assertTrue(wirelessApplies[0].contains("wl_unit=0"))
        assertTrue(wirelessApplies[1].contains("wl_unit=1"))
    }

    @Test
    fun `mid-flight error at Step 4 still invokes logout cmd and unbind in finally block`() = runBlocking {
        val logoutCalled = AtomicBoolean(false)
        val unbindCalled = AtomicBoolean(false)
        val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.body.readUtf8()
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

        val driver = createTestDriver(activeBand, onUnbind = { unbindCalled.set(true) })
        try {
            driver.executeMobileSetup(sampleRecord, testSettings())
            fail("Expected exception at Step 4")
        } catch (_: Exception) {
        }

        assertTrue(logoutCalled.get())
        assertTrue(unbindCalled.get())
    }

    @Test
    fun `Smart Retry prioritizes Passwd2@ when BSSID already changed password and stops at numberLogin 2`() = runBlocking {
        val loginAttempts = CopyOnWriteArrayList<String>()
        val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)

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
                            MockResponse().setResponseCode(200).setBody("""{"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}""")
                        }
                    }
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val driver = createTestDriver(activeBand)
        val result = driver.executeMobileSetup(
            record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
            settings = testSettings(),
            bssid = "AA:BB:CC:DD:EE:FF"
        )

        assertTrue(result.success)
        assertEquals(2, loginAttempts.size)
        assertTrue(loginAttempts.all { it.contains("Passwd2") && !it.contains("pass=admin") })

        loginAttempts.clear()
        try {
            driver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = ""),
                settings = testSettings(),
                bssid = "11:22:33:44:55:66"
            )
            fail("Expected OntAccountLockoutWarning when numberLogin >= 2")
        } catch (e: OntAccountLockoutWarning) {
            assertTrue(e.message!!.contains("2"))
        }
    }

    @Test
    fun `Step 1 rejects gateway response that lacks ONT HTML signature and FlushTrackedIOException guards byte flush`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setBody("<html><body>Captive Portal Not ONT</body></html>")
            }
        }

        val driver = createTestDriver(AtomicReference(WifiBand.BAND_2_4_GHZ))
        assertFalse(
            driver.isPostFlushRadioDrop(
                OntMobileDriver.FlushTrackedIOException(java.net.SocketTimeoutException("timeout"), allBytesFlushed = false)
            )
        )
        assertTrue(
            driver.isPostFlushRadioDrop(
                OntMobileDriver.FlushTrackedIOException(java.net.SocketTimeoutException("Read timed out"), allBytesFlushed = true)
            )
        )
        assertTrue(
            driver.isPreSendConnectFailure(
                OntMobileDriver.FlushTrackedIOException(java.net.ConnectException("Connection refused"), allBytesFlushed = false)
            )
        )

        try {
            driver.executeMobileSetup(sampleRecord, testSettings())
            fail("Expected OntNetworkError when gateway HTML lacks ONT signature")
        } catch (e: OntNetworkError) {
            assertTrue(e.message!!.contains("GW040") || e.message!!.contains("ONT"))
        }
    }

    @Test
    fun `resolveConnectedBandFromOnt overrides misdetected 2_4GHz client band using 2D wirelessInfoList and captures baseMacAddress`() = runBlocking {
        val callSequence = CopyOnWriteArrayList<String>()
        val resolvedCallbacks = CopyOnWriteArrayList<Pair<WifiBand, String?>>()
        val onSecondConn = AtomicBoolean(false)

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
                    "/cgi-bin/luci/dhcpclient.json" -> {
                        if (!onSecondConn.get()) {
                            MockResponse().setResponseCode(200).setBody(
                                """{"dhcpInfoList":["192.168.1.2|DHCP||aa:bb:cc:dd:ee:ff|phone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.2|1|wl1.1|-45"]}"""
                            )
                        } else {
                            MockResponse().setResponseCode(200).setBody(assocDhcpBody(WifiBand.BAND_2_4_GHZ))
                        }
                    }
                    "/cgi-bin/luci/deviceinfo2.json" -> MockResponse().setResponseCode(200).setBody(
                        """{"wirelessInfoList":[["wl0|1|100|10\n|GW040-NSv2_2.4G_60BCD8\n|c4:2c:7b:60:bc:d9\n","GW040-NSv2_2.4G_60BCD8\n|wl0"],["wl1|1|100|120\n|GW040-NSv2_5G_60BCD8\n|c4:2c:7b:60:bc:da\n","GW040-NSv2_5G_60BCD8\n|wl1"]],"baseMacAddress":"C4:2C:7B:60:BC:D8\n"}"""
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        if (body.contains("action=Apply")) {
                            val unit = if (body.contains("wl_unit=1")) "1" else "0"
                            callSequence.add("wirelesscfg:unit=$unit")
                        }
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)
        val bssidHints = CopyOnWriteArrayList<String?>()
        val driver = createTestDriver(
            activeBandRef = activeBand,
            onHandover = { _, _, bssidHint ->
                onSecondConn.set(true)
                bssidHints.add(bssidHint)
            }
        )

        val result = driver.executeMobileSetup(
            record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
            settings = testSettings(),
            connectedBand = WifiBand.BAND_2_4_GHZ,
            localWifiIpv4 = "192.168.1.2",
            onBandResolved = { band, baseMac -> resolvedCallbacks.add(band to baseMac) }
        )

        assertTrue(result.success)
        assertEquals(WifiBand.BAND_5_GHZ, result.resolvedConnectedBand)
        assertEquals(listOf(WifiBand.BAND_5_GHZ to "C4:2C:7B:60:BC:D8"), resolvedCallbacks)
        assertEquals(listOf("c4:2c:7b:60:bc:d9"), bssidHints)
        assertEquals(
            listOf("wirelesscfg:unit=0", "wirelesscfg:unit=1"),
            callSequence.filter { it.startsWith("wirelesscfg:unit=") }
        )
    }

    // Deep Test 1: Full 9-Step Dual-Wi-Fi + Final Firmware Flow (2.4GHz->5GHz & 5GHz->2.4GHz)
    @Test
    fun `twoConnectionNineStep configures Band B logs out switches to Band B re-authenticates only with Passwd2 configures Band A and uploads Firmware last`() = runBlocking {
        val fwFile = createTempFirmwareFile(72 * 1024)
        try {
            for (initialBand in listOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ)) {
                OntMobileDriver.clearCachedPasswordsForTest()
                val events = CopyOnWriteArrayList<String>()
                val loginPasswords = CopyOnWriteArrayList<String>()
                val recordedUploads = CopyOnWriteArrayList<ByteArray>()
                val activeBand = AtomicReference(initialBand)
                val onSecondConnection = AtomicBoolean(false)
                val conn2FirstUserExist = AtomicBoolean(true)
                val unbindCalled = AtomicBoolean(false)

                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path ?: ""
                        val rawBytes = request.body.readByteArray()
                        val body = String(rawBytes, Charsets.ISO_8859_1)
                        return when (path) {
                            "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                            "/cgi-bin/luci/login.cmd" -> {
                                val pw = Regex("pass=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                                loginPasswords.add(pw)
                                if (!onSecondConnection.get()) {
                                    MockResponse().setResponseCode(200).setBody(
                                        """{"status":"OK","firstlog":"0","sessionKey":"sess_conn1","numberLogin":"0","timeoutLogin":"0"}"""
                                    )
                                } else if (conn2FirstUserExist.getAndSet(false)) {
                                    MockResponse().setResponseCode(200).setBody("""{"status":"user exist"}""")
                                } else {
                                    MockResponse().setResponseCode(200).setBody(
                                        """{"status":"OK","firstlog":"0","sessionKey":"sess_conn2","numberLogin":"0","timeoutLogin":"0"}"""
                                    )
                                }
                            }
                            "/cgi-bin/luci/deviceinfo2.json" -> MockResponse().setResponseCode(200).setBody(
                                """{"baseMacAddress":"C4:2C:7B:60:BC:D8\n"}"""
                            )
                            "/cgi-bin/luci/dhcpclient.json" -> {
                                val ifc = if (activeBand.get() == WifiBand.BAND_5_GHZ) "wl1" else "wl0"
                                val ip = if (onSecondConnection.get()) "192.168.1.3" else "192.168.1.2"
                                MockResponse().setResponseCode(200).setBody(
                                    """{"dhcpInfoList":["$ip|DHCP||aa:bb:cc:dd:ee:ff|phone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|$ip|1|$ifc|-42"]}"""
                                )
                            }
                            "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                            "/cgi-bin/luci/wirelesscfg.json" -> {
                                val unit = if (body.contains("wl_unit=1")) "1" else "0"
                                if (body.contains("action=Apply")) {
                                    events.add("wireless_apply:unit=$unit")
                                }
                                MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                            }
                            "/cgi-bin/luci/wlrestart.json" -> MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                            "/cgi-bin/luci/logout.cmd" -> {
                                val outKey = Regex("sessionKey=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                                events.add("logout:$outKey")
                                MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                            }
                            "/cgi-bin/luci/updatefwsessioncheck.json" -> {
                                events.add("fw_check")
                                MockResponse().setResponseCode(200).setBody("""{"status":"OK","sessionKey":"sess_fw1"}""")
                            }
                            "/cgi-bin/luci/imagesize.json" -> {
                                assertTrue(body.contains("sessionKey=sess_fw1"))
                                events.add("fw_imagesize")
                                MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696","sessionKey":"sess_fw2"}""")
                            }
                            "/cgi-bin/luci/uploadsoftware.conf" -> {
                                events.add("fw_upload")
                                recordedUploads.add(rawBytes)
                                MockResponse().setResponseCode(200).setBody("""{"status":"valid","sessionKey":"sess_fw3"}""")
                            }
                            else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                    }
                }

                val progressSteps = CopyOnWriteArrayList<StepProgress>()
                val driver = createTestDriver(
                    activeBandRef = activeBand,
                    onUnbind = { unbindCalled.set(true) },
                    onHandover = { ssid, band, _ ->
                        onSecondConnection.set(true)
                        events.add("handover:$ssid:${band.label}")
                    }
                )

                val result = driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = testSettings(enableFirmwareUpdate = true, firmwareFile = fwFile),
                    connectedBand = initialBand,
                    localWifiIpv4 = "192.168.1.2",
                    onProgress = { progressSteps.add(it) }
                )

                assertTrue(result.success)
                assertTrue(result.firmwareUpdated)
                assertTrue(unbindCalled.get())
                assertTrue("Must never send adminDefaultPass", loginPasswords.none { it == "admin" })
                assertTrue(progressSteps.all { it.totalSteps == 9 })

                val expectedConn1Unit = if (initialBand == WifiBand.BAND_2_4_GHZ) "1" else "0"
                val expectedConn2Unit = if (initialBand == WifiBand.BAND_2_4_GHZ) "0" else "1"
                val applyConn1Idx = events.indexOf("wireless_apply:unit=$expectedConn1Unit")
                val handoverIdx = events.indexOfFirst { it.startsWith("handover:") }
                val applyConn2Idx = events.indexOf("wireless_apply:unit=$expectedConn2Unit")
                val fwUploadIdx = events.indexOf("fw_upload")
                assertTrue(applyConn1Idx >= 0 && handoverIdx > applyConn1Idx)
                assertTrue(applyConn2Idx > handoverIdx && fwUploadIdx > applyConn2Idx)
                assertFalse("No logout.cmd after firmware status=valid", events.drop(fwUploadIdx + 1).any { it.startsWith("logout:") })

                val uploadedBody = recordedUploads.single()
                assertTrue(uploadedBody.contains(0x7f.toByte()))
                assertTrue(uploadedBody.contains(0xff.toByte()))
            }
        } finally {
            fwFile.delete()
        }
    }

    // Deep Test 2: Connection 2 "user exist" TTL Recovery & ensureGatewayReadyOnSecondaryBand Zero "admin" Fallback (RT-01)
    @Test
    fun `ensureGatewayReadyOnSecondaryBand recovers from fail authen and user exist with zero admin fallback and single-call lockout stop`() = runBlocking {
        val fwFile = createTempFirmwareFile(68 * 1024)
        try {
            val postStep2Passwords = CopyOnWriteArrayList<String>()
            val releasedSessions = CopyOnWriteArrayList<String>()
            val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)
            val onSecondConn = AtomicBoolean(false)
            var conn2LoginAttempt = 0
            var preFwRestartCount = 0

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val body = request.body.readUtf8()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> {
                            val pw = Regex("pass=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                            if (!onSecondConn.get()) {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"status":"OK","firstlog":"0","sessionKey":"sess_c1","numberLogin":"0","timeoutLogin":"0"}"""
                                )
                            } else {
                                postStep2Passwords.add(pw)
                                conn2LoginAttempt++
                                // Simulate user exist persisting across 2 polls even after logout, then succeeding!
                                if (conn2LoginAttempt in setOf(1, 2, 4)) {
                                    MockResponse().setResponseCode(200).setBody("""{"status":"user exist"}""")
                                } else {
                                    MockResponse().setResponseCode(200).setBody(
                                        """{"status":"OK","firstlog":"0","sessionKey":"sess_c2_$conn2LoginAttempt","numberLogin":"0","timeoutLogin":"0"}"""
                                    )
                                }
                            }
                        }
                        "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[],"sessionKey":"0"}""")
                        "/cgi-bin/luci/wlrestart.json" -> {
                            if (onSecondConn.get()) {
                                preFwRestartCount++
                                if (preFwRestartCount == 2) {
                                    // Trigger ensureGatewayReadyOnSecondaryBand re-auth before Step 9!
                                    return MockResponse().setResponseCode(200).setBody("""{"status":"fail authen"}""")
                                }
                            }
                            MockResponse().setResponseCode(200).setBody("""{"_wlrestart_":"0"}""")
                        }
                        "/cgi-bin/luci/logout.cmd" -> {
                            val outSess = Regex("sessionKey=([^&]+)").find(body)?.groupValues?.get(1).orEmpty()
                            releasedSessions.add(outSess)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696"}""")
                        "/cgi-bin/luci/uploadsoftware.conf" -> MockResponse().setResponseCode(200).setBody("""{"status":"valid"}""")
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                }
            }

            val driver = createTestDriver(
                activeBandRef = activeBand,
                onRebind = { SocketFactory.getDefault() },
                onHandover = { _, _, _ -> onSecondConn.set(true) }
            )

            val res = driver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = testSettings(enableFirmwareUpdate = true, firmwareFile = fwFile)
            )
            assertTrue(res.success)
            assertTrue(releasedSessions.contains("sess_c1"))
            assertTrue(postStep2Passwords.isNotEmpty())
            assertTrue(
                "Connection 2 must NEVER send adminDefaultPass ('admin'): $postStep2Passwords",
                postStep2Passwords.none { it == "admin" }
            )

            // Verify numberLogin == 2 during Connection 2 stops immediately in 1 call (zero double-login)
            var lockoutCalls = 0
            onSecondConn.set(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    request.body.readUtf8()
                    return when (request.path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> {
                            if (!onSecondConn.get()) {
                                MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_ok"}""")
                            } else {
                                lockoutCalls++
                                MockResponse().setResponseCode(200).setBody("""{"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}""")
                            }
                        }
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            try {
                driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = testSettings()
                )
                fail("Expected OntAccountLockoutWarning on numberLogin == 2")
            } catch (_: OntAccountLockoutWarning) {
            }
            assertEquals(1, lockoutCalls)
        } finally {
            fwFile.delete()
        }
    }

    // Deep Test 3: Same-SSID ("GiaDinh") wl0 Rejection, Neighbor ONT baseMacAddress Rejection & TLS Pinning Rejection (RT-02, RT-04)
    @Test
    fun `Step 7 rejects same-SSID still on wl0 neighbor baseMacAddress mismatch and mismatched TLS certificate pin`() = runBlocking {
        // 3a. TLS leaf certificate SHA-256 pinning check
        val certOnt1 = stubX509CertWithBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val certEvilTwin = stubX509CertWithBytes(byteArrayOf(0x09, 0x08, 0x07, 0x06))
        val observedPins = CopyOnWriteArrayList<String>()
        UnsafeSslClient.verifyPinnedCertificateChain(arrayOf(certOnt1), pinnedCertSha256 = null) {
            observedPins.add(it)
        }
        assertEquals(1, observedPins.size)
        // Matching leaf cert passes
        UnsafeSslClient.verifyPinnedCertificateChain(arrayOf(certOnt1), pinnedCertSha256 = observedPins[0])
        // Mismatched Evil-Twin / neighbor cert throws SSLPeerUnverifiedException immediately
        try {
            UnsafeSslClient.verifyPinnedCertificateChain(arrayOf(certEvilTwin), pinnedCertSha256 = observedPins[0])
            fail("Expected SSLPeerUnverifiedException on TLS pin mismatch")
        } catch (_: SSLPeerUnverifiedException) {
        }

        // 3b. Same SSID ("GiaDinh") on 2.4G & 5G still associated with wl0 -> Step 7 rejects before Step 8!
        val step8Applied = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_dup"}""")
                    "/cgi-bin/luci/deviceinfo2.json" -> MockResponse().setResponseCode(200).setBody("""{"baseMacAddress":"A4:F4:C2:11:22:33"}""")
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(
                        """{"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.3|1|wl0|-40"]}"""
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        if (body.contains("wl_unit=0") && body.contains("action=Apply")) {
                            step8Applied.set(true)
                        }
                        MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val sameSsidRecord = sampleRecord.copy(ssid24g = "GiaDinh", ssid5g = "GiaDinh", lastKnownAdminPass = "Passwd2@")
        val driver = createTestDriver(AtomicReference(WifiBand.BAND_2_4_GHZ))
        try {
            driver.executeMobileSetup(
                record = sameSsidRecord,
                settings = testSettings(),
                connectedBand = WifiBand.BAND_2_4_GHZ,
                localWifiIpv4 = "192.168.1.2"
            )
            fail("Expected OntDriverException when wlAssociatedDevice remains wl0 on Connection 2")
        } catch (e: OntDriverException) {
            assertTrue(e.message!!.contains("2.4GHz") || e.message!!.contains("wl0"))
        }
        assertFalse("Step 8 must NOT execute when still on Band A", step8Applied.get())

        // 3c. Neighbor ONT baseMacAddress mismatch on Connection 2 -> immediate rejection
        val onSecond = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                request.body.readUtf8()
                return when (request.path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_mac"}""")
                    "/cgi-bin/luci/deviceinfo2.json" -> {
                        val mac = if (onSecond.get()) "DE:AD:BE:EF:99:99" else "A4:F4:C2:11:22:33"
                        MockResponse().setResponseCode(200).setBody("""{"baseMacAddress":"$mac"}""")
                    }
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(
                        assocDhcpBody(if (onSecond.get()) WifiBand.BAND_5_GHZ else WifiBand.BAND_2_4_GHZ)
                    )
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val macDriver = createTestDriver(
            AtomicReference(WifiBand.BAND_2_4_GHZ),
            onHandover = { _, _, _ -> onSecond.set(true) }
        )
        try {
            macDriver.executeMobileSetup(
                record = sameSsidRecord,
                settings = testSettings(),
                connectedBand = WifiBand.BAND_2_4_GHZ,
                localWifiIpv4 = "192.168.1.2"
            )
            fail("Expected neighbor ONT baseMacAddress mismatch error")
        } catch (e: OntDriverException) {
            assertTrue(e.message!!.contains("MAC"))
        }
    }

    // Deep Test 4: Step 8 Read-Only NVRAM Verification & 2.4GHz-Only Phone Fallback (RT-02, RT-08)
    @Test
    fun `Step 8 verifies read-only nvramWlSsid on socket drop and allowSingleBand24gFallback completes 9-step firmware setup on 2_4GHz-only phone`() = runBlocking {
        // 4a. Step 8 socket drop when NVRAM already saved primarySsid -> sends read-only query + wlrestart without duplicate Apply
        val activeBand = AtomicReference(WifiBand.BAND_5_GHZ)
        var unit1ApplyCount = 0
        val readPayloads = CopyOnWriteArrayList<String>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_ro"}""")
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        if (body.contains("action=Apply")) {
                            if (body.contains("wl_unit=1")) {
                                unit1ApplyCount++
                                if (unit1ApplyCount == 1) {
                                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                                }
                            }
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        } else {
                            readPayloads.add(body)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK","nvramWlSsid":"Manh Ngoc 5G"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val roDriver = createTestDriver(activeBand)
        val roRes = roDriver.executeMobileSetup(
            record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
            settings = testSettings(),
            connectedBand = WifiBand.BAND_5_GHZ
        )
        assertTrue(roRes.success)
        assertEquals(1, unit1ApplyCount)
        assertEquals(1, readPayloads.size)
        assertTrue(readPayloads[0].contains("page=ssid.asp&wl_unit=1&wl_bssid=0"))

        // 4b. 2.4GHz-only Android fallback (`allowSingleBand24gFallback = true`) with 9-step Firmware Update
        val fwFile = createTempFirmwareFile(68 * 1024)
        try {
            val handoverTargets = CopyOnWriteArrayList<Pair<String, WifiBand>>()
            val fallbackBandRef = AtomicReference(WifiBand.BAND_2_4_GHZ)
            val savedByUnit = ConcurrentMapStub()

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val raw = request.body.readByteArray()
                    val body = String(raw, Charsets.ISO_8859_1)
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_24only"}""")
                        "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(WifiBand.BAND_2_4_GHZ))
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/wirelesscfg.json" -> {
                            val unit = if (body.contains("wl_unit=1")) "1" else "0"
                            if (body.contains("action=Apply")) {
                                savedByUnit.put(unit, if (unit == "1") "Manh Ngoc 5G" else "Manh Ngoc")
                                if (unit == "0") {
                                    // Simulate 2.4GHz dropping socket right as it renames in Step 6!
                                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                                }
                                MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                            } else {
                                MockResponse().setResponseCode(200).setBody("""{"status":"OK","nvramWlSsid":"${savedByUnit.get(unit)}"}""")
                            }
                        }
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696"}""")
                        "/cgi-bin/luci/uploadsoftware.conf" -> MockResponse().setResponseCode(200).setBody("""{"status":"valid"}""")
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            val singleBandDriver = createTestDriver(
                activeBandRef = fallbackBandRef,
                allowSingleBand24gFallback = true,
                onHandover = { ssid, band, _ -> handoverTargets.add(ssid to band) }
            )
            val fbRes = singleBandDriver.executeMobileSetup(
                record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                settings = testSettings(enableFirmwareUpdate = true, firmwareFile = fwFile),
                connectedBand = WifiBand.BAND_2_4_GHZ
            )
            assertTrue(fbRes.success)
            assertTrue(fbRes.firmwareUpdated)
            assertEquals(listOf("Manh Ngoc" to WifiBand.BAND_2_4_GHZ), handoverTargets)
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `updateFirmware handles insufficient RAM invalid signature and post-flush socket drop cleanly`() = runBlocking {
        val fwFile = createTempFirmwareFile(80 * 1024)
        try {
            val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)

            // 1. Insufficient RAM rotates sessionKey and logs out cleanly
            val logoutPayloads = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    val body = request.body.readUtf8()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_start"}""")
                        "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","sessionKey":"sess_chk_rot"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"20","sessionKey":"sess_img_rot"}""")
                        "/cgi-bin/luci/logout.cmd" -> {
                            logoutPayloads.add(body)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            val driver = createTestDriver(activeBand)
            try {
                driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = testSettings(enableFirmwareUpdate = true, firmwareFile = fwFile)
                )
                fail("Expected OntDriverException on insufficient RAM")
            } catch (e: OntDriverException) {
                assertTrue(e.message!!.contains("Bước 1-8"))
                assertTrue(e.message!!.contains("RAM trống"))
            }
            assertTrue(logoutPayloads.last().contains("sessionKey=sess_img_rot"))

            // 2. Post-flush socket drop during uploadsoftware.conf clears sessionKey (NO logout.cmd after upload!)
            val uploadSeen = AtomicBoolean(false)
            val logoutAfterUpload = AtomicBoolean(false)
            activeBand.set(WifiBand.BAND_2_4_GHZ)

            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    request.body.readByteArray()
                    return when (path) {
                        "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                        "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_flush"}""")
                        "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                        "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                        "/cgi-bin/luci/updatefwsessioncheck.json" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        "/cgi-bin/luci/imagesize.json" -> MockResponse().setResponseCode(200).setBody("""{"ramFree":"251696"}""")
                        "/cgi-bin/luci/uploadsoftware.conf" -> {
                            uploadSeen.set(true)
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        "/cgi-bin/luci/logout.cmd" -> {
                            if (uploadSeen.get()) logoutAfterUpload.set(true)
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                        else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                    }
                }
            }

            try {
                driver.executeMobileSetup(
                    record = sampleRecord.copy(lastKnownAdminPass = "Passwd2@"),
                    settings = testSettings(enableFirmwareUpdate = true, firmwareFile = fwFile)
                )
                fail("Expected OntNetworkError on post-flush socket drop")
            } catch (e: OntNetworkError) {
                assertTrue(e.message!!.contains("Bước 1-8"))
            }
            assertFalse("logout.cmd must NOT fire after 100% firmware bytes flushed", logoutAfterUpload.get())
        } finally {
            fwFile.delete()
        }
    }

    @Test
    fun `configureWifiBand sends all 6 security modes conflict_mesh fails without retry and open mode stores blank passwords`() = runBlocking {
        val wirelessBodies = CopyOnWriteArrayList<String>()
        val activeBand = AtomicReference(WifiBand.BAND_2_4_GHZ)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                if (path == "/cgi-bin/luci/wirelesscfg.json") wirelessBodies.add(body)
                return MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
            }
        }

        val driver = createTestDriver(activeBand)
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

        // Open mode end-to-end 8-step setup
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_open"}""")
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        val openRes = driver.executeMobileSetup(
            record = sampleRecord.copy(pass24g = "", pass5g = "", lastKnownAdminPass = "Passwd2@"),
            settings = testSettings(wifiSecurityMode = "open"),
            connectedBand = WifiBand.BAND_2_4_GHZ
        )
        assertTrue(openRes.success)
        assertEquals("open", openRes.wifiSecurityMode)
        assertEquals("", openRes.newPass24g)
        assertEquals("", openRes.newPass5g)

        // Deterministic conflict_mesh at Step 8 throws immediately without retry
        var step8Attempts = 0
        activeBand.set(WifiBand.BAND_2_4_GHZ)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val body = request.body.readUtf8()
                return when (path) {
                    "/" -> MockResponse().setResponseCode(200).setBody("<html>VNPT iGate GW040-NSv2</html>")
                    "/cgi-bin/luci/login.cmd" -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","firstlog":"0","sessionKey":"sess_mesh"}""")
                    "/cgi-bin/luci/dhcpclient.json" -> MockResponse().setResponseCode(200).setBody(assocDhcpBody(activeBand.get()))
                    "/cgi-bin/luci/wancfg.json" -> MockResponse().setResponseCode(200).setBody("""{"wanCfg":[]}""")
                    "/cgi-bin/luci/wirelesscfg.json" -> {
                        if (body.contains("wl_unit=0") && body.contains("action=Apply")) {
                            step8Attempts++
                            MockResponse().setResponseCode(200).setBody("""{"status":"conflict_mesh"}""")
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"status":"OK"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"OK","_wlrestart_":"0"}""")
                }
            }
        }

        try {
            driver.executeMobileSetup(
                record = sampleRecord.copy(pass24g = "", pass5g = "", lastKnownAdminPass = "Passwd2@"),
                settings = testSettings(wifiSecurityMode = "open"),
                connectedBand = WifiBand.BAND_2_4_GHZ
            )
            fail("Expected OntDriverException on conflict_mesh")
        } catch (e: OntDriverException) {
            assertTrue(e.message!!.contains("Mesh", ignoreCase = true))
        }
        assertEquals(1, step8Attempts)
    }

    private class ConcurrentMapStub {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun put(k: String, v: String) { map[k] = v }
        fun get(k: String): String = map[k].orEmpty()
    }
}
