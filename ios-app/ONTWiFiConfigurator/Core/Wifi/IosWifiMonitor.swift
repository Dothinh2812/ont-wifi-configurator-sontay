import Darwin
import Foundation
import Network
#if canImport(UIKit)
import UIKit
#endif
#if canImport(NetworkExtension)
import NetworkExtension
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

public enum HotspotJoinResult: Equatable, Sendable {
    case joined(WifiInterfaceSnapshot)
    case entitlementUnavailable(String)
    case transientFailure(String)
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

    public static func computeReconnectBackoffMs(
        attempt: Int,
        initialBackoffMs: Int = 5_000,
        maxBackoffMs: Int = 20_000
    ) -> Int {
        let safeInitial = max(0, initialBackoffMs)
        let safeMax = max(safeInitial, maxBackoffMs)
        let step = max(1, attempt)
        return min(max(safeInitial * step, safeInitial), safeMax)
    }

    /// Detailed programmatic Wi-Fi association via `NEHotspotConfigurationManager` (`[Red Team RT-01]`):
    /// - Treats `error == nil` or `alreadyAssociated` (`code == 13`) as L2-associated and immediately
    ///   records `onOpenProfileApplied` BEFORE DHCP wait so temporary Open profiles never leak.
    /// - Waits up to `dhcpWaitMs` (`15_000ms`) for `en0` `192.168.1.x` + holds `1_200ms` link stability.
    /// - Classifies `NEHotspotConfigurationError.internal` (`code == 8`) returning in `< 40ms` with
    ///   unchanged `en0` IP (or `userDenied` `code == 7`) as `.entitlementUnavailable` (unsigned Sideloadly `.ipa`),
    ///   while classifying `>= 40ms` association timeouts / DHCP delays as `.transientFailure` for spaced retry.
    public func tryJoinWifiDetailed(
        ssid: String,
        passphrase: String,
        securityMode: String,
        dhcpWaitMs: Int = 15_000,
        stabilityHoldMs: Int = 1_200,
        onOpenProfileApplied: (@Sendable () -> Void)? = nil
    ) async -> HotspotJoinResult {
        let cleanSsid = ssid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanSsid.isEmpty else {
            return .transientFailure("Tên Wi-Fi đích rỗng.")
        }
        #if canImport(NetworkExtension)
        let isOpen = securityMode.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == WifiSecurityMode.open.rawValue
        let config: NEHotspotConfiguration
        if isOpen {
            config = NEHotspotConfiguration(ssid: cleanSsid)
            config.joinOnce = true
        } else {
            guard passphrase.count >= 8 else {
                return .transientFailure("Mật khẩu Wi-Fi WPA ngắn hơn 8 ký tự.")
            }
            config = NEHotspotConfiguration(ssid: cleanSsid, passphrase: passphrase, isWEP: false)
            config.joinOnce = false
        }

        let preApplyIp = inspectInterfaces().en0IpAddress
        let startUptime = ProcessInfo.processInfo.systemUptime
        let applyError: Error? = await withCheckedContinuation { continuation in
            NEHotspotConfigurationManager.shared.apply(config) { error in
                continuation.resume(returning: error)
            }
        }
        let elapsedMs = (ProcessInfo.processInfo.systemUptime - startUptime) * 1000.0

        let isAlreadyAssociated: Bool = {
            guard let err = applyError as NSError? else { return false }
            return err.domain == NEHotspotConfigurationErrorDomain &&
                err.code == NEHotspotConfigurationError.alreadyAssociated.rawValue
        }()

        if applyError == nil || isAlreadyAssociated {
            if isOpen {
                onOpenProfileApplied?()
            }
            let dhcpSnap = await awaitWifiIpv4Ready(maxWaitMs: max(800, dhcpWaitMs))
            guard dhcpSnap.isOnOntSubnet else {
                return .transientFailure(
                    "Đã ghép nối sóng '\(cleanSsid)' nhưng ONT chưa cấp xong IP DHCP 192.168.1.x sau \(dhcpWaitMs / 1000)s."
                )
            }
            if stabilityHoldMs > 0 {
                var heldMs = 0
                while heldMs < stabilityHoldMs {
                    let slice = min(300, stabilityHoldMs - heldMs)
                    try? await Task.sleep(nanoseconds: UInt64(slice) * 1_000_000)
                    heldMs += slice
                    if !inspectInterfaces().isOnOntSubnet {
                        return .transientFailure(
                            "Liên kết Wi-Fi '\(cleanSsid)' vừa nháy tắt trong cửa sổ kiểm tra ổn định (\(heldMs)/\(stabilityHoldMs)ms)."
                        )
                    }
                }
            }
            return .joined(inspectInterfaces())
        }

        let nsErr = applyError! as NSError
        if nsErr.domain == NEHotspotConfigurationErrorDomain {
            if nsErr.code == NEHotspotConfigurationError.userDenied.rawValue {
                return .entitlementUnavailable(
                    "Người dùng đã đóng hộp thoại ghép nối tự động — vui lòng chọn Wi-Fi '\(cleanSsid)' trong Cài đặt iOS."
                )
            }
            let postApplyIp = inspectInterfaces().en0IpAddress
            if nsErr.code == NEHotspotConfigurationError.internal.rawValue &&
                elapsedMs < 40.0 &&
                postApplyIp == preApplyIp {
                return .entitlementUnavailable(
                    "Bản cài Sideloadly miễn phí không hỗ trợ NEHotspotConfiguration tự động (<40ms) — chuyển sang kết nối trong Cài đặt iOS."
                )
            }
        }
        return .transientFailure(
            "Ghép nối tự động '\(cleanSsid)' chưa thành công (code=\(nsErr.code), \(Int(elapsedMs))ms): \(nsErr.localizedDescription)"
        )
        #else
        return .entitlementUnavailable("Thiết bị không hỗ trợ NetworkExtension.")
        #endif
    }

    /// Compatibility wrapper delegating to `tryJoinWifiDetailed`.
    public func tryJoinWifiIfEntitled(
        ssid: String,
        passphrase: String,
        securityMode: String
    ) async -> Bool {
        if case .joined = await tryJoinWifiDetailed(
            ssid: ssid,
            passphrase: passphrase,
            securityMode: securityMode,
            dhcpWaitMs: 15_000
        ) {
            return true
        }
        return false
    }

    /// Clean up temporary Open Wi-Fi configuration so the iPhone does not retain an open profile.
    public func removeTemporaryOpenWifiIfNeeded(ssid: String, securityMode: String = WifiSecurityMode.open.rawValue) {
        let cleanSsid = ssid.trimmingCharacters(in: .whitespacesAndNewlines)
        let isOpen = securityMode.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == WifiSecurityMode.open.rawValue
        guard !cleanSsid.isEmpty, isOpen else { return }
        #if canImport(NetworkExtension)
        NEHotspotConfigurationManager.shared.removeConfiguration(forSSID: cleanSsid)
        #endif
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
