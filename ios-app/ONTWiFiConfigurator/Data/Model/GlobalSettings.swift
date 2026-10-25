import Foundation

public struct GlobalSettings: Codable, Equatable, Sendable {
    public var baseUrl: String
    public var adminDefaultPass: String
    public var adminNewPass: String
    public var vlanId: Int
    public var mtu: Int
    public var defaultWifi5Ssid: String
    public var defaultWifi5Pass: String
    public var lanIp: String
    public var lanMask: String
    public var dhcpStart: String
    public var dhcpEnd: String
    public var dhcpLease: Int
    public var brName: String
    public var requestTimeoutMs: Int

    public init(
        baseUrl: String = "https://192.168.1.1",
        adminDefaultPass: String = "admin",
        adminNewPass: String = "Passwd2@",
        vlanId: Int = 11,
        mtu: Int = 1492,
        defaultWifi5Ssid: String = "VNPT 5G",
        defaultWifi5Pass: String = "1234567890",
        lanIp: String = "192.168.1.1",
        lanMask: String = "255.255.255.0",
        dhcpStart: String = "192.168.1.2",
        dhcpEnd: String = "192.168.1.254",
        dhcpLease: Int = 1440,
        brName: String = "br-lan",
        requestTimeoutMs: Int = 5000
    ) {
        self.baseUrl = baseUrl
        self.adminDefaultPass = adminDefaultPass
        self.adminNewPass = adminNewPass
        self.vlanId = vlanId
        self.mtu = mtu
        self.defaultWifi5Ssid = defaultWifi5Ssid
        self.defaultWifi5Pass = defaultWifi5Pass
        self.lanIp = lanIp
        self.lanMask = lanMask
        self.dhcpStart = dhcpStart
        self.dhcpEnd = dhcpEnd
        self.dhcpLease = dhcpLease
        self.brName = brName
        self.requestTimeoutMs = requestTimeoutMs
    }
}
