import Darwin
import Foundation
import Network
#if canImport(UIKit)
import UIKit
#endif

public enum WifiBand: String, Codable, CaseIterable, Sendable {
    case band24GHz = "BAND_2_4_GHZ"
    case band5GHz = "BAND_5_GHZ"

    public var label: String {
        switch self {
        case .band24GHz: return "2.4GHz"
        case .band5GHz: return "5GHz"
        }
    }
}

public struct WifiInterfaceSnapshot: Equatable, Sendable {
    public let en0IpAddress: String
    public let subnetMask: String
    public let isOnOntSubnet: Bool
    public let hasActiveVpnInterface: Bool

    public init(
        en0IpAddress: String = "",
        subnetMask: String = "",
        isOnOntSubnet: Bool = false,
        hasActiveVpnInterface: Bool = false
    ) {
        self.en0IpAddress = en0IpAddress
        self.subnetMask = subnetMask
        self.isOnOntSubnet = isOnOntSubnet
        self.hasActiveVpnInterface = hasActiveVpnInterface
    }
}

/// Inspects iOS network interfaces directly via POSIX/Darwin `getifaddrs("en0")` and `NWPathMonitor`:
/// - Requires ZERO paid Apple Developer Entitlements (100% compatible with free Sideloadly / AltStore / TrollStore).
/// - Verifies that `"en0"` (iPhone Wi-Fi card) holds an IPv4 address in `192.168.1.x` (`!= 192.168.1.1`).
/// - Detects active VPN tunnels (`utun*`, `ipsec*`, `ppp*`) that could hijack `192.168.1.1` routing.
/// - Polls `awaitWifiIpv4Ready(maxWaitMs: 1200)` after Wi-Fi changes so DHCP finishes assigning IPv4 before probing.
public final class IosWifiMonitor: @unchecked Sendable {

    private var pathMonitor: NWPathMonitor?
    private let monitorQueue = DispatchQueue(label: "vn.vnpt.ontconfig.wifimonitor")

    public init() {}

    deinit {
        stopMonitoring()
    }

    /// Inspect `"en0"` IPv4 and active VPN tunnel interfaces (`utun*`, `ipsec*`, `ppp*`) via `getifaddrs()`.
    public func inspectInterfaces() -> WifiInterfaceSnapshot {
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPtr) == 0, let firstAddr = ifaddrPtr else {
            return WifiInterfaceSnapshot()
        }
        defer { freeifaddrs(ifaddrPtr) }

        var en0Ip = ""
        var en0Mask = ""
        var hasVpn = false

        var cursor: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let ifa = cursor?.pointee {
            defer { cursor = ifa.ifa_next }

            let name = String(cString: ifa.ifa_name)
            let flags = Int32(ifa.ifa_flags)
            let isUpAndRunning = (flags & IFF_UP) != 0 && (flags & IFF_RUNNING) != 0
            guard isUpAndRunning, let addr = ifa.ifa_addr else { continue }

            let family = Int32(addr.pointee.sa_family)
            if name == "en0" && family == AF_INET {
                en0Ip = Self.ipv4String(from: addr)
                if let netmaskAddr = ifa.ifa_netmask {
                    en0Mask = Self.ipv4String(from: netmaskAddr)
                }
            } else if (name.hasPrefix("utun") || name.hasPrefix("ipsec") || name.hasPrefix("ppp")) &&
                        family == AF_INET {
                let vpnIp = Self.ipv4String(from: addr)
                if !vpnIp.isEmpty && !vpnIp.hasPrefix("169.254.") {
                    hasVpn = true
                }
            }
        }

        let isOntSubnet = en0Ip.hasPrefix("192.168.1.") && en0Ip != "192.168.1.1"
        return WifiInterfaceSnapshot(
            en0IpAddress: en0Ip,
            subnetMask: en0Mask,
            isOnOntSubnet: isOntSubnet,
            hasActiveVpnInterface: hasVpn
        )
    }

    /// Wait up to `maxWaitMs` (default 1200ms, polling every 80ms) for iOS DHCP on `"en0"` to assign a `192.168.1.x` IPv4.
    public func awaitWifiIpv4Ready(maxWaitMs: Int = 1200) async -> WifiInterfaceSnapshot {
        let intervalMs = 80
        let maxAttempts = max(1, maxWaitMs / intervalMs)
        var latest = inspectInterfaces()
        if latest.isOnOntSubnet {
            return latest
        }

        for _ in 0..<maxAttempts {
            try? await Task.sleep(nanoseconds: UInt64(intervalMs) * 1_000_000)
            latest = inspectInterfaces()
            if latest.isOnOntSubnet {
                return latest
            }
        }
        return latest
    }

    public func startMonitoring(onWifiChanged: @escaping @Sendable (WifiInterfaceSnapshot) -> Void) {
        stopMonitoring()
        let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
        self.pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] _ in
            guard let self = self else { return }
            Task {
                let snapshot = await self.awaitWifiIpv4Ready(maxWaitMs: 1200)
                onWifiChanged(snapshot)
            }
        }
        monitor.start(queue: monitorQueue)
    }

    public func stopMonitoring() {
        pathMonitor?.cancel()
        pathMonitor = nil
    }

    public static func detectBandFromSsidHint(_ ssidHint: String) -> WifiBand {
        let upper = ssidHint.uppercased()
        if upper.contains("5G") || upper.contains("5GHZ") {
            return .band5GHz
        }
        return .band24GHz
    }

    @MainActor
    public func openSystemWifiSettings() {
        #if canImport(UIKit)
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
        #endif
    }

    private static func ipv4String(from sockaddrPtr: UnsafeMutablePointer<sockaddr>) -> String {
        guard Int32(sockaddrPtr.pointee.sa_family) == AF_INET else { return "" }
        var addrIn = sockaddrPtr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &addrIn.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
            return ""
        }
        return String(cString: buffer)
    }
}
