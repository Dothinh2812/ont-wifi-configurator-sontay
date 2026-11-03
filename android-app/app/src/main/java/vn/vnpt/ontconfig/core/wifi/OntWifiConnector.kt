package vn.vnpt.ontconfig.core.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
            try {
                @Suppress("DEPRECATION")
                wifiManager.startScan()
            } catch (_: Exception) {
            }
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
        frequencyMhz: Int = -1
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

        // Fast path: if phone is already connected to this ONT in Android System Wi-Fi settings
        // (matched by SSID, BSSID, or active 192.168.1.x subnet when Android hides SSID as <unknown ssid>),
        // bind directly in <10ms without triggering a slow WifiNetworkSpecifier renegotiation!
        val (currentSysSsid, currentSysBssid) = getSystemConnectedSsidAndBssid()
        val alreadyOnTargetWifi =
            (currentSysSsid.isNotBlank() && currentSysSsid.equals(ssid, ignoreCase = true)) ||
                (!bssid.isNullOrBlank() && currentSysBssid.isNotBlank() && isSameOntBssid(currentSysBssid, bssid)) ||
                (currentSysSsid.isBlank() && hasActiveOntSubnetWifi())

        if (alreadyOnTargetWifi) {
            val existingNet = bindCurrentWifiNetworkIfConnected()
            if (existingNet != null) {
                awaitWifiIpv4Ready(existingNet, maxWaitMs = 600L)
                return@withTimeout existingNet
            }
        }

        unbindOntWifi(clearSelectedMetadata = false)

        val connectedNet = suspendCancellableCoroutine<Network> { cont ->
            val requestBuilder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ssid.isNotBlank()) {
                val specBuilder = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                // Note: Do NOT call specBuilder.setBssid(...) because locking BSSID causes
                // Android 10-14 to fail connection on many OEM ROMs or drop permanently on radio restart.
                applySecurityToSpecifier(specBuilder, effectivePassphrase, effectiveSecurityMode)
                requestBuilder.setNetworkSpecifier(specBuilder.build())
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    specifierWifiNetwork = network
                    boundWifiNetwork = network
                    bindProcessNetworkSafe(network)
                    if (cont.isActive) {
                        cont.resume(network)
                    }
                }

                override fun onUnavailable() {
                    // Fallback: if system Wi-Fi is already connected to a Wi-Fi network, bind it!
                    val fallbackNet = bindCurrentWifiNetworkIfConnected()
                    if (fallbackNet != null && cont.isActive) {
                        cont.resume(fallbackNet)
                        return
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
                    }
                    bindProcessNetworkSafe(null)
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
     * [Red Team RT-02 & RT-06] Scan-Throttle-Safe Beacon Verification after Step 7 radio reload:
     * Waits 3.5s for Broadcom radio restart, invokes at most 1 startScan() call (respecting Android 9+
     * limit of 4 scans / 2 minutes), and checks for a fresh ScanResult matching the new 2.4G or 5G SSID.
     */
    @SuppressLint("MissingPermission")
    suspend fun verifyNewSsidBroadcast(
        expectedSsid24g: String,
        expectedSsid5g: String,
        step7StartElapsedMicros: Long = SystemClock.elapsedRealtimeNanos() / 1000L,
        initialSettleDelayMs: Long = 1200L,
        timeoutMs: Long = 3500L
    ): Boolean {
        if (initialSettleDelayMs > 0) {
            delay(initialSettleDelayMs)
        }
        var scanTriggered = false
        val startMs = SystemClock.elapsedRealtime()

        while (SystemClock.elapsedRealtime() - startMs < timeoutMs) {
            if (!scanTriggered) {
                scanTriggered = true
                try {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                } catch (_: Exception) {
                }
            }

            val results = try {
                wifiManager.scanResults ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }

            val foundNewSsid = results.any { sr ->
                @Suppress("DEPRECATION")
                val ssid = (sr.SSID ?: "").removeSurrounding("\"")
                val isFresh = sr.timestamp <= 0L || sr.timestamp >= step7StartElapsedMicros
                isFresh && (ssid == expectedSsid24g || ssid == expectedSsid5g)
            }
            if (foundNewSsid) return true

            delay(1000L)
        }
        return false
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
