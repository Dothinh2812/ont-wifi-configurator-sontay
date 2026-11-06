package vn.vnpt.ontconfig.core.driver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import org.json.JSONArray
import org.json.JSONObject
import vn.vnpt.ontconfig.core.logging.LogLevel
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import vn.vnpt.ontconfig.core.wifi.OntWifiConnector
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

data class StepProgress(
    val stepNumber: Int,
    val totalSteps: Int = 8,
    val title: String,
    val status: StepStatus,
    val detail: String = ""
)

data class MobileSetupResult(
    val success: Boolean,
    val soMay: String,
    val slid: String,
    val accountPppoe: String,
    val newSsid24g: String,
    val newPass24g: String,
    val newSsid5g: String,
    val newPass5g: String,
    val updatedAdminPass: String,
    val message: String,
    val beaconVerified: Boolean = false,
    val firmwareUpdated: Boolean = false,
    val firmwareFileName: String = "",
    val configuredSecondaryBandLabel: String = "",
    val configuredSecondarySsid: String = "",
    val configuredSecondaryPass: String = "",
    val wifiSecurityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
    val resolvedConnectedBand: WifiBand = WifiBand.BAND_2_4_GHZ
)

data class WifiHandoverContext(
    val socketFactory: SocketFactory,
    val localWifiIpv4: String?
)

