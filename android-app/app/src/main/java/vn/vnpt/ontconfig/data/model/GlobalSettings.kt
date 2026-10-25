package vn.vnpt.ontconfig.data.model

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
    val requestTimeoutMs: Long = 5000L
)
