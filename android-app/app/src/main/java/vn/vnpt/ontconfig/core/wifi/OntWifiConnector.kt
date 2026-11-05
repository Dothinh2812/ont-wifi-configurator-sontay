package vn.vnpt.ontconfig.core.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class WifiBand(val label: String, val wlUnit: Int) {
    BAND_2_4_GHZ("2.4GHz", 0),
    BAND_5_GHZ("5GHz", 1)
}

data class WifiScanCandidate(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val isOpen: Boolean,
    val band: WifiBand = OntWifiConnector.detectBandFromFrequency(frequencyMhz, ssid)
)

class OntWifiConnector(private val context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var passiveWifiMonitorCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var boundWifiNetwork: Network? = null
        private set


    @Volatile
    private var specifierWifiNetwork: Network? = null
    @Volatile
    private var lastSelectedSsid: String = ""

    @Volatile
    private var lastSelectedBssid: String = ""

    @Volatile
    private var lastSelectedBand: WifiBand? = null

    @Volatile
    private var lastSelectedPassphrase: String? = null

    @Volatile
    private var lastSelectedSecurityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue
    @Volatile
    private var legacyAddedNetworkId: Int = -1

    /**
     * Register a passive system Wi-Fi listener so the moment Android associates with an ONT Wi-Fi
     * or finishes DHCP (192.168.1.x), the process binds to it and triggers an immediate gateway probe.
     */
    @SuppressLint("MissingPermission")
    fun startMonitoringSystemWifi(
        shouldAutoBind: () -> Boolean = { true },
        onWifiReady: () -> Unit
    ) {
        stopMonitoringSystemWifi()
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!shouldAutoBind()) return
                    bindCurrentWifiNetworkIfConnected()
                    onWifiReady()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (!shouldAutoBind()) return
                    bindCurrentWifiNetworkIfConnected()
                    onWifiReady()
                }

                override fun onLost(network: Network) {
                    if (boundWifiNetwork == network && shouldAutoBind()) {
                        bindCurrentWifiNetworkIfConnected()
                        onWifiReady()
                    }
                }
            }
            passiveWifiMonitorCallback = cb
            connectivityManager.registerNetworkCallback(req, cb)
        } catch (_: Exception) {
        }
    }

    fun stopMonitoringSystemWifi() {
        val cb = passiveWifiMonitorCallback
        passiveWifiMonitorCallback = null
        if (cb != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * [Red Team RT-10] Scan nearby Wi-Fi networks and filter strictly for factory-default
     * ONT SSIDs: GW040-NSv2_2.4G_XXXXXX or GW040-NSv2_5G_XXXXXX.
     */
    @SuppressLint("MissingPermission")
    fun scanOntNetworks(triggerStartScan: Boolean = false): List<WifiScanCandidate> {
        if (triggerStartScan) {
            tryRequestStartScan()
        }
        val rawResults: List<ScanResult> = try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        val candidates = rawResults.mapNotNull { sr ->
            @Suppress("DEPRECATION")
            val ssid = (sr.SSID ?: "").removeSurrounding("\"")
            val bssid = sr.BSSID ?: ""
            if (ssid.isBlank()) return@mapNotNull null
            val caps = sr.capabilities ?: ""
            val isOpen = !caps.contains("WPA", ignoreCase = true) &&
                !caps.contains("WEP", ignoreCase = true) &&
                !caps.contains("SAE", ignoreCase = true)
            WifiScanCandidate(
                ssid = ssid,
                bssid = bssid,
                rssi = sr.level,
                frequencyMhz = sr.frequency,
                isOpen = isOpen,
                band = detectBandFromFrequency(sr.frequency, ssid)
            )
        }
        return filterAndRankOntNetworks(candidates)
    }

    private fun tryRequestStartScan(): Boolean {
        if (!tryConsumeProcessScanQuota()) return false
        return try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (_: Exception) {
            false
        }
    }

    fun is5GHzBandSupported(): Boolean = try {
        wifiManager.is5GHzBandSupported
    } catch (_: Exception) {
        true
    }

    fun isWpa3SaeSupported(): Boolean = isWpa3SaeSupportedSafe()

    fun isLocationServiceEnabled(): Boolean = try {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Exception) {
        false
    }

    fun validateHandoverSecurityCapability(secMode: String): String? =
        validateHandoverSecurityCapability(
            secMode = secMode,
            sdkInt = Build.VERSION.SDK_INT,
            isWpa3SaeSupported = isWpa3SaeSupported()
        )

    @SuppressLint("NewApi")
    private fun isWpa3SaeSupportedSafe(): Boolean = try {
        wifiManager.isWpa3SaeSupported
    } catch (_: Exception) {
        true
    }
    @SuppressLint("NewApi")
    private fun applySecurityToSpecifier(
        specBuilder: WifiNetworkSpecifier.Builder,
        passphrase: String?,
        securityMode: String
    ) {
        val mode = WifiSecurityMode.fromFormValue(securityMode) ?: WifiSecurityMode.WPA2_WPA3_PSK
        val pass = passphrase.orEmpty()
        if (mode == WifiSecurityMode.OPEN || pass.isBlank()) return
        if (mode == WifiSecurityMode.WPA3_PSK) {
            if (!isWpa3SaeSupportedSafe()) {
                throw IllegalStateException(
                    "Điện thoại này không hỗ trợ phần cứng WPA3-SAE. Vui lòng chọn WPA2-PSK / WPA3-PSK hoặc WPA2-PSK."
                )
            }
            specBuilder.setWpa3Passphrase(pass)
        } else {
            specBuilder.setWpa2Passphrase(pass)
        }
    }

    @SuppressLint("NewApi")
    private fun applySecurityToSuggestion(
        suggestionBuilder: WifiNetworkSuggestion.Builder,
        passphrase: String,
        securityMode: String
    ) {
        val mode = WifiSecurityMode.fromFormValue(securityMode) ?: WifiSecurityMode.WPA2_WPA3_PSK
        if (mode == WifiSecurityMode.OPEN || passphrase.isBlank()) return
        if (mode == WifiSecurityMode.WPA3_PSK && isWpa3SaeSupportedSafe()) {
            suggestionBuilder.setWpa3Passphrase(passphrase)
        } else {
            suggestionBuilder.setWpa2Passphrase(passphrase)
        }
    }

    /**
     * [Red Team RT-06] Request and bind specifically to the ONT Wi-Fi transport
     * (removing NET_CAPABILITY_INTERNET so Android 10-14 routes 192.168.1.1 over Wi-Fi
     * even while 4G/5G cellular data is active).
     *
     * If the phone is already connected to [ssid] via Android System Wi-Fi, binds directly
     * to the active TRANSPORT_WIFI network without triggering a conflicting WifiNetworkSpecifier
     * disconnect. Also avoids locking BSSID on WifiNetworkSpecifier so radio restarts on the ONT
     * do not invalidate the specifier.
     */
    suspend fun connectToOntWifi(
        ssid: String,
        bssid: String? = null,
        passphrase: String? = null,
        timeoutMs: Long = 15000L,
        securityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
        frequencyMhz: Int = -1,
        allowSubnetFallbackWhenSsidUnknown: Boolean = true,
        excludedNetwork: Network? = null
    ): Network = withTimeout(timeoutMs) {
        val sameSsidAsPrevious = lastSelectedSsid.isNotBlank() && lastSelectedSsid.equals(ssid, ignoreCase = true)
        val isExplicitOpen = securityMode.trim().equals(WifiSecurityMode.OPEN.formValue, ignoreCase = true)
        val effectivePassphrase = when {
            isExplicitOpen -> null
            !passphrase.isNullOrBlank() -> passphrase
            sameSsidAsPrevious -> lastSelectedPassphrase
            else -> passphrase
        }
        val effectiveSecurityMode = when {
            isExplicitOpen -> WifiSecurityMode.OPEN.formValue
            sameSsidAsPrevious && passphrase.isNullOrBlank() && !lastSelectedPassphrase.isNullOrBlank() ->
                lastSelectedSecurityMode
            else -> securityMode
        }
        val resolvedBand = if (frequencyMhz <= 0 && sameSsidAsPrevious && lastSelectedBand != null) {
            lastSelectedBand!!
        } else {
            detectBandFromFrequency(frequencyMhz, ssid)
        }
        lastSelectedSsid = ssid
        lastSelectedBssid = bssid.orEmpty().ifBlank { if (sameSsidAsPrevious) lastSelectedBssid else "" }
        lastSelectedBand = resolvedBand
        lastSelectedPassphrase = effectivePassphrase
        lastSelectedSecurityMode = effectiveSecurityMode

        val (currentSysSsid, currentSysBssid) = getSystemConnectedSsidAndBssid()
        val alreadyOnTargetWifi = if (allowSubnetFallbackWhenSsidUnknown) {
            (currentSysSsid.isNotBlank() && currentSysSsid.equals(ssid, ignoreCase = true)) ||
                (!bssid.isNullOrBlank() && currentSysBssid.isNotBlank() && isSameOntBssid(currentSysBssid, bssid)) ||
                (currentSysSsid.isBlank() && hasActiveOntSubnetWifi())
        } else {
            currentSysSsid.isNotBlank() && currentSysSsid.equals(ssid, ignoreCase = true)
        }

        if (alreadyOnTargetWifi) {
            val existingNet = bindCurrentWifiNetworkIfConnected()
            if (existingNet != null && existingNet != excludedNetwork) {
                awaitWifiIpv4Ready(existingNet, maxWaitMs = 600L)
                return@withTimeout existingNet
            }
        }

        unbindOntWifi(clearSelectedMetadata = false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ssid.isNotBlank()) {
            val legacyNet = connectLegacyApi26To28Once(
                targetSsid = ssid,
                passphrase = effectivePassphrase.orEmpty(),
                securityMode = effectiveSecurityMode,
                excludedNetwork = excludedNetwork,
                attemptTimeoutMs = timeoutMs
            )
            if (legacyNet != null) {
                awaitWifiIpv4Ready(legacyNet, maxWaitMs = 1200L)
                return@withTimeout legacyNet
            }
        }

        val connectedNet = suspendCancellableCoroutine<Network> { cont ->
            val requestBuilder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ssid.isNotBlank()) {
                val specBuilder = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                applySecurityToSpecifier(specBuilder, effectivePassphrase, effectiveSecurityMode)
                requestBuilder.setNetworkSpecifier(specBuilder.build())
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (excludedNetwork != null && network == excludedNetwork) return
                    specifierWifiNetwork = network
                    boundWifiNetwork = network
                    bindProcessNetworkSafe(network)
                    if (cont.isActive) {
                        cont.resume(network)
                    }
                }

                override fun onUnavailable() {
                    if (allowSubnetFallbackWhenSsidUnknown) {
                        val fallbackNet = bindCurrentWifiNetworkIfConnected()
                        if (fallbackNet != null && fallbackNet != excludedNetwork && cont.isActive) {
                            cont.resume(fallbackNet)
                            return
                        }
                    }
                    specifierWifiNetwork = null
                    boundWifiNetwork = null
                    bindProcessNetworkSafe(null)
                    if (cont.isActive) {
                        cont.resumeWithException(
                            IllegalStateException(
                                "Không thể tự bắt sóng '$ssid'. Hãy kết nối Wi-Fi '$ssid' trong Cài đặt máy rồi bấm 'Đã bắt tay'."
                            )
                        )
                    }
                }

                override fun onLost(network: Network) {
                    if (specifierWifiNetwork == network) {
                        specifierWifiNetwork = null
                    }
                    if (boundWifiNetwork == network) {
                        boundWifiNetwork = null
                        bindProcessNetworkSafe(null)
                    }
                }
            }

            activeNetworkCallback = callback
            connectivityManager.requestNetwork(requestBuilder.build(), callback)

            cont.invokeOnCancellation {
                unbindOntWifi(clearSelectedMetadata = false)
            }
        }
        awaitWifiIpv4Ready(connectedNet, maxWaitMs = 1200L)
        connectedNet
    }

    /**
     * [Red Team RT-03, RT-06, RT-08, RT-09] Automated Step 7 Wi-Fi Handover Engine:
     * Waits for ONT wlmngr/DFS CAC radio startup after Connection 1, excludes the old Connection 1
     * Network so GPS-off <unknown ssid> never hijacks back to Band A, respects Android 9+'s
     * process-wide 4 scans / 120s quota, uses WifiNetworkSpecifier ONLY on API 29+ (no parallel
     * WifiNetworkSuggestion), and supports WifiConfiguration on API 26-28.
     */
    @SuppressLint("MissingPermission")
    suspend fun switchToConfiguredSecondaryWifi(
        targetSsid: String,
        passphrase: String,
        targetBand: WifiBand,
        securityMode: String,
        targetBssidHint: String? = null,
        excludedNetwork: Network? = boundWifiNetwork,
        timeoutMs: Long = 120_000L,
        initialSettleMs: Long = 4_000L,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Network {
        val capabilityErr = validateHandoverSecurityCapability(securityMode)
        if (capabilityErr != null) {
            throw IllegalStateException(capabilityErr)
        }

        val prevNetwork = excludedNetwork ?: boundWifiNetwork
        val isExplicitOpen = securityMode.trim().equals(WifiSecurityMode.OPEN.formValue, ignoreCase = true)
        unbindOntWifi(clearSelectedMetadata = false)
        lastSelectedSsid = targetSsid
        lastSelectedBssid = ""
        lastSelectedBand = targetBand
        lastSelectedPassphrase = if (isExplicitOpen) null else passphrase
        lastSelectedSecurityMode = securityMode

        val startRealtimeMs = SystemClock.elapsedRealtime()
        val deadlineMs = startRealtimeMs + timeoutMs.coerceAtLeast(5_000L)
        val handoverStartMicros = SystemClock.elapsedRealtimeNanos() / 1_000L

        // Phase A: Wait for ONT wlmngr / acsd / DFS CAC to bring up targetSsid
        onStatusUpdate?.invoke("Đang đợi ONT khởi động lại sóng & phát Wi-Fi '$targetSsid' (${targetBand.label})...")
        val settleStep1 = minOf(initialSettleMs.coerceAtLeast(0L), (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        if (settleStep1 > 0L) {
            delay(settleStep1)
        }

        findAlreadyConnectedTargetNetwork(targetSsid, prevNetwork)?.let { existing ->
            if (awaitOntSubnetIpv4Ready(existing, maxWaitMs = 5_000L)) {
                boundWifiNetwork = existing
                bindProcessNetworkSafe(existing)
                return existing
            }
        }

        if (isLocationServiceEnabled()) {
            val scanUpdated = java.util.concurrent.atomic.AtomicBoolean(false)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    scanUpdated.set(true)
                }
            }
            var receiverRegistered = false
            try {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            } catch (_: Exception) {
            }

            try {
                val phaseABudgetMs = minOf(
                    35_000L,
                    ((deadlineMs - SystemClock.elapsedRealtime()) / 3L).coerceAtLeast(0L)
                )
                val phaseADeadlineMs = SystemClock.elapsedRealtime() + phaseABudgetMs
                var activeScansIssued = 0
                var lastScanTriggerMs = 0L

                while (SystemClock.elapsedRealtime() < phaseADeadlineMs) {
                    if (isTargetSsidVisibleInScanResults(targetSsid, targetBssidHint, handoverStartMicros)) {
                        onStatusUpdate?.invoke("Đã phát hiện sóng '$targetSsid' (${targetBand.label}), bắt đầu ghép nối...")
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (activeScansIssued < 2 && (activeScansIssued == 0 || now - lastScanTriggerMs >= 12_000L)) {
                        if (tryRequestStartScan()) {
                            activeScansIssued++
                            lastScanTriggerMs = now
                        }
                    }
                    scanUpdated.set(false)
                    var slept = 0L
                    while (slept < 1_500L && !scanUpdated.get() && SystemClock.elapsedRealtime() < phaseADeadlineMs) {
                        delay(150L)
                        slept += 150L
                    }
                }
            } finally {
                if (receiverRegistered) {
                    try {
                        context.applicationContext.unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            val gpsOffExtraSettle = minOf(4_000L, (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (gpsOffExtraSettle > 0L) {
                delay(gpsOffExtraSettle)
            }
        }

        // Phase B: Persistent association loop across remaining handover budget (up to 120s)
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            attempt++
            findAlreadyConnectedTargetNetwork(targetSsid, prevNetwork)?.let { existing ->
                if (awaitOntSubnetIpv4Ready(existing, maxWaitMs = 5_000L)) {
                    boundWifiNetwork = existing
                    bindProcessNetworkSafe(existing)
                    return existing
                }
            }

            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break

            onStatusUpdate?.invoke(
                "Đang ghép nối sang Wi-Fi '$targetSsid' (${targetBand.label}, lượt $attempt — bấm 'Kết nối' nếu hộp thoại hiện lên)..."
            )

            val candidateNet: Network? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val perAttemptMs = minOf(45_000L, remainingMs)
                requestSpecifierNetworkOnce(
                    targetSsid = targetSsid,
                    passphrase = if (isExplicitOpen) null else passphrase,
                    securityMode = securityMode,
                    excludedNetwork = prevNetwork,
                    attemptTimeoutMs = perAttemptMs
                )
            } else {
                val perAttemptMs = minOf(25_000L, remainingMs)
                connectLegacyApi26To28Once(
                    targetSsid = targetSsid,
                    passphrase = passphrase,
                    securityMode = securityMode,
                    excludedNetwork = prevNetwork,
                    attemptTimeoutMs = perAttemptMs
                )
            }

            if (candidateNet != null && candidateNet != prevNetwork) {
                val dhcpWaitMs = minOf(5_000L, (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(800L))
                if (awaitOntSubnetIpv4Ready(candidateNet, maxWaitMs = dhcpWaitMs)) {
                    boundWifiNetwork = candidateNet
                    bindProcessNetworkSafe(candidateNet)
                    return candidateNet
                }
            }

            unbindOntWifi(clearSelectedMetadata = false)
            val backoffMs = minOf(2_500L, (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (backoffMs > 0L) {
                delay(backoffMs)
            }
        }

        throw IllegalStateException(
            "Hết thời gian chờ (${timeoutMs / 1000}s) chuyển kết nối sang Wi-Fi mới '$targetSsid' (${targetBand.label}). " +
                "Hãy đứng gần ONT, kiểm tra hộp thoại ghép nối Wi-Fi và thử lại."
        )
    }

    @SuppressLint("MissingPermission")
    private fun isTargetSsidVisibleInScanResults(
        targetSsid: String,
        targetBssidHint: String?,
        minTimestampMicros: Long
    ): Boolean {
        val results = try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        return results.any { sr ->
            @Suppress("DEPRECATION")
            val ssid = (sr.SSID ?: "").removeSurrounding("\"")
            val isFresh = sr.timestamp <= 0L || sr.timestamp >= minTimestampMicros - 5_000_000L
            if (!isFresh || ssid != targetSsid) return@any false
            if (!targetBssidHint.isNullOrBlank() && !sr.BSSID.isNullOrBlank()) {
                isSameOntBssid(sr.BSSID, targetBssidHint)
            } else {
                true
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun findAlreadyConnectedTargetNetwork(
        targetSsid: String,
        excludedNetwork: Network?
    ): Network? {
        if (targetSsid.isBlank()) return null
        val (sysSsid, _) = getSystemConnectedSsidAndBssid()
        for (net in getAllNetworksSafe()) {
            if (!isWifiTransport(net) || net == excludedNetwork) continue
            val netSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wifiInfo = try {
                    connectivityManager.getNetworkCapabilities(net)?.transportInfo as? WifiInfo
                } catch (_: Exception) {
                    null
                }
                val raw = wifiInfo?.ssid?.removeSurrounding("\"").orEmpty()
                if (raw.isNotBlank() && raw != "<unknown ssid>") raw else sysSsid
            } else {
                sysSsid
            }
            if (netSsid.isNotBlank() && netSsid.equals(targetSsid, ignoreCase = true)) {
                if (boundWifiNetwork != net) {
                    boundWifiNetwork = net
                    bindProcessNetworkSafe(net)
                }
                return net
            }
        }
        val activeNet = specifierWifiNetwork ?: boundWifiNetwork
        if (
            activeNet != null &&
            activeNet != excludedNetwork &&
            lastSelectedSsid.isNotBlank() &&
            lastSelectedSsid.equals(targetSsid, ignoreCase = true) &&
            getAllNetworksSafe().contains(activeNet) &&
            isWifiTransport(activeNet) &&
            getWifiNetworkScore(activeNet) >= 3
        ) {
            if (boundWifiNetwork != activeNet) {
                boundWifiNetwork = activeNet
                bindProcessNetworkSafe(activeNet)
            }
            return activeNet
        }
        return null
    }

    @SuppressLint("NewApi")
    private suspend fun requestSpecifierNetworkOnce(
        targetSsid: String,
        passphrase: String?,
        securityMode: String,
        excludedNetwork: Network?,
        attemptTimeoutMs: Long
    ): Network? {
        return try {
            withTimeout(attemptTimeoutMs.coerceAtLeast(1_000L)) {
                suspendCancellableCoroutine<Network?> { cont ->
                    val specBuilder = WifiNetworkSpecifier.Builder().setSsid(targetSsid)
                    applySecurityToSpecifier(specBuilder, passphrase, securityMode)
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specBuilder.build())
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (excludedNetwork != null && network == excludedNetwork) return
                            specifierWifiNetwork = network
                            boundWifiNetwork = network
                            bindProcessNetworkSafe(network)
                            if (cont.isActive) {
                                cont.resume(network)
                            }
                        }

                        override fun onUnavailable() {
                            if (cont.isActive) {
                                cont.resume(null)
                            }
                        }

                        override fun onLost(network: Network) {
                            if (specifierWifiNetwork == network) {
                                specifierWifiNetwork = null
                            }
                            if (boundWifiNetwork == network) {
                                boundWifiNetwork = null
                                bindProcessNetworkSafe(null)
                            }
                        }
                    }

                    activeNetworkCallback = callback
                    connectivityManager.requestNetwork(request, callback)
                    cont.invokeOnCancellation {
                        if (boundWifiNetwork == null) {
                            try {
                                connectivityManager.unregisterNetworkCallback(callback)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun connectLegacyApi26To28Once(
        targetSsid: String,
        passphrase: String,
        securityMode: String,
        excludedNetwork: Network?,
        attemptTimeoutMs: Long
    ): Network? {
        removeLegacyAddedNetworkSafe()
        val mode = WifiSecurityMode.fromFormValue(securityMode) ?: WifiSecurityMode.WPA2_WPA3_PSK
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"$targetSsid\""
            if (mode == WifiSecurityMode.OPEN) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$passphrase\""
            }
        }
        val netId = try {
            wifiManager.addNetwork(wifiConfig)
        } catch (_: Exception) {
            -1
        }
        if (netId == -1) return null
        legacyAddedNetworkId = netId
        try {
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
        } catch (_: Exception) {
            return null
        }

        val deadline = SystemClock.elapsedRealtime() + attemptTimeoutMs.coerceAtLeast(1_500L)
        while (SystemClock.elapsedRealtime() < deadline) {
            val matchedBySsid = findAlreadyConnectedTargetNetwork(targetSsid, excludedNetwork)
            if (matchedBySsid != null) {
                boundWifiNetwork = matchedBySsid
                bindProcessNetworkSafe(matchedBySsid)
                return matchedBySsid
            }
            val nonExcludedSubnetNet = getAllNetworksSafe().firstOrNull { net ->
                net != excludedNetwork && isWifiTransport(net) && getWifiNetworkScore(net) >= 3
            }
            if (nonExcludedSubnetNet != null) {
                boundWifiNetwork = nonExcludedSubnetNet
                bindProcessNetworkSafe(nonExcludedSubnetNet)
                return nonExcludedSubnetNet
            }
            delay(300L)
        }
        return null
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun removeLegacyAddedNetworkSafe() {
        val netId = legacyAddedNetworkId
        legacyAddedNetworkId = -1
        if (netId != -1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                wifiManager.disableNetwork(netId)
                wifiManager.removeNetwork(netId)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun awaitOntSubnetIpv4Ready(network: Network, maxWaitMs: Long = 5_000L): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < maxWaitMs) {
            if (getWifiNetworkScore(network) >= 3) return true
            delay(100L)
        }
        return getWifiNetworkScore(network) >= 3
    }

    /**
     * Wait briefly after Wi-Fi L2 association for DHCP to assign an IPv4 address (192.168.1.x)
     * so the immediate HTTP probe to 192.168.1.1 does not fail with ENETUNREACH.
     */
    private suspend fun awaitWifiIpv4Ready(network: Network, maxWaitMs: Long = 1200L) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < maxWaitMs) {
            if (getWifiNetworkScore(network) >= 2) return
            delay(80L)
        }
    }

    private fun getWifiNetworkScore(net: Network): Int {
        val linkProps = try {
            connectivityManager.getLinkProperties(net)
        } catch (_: Exception) {
            null
        } ?: return 1

        var hasIpv4 = false
        for (la in linkProps.linkAddresses) {
            val addr = la.address
            if (addr is Inet4Address) {
                hasIpv4 = true
                val hostAddr = addr.hostAddress.orEmpty()
                if (hostAddr.startsWith("192.168.1.")) {
                    return 3
                }
            }
        }
        for (route in linkProps.routes) {
            val gw = route.gateway?.hostAddress.orEmpty()
            if (gw == "192.168.1.1") {
                return 3
            }
        }
        return if (hasIpv4) 2 else 1
    }

    @Suppress("DEPRECATION")
    private fun getAllNetworksSafe(): Array<Network> = try {
        connectivityManager.allNetworks
    } catch (_: Exception) {
        emptyArray()
    }

    private fun isWifiTransport(net: Network): Boolean = try {
        connectivityManager.getNetworkCapabilities(net)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) {
        false
    }

    private fun bindProcessNetworkSafe(network: Network?) {
        try {
            connectivityManager.bindProcessToNetwork(network)
        } catch (_: Exception) {
        }
    }

    private fun hasActiveOntSubnetWifi(): Boolean =
        getAllNetworksSafe().any { net -> isWifiTransport(net) && getWifiNetworkScore(net) >= 3 }

    /**
     * Bind to the best currently connected Wi-Fi network (preferring 192.168.1.x ONT subnet)
     * and automatically clear stale boundWifiNetwork if Wi-Fi disconnected.
     */
    @SuppressLint("MissingPermission")
    fun bindCurrentWifiNetworkIfConnected(): Network? {
        val allNetworks = getAllNetworksSafe()
        val specNet = specifierWifiNetwork
        if (specNet != null && allNetworks.contains(specNet)) {
            if (boundWifiNetwork != specNet) {
                boundWifiNetwork = specNet
                bindProcessNetworkSafe(specNet)
            }
            return specNet
        }

        val bestNet = allNetworks
            .filter(::isWifiTransport)
            .maxByOrNull(::getWifiNetworkScore)

        if (bestNet != null) {
            if (boundWifiNetwork != bestNet) {
                boundWifiNetwork = bestNet
                bindProcessNetworkSafe(bestNet)
            }
            return bestNet
        }

        if (boundWifiNetwork != null) {
            boundWifiNetwork = null
            bindProcessNetworkSafe(null)
        }
        return null
    }

    /**
     * Return the phone's current ONT-subnet IPv4 address (`192.168.1.x`, `!= 192.168.1.1`)
     * from the bound or active Wi-Fi `LinkProperties` without requiring Location permissions.
     */
    @SuppressLint("MissingPermission")
    fun getBoundWifiIpv4Address(): String? {
        val candidateNets = linkedSetOf<Network>().apply {
            boundWifiNetwork?.let(::add)
            specifierWifiNetwork?.let(::add)
            getAllNetworksSafe().filterTo(this, ::isWifiTransport)
        }
        for (net in candidateNets) {
            val linkProps = try {
                connectivityManager.getLinkProperties(net)
            } catch (_: Exception) {
                null
            } ?: continue
            for (la in linkProps.linkAddresses) {
                val addr = la.address
                if (addr is Inet4Address) {
                    val hostAddr = addr.hostAddress.orEmpty().trim()
                    if (hostAddr.startsWith("192.168.1.") && hostAddr != "192.168.1.1") {
                        return hostAddr
                    }
                }
            }
        }
        return null
    }

    /**
     * [Red Team RT-06] Mandatory cleanup: unbind process network so phone immediately restores
     * 4G/5G cellular routing (e.g. for sharing exported Excel reports via Zalo).
     */
    fun unbindOntWifi(clearSelectedMetadata: Boolean = true) {
        bindProcessNetworkSafe(null)
        specifierWifiNetwork = null
        boundWifiNetwork = null
        removeLegacyAddedNetworkSafe()
        if (clearSelectedMetadata) {
            lastSelectedSsid = ""
            lastSelectedBssid = ""
            lastSelectedBand = null
            lastSelectedPassphrase = null
            lastSelectedSecurityMode = WifiSecurityMode.WPA2_WPA3_PSK.formValue
        }
        val cb = activeNetworkCallback
        activeNetworkCallback = null
        if (cb != null) {
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun detectConnectedBand(
        ssidHint: String? = null,
        configured24Ssid: String? = null,
        configured5Ssid: String? = null
    ): WifiBand {
        return try {
            val activeWifiNet = boundWifiNetwork
                ?: specifierWifiNetwork
                ?: getAllNetworksSafe().firstOrNull(::isWifiTransport)
            val transportWifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activeWifiNet != null) {
                connectivityManager.getNetworkCapabilities(activeWifiNet)?.transportInfo as? WifiInfo
            } else {
                null
            }
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val transportFreq = transportWifiInfo?.frequency ?: -1
            val freq = if (transportFreq > 0) transportFreq else (info?.frequency ?: -1)
            val transportSsid = transportWifiInfo?.ssid?.removeSurrounding("\"").orEmpty()
            val rawSsid = if (transportSsid.isNotBlank() && transportSsid != "<unknown ssid>") {
                transportSsid
            } else {
                info?.ssid?.removeSurrounding("\"") ?: ""
            }
            val cleanSsid = if (rawSsid == "<unknown ssid>" || rawSsid.isBlank()) {
                ssidHint?.ifBlank { lastSelectedSsid } ?: lastSelectedSsid
            } else {
                rawSsid
            }
            when {
                freq >= 4900 -> WifiBand.BAND_5_GHZ
                freq in 2400..2500 -> WifiBand.BAND_2_4_GHZ
                cleanSsid.isNotBlank() -> detectBandFromFrequency(-1, cleanSsid, configured24Ssid, configured5Ssid)
                lastSelectedBand != null -> lastSelectedBand!!
                else -> WifiBand.BAND_2_4_GHZ
            }
        } catch (_: Exception) {
            if (!ssidHint.isNullOrBlank()) {
                detectBandFromFrequency(-1, ssidHint, configured24Ssid, configured5Ssid)
            } else {
                lastSelectedBand ?: WifiBand.BAND_2_4_GHZ
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSystemConnectedSsidAndBssid(): Pair<String, String> {
        return try {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val rawSsid = info?.ssid?.removeSurrounding("\"") ?: ""
            val ssid = if (rawSsid == "<unknown ssid>") "" else rawSsid
            val rawBssid = info?.bssid ?: ""
            val bssid = if (rawBssid == "02:00:00:00:00:00") "" else rawBssid
            ssid to bssid
        } catch (_: Exception) {
            "" to ""
        }
    }

    @SuppressLint("MissingPermission")
    fun getConnectedSsidAndBssid(): Pair<String, String> {
        val (sysSsid, sysBssid) = getSystemConnectedSsidAndBssid()
        val ssid = sysSsid.ifBlank { lastSelectedSsid }
        val bssid = sysBssid.ifBlank { lastSelectedBssid }
        return ssid to bssid
    }

    /**
     * Optional post-Step 7 action when KTV explicitly taps "Kết nối vào Wi-Fi mới để kiểm tra mạng".
     */
    fun promptJoinCustomerWifi(
        ssid: String,
        passphrase: String,
        securityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
        secondarySsid: String = "",
        secondaryPassphrase: String = ""
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val suggestions = mutableListOf<WifiNetworkSuggestion>()
                if (ssid.isNotBlank()) {
                    val primaryBuilder = WifiNetworkSuggestion.Builder().setSsid(ssid)
                    applySecurityToSuggestion(primaryBuilder, passphrase, securityMode)
                    suggestions.add(primaryBuilder.build())
                }
                if (secondarySsid.isNotBlank() && !secondarySsid.equals(ssid, ignoreCase = true)) {
                    val secondaryBuilder = WifiNetworkSuggestion.Builder().setSsid(secondarySsid)
                    applySecurityToSuggestion(secondaryBuilder, secondaryPassphrase, securityMode)
                    suggestions.add(secondaryBuilder.build())
                }
                if (suggestions.isNotEmpty()) {
                    wifiManager.addNetworkSuggestions(suggestions)
                }
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            } else {
                val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(wifiIntent)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        val DEFAULT_ONT_SSID_REGEX = Regex("^GW040-NSv2_(2\\.4G|5G)_([0-9A-Fa-f]{6})$")
        private const val SCAN_WINDOW_MS = 120_000L
        private const val MAX_SCANS_PER_WINDOW = 4
        private val processScanTimestampsMs = ArrayDeque<Long>()

        fun tryConsumeScanQuotaInWindow(
            timestamps: ArrayDeque<Long>,
            nowMs: Long,
            windowMs: Long = SCAN_WINDOW_MS,
            maxScans: Int = MAX_SCANS_PER_WINDOW
        ): Boolean {
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() > windowMs) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= maxScans) return false
            timestamps.addLast(nowMs)
            return true
        }

        fun tryConsumeProcessScanQuota(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
            synchronized(processScanTimestampsMs) {
                tryConsumeScanQuotaInWindow(processScanTimestampsMs, nowMs)
            }

        fun validateHandoverSecurityCapability(
            secMode: String,
            sdkInt: Int = Build.VERSION.SDK_INT,
            isWpa3SaeSupported: Boolean = true
        ): String? {
            val mode = WifiSecurityMode.fromFormValue(secMode)
                ?: return "Chế độ bảo mật Wi-Fi '$secMode' không hợp lệ."
            if (mode == WifiSecurityMode.WPA3_PSK && (sdkInt < Build.VERSION_CODES.Q || !isWpa3SaeSupported)) {
                return "Điện thoại này không hỗ trợ tự động chuyển sang Wi-Fi WPA3-PSK thuần ở Bước 7 " +
                    "(yêu cầu Android 10+ và phần cứng WPA3-SAE). Vui lòng chọn 'WPA2-PSK / WPA3-PSK' hoặc 'WPA2-PSK'."
            }
            if (mode == WifiSecurityMode.WPA_PSK && sdkInt >= Build.VERSION_CODES.Q) {
                return "Android 10+ không hỗ trợ tự động ghép nối chuẩn cũ WPA-PSK (TKIP) thuần ở Bước 7. " +
                    "Vui lòng chọn 'WPA-PSK / WPA2-PSK' hoặc 'WPA2-PSK'."
            }
            return null
        }


        fun detectBandFromFrequency(
            frequencyMhz: Int,
            ssidHint: String? = null,
            configured24Ssid: String? = null,
            configured5Ssid: String? = null
        ): WifiBand {
            if (frequencyMhz >= 4900) return WifiBand.BAND_5_GHZ
            if (frequencyMhz in 2400..2500) return WifiBand.BAND_2_4_GHZ
            val rawHint = ssidHint?.trim().orEmpty()
            val c24 = configured24Ssid?.trim().orEmpty()
            val c5 = configured5Ssid?.trim().orEmpty()
            if (rawHint.isNotEmpty() && !c5.equals(c24, ignoreCase = true)) {
                if (c5.isNotEmpty() && rawHint.equals(c5, ignoreCase = true)) {
                    return WifiBand.BAND_5_GHZ
                }
                if (c24.isNotEmpty() && rawHint.equals(c24, ignoreCase = true)) {
                    return WifiBand.BAND_2_4_GHZ
                }
            }
            val hint = rawHint.uppercase()
            if (hint.contains("_5G") || hint.endsWith(" 5G") || hint.endsWith("-5G")) {
                return WifiBand.BAND_5_GHZ
            }
            return WifiBand.BAND_2_4_GHZ
        }

        /**
         * [Red Team RT-10] Validate factory-default ONT SSID pattern and verify MAC suffix against BSSID when present.
         */
        fun isDefaultOntSsid(ssid: String, bssid: String? = null): Boolean {
            val cleanSsid = ssid.trim().removeSurrounding("\"")
            val match = DEFAULT_ONT_SSID_REGEX.matchEntire(cleanSsid) ?: return false
            val ssidHexSuffix = match.groupValues[2].uppercase()

            if (!bssid.isNullOrBlank()) {
                val bssidHex = bssid.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.uppercase()
                if (bssidHex.length == 12) {
                    val bssidSuffix = bssidHex.takeLast(6)
                    if (ssidHexSuffix != bssidSuffix && ssidHexSuffix.take(5) != bssidSuffix.take(5)) {
                        return false
                    }
                }
            }
            return true
        }

        fun isSameOntBssid(bssid1: String, bssid2: String): Boolean {
            val h1 = bssid1.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.uppercase()
            val h2 = bssid2.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.uppercase()
            if (h1.length != 12 || h2.length != 12) return bssid1.equals(bssid2, ignoreCase = true)
            return h1 == h2 || h1.take(11) == h2.take(11)
        }

        fun filterAndRankOntNetworks(candidates: List<WifiScanCandidate>): List<WifiScanCandidate> {
            return candidates
                .filter { isDefaultOntSsid(it.ssid, it.bssid) }
                .sortedWith(
                    compareByDescending<WifiScanCandidate> { it.rssi }
                        .thenBy { if (it.band == WifiBand.BAND_2_4_GHZ) 0 else 1 }
                )
        }
    }
}