class OntMobileDriver(
    private val baseUrl: String = "https://192.168.1.1",
    private val timeoutMs: Long = 5000L,
    private val socketFactory: SocketFactory? = null,
    private val settlingDelayMs: Long = 400L,
    private val recoveryRetryDelayMs: Long = 1200L,
    private val wlRestartPollIntervalMs: Long = 1000L,
    private val maxWlRestartPolls: Int = 30,
    private val userExistPollIntervalMs: Long = 3000L,
    private val postDhcpGatewaySettleMs: Long = 2_500L,
    private val handoverRetryInitialBackoffMs: Long = 5_000L,
    private val handoverRetryMaxBackoffMs: Long = 20_000L,
    private val bandVerifyPollTimeoutMs: Long = 15_000L,
    private val bandVerifyPollIntervalMs: Long = 2_000L,
    private val allowSingleBand24gFallback: Boolean = false,
    private val probePauseMutex: Mutex? = null,
    private val onUnbindNetwork: (() -> Unit)? = null,
    private val onAdminPasswordChanged: (suspend (String) -> Unit)? = null,
    private val onRebindWifiNetwork: (suspend () -> SocketFactory?)? = null,
    private val isNetworkTransportAlive: (() -> Boolean)? = null,
    private val onLog: ((LogLevel, String, String) -> Unit)? = null,
    private val onSwitchToSecondaryWifi: (suspend (
        ssid: String,
        password: String,
        band: WifiBand,
        secMode: String,
        bssidHint: String?,
        timeoutMs: Long,
        minScanTimestampMicros: Long,
        outerAttempt: Int,
        lockToBssidHint: Boolean,
        onDetail: (String) -> Unit
    ) -> WifiHandoverContext)? = null
) {

    private class MissingDeviceInfoException(message: String) : OntNetworkError(message)

    private fun logDiag(level: LogLevel, tag: String, message: String) {
        onLog?.invoke(level, tag, message)
    }

    private fun currentScanEpochMicros(): Long {
        val bootMicros = runCatching { android.os.SystemClock.elapsedRealtimeNanos() / 1_000L }.getOrDefault(0L)
        return if (bootMicros > 0L) bootMicros else (System.nanoTime() / 1_000L).coerceAtLeast(1L)
    }

    internal class FlushTrackedIOException(
        override val cause: IOException,
        val allBytesFlushed: Boolean
    ) : IOException(cause.message, cause)

    private class FlushTrackedRequestBody(
        private val delegate: RequestBody,
        private val allBytesFlushed: AtomicBoolean
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: okio.BufferedSink) {
            delegate.writeTo(sink)
            sink.flush()
            allBytesFlushed.set(true)
        }
    }

    private val cleanBaseUrl = baseUrl.trimEnd('/')
    private val hostOverride = try {
        URI(cleanBaseUrl).host
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var currentSocketFactory: SocketFactory? = socketFactory

    @Volatile
    private var observedPeerCertSha256: String? = null

    @Volatile
    private var pinnedPeerCertSha256: String? = null

    @Volatile
    private var client: OkHttpClient = buildHttpClient(socketFactory, timeoutMs)

    @Volatile
    private var logoutClient: OkHttpClient = buildHttpClient(socketFactory, 1500L)
    @Volatile
    private var sessionKey: String = ""

    @Volatile
    private var lastActiveSessionKey: String = ""

    @Volatile
    private var currentUser: String = "admin"

    @Volatile
    private var currentPass: String = ""
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    private fun isValidSessionToken(key: String): Boolean =
        key.isNotEmpty() && key != "0" && key != "factoryID"

    private fun clearSessionKeys() {
        sessionKey = ""
        lastActiveSessionKey = ""
    }

    private fun updateSessionKey(newKey: String) {
        val clean = newKey.trim()
        sessionKey = clean
        if (isValidSessionToken(clean)) {
            lastActiveSessionKey = clean
        }
    }
    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    private fun hasTlsCertMismatchCause(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is SSLPeerUnverifiedException }


    private fun buildHttpClient(sf: SocketFactory?, timeout: Long): OkHttpClient {
        return UnsafeSslClient.create(
            socketFactory = sf,
            timeoutMs = timeout,
            allowedHostOverride = hostOverride,
            pinnedCertSha256 = pinnedPeerCertSha256,
            onPeerCertSha256 = { sha ->
                observedPeerCertSha256 = sha
            }
        )
    }

    private fun rebuildHttpClients(newSocketFactory: SocketFactory?) {
        currentSocketFactory = newSocketFactory
        client = buildHttpClient(newSocketFactory, timeoutMs)
        logoutClient = buildHttpClient(newSocketFactory, 1500L)
    }

    internal fun setPinnedCertSha256ForTest(sha256Hex: String?) {
        pinnedPeerCertSha256 = sha256Hex?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        rebuildHttpClients(currentSocketFactory)
    }
    /**
     * [Red Team RT-03] Build upload client keeping short connectTimeout (5s) while extending
     * readTimeout and writeTimeout (default 120s) for streaming 20-35 MB firmware images.
     */
    private fun buildUploadHttpClient(uploadTimeoutMs: Long): OkHttpClient {
        val effectiveTimeout = uploadTimeoutMs.coerceAtLeast(timeoutMs)
        return client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .writeTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .build()
    }

    fun updateSocketFactory(newSocketFactory: SocketFactory?) {
        if (currentSocketFactory === newSocketFactory) return
        rebuildHttpClients(newSocketFactory)
    }

    /**
     * [Red Team RT-04 & RT-10] Probe root URL and verify both HTTP 200 AND ONT HTML signature
     * ("iGate", "GW040", "VNPT", or "login.js"), failing closed immediately on TLS pin mismatch.
     */
    suspend fun probeGateway(failOnCertMismatch: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$cleanBaseUrl/")
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Connection", "close")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@withContext false
                val body = resp.body?.string().orEmpty()
                ONT_HTML_SIGNATURES.any { sig -> body.contains(sig, ignoreCase = true) }
            }
        } catch (e: SSLPeerUnverifiedException) {
            if (failOnCertMismatch || !pinnedPeerCertSha256.isNullOrBlank()) {
                throw OntDriverException(
                    "Chứng chỉ bảo mật TLS của ONT ở Lần kết nối 2 không khớp với ONT ban đầu — nghi ngờ bắt nhầm Wi-Fi khác.",
                    e
                )
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun postForm(
        path: String,
        rawPayload: String,
        useShortLogoutTimeout: Boolean = false,
        referer: String = "$cleanBaseUrl/"
    ): Pair<Int, String> {
        val targetClient = if (useShortLogoutTimeout) logoutClient else client
        val allBytesFlushed = AtomicBoolean(false)
        val trackedBody = FlushTrackedRequestBody(
            delegate = rawPayload.toRequestBody(formMediaType),
            allBytesFlushed = allBytesFlushed
        )
        val req = Request.Builder()
            .url("$cleanBaseUrl$path")
            .post(trackedBody)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Connection", "close")
            .build()

        try {
            targetClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                return resp.code to text
            }
        } catch (e: IOException) {
            if (e is FlushTrackedIOException) throw e
            throw FlushTrackedIOException(e, allBytesFlushed.get())
        }
    }

    private fun postFormWithReferer(
        path: String,
        rawPayload: String,
        referer: String
    ): Pair<Int, String> = postForm(
        path = path,
        rawPayload = rawPayload,
        useShortLogoutTimeout = false,
        referer = referer
    )

    private fun parseJsonOrThrow(stepContext: String, code: Int, body: String): JSONObject {
        if (code != 200) {
            throw OntDriverException("$stepContext thất bại (HTTP $code)")
        }
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return JSONObject().put("status", "OK")
        }
        if (trimmed.startsWith("<")) {
            throw OntDriverException("$stepContext trả về trang HTML thay vì JSON hợp lệ (phiên có thể đã hết hạn)")
        }
        return try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw OntDriverException("$stepContext trả về JSON không hợp lệ: ${trimmed.take(120)}", e)
        }
    }

    /**
     * Authenticate with /cgi-bin/luci/login.cmd and enforce lockout thresholds.
     */
    fun login(user: String, password: String): JSONObject {
        OntInputValidator.sanitizeInputMetacharacters(user)
        OntInputValidator.sanitizeInputMetacharacters(password)

        val encUser = OntInputValidator.urlEncodeFormValue(user)
        val encPass = OntInputValidator.urlEncodeFormValue(password)
        val payload = "user=$encUser&pass=$encPass"

        val (code, text) = try {
            postForm("/cgi-bin/luci/login.cmd", payload)
        } catch (e: IOException) {
            throw OntNetworkError("Lỗi kết nối khi gửi yêu cầu đăng nhập: ${e.message}", e)
        }

        val data = parseJsonOrThrow("Đăng nhập login.cmd", code, text)
        val status = data.optString("status", "")
        if (status.equals("user exist", ignoreCase = true)) {
            throw OntUserExistError(
                "Tài khoản admin đang bận phiên khác (user exist). " +
                    "Vui lòng chờ 60s hoặc khởi động lại ONT để giải phóng phiên."
            )
        }

        val timeoutLogin = data.optString("timeoutLogin", "0").toIntOrNull() ?: 0
        val numberLogin = data.optString("numberLogin", "0").toIntOrNull() ?: 0

        if (timeoutLogin > 0 || numberLogin >= 3) {
            throw OntAccountLockoutError(
                "ONT đang bị khóa phần cứng trong ${timeoutLogin}s do nhập sai quá $numberLogin lần. " +
                    "Vui lòng đợi hết thời gian hoặc bấm nút Reset cứng sau thân ONT."
            )
        }

        if (numberLogin >= 2) {
            throw OntAccountLockoutWarning(
                "Cảnh báo: ONT đã ghi nhận $numberLogin lần đăng nhập sai. " +
                    "Dừng lại để tránh bị khóa 180s. Vui lòng kiểm tra mật khẩu hoặc bấm nút Reset cứng."
            )
        }

        when (data.optString("firstlog", "")) {
            "1" -> updateSessionKey("factoryID")
            "0" -> {
                val issuedKey = data.optString("sessionKey", "").trim()
                if (issuedKey.isNotEmpty() && issuedKey != "0") {
                    updateSessionKey(issuedKey)
                } else {
                    sessionKey = if (issuedKey == "0") "" else issuedKey
                }
            }
        }
        currentUser = user
        currentPass = password
        return data
    }

    /**
     * [Red Team RT-04] Smart Login & Password Change:
     * Prioritizes newPass ("Passwd2@") if this BSSID or subscriber record already completed Step 2
     * in a previous interrupted run, preventing a wasted default-pass attempt that would push
     * numberLogin to 2 and trigger OntAccountLockoutWarning.
     */
    fun loginAndChangePassword(
        defaultPass: String,
        newPass: String,
        bssid: String? = null,
        lastKnownAdminPass: String = ""
    ): String {
        if (!OntInputValidator.validatePasswordComplexity(newPass)) {
            throw OntValidationError(
                "Mật khẩu admin mới không đạt chuẩn độ phức tạp (8..32 ký tự ASCII không khoảng trắng, đủ hoa, thường, số và ký tự đặc biệt)."
            )
        }

        val bssidKey = bssid?.trim()?.uppercase().orEmpty()
        val cachedPass = if (bssidKey.isNotEmpty()) bssidPasswordCache[bssidKey] else null
        val preferNewPassFirst = (cachedPass == newPass) || (lastKnownAdminPass == newPass)

        val candidateOrder = if (preferNewPassFirst) {
            listOf(newPass, defaultPass).distinct()
        } else {
            listOf(defaultPass, newPass).distinct()
        }

        for (candidatePass in candidateOrder) {
            var loginData: JSONObject? = null
            val loginOk = try {
                loginData = login("admin", candidatePass)
                val firstLog = loginData.optString("firstlog", "")
                val status = loginData.optString("status", "")
                (status == "OK" || firstLog == "1" || firstLog == "0") &&
                    (firstLog == "1" || sessionKey.isNotEmpty())
            } catch (e: OntAccountLockoutError) {
                throw e
            } catch (e: OntAccountLockoutWarning) {
                throw e
            } catch (e: OntUserExistError) {
                throw e
            } catch (_: Exception) {
                false
            }

            if (!loginOk || loginData == null) {
                if (candidatePass == defaultPass && bssidKey.isNotEmpty()) {
                    bssidPasswordCache[bssidKey] = newPass
                }
                continue
            }

            val firstLog = loginData.optString("firstlog", "")
            if (firstLog == "1") {
                val encNewPass = OntInputValidator.urlEncodeFormValue(newPass)
                val changePayload =
                    "undefined&sessionKey=$sessionKey&username=admin&oldPass=&newPass=$encNewPass&user_cur_log=admin"
                val (code, body) = try {
                    postForm("/cgi-bin/luci/accesscontrolcfg.json", changePayload)
                } catch (e: IOException) {
                    throw OntNetworkError("Lỗi mạng khi đổi mật khẩu admin: ${e.message}", e)
                }
                parseJsonOrThrow("Đổi mật khẩu Admin", code, body)

                if (bssidKey.isNotEmpty()) {
                    bssidPasswordCache[bssidKey] = newPass
                }

                val relogData = login("admin", newPass)
                val relogKey = relogData.optString("sessionKey", "").trim()
                if (relogKey.isNotEmpty() && relogKey != "0") {
                    updateSessionKey(relogKey)
                }
                return sessionKey
            } else {
                if (bssidKey.isNotEmpty()) {
                    bssidPasswordCache[bssidKey] = candidatePass
                }
                return sessionKey
            }
        }

        throw OntAuthenticationError(
            "Đăng nhập thất bại với cả mật khẩu mặc định và mật khẩu mới. " +
                "Vui lòng bấm nút Reset cứng sau thân ONT để đưa về trạng thái xuất xưởng."
        )
    }

    /**
     * [Red Team RT-07 & RT-08] Idempotent WAN PPPoE VLAN 11 Configuration:
     * Queries wancfg.json; if VLAN 11 exists, ALWAYS sends serviceId=1 (Edit mode per wan.js,
     * never entry["idx"] which is 2 in production firmware) to editwan.json and sendwan.cmd.
     * If VLAN 11 does not exist, sends serviceId=0 to addwan.json and sendwan.cmd.
     */
    fun configureWanIdempotent(
        vlanId: Int,
        pppUser: String,
        pppPass: String,
        mtu: Int = 1492
    ): JSONObject {
        OntInputValidator.sanitizeInputMetacharacters(pppUser)
        OntInputValidator.sanitizeInputMetacharacters(pppPass)

        val (wanCode, wanBody) = try {
            postForm("/cgi-bin/luci/wancfg.json", "undefined&sessionKey=$sessionKey")
        } catch (e: IOException) {
            throw OntNetworkError("Lỗi mạng khi tra cứu danh sách WAN: ${e.message}", e)
        }
        val wanJson = parseJsonOrThrow("Tra cứu danh sách WAN (wancfg.json)", wanCode, wanBody)
        val wanArray = wanJson.optJSONArray("wanCfg")

        var existingIfName: String? = null
        if (wanArray != null) {
            for (i in 0 until wanArray.length()) {
                val item = wanArray.optJSONObject(i) ?: continue
                val vId = item.optString("vlanId", item.optString("vlanMuxId", ""))
                if (vId == vlanId.toString()) {
                    existingIfName = item.optString("ifcStr", "").ifBlank { "veip0.$vlanId" }
                    break
                }
            }
        }

        val encUser = OntInputValidator.urlEncodeFormValue(pppUser)
        val encPass = OntInputValidator.urlEncodeFormValue(pppPass)

        return if (existingIfName != null) {
            val editPayload =
                "serviceId=1&wanIfName=${OntInputValidator.urlEncodeFormValue(existingIfName)}&ntwkPrtcl=0&sessionKey=$sessionKey"
            val (editCode, editBody) = try {
                postForm("/cgi-bin/luci/editwan.json", editPayload)
            } catch (e: IOException) {
                throw OntNetworkError("Lỗi mạng khi gọi editwan.json: ${e.message}", e)
            }
            parseJsonOrThrow("Chuẩn bị sửa WAN (editwan.json)", editCode, editBody)

            val sendPayload =
                "enblEnetWan=0&serviceName=internet&vlanMuxId=$vlanId&vlanMuxPr=0&ntwkPrtcl=0" +
                    "&pppUserName=$encUser&pppPassword=$encPass&enblIpVer=0&wanAddr6Type=DHCP" +
                    "&ethMtu=$mtu&enblFirewall=1&enblNat=1&serviceId=1" +
                    "&wanIfName=${OntInputValidator.urlEncodeFormValue(existingIfName)}&sessionKey=$sessionKey"
            val (sendCode, sendBody) = try {
                postForm("/cgi-bin/luci/sendwan.cmd", sendPayload)
            } catch (e: IOException) {
                throw OntNetworkError("Lỗi mạng khi lưu cấu hình WAN (sendwan.cmd): ${e.message}", e)
            }
            parseJsonOrThrow("Lưu cấu hình WAN (sendwan.cmd)", sendCode, sendBody)
        } else {
            val addPayload = "undefined&sessionKey=$sessionKey&serviceId=0"
            val (addCode, addBody) = try {
                postForm("/cgi-bin/luci/addwan.json", addPayload)
            } catch (e: IOException) {
                throw OntNetworkError("Lỗi mạng khi gọi addwan.json: ${e.message}", e)
            }
            parseJsonOrThrow("Tạo mới giao diện WAN (addwan.json)", addCode, addBody)

            val sendPayload =
                "enblEnetWan=0&serviceName=internet&vlanMuxId=$vlanId&vlanMuxPr=0&ntwkPrtcl=0" +
                    "&pppUserName=$encUser&pppPassword=$encPass&enblIpVer=0&wanAddr6Type=DHCP" +
                    "&ethMtu=$mtu&enblFirewall=1&enblNat=1&serviceId=0&wanIfName=undefined&sessionKey=$sessionKey"
            val (sendCode, sendBody) = try {
                postForm("/cgi-bin/luci/sendwan.cmd", sendPayload)
            } catch (e: IOException) {
                throw OntNetworkError("Lỗi mạng khi lưu cấu hình WAN mới (sendwan.cmd): ${e.message}", e)
            }
            parseJsonOrThrow("Lưu cấu hình WAN mới (sendwan.cmd)", sendCode, sendBody)
        }
    }

    /**
     * Step 4: Configure GPON SLID / Password (<= 10 ASCII chars).
     */
    fun setGponPassword(slidValue: String): JSONObject {
        val validSlid = try {
            OntInputValidator.validateSlid(slidValue)
        } catch (e: IllegalArgumentException) {
            throw OntValidationError(e.message ?: "SLID không hợp lệ")
        }
        val encSlid = OntInputValidator.urlEncodeFormValue(validSlid)
        val payload = "sessionKey=$sessionKey&data=$encSlid"
        val (code, body) = try {
            postForm("/cgi-bin/luci/gponpasswd.json", payload)
        } catch (e: IOException) {
            throw OntNetworkError("Lỗi mạng khi cài đặt GPON SLID: ${e.message}", e)
        }
        return parseJsonOrThrow("Cài đặt GPON Password (gponpasswd.json)", code, body)
    }

    /**
     * Step 5: Configure LAN IPv4 (default 192.168.1.1).
     */
    fun configureLan(settings: GlobalSettings): JSONObject {
        val payload =
            "&sessionKey=$sessionKey&ethIpAddress=${settings.lanIp}&ethSubnetMask=${settings.lanMask}" +
                "&enblLanFirewall=0&dhcpEthStart=${settings.dhcpStart}&dhcpEthEnd=${settings.dhcpEnd}" +
                "&dhcpLeasedTime=${settings.dhcpLease}&enblDhcpSrv=1&dhcpClearLeaseTime=0" +
                "&enblLan2=0&brName=${settings.brName}"
        val (code, body) = try {
            postForm("/cgi-bin/luci/savelanv4cfg.cmd", payload)
        } catch (e: IOException) {
            throw OntNetworkError("Lỗi mạng khi lưu cấu hình LAN IPv4: ${e.message}", e)
        }
        return parseJsonOrThrow("Lưu cấu hình LAN IPv4 (savelanv4cfg.cmd)", code, body)
    }

    data class WifiApplyOutcome(
        val cfgSavedToNvram: Boolean,
        val droppedDuringRadioReload: Boolean
    )

    /**
     * Configure a Wi-Fi band (unit = 0 for 2.4GHz, unit = 1 for 5GHz):
     * 1. Sends `wirelesscfg.json` (`action=Apply&wl_unit=$unit...`) to write SSID & WPA-PSK into NVRAM.
     * 2. Sends `wlrestart.json` (`undefined&sessionKey=$sessionKey`) to commit/restart the radio driver
     *    (per [Red Team RT-02] and `app/core/ont_driver.py:325-333`, each band must be committed via
     *    `wlrestart.json` + settling delay so two `wirelesscfg.json` calls never collide in `wlconf`/`nas`).
     */
    suspend fun configureWifiBand(
        unit: Int,
        ssid: String,
        password: String,
        secMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
        isConnectedBandFinalStep: Boolean = false
    ): WifiApplyOutcome {
        val mode = WifiSecurityMode.fromFormValue(secMode)
            ?: throw OntValidationError("Chế độ bảo mật Wi-Fi '$secMode' không hợp lệ.")
        try {
            OntInputValidator.sanitizeInputMetacharacters(ssid)
            OntInputValidator.sanitizeInputMetacharacters(password)
        } catch (e: IllegalArgumentException) {
            throw OntValidationError(e.message ?: "Tham số Wi-Fi chứa ký tự không hợp lệ")
        }
        if (ssid.length !in 1..32 || ssid.trim().isEmpty() || !OntInputValidator.isPrintableAscii(ssid)) {
            throw OntValidationError("SSID Wi-Fi unit=$unit phải từ 1..32 ký tự ASCII in được và không chứa toàn dấu cách.")
        }
        if (!OntInputValidator.isPrintableAscii(password)) {
            throw OntValidationError("Mật khẩu Wi-Fi unit=$unit chỉ chấp nhận ký tự ASCII in được (0x20..0x7E).")
        }
        if (mode.requiresPassword && (password.length !in 8..63 || password.trim().isEmpty())) {
            throw OntValidationError("Mật khẩu Wi-Fi unit=$unit phải dài từ 8..63 ký tự ASCII và không chứa toàn dấu cách.")
        }

        val encSsid = OntInputValidator.urlEncodeFormValue(ssid)
        val encPass = OntInputValidator.urlEncodeFormValue(password)
        val encSec = OntInputValidator.urlEncodeFormValue(mode.formValue)
        val encKey = OntInputValidator.urlEncodeFormValue(sessionKey)

        val cfgPayload =
            "page=ssid.asp&action=Apply&wl_unit=$unit&wl_radio=1&wl_bssid=0&wl_bss_enabled=1" +
                "&wl_ssid=$encSsid&wl_bss_maxassoc=32&wl_closed=0&sec_mode=$encSec" +
                "&wl_wpa_psk=$encPass&wl_mode=null&wl_auth=0&wl_auth_mode=radius&wl_osenen=0" +
                "&wl_akm=&wl_akm_wpa=disabled&wl_akm_psk=${mode.akmPsk}&wl_akm_wpa2=disabled&wl_akm_psk2=${mode.akmPsk2}" +
                "&wl_akm_brcm_psk=disabled&wl_preauth=disabled&wl_wep=disabled&wl_crypto=${mode.crypto}" +
                "&wl_radius_ipaddr=0.0.0.0&wl_radius_port=1812&wl_radius_key=1234&sessionKey=$encKey"

        var cfgSavedToNvram = false
        var droppedDuringRadioReload = false

        try {
            val (cfgCode, cfgBody) = postForm("/cgi-bin/luci/wirelesscfg.json", cfgPayload)
            if (cfgCode == 200 && cfgBody.trim().startsWith("<")) {
                sessionKey = ""
                throw OntNetworkError(
                    "Phiên làm việc ONT hết hạn (trang HTML) khi cài đặt Wi-Fi unit=$unit, cần xác thực lại."
                )
            }
            val cfgJson = parseJsonOrThrow("Cài đặt Wi-Fi unit=$unit (wirelesscfg.json)", cfgCode, cfgBody)
            val cfgStatus = cfgJson.optString("status", "").trim()
            if (cfgStatus.equals("fail authen", ignoreCase = true)) {
                sessionKey = ""
                throw OntNetworkError(
                    "Phiên làm việc ONT hết hạn (status=fail authen) khi cài đặt Wi-Fi unit=$unit, cần xác thực lại."
                )
            }
            if (cfgStatus == "conflict_mesh") {
                throw OntDriverException(
                    "ONT đang chạy Mesh nên không cho phép đổi chế độ bảo mật Wi-Fi sang Open " +
                        "(chỉ hỗ trợ WPA2-PSK hoặc WPA-PSK/WPA2-PSK)."
                )
            }
            if (cfgStatus.isNotEmpty() && cfgStatus != "OK") {
                throw OntDriverException("Cài đặt Wi-Fi unit=$unit bị từ chối (status=$cfgStatus)")
            }
            cfgSavedToNvram = true
        } catch (e: IOException) {
            if (isConnectedBandFinalStep && isPostFlushRadioDrop(e)) {
                droppedDuringRadioReload = true
            } else if (isPreSendConnectFailure(e)) {
                throw OntNetworkError("Không thể kết nối tới ONT khi gửi cấu hình Wi-Fi unit=$unit: ${e.message}", e)
            } else {
                throw OntNetworkError("Lỗi mạng khi gửi cấu hình Wi-Fi unit=$unit: ${e.message}", e)
            }
        }

        if (!droppedDuringRadioReload) {
            try {
                val (resCode, resBody) = postForm(
                    path = "/cgi-bin/luci/wlrestart.json",
                    rawPayload = "undefined&sessionKey=$sessionKey",
                    useShortLogoutTimeout = isConnectedBandFinalStep
                )
                val resJson = parseJsonOrThrow("Khởi động lại driver Wi-Fi (wlrestart.json)", resCode, resBody)

                // Wait for _wlrestart_ == "0" per wireless.js get_loading_data() (30 x 1000ms in production)
                if (!isConnectedBandFinalStep && maxWlRestartPolls > 0 && resJson.optString("_wlrestart_", "0") != "0") {
                    for (pollAttempt in 1..maxWlRestartPolls) {
                        if (wlRestartPollIntervalMs > 0L) {
                            delay(wlRestartPollIntervalMs)
                        }
                        val (pCode, pBody) = postForm(
                            "/cgi-bin/luci/wlrestart.json",
                            "undefined&sessionKey=$sessionKey"
                        )
                        val pJson = parseJsonOrThrow("Kiểm tra wlrestart.json", pCode, pBody)
                        if (pJson.optString("_wlrestart_", "0") == "0") break
                    }
                }
            } catch (e: IOException) {
                if (isConnectedBandFinalStep || cfgSavedToNvram) {
                    droppedDuringRadioReload = true
                } else {
                    throw OntNetworkError("Lỗi mạng khi gọi wlrestart.json cho unit=$unit: ${e.message}", e)
                }
            } catch (e: OntDriverException) {
                if (cfgSavedToNvram) {
                    droppedDuringRadioReload = true
                } else {
                    throw e
                }
            }
        }
        return WifiApplyOutcome(
            cfgSavedToNvram = cfgSavedToNvram,
            droppedDuringRadioReload = droppedDuringRadioReload
        )
    }

    private fun normalizeWlInterface(rawIfc: String): String {
        val lower = rawIfc.trim().lowercase()
        return when {
            lower.startsWith("wl0") -> "wl0"
            lower.startsWith("wl1") -> "wl1"
            else -> ""
        }
    }

    private fun optPipeRow(array: JSONArray, index: Int): String =
        array.optJSONArray(index)?.optString(0) ?: array.optString(index, "")

    private fun postOptionalSessionJson(path: String): JSONObject? {
        return try {
            val encKey = OntInputValidator.urlEncodeFormValue(sessionKey)
            val (code, body) = postForm(
                path = path,
                rawPayload = "undefined&sessionKey=$encKey",
                useShortLogoutTimeout = false
            )
            val trimmed = body.trim()
            if (code != 200 || trimmed.isEmpty() || trimmed.startsWith("<")) return null
            JSONObject(trimmed).also { json ->
                val rotated = json.optString("sessionKey", "").trim()
                if (isValidSessionToken(rotated)) {
                    updateSessionKey(rotated)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (hasTlsCertMismatchCause(e)) {
                throw OntDriverException(
                    "Chứng chỉ bảo mật TLS của ONT ở Lần kết nối 2 không khớp với chứng chỉ đã ghim ở Lần kết nối 1 khi đọc $path — nghi ngờ bắt nhầm Wi-Fi khác.",
                    e
                )
            }
            null
        }
    }

    internal data class ConnectedBandResolution(
        val connectedBand: WifiBand,
        val ontBaseMac: String?,
        val wl0Bssid: String? = null,
        val wl1Bssid: String? = null
    )

    /**
     * [Red Team RT-02] Server-side connected band & ONT identity resolution immediately after Step 2:
     * Queries `dhcpclient.json` when `localIpv4` is present (preferring live `wlAssociatedDevice`)
     * and UNCONDITIONALLY queries `deviceinfo2.json` to capture trimmed `baseMacAddress` plus
     * per-radio `wl0`/`wl1` BSSIDs from `wirelessInfoList`.
     */
    fun resolveConnectedBandFromOnt(
        localIpv4: String?,
        fallbackBand: WifiBand,
        bssid: String? = null
    ): Pair<WifiBand, String?> {
        val detail = resolveConnectedBandFromOntInternal(localIpv4, fallbackBand, bssid)
        return detail.connectedBand to detail.ontBaseMac
    }

    private fun resolveConnectedBandFromOntInternal(
        localIpv4: String?,
        fallbackBand: WifiBand,
        bssid: String? = null
    ): ConnectedBandResolution {
        if (!isValidSessionToken(sessionKey)) {
            return ConnectedBandResolution(fallbackBand, null)
        }
        val cleanIp = localIpv4?.trim().orEmpty()
        val hasValidSubnetIp = cleanIp.startsWith("192.168.1.") && cleanIp != "192.168.1.1"

        var matchedWlIfc = ""
        if (hasValidSubnetIp) {
            postOptionalSessionJson("/cgi-bin/luci/dhcpclient.json")?.let { dhcpJson ->
                var clientMac = ""
                dhcpJson.optJSONArray("dhcpInfoList")?.let { dhcpArr ->
                    for (i in 0 until dhcpArr.length()) {
                        val parts = optPipeRow(dhcpArr, i).split("|")
                        if (parts.getOrNull(0)?.trim() == cleanIp) {
                            clientMac = parts.getOrNull(3)?.trim().orEmpty()
                            break
                        }
                    }
                }

                var exactIpIfc = ""
                var macFallbackIfc = ""
                dhcpJson.optJSONArray("wlAssociatedDevice")?.let { assocArr ->
                    for (i in 0 until assocArr.length()) {
                        val assocParts = optPipeRow(assocArr, i).split("|")
                        val assocMac = assocParts.getOrNull(0)?.trim().orEmpty()
                        val assocIp = assocParts.getOrNull(1)?.trim().orEmpty()
                        val rowIfc = normalizeWlInterface(assocParts.getOrNull(3).orEmpty()).ifEmpty {
                            assocParts.firstNotNullOfOrNull { token ->
                                normalizeWlInterface(token).takeIf { it.isNotEmpty() }
                            }.orEmpty()
                        }
                        if (rowIfc.isEmpty()) continue
                        if (cleanIp.isNotEmpty() && assocIp == cleanIp && exactIpIfc.isEmpty()) {
                            exactIpIfc = rowIfc
                        }
                        if (clientMac.isNotEmpty() && assocMac.equals(clientMac, ignoreCase = true) && macFallbackIfc.isEmpty()) {
                            macFallbackIfc = rowIfc
                        }
                    }
                }
                matchedWlIfc = exactIpIfc.ifEmpty { macFallbackIfc }
            }
        }

        var ontBaseMac: String? = null
        var wl0Bssid: String? = null
        var wl1Bssid: String? = null
        var resolvedFromDeviceInfo: WifiBand? = null

        var devJson: JSONObject? = null
        for (attempt in 1..3) {
            devJson = postOptionalSessionJson("/cgi-bin/luci/deviceinfo2.json")
            if (devJson != null) break
            if (attempt < 3) {
                try {
                    Thread.sleep(minOf(settlingDelayMs.coerceAtLeast(5L), 150L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        devJson?.let { devJson ->
            val baseMac = devJson.optString("baseMacAddress", "").trim().ifEmpty { null }
            ontBaseMac = baseMac
            if (baseMac != null && bssid.isNullOrBlank() && currentPass.isNotEmpty()) {
                bssidPasswordCache[baseMac.uppercase()] = currentPass
            }
            devJson.optJSONArray("wirelessInfoList")?.let { wlList ->
                for (i in 0 until wlList.length()) {
                    val entryStr = optPipeRow(wlList, i)
                    if (entryStr.isBlank()) continue
                    val subData = entryStr.split("|").map { it.trim() }
                    val entryIfc = normalizeWlInterface(subData.getOrNull(0).orEmpty())
                    val radioBssid = subData.getOrNull(5).orEmpty().takeIf { it.contains(":") }
                    if (entryIfc == "wl0" && radioBssid != null) wl0Bssid = radioBssid
                    if (entryIfc == "wl1" && radioBssid != null) wl1Bssid = radioBssid
                    if (matchedWlIfc.isNotEmpty() && entryIfc == matchedWlIfc && resolvedFromDeviceInfo == null) {
                        val chanDigits = CHANNEL_LEADING_DIGITS_REGEX
                            .find(subData.getOrNull(3).orEmpty())
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0
                        resolvedFromDeviceInfo = when {
                            chanDigits in 1..14 -> WifiBand.BAND_2_4_GHZ
                            chanDigits >= 36 -> WifiBand.BAND_5_GHZ
                            entryIfc == "wl1" -> WifiBand.BAND_5_GHZ
                            else -> WifiBand.BAND_2_4_GHZ
                        }
                    }
                }
            }
        }

        val b24 = wl0Bssid
        val b5 = wl1Bssid
        val bssidResolvedBand = if (!bssid.isNullOrBlank()) {
            when {
                b24 != null && OntWifiConnector.isSameOntBssid(bssid, b24) -> WifiBand.BAND_2_4_GHZ
                b5 != null && OntWifiConnector.isSameOntBssid(bssid, b5) -> WifiBand.BAND_5_GHZ
                else -> null
            }
        } else {
            null
        }

        val finalBand = bssidResolvedBand ?: resolvedFromDeviceInfo ?: when (matchedWlIfc) {
            "wl1" -> WifiBand.BAND_5_GHZ
            "wl0" -> WifiBand.BAND_2_4_GHZ
            else -> fallbackBand
        }
        return ConnectedBandResolution(
            connectedBand = finalBand,
            ontBaseMac = ontBaseMac,
            wl0Bssid = wl0Bssid,
            wl1Bssid = wl1Bssid
        )
    }

    /**
     * [Red Team RT-01] Authenticate Connection 2 strictly with `newAdminPass` ONLY — NEVER falling
     * back to `adminDefaultPass` ("admin"), preventing 180s hardware lockout on socket resets.
     */
    private suspend fun authenticateSecondConnection(
        newAdminPass: String,
        previousSessionKey: String,
        userExistWaitBudgetMs: Long = 65_000L
    ): String {
        fun ensureTransportAlive(stage: String) {
            if (isNetworkTransportAlive?.invoke() == false) {
                throw OntNetworkError("Mất liên kết Wi-Fi (onLost) trong giai đoạn $stage ở Lần kết nối 2.")
            }
        }

        fun attemptNewPassLoginOnce(): String {
            ensureTransportAlive("gửi login.cmd")
            val loginData = login("admin", newAdminPass)
            val status = loginData.optString("status", "").trim()
            val firstLog = loginData.optString("firstlog", "").trim()
            if (firstLog == "1" || status.equals("FAIL", ignoreCase = true) || !isValidSessionToken(sessionKey)) {
                throw OntAuthenticationError(
                    "Đăng nhập Lần kết nối 2 bằng mật khẩu Admin mới thất bại (status=$status, firstlog=$firstLog). " +
                        "Dừng ngay để tránh khóa ONT — hãy kiểm tra xem điện thoại có bắt nhầm ONT khác không."
                )
            }
            return sessionKey
        }

        try {
            return attemptNewPassLoginOnce()
        } catch (e: OntAccountLockoutError) {
            throw e
        } catch (e: OntAccountLockoutWarning) {
            throw e
        } catch (e: OntAuthenticationError) {
            throw e
        } catch (ne: OntNetworkError) {
            if (hasTlsCertMismatchCause(ne)) {
                throw OntDriverException("Chứng chỉ TLS không khớp với ONT ở Lần kết nối 1.", ne)
            }
            throw ne
        } catch (_: OntUserExistError) {
            ensureTransportAlive("xử lý user exist")
            logDiag(LogLevel.WARN, "AUTH", "Phát hiện 'user exist' ở Lần 2 — thử giải phóng phiên cũ và chờ TTL.")
            var lastNetworkError: OntNetworkError? = null
            var sawLiveUserExist = true
            val keyToRelease = previousSessionKey.ifEmpty { lastActiveSessionKey }
            if (isValidSessionToken(keyToRelease)) {
                sessionKey = keyToRelease
                logout()
                ensureTransportAlive("sau logout phiên cũ")
                try {
                    return attemptNewPassLoginOnce()
                } catch (e2: OntAccountLockoutError) {
                    throw e2
                } catch (e2: OntAccountLockoutWarning) {
                    throw e2
                } catch (e2: OntAuthenticationError) {
                    throw e2
                } catch (_: OntUserExistError) {
                    sawLiveUserExist = true
                    lastNetworkError = null
                } catch (ne: OntNetworkError) {
                    if (hasTlsCertMismatchCause(ne)) {
                        throw OntDriverException("Chứng chỉ TLS không khớp với ONT ở Lần kết nối 1.", ne)
                    }
                    ensureTransportAlive("lỗi mạng sau logout phiên cũ")
                    lastNetworkError = ne
                    sawLiveUserExist = false
                }
            }

            val deadlineMs = monotonicMillis() + userExistWaitBudgetMs.coerceAtLeast(userExistPollIntervalMs)
            while (monotonicMillis() < deadlineMs) {
                ensureTransportAlive("chờ giải phóng phiên user exist")
                val sleepMs = minOf(
                    userExistPollIntervalMs.coerceAtLeast(10L),
                    (deadlineMs - monotonicMillis()).coerceAtLeast(10L)
                )
                delay(sleepMs)
                ensureTransportAlive("trước khi thử lại login.cmd")
                try {
                    return attemptNewPassLoginOnce()
                } catch (_: OntUserExistError) {
                    sawLiveUserExist = true
                    lastNetworkError = null
                    continue
                } catch (ne: OntNetworkError) {
                    if (hasTlsCertMismatchCause(ne)) {
                        throw OntDriverException("Chứng chỉ TLS không khớp với ONT ở Lần kết nối 1.", ne)
                    }
                    ensureTransportAlive("rớt mạng khi chờ user exist")
                    lastNetworkError = ne
                    sawLiveUserExist = false
                    continue
                }
            }
            if (lastNetworkError != null && !sawLiveUserExist) {
                throw lastNetworkError
            }
            throw OntUserExistError(
                "Tài khoản admin ONT vẫn bận phiên cũ (user exist) sau khi chờ ${userExistWaitBudgetMs / 1000}s ở Lần kết nối 2."
            )
        }
    }

    /**
     * [Red Team RT-01 & RT-03] Readiness & session synchronization gate on Secondary Wi-Fi (`Band B`):
     * Rebinds exclusively to `Band B` (rejecting null SocketFactory when `onRebindWifiNetwork` is set),
     * verifies pinned TLS + ONT HTML signature, ensures `_wlrestart_ == "0"`, and re-authenticates
     * ONLY via `authenticateSecondConnection(settings.adminNewPass, ...)`.
     */
    private suspend fun ensureGatewayReadyOnSecondaryBand(
        settings: GlobalSettings,
        timeoutBudgetMs: Long
    ): Boolean {
        val effectiveBudget = timeoutBudgetMs.coerceAtLeast(timeoutMs)
        val deadlineMs = monotonicMillis() + effectiveBudget
        val stepDelayMs = recoveryRetryDelayMs.coerceAtLeast(20L)

        while (monotonicMillis() < deadlineMs) {
            if (onRebindWifiNetwork != null) {
                val newSf = try {
                    onRebindWifiNetwork.invoke()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
                if (newSf == null) {
                    val waitRebind = minOf(stepDelayMs, (deadlineMs - monotonicMillis()).coerceAtLeast(10L))
                    delay(waitRebind)
                    continue
                }
                updateSocketFactory(newSf)
            }

            if (probeGateway(failOnCertMismatch = true)) {
                val activeKey = sessionKey
                val httpResult = if (isValidSessionToken(activeKey)) {
                    try {
                        val encKey = OntInputValidator.urlEncodeFormValue(activeKey)
                        postForm("/cgi-bin/luci/wlrestart.json", "undefined&sessionKey=$encKey")
                    } catch (e: IOException) {
                        if (hasTlsCertMismatchCause(e)) {
                            throw OntDriverException("Chứng chỉ TLS ONT không khớp khi đồng bộ Lần 2.", e)
                        }
                        null
                    }
                } else {
                    null
                }

                val wlJson = httpResult?.let { (code, body) ->
                    val trimmed = body.trim()
                    if (code == 200 && trimmed.isNotEmpty() && !trimmed.startsWith("<")) {
                        try {
                            JSONObject(trimmed)
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                val statusStr = wlJson?.optString("status", "")?.trim().orEmpty()
                if (wlJson == null || statusStr.equals("fail authen", ignoreCase = true)) {
                    val remainingAuthMs = (deadlineMs - monotonicMillis()).coerceAtLeast(userExistPollIntervalMs)
                    try {
                        authenticateSecondConnection(
                            newAdminPass = settings.adminNewPass,
                            previousSessionKey = lastActiveSessionKey,
                            userExistWaitBudgetMs = remainingAuthMs
                        )
                        val postAuthCheck = try {
                            val encFresh = OntInputValidator.urlEncodeFormValue(sessionKey)
                            postForm("/cgi-bin/luci/wlrestart.json", "undefined&sessionKey=$encFresh")
                        } catch (e: IOException) {
                            if (hasTlsCertMismatchCause(e)) {
                                throw OntDriverException("Chứng chỉ TLS ONT không khớp sau xác thực Lần 2.", e)
                            }
                            null
                        }
                        val postAuthRestart = postAuthCheck?.let { (c, b) ->
                            val t = b.trim()
                            if (c == 200 && t.isNotEmpty() && !t.startsWith("<")) {
                                runCatching { JSONObject(t).optString("_wlrestart_", "0").trim() }.getOrDefault("0")
                            } else {
                                "0"
                            }
                        } ?: "0"
                        if (postAuthRestart != "0") {
                            val waitRestart = minOf(
                                wlRestartPollIntervalMs.coerceAtLeast(10L),
                                (deadlineMs - monotonicMillis()).coerceAtLeast(10L)
                            )
                            delay(waitRestart)
                            continue
                        }
                        return true
                    } catch (e: OntAccountLockoutError) {
                        throw e
                    } catch (e: OntAccountLockoutWarning) {
                        throw e
                    } catch (e: OntAuthenticationError) {
                        throw e
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (de: OntDriverException) {
                        if (de !is OntNetworkError && de !is OntUserExistError) throw de
                    }
                } else if (wlJson.optString("_wlrestart_", "0").trim() != "0") {
                    val pollSleep = minOf(
                        wlRestartPollIntervalMs.coerceAtLeast(10L),
                        (deadlineMs - monotonicMillis()).coerceAtLeast(10L)
                    )
                    delay(pollSleep)
                    continue
                } else {
                    return true
                }
            }

            val remaining = deadlineMs - monotonicMillis()
            if (remaining > 0L) {
                delay(minOf(stepDelayMs, remaining))
            }
        }
        return false
    }

    /**
     * [Red Team RT-02] Verify ground-truth ONT identity (`baseMacAddress`) and live wireless interface
     * (`wlAssociatedDevice` == `expectedBand`, never falling back to stale `dhcpInfoList`) on Connection 2.
     */
    private suspend fun verifySecondConnectionBandAndMacWithRetry(
        localIpv4: String?,
        expectedBand: WifiBand,
        expectedOntBaseMac: String?,
        isSameSsidBothBands: Boolean = false
    ) {
        val pollBudget = bandVerifyPollTimeoutMs.coerceAtLeast(20L)
        val pollInterval = bandVerifyPollIntervalMs.coerceAtLeast(10L)
        val deadlineMs = monotonicMillis() + pollBudget
        var consecutiveWrongBandCount = 0
        var lastConvergenceError: OntNetworkError? = null
        var attempt = 0

        while (true) {
            attempt++
            if (isNetworkTransportAlive?.invoke() == false) {
                throw OntNetworkError("Mất liên kết Wi-Fi trong lúc xác thực bảng trạm Lần kết nối 2.")
            }
            try {
                verifySecondConnectionBandAndMac(
                    localIpv4 = localIpv4,
                    expectedBand = expectedBand,
                    expectedOntBaseMac = expectedOntBaseMac,
                    isSameSsidBothBands = isSameSsidBothBands
                )
                logDiag(
                    LogLevel.INFO,
                    "VERIFY",
                    "Xác thực thành công băng tần ${expectedBand.label} & MAC=${expectedOntBaseMac ?: "N/A"} (lượt poll #$attempt)."
                )
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (wrongBand: StillOnPrimaryBandException) {
                consecutiveWrongBandCount++
                lastConvergenceError = wrongBand
                logDiag(
                    LogLevel.WARN,
                    "VERIFY",
                    "Lượt poll #$attempt thấy vẫn ở băng tần cũ (${consecutiveWrongBandCount}/2): ${wrongBand.message}"
                )
                if (consecutiveWrongBandCount >= 2 || monotonicMillis() >= deadlineMs) {
                    throw wrongBand
                }
            } catch (ne: OntNetworkError) {
                consecutiveWrongBandCount = 0
                lastConvergenceError = ne
                logDiag(
                    LogLevel.INFO,
                    "VERIFY",
                    "Bảng trạm / thông tin ONT đang cập nhật ở lượt poll #$attempt: ${ne.message}"
                )
                if (monotonicMillis() >= deadlineMs) {
                    if (!isSameSsidBothBands) {
                        logDiag(
                            LogLevel.INFO,
                            "VERIFY",
                            "2 Wi-Fi khác tên và ONT phản hồi đúng MAC $expectedOntBaseMac — xác nhận đã chuyển sang ${expectedBand.label} thành công sau khi hết thời gian chờ bảng trạm."
                        )
                        return
                    }
                    throw ne
                }
            }

            val remainingMs = deadlineMs - monotonicMillis()
            if (remainingMs <= 0L) break
            delay(minOf(pollInterval, remainingMs))
        }

        throw lastConvergenceError ?: OntNetworkError(
            "Chưa xác thực được bảng kết nối wlAssociatedDevice của băng tần ${expectedBand.label} sau ${pollBudget}ms."
        )
    }

    private fun verifySecondConnectionBandAndMac(
        localIpv4: String?,
        expectedBand: WifiBand,
        expectedOntBaseMac: String?,
        isSameSsidBothBands: Boolean = false
    ) {
        val ifcBandMap = mutableMapOf<String, WifiBand>()
        val devJson = postOptionalSessionJson("/cgi-bin/luci/deviceinfo2.json")
        val actualBaseMac = devJson?.optString("baseMacAddress", "")?.trim()?.ifEmpty { null }
        if (!expectedOntBaseMac.isNullOrBlank()) {
            if (!actualBaseMac.isNullOrBlank() && !actualBaseMac.equals(expectedOntBaseMac.trim(), ignoreCase = true)) {
                throw OntDriverException(
                    "Địa chỉ MAC của ONT ở Lần kết nối 2 ($actualBaseMac) không khớp với ONT ban đầu ($expectedOntBaseMac) — nghi ngờ bắt nhầm ONT khác."
                )
            }
            if (actualBaseMac == null) {
                throw MissingDeviceInfoException(
                    "Chưa đọc được baseMacAddress từ deviceinfo2.json ở Lần kết nối 2 để đối chiếu với ONT ban đầu ($expectedOntBaseMac)."
                )
            }
        }
        devJson?.optJSONArray("wirelessInfoList")?.let { wlList ->
            for (i in 0 until wlList.length()) {
                val entryStr = optPipeRow(wlList, i)
                if (entryStr.isBlank()) continue
                val subData = entryStr.split("|").map { it.trim() }
                val entryIfc = normalizeWlInterface(subData.getOrNull(0).orEmpty())
                if (entryIfc.isEmpty()) continue
                val chanDigits = CHANNEL_LEADING_DIGITS_REGEX
                    .find(subData.getOrNull(3).orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: 0
                ifcBandMap[entryIfc] = when {
                    chanDigits in 1..14 -> WifiBand.BAND_2_4_GHZ
                    chanDigits >= 36 -> WifiBand.BAND_5_GHZ
                    entryIfc == "wl1" -> WifiBand.BAND_5_GHZ
                    else -> WifiBand.BAND_2_4_GHZ
                }
            }
        }

        val dhcpJson = postOptionalSessionJson("/cgi-bin/luci/dhcpclient.json")
            ?: throw OntNetworkError("Không đọc được bảng kết nối Wi-Fi (dhcpclient.json) ở Lần kết nối 2.")

        val assocArr = dhcpJson.optJSONArray("wlAssociatedDevice")
            ?: throw OntNetworkError(
                "Bảng wlAssociatedDevice chưa sẵn sàng ở Lần kết nối 2 để xác thực Wi-Fi ${expectedBand.label}."
            )

        val cleanIp = localIpv4?.trim().orEmpty()
        var clientMac = ""
        if (cleanIp.isNotEmpty()) {
            dhcpJson.optJSONArray("dhcpInfoList")?.let { dhcpArr ->
                for (i in 0 until dhcpArr.length()) {
                    val parts = optPipeRow(dhcpArr, i).split("|")
                    if (parts.getOrNull(0)?.trim() == cleanIp) {
                        clientMac = parts.getOrNull(3)?.trim().orEmpty()
                        break
                    }
                }
            }
        }

        var matchedAssocIfc = ""
        // Pass 1: exact active IP match (eliminates stale association rows from previous connections)
        if (cleanIp.isNotEmpty()) {
            for (i in 0 until assocArr.length()) {
                val assocParts = optPipeRow(assocArr, i).split("|")
                val assocIp = assocParts.getOrNull(1)?.trim().orEmpty()
                val rowIfc = normalizeWlInterface(assocParts.getOrNull(3).orEmpty()).ifEmpty {
                    assocParts.firstNotNullOfOrNull { token ->
                        normalizeWlInterface(token).takeIf { it.isNotEmpty() }
                    }.orEmpty()
                }
                if (rowIfc.isNotEmpty() && assocIp == cleanIp) {
                    matchedAssocIfc = rowIfc
                    break
                }
            }
        }
        // Pass 2: MAC match, preferring expectedBand if multiple entries exist
        if (matchedAssocIfc.isEmpty() && clientMac.isNotEmpty()) {
            var preferredIfc = ""
            var fallbackIfc = ""
            for (i in 0 until assocArr.length()) {
                val assocParts = optPipeRow(assocArr, i).split("|")
                val assocMac = assocParts.getOrNull(0)?.trim().orEmpty()
                val rowIfc = normalizeWlInterface(assocParts.getOrNull(3).orEmpty()).ifEmpty {
                    assocParts.firstNotNullOfOrNull { token ->
                        normalizeWlInterface(token).takeIf { it.isNotEmpty() }
                    }.orEmpty()
                }
                if (rowIfc.isNotEmpty() && assocMac.equals(clientMac, ignoreCase = true)) {
                    val band = ifcBandMap[rowIfc] ?: when (rowIfc) {
                        "wl1" -> WifiBand.BAND_5_GHZ
                        else -> WifiBand.BAND_2_4_GHZ
                    }
                    if (band == expectedBand) {
                        preferredIfc = rowIfc
                        break
                    }
                    if (fallbackIfc.isEmpty()) {
                        fallbackIfc = rowIfc
                    }
                }
            }
            if (preferredIfc.isNotEmpty()) {
                matchedAssocIfc = preferredIfc
            } else if (isSameSsidBothBands && fallbackIfc.isNotEmpty()) {
                matchedAssocIfc = fallbackIfc
            }
        }
        // Pass 3: single entry fallback
        if (matchedAssocIfc.isEmpty() && assocArr.length() == 1) {
            val assocParts = optPipeRow(assocArr, 0).split("|")
            val singleIfc = normalizeWlInterface(assocParts.getOrNull(3).orEmpty()).ifEmpty {
                assocParts.firstNotNullOfOrNull { token ->
                    normalizeWlInterface(token).takeIf { it.isNotEmpty() }
                }.orEmpty()
            }
            if (singleIfc.isNotEmpty()) {
                val band = ifcBandMap[singleIfc] ?: when (singleIfc) {
                    "wl1" -> WifiBand.BAND_5_GHZ
                    else -> WifiBand.BAND_2_4_GHZ
                }
                if (band == expectedBand || isSameSsidBothBands) {
                    matchedAssocIfc = singleIfc
                }
            }
        }
        if (matchedAssocIfc.isEmpty()) {
            throw OntNetworkError(
                "Chưa thấy thiết bị trong bảng kết nối trực tiếp wlAssociatedDevice của băng tần ${expectedBand.label}."
            )
        }

        val liveBand = ifcBandMap[matchedAssocIfc] ?: when (matchedAssocIfc) {
            "wl1" -> WifiBand.BAND_5_GHZ
            else -> WifiBand.BAND_2_4_GHZ
        }
        if (liveBand != expectedBand) {
            if (isSameSsidBothBands) {
                throw StillOnPrimaryBandException(
                    "Thiết bị vẫn đang kết nối trên băng tần ${liveBand.label} ($matchedAssocIfc) thay vì băng tần ${expectedBand.label}. " +
                        "Hãy kiểm tra lại Wi-Fi vừa ghép nối (đặc biệt khi 2 băng tần đặt trùng tên)."
                )
            } else {
                throw OntNetworkError(
                    "Bảng kết nối trực tiếp wlAssociatedDevice chưa cập nhật sang băng tần ${expectedBand.label} ($matchedAssocIfc)."
                )
            }
        }
    }

    /**
     * [Red Team RT-02] Read-only NVRAM SSID query for `wl_unit` (`0` or `1`) without `action=Apply`.
     */
    private fun readConfiguredBandSsidFromNvram(unit: Int): String? {
        if (!isValidSessionToken(sessionKey)) return null
        return try {
            val encKey = OntInputValidator.urlEncodeFormValue(sessionKey)
            val payload = "page=ssid.asp&wl_unit=$unit&wl_bssid=0&sessionKey=$encKey"
            val (code, body) = postForm(
                path = "/cgi-bin/luci/wirelesscfg.json",
                rawPayload = payload,
                useShortLogoutTimeout = false,
                referer = "$cleanBaseUrl/wireless.html"
            )
            val trimmed = body.trim()
            if (code != 200 || trimmed.isEmpty() || trimmed.startsWith("<")) return null
            val json = JSONObject(trimmed)
            val rotated = json.optString("sessionKey", "").trim()
            if (isValidSessionToken(rotated)) {
                updateSessionKey(rotated)
            }
            if (json.has("nvramWlSsid")) json.optString("nvramWlSsid", "") else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun commitRadioReloadBestEffort() {
        try {
            val (resCode, resBody) = postForm(
                path = "/cgi-bin/luci/wlrestart.json",
                rawPayload = "undefined&sessionKey=$sessionKey",
                useShortLogoutTimeout = false
            )
            val resJson = parseJsonOrThrow("Khởi động lại driver Wi-Fi (wlrestart.json)", resCode, resBody)
            if (maxWlRestartPolls > 0 && resJson.optString("_wlrestart_", "0") != "0") {
                for (pollAttempt in 1..maxWlRestartPolls) {
                    if (wlRestartPollIntervalMs > 0L) delay(wlRestartPollIntervalMs)
                    val (pCode, pBody) = postForm("/cgi-bin/luci/wlrestart.json", "undefined&sessionKey=$sessionKey")
                    val pJson = parseJsonOrThrow("Kiểm tra wlrestart.json", pCode, pBody)
                    if (pJson.optString("_wlrestart_", "0") == "0") break
                }
            }
        } catch (_: Exception) {
            // NVRAM already confirmed saved via readConfiguredBandSsidFromNvram
        }
    }
    /**
     * [Red Team RT-03 & RT-04] Upload and apply ONT firmware via 3-step LuCI CGI sequence:
     * 1. POST /cgi-bin/luci/updatefwsessioncheck.json -> {"status": "OK"}
     * 2. POST /cgi-bin/luci/imagesize.json -> {"ramFree": "...", "sessionKey": "..."}
     * 3. POST /cgi-bin/luci/uploadsoftware.conf (multipart/form-data: file, sessionKey) -> {"status": "valid"}
     */
    fun updateFirmware(
        firmwareFile: File,
        uploadTimeoutMs: Long = 180_000L,
        onProgressPercent: ((Int) -> Unit)? = null
    ): JSONObject {
        if (!firmwareFile.exists() || !firmwareFile.isFile) {
            throw OntValidationError("File firmware không tồn tại: '${firmwareFile.path}'")
        }
        if (!OntInputValidator.isValidFirmwareExtension(firmwareFile.name)) {
            throw OntValidationError(
                "Định dạng file firmware không hợp lệ (chỉ hỗ trợ .signed, .bin, .img): '${firmwareFile.name}'"
            )
        }
        val fileSize = firmwareFile.length()
        if (
            fileSize < OntInputValidator.MIN_FIRMWARE_SIZE_BYTES ||
            fileSize > OntInputValidator.MAX_FIRMWARE_SIZE_BYTES
        ) {
            throw OntValidationError("Dung lượng file firmware không hợp lệ ($fileSize bytes): '${firmwareFile.name}'")
        }
        val safeFilename = OntInputValidator.sanitizeFirmwareFilename(firmwareFile.name)
        val refererUrl = "$cleanBaseUrl/update_software.html"

        // 1. Check firmware update session
        val encKey1 = OntInputValidator.urlEncodeFormValue(sessionKey)
        val (chkCode, chkBody) = try {
            postFormWithReferer(
                path = "/cgi-bin/luci/updatefwsessioncheck.json",
                rawPayload = "sessionKey=$encKey1",
                referer = refererUrl
            )
        } catch (e: IOException) {
            throw OntNetworkError("Lỗi mạng khi kiểm tra phiên nâng cấp firmware: ${e.message}", e)
        }
        val chkData = parseJsonOrThrow("Kiểm tra phiên nâng cấp firmware (updatefwsessioncheck.json)", chkCode, chkBody)
        val chkRotatedKey = chkData.optString("sessionKey", "").trim()
        if (chkRotatedKey.isNotEmpty() && chkRotatedKey != "0") {
            updateSessionKey(chkRotatedKey)
        }
        if (chkData.optString("status") != "OK") {
            throw OntDriverException(
                "Phiên nâng cấp firmware không hợp lệ (status=${chkData.optString("status")})."
            )
        }

        // 2. Query available RAM size (KB)
        val encKey2 = OntInputValidator.urlEncodeFormValue(sessionKey)
        val (imgCode, imgBody) = try {
            postFormWithReferer(
                path = "/cgi-bin/luci/imagesize.json",
                rawPayload = "sessionKey=$encKey2",
                referer = refererUrl
            )
        } catch (e: IOException) {
            throw OntNetworkError("Lỗi mạng khi kiểm tra dung lượng RAM trống của ONT: ${e.message}", e)
        }
        val imgData = parseJsonOrThrow("Kiểm tra dung lượng RAM trống (imagesize.json)", imgCode, imgBody)
        val rotatedKey = imgData.optString("sessionKey", "").trim()
        if (rotatedKey.isNotEmpty() && rotatedKey != "0") {
            updateSessionKey(rotatedKey)
        }
        val ramFreeStr = imgData.optString("ramFree", "").trim()
        val ramFreeKb = ramFreeStr.toLongOrNull()
            ?: throw OntDriverException("Không đọc được thông số ramFree hợp lệ từ ONT (ramFree='$ramFreeStr').")
        val fileSizeKb = fileSize / 1024.0
        if (ramFreeKb <= 0L || fileSizeKb > ramFreeKb) {
            throw OntDriverException(
                "Không đủ bộ nhớ RAM trống trên ONT ($ramFreeKb KB) để tải file firmware (${fileSizeKb.toLong()} KB)."
            )
        }

        // 3. Upload binary firmware via multipart/form-data with streaming progress & post-flush tracking
        val allBytesFlushed = AtomicBoolean(false)
        val octetMediaType = "application/octet-stream".toMediaType()
        val trackingFileBody = object : RequestBody() {
            override fun contentType() = octetMediaType
            override fun contentLength() = fileSize
            override fun writeTo(sink: okio.BufferedSink) {
                val buffer = ByteArray(32 * 1024)
                var sentBytes = 0L
                var lastReportedPct = -1
                FileInputStream(firmwareFile).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        sentBytes += read
                        if (onProgressPercent != null && fileSize > 0L) {
                            val pct = ((sentBytes * 100L) / fileSize).toInt().coerceIn(0, 100)
                            if (pct == 100 || pct - lastReportedPct >= 2) {
                                lastReportedPct = pct
                                onProgressPercent.invoke(pct)
                            }
                        }
                    }
                }
                sink.flush()
                allBytesFlushed.set(true)
            }
        }
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", safeFilename, trackingFileBody)
            .addFormDataPart("sessionKey", sessionKey)
            .build()

        val req = Request.Builder()
            .url("$cleanBaseUrl/cgi-bin/luci/uploadsoftware.conf")
            .post(multipartBody)
            .header("User-Agent", USER_AGENT)
            .header("Referer", refererUrl)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Connection", "close")
            .build()

        val uploadHttpClient = buildUploadHttpClient(uploadTimeoutMs)
        val (upCode, upBody) = try {
            uploadHttpClient.newCall(req).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            val tracked = e as? FlushTrackedIOException ?: FlushTrackedIOException(e, allBytesFlushed.get())
            if (isPostFlushRadioDrop(tracked)) {
                clearSessionKeys()
            }
            throw OntNetworkError("Lỗi kết nối trong khi tải lên file firmware: ${e.message}", tracked)
        }

        val upData = parseJsonOrThrow("Tải lên file firmware (uploadsoftware.conf)", upCode, upBody)
        val upSessionKey = upData.optString("sessionKey", "").trim()
        if (upSessionKey.isNotEmpty() && upSessionKey != "0") {
            updateSessionKey(upSessionKey)
        }
        if (upData.optString("status") != "valid") {
            throw OntDriverException(
                "File firmware không hợp lệ hoặc sai chữ ký (status=${upData.optString("status", "unknown")})."
            )
        }
        // [Red Team RT-01 / RT-03] Clear sessionKey & lastActiveSessionKey immediately so finally { logout() } never sends logout.cmd while ONT flashes
        clearSessionKeys()
        return upData
    }

    /**
     * Distinguish pre-send TCP connection failures (where the HTTP request was NEVER sent because
     * the Wi-Fi link was already down) from post-flush radio drops.
     */
    internal fun isPreSendConnectFailure(e: IOException): Boolean {
        val raw = (e as? FlushTrackedIOException)?.cause ?: e
        if (raw is ConnectException || raw is NoRouteToHostException) return true
        val msg = raw.message.orEmpty()
        if (raw is SocketTimeoutException && msg.contains("connect timed out", ignoreCase = true)) {
            return true
        }
        if (raw is SocketException && (
                msg.contains("Network is unreachable", ignoreCase = true) ||
                    msg.contains("Host is unreachable", ignoreCase = true) ||
                    msg.contains("ENETUNREACH", ignoreCase = true) ||
                    msg.contains("EHOSTUNREACH", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true)
            )
        ) {
            return true
        }
        return false
    }

    /**
     * Detect socket/TLS stream drops that occur AFTER the HTTP POST request was flushed to the ONT
     * because the ONT immediately restarted the Wi-Fi radio upon applying the new SSID/password.
     */
    internal fun isPostFlushRadioDrop(e: IOException): Boolean {
        if (e is FlushTrackedIOException && !e.allBytesFlushed) return false
        if (isPreSendConnectFailure(e)) return false
        val raw = (e as? FlushTrackedIOException)?.cause ?: e
        val msg = raw.message.orEmpty()
        return raw is EOFException ||
            raw is SocketException ||
            raw is SocketTimeoutException ||
            raw is SSLException ||
            raw is InterruptedIOException ||
            msg.contains("unexpected end of stream", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Software caused connection abort", ignoreCase = true) ||
            msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Read error", ignoreCase = true) ||
            msg.contains("I/O error", ignoreCase = true) ||
            msg.contains("Socket closed", ignoreCase = true)
    }

    /**
     * [Red Team RT-01 & RT-05] Mandatory session release in finally block (1.5s timeout).
     * Preserves `lastActiveSessionKey` if `logout.cmd` times out so subsequent `"user exist"` recovery
     * still knows which session token to release.
     */
    fun logout() {
        val key = sessionKey
        if (key.isEmpty()) return
        val payload = "status=user want to logout&user=$currentUser&sessionKey=$key"
        var releasedOk = false
        try {
            val (code, _) = postForm("/cgi-bin/luci/logout.cmd", payload, useShortLogoutTimeout = true)
            releasedOk = (code == 200)
        } catch (_: Exception) {
        } finally {
            sessionKey = ""
            if (releasedOk && lastActiveSessionKey == key) {
                lastActiveSessionKey = ""
            }
        }
    }

    /**
     * Execute the automated Two-Connection Dual-Wi-Fi + Final Firmware Update workflow:
     * - Connection 1 (Steps 1..6 on initial Band A): Probe & pin TLS SHA-256 -> Smart Login & Admin pass
     *   + unconditional `deviceinfo2.json`/`dhcpclient.json` ground-truth -> WAN PPPoE -> GPON SLID
     *   -> LAN IPv4 -> Configure secondary Wi-Fi `Band B` (+ `wlrestart.json` <=30s) -> clean `logout.cmd`.
     *   (On 2.4GHz-only Android hardware `allowSingleBand24gFallback`, configures 5GHz then 2.4GHz and rejoins 2.4GHz).
     * - Handover (Step 7): `onSwitchToSecondaryWifi` (120s budget) -> rebuild pinned TLS client ->
     *   `probeGateway` (<=45s) -> `authenticateSecondConnection` strictly with `adminNewPass` (<=65s user-exist
     *   recovery, zero `"admin"` fallback) -> `verifySecondConnectionBandAndMac`.
     * - Connection 2 (Steps 8..9 on `Band B`): Configure remaining Wi-Fi `Band A` (with NVRAM read-only
     *   verification on transient drop) -> if `enableFirmwareUpdate`, run `ensureGatewayReadyOnSecondaryBand` (<=60s)
     *   and upload Firmware at Step 9 (<=180s), clearing session keys immediately on `"status": "valid"`.
     */
    suspend fun executeMobileSetup(
        record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings(baseUrl = baseUrl, requestTimeoutMs = timeoutMs),
        connectedBand: WifiBand = WifiBand.BAND_2_4_GHZ,
        bssid: String? = null,
        localWifiIpv4: String? = null,
        onBandResolved: ((WifiBand, String?) -> Unit)? = null,
        onProgress: ((StepProgress) -> Unit)? = null
    ): MobileSetupResult = withContext(Dispatchers.IO) {
        val preRunCheck = OntInputValidator.validateBeforeRun(record, settings)
        if (!preRunCheck.isValid) {
            throw OntValidationError(preRunCheck.errors.joinToString("; "))
        }

        val doFwUpdate = settings.enableFirmwareUpdate
        val fwFile = if (doFwUpdate) File(settings.firmwareFilePath.trim()) else null
        if (doFwUpdate) {
            if (fwFile == null || !fwFile.exists() || !fwFile.isFile) {
                throw OntValidationError("File firmware không tồn tại tại đường dẫn: '${settings.firmwareFilePath}'")
            }
            if (!OntInputValidator.isValidFirmwareExtension(fwFile.name)) {
                throw OntValidationError("Định dạng file firmware không hợp lệ: '${fwFile.name}'")
            }
            val fwLen = fwFile.length()
            if (fwLen !in OntInputValidator.MIN_FIRMWARE_SIZE_BYTES..OntInputValidator.MAX_FIRMWARE_SIZE_BYTES) {
                throw OntValidationError("Dung lượng file firmware không hợp lệ ($fwLen bytes): '${fwFile.name}'")
            }
        }

        val secMode = WifiSecurityMode.fromFormValue(settings.wifiSecurityMode)
            ?: throw OntValidationError("Chế độ bảo mật Wi-Fi '${settings.wifiSecurityMode}' không hợp lệ.")
        val totalSteps = if (doFwUpdate) 9 else 8
        val wifi24Ssid = record.effectiveWifi24Ssid
        val wifi24Pass = record.effectiveWifi24Pass(secMode.formValue)
        val wifi5Ssid = record.effectiveWifi5Ssid
        val wifi5Pass = record.effectiveWifi5Pass(secMode.formValue)

        fun emit(step: Int, title: String, status: StepStatus, detail: String) {
            val level = if (status == StepStatus.FAILED) LogLevel.ERROR else LogLevel.INFO
            logDiag(level, "STEP$step", "[$status] $title — $detail")
            onProgress?.invoke(
                StepProgress(
                    stepNumber = step,
                    totalSteps = totalSteps,
                    title = title,
                    status = status,
                    detail = detail
                )
            )
        }

        probePauseMutex?.lock()
        var unbindDone = false
        clearSessionKeys()
        observedPeerCertSha256 = null

        try {
            // Step 1: Probe Gateway & Pin Leaf X.509 SHA-256 Certificate
            val step1Title = "Bước 1/$totalSteps: Kiểm tra kết nối & nhận diện ONT Gateway"
            emit(1, step1Title, StepStatus.RUNNING, "Đang kiểm tra $cleanBaseUrl...")
            if (!probeGateway(failOnCertMismatch = false)) {
                throw OntNetworkError(
                    "Không thể kết nối tới ONT GW040-NSv2 tại $cleanBaseUrl (không nhận diện được chữ ký iGate/GW040/VNPT)."
                )
            }
            if (pinnedPeerCertSha256 == null && observedPeerCertSha256 != null) {
                pinnedPeerCertSha256 = observedPeerCertSha256
                rebuildHttpClients(currentSocketFactory)
            }
            emit(1, step1Title, StepStatus.SUCCESS, "Đã xác nhận kết nối tới ONT GW040-NSv2")

            // Step 2: Smart Login & Change Admin Password
            val step2Title = "Bước 2/$totalSteps: Đăng nhập & Đổi mật khẩu Admin"
            emit(2, step2Title, StepStatus.RUNNING, "Đang xác thực tài khoản admin...")
            loginAndChangePassword(
                defaultPass = settings.adminDefaultPass,
                newPass = settings.adminNewPass,
                bssid = bssid,
                lastKnownAdminPass = record.lastKnownAdminPass
            )
            onAdminPasswordChanged?.invoke(settings.adminNewPass)
            emit(2, step2Title, StepStatus.SUCCESS, "Đã xác thực và bảo mật tài khoản Admin")

            // Step 2c: Unconditional ONT band & baseMacAddress ground-truth resolution
            val bandResolution = resolveConnectedBandFromOntInternal(
                localIpv4 = localWifiIpv4,
                fallbackBand = connectedBand,
                bssid = bssid
            )
            val effectiveConnectedBand = if (allowSingleBand24gFallback) {
                WifiBand.BAND_2_4_GHZ
            } else {
                bandResolution.connectedBand
            }
            val resolvedOntBaseMac = bandResolution.ontBaseMac
            if (!localWifiIpv4.isNullOrBlank() || resolvedOntBaseMac != null) {
                onBandResolved?.invoke(effectiveConnectedBand, resolvedOntBaseMac)
            }

            // Step 3: Idempotent WAN PPPoE VLAN 11
            val step3Title = "Bước 3/$totalSteps: Cấu hình WAN PPPoE (VLAN ${settings.vlanId})"
            emit(3, step3Title, StepStatus.RUNNING, "Account: ${record.accountPppoe}")
            configureWanIdempotent(
                vlanId = settings.vlanId,
                pppUser = record.accountPppoe,
                pppPass = record.passPppoe,
                mtu = settings.mtu
            )
            emit(3, step3Title, StepStatus.SUCCESS, "Đã lưu WAN PPPoE (${record.accountPppoe})")

            // Step 4: GPON SLID / Password
            val step4Title = "Bước 4/$totalSteps: Cài đặt GPON Password (SLID / Mã MEN)"
            emit(4, step4Title, StepStatus.RUNNING, "SLID: ${record.slid}")
            setGponPassword(record.slid)
            emit(4, step4Title, StepStatus.SUCCESS, "Đã nạp SLID ${record.slid}")

            // Step 5: LAN IPv4
            val step5Title = "Bước 5/$totalSteps: Kiểm tra & Lưu cấu hình LAN IPv4"
            emit(5, step5Title, StepStatus.RUNNING, "Gateway IP: ${settings.lanIp}")
            configureLan(settings)
            emit(5, step5Title, StepStatus.SUCCESS, "Đã lưu cấu hình LAN ${settings.lanIp}")

            // Plan Connection 1 -> Connection 2 bands
            val isConnected24g = (effectiveConnectedBand == WifiBand.BAND_2_4_GHZ)
            val secondaryBand = if (isConnected24g) WifiBand.BAND_5_GHZ else WifiBand.BAND_2_4_GHZ
            val secondaryUnit = if (isConnected24g) 1 else 0
            val secondaryBandLabel = if (isConnected24g) "5GHz" else "2.4GHz"
            val secondarySsid = if (isConnected24g) wifi5Ssid else wifi24Ssid
            val secondaryPass = if (isConnected24g) wifi5Pass else wifi24Pass

            val primaryUnit = if (isConnected24g) 0 else 1
            val primaryBandLabel = if (isConnected24g) "2.4GHz" else "5GHz"
            val primarySsid = if (isConnected24g) wifi24Ssid else wifi5Ssid
            val primaryPass = if (isConnected24g) wifi24Pass else wifi5Pass

            val handoverBand: WifiBand
            val handoverBandLabel: String
            val handoverSsid: String
            val handoverPass: String
            val handoverBssidHint: String?

            // Step 6: Configure Secondary Wi-Fi Band (or both bands on 2.4GHz-only Android hardware) & Logout Connection 1
            val conn1SessionKey: String
            if (allowSingleBand24gFallback) {
                val step6Title = "Bước 6/$totalSteps: Cấu hình Wi-Fi 5GHz & 2.4GHz (Chế độ máy 2.4GHz)"
                emit(6, step6Title, StepStatus.RUNNING, "Đang lưu 5GHz ('$wifi5Ssid') và 2.4GHz ('$wifi24Ssid')...")
                configureWifiBand(
                    unit = 1,
                    ssid = wifi5Ssid,
                    password = wifi5Pass,
                    secMode = secMode.formValue,
                    isConnectedBandFinalStep = false
                )
                if (settlingDelayMs > 0L) delay(settlingDelayMs)
                val unit0Outcome = configureWifiBand(
                    unit = 0,
                    ssid = wifi24Ssid,
                    password = wifi24Pass,
                    secMode = secMode.formValue,
                    isConnectedBandFinalStep = true
                )
                conn1SessionKey = lastActiveSessionKey.ifEmpty { sessionKey }
                if (!unit0Outcome.droppedDuringRadioReload) {
                    logout()
                } else {
                    sessionKey = ""
                }
                if (conn1SessionKey.isNotEmpty()) {
                    lastActiveSessionKey = conn1SessionKey
                }
                handoverBand = WifiBand.BAND_2_4_GHZ
                handoverBandLabel = "2.4GHz"
                handoverSsid = wifi24Ssid
                handoverPass = wifi24Pass
                handoverBssidHint = bandResolution.wl0Bssid
                emit(6, step6Title, StepStatus.SUCCESS, "Đã gửi cấu hình 5GHz & 2.4GHz, chuẩn bị kết nối lại '$wifi24Ssid'")
            } else {
                val step6Title = "Bước 6/$totalSteps: Đổi tên & cấu hình Wi-Fi phụ $secondaryBandLabel"
                emit(6, step6Title, StepStatus.RUNNING, "SSID phụ: $secondarySsid")
                configureWifiBand(
                    unit = secondaryUnit,
                    ssid = secondarySsid,
                    password = secondaryPass,
                    secMode = secMode.formValue,
                    isConnectedBandFinalStep = false
                )
                if (settlingDelayMs > 0L) delay(settlingDelayMs)
                conn1SessionKey = lastActiveSessionKey.ifEmpty { sessionKey }
                logout()
                if (conn1SessionKey.isNotEmpty()) {
                    lastActiveSessionKey = conn1SessionKey
                }
                handoverBand = secondaryBand
                handoverBandLabel = secondaryBandLabel
                handoverSsid = secondarySsid
                handoverPass = secondaryPass
                handoverBssidHint = if (handoverBand == WifiBand.BAND_5_GHZ) {
                    bandResolution.wl1Bssid
                } else {
                    bandResolution.wl0Bssid
                }
                emit(6, step6Title, StepStatus.SUCCESS, "Đã lưu Wi-Fi phụ $secondaryBandLabel ('$secondarySsid') & đóng phiên Lần 1")
            }

            // Step 7: Outer Spaced-Backoff Reconnect & ONT Readiness Loop (Connection 2)
            val step7Title = "Bước 7/$totalSteps: Đợi ONT phát sóng mới & Chuyển sang Wi-Fi $handoverBandLabel"
            val switchHandler = onSwitchToSecondaryWifi
                ?: throw OntDriverException(
                    "Chưa cấu hình hàm chuyển sóng tự động (onSwitchToSecondaryWifi) cho quy trình 2 lần kết nối."
                )
            val step6CompletedMicros = currentScanEpochMicros()
            val totalHandoverBudgetMs = settings.wifiHandoverTimeoutMs.coerceAtLeast(100L)
            val handoverDeadlineMs = monotonicMillis() + totalHandoverBudgetMs
            var outerAttempt = 0
            var preferBssidLock = false
            var missingDeviceInfoOuterCount = 0
            var lastHandoverError: OntDriverException? = null
            var step7Verified = false

            logDiag(
                LogLevel.INFO,
                "STEP7",
                "Bắt đầu Bước 7: target='$handoverSsid' ($handoverBandLabel), bssidHint=${handoverBssidHint ?: "none"}, budget=${totalHandoverBudgetMs}ms, step6Micros=$step6CompletedMicros."
            )

            while (!step7Verified && monotonicMillis() < handoverDeadlineMs) {
                outerAttempt++
                val remainingBudgetMs = (handoverDeadlineMs - monotonicMillis()).coerceAtLeast(50L)
                emit(
                    7,
                    step7Title,
                    StepStatus.RUNNING,
                    if (outerAttempt == 1) {
                        "Đang chờ ONT khởi động lại sóng '$handoverSsid' ($handoverBandLabel) và chuyển kết nối..."
                    } else {
                        "Thử kết nối lại lượt $outerAttempt sang Wi-Fi '$handoverSsid' ($handoverBandLabel)..."
                    }
                )

                try {
                    val handoverCtx = try {
                        switchHandler.invoke(
                            handoverSsid,
                            handoverPass,
                            handoverBand,
                            secMode.formValue,
                            handoverBssidHint,
                            remainingBudgetMs,
                            step6CompletedMicros,
                            outerAttempt,
                            preferBssidLock
                        ) { statusDetail ->
                            emit(7, step7Title, StepStatus.RUNNING, statusDetail)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (de: OntDriverException) {
                        throw de
                    } catch (e: Exception) {
                        throw OntNetworkError(
                            "Không thể chuyển kết nối sang Wi-Fi mới '$handoverSsid' ($handoverBandLabel): ${e.message}",
                            e
                        )
                    }

                    rebuildHttpClients(handoverCtx.socketFactory)

                    val settleAfterDhcpMs = minOf(
                        postDhcpGatewaySettleMs.coerceAtLeast(0L),
                        ((handoverDeadlineMs - monotonicMillis()) / 3L).coerceAtLeast(0L)
                    )
                    if (settleAfterDhcpMs > 0L) {
                        delay(settleAfterDhcpMs)
                    }

                    if (isNetworkTransportAlive?.invoke() == false) {
                        throw OntNetworkError("Liên kết Wi-Fi '$handoverSsid' vừa rớt ngay sau khi cấp DHCP.")
                    }

                    emit(
                        7,
                        step7Title,
                        StepStatus.RUNNING,
                        "Đã ghép nối '$handoverSsid' (lượt $outerAttempt), đang kiểm tra ONT Gateway 192.168.1.1..."
                    )
                    val postHandoverBudgetMs = minOf(
                        settings.gatewayPostHandoverTimeoutMs.coerceAtLeast(50L),
                        (handoverDeadlineMs - monotonicMillis()).coerceAtLeast(50L)
                    )
                    val probeDeadlineMs = monotonicMillis() + postHandoverBudgetMs
                    var gatewayReachableOnConn2 = false
                    while (monotonicMillis() < probeDeadlineMs) {
                        if (isNetworkTransportAlive?.invoke() == false) {
                            throw OntNetworkError(
                                "Mất kết nối Wi-Fi '$handoverSsid' (onLost) khi đang thăm dò ONT Gateway."
                            )
                        }
                        if (probeGateway(failOnCertMismatch = true)) {
                            gatewayReachableOnConn2 = true
                            break
                        }
                        val sleepProbe = minOf(
                            recoveryRetryDelayMs.coerceAtLeast(15L),
                            (probeDeadlineMs - monotonicMillis()).coerceAtLeast(10L)
                        )
                        delay(sleepProbe)
                    }
                    if (!gatewayReachableOnConn2 && !probeGateway(failOnCertMismatch = true)) {
                        throw OntNetworkError(
                            "Đã chuyển sang Wi-Fi '$handoverSsid' nhưng không truy cập được ONT Gateway tại $cleanBaseUrl sau ${postHandoverBudgetMs / 1000}s."
                        )
                    }
                    logDiag(
                        LogLevel.INFO,
                        "STEP7",
                        "Gateway 192.168.1.1 phản hồi hợp lệ ở lượt $outerAttempt (certSha256=${observedPeerCertSha256?.take(12) ?: "none"}...)."
                    )

                    emit(7, step7Title, StepStatus.RUNNING, "Đang đăng nhập lại ONT bằng mật khẩu Admin mới...")
                    val userExistBudgetMs = if (settings.gatewayPostHandoverTimeoutMs >= 45_000L) {
                        65_000L
                    } else {
                        settings.gatewayPostHandoverTimeoutMs.coerceAtLeast(userExistPollIntervalMs * 2)
                    }
                    authenticateSecondConnection(
                        newAdminPass = settings.adminNewPass,
                        previousSessionKey = conn1SessionKey,
                        userExistWaitBudgetMs = userExistBudgetMs
                    )

                    verifySecondConnectionBandAndMacWithRetry(
                        localIpv4 = handoverCtx.localWifiIpv4,
                        expectedBand = handoverBand,
                        expectedOntBaseMac = resolvedOntBaseMac,
                        isSameSsidBothBands = wifi24Ssid.equals(wifi5Ssid, ignoreCase = true)
                    )
                    step7Verified = true
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ne: OntNetworkError) {
                    lastHandoverError = ne
                    if (ne is StillOnPrimaryBandException && !handoverBssidHint.isNullOrBlank()) {
                        preferBssidLock = true
                    }
                    if (ne is MissingDeviceInfoException) {
                        missingDeviceInfoOuterCount++
                        if (missingDeviceInfoOuterCount >= 2) {
                            throw OntDriverException(
                                "Địa chỉ MAC của ONT ở Lần kết nối 2 (không xác định) không thể đối chiếu với ONT ban đầu ($resolvedOntBaseMac) sau 2 lượt đọc deviceinfo2.json.",
                                ne
                            )
                        }
                    }
                    logDiag(
                        LogLevel.WARN,
                        "STEP7",
                        "Lượt Bước 7 #$outerAttempt gặp sự cố tạm thời (${ne.javaClass.simpleName}): ${ne.message}"
                    )
                    if (isValidSessionToken(sessionKey)) {
                        runCatching { logout() }
                    }
                    runCatching { onUnbindNetwork?.invoke() }

                    val afterFailRemainingMs = handoverDeadlineMs - monotonicMillis()
                    val targetBackoffMs = OntWifiConnector.computeReconnectBackoffMs(
                        attemptNumber = outerAttempt,
                        initialBackoffMs = handoverRetryInitialBackoffMs,
                        maxBackoffMs = handoverRetryMaxBackoffMs
                    )
                    if (afterFailRemainingMs <= handoverRetryInitialBackoffMs.coerceAtLeast(10L)) {
                        break
                    }
                    val sleepBackoffMs = minOf(targetBackoffMs, afterFailRemainingMs - 10L).coerceAtLeast(10L)
                    emit(
                        7,
                        step7Title,
                        StepStatus.RUNNING,
                        "Kết nối Wi-Fi '$handoverSsid' chưa ổn định, tự động thử lại sau ${(sleepBackoffMs + 999L) / 1000L}s (lượt ${outerAttempt + 1})..."
                    )
                    logDiag(
                        LogLevel.INFO,
                        "STEP7",
                        "Nghỉ giãn cách ${sleepBackoffMs}ms trước lượt Bước 7 #${outerAttempt + 1} (lockBssid=$preferBssidLock)."
                    )
                    delay(sleepBackoffMs)
                } catch (de: OntDriverException) {
                    logDiag(
                        LogLevel.ERROR,
                        "STEP7",
                        "Dừng ngay Bước 7 ở lượt #$outerAttempt do lỗi xác thực/bảo mật không được phép retry: ${de.message}"
                    )
                    throw de
                }
            }

            if (!step7Verified) {
                throw lastHandoverError ?: OntNetworkError(
                    "Hết thời gian chờ (${totalHandoverBudgetMs / 1000}s) chuyển kết nối sang Wi-Fi '$handoverSsid' ($handoverBandLabel)."
                )
            }

            emit(
                7,
                step7Title,
                StepStatus.SUCCESS,
                "Đã đứng trên Wi-Fi '$handoverSsid' ($handoverBandLabel) & xác thực ONT thành công"
            )
            // Step 8: Configure Remaining Primary Wi-Fi Band (or verify NVRAM on 2.4GHz-only fallback)
            if (allowSingleBand24gFallback) {
                val step8Title = "Bước 8/$totalSteps: Xác nhận lưu NVRAM cả 2 băng tần Wi-Fi"
                emit(8, step8Title, StepStatus.RUNNING, "Đang kiểm tra NVRAM Wi-Fi 5GHz & 2.4GHz...")
                val nvram5 = readConfiguredBandSsidFromNvram(1)
                if (nvram5 != wifi5Ssid) {
                    configureWifiBand(
                        unit = 1,
                        ssid = wifi5Ssid,
                        password = wifi5Pass,
                        secMode = secMode.formValue,
                        isConnectedBandFinalStep = false
                    )
                }
                val nvram24 = readConfiguredBandSsidFromNvram(0)
                if (nvram24 != null && nvram24 != wifi24Ssid) {
                    configureWifiBand(
                        unit = 0,
                        ssid = wifi24Ssid,
                        password = wifi24Pass,
                        secMode = secMode.formValue,
                        isConnectedBandFinalStep = true
                    )
                }
                emit(8, step8Title, StepStatus.SUCCESS, "Đã xác nhận lưu NVRAM cả 2 Wi-Fi ('$wifi24Ssid' & '$wifi5Ssid')")
            } else {
                val step8Title = "Bước 8/$totalSteps: Đổi tên & cấu hình Wi-Fi còn lại $primaryBandLabel"
                emit(
                    8,
                    step8Title,
                    StepStatus.RUNNING,
                    "SSID mới: $primarySsid (cấu hình từ kết nối phụ $handoverBandLabel)"
                )
                try {
                    configureWifiBand(
                        unit = primaryUnit,
                        ssid = primarySsid,
                        password = primaryPass,
                        secMode = secMode.formValue,
                        isConnectedBandFinalStep = false
                    )
                } catch (e: OntNetworkError) {
                    emit(
                        8,
                        step8Title,
                        StepStatus.RUNNING,
                        "Đang đồng bộ lại kết nối '$handoverSsid' để kiểm tra NVRAM cho '$primarySsid'..."
                    )
                    val readyAfterDrop = ensureGatewayReadyOnSecondaryBand(
                        settings = settings,
                        timeoutBudgetMs = settings.postSecondWifiSettleTimeoutMs
                    )
                    if (!readyAfterDrop) {
                        throw OntNetworkError(
                            "Mất kết nối tới ONT trên Wi-Fi '$handoverSsid' khi xác nhận cấu hình Wi-Fi $primaryBandLabel ('$primarySsid').",
                            e
                        )
                    }
                    val savedNvramSsid = readConfiguredBandSsidFromNvram(primaryUnit)
                    if (savedNvramSsid == primarySsid) {
                        commitRadioReloadBestEffort()
                    } else {
                        configureWifiBand(
                            unit = primaryUnit,
                            ssid = primarySsid,
                            password = primaryPass,
                            secMode = secMode.formValue,
                            isConnectedBandFinalStep = false
                        )
                    }
                }
                if (settlingDelayMs > 0L) delay(settlingDelayMs)
                emit(
                    8,
                    step8Title,
                    StepStatus.SUCCESS,
                    "Đã hoàn tất cấu hình cả 2 băng tần Wi-Fi ('$wifi24Ssid' / '$wifi5Ssid')"
                )
            }

            // Final Step: Either Step 9 Firmware Update (9/9) or Clean Logout & Finish (8/8)
            if (doFwUpdate && fwFile != null) {
                val step9Title = "Bước 9/9: Tải lên & Cập nhật Firmware ONT (Bước cuối)"
                val fwDisplayName = settings.firmwareFileName.ifBlank { fwFile.name }
                emit(
                    9,
                    step9Title,
                    StepStatus.RUNNING,
                    "Đang đồng bộ kết nối '$handoverSsid' trước khi tải lên Firmware..."
                )
                val readyForFirmware = ensureGatewayReadyOnSecondaryBand(
                    settings = settings,
                    timeoutBudgetMs = settings.postSecondWifiSettleTimeoutMs
                )
                if (!readyForFirmware) {
                    throw OntNetworkError(
                        "Đã cấu hình xong cả 2 Wi-Fi ('$wifi24Ssid' / '$wifi5Ssid'), nhưng mất kết nối tới ONT trước khi nạp Firmware (Bước 9). Hãy kiểm tra kết nối Wi-Fi '$handoverSsid' rồi thử lại."
                    )
                }

                val fwSizeKb = fwFile.length() / 1024
                emit(
                    9,
                    step9Title,
                    StepStatus.RUNNING,
                    "Đang tải lên $fwDisplayName ($fwSizeKb KB)..."
                )
                try {
                    updateFirmware(
                        firmwareFile = fwFile,
                        uploadTimeoutMs = settings.firmwareUploadTimeoutMs,
                        onProgressPercent = { pct ->
                            emit(
                                9,
                                step9Title,
                                StepStatus.RUNNING,
                                "Đang tải lên $fwDisplayName ($pct% — $fwSizeKb KB)..."
                            )
                        }
                    )
                } catch (e: OntDriverException) {
                    val prefix =
                        "Đã cấu hình xong cả 2 Wi-Fi ('$wifi24Ssid' / '$wifi5Ssid' — Bước 1-8), nhưng lỗi Cập nhật Firmware (Bước 9): ${e.message}"
                    throw when (e) {
                        is OntNetworkError -> OntNetworkError(prefix, e)
                        is OntValidationError -> OntValidationError(prefix)
                        else -> OntDriverException(prefix, e)
                    }
                }

                onUnbindNetwork?.invoke()
                unbindDone = true

                emit(
                    9,
                    step9Title,
                    StepStatus.SUCCESS,
                    "Đã nạp Firmware ($fwDisplayName) thành công! ONT đang tự ghi flash & khởi động lại (~2 phút 20 giây)."
                )
                return@withContext MobileSetupResult(
                    success = true,
                    soMay = record.slid,
                    slid = record.slid,
                    accountPppoe = record.accountPppoe,
                    newSsid24g = wifi24Ssid,
                    newPass24g = wifi24Pass,
                    newSsid5g = wifi5Ssid,
                    newPass5g = wifi5Pass,
                    updatedAdminPass = settings.adminNewPass,
                    message = "Hoàn tất 9/9 bước! Đã đổi cả 2 Wi-Fi ($wifi24Ssid / $wifi5Ssid) & nạp Firmware ($fwDisplayName). ONT đang tự khởi động lại (~2 phút 20 giây).",
                    beaconVerified = true,
                    firmwareUpdated = true,
                    firmwareFileName = fwDisplayName,
                    configuredSecondaryBandLabel = handoverBandLabel,
                    configuredSecondarySsid = handoverSsid,
                    configuredSecondaryPass = handoverPass,
                    wifiSecurityMode = secMode.formValue,
                    resolvedConnectedBand = effectiveConnectedBand
                )
            }

            logout()
            onUnbindNetwork?.invoke()
            unbindDone = true

            MobileSetupResult(
                success = true,
                soMay = record.slid,
                slid = record.slid,
                accountPppoe = record.accountPppoe,
                newSsid24g = wifi24Ssid,
                newPass24g = wifi24Pass,
                newSsid5g = wifi5Ssid,
                newPass5g = wifi5Pass,
                updatedAdminPass = settings.adminNewPass,
                message = "Cấu hình hoàn tất 8/8 bước! Đã đổi thành công cả 2 Wi-Fi: $wifi24Ssid / $wifi5Ssid",
                beaconVerified = true,
                firmwareUpdated = false,
                configuredSecondaryBandLabel = handoverBandLabel,
                configuredSecondarySsid = handoverSsid,
                configuredSecondaryPass = handoverPass,
                wifiSecurityMode = secMode.formValue,
                resolvedConnectedBand = effectiveConnectedBand
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    logout()
                } catch (_: Exception) {
                }
                if (!unbindDone) {
                    try {
                        onUnbindNetwork?.invoke()
                    } catch (_: Exception) {
                    }
                }
                val hadPin = pinnedPeerCertSha256 != null
                pinnedPeerCertSha256 = null
                observedPeerCertSha256 = null
                if (hadPin) {
                    rebuildHttpClients(currentSocketFactory)
                }
                try {
                    probePauseMutex?.unlock()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"

        private val ONT_HTML_SIGNATURES = listOf("iGate", "GW040", "VNPT", "login.js")
        private val CHANNEL_LEADING_DIGITS_REGEX = Regex("^(\\d+)")

        private val bssidPasswordCache = ConcurrentHashMap<String, String>()

        fun clearCachedPasswordsForTest() {
            bssidPasswordCache.clear()
        }
    }
}
