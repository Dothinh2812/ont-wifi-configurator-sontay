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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import vn.vnpt.ontconfig.core.validation.OntInputValidator
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
enum class StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED
}

data class StepProgress(
    val stepNumber: Int,
    val totalSteps: Int = 7,
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
    val manualWifiStepPending: Boolean = false,
    val manualWifiUnit: Int = 0,
    val manualWifiBandLabel: String = "",
    val manualWifiSsid: String = "",
    val manualWifiPass: String = "",
    val configuredSecondaryBandLabel: String = "",
    val configuredSecondarySsid: String = "",
    val configuredSecondaryPass: String = "",
    val wifiSecurityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
    val resolvedConnectedBand: WifiBand = WifiBand.BAND_2_4_GHZ
)

class OntMobileDriver(
    private val baseUrl: String = "https://192.168.1.1",
    private val timeoutMs: Long = 5000L,
    private val socketFactory: SocketFactory? = null,
    private val settlingDelayMs: Long = 400L,
    private val recoveryRetryDelayMs: Long = 1200L,
    private val probePauseMutex: Mutex? = null,
    private val onUnbindNetwork: (() -> Unit)? = null,
    private val onVerifyNewSsid: (suspend (String, String) -> Boolean)? = null,
    private val onAdminPasswordChanged: (suspend (String) -> Unit)? = null,
    private val onRebindWifiNetwork: (suspend () -> SocketFactory?)? = null
) {

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
    private fun buildHttpClient(sf: SocketFactory?, timeout: Long): OkHttpClient {
        return UnsafeSslClient.create(
            socketFactory = sf,
            timeoutMs = timeout,
            allowedHostOverride = hostOverride
        )
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
        currentSocketFactory = newSocketFactory
        client = buildHttpClient(newSocketFactory, timeoutMs)
        logoutClient = buildHttpClient(newSocketFactory, 1500L)
    }

    /**
     * [Red Team RT-10] Probe root URL and verify both HTTP 200 AND ONT HTML signature
     * ("iGate", "GW040", "VNPT", or "login.js").
     */
    suspend fun probeGateway(): Boolean = withContext(Dispatchers.IO) {
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

                // Wait for _wlrestart_ == "0" per wireless.js get_loading_data() if ONT reports restart in progress
                if (!isConnectedBandFinalStep && resJson.optString("_wlrestart_", "0") != "0") {
                    for (pollAttempt in 1..10) {
                        delay(1000L)
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
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [Red Team RT-02] Server-side connected band ground-truth resolution immediately after Step 2:
     * Queries `dhcpclient.json` (preferring live `wlAssociatedDevice` over stale `dhcpInfoList`)
     * and `deviceinfo2.json` (parsing 2D `wirelessInfoList` `[[String, String]]` with `\r\n` trimming)
     * over the primary HTTP client (`timeoutMs`, default 5000ms).
     */
    fun resolveConnectedBandFromOnt(
        localIpv4: String?,
        fallbackBand: WifiBand,
        bssid: String? = null
    ): Pair<WifiBand, String?> {
        val cleanIp = localIpv4?.trim().orEmpty()
        if (cleanIp.isEmpty() || !cleanIp.startsWith("192.168.1.") || cleanIp == "192.168.1.1") {
            return fallbackBand to null
        }
        if (!isValidSessionToken(sessionKey)) {
            return fallbackBand to null
        }

        var matchedWlIfc = ""
        postOptionalSessionJson("/cgi-bin/luci/dhcpclient.json")?.let { dhcpJson ->
            var clientMac = ""
            var leaseIfc = ""
            dhcpJson.optJSONArray("dhcpInfoList")?.let { dhcpArr ->
                for (i in 0 until dhcpArr.length()) {
                    val parts = optPipeRow(dhcpArr, i).split("|")
                    if (parts.getOrNull(0)?.trim() == cleanIp) {
                        clientMac = parts.getOrNull(3)?.trim().orEmpty()
                        leaseIfc = normalizeWlInterface(parts.getOrNull(7).orEmpty())
                        break
                    }
                }
            }

            var liveAssocIfc = ""
            dhcpJson.optJSONArray("wlAssociatedDevice")?.let { assocArr ->
                for (i in 0 until assocArr.length()) {
                    val assocParts = optPipeRow(assocArr, i).split("|")
                    val assocMac = assocParts.getOrNull(0)?.trim().orEmpty()
                    val assocIp = assocParts.getOrNull(1)?.trim().orEmpty()
                    val macMatches = clientMac.isNotEmpty() && assocMac.equals(clientMac, ignoreCase = true)
                    if (macMatches || assocIp == cleanIp) {
                        liveAssocIfc = normalizeWlInterface(assocParts.getOrNull(3).orEmpty()).ifEmpty {
                            assocParts.firstNotNullOfOrNull { token ->
                                normalizeWlInterface(token).takeIf { it.isNotEmpty() }
                            }.orEmpty()
                        }
                        if (liveAssocIfc.isNotEmpty()) break
                    }
                }
            }
            matchedWlIfc = liveAssocIfc.ifEmpty { leaseIfc }
        }

        var ontBaseMac: String? = null
        var resolvedFromDeviceInfo: WifiBand? = null
        postOptionalSessionJson("/cgi-bin/luci/deviceinfo2.json")?.let { devJson ->
            val baseMac = devJson.optString("baseMacAddress", "").trim().ifEmpty { null }
            ontBaseMac = baseMac
            if (baseMac != null && bssid.isNullOrBlank() && currentPass.isNotEmpty()) {
                bssidPasswordCache[baseMac.uppercase()] = currentPass
            }
            if (matchedWlIfc.isNotEmpty()) {
                devJson.optJSONArray("wirelessInfoList")?.let { wlList ->
                    for (i in 0 until wlList.length()) {
                        val entryStr = optPipeRow(wlList, i)
                        if (entryStr.isBlank()) continue
                        val subData = entryStr.split("|").map { it.trim() }
                        val entryIfc = normalizeWlInterface(subData.getOrNull(0).orEmpty())
                        if (entryIfc == matchedWlIfc) {
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
                            break
                        }
                    }
                }
            }
        }

        val finalBand = resolvedFromDeviceInfo ?: when (matchedWlIfc) {
            "wl1" -> WifiBand.BAND_5_GHZ
            "wl0" -> WifiBand.BAND_2_4_GHZ
            else -> fallbackBand
        }
        return finalBand to ontBaseMac
    }

    private fun reloginAfterRadioBounce(
        settings: GlobalSettings,
        bssid: String?,
        previousSessionKey: String
    ): Boolean {
        val activeAdminPass = currentPass.ifEmpty { settings.adminNewPass }
        fun attemptLogin(): Boolean {
            loginAndChangePassword(
                defaultPass = settings.adminDefaultPass,
                newPass = settings.adminNewPass,
                bssid = bssid,
                lastKnownAdminPass = activeAdminPass
            )
            return true
        }
        fun handleNonUserExistFailure(e: Exception): Boolean {
            when (e) {
                is OntAccountLockoutError,
                is OntAccountLockoutWarning,
                is OntAuthenticationError,
                is CancellationException -> throw e
                else -> {
                    if (sessionKey.isEmpty() && lastActiveSessionKey.isNotEmpty()) {
                        sessionKey = lastActiveSessionKey
                    }
                    return false
                }
            }
        }

        return try {
            attemptLogin()
        } catch (_: OntUserExistError) {
            val keyToRelease = previousSessionKey.ifEmpty { lastActiveSessionKey }
            if (!isValidSessionToken(keyToRelease)) return false
            try {
                sessionKey = keyToRelease
                logout()
                attemptLogin()
            } catch (e2: Exception) {
                handleNonUserExistFailure(e2)
            }
        } catch (e: Exception) {
            handleNonUserExistFailure(e)
        }
    }

    /**
     * [Red Team RT-01 & RT-05] Narrow HTTP-only try/catch around `wlrestart.json`, explicit detection
     * of LuCI's `{"status": "fail authen"}` expiry and missing `_wlrestart_`, dynamic `lastActiveSessionKey`
     * recovery, zero double-login on `numberLogin >= 2`, and strict `CancellationException` propagation.
     */
    private suspend fun ensureGatewayReadyBeforeStep7(
        settings: GlobalSettings,
        bssid: String?,
        previousSessionKey: String = lastActiveSessionKey.ifEmpty { sessionKey },
        maxAttempts: Int = 8,
        retryDelayMs: Long = recoveryRetryDelayMs,
        allowActiveRebind: Boolean = true
    ): Boolean {
        for (attempt in 1..maxAttempts) {
            if (allowActiveRebind && onRebindWifiNetwork != null) {
                try {
                    val newSf = onRebindWifiNetwork.invoke()
                    if (newSf != null && newSf != currentSocketFactory) {
                        updateSocketFactory(newSf)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                }
            }

            if (probeGateway()) {
                val activeKey = sessionKey
                val httpResult = if (isValidSessionToken(activeKey)) {
                    try {
                        val encKey = OntInputValidator.urlEncodeFormValue(activeKey)
                        postForm("/cgi-bin/luci/wlrestart.json", "undefined&sessionKey=$encKey")
                    } catch (_: IOException) {
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
                if (
                    wlJson == null ||
                    statusStr.equals("fail authen", ignoreCase = true) ||
                    !wlJson.has("_wlrestart_")
                ) {
                    val keyForRelogin = lastActiveSessionKey.ifEmpty { previousSessionKey }
                    if (reloginAfterRadioBounce(settings, bssid, keyForRelogin)) {
                        return true
                    }
                } else if (wlJson.optString("_wlrestart_", "0").trim() != "0") {
                    delay(minOf(800L, retryDelayMs.coerceAtLeast(20L)))
                    continue
                } else {
                    return true
                }
            }

            if (attempt < maxAttempts && retryDelayMs > 0L) {
                delay(retryDelayMs)
            }
        }
        return false
    }

    /**
     * [Red Team RT-03 & RT-04] Upload and apply ONT firmware via 3-step LuCI CGI sequence:
     * 1. POST /cgi-bin/luci/updatefwsessioncheck.json -> {"status": "OK"}
     * 2. POST /cgi-bin/luci/imagesize.json -> {"ramFree": "...", "sessionKey": "..."}
     * 3. POST /cgi-bin/luci/uploadsoftware.conf (multipart/form-data: file, sessionKey) -> {"status": "valid"}
     */
    fun updateFirmware(
        firmwareFile: File,
        uploadTimeoutMs: Long = 120_000L
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

        // 3. Upload binary firmware via multipart/form-data with post-flush tracking
        val allBytesFlushed = AtomicBoolean(false)
        val baseFileBody = firmwareFile.asRequestBody("application/octet-stream".toMediaType())
        val trackingFileBody = object : RequestBody() {
            override fun contentType() = baseFileBody.contentType()
            override fun contentLength() = baseFileBody.contentLength()
            override fun writeTo(sink: okio.BufferedSink) {
                baseFileBody.writeTo(sink)
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
     * [Red Team RT-04] Shared 3-attempt final connected Wi-Fi apply + `stillOnOldWifi` verification
     * state machine used by both `executeMobileSetup` (Step 7) and `executeManualConnectedWifiOnly`.
     */
    private suspend fun applyFinalConnectedBandWithRetries(
        unit: Int,
        ssid: String,
        password: String,
        secMode: String,
        settings: GlobalSettings,
        bssid: String?,
        oldWifiStillAliveMessage: String,
        fallbackFailureMessage: String,
        onRetryAttempt: ((nextAttempt: Int) -> Unit)? = null
    ): WifiApplyOutcome {
        var lastError: Exception? = null

        for (attempt in 1..3) {
            val attemptSessionKey = lastActiveSessionKey
            val outcome = try {
                configureWifiBand(
                    unit = unit,
                    ssid = ssid,
                    password = password,
                    secMode = secMode,
                    isConnectedBandFinalStep = true
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is OntValidationError || (e is OntDriverException && e !is OntNetworkError)) {
                    throw e
                }
                lastError = e
                val causeIo = (e as? IOException) ?: (e.cause as? IOException)
                val prevKey = lastActiveSessionKey.ifEmpty { attemptSessionKey }
                if (attempt < 3) {
                    onRetryAttempt?.invoke(attempt + 1)
                    if (recoveryRetryDelayMs > 0L) {
                        delay(recoveryRetryDelayMs)
                    }
                    val gatewayReady = ensureGatewayReadyBeforeStep7(
                        settings = settings,
                        bssid = bssid,
                        previousSessionKey = prevKey,
                        maxAttempts = 6,
                        retryDelayMs = recoveryRetryDelayMs
                    )
                    if (!gatewayReady && causeIo != null && isPostFlushRadioDrop(causeIo)) {
                        return WifiApplyOutcome(cfgSavedToNvram = false, droppedDuringRadioReload = true)
                    }
                    continue
                }
                if (causeIo != null && isPostFlushRadioDrop(causeIo)) {
                    val stillOnOldWifi = ensureGatewayReadyBeforeStep7(
                        settings = settings,
                        bssid = bssid,
                        previousSessionKey = prevKey,
                        maxAttempts = 3,
                        retryDelayMs = recoveryRetryDelayMs,
                        allowActiveRebind = false
                    )
                    if (!stillOnOldWifi) {
                        return WifiApplyOutcome(cfgSavedToNvram = false, droppedDuringRadioReload = true)
                    }
                }
                throw e
            }

            if (outcome.cfgSavedToNvram) {
                return outcome
            }
            if (outcome.droppedDuringRadioReload) {
                val stillOnOldWifi = ensureGatewayReadyBeforeStep7(
                    settings = settings,
                    bssid = bssid,
                    previousSessionKey = lastActiveSessionKey.ifEmpty { attemptSessionKey },
                    maxAttempts = 3,
                    retryDelayMs = recoveryRetryDelayMs,
                    allowActiveRebind = false
                )
                if (!stillOnOldWifi) {
                    return outcome
                }
                if (attempt < 3) {
                    onRetryAttempt?.invoke(attempt + 1)
                    continue
                }
                throw OntNetworkError(oldWifiStillAliveMessage)
            }
        }

        throw lastError ?: OntNetworkError(fallbackFailureMessage)
    }

    private fun releaseOrClearSessionAfterFinalWifi(outcome: WifiApplyOutcome) {
        if (!outcome.droppedDuringRadioReload) {
            logout()
        } else {
            clearSessionKeys()
        }
    }

    /**
     * Execute the 7-step wireless ONT setup workflow with:
     * - [RT-10] Probe Pause Mutex barrier & Step 1 ONT HTML signature check
     * - [RT-04] Step 2 Smart Login retry order
     * - [RT-07] Step 3 Idempotent WAN PPPoE VLAN 11 (serviceId=1 on Edit)
     * - Step 4 GPON SLID (<= 10 ASCII)
     * - Step 5 LAN IPv4 (192.168.1.1)
     * - [RT-02] Step 6 Non-connected Wi-Fi band FIRST + wlrestart.json + 2.5s settling pause
     * - [RT-02] Step 7 Gateway readiness/rebind check + Connected Wi-Fi band LAST + wlrestart.json
     * - [RT-01 & RT-06] Mandatory finally { withContext(NonCancellable) { logout(); unbind() } }
     */
    suspend fun executeMobileSetup(
        record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings(baseUrl = baseUrl, requestTimeoutMs = timeoutMs),
        connectedBand: WifiBand = WifiBand.BAND_2_4_GHZ,
        bssid: String? = null,
        alreadyConfiguredOppositeBand: Boolean = false,
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
        val totalSteps = if (doFwUpdate) 8 else 7
        val wifi24Ssid = record.effectiveWifi24Ssid
        val wifi24Pass = record.effectiveWifi24Pass(secMode.formValue)
        val wifi5Ssid = record.effectiveWifi5Ssid
        val wifi5Pass = record.effectiveWifi5Pass(secMode.formValue)
        fun emit(step: Int, title: String, status: StepStatus, detail: String) {
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

        try {
            // Step 1: Probe Gateway & Verify ONT HTML Signature
            val step1Title = "Bước 1/$totalSteps: Kiểm tra kết nối & nhận diện ONT Gateway"
            emit(1, step1Title, StepStatus.RUNNING, "Đang kiểm tra $cleanBaseUrl...")
            if (!probeGateway()) {
                throw OntNetworkError(
                    "Không thể kết nối tới ONT GW040-NSv2 tại $cleanBaseUrl (không nhận diện được chữ ký iGate/GW040/VNPT)."
                )
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

            // [Red Team RT-02] Resolve ground-truth connected Wi-Fi band immediately after Step 2
            // (before Step 5 savelanv4cfg.cmd touches br-lan / dnsmasq)
            val (effectiveConnectedBand, resolvedOntBaseMac) = if (!localWifiIpv4.isNullOrBlank()) {
                val resolved = resolveConnectedBandFromOnt(
                    localIpv4 = localWifiIpv4,
                    fallbackBand = connectedBand,
                    bssid = bssid
                )
                onBandResolved?.invoke(resolved.first, resolved.second)
                resolved
            } else {
                connectedBand to null
            }
            val effectiveBssid = bssid?.takeIf { it.isNotBlank() } ?: resolvedOntBaseMac

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

            // Dynamic Band Ordering:
            // Configure the NON-CONNECTED band in Step 6, keep the active Wi-Fi link alive,
            // and configure the CONNECTED band LAST in Step 7!
            val isConnected24g = (effectiveConnectedBand == WifiBand.BAND_2_4_GHZ)
            val step6Unit = if (isConnected24g) 1 else 0
            val step6BandLabel = if (isConnected24g) "5GHz" else "2.4GHz"
            val step6Ssid = if (isConnected24g) wifi5Ssid else wifi24Ssid
            val step6Pass = if (isConnected24g) wifi5Pass else wifi24Pass

            val step7Unit = if (isConnected24g) 0 else 1
            val step7BandLabel = if (isConnected24g) "2.4GHz" else "5GHz"
            val step7Ssid = if (isConnected24g) wifi24Ssid else wifi5Ssid
            val step7Pass = if (isConnected24g) wifi24Pass else wifi5Pass

            // Step 6: Non-connected Wi-Fi band + wlrestart.json (DO NOT logout or unbind!)
            val step6Title = "Bước 6/$totalSteps: Cấu hình Wi-Fi $step6BandLabel (Băng tần phụ)"
            emit(6, step6Title, StepStatus.RUNNING, "SSID: $step6Ssid")
            configureWifiBand(
                unit = step6Unit,
                ssid = step6Ssid,
                password = step6Pass,
                secMode = secMode.formValue,
                isConnectedBandFinalStep = false
            )

            if (doFwUpdate && fwFile != null) {
                // Firmware Update Mode (8 steps):
                // Keep current connection stable for Firmware upload at Step 7!
                if (settlingDelayMs > 0) {
                    delay(settlingDelayMs)
                }
                emit(6, step6Title, StepStatus.SUCCESS, "Đã lưu & khởi động Wi-Fi $step6BandLabel ($step6Ssid)")

                val gatewayReady = ensureGatewayReadyBeforeStep7(
                    settings = settings,
                    bssid = effectiveBssid,
                    previousSessionKey = lastActiveSessionKey,
                    maxAttempts = 8,
                    retryDelayMs = recoveryRetryDelayMs
                )
                if (!gatewayReady) {
                    throw OntNetworkError(
                        "Đã lưu cấu hình Bước 1-6 (kể cả Wi-Fi $step6BandLabel '$step6Ssid'), nhưng mất kết nối tới ONT trước khi nạp Firmware (Bước 7). Hãy kiểm tra lại kết nối Wi-Fi ONT rồi bấm Chạy Lại."
                    )
                }

                // Step 7/8: Upload & Update Firmware BEFORE the final connected Wi-Fi step
                val step7FwTitle = "Bước 7/8: Tải lên & Cập nhật Firmware ONT (Trước bước cuối)"
                val fwDisplayName = settings.firmwareFileName.ifBlank { fwFile.name }
                emit(
                    7,
                    step7FwTitle,
                    StepStatus.RUNNING,
                    "Đang tải lên $fwDisplayName (${fwFile.length() / 1024} KB)..."
                )
                try {
                    updateFirmware(
                        firmwareFile = fwFile,
                        uploadTimeoutMs = settings.firmwareUploadTimeoutMs
                    )
                } catch (e: OntDriverException) {
                    val prefix =
                        "Đã đổ xong cấu hình (Bước 1-6: WAN, SLID, LAN, Wi-Fi $step6BandLabel), nhưng lỗi Cập nhật Firmware (Bước 7): ${e.message}"
                    throw when (e) {
                        is OntNetworkError -> OntNetworkError(prefix, e)
                        is OntValidationError -> OntValidationError(prefix)
                        else -> OntDriverException(prefix, e)
                    }
                }
                emit(
                    7,
                    step7FwTitle,
                    StepStatus.SUCCESS,
                    "Đã nạp Firmware hợp lệ ($fwDisplayName), ONT bắt đầu cập nhật & khởi động lại"
                )

                // Immediately unbind ONT Wi-Fi so 4G/5G cellular restores while ONT flashes (~2m17s)
                onUnbindNetwork?.invoke()
                unbindDone = true

                val step8Title = "Bước 8/8: Đổi Wi-Fi $step7BandLabel kết nối chính (Thủ công sau khi ONT reboot)"
                val step8Message = if (alreadyConfiguredOppositeBand) {
                    emit(
                        8,
                        step8Title,
                        StepStatus.SUCCESS,
                        "Cả 2 băng tần Wi-Fi ('$wifi24Ssid' / '$wifi5Ssid') đã được cấu hình. ONT đang khởi động lại với Firmware mới."
                    )
                    "Đã nạp Firmware ($fwDisplayName) và hoàn tất cả 2 băng tần Wi-Fi! Đợi ONT khởi động lại (~2 phút)."
                } else {
                    val passNote = if (secMode == WifiSecurityMode.OPEN) {
                        "Không mật khẩu (Open)"
                    } else {
                        "Mật khẩu: $step7Pass"
                    }
                    emit(
                        8,
                        step8Title,
                        StepStatus.PENDING,
                        "Đợi ONT khởi động lại (~2 phút), sau đó đổi Wi-Fi $step7BandLabel sang '$step7Ssid' ($passNote)"
                    )
                    "Đã đổ Bước 1-6 & Nạp Firmware ($fwDisplayName)! Đợi ONT khởi động lại (~2 phút) rồi đổi nốt Wi-Fi $step7BandLabel ('$step7Ssid')."
                }
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
                    message = step8Message,
                    beaconVerified = alreadyConfiguredOppositeBand,
                    firmwareUpdated = true,
                    firmwareFileName = fwDisplayName,
                    manualWifiStepPending = !alreadyConfiguredOppositeBand,
                    manualWifiUnit = step7Unit,
                    manualWifiBandLabel = step7BandLabel,
                    manualWifiSsid = step7Ssid,
                    manualWifiPass = step7Pass,
                    configuredSecondaryBandLabel = step6BandLabel,
                    configuredSecondarySsid = step6Ssid,
                    configuredSecondaryPass = step6Pass,
                    wifiSecurityMode = secMode.formValue,
                    resolvedConnectedBand = effectiveConnectedBand
                )
            }

            if (settlingDelayMs > 0) {
                delay(settlingDelayMs)
            }
            emit(6, step6Title, StepStatus.SUCCESS, "Đã lưu & khởi động Wi-Fi $step6BandLabel ($step6Ssid)")

            // Mandatory readiness & session sync gate over the SAME active Wi-Fi connection before Step 7 Attempt 1
            val gatewayReadyBeforeStep7 = ensureGatewayReadyBeforeStep7(
                settings = settings,
                bssid = effectiveBssid,
                previousSessionKey = lastActiveSessionKey,
                maxAttempts = 8,
                retryDelayMs = recoveryRetryDelayMs
            )
            if (!gatewayReadyBeforeStep7) {
                throw OntNetworkError(
                    "Đã lưu cấu hình Bước 1-6 (kể cả Wi-Fi $step6BandLabel '$step6Ssid'), nhưng mất kết nối tới ONT trước khi thực hiện Bước 7 ('$step7Ssid'). Hãy kiểm tra lại kết nối Wi-Fi ONT rồi bấm Chạy Lại."
                )
            }

            // Step 7: Save connected Wi-Fi band to NVRAM BEFORE allowing disconnect
            val step7Title = "Bước 7/7: Đổi tên & mật khẩu Wi-Fi $step7BandLabel (Bước cuối)"
            emit(7, step7Title, StepStatus.RUNNING, "SSID mới: $step7Ssid (Lưu NVRAM trước khi ngắt kết nối)")
            val step7Outcome = applyFinalConnectedBandWithRetries(
                unit = step7Unit,
                ssid = step7Ssid,
                password = step7Pass,
                secMode = secMode.formValue,
                settings = settings,
                bssid = effectiveBssid,
                oldWifiStillAliveMessage = "Cả 3 lần thử Bước 7 đều ngắt kết nối trước khi ONT lưu '$step7Ssid' vào NVRAM (sóng cũ vẫn còn phản hồi). Vui lòng đứng gần ONT và bấm Chạy Lại.",
                fallbackFailureMessage = "Không thể gửi cấu hình Bước 7 cho băng tần $step7BandLabel ('$step7Ssid').",
                onRetryAttempt = { nextAttempt ->
                    emit(
                        7,
                        step7Title,
                        StepStatus.RUNNING,
                        "Đang đồng bộ lại kết nối ONT để áp dụng Bước 7 (lần $nextAttempt/3)..."
                    )
                }
            )
            releaseOrClearSessionAfterFinalWifi(step7Outcome)

            // Immediately unbind process network so 4G/5G cellular restores right away
            onUnbindNetwork?.invoke()
            unbindDone = true

            // [Red Team RT-04] Skip 4.7s beacon scan wait when wirelesscfg.json already confirmed NVRAM save (HTTP 200 OK)
            val beaconVerified = if (step7Outcome.cfgSavedToNvram) {
                true
            } else {
                try {
                    onVerifyNewSsid?.invoke(wifi24Ssid, wifi5Ssid) ?: true
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    false
                }
            }

            emit(
                7,
                step7Title,
                StepStatus.SUCCESS,
                "Đã đổi Wi-Fi sang '$wifi24Ssid' / '$wifi5Ssid' và khôi phục mạng di động!"
            )

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
                message = "Cấu hình hoàn tất 7/7 bước! Wi-Fi mới: $wifi24Ssid / $wifi5Ssid",
                beaconVerified = beaconVerified,
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
                try {
                    probePauseMutex?.unlock()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * [Red Team RT-04 & RT-06] Standalone helper to configure ONLY the remaining connected Wi-Fi band
     * manually after the ONT finishes its ~2-minute firmware flash & reboot, with full 3-attempt
     * NVRAM save / old-Wi-Fi disappearance verification.
     */
    suspend fun executeManualConnectedWifiOnly(
        record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings(baseUrl = baseUrl, requestTimeoutMs = timeoutMs),
        targetUnit: Int,
        targetBandLabel: String,
        targetSsid: String,
        targetPass: String,
        targetSecMode: String = settings.wifiSecurityMode,
        bssid: String? = null
    ): MobileSetupResult = withContext(Dispatchers.IO) {
        val secMode = WifiSecurityMode.fromFormValue(targetSecMode)
            ?: throw OntValidationError("Chế độ bảo mật Wi-Fi '$targetSecMode' không hợp lệ.")
        val cleanSsid = targetSsid.trim()
        val cleanPass = if (secMode == WifiSecurityMode.OPEN) "" else targetPass.trim()
        if (cleanSsid.length !in 1..32 || !OntInputValidator.isPrintableAscii(cleanSsid)) {
            throw OntValidationError("Tên Wi-Fi $targetBandLabel phải dài từ 1..32 ký tự ASCII in được.")
        }
        if (!OntInputValidator.isPrintableAscii(cleanPass)) {
            throw OntValidationError("Mật khẩu Wi-Fi $targetBandLabel chỉ chấp nhận ký tự ASCII in được.")
        }
        if (secMode.requiresPassword && cleanPass.length !in 8..63) {
            throw OntValidationError("Mật khẩu Wi-Fi $targetBandLabel phải dài từ 8..63 ký tự.")
        }
        try {
            OntInputValidator.sanitizeInputMetacharacters(cleanSsid)
            OntInputValidator.sanitizeInputMetacharacters(cleanPass)
        } catch (e: IllegalArgumentException) {
            throw OntValidationError(e.message ?: "Tham số Wi-Fi chứa ký tự không hợp lệ")
        }
        probePauseMutex?.lock()
        var unbindDone = false
        clearSessionKeys()
        try {
            if (!probeGateway()) {
                throw OntNetworkError(
                    "Chưa kết nối được tới ONT GW040-NSv2 tại $cleanBaseUrl. " +
                        "Hãy đảm bảo ONT đã khởi động lại xong (~2 phút) và điện thoại đang bắt sóng Wi-Fi của ONT."
                )
            }

            loginAndChangePassword(
                defaultPass = settings.adminDefaultPass,
                newPass = settings.adminNewPass,
                bssid = bssid,
                lastKnownAdminPass = record.lastKnownAdminPass.ifBlank { settings.adminNewPass }
            )
            onAdminPasswordChanged?.invoke(settings.adminNewPass)

            val outcome = applyFinalConnectedBandWithRetries(
                unit = targetUnit,
                ssid = cleanSsid,
                password = cleanPass,
                secMode = secMode.formValue,
                settings = settings,
                bssid = bssid,
                oldWifiStillAliveMessage = "Cả 3 lần thử đổi Wi-Fi $targetBandLabel ('$cleanSsid') đều ngắt kết nối khi sóng cũ vẫn còn hoạt động. Vui lòng đứng gần ONT và thử lại.",
                fallbackFailureMessage = "Không thể lưu cấu hình Wi-Fi $targetBandLabel ('$cleanSsid')."
            )
            releaseOrClearSessionAfterFinalWifi(outcome)

            onUnbindNetwork?.invoke()
            unbindDone = true

            MobileSetupResult(
                success = true,
                soMay = record.slid,
                slid = record.slid,
                accountPppoe = record.accountPppoe,
                newSsid24g = record.effectiveWifi24Ssid,
                newPass24g = record.effectiveWifi24Pass(secMode.formValue),
                newSsid5g = record.effectiveWifi5Ssid,
                newPass5g = record.effectiveWifi5Pass(secMode.formValue),
                updatedAdminPass = settings.adminNewPass,
                message = "Đã đổi xong Wi-Fi $targetBandLabel sang '$cleanSsid'! Hoàn tất toàn bộ cấu hình ONT.",
                beaconVerified = true,
                firmwareUpdated = settings.enableFirmwareUpdate,
                firmwareFileName = settings.firmwareFileName,
                manualWifiStepPending = false,
                manualWifiUnit = targetUnit,
                manualWifiBandLabel = targetBandLabel,
                manualWifiSsid = cleanSsid,
                manualWifiPass = cleanPass,
                wifiSecurityMode = secMode.formValue,
                resolvedConnectedBand = if (targetUnit == 1) WifiBand.BAND_5_GHZ else WifiBand.BAND_2_4_GHZ
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
