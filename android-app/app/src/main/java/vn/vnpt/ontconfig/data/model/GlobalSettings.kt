package vn.vnpt.ontconfig.data.model

enum class WifiSecurityMode(
    val formValue: String,
    val displayLabel: String,
    val shortLabel: String,
    val akmPsk: String,
    val akmPsk2: String,
    val crypto: String,
    val requiresPassword: Boolean
) {
    WPA2_WPA3_PSK(
        formValue = "wpa2_wpa3_psk",
        displayLabel = "WPA2-PSK / WPA3-PSK (Mặc định)",
        shortLabel = "WPA2/WPA3-PSK",
        akmPsk = "enabled",
        akmPsk2 = "enabled",
        crypto = "aes",
        requiresPassword = true
    ),
    WPA2_PSK(
        formValue = "wpa2_psk",
        displayLabel = "WPA2-PSK (Tương thích rộng / Mesh)",
        shortLabel = "WPA2-PSK",
        akmPsk = "enabled",
        akmPsk2 = "enabled",
        crypto = "aes",
        requiresPassword = true
    ),
    WPA_WPA2_PSK(
        formValue = "wpa_wpa2_psk",
        displayLabel = "WPA-PSK / WPA2-PSK (Thiết bị cũ / Mesh)",
        shortLabel = "WPA/WPA2-PSK",
        akmPsk = "enabled",
        akmPsk2 = "enabled",
        crypto = "tkip+aes",
        requiresPassword = true
    ),
    WPA3_PSK(
        formValue = "wpa3_psk",
        displayLabel = "WPA3-PSK",
        shortLabel = "WPA3-PSK",
        akmPsk = "enabled",
        akmPsk2 = "enabled",
        crypto = "aes",
        requiresPassword = true
    ),
    WPA_PSK(
        formValue = "wpa_psk",
        displayLabel = "WPA-PSK",
        shortLabel = "WPA-PSK",
        akmPsk = "enabled",
        akmPsk2 = "enabled",
        crypto = "tkip+aes",
        requiresPassword = true
    ),
    OPEN(
        formValue = "open",
        displayLabel = "Open (Không mật khẩu)",
        shortLabel = "Open",
        akmPsk = "disabled",
        akmPsk2 = "disabled",
        crypto = "aes",
        requiresPassword = false
    );

    companion object {
        fun fromFormValue(value: String): WifiSecurityMode? {
            val clean = value.trim()
            return entries.firstOrNull { it.formValue == clean }
        }
    }
}

data class GlobalSettings(
    val baseUrl: String = "https://192.168.1.1",
    val adminDefaultPass: String = "admin",
    val adminNewPass: String = "Passwd2@",
    val vlanId: Int = 11,
    val mtu: Int = 1492,
    val defaultWifi5Ssid: String = "VNPT 5G",
    val defaultWifi5Pass: String = "1234567890",
    val lanIp: String = "192.168.1.1",
    val lanMask: String = "255.255.255.0",
    val dhcpStart: String = "192.168.1.2",
    val dhcpEnd: String = "192.168.1.254",
    val dhcpLease: Int = 1440,
    val brName: String = "br-lan",
    val requestTimeoutMs: Long = 5000L,
    val enableFirmwareUpdate: Boolean = false,
    val firmwareFilePath: String = "",
    val firmwareFileName: String = "",
    val firmwareFileSize: Long = 0L,
    val firmwareUploadTimeoutMs: Long = 180_000L,
    val wifiSecurityMode: String = WifiSecurityMode.WPA2_WPA3_PSK.formValue,
    val wifiHandoverTimeoutMs: Long = 180_000L,
    val gatewayPostHandoverTimeoutMs: Long = 60_000L,
    val postSecondWifiSettleTimeoutMs: Long = 90_000L
)
