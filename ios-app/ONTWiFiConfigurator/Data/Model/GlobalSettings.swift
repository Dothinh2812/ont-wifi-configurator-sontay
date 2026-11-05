import Foundation

public enum WifiSecurityMode: String, Codable, CaseIterable, Sendable {
    case wpa2Wpa3Psk = "wpa2_wpa3_psk"
    case wpa2Psk = "wpa2_psk"
    case wpaWpa2Psk = "wpa_wpa2_psk"
    case wpa3Psk = "wpa3_psk"
    case wpaPsk = "wpa_psk"
    case open = "open"

    public var displayLabel: String {
        switch self {
        case .wpa2Wpa3Psk:
            return "WPA2-PSK / WPA3-PSK (Mặc định)"
        case .wpa2Psk:
            return "WPA2-PSK (Tương thích rộng / Mesh)"
        case .wpaWpa2Psk:
            return "WPA-PSK / WPA2-PSK (Thiết bị cũ / Mesh)"
        case .wpa3Psk:
            return "WPA3-PSK"
        case .wpaPsk:
            return "WPA-PSK"
        case .open:
            return "Open (Không mật khẩu)"
        }
    }

    public var shortLabel: String {
        switch self {
        case .wpa2Wpa3Psk:
            return "WPA2/WPA3-PSK"
        case .wpa2Psk:
            return "WPA2-PSK"
        case .wpaWpa2Psk:
            return "WPA/WPA2-PSK"
        case .wpa3Psk:
            return "WPA3-PSK"
        case .wpaPsk:
            return "WPA-PSK"
        case .open:
            return "Open"
        }
    }

    public var akmPsk: String {
        return self == .open ? "disabled" : "enabled"
    }

    public var akmPsk2: String {
        return self == .open ? "disabled" : "enabled"
    }

    public var crypto: String {
        switch self {
        case .wpaWpa2Psk, .wpaPsk:
            return "tkip+aes"
        case .wpa2Wpa3Psk, .wpa2Psk, .wpa3Psk, .open:
            return "aes"
        }
    }

    public var requiresPassword: Bool {
        return self != .open
    }
}

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
    public var wifiSecurityMode: String
    public var enableFirmwareUpdate: Bool
    public var firmwareFilePath: String
    public var firmwareFileName: String
    public var firmwareFileSize: Int64
    public var wifiHandoverTimeoutMs: Int
    public var gatewayPostHandoverTimeoutMs: Int
    public var postSecondWifiSettleTimeoutMs: Int
    public var firmwareUploadTimeoutMs: Int

    enum CodingKeys: String, CodingKey {
        case baseUrl
        case adminDefaultPass
        case adminNewPass
        case vlanId
        case mtu
        case defaultWifi5Ssid
        case defaultWifi5Pass
        case lanIp
        case lanMask
        case dhcpStart
        case dhcpEnd
        case dhcpLease
        case brName
        case requestTimeoutMs
        case wifiSecurityMode
        case enableFirmwareUpdate
        case firmwareFilePath
        case firmwareFileName
        case firmwareFileSize
        case wifiHandoverTimeoutMs
        case gatewayPostHandoverTimeoutMs
        case postSecondWifiSettleTimeoutMs
        case firmwareUploadTimeoutMs
    }

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
        requestTimeoutMs: Int = 5000,
        wifiSecurityMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue,
        enableFirmwareUpdate: Bool = false,
        firmwareFilePath: String = "",
        firmwareFileName: String = "",
        firmwareFileSize: Int64 = 0,
        wifiHandoverTimeoutMs: Int = 180_000,
        gatewayPostHandoverTimeoutMs: Int = 45_000,
        postSecondWifiSettleTimeoutMs: Int = 60_000,
        firmwareUploadTimeoutMs: Int = 180_000
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
        self.wifiSecurityMode = wifiSecurityMode
        self.enableFirmwareUpdate = enableFirmwareUpdate
        self.firmwareFilePath = firmwareFilePath
        self.firmwareFileName = firmwareFileName
        self.firmwareFileSize = firmwareFileSize
        self.wifiHandoverTimeoutMs = wifiHandoverTimeoutMs
        self.gatewayPostHandoverTimeoutMs = gatewayPostHandoverTimeoutMs
        self.postSecondWifiSettleTimeoutMs = postSecondWifiSettleTimeoutMs
        self.firmwareUploadTimeoutMs = firmwareUploadTimeoutMs
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.baseUrl = try container.decodeIfPresent(String.self, forKey: .baseUrl) ?? "https://192.168.1.1"
        self.adminDefaultPass = try container.decodeIfPresent(String.self, forKey: .adminDefaultPass) ?? "admin"
        self.adminNewPass = try container.decodeIfPresent(String.self, forKey: .adminNewPass) ?? "Passwd2@"
        self.vlanId = try container.decodeIfPresent(Int.self, forKey: .vlanId) ?? 11
        self.mtu = try container.decodeIfPresent(Int.self, forKey: .mtu) ?? 1492
        self.defaultWifi5Ssid = try container.decodeIfPresent(String.self, forKey: .defaultWifi5Ssid) ?? "VNPT 5G"
        self.defaultWifi5Pass = try container.decodeIfPresent(String.self, forKey: .defaultWifi5Pass) ?? "1234567890"
        self.lanIp = try container.decodeIfPresent(String.self, forKey: .lanIp) ?? "192.168.1.1"
        self.lanMask = try container.decodeIfPresent(String.self, forKey: .lanMask) ?? "255.255.255.0"
        self.dhcpStart = try container.decodeIfPresent(String.self, forKey: .dhcpStart) ?? "192.168.1.2"
        self.dhcpEnd = try container.decodeIfPresent(String.self, forKey: .dhcpEnd) ?? "192.168.1.254"
        self.dhcpLease = try container.decodeIfPresent(Int.self, forKey: .dhcpLease) ?? 1440
        self.brName = try container.decodeIfPresent(String.self, forKey: .brName) ?? "br-lan"
        self.requestTimeoutMs = try container.decodeIfPresent(Int.self, forKey: .requestTimeoutMs) ?? 5000
        self.wifiSecurityMode = try container.decodeIfPresent(String.self, forKey: .wifiSecurityMode) ?? WifiSecurityMode.wpa2Wpa3Psk.rawValue
        self.enableFirmwareUpdate = try container.decodeIfPresent(Bool.self, forKey: .enableFirmwareUpdate) ?? false
        self.firmwareFilePath = try container.decodeIfPresent(String.self, forKey: .firmwareFilePath) ?? ""
        self.firmwareFileName = try container.decodeIfPresent(String.self, forKey: .firmwareFileName) ?? ""
        self.firmwareFileSize = try container.decodeIfPresent(Int64.self, forKey: .firmwareFileSize) ?? 0
        self.wifiHandoverTimeoutMs = try container.decodeIfPresent(Int.self, forKey: .wifiHandoverTimeoutMs) ?? 180_000
        self.gatewayPostHandoverTimeoutMs = try container.decodeIfPresent(Int.self, forKey: .gatewayPostHandoverTimeoutMs) ?? 45_000
        self.postSecondWifiSettleTimeoutMs = try container.decodeIfPresent(Int.self, forKey: .postSecondWifiSettleTimeoutMs) ?? 60_000
        self.firmwareUploadTimeoutMs = try container.decodeIfPresent(Int.self, forKey: .firmwareUploadTimeoutMs) ?? 180_000
    }
}
