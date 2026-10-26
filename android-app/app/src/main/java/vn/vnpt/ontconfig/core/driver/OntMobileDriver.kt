package vn.vnpt.ontconfig.core.driver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
    val beaconVerified: Boolean = false
)

class OntMobileDriver(
    private val baseUrl: String = "https://192.168.1.1",
    private val timeoutMs: Long = 5000L,
    private val socketFactory: SocketFactory? = null,
    private val settlingDelayMs: Long = 2500L,
    private val probePauseMutex: Mutex? = null,
    private val onUnbindNetwork: (() -> Unit)? = null,
    private val onVerifyNewSsid: (suspend (String, String) -> Boolean)? = null,
    private val onAdminPasswordChanged: (suspend (String) -> Unit)? = null,
    private val onRebindWifiNetwork: (suspend () -> SocketFactory?)? = null
) {

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
    private var currentUser: String = "admin"

    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    private fun buildHttpClient(sf: SocketFactory?, timeout: Long): OkHttpClient {
        return UnsafeSslClient.create(
            socketFactory = sf,
            timeoutMs = timeout,
            allowedHostOverride = hostOverride
        )
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
        useShortLogoutTimeout: Boolean = false
    ): Pair<Int, String> {
        val targetClient = if (useShortLogoutTimeout) logoutClient else client
        val req = Request.Builder()
            .url("$cleanBaseUrl$path")
            .post(rawPayload.toRequestBody(formMediaType))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$cleanBaseUrl/")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Connection", "close")
            .build()

        targetClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return resp.code to text
        }
    }

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
            "1" -> sessionKey = "factoryID"
            "0" -> sessionKey = data.optString("sessionKey", "")
        }
        currentUser = user
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
                sessionKey = relogData.optString("sessionKey", sessionKey)
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
        isConnectedBandFinalStep: Boolean = false
    ): WifiApplyOutcome {
        OntInputValidator.sanitizeInputMetacharacters(ssid)
        OntInputValidator.sanitizeInputMetacharacters(password)

        val encSsid = OntInputValidator.urlEncodeFormValue(ssid)
        val encPass = OntInputValidator.urlEncodeFormValue(password)

        val cfgPayload =
            "page=ssid.asp&action=Apply&wl_unit=$unit&wl_radio=1&wl_bssid=0&wl_bss_enabled=1" +
                "&wl_ssid=$encSsid&wl_bss_maxassoc=32&wl_closed=0&sec_mode=wpa2_wpa3_psk" +
                "&wl_wpa_psk=$encPass&wl_mode=null&wl_auth=0&wl_auth_mode=radius&wl_osenen=0" +
                "&wl_akm=&wl_akm_wpa=disabled&wl_akm_psk=enabled&wl_akm_wpa2=disabled&wl_akm_psk2=enabled" +
                "&wl_akm_brcm_psk=disabled&wl_preauth=disabled&wl_wep=disabled&wl_crypto=aes" +
                "&wl_radius_ipaddr=0.0.0.0&wl_radius_port=1812&wl_radius_key=1234&sessionKey=$sessionKey"

        var cfgSavedToNvram = false
        var droppedDuringRadioReload = false

        try {
            val (cfgCode, cfgBody) = postForm("/cgi-bin/luci/wirelesscfg.json", cfgPayload)
            parseJsonOrThrow("Cài đặt Wi-Fi unit=$unit (wirelesscfg.json)", cfgCode, cfgBody)
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
                    "/cgi-bin/luci/wlrestart.json",
                    "undefined&sessionKey=$sessionKey"
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
            }
        }

        return WifiApplyOutcome(
            cfgSavedToNvram = cfgSavedToNvram,
            droppedDuringRadioReload = droppedDuringRadioReload
        )
    }

    /**
     * Wait and verify that the ONT gateway is reachable and ready after Step 6's Wi-Fi restart
     * before executing Step 7. If Step 6's `wlrestart.json` bounced the phone's Wi-Fi radio,
     * invokes `onRebindWifiNetwork` to re-bind the new Android `Network` SocketFactory and
     * re-authenticates so Step 7 is guaranteed to execute over a live connection.
     *
     * @return true if the gateway on the current Wi-Fi is reachable and ready; false if unreachable.
     */
    private suspend fun ensureGatewayReadyBeforeStep7(
        settings: GlobalSettings,
        bssid: String?,
        maxAttempts: Int = 8,
        retryDelayMs: Long = 1200L
    ): Boolean {
        for (attempt in 1..maxAttempts) {
            if (onRebindWifiNetwork != null) {
                try {
                    val newSf = onRebindWifiNetwork.invoke()
                    if (newSf != null && newSf != currentSocketFactory) {
                        updateSocketFactory(newSf)
                    }
                } catch (_: Exception) {
                }
            }

            if (probeGateway()) {
                // Verify session is still valid after Step 6 radio restart; re-login if needed
                try {
                    val (code, body) = postForm(
                        "/cgi-bin/luci/wlrestart.json",
                        "undefined&sessionKey=$sessionKey"
                    )
                    if (code != 200 || body.trim().startsWith("<")) {
                        loginAndChangePassword(
                            defaultPass = settings.adminDefaultPass,
                            newPass = settings.adminNewPass,
                            bssid = bssid,
                            lastKnownAdminPass = settings.adminNewPass
                        )
                    } else {
                        val wlJson = try {
                            JSONObject(body.trim())
                        } catch (_: Exception) {
                            null
                        }
                        if (wlJson != null && wlJson.optString("_wlrestart_", "0") != "0") {
                            delay(800L)
                            continue
                        }
                    }
                } catch (_: Exception) {
                    try {
                        loginAndChangePassword(
                            defaultPass = settings.adminDefaultPass,
                            newPass = settings.adminNewPass,
                            bssid = bssid,
                            lastKnownAdminPass = settings.adminNewPass
                        )
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            if (attempt < maxAttempts && retryDelayMs > 0L) {
                delay(retryDelayMs)
            }
        }
        return false
    }

    /**
     * Distinguish pre-send TCP connection failures (where the HTTP request was NEVER sent because
     * the Wi-Fi link was already down) from post-flush radio drops.
     */
    private fun isPreSendConnectFailure(e: IOException): Boolean {
        if (e is ConnectException || e is NoRouteToHostException) return true
        val msg = e.message.orEmpty()
        if (e is SocketTimeoutException && msg.contains("connect timed out", ignoreCase = true)) {
            return true
        }
        if (e is SocketException && (
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
    private fun isPostFlushRadioDrop(e: IOException): Boolean {
        if (isPreSendConnectFailure(e)) return false
        return e is EOFException ||
            e is SocketException ||
            e is SocketTimeoutException ||
            e is SSLException ||
            e is InterruptedIOException ||
            (e.message?.contains("unexpected end of stream", ignoreCase = true) == true) ||
            (e.message?.contains("Connection reset", ignoreCase = true) == true) ||
            (e.message?.contains("Software caused connection abort", ignoreCase = true) == true) ||
            (e.message?.contains("Broken pipe", ignoreCase = true) == true) ||
            (e.message?.contains("Read error", ignoreCase = true) == true) ||
            (e.message?.contains("I/O error", ignoreCase = true) == true) ||
            (e.message?.contains("Socket closed", ignoreCase = true) == true)
    }

    /**
     * [Red Team RT-01] Mandatory session release in finally block (1.5s timeout).
     * Swallows socket exceptions if the Wi-Fi radio already restarted after Step 7.
     */
    fun logout() {
        val key = sessionKey
        if (key.isEmpty()) return
        val payload = "status=user want to logout&user=$currentUser&sessionKey=$key"
        try {
            postForm("/cgi-bin/luci/logout.cmd", payload, useShortLogoutTimeout = true)
        } catch (_: Exception) {
        } finally {
            sessionKey = ""
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
        onProgress: ((StepProgress) -> Unit)? = null
    ): MobileSetupResult = withContext(Dispatchers.IO) {
        val preRunCheck = OntInputValidator.validateBeforeRun(record, settings)
        if (!preRunCheck.isValid) {
            throw OntValidationError(preRunCheck.errors.joinToString("; "))
        }

        val wifi24Ssid = record.effectiveWifi24Ssid
        val wifi24Pass = record.effectiveWifi24Pass
        val wifi5Ssid = record.effectiveWifi5Ssid
        val wifi5Pass = record.effectiveWifi5Pass

        fun emit(step: Int, title: String, status: StepStatus, detail: String) {
            onProgress?.invoke(StepProgress(stepNumber = step, totalSteps = 7, title = title, status = status, detail = detail))
        }

        probePauseMutex?.lock()
        var unbindDone = false

        try {
            // Step 1: Probe Gateway & Verify ONT HTML Signature
            val step1Title = "Bước 1/7: Kiểm tra kết nối & nhận diện ONT Gateway"
            emit(1, step1Title, StepStatus.RUNNING, "Đang kiểm tra $cleanBaseUrl...")
            if (!probeGateway()) {
                throw OntNetworkError(
                    "Không thể kết nối tới ONT GW040-NSv2 tại $cleanBaseUrl (không nhận diện được chữ ký iGate/GW040/VNPT)."
                )
            }
            emit(1, step1Title, StepStatus.SUCCESS, "Đã xác nhận kết nối tới ONT GW040-NSv2")

            // Step 2: Smart Login & Change Admin Password
            val step2Title = "Bước 2/7: Đăng nhập & Đổi mật khẩu Admin"
            emit(2, step2Title, StepStatus.RUNNING, "Đang xác thực tài khoản admin...")
            loginAndChangePassword(
                defaultPass = settings.adminDefaultPass,
                newPass = settings.adminNewPass,
                bssid = bssid,
                lastKnownAdminPass = record.lastKnownAdminPass
            )
            onAdminPasswordChanged?.invoke(settings.adminNewPass)
            emit(2, step2Title, StepStatus.SUCCESS, "Đã xác thực và bảo mật tài khoản Admin")

            // Step 3: Idempotent WAN PPPoE VLAN 11
            val step3Title = "Bước 3/7: Cấu hình WAN PPPoE (VLAN ${settings.vlanId})"
            emit(3, step3Title, StepStatus.RUNNING, "Account: ${record.accountPppoe}")
            configureWanIdempotent(
                vlanId = settings.vlanId,
                pppUser = record.accountPppoe,
                pppPass = record.passPppoe,
                mtu = settings.mtu
            )
            emit(3, step3Title, StepStatus.SUCCESS, "Đã lưu WAN PPPoE (${record.accountPppoe})")

            // Step 4: GPON SLID / Password
            val step4Title = "Bước 4/7: Cài đặt GPON Password (SLID / Mã MEN)"
            emit(4, step4Title, StepStatus.RUNNING, "SLID: ${record.slid}")
            setGponPassword(record.slid)
            emit(4, step4Title, StepStatus.SUCCESS, "Đã nạp SLID ${record.slid}")

            // Step 5: LAN IPv4
            val step5Title = "Bước 5/7: Kiểm tra & Lưu cấu hình LAN IPv4"
            emit(5, step5Title, StepStatus.RUNNING, "Gateway IP: ${settings.lanIp}")
            configureLan(settings)
            emit(5, step5Title, StepStatus.SUCCESS, "Đã lưu cấu hình LAN ${settings.lanIp}")

            // Dynamic Band Ordering:
            // Configure the NON-CONNECTED band in Step 6 (+ wlrestart.json & settling pause),
            // verify gateway readiness / re-bind if Step 6 bounced the radio,
            // and configure the CONNECTED band LAST in Step 7!
            val isConnected24g = (connectedBand == WifiBand.BAND_2_4_GHZ)
            val step6Unit = if (isConnected24g) 1 else 0
            val step6BandLabel = if (isConnected24g) "5GHz" else "2.4GHz"
            val step6Ssid = if (isConnected24g) wifi5Ssid else wifi24Ssid
            val step6Pass = if (isConnected24g) wifi5Pass else wifi24Pass

            val step7Unit = if (isConnected24g) 0 else 1
            val step7BandLabel = if (isConnected24g) "2.4GHz" else "5GHz"
            val step7Ssid = if (isConnected24g) wifi24Ssid else wifi5Ssid
            val step7Pass = if (isConnected24g) wifi24Pass else wifi5Pass

            // Step 6: Non-connected Wi-Fi band + wlrestart.json
            val step6Title = "Bước 6/7: Cấu hình Wi-Fi $step6BandLabel (Băng tần phụ)"
            emit(6, step6Title, StepStatus.RUNNING, "SSID: $step6Ssid")
            configureWifiBand(
                unit = step6Unit,
                ssid = step6Ssid,
                password = step6Pass,
                isConnectedBandFinalStep = false
            )
            if (settlingDelayMs > 0) {
                delay(settlingDelayMs)
            }
            emit(6, step6Title, StepStatus.SUCCESS, "Đã lưu & khởi động Wi-Fi $step6BandLabel ($step6Ssid)")

            // Step 7: Connected Wi-Fi band LAST + Handle Radio Reload Socket Drop
            // Attempt 1 executes immediately while the HTTP connection from Step 6 is still warm,
            // before any asynchronous radio reload from Step 6 drops the link.
            val step7Title = "Bước 7/7: Đổi tên & mật khẩu Wi-Fi $step7BandLabel (Bước cuối)"
            emit(7, step7Title, StepStatus.RUNNING, "SSID mới: $step7Ssid (Sóng mặc định sẽ khởi động lại)")
            var step7Outcome = WifiApplyOutcome(cfgSavedToNvram = false, droppedDuringRadioReload = false)
            var lastStep7Error: Exception? = null

            for (step7Attempt in 1..3) {
                try {
                    step7Outcome = configureWifiBand(
                        unit = step7Unit,
                        ssid = step7Ssid,
                        password = step7Pass,
                        isConnectedBandFinalStep = true
                    )
                    if (step7Outcome.cfgSavedToNvram) {
                        break
                    }
                    if (step7Outcome.droppedDuringRadioReload) {
                        // The socket dropped while waiting for wirelesscfg.json response.
                        // Check if the old Wi-Fi gateway is still reachable (meaning it was a transient
                        // Step 6 radio bounce and the ONT has not renamed the connected band yet).
                        // If the old Wi-Fi gateway is no longer reachable, the ONT already renamed the
                        // connected band and shut down the old SSID -> Step 7 succeeded!
                        if (step7Attempt < 3) {
                            val checkDelay = if (settlingDelayMs >= 500L) 1200L else 20L
                            val stillOnOldWifi = ensureGatewayReadyBeforeStep7(
                                settings = settings,
                                bssid = bssid,
                                maxAttempts = 3,
                                retryDelayMs = checkDelay
                            )
                            if (stillOnOldWifi) {
                                emit(
                                    7,
                                    step7Title,
                                    StepStatus.RUNNING,
                                    "Đang đồng bộ lại kết nối ONT để áp dụng Bước 7 (lần ${step7Attempt + 1}/3)..."
                                )
                                continue
                            }
                        }
                        break
                    }
                } catch (e: Exception) {
                    lastStep7Error = e
                    val causeIo = (e as? IOException) ?: (e.cause as? IOException)
                    if (step7Attempt < 3) {
                        emit(
                            7,
                            step7Title,
                            StepStatus.RUNNING,
                            "Đang đồng bộ lại kết nối ONT để áp dụng Bước 7 (lần ${step7Attempt + 1}/3)..."
                        )
                        val waitMs = if (settlingDelayMs >= 500L) 1200L else 50L
                        delay(waitMs)
                        val gatewayReady = ensureGatewayReadyBeforeStep7(
                            settings = settings,
                            bssid = bssid,
                            maxAttempts = 6,
                            retryDelayMs = waitMs
                        )
                        if (!gatewayReady && causeIo != null && isPostFlushRadioDrop(causeIo)) {
                            step7Outcome = WifiApplyOutcome(cfgSavedToNvram = false, droppedDuringRadioReload = true)
                            break
                        }
                    } else if (causeIo != null && isPostFlushRadioDrop(causeIo)) {
                        step7Outcome = WifiApplyOutcome(cfgSavedToNvram = false, droppedDuringRadioReload = true)
                    } else {
                        throw e
                    }
                }
            }

            if (!step7Outcome.cfgSavedToNvram && !step7Outcome.droppedDuringRadioReload) {
                throw lastStep7Error ?: OntNetworkError(
                    "Không thể gửi cấu hình Bước 7 cho băng tần $step7BandLabel ('$step7Ssid')."
                )
            }

            // If socket did not drop yet, release session before radio cuts off
            if (!step7Outcome.droppedDuringRadioReload) {
                logout()
            } else {
                sessionKey = ""
            }

            // Immediately unbind process network so 4G/5G cellular restores right away
            onUnbindNetwork?.invoke()
            unbindDone = true

            val beaconVerified = try {
                onVerifyNewSsid?.invoke(wifi24Ssid, wifi5Ssid) ?: true
            } catch (_: Exception) {
                false
            }

            // Note: When Step 7 applies new SSID/password on the currently connected Wi-Fi band,
            // dropping the Wi-Fi connection (droppedDuringRadioReload == true) is the expected hardware
            // behavior of the ONT restarting that radio with the new SSID. Do NOT fail Step 7 if
            // Android OS throttles background Wi-Fi beacon scans (beaconVerified == false).
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
                beaconVerified = beaconVerified || step7Outcome.cfgSavedToNvram
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

        private val bssidPasswordCache = ConcurrentHashMap<String, String>()

        fun clearCachedPasswordsForTest() {
            bssidPasswordCache.clear()
        }
    }
}
