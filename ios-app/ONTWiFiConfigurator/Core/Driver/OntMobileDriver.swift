import Foundation

public enum StepStatus: String, Codable, Sendable {
    case pending = "PENDING"
    case running = "RUNNING"
    case success = "SUCCESS"
    case failed = "FAILED"
}

public struct StepProgress: Identifiable, Equatable, Sendable {
    public var id: Int { stepNumber }
    public let stepNumber: Int
    public let totalSteps: Int
    public let title: String
    public var status: StepStatus
    public var detail: String

    public init(
        stepNumber: Int,
        totalSteps: Int = 8,
        title: String,
        status: StepStatus,
        detail: String = ""
    ) {
        self.stepNumber = stepNumber
        self.totalSteps = totalSteps
        self.title = title
        self.status = status
        self.detail = detail
    }
}

public struct MobileSetupResult: Equatable, Sendable {
    public let success: Bool
    public let soMay: String
    public let slid: String
    public let accountPppoe: String
    public let newSsid24g: String
    public let newPass24g: String
    public let newSsid5g: String
    public let newPass5g: String
    public let updatedAdminPass: String
    public let message: String
    public let beaconVerified: Bool
    public let firmwareUpdated: Bool
    public let firmwareFileName: String
    public let configuredSecondaryBandLabel: String
    public let configuredSecondarySsid: String
    public let configuredSecondaryPass: String
    public let wifiSecurityMode: String
    public let resolvedConnectedBand: WifiBand

    public init(
        success: Bool,
        soMay: String,
        slid: String,
        accountPppoe: String,
        newSsid24g: String,
        newPass24g: String,
        newSsid5g: String,
        newPass5g: String,
        updatedAdminPass: String,
        message: String,
        beaconVerified: Bool = false,
        firmwareUpdated: Bool = false,
        firmwareFileName: String = "",
        configuredSecondaryBandLabel: String = "",
        configuredSecondarySsid: String = "",
        configuredSecondaryPass: String = "",
        wifiSecurityMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue,
        resolvedConnectedBand: WifiBand = .band24GHz
    ) {
        self.success = success
        self.soMay = soMay
        self.slid = slid
        self.accountPppoe = accountPppoe
        self.newSsid24g = newSsid24g
        self.newPass24g = newPass24g
        self.newSsid5g = newSsid5g
        self.newPass5g = newPass5g
        self.updatedAdminPass = updatedAdminPass
        self.message = message
        self.beaconVerified = beaconVerified
        self.firmwareUpdated = firmwareUpdated
        self.firmwareFileName = firmwareFileName
        self.configuredSecondaryBandLabel = configuredSecondaryBandLabel
        self.configuredSecondarySsid = configuredSecondarySsid
        self.configuredSecondaryPass = configuredSecondaryPass
        self.wifiSecurityMode = wifiSecurityMode
        self.resolvedConnectedBand = resolvedConnectedBand
    }
}

public struct WifiApplyOutcome: Equatable, Sendable {
    public let cfgSavedToNvram: Bool
    public let droppedDuringRadioReload: Bool
}

public final class OntMobileDriver: @unchecked Sendable {

    public static let userAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    public static let ontHtmlSignatures = ["iGate", "GW040", "VNPT", "login.js"]

    private static let cacheLock = NSLock()
    /// Per-record password cache keyed by `"\(rowIndex)|\(soMay)"` (NEVER global IP `192.168.1.1`).
    private static var recordPasswordCache: [String: String] = [:]

    public static func clearCachedPasswordsForTest() {
        cacheLock.lock()
        recordPasswordCache.removeAll()
        cacheLock.unlock()
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

    private let cleanBaseUrl: String
    private let timeoutMs: Int
    private let settlingDelayMs: Int
    private let recoveryRetryDelayMs: Int
    private let wlRestartPollIntervalMs: Int
    private let maxWlRestartPolls: Int
    private let userExistPollIntervalMs: Int
    private let postDhcpGatewaySettleMs: Int
    private let handoverRetryInitialBackoffMs: Int
    private let handoverRetryMaxBackoffMs: Int
    private let bandVerifyPollTimeoutMs: Int
    private let bandVerifyPollIntervalMs: Int
    private let probePauseMutex: AsyncMutex?
    private let onAdminPasswordChanged: (@Sendable (String) async -> Void)?
    private let isAppInBackground: @Sendable () -> Bool
    private let isNetworkTransportAlive: (@Sendable () -> Bool)?
    private let onLog: (@Sendable (LogLevel, String, String) -> Void)?
    private let onSwitchToSecondaryWifi: (@Sendable (
        _ ssid: String,
        _ pass: String,
        _ band: WifiBand,
        _ secMode: String,
        _ expectedOntBaseMac: String?,
        _ timeoutMs: Int,
        _ onDetail: @Sendable (String) -> Void
    ) async throws -> String?)?
    private let session: UnsafeSslSession
    private let logoutSession: UnsafeSslSession

    private let stateLock = NSLock()
    private var sessionKey: String = ""
    private var lastActiveSessionKeyStorage: String = ""
    private var currentUser: String = "admin"

    public init(
        baseUrl: String = "https://192.168.1.1",
        timeoutMs: Int = 5000,
        settlingDelayMs: Int = 200,
        recoveryRetryDelayMs: Int = 1200,
        wlRestartPollIntervalMs: Int = 1000,
        maxWlRestartPolls: Int = 30,
        userExistPollIntervalMs: Int = 3000,
        postDhcpGatewaySettleMs: Int = 2500,
        handoverRetryInitialBackoffMs: Int = 5000,
        handoverRetryMaxBackoffMs: Int = 20000,
        bandVerifyPollTimeoutMs: Int = 15000,
        bandVerifyPollIntervalMs: Int = 2000,
        probePauseMutex: AsyncMutex? = nil,
        protocolClasses: [AnyClass] = [],
        onAdminPasswordChanged: (@Sendable (String) async -> Void)? = nil,
        isAppInBackground: @escaping @Sendable () -> Bool = { false },
        isNetworkTransportAlive: (@Sendable () -> Bool)? = nil,
        onLog: (@Sendable (LogLevel, String, String) -> Void)? = nil,
        onSwitchToSecondaryWifi: (@Sendable (
            _ ssid: String,
            _ pass: String,
            _ band: WifiBand,
            _ secMode: String,
            _ expectedOntBaseMac: String?,
            _ timeoutMs: Int,
            _ onDetail: @Sendable (String) -> Void
        ) async throws -> String?)? = nil
    ) {
        let trimmed = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.cleanBaseUrl = trimmed
        self.timeoutMs = timeoutMs
        self.settlingDelayMs = settlingDelayMs
        self.recoveryRetryDelayMs = recoveryRetryDelayMs
        self.wlRestartPollIntervalMs = wlRestartPollIntervalMs
        self.maxWlRestartPolls = maxWlRestartPolls
        self.userExistPollIntervalMs = userExistPollIntervalMs
        self.postDhcpGatewaySettleMs = postDhcpGatewaySettleMs
        self.handoverRetryInitialBackoffMs = handoverRetryInitialBackoffMs
        self.handoverRetryMaxBackoffMs = handoverRetryMaxBackoffMs
        self.bandVerifyPollTimeoutMs = bandVerifyPollTimeoutMs
        self.bandVerifyPollIntervalMs = bandVerifyPollIntervalMs
        self.probePauseMutex = probePauseMutex
        self.onAdminPasswordChanged = onAdminPasswordChanged
        self.isAppInBackground = isAppInBackground
        self.isNetworkTransportAlive = isNetworkTransportAlive
        self.onLog = onLog
        self.onSwitchToSecondaryWifi = onSwitchToSecondaryWifi
        let hostOverride = URL(string: trimmed)?.host
        self.session = UnsafeSslSession(
            timeoutMs: timeoutMs,
            allowedHostOverride: hostOverride,
            protocolClasses: protocolClasses
        )
        self.logoutSession = UnsafeSslSession(
            timeoutMs: 1500,
            allowedHostOverride: hostOverride,
            protocolClasses: protocolClasses
        )
    }

    private func logDiag(_ level: LogLevel, _ tag: String, _ message: String) {
        onLog?(level, tag, message)
    }

    private var currentSessionKey: String {
        get {
            stateLock.lock()
            defer { stateLock.unlock() }
            return sessionKey
        }
        set {
            stateLock.lock()
            sessionKey = newValue
            stateLock.unlock()
        }
    }

    private var lastActiveSessionKey: String {
        stateLock.lock()
        defer { stateLock.unlock() }
        return lastActiveSessionKeyStorage
    }

    private func isValidSessionToken(_ key: String) -> Bool {
        !key.isEmpty && key != "0" && key != "factoryID"
    }

    private func updateSessionKey(_ newKey: String) {
        let clean = newKey.trimmingCharacters(in: .whitespacesAndNewlines)
        stateLock.lock()
        sessionKey = clean
        if isValidSessionToken(clean) {
            lastActiveSessionKeyStorage = clean
        }
        stateLock.unlock()
    }

    private func clearAllSessionKeys() {
        stateLock.lock()
        sessionKey = ""
        lastActiveSessionKeyStorage = ""
        stateLock.unlock()
    }

    public func setPinnedCertSha256ForTest(_ sha256Hex: String?) {
        let clean = sha256Hex?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        session.pinnedCertSha256 = clean
        logoutSession.pinnedCertSha256 = clean
    }

    private func isTlsPinMismatch(_ error: Error) -> Bool {
        let nsErr = error as NSError
        if nsErr.domain == UnsafeSslSession.tlsPinErrorDomain {
            return true
        }
        if let underlying = nsErr.userInfo[NSUnderlyingErrorKey] as? Error {
            return isTlsPinMismatch(underlying)
        }
        return false
    }

    /// Step 1: Probe root URL and verify both HTTP 200 AND ONT HTML signature ("iGate", "GW040", "VNPT", or "login.js").
    public func probeGateway() async -> Bool {
        return (try? await probeGatewayCheckingCertPin(failOnCertMismatch: false)) ?? false
    }

    private func probeGatewayCheckingCertPin(failOnCertMismatch: Bool) async throws -> Bool {
        guard let url = URL(string: "\(cleanBaseUrl)/") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("close", forHTTPHeaderField: "Connection")

        do {
            let resp = try await session.execute(req)
            guard resp.statusCode == 200 else { return false }
            return Self.ontHtmlSignatures.contains { sig in
                resp.body.range(of: sig, options: .caseInsensitive) != nil
            }
        } catch {
            if (failOnCertMismatch || session.pinnedCertSha256 != nil) && isTlsPinMismatch(error) {
                throw OntDriverException(
                    "Chứng chỉ bảo mật TLS của ONT ở Lần kết nối 2 không khớp với ONT ban đầu — nghi ngờ bắt nhầm Wi-Fi khác.",
                    underlyingError: error
                )
            }
            return false
        }
    }

    private func postForm(
        path: String,
        rawPayload: String,
        useShortLogoutTimeout: Bool = false,
        referer: String? = nil
    ) async throws -> HttpResponsePayload {
        guard let url = URL(string: "\(cleanBaseUrl)\(path)") else {
            throw OntNetworkError("URL không hợp lệ: \(cleanBaseUrl)\(path)")
        }
        let bodyData = Data(rawPayload.utf8)
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(referer ?? "\(cleanBaseUrl)/", forHTTPHeaderField: "Referer")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("XMLHttpRequest", forHTTPHeaderField: "X-Requested-With")
        req.setValue("close", forHTTPHeaderField: "Connection")

        let targetSession = useShortLogoutTimeout ? logoutSession : session
        return try await targetSession.execute(req, expectedBodyBytes: bodyData.count)
    }
    private func parseJsonOrThrow(
        stepContext: String,
        statusCode: Int,
        body: String
    ) throws -> [String: Any] {
        guard statusCode == 200 else {
            throw OntDriverException("\(stepContext) thất bại (HTTP \(statusCode))")
        }
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return ["status": "OK"]
        }
        if trimmed.hasPrefix("<") {
            throw OntDriverException("\(stepContext) trả về trang HTML thay vì JSON hợp lệ (phiên có thể đã hết hạn)")
        }
        guard let data = trimmed.data(using: .utf8),
              let jsonObj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let preview = String(trimmed.prefix(120))
            throw OntDriverException("\(stepContext) trả về JSON không hợp lệ: \(preview)")
        }
        return jsonObj
    }

    private func stringValue(_ dict: [String: Any], _ key: String, default defaultVal: String = "") -> String {
        if let s = dict[key] as? String { return s }
        if let n = dict[key] as? NSNumber { return n.stringValue }
        return defaultVal
    }

    /// Authenticate with `/cgi-bin/luci/login.cmd` and enforce lockout thresholds (`numberLogin < 2`).
    public func login(user: String, password: String) async throws -> [String: Any] {
        try OntInputValidator.sanitizeInputMetacharacters(user)
        try OntInputValidator.sanitizeInputMetacharacters(password)

        let encUser = OntInputValidator.urlEncodeFormValue(user)
        let encPass = OntInputValidator.urlEncodeFormValue(password)
        let payload = "user=\(encUser)&pass=\(encPass)"

        let resp: HttpResponsePayload
        do {
            resp = try await postForm(path: "/cgi-bin/luci/login.cmd", rawPayload: payload)
        } catch {
            throw OntNetworkError("Lỗi kết nối khi gửi yêu cầu đăng nhập: \(error.localizedDescription)", cause: error)
        }

        let data = try parseJsonOrThrow(stepContext: "Đăng nhập login.cmd", statusCode: resp.statusCode, body: resp.body)
        let status = stringValue(data, "status")
        if status.caseInsensitiveCompare("user exist") == .orderedSame {
            throw OntUserExistError(
                "Tài khoản admin đang bận phiên khác (user exist). Vui lòng chờ 60s hoặc khởi động lại ONT để giải phóng phiên."
            )
        }

        let timeoutLogin = Int(stringValue(data, "timeoutLogin", default: "0")) ?? 0
        let numberLogin = Int(stringValue(data, "numberLogin", default: "0")) ?? 0

        if timeoutLogin > 0 || numberLogin >= 3 {
            throw OntAccountLockoutError(
                "ONT đang bị khóa phần cứng trong \(timeoutLogin)s do nhập sai quá \(numberLogin) lần. Vui lòng đợi hết thời gian hoặc bấm nút Reset cứng sau thân ONT."
            )
        }

        if numberLogin >= 2 {
            throw OntAccountLockoutWarning(
                "Cảnh báo: ONT đã ghi nhận \(numberLogin) lần đăng nhập sai. Dừng lại để tránh bị khóa 180s. Vui lòng kiểm tra mật khẩu hoặc bấm nút Reset cứng."
            )
        }

        let firstLog = stringValue(data, "firstlog")
        if firstLog == "1" {
            updateSessionKey("factoryID")
        } else if firstLog == "0" {
            let issued = stringValue(data, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
            if !issued.isEmpty && issued != "0" {
                updateSessionKey(issued)
            } else {
                currentSessionKey = (issued == "0") ? "" : issued
            }
        }
        stateLock.lock()
        currentUser = user
        stateLock.unlock()
        return data
    }

    private static func recordCacheKey(rowIndex: Int, soMay: String) -> String {
        return "\(rowIndex)|\(soMay.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())"
    }

    private static func getCachedPass(for key: String) -> String? {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        return recordPasswordCache[key]
    }

    private static func setCachedPass(_ pass: String, for key: String) {
        cacheLock.lock()
        recordPasswordCache[key] = pass
        cacheLock.unlock()
    }

    /// Step 2: Smart Login & Password Change scoped per subscriber record `(rowIndex, soMay)`.
    public func loginAndChangePassword(
        defaultPass: String,
        newPass: String,
        recordKey: String,
        lastKnownAdminPass: String = ""
    ) async throws -> String {
        guard OntInputValidator.validatePasswordComplexity(newPass) else {
            throw OntValidationError(
                "Mật khẩu admin mới không đạt chuẩn độ phức tạp (8..32 ký tự ASCII không khoảng trắng, đủ hoa, thường, số và ký tự đặc biệt)."
            )
        }

        let cachedPass = Self.getCachedPass(for: recordKey)
        let preferNewPassFirst = (cachedPass == newPass) || (lastKnownAdminPass == newPass)

        var candidateOrder: [String] = preferNewPassFirst ? [newPass, defaultPass] : [defaultPass, newPass]
        if candidateOrder.count == 2 && candidateOrder[0] == candidateOrder[1] {
            candidateOrder.removeLast()
        }

        for candidatePass in candidateOrder {
            var loginData: [String: Any]?
            var loginOk = false
            do {
                let data = try await login(user: "admin", password: candidatePass)
                loginData = data
                let firstLog = stringValue(data, "firstlog")
                let status = stringValue(data, "status")
                loginOk = (status == "OK" || firstLog == "1" || firstLog == "0") &&
                    (firstLog == "1" || !currentSessionKey.isEmpty)
            } catch let e as OntAccountLockoutError {
                throw e
            } catch let e as OntAccountLockoutWarning {
                throw e
            } catch let e as OntUserExistError {
                throw e
            } catch {
                loginOk = false
            }

            guard loginOk, let validLoginData = loginData else {
                continue
            }

            let firstLog = stringValue(validLoginData, "firstlog")
            if firstLog == "1" {
                let encNewPass = OntInputValidator.urlEncodeFormValue(newPass)
                let changePayload =
                    "undefined&sessionKey=\(currentSessionKey)&username=admin&oldPass=&newPass=\(encNewPass)&user_cur_log=admin"
                let resp: HttpResponsePayload
                do {
                    resp = try await postForm(path: "/cgi-bin/luci/accesscontrolcfg.json", rawPayload: changePayload)
                } catch {
                    throw OntNetworkError("Lỗi mạng khi đổi mật khẩu admin: \(error.localizedDescription)", cause: error)
                }
                _ = try parseJsonOrThrow(stepContext: "Đổi mật khẩu Admin", statusCode: resp.statusCode, body: resp.body)

                Self.setCachedPass(newPass, for: recordKey)
                let relogData = try await login(user: "admin", password: newPass)
                let newKey = stringValue(relogData, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
                if !newKey.isEmpty && newKey != "0" {
                    updateSessionKey(newKey)
                }
                return currentSessionKey
            } else {
                Self.setCachedPass(candidatePass, for: recordKey)
                return currentSessionKey
            }
        }

        throw OntAuthenticationError(
            "Đăng nhập thất bại với cả mật khẩu mặc định và mật khẩu mới. Vui lòng bấm nút Reset cứng sau thân ONT để đưa về trạng thái xuất xưởng."
        )
    }

    /// Step 3: Idempotent WAN PPPoE VLAN 11 (`serviceId=1` on Edit, `serviceId=0` on Add).
    @discardableResult
    public func configureWanIdempotent(
        vlanId: Int,
        pppUser: String,
        pppPass: String,
        mtu: Int = 1492
    ) async throws -> [String: Any] {
        try OntInputValidator.sanitizeInputMetacharacters(pppUser)
        try OntInputValidator.sanitizeInputMetacharacters(pppPass)

        let wanResp: HttpResponsePayload
        do {
            wanResp = try await postForm(
                path: "/cgi-bin/luci/wancfg.json",
                rawPayload: "undefined&sessionKey=\(currentSessionKey)"
            )
        } catch {
            throw OntNetworkError("Lỗi mạng khi tra cứu danh sách WAN: \(error.localizedDescription)", cause: error)
        }

        let wanJson = try parseJsonOrThrow(
            stepContext: "Tra cứu danh sách WAN (wancfg.json)",
            statusCode: wanResp.statusCode,
            body: wanResp.body
        )

        var existingIfName: String?
        if let wanArray = wanJson["wanCfg"] as? [[String: Any]] {
            for item in wanArray {
                let vId = stringValue(item, "vlanId", default: stringValue(item, "vlanMuxId"))
                if vId == String(vlanId) {
                    let ifc = stringValue(item, "ifcStr").trimmingCharacters(in: .whitespacesAndNewlines)
                    existingIfName = ifc.isEmpty ? "veip0.\(vlanId)" : ifc
                    break
                }
            }
        }

        let encUser = OntInputValidator.urlEncodeFormValue(pppUser)
        let encPass = OntInputValidator.urlEncodeFormValue(pppPass)

        if let ifName = existingIfName {
            let encIfName = OntInputValidator.urlEncodeFormValue(ifName)
            let editPayload = "serviceId=1&wanIfName=\(encIfName)&ntwkPrtcl=0&sessionKey=\(currentSessionKey)"
            let editResp: HttpResponsePayload
            do {
                editResp = try await postForm(path: "/cgi-bin/luci/editwan.json", rawPayload: editPayload)
            } catch {
                throw OntNetworkError("Lỗi mạng khi gọi editwan.json: \(error.localizedDescription)", cause: error)
            }
            _ = try parseJsonOrThrow(
                stepContext: "Chuẩn bị sửa WAN (editwan.json)",
                statusCode: editResp.statusCode,
                body: editResp.body
            )

            let sendPayload =
                "enblEnetWan=0&serviceName=internet&vlanMuxId=\(vlanId)&vlanMuxPr=0&ntwkPrtcl=0" +
                "&pppUserName=\(encUser)&pppPassword=\(encPass)&enblIpVer=0&wanAddr6Type=DHCP" +
                "&ethMtu=\(mtu)&enblFirewall=1&enblNat=1&serviceId=1" +
                "&wanIfName=\(encIfName)&sessionKey=\(currentSessionKey)"
            let sendResp: HttpResponsePayload
            do {
                sendResp = try await postForm(path: "/cgi-bin/luci/sendwan.cmd", rawPayload: sendPayload)
            } catch {
                throw OntNetworkError("Lỗi mạng khi lưu cấu hình WAN (sendwan.cmd): \(error.localizedDescription)", cause: error)
            }
            return try parseJsonOrThrow(
                stepContext: "Lưu cấu hình WAN (sendwan.cmd)",
                statusCode: sendResp.statusCode,
                body: sendResp.body
            )
        } else {
            let addPayload = "undefined&sessionKey=\(currentSessionKey)&serviceId=0"
            let addResp: HttpResponsePayload
            do {
                addResp = try await postForm(path: "/cgi-bin/luci/addwan.json", rawPayload: addPayload)
            } catch {
                throw OntNetworkError("Lỗi mạng khi gọi addwan.json: \(error.localizedDescription)", cause: error)
            }
            _ = try parseJsonOrThrow(
                stepContext: "Tạo mới giao diện WAN (addwan.json)",
                statusCode: addResp.statusCode,
                body: addResp.body
            )

            let sendPayload =
                "enblEnetWan=0&serviceName=internet&vlanMuxId=\(vlanId)&vlanMuxPr=0&ntwkPrtcl=0" +
                "&pppUserName=\(encUser)&pppPassword=\(encPass)&enblIpVer=0&wanAddr6Type=DHCP" +
                "&ethMtu=\(mtu)&enblFirewall=1&enblNat=1&serviceId=0&wanIfName=undefined&sessionKey=\(currentSessionKey)"
            let sendResp: HttpResponsePayload
            do {
                sendResp = try await postForm(path: "/cgi-bin/luci/sendwan.cmd", rawPayload: sendPayload)
            } catch {
                throw OntNetworkError("Lỗi mạng khi lưu cấu hình WAN mới (sendwan.cmd): \(error.localizedDescription)", cause: error)
            }
            return try parseJsonOrThrow(
                stepContext: "Lưu cấu hình WAN mới (sendwan.cmd)",
                statusCode: sendResp.statusCode,
                body: sendResp.body
            )
        }
    }

    /// Step 4: Configure GPON SLID / Password (<= 10 ASCII chars).
    @discardableResult
    public func setGponPassword(_ slidValue: String) async throws -> [String: Any] {
        let validSlid: String
        do {
            validSlid = try OntInputValidator.validateSlid(slidValue)
        } catch {
            throw OntValidationError(error.localizedDescription)
        }
        let encSlid = OntInputValidator.urlEncodeFormValue(validSlid)
        let payload = "sessionKey=\(currentSessionKey)&data=\(encSlid)"
        let resp: HttpResponsePayload
        do {
            resp = try await postForm(path: "/cgi-bin/luci/gponpasswd.json", rawPayload: payload)
        } catch {
            throw OntNetworkError("Lỗi mạng khi cài đặt GPON SLID: \(error.localizedDescription)", cause: error)
        }
        return try parseJsonOrThrow(
            stepContext: "Cài đặt GPON Password (gponpasswd.json)",
            statusCode: resp.statusCode,
            body: resp.body
        )
    }

    /// Step 5: Configure LAN IPv4 (default 192.168.1.1).
    @discardableResult
    public func configureLan(_ settings: GlobalSettings) async throws -> [String: Any] {
        try OntInputValidator.sanitizeInputMetacharacters(settings.lanIp)
        try OntInputValidator.sanitizeInputMetacharacters(settings.lanMask)
        try OntInputValidator.sanitizeInputMetacharacters(settings.dhcpStart)
        try OntInputValidator.sanitizeInputMetacharacters(settings.dhcpEnd)
        try OntInputValidator.sanitizeInputMetacharacters(settings.brName)

        let payload =
            "&sessionKey=\(currentSessionKey)&ethIpAddress=\(settings.lanIp)&ethSubnetMask=\(settings.lanMask)" +
            "&enblLanFirewall=0&dhcpEthStart=\(settings.dhcpStart)&dhcpEthEnd=\(settings.dhcpEnd)" +
            "&dhcpLeasedTime=\(settings.dhcpLease)&enblDhcpSrv=1&dhcpClearLeaseTime=0" +
            "&enblLan2=0&brName=\(settings.brName)"
        let resp: HttpResponsePayload
        do {
            resp = try await postForm(path: "/cgi-bin/luci/savelanv4cfg.cmd", rawPayload: payload)
        } catch {
            throw OntNetworkError("Lỗi mạng khi lưu cấu hình LAN IPv4: \(error.localizedDescription)", cause: error)
        }
        return try parseJsonOrThrow(
            stepContext: "Lưu cấu hình LAN IPv4 (savelanv4cfg.cmd)",
            statusCode: resp.statusCode,
            body: resp.body
        )
    }

    /// Step 6 & Step 7: Configure Wi-Fi band (`unit = 0` for 2.4GHz, `unit = 1` for 5GHz).
    public func configureWifiBand(
        unit: Int,
        ssid: String,
        password: String,
        secMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue,
        isConnectedBandFinalStep: Bool = false
    ) async throws -> WifiApplyOutcome {
        let cleanSec = secMode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let mode = WifiSecurityMode(rawValue: cleanSec) else {
            throw OntValidationError("Chế độ bảo mật Wi-Fi '\(secMode)' không hợp lệ.")
        }
        do {
            try OntInputValidator.sanitizeInputMetacharacters(ssid)
            try OntInputValidator.sanitizeInputMetacharacters(password)
        } catch {
            throw OntValidationError(error.localizedDescription)
        }
        if !(1...32).contains(ssid.count) || ssid.trimmingCharacters(in: .whitespaces).isEmpty || !OntInputValidator.isPrintableAscii(ssid) {
            throw OntValidationError("SSID Wi-Fi unit=\(unit) phải từ 1..32 ký tự ASCII in được và không chứa toàn dấu cách.")
        }
        if !OntInputValidator.isPrintableAscii(password) {
            throw OntValidationError("Mật khẩu Wi-Fi unit=\(unit) chỉ chấp nhận ký tự ASCII in được (0x20..0x7E).")
        }
        if mode.requiresPassword && (!(8...63).contains(password.count) || password.trimmingCharacters(in: .whitespaces).isEmpty) {
            throw OntValidationError("Mật khẩu Wi-Fi unit=\(unit) phải dài từ 8..63 ký tự ASCII và không chứa toàn dấu cách.")
        }

        let encSsid = OntInputValidator.urlEncodeFormValue(ssid)
        let encPass = OntInputValidator.urlEncodeFormValue(password)
        let encSec = OntInputValidator.urlEncodeFormValue(mode.rawValue)
        let encKey = OntInputValidator.urlEncodeFormValue(currentSessionKey)

        let cfgPayload =
            "page=ssid.asp&action=Apply&wl_unit=\(unit)&wl_radio=1&wl_bssid=0&wl_bss_enabled=1" +
            "&wl_ssid=\(encSsid)&wl_bss_maxassoc=32&wl_closed=0&sec_mode=\(encSec)" +
            "&wl_wpa_psk=\(encPass)&wl_mode=null&wl_auth=0&wl_auth_mode=radius&wl_osenen=0" +
            "&wl_akm=&wl_akm_wpa=disabled&wl_akm_psk=\(mode.akmPsk)&wl_akm_wpa2=disabled&wl_akm_psk2=\(mode.akmPsk2)" +
            "&wl_akm_brcm_psk=disabled&wl_preauth=disabled&wl_wep=disabled&wl_crypto=\(mode.crypto)" +
            "&wl_radius_ipaddr=0.0.0.0&wl_radius_port=1812&wl_radius_key=1234&sessionKey=\(encKey)"

        var cfgSavedToNvram = false
        var droppedDuringRadioReload = false

        do {
            let cfgResp = try await postForm(path: "/cgi-bin/luci/wirelesscfg.json", rawPayload: cfgPayload)
            if cfgResp.statusCode == 200 && cfgResp.body.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
                currentSessionKey = ""
                throw OntNetworkError(
                    "Phiên làm việc ONT hết hạn (trang HTML) khi cài đặt Wi-Fi unit=\(unit), cần xác thực lại."
                )
            }
            let cfgJson = try parseJsonOrThrow(
                stepContext: "Cài đặt Wi-Fi unit=\(unit) (wirelesscfg.json)",
                statusCode: cfgResp.statusCode,
                body: cfgResp.body
            )
            let cfgStatus = stringValue(cfgJson, "status").trimmingCharacters(in: .whitespacesAndNewlines)
            if cfgStatus.caseInsensitiveCompare("fail authen") == .orderedSame {
                currentSessionKey = ""
                throw OntNetworkError(
                    "Phiên làm việc ONT hết hạn (status=fail authen) khi cài đặt Wi-Fi unit=\(unit), cần xác thực lại."
                )
            }
            if cfgStatus == "conflict_mesh" {
                throw OntDriverException(
                    "ONT đang chạy Mesh nên không cho phép đổi chế độ bảo mật Wi-Fi sang Open (chỉ hỗ trợ WPA2-PSK hoặc WPA-PSK/WPA2-PSK)."
                )
            }
            if !cfgStatus.isEmpty && cfgStatus != "OK" {
                throw OntDriverException("Cài đặt Wi-Fi unit=\(unit) bị từ chối (status=\(cfgStatus))")
            }
            cfgSavedToNvram = true
        } catch let err as OntDriverException {
            throw err
        } catch {
            if error is CancellationError {
                throw error
            }
            if isConnectedBandFinalStep && isPostFlushRadioDrop(error) {
                droppedDuringRadioReload = true
            } else if isPreSendConnectFailure(error) {
                throw OntNetworkError("Không thể kết nối tới ONT khi gửi cấu hình Wi-Fi unit=\(unit): \(error.localizedDescription)", cause: error)
            } else {
                throw OntNetworkError("Lỗi mạng khi gửi cấu hình Wi-Fi unit=\(unit): \(error.localizedDescription)", cause: error)
            }
        }

        if !droppedDuringRadioReload {
            do {
                let resResp = try await postForm(
                    path: "/cgi-bin/luci/wlrestart.json",
                    rawPayload: "undefined&sessionKey=\(currentSessionKey)",
                    useShortLogoutTimeout: isConnectedBandFinalStep
                )
                let resJson = try parseJsonOrThrow(
                    stepContext: "Khởi động lại driver Wi-Fi (wlrestart.json)",
                    statusCode: resResp.statusCode,
                    body: resResp.body
                )

                if !isConnectedBandFinalStep && maxWlRestartPolls > 0 && stringValue(resJson, "_wlrestart_", default: "0") != "0" {
                    for _ in 1...maxWlRestartPolls {
                        if wlRestartPollIntervalMs > 0 {
                            try await Task.sleep(nanoseconds: UInt64(wlRestartPollIntervalMs) * 1_000_000)
                        }
                        let pollResp = try await postForm(
                            path: "/cgi-bin/luci/wlrestart.json",
                            rawPayload: "undefined&sessionKey=\(currentSessionKey)"
                        )
                        let pollJson = try parseJsonOrThrow(
                            stepContext: "Kiểm tra wlrestart.json",
                            statusCode: pollResp.statusCode,
                            body: pollResp.body
                        )
                        if stringValue(pollJson, "_wlrestart_", default: "0") == "0" {
                            break
                        }
                    }
                }
            } catch {
                if error is CancellationError {
                    throw error
                }
                if isConnectedBandFinalStep || cfgSavedToNvram {
                    droppedDuringRadioReload = true
                } else {
                    throw OntNetworkError("Lỗi mạng khi gọi wlrestart.json cho unit=\(unit): \(error.localizedDescription)", cause: error)
                }
            }
        }

        return WifiApplyOutcome(
            cfgSavedToNvram: cfgSavedToNvram,
            droppedDuringRadioReload: droppedDuringRadioReload
        )
    }

    private func normalizeWlInterface(_ rawIfc: String) -> String {
        let lower = rawIfc.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if lower.hasPrefix("wl0") { return "wl0" }
        if lower.hasPrefix("wl1") { return "wl1" }
        return ""
    }

    private func pipeRowString(_ item: Any) -> String {
        (item as? [Any])?.first as? String ?? (item as? String) ?? ""
    }

    private func postOptionalSessionJson(path: String) async throws -> [String: Any]? {
        let encKey = OntInputValidator.urlEncodeFormValue(currentSessionKey)
        let resp: HttpResponsePayload
        do {
            resp = try await postForm(
                path: path,
                rawPayload: "undefined&sessionKey=\(encKey)",
                useShortLogoutTimeout: false
            )
        } catch let ce as CancellationError {
            throw ce
        } catch {
            if isTlsPinMismatch(error) {
                throw OntDriverException(
                    "Chứng chỉ bảo mật TLS của ONT ở Lần kết nối 2 không khớp với chứng chỉ đã ghim ở Lần 1 khi đọc \(path) — nghi ngờ bắt nhầm Wi-Fi khác.",
                    underlyingError: error
                )
            }
            return nil
        }
        let trimmed = resp.body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard resp.statusCode == 200,
              !trimmed.isEmpty,
              !trimmed.hasPrefix("<"),
              let data = trimmed.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let rotated = stringValue(json, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
        if isValidSessionToken(rotated) {
            updateSessionKey(rotated)
        }
        return json
    }

    private struct StillOnPrimaryBandError: Error {
        let message: String
    }

    private struct MissingDeviceInfoError: Error {
        let message: String
    }
    /// [Red Team RT-02] Server-side connected band & ONT identity ground-truth resolution immediately after Step 2:
    /// Queries `dhcpclient.json` when `localIpv4` is valid (`wlAssociatedDevice` prioritized over `dhcpInfoList`)
    /// and UNCONDITIONALLY queries `deviceinfo2.json` to capture trimmed `baseMacAddress`.
    public func resolveConnectedBandFromOnt(
        localIpv4: String?,
        fallbackBand: WifiBand
    ) async -> (band: WifiBand, ontBaseMac: String?) {
        guard isValidSessionToken(currentSessionKey) else {
            return (fallbackBand, nil)
        }
        let cleanIp = (localIpv4 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let hasValidSubnetIp = !cleanIp.isEmpty && cleanIp.hasPrefix("192.168.1.") && cleanIp != "192.168.1.1"

        var matchedWlIfc = ""
        if hasValidSubnetIp, let dhcpJson = try? await postOptionalSessionJson(path: "/cgi-bin/luci/dhcpclient.json") {
            var clientMac = ""
            if let dhcpArr = dhcpJson["dhcpInfoList"] as? [Any] {
                for item in dhcpArr {
                    let parts = pipeRowString(item).components(separatedBy: "|")
                    if parts.first?.trimmingCharacters(in: .whitespacesAndNewlines) == cleanIp {
                        if parts.count > 3 {
                            clientMac = parts[3].trimmingCharacters(in: .whitespacesAndNewlines)
                        }
                        break
                    }
                }
            }

            var exactIpIfc = ""
            var macFallbackIfc = ""
            if let assocArr = dhcpJson["wlAssociatedDevice"] as? [Any] {
                for item in assocArr {
                    let assocParts = pipeRowString(item).components(separatedBy: "|")
                    let assocMac = assocParts.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    let assocIp = assocParts.count > 1
                        ? assocParts[1].trimmingCharacters(in: .whitespacesAndNewlines)
                        : ""
                    let ifcAt3 = assocParts.count > 3 ? normalizeWlInterface(assocParts[3]) : ""
                    let rowIfc = !ifcAt3.isEmpty
                        ? ifcAt3
                        : (assocParts.map { normalizeWlInterface($0) }.first(where: { !$0.isEmpty }) ?? "")
                    guard !rowIfc.isEmpty else { continue }
                    if !cleanIp.isEmpty && assocIp == cleanIp && exactIpIfc.isEmpty {
                        exactIpIfc = rowIfc
                    }
                    if !clientMac.isEmpty && assocMac.caseInsensitiveCompare(clientMac) == .orderedSame && macFallbackIfc.isEmpty {
                        macFallbackIfc = rowIfc
                    }
                }
            }
            matchedWlIfc = !exactIpIfc.isEmpty ? exactIpIfc : macFallbackIfc
        }

        var ontBaseMac: String?
        var resolvedFromDeviceInfo: WifiBand?
        var fetchedDevJson: [String: Any]?
        for attempt in 1...3 {
            if let json = try? await postOptionalSessionJson(path: "/cgi-bin/luci/deviceinfo2.json") {
                fetchedDevJson = json
                break
            }
            if attempt < 3 {
                let retryMs = min(max(settlingDelayMs, 5), 150)
                try? await Task.sleep(nanoseconds: UInt64(retryMs) * 1_000_000)
            }
        }
        if let devJson = fetchedDevJson {
            let rawMac = stringValue(devJson, "baseMacAddress").trimmingCharacters(in: .whitespacesAndNewlines)
            if !rawMac.isEmpty {
                ontBaseMac = rawMac
            }
            if !matchedWlIfc.isEmpty, let wlList = devJson["wirelessInfoList"] as? [Any] {
                for rowItem in wlList {
                    let entryStr = pipeRowString(rowItem)
                    guard !entryStr.isEmpty else { continue }
                    let subData = entryStr
                        .components(separatedBy: "|")
                        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    guard let firstCol = subData.first else { continue }
                    let entryIfc = normalizeWlInterface(firstCol)
                    if entryIfc == matchedWlIfc {
                        let chanField = subData.count > 3 ? subData[3] : ""
                        let chan = Int(String(chanField.prefix(while: { $0.isNumber }))) ?? 0
                        if (1...14).contains(chan) {
                            resolvedFromDeviceInfo = .band24GHz
                        } else if chan >= 36 {
                            resolvedFromDeviceInfo = .band5GHz
                        } else {
                            resolvedFromDeviceInfo = (entryIfc == "wl1") ? .band5GHz : .band24GHz
                        }
                        break
                    }
                }
            }
        }

        let finalBand: WifiBand
        if let resolved = resolvedFromDeviceInfo {
            finalBand = resolved
        } else if matchedWlIfc == "wl1" {
            finalBand = .band5GHz
        } else if matchedWlIfc == "wl0" {
            finalBand = .band24GHz
        } else {
            finalBand = fallbackBand
        }
        return (finalBand, ontBaseMac)
    }

    /// [Red Team RT-01] Authenticate Connection 2 strictly with `newAdminPass` ONLY — NEVER falling back to `adminDefaultPass`.
    private func authenticateSecondConnection(
        newAdminPass: String,
        previousSessionKey: String,
        userExistWaitBudgetMs: Int = 65_000
    ) async throws -> String {
        func ensureTransportAlive(_ stage: String) throws {
            if isNetworkTransportAlive?() == false {
                throw OntNetworkError("Mất liên kết Wi-Fi en0 trong giai đoạn \(stage) ở Lần kết nối 2.")
            }
        }

        func attemptNewPassLoginOnce() async throws -> String {
            try ensureTransportAlive("gửi login.cmd")
            let loginData = try await login(user: "admin", password: newAdminPass)
            let status = stringValue(loginData, "status").trimmingCharacters(in: .whitespacesAndNewlines)
            let firstLog = stringValue(loginData, "firstlog").trimmingCharacters(in: .whitespacesAndNewlines)
            if firstLog == "1" || status.caseInsensitiveCompare("FAIL") == .orderedSame || !isValidSessionToken(currentSessionKey) {
                throw OntAuthenticationError(
                    "Đăng nhập Lần kết nối 2 bằng mật khẩu Admin mới thất bại (status=\(status), firstlog=\(firstLog)). " +
                    "Dừng ngay để tránh khóa ONT — hãy kiểm tra xem iPhone có đang bắt nhầm Wi-Fi khác không."
                )
            }
            return currentSessionKey
        }

        do {
            return try await attemptNewPassLoginOnce()
        } catch let e as OntAccountLockoutError {
            throw e
        } catch let e as OntAccountLockoutWarning {
            throw e
        } catch let e as OntAuthenticationError {
            throw e
        } catch is OntUserExistError {
            try ensureTransportAlive("xử lý user exist")
            logDiag(.warn, "AUTH", "Phát hiện 'user exist' ở Lần 2 — thử giải phóng phiên cũ và chờ TTL.")
            var lastNetworkError: OntNetworkError?
            var sawLiveUserExist = true
            let keyToRelease = previousSessionKey.isEmpty ? lastActiveSessionKey : previousSessionKey
            if isValidSessionToken(keyToRelease) {
                currentSessionKey = keyToRelease
                await logout()
                try ensureTransportAlive("sau logout phiên cũ")
                do {
                    return try await attemptNewPassLoginOnce()
                } catch let e2 as OntAccountLockoutError {
                    throw e2
                } catch let e2 as OntAccountLockoutWarning {
                    throw e2
                } catch let e2 as OntAuthenticationError {
                    throw e2
                } catch is OntUserExistError {
                    sawLiveUserExist = true
                    lastNetworkError = nil
                } catch let ne as OntNetworkError {
                    if isTlsPinMismatch(ne) {
                        throw OntDriverException("Chứng chỉ TLS của ONT ở Lần kết nối 2 không khớp với Lần 1.", underlyingError: ne)
                    }
                    try ensureTransportAlive("lỗi mạng sau logout phiên cũ")
                    lastNetworkError = ne
                    sawLiveUserExist = false
                }
            }

            let budgetSec = Double(max(userExistWaitBudgetMs, userExistPollIntervalMs)) / 1000.0
            let deadline = Date().addingTimeInterval(budgetSec)
            while Date() < deadline {
                try Task.checkCancellation()
                try ensureTransportAlive("chờ giải phóng phiên user exist")
                let remMs = max(10, Int(deadline.timeIntervalSinceNow * 1000.0))
                let sleepMs = min(max(userExistPollIntervalMs, 10), remMs)
                try await Task.sleep(nanoseconds: UInt64(sleepMs) * 1_000_000)
                try ensureTransportAlive("trước khi thử lại login.cmd")
                do {
                    return try await attemptNewPassLoginOnce()
                } catch is OntUserExistError {
                    sawLiveUserExist = true
                    lastNetworkError = nil
                    continue
                } catch let ne as OntNetworkError {
                    if isTlsPinMismatch(ne) {
                        throw OntDriverException("Chứng chỉ TLS của ONT ở Lần kết nối 2 không khớp với Lần 1.", underlyingError: ne)
                    }
                    try ensureTransportAlive("rớt mạng khi chờ user exist")
                    lastNetworkError = ne
                    sawLiveUserExist = false
                    continue
                }
            }
            if let netErr = lastNetworkError, !sawLiveUserExist {
                throw netErr
            }
            throw OntUserExistError(
                "Tài khoản admin ONT vẫn bận phiên cũ (user exist) sau khi chờ \(userExistWaitBudgetMs / 1000)s ở Lần kết nối 2."
            )
        } catch {
            if isTlsPinMismatch(error) {
                throw OntDriverException("Chứng chỉ TLS của ONT ở Lần kết nối 2 không khớp với Lần 1.", underlyingError: error)
            }
            throw error
        }
    }

    /// [Red Team RT-01] Readiness & session synchronization gate on Secondary Wi-Fi (`Band B`),
    /// re-authenticating exclusively with `settings.adminNewPass` (zero `adminDefaultPass` fallback).
    private func ensureGatewayReadyOnSecondaryBand(
        settings: GlobalSettings,
        timeoutBudgetMs: Int
    ) async throws -> Bool {
        let budgetSec = Double(max(timeoutBudgetMs, timeoutMs)) / 1000.0
        let deadline = Date().addingTimeInterval(budgetSec)
        let stepDelayMs = max(recoveryRetryDelayMs, 20)

        while Date() < deadline {
            try Task.checkCancellation()
            if try await probeGatewayCheckingCertPin(failOnCertMismatch: true) {
                let activeKey = currentSessionKey
                var httpResp: HttpResponsePayload?
                if isValidSessionToken(activeKey) {
                    do {
                        let encKey = OntInputValidator.urlEncodeFormValue(activeKey)
                        httpResp = try await postForm(
                            path: "/cgi-bin/luci/wlrestart.json",
                            rawPayload: "undefined&sessionKey=\(encKey)"
                        )
                    } catch {
                        if error is CancellationError { throw error }
                        if isTlsPinMismatch(error) {
                            throw OntDriverException("Chứng chỉ TLS ONT không khớp khi đồng bộ Lần 2.", underlyingError: error)
                        }
                    }
                }

                let wlJson: [String: Any]? = httpResp.flatMap { resp in
                    let trimmed = resp.body.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard resp.statusCode == 200, !trimmed.isEmpty, !trimmed.hasPrefix("<"),
                          let data = trimmed.data(using: .utf8) else {
                        return nil
                    }
                    return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                }
                let statusStr = wlJson.map { stringValue($0, "status").trimmingCharacters(in: .whitespacesAndNewlines) } ?? ""

                if wlJson == nil || statusStr.lowercased() == "fail authen" {
                    let remMs = max(userExistPollIntervalMs, Int(deadline.timeIntervalSinceNow * 1000.0))
                    do {
                        _ = try await authenticateSecondConnection(
                            newAdminPass: settings.adminNewPass,
                            previousSessionKey: lastActiveSessionKey,
                            userExistWaitBudgetMs: remMs
                        )
                        let encFresh = OntInputValidator.urlEncodeFormValue(currentSessionKey)
                        if let checkResp = try? await postForm(
                            path: "/cgi-bin/luci/wlrestart.json",
                            rawPayload: "undefined&sessionKey=\(encFresh)"
                        ), checkResp.statusCode == 200,
                           let d = checkResp.body.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
                           let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
                           stringValue(j, "_wlrestart_", default: "0").trimmingCharacters(in: .whitespacesAndNewlines) != "0" {
                            let pollWait = min(max(wlRestartPollIntervalMs, 10), max(10, Int(deadline.timeIntervalSinceNow * 1000.0)))
                            try await Task.sleep(nanoseconds: UInt64(pollWait) * 1_000_000)
                            continue
                        }
                        return true
                    } catch let e as OntAccountLockoutError {
                        throw e
                    } catch let e as OntAccountLockoutWarning {
                        throw e
                    } catch let e as OntAuthenticationError {
                        throw e
                    } catch let e as CancellationError {
                        throw e
                    } catch let de as OntDriverException {
                        if !(de is OntNetworkError) && !(de is OntUserExistError) {
                            throw de
                        }
                    }
                } else if let validJson = wlJson,
                          stringValue(validJson, "_wlrestart_", default: "0").trimmingCharacters(in: .whitespacesAndNewlines) != "0" {
                    let pollWaitMs = min(max(wlRestartPollIntervalMs, 10), max(10, Int(deadline.timeIntervalSinceNow * 1000.0)))
                    try await Task.sleep(nanoseconds: UInt64(pollWaitMs) * 1_000_000)
                    continue
                } else {
                    return true
                }
            }

            let remainingMs = Int(deadline.timeIntervalSinceNow * 1000.0)
            if remainingMs > 0 {
                try await Task.sleep(nanoseconds: UInt64(min(stepDelayMs, remainingMs)) * 1_000_000)
            }
        }
        return false
    }

    /// [Red Team RT-02] Verify `baseMacAddress` match and live `wlAssociatedDevice == expectedBand` on Connection 2.
    private func verifySecondConnectionBandAndMac(
        localIpv4: String?,
        expectedBand: WifiBand,
        expectedOntBaseMac: String?,
        isSameSsidBothBands: Bool = false
    ) async throws {
        let pollBudgetMs = max(20, bandVerifyPollTimeoutMs)
        let pollIntervalMs = max(10, bandVerifyPollIntervalMs)
        let deadline = Date().addingTimeInterval(Double(pollBudgetMs) / 1000.0)
        var consecutiveWrongBandCount = 0
        var lastStillPrimary: StillOnPrimaryBandError?
        var lastMissingDevInfo: MissingDeviceInfoError?
        var lastNetworkErr: OntNetworkError?
        var pollAttempt = 0

        while true {
            try Task.checkCancellation()
            pollAttempt += 1
            if isNetworkTransportAlive?() == false {
                throw OntNetworkError("Mất liên kết Wi-Fi en0 trong lúc xác thực bảng trạm Lần kết nối 2.")
            }

            do {
                try await verifySecondConnectionBandAndMacOnce(
                    localIpv4: localIpv4,
                    expectedBand: expectedBand,
                    expectedOntBaseMac: expectedOntBaseMac,
                    isSameSsidBothBands: isSameSsidBothBands
                )
                logDiag(.info, "VERIFY", "Xác thực thành công băng tần \(expectedBand.label) & MAC=\(expectedOntBaseMac ?? "N/A") (poll #\(pollAttempt)).")
                return
            } catch let ce as CancellationError {
                throw ce
            } catch let wrongBand as StillOnPrimaryBandError {
                consecutiveWrongBandCount += 1
                lastStillPrimary = wrongBand
                logDiag(.warn, "VERIFY", "Poll #\(pollAttempt) thấy vẫn ở băng tần cũ (\(consecutiveWrongBandCount)/2): \(wrongBand.message)")
                if consecutiveWrongBandCount >= 2 || Date() >= deadline {
                    throw wrongBand
                }
            } catch let missingInfo as MissingDeviceInfoError {
                consecutiveWrongBandCount = 0
                lastMissingDevInfo = missingInfo
                logDiag(.info, "VERIFY", "Poll #\(pollAttempt) đang đợi deviceinfo2.json phản hồi: \(missingInfo.message)")
                if Date() >= deadline {
                    throw missingInfo
                }
            } catch let ne as OntNetworkError {
                if isTlsPinMismatch(ne) {
                    throw OntDriverException("Chứng chỉ TLS của ONT ở Lần kết nối 2 không khớp với Lần 1.", underlyingError: ne)
                }
                consecutiveWrongBandCount = 0
                lastNetworkErr = ne
                logDiag(.info, "VERIFY", "Poll #\(pollAttempt) đang đợi bảng wlAssociatedDevice hội tụ: \(ne.localizedDescription)")
                if Date() >= deadline {
                    if !isSameSsidBothBands {
                        logDiag(.info, "VERIFY", "2 Wi-Fi khác tên và ONT phản hồi đúng MAC \(expectedOntBaseMac ?? "") — xác nhận đã chuyển sang \(expectedBand.label) thành công sau khi hết thời gian chờ bảng trạm.")
                        return
                    }
                    throw ne
                }
            }
            let remainingMs = Int(deadline.timeIntervalSinceNow * 1000.0)
            if remainingMs <= 0 { break }
            try await Task.sleep(nanoseconds: UInt64(min(pollIntervalMs, remainingMs)) * 1_000_000)
        }

        if let wrongBand = lastStillPrimary {
            throw wrongBand
        }
        if let missingInfo = lastMissingDevInfo {
            throw missingInfo
        }
        throw lastNetworkErr ?? OntNetworkError(
            "Chưa xác thực được bảng kết nối wlAssociatedDevice của băng tần \(expectedBand.label) sau \(pollBudgetMs)ms."
        )
    }

    private func verifySecondConnectionBandAndMacOnce(
        localIpv4: String?,
        expectedBand: WifiBand,
        expectedOntBaseMac: String?,
        isSameSsidBothBands: Bool = false
    ) async throws {
        var ifcBandMap: [String: WifiBand] = [:]
        let devJson = try await postOptionalSessionJson(path: "/cgi-bin/luci/deviceinfo2.json")
        let actualBaseMac = devJson.map { stringValue($0, "baseMacAddress").trimmingCharacters(in: .whitespacesAndNewlines) } ?? ""
        if let expectedMac = expectedOntBaseMac?.trimmingCharacters(in: .whitespacesAndNewlines), !expectedMac.isEmpty {
            if !actualBaseMac.isEmpty && actualBaseMac.caseInsensitiveCompare(expectedMac) != .orderedSame {
                throw OntDriverException(
                    "Địa chỉ MAC của ONT ở Lần kết nối 2 (\(actualBaseMac)) không khớp với ONT ban đầu (\(expectedMac)) — nghi ngờ bắt nhầm ONT khác."
                )
            }
            if actualBaseMac.isEmpty {
                throw MissingDeviceInfoError(
                    message: "Chưa đọc được baseMacAddress từ deviceinfo2.json ở Lần kết nối 2 để đối chiếu với ONT ban đầu (\(expectedMac))."
                )
            }
        }
        if let wlList = devJson?["wirelessInfoList"] as? [Any] {
            for rowItem in wlList {
                let entryStr = pipeRowString(rowItem)
                guard !entryStr.isEmpty else { continue }
                let subData = entryStr
                    .components(separatedBy: "|")
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                guard let firstCol = subData.first else { continue }
                let entryIfc = normalizeWlInterface(firstCol)
                guard !entryIfc.isEmpty else { continue }
                let chanField = subData.count > 3 ? subData[3] : ""
                let chan = Int(String(chanField.prefix(while: { $0.isNumber }))) ?? 0
                if (1...14).contains(chan) {
                    ifcBandMap[entryIfc] = .band24GHz
                } else if chan >= 36 {
                    ifcBandMap[entryIfc] = .band5GHz
                } else {
                    ifcBandMap[entryIfc] = (entryIfc == "wl1") ? .band5GHz : .band24GHz
                }
            }
        }

        guard let dhcpJson = try await postOptionalSessionJson(path: "/cgi-bin/luci/dhcpclient.json") else {
            throw OntNetworkError("Không đọc được bảng kết nối Wi-Fi (dhcpclient.json) ở Lần kết nối 2.")
        }
        guard let assocArr = dhcpJson["wlAssociatedDevice"] as? [Any] else {
            throw OntNetworkError(
                "Bảng wlAssociatedDevice chưa sẵn sàng ở Lần kết nối 2 cho băng tần \(expectedBand.label)."
            )
        }

        let cleanIp = (localIpv4 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        var clientMac = ""
        if !cleanIp.isEmpty, let dhcpArr = dhcpJson["dhcpInfoList"] as? [Any] {
            for item in dhcpArr {
                let parts = pipeRowString(item).components(separatedBy: "|")
                if parts.first?.trimmingCharacters(in: .whitespacesAndNewlines) == cleanIp {
                    if parts.count > 3 {
                        clientMac = parts[3].trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                    break
                }
            }
        }

        var matchedAssocIfc = ""
        // Pass 1: exact active IP match (eliminates stale association rows from previous connections)
        if !cleanIp.isEmpty {
            for item in assocArr {
                let assocParts = pipeRowString(item).components(separatedBy: "|")
                let assocIp = assocParts.count > 1 ? assocParts[1].trimmingCharacters(in: .whitespacesAndNewlines) : ""
                let ifcAt3 = assocParts.count > 3 ? normalizeWlInterface(assocParts[3]) : ""
                let rowIfc = !ifcAt3.isEmpty
                    ? ifcAt3
                    : (assocParts.map { normalizeWlInterface($0) }.first { !$0.isEmpty } ?? "")
                if !rowIfc.isEmpty && assocIp == cleanIp {
                    matchedAssocIfc = rowIfc
                    break
                }
            }
        }
        // Pass 2: MAC match, preferring expectedBand if multiple entries exist
        if matchedAssocIfc.isEmpty && !clientMac.isEmpty {
            var preferredIfc = ""
            var fallbackIfc = ""
            for item in assocArr {
                let assocParts = pipeRowString(item).components(separatedBy: "|")
                let assocMac = assocParts.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let ifcAt3 = assocParts.count > 3 ? normalizeWlInterface(assocParts[3]) : ""
                let rowIfc = !ifcAt3.isEmpty
                    ? ifcAt3
                    : (assocParts.map { normalizeWlInterface($0) }.first { !$0.isEmpty } ?? "")
                if !rowIfc.isEmpty && assocMac.caseInsensitiveCompare(clientMac) == .orderedSame {
                    let band = ifcBandMap[rowIfc] ?? ((rowIfc == "wl1") ? .band5GHz : .band24GHz)
                    if band == expectedBand {
                        preferredIfc = rowIfc
                        break
                    }
                    if fallbackIfc.isEmpty {
                        fallbackIfc = rowIfc
                    }
                }
            }
            if !preferredIfc.isEmpty {
                matchedAssocIfc = preferredIfc
            } else if isSameSsidBothBands && !fallbackIfc.isEmpty {
                matchedAssocIfc = fallbackIfc
            }
        }
        // Pass 3: single entry fallback
        if matchedAssocIfc.isEmpty && assocArr.count == 1 {
            let assocParts = pipeRowString(assocArr[0]).components(separatedBy: "|")
            let ifcAt3 = assocParts.count > 3 ? normalizeWlInterface(assocParts[3]) : ""
            let singleIfc = !ifcAt3.isEmpty
                ? ifcAt3
                : (assocParts.map { normalizeWlInterface($0) }.first { !$0.isEmpty } ?? "")
            if !singleIfc.isEmpty {
                let band = ifcBandMap[singleIfc] ?? ((singleIfc == "wl1") ? .band5GHz : .band24GHz)
                if band == expectedBand || isSameSsidBothBands {
                    matchedAssocIfc = singleIfc
                }
            }
        }
        guard !matchedAssocIfc.isEmpty else {
            throw OntNetworkError(
                "Chưa thấy iPhone trong bảng kết nối trực tiếp wlAssociatedDevice của băng tần \(expectedBand.label)."
            )
        }

        let liveBand = ifcBandMap[matchedAssocIfc] ?? ((matchedAssocIfc == "wl1") ? .band5GHz : .band24GHz)
        if liveBand != expectedBand {
            throw StillOnPrimaryBandError(
                message: "Thiết bị vẫn đang kết nối trên băng tần \(liveBand.label) (\(matchedAssocIfc)) thay vì băng tần phụ \(expectedBand.label)."
            )
        }
    }

    /// [Red Team RT-02] Read-only NVRAM SSID check (`page=ssid.asp&wl_unit=\(unit)&wl_bssid=0` without `action=Apply`).
    private func readConfiguredBandSsidFromNvram(unit: Int) async -> String? {
        let activeKey = currentSessionKey
        guard isValidSessionToken(activeKey) else { return nil }
        let encKey = OntInputValidator.urlEncodeFormValue(activeKey)
        let payload = "page=ssid.asp&wl_unit=\(unit)&wl_bssid=0&sessionKey=\(encKey)"
        guard let resp = try? await postForm(
            path: "/cgi-bin/luci/wirelesscfg.json",
            rawPayload: payload,
            useShortLogoutTimeout: false,
            referer: "\(cleanBaseUrl)/wireless.html"
        ) else {
            return nil
        }
        let trimmed = resp.body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard resp.statusCode == 200,
              !trimmed.isEmpty,
              !trimmed.hasPrefix("<"),
              let data = trimmed.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let rotated = stringValue(json, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
        if isValidSessionToken(rotated) {
            updateSessionKey(rotated)
        }
        guard json["nvramWlSsid"] != nil else { return nil }
        return stringValue(json, "nvramWlSsid")
    }

    private func commitRadioReloadBestEffort() async {
        do {
            let resResp = try await postForm(
                path: "/cgi-bin/luci/wlrestart.json",
                rawPayload: "undefined&sessionKey=\(currentSessionKey)"
            )
            let resJson = try parseJsonOrThrow(
                stepContext: "Khởi động lại driver Wi-Fi (wlrestart.json)",
                statusCode: resResp.statusCode,
                body: resResp.body
            )
            if maxWlRestartPolls > 0 && stringValue(resJson, "_wlrestart_", default: "0") != "0" {
                for _ in 1...maxWlRestartPolls {
                    if wlRestartPollIntervalMs > 0 {
                        try await Task.sleep(nanoseconds: UInt64(wlRestartPollIntervalMs) * 1_000_000)
                    }
                    let pollResp = try await postForm(
                        path: "/cgi-bin/luci/wlrestart.json",
                        rawPayload: "undefined&sessionKey=\(currentSessionKey)"
                    )
                    let pollJson = try parseJsonOrThrow(
                        stepContext: "Kiểm tra wlrestart.json",
                        statusCode: pollResp.statusCode,
                        body: pollResp.body
                    )
                    if stringValue(pollJson, "_wlrestart_", default: "0") == "0" {
                        break
                    }
                }
            }
        } catch {
            // NVRAM already confirmed saved via readConfiguredBandSsidFromNvram
        }
    }

    /// Step 9: Upload and apply ONT firmware via 3-step LuCI CGI sequence (`updatefwsessioncheck.json` -> `imagesize.json` -> `uploadsoftware.conf`).
    @discardableResult
    public func updateFirmware(
        firmwareURL: URL,
        uploadTimeoutMs: Int = 180_000,
        onProgressPercent: (@Sendable (Int) -> Void)? = nil
    ) async throws -> [String: Any] {
        let fileManager = FileManager.default
        var isDir: ObjCBool = false
        guard fileManager.fileExists(atPath: firmwareURL.path, isDirectory: &isDir), !isDir.boolValue else {
            throw OntValidationError("File firmware không tồn tại: '\(firmwareURL.path)'")
        }
        let rawName = firmwareURL.lastPathComponent
        guard OntInputValidator.isValidFirmwareExtension(rawName) else {
            throw OntValidationError("Định dạng file firmware không hợp lệ (chỉ hỗ trợ .signed, .bin, .img): '\(rawName)'")
        }
        let attrs = try? fileManager.attributesOfItem(atPath: firmwareURL.path)
        let fileSize = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        guard (OntInputValidator.minFirmwareSizeBytes...OntInputValidator.maxFirmwareSizeBytes).contains(fileSize) else {
            throw OntValidationError("Dung lượng file firmware không hợp lệ (\(fileSize) bytes): '\(rawName)'")
        }

        let safeFilename = OntInputValidator.sanitizeFirmwareFilename(rawName)
        let refererUrl = "\(cleanBaseUrl)/update_software.html"

        // 1. Check firmware update session
        let encKey1 = OntInputValidator.urlEncodeFormValue(currentSessionKey)
        let chkResp: HttpResponsePayload
        do {
            chkResp = try await postForm(
                path: "/cgi-bin/luci/updatefwsessioncheck.json",
                rawPayload: "sessionKey=\(encKey1)",
                referer: refererUrl
            )
        } catch {
            throw OntNetworkError("Lỗi mạng khi kiểm tra phiên nâng cấp firmware: \(error.localizedDescription)", cause: error)
        }
        let chkData = try parseJsonOrThrow(
            stepContext: "Kiểm tra phiên nâng cấp firmware (updatefwsessioncheck.json)",
            statusCode: chkResp.statusCode,
            body: chkResp.body
        )
        let chkRotated = stringValue(chkData, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
        if !chkRotated.isEmpty && chkRotated != "0" {
            updateSessionKey(chkRotated)
        }
        let chkStatus = stringValue(chkData, "status")
        guard chkStatus == "OK" else {
            throw OntDriverException("Phiên nâng cấp firmware không hợp lệ (status=\(chkStatus)).")
        }

        // 2. Query available RAM size (KB)
        let encKey2 = OntInputValidator.urlEncodeFormValue(currentSessionKey)
        let imgResp: HttpResponsePayload
        do {
            imgResp = try await postForm(
                path: "/cgi-bin/luci/imagesize.json",
                rawPayload: "sessionKey=\(encKey2)",
                referer: refererUrl
            )
        } catch {
            throw OntNetworkError("Lỗi mạng khi kiểm tra dung lượng RAM trống của ONT: \(error.localizedDescription)", cause: error)
        }
        let imgData = try parseJsonOrThrow(
            stepContext: "Kiểm tra dung lượng RAM trống (imagesize.json)",
            statusCode: imgResp.statusCode,
            body: imgResp.body
        )
        let imgRotated = stringValue(imgData, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
        if !imgRotated.isEmpty && imgRotated != "0" {
            updateSessionKey(imgRotated)
        }
        let ramFreeStr = stringValue(imgData, "ramFree").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let ramFreeKb = Int64(ramFreeStr) else {
            throw OntDriverException("Không đọc được thông số ramFree hợp lệ từ ONT (ramFree='\(ramFreeStr)').")
        }
        let fileSizeKb = Double(fileSize) / 1024.0
        if ramFreeKb <= 0 || fileSizeKb > Double(ramFreeKb) {
            throw OntDriverException(
                "Không đủ bộ nhớ RAM trống trên ONT (\(ramFreeKb) KB) để tải file firmware (\(Int64(fileSizeKb)) KB)."
            )
        }

        // 3. Stream multipart firmware upload
        guard let uploadURL = URL(string: "\(cleanBaseUrl)/cgi-bin/luci/uploadsoftware.conf") else {
            throw OntValidationError("URL tải lên firmware không hợp lệ.")
        }
        let upResp: HttpResponsePayload
        do {
            upResp = try await session.uploadMultipartFile(
                url: uploadURL,
                headers: [
                    "User-Agent": Self.userAgent,
                    "Referer": refererUrl,
                    "Accept": "application/json, text/javascript, */*; q=0.01"
                ],
                fileFieldName: "file",
                fileURL: firmwareURL,
                safeFileName: safeFilename,
                formFields: [("sessionKey", currentSessionKey)],
                timeoutOverrideMs: uploadTimeoutMs,
                onProgressPercent: onProgressPercent
            )
        } catch {
            if error is CancellationError {
                throw error
            }
            if isTlsPinMismatch(error) {
                throw OntDriverException(error.localizedDescription, underlyingError: error)
            }
            // [Red Team RT-05, RT-07] Clear session keys whenever 100% of multipart bytes flushed, REGARDLESS of isAppInBackground()!
            let nsErr = error as NSError
            let didFlushFullRequestBody = (nsErr.userInfo["didFlushFullRequestBody"] as? Bool) ?? false
            if didFlushFullRequestBody && !isPreSendConnectFailure(error) {
                clearAllSessionKeys()
            }
            throw OntNetworkError("Lỗi kết nối trong khi tải lên file firmware: \(error.localizedDescription)", cause: error)
        }

        let upData = try parseJsonOrThrow(
            stepContext: "Tải lên file firmware (uploadsoftware.conf)",
            statusCode: upResp.statusCode,
            body: upResp.body
        )
        let upRotated = stringValue(upData, "sessionKey").trimmingCharacters(in: .whitespacesAndNewlines)
        if !upRotated.isEmpty && upRotated != "0" {
            updateSessionKey(upRotated)
        }
        let upStatus = stringValue(upData, "status", default: "unknown")
        guard upStatus == "valid" else {
            throw OntDriverException("File firmware không hợp lệ hoặc sai chữ ký (status=\(upStatus)).")
        }

        // Clear session keys immediately so logout.cmd is NEVER sent while ONT writes flash & reboots
        clearAllSessionKeys()
        return upData
    }
    private func isPreSendConnectFailure(_ error: Error) -> Bool {
        let nsErr = error as NSError
        if nsErr.domain == NSURLErrorDomain {
            switch nsErr.code {
            case NSURLErrorCannotConnectToHost,
                 NSURLErrorNotConnectedToInternet,
                 NSURLErrorCannotFindHost,
                 NSURLErrorDNSLookupFailed:
                return true
            case NSURLErrorTimedOut:
                let flushed = (nsErr.userInfo["didFlushFullRequestBody"] as? Bool) ?? false
                return !flushed
            default:
                break
            }
        }
        if let underlying = nsErr.userInfo[NSUnderlyingErrorKey] as? Error {
            return isPreSendConnectFailure(underlying)
        }
        return false
    }

    private func isPostFlushRadioDrop(_ error: Error) -> Bool {
        guard !isAppInBackground() else { return false }
        if isPreSendConnectFailure(error) { return false }

        let nsErr = error as NSError
        if nsErr.domain == NSURLErrorDomain {
            switch nsErr.code {
            case NSURLErrorNetworkConnectionLost,
                 NSURLErrorCannotParseResponse:
                return true
            case NSURLErrorTimedOut:
                return (nsErr.userInfo["didFlushFullRequestBody"] as? Bool) ?? false
            default:
                break
            }
        }
        if let underlying = nsErr.userInfo[NSUnderlyingErrorKey] as? Error {
            return isPostFlushRadioDrop(underlying)
        }
        let msg = nsErr.localizedDescription.lowercased()
        return msg.contains("connection lost") ||
            msg.contains("connection reset") ||
            msg.contains("broken pipe") ||
            msg.contains("eof")
    }

    /// Mandatory session release (1.5s timeout). Swallows socket errors if Wi-Fi radio already restarted.
    public func logout() async {
        let key = currentSessionKey
        guard !key.isEmpty else { return }
        stateLock.lock()
        let user = currentUser
        stateLock.unlock()

        let payload = "status=user want to logout&user=\(user)&sessionKey=\(key)"
        var releasedOk = false
        if let resp = try? await postForm(
            path: "/cgi-bin/luci/logout.cmd",
            rawPayload: payload,
            useShortLogoutTimeout: true
        ) {
            releasedOk = (resp.statusCode == 200)
        }
        stateLock.lock()
        sessionKey = ""
        if releasedOk && lastActiveSessionKeyStorage == key {
            lastActiveSessionKeyStorage = ""
        }
        stateLock.unlock()
    }

    /// Execute the automated Two-Connection Dual-Wi-Fi + Final Firmware Update workflow (8 or 9 steps).
    public func executeMobileSetup(
        record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings(),
        connectedBand: WifiBand = .band24GHz,
        localWifiIpv4: String? = nil,
        onBandResolved: (@Sendable (WifiBand, String?) -> Void)? = nil,
        onProgress: (@Sendable (StepProgress) -> Void)? = nil
    ) async throws -> MobileSetupResult {
        let preRunCheck = OntInputValidator.validateBeforeRun(record, settings: settings)
        guard preRunCheck.isValid else {
            throw OntValidationError(preRunCheck.errors.joined(separator: "; "))
        }

        let doFwUpdate = settings.enableFirmwareUpdate
        let fwURL: URL?
        if doFwUpdate {
            let fwPath = settings.firmwareFilePath.trimmingCharacters(in: .whitespacesAndNewlines)
            let candidateURL = URL(fileURLWithPath: fwPath)
            var isDir: ObjCBool = false
            guard !fwPath.isEmpty,
                  FileManager.default.fileExists(atPath: candidateURL.path, isDirectory: &isDir),
                  !isDir.boolValue else {
                throw OntValidationError("File firmware không tồn tại tại đường dẫn: '\(fwPath)'")
            }
            guard OntInputValidator.isValidFirmwareExtension(candidateURL.lastPathComponent) else {
                throw OntValidationError("Định dạng file firmware không hợp lệ: '\(candidateURL.lastPathComponent)'")
            }
            let attrs = try? FileManager.default.attributesOfItem(atPath: candidateURL.path)
            let fLen = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
            guard (OntInputValidator.minFirmwareSizeBytes...OntInputValidator.maxFirmwareSizeBytes).contains(fLen) else {
                throw OntValidationError("Dung lượng file firmware không hợp lệ (\(fLen) bytes): '\(candidateURL.lastPathComponent)'")
            }
            fwURL = candidateURL
        } else {
            fwURL = nil
        }

        let cleanSec = settings.wifiSecurityMode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let secMode = WifiSecurityMode(rawValue: cleanSec) else {
            throw OntValidationError("Chế độ bảo mật Wi-Fi '\(settings.wifiSecurityMode)' không hợp lệ.")
        }
        let totalSteps = doFwUpdate ? 9 : 8
        let wifi24Ssid = record.effectiveWifi24Ssid
        let wifi24Pass = record.effectiveWifi24Pass(secMode: secMode.rawValue)
        let wifi5Ssid = record.effectiveWifi5Ssid
        let wifi5Pass = record.effectiveWifi5Pass(secMode: secMode.rawValue)
        let recordKey = Self.recordCacheKey(rowIndex: record.rowIndex, soMay: record.slid)

        @Sendable func emit(_ step: Int, _ title: String, _ status: StepStatus, _ detail: String) {
            let level: LogLevel = (status == .failed) ? .error : .info
            logDiag(level, "STEP\(step)", "[\(status.rawValue)] \(title) — \(detail)")
            onProgress?(StepProgress(stepNumber: step, totalSteps: totalSteps, title: title, status: status, detail: detail))
        }

        let initialInjectedPin = session.pinnedCertSha256
        await probePauseMutex?.lock()
        defer { probePauseMutex?.unlock() }
        defer {
            session.resetPeerCertificateTracking()
            logoutSession.resetPeerCertificateTracking()
        }
        clearAllSessionKeys()
        if let testPin = initialInjectedPin, !testPin.isEmpty {
            session.pinnedCertSha256 = testPin
            logoutSession.pinnedCertSha256 = testPin
        }

        do {
            // Step 1: Probe Gateway & Pin Leaf X.509 SHA-256 Certificate
            let step1Title = "Bước 1/\(totalSteps): Kiểm tra kết nối & nhận diện ONT Gateway"
            emit(1, step1Title, .running, "Đang kiểm tra \(cleanBaseUrl)...")
            guard try await probeGatewayCheckingCertPin(failOnCertMismatch: false) else {
                throw OntNetworkError(
                    "Không thể kết nối tới ONT GW040-NSv2 tại \(cleanBaseUrl) (không nhận diện được chữ ký iGate/GW040/VNPT)."
                )
            }
            if session.pinnedCertSha256 == nil, let observedSha = session.lastPeerCertSha256 {
                session.pinnedCertSha256 = observedSha
                logoutSession.pinnedCertSha256 = observedSha
            }
            emit(1, step1Title, .success, "Đã xác nhận kết nối tới ONT GW040-NSv2")

            // Step 2: Smart Login & Change Admin Password
            let step2Title = "Bước 2/\(totalSteps): Đăng nhập & Đổi mật khẩu Admin"
            emit(2, step2Title, .running, "Đang xác thực tài khoản admin...")
            _ = try await loginAndChangePassword(
                defaultPass: settings.adminDefaultPass,
                newPass: settings.adminNewPass,
                recordKey: recordKey,
                lastKnownAdminPass: record.lastKnownAdminPass
            )
            await onAdminPasswordChanged?(settings.adminNewPass)
            emit(2, step2Title, .success, "Đã xác thực và bảo mật tài khoản Admin")

            // Step 2c: Unconditional ONT band & baseMacAddress ground-truth resolution
            let trimmedLocalIp = (localWifiIpv4 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let (effectiveConnectedBand, resolvedOntBaseMac) = await resolveConnectedBandFromOnt(
                localIpv4: trimmedLocalIp,
                fallbackBand: connectedBand
            )
            if !trimmedLocalIp.isEmpty || resolvedOntBaseMac != nil {
                onBandResolved?(effectiveConnectedBand, resolvedOntBaseMac)
            }

            // Step 3: Idempotent WAN PPPoE VLAN 11
            let step3Title = "Bước 3/\(totalSteps): Cấu hình WAN PPPoE (VLAN \(settings.vlanId))"
            emit(3, step3Title, .running, "Account: \(record.accountPppoe)")
            try await configureWanIdempotent(
                vlanId: settings.vlanId,
                pppUser: record.accountPppoe,
                pppPass: record.passPppoe,
                mtu: settings.mtu
            )
            emit(3, step3Title, .success, "Đã lưu WAN PPPoE (\(record.accountPppoe))")

            // Step 4: GPON SLID / Password
            let step4Title = "Bước 4/\(totalSteps): Cài đặt GPON Password (SLID / Mã MEN)"
            emit(4, step4Title, .running, "SLID: \(record.slid)")
            try await setGponPassword(record.slid)
            emit(4, step4Title, .success, "Đã nạp SLID \(record.slid)")

            // Step 5: LAN IPv4
            let step5Title = "Bước 5/\(totalSteps): Kiểm tra & Lưu cấu hình LAN IPv4"
            emit(5, step5Title, .running, "Gateway IP: \(settings.lanIp)")
            try await configureLan(settings)
            emit(5, step5Title, .success, "Đã lưu cấu hình LAN \(settings.lanIp)")

            // Plan Connection 1 -> Connection 2 bands
            let isConnected24g = (effectiveConnectedBand == .band24GHz)
            let secondaryBand: WifiBand = isConnected24g ? .band5GHz : .band24GHz
            let secondaryUnit = isConnected24g ? 1 : 0
            let secondaryBandLabel = isConnected24g ? "5GHz" : "2.4GHz"
            let secondarySsid = isConnected24g ? wifi5Ssid : wifi24Ssid
            let secondaryPass = isConnected24g ? wifi5Pass : wifi24Pass

            let primaryUnit = isConnected24g ? 0 : 1
            let primaryBandLabel = isConnected24g ? "2.4GHz" : "5GHz"
            let primarySsid = isConnected24g ? wifi24Ssid : wifi5Ssid
            let primaryPass = isConnected24g ? wifi24Pass : wifi5Pass

            // Step 6: Configure Secondary Wi-Fi Band & Cleanly Logout Connection 1
            let step6Title = "Bước 6/\(totalSteps): Đổi tên & cấu hình Wi-Fi phụ \(secondaryBandLabel)"
            emit(6, step6Title, .running, "SSID phụ: \(secondarySsid)")
            _ = try await configureWifiBand(
                unit: secondaryUnit,
                ssid: secondarySsid,
                password: secondaryPass,
                secMode: secMode.rawValue,
                isConnectedBandFinalStep: false
            )
            if settlingDelayMs > 0 {
                try await Task.sleep(nanoseconds: UInt64(settlingDelayMs) * 1_000_000)
            }
            let conn1SessionKey = lastActiveSessionKey.isEmpty ? currentSessionKey : lastActiveSessionKey
            await logout()
            if !conn1SessionKey.isEmpty {
                stateLock.lock()
                lastActiveSessionKeyStorage = conn1SessionKey
                stateLock.unlock()
            }
            emit(6, step6Title, .success, "Đã lưu Wi-Fi phụ \(secondaryBandLabel) ('\(secondarySsid)') & đóng phiên Lần 1")

            // Step 7: Wait for ONT Radio Startup & Switch to Secondary Wi-Fi (Connection 2)
            let step7Title = "Bước 7/\(totalSteps): Đợi ONT phát sóng mới & Chuyển sang Wi-Fi \(secondaryBandLabel)"
            guard let switchHandler = onSwitchToSecondaryWifi else {
                throw OntDriverException(
                    "Chưa cấu hình hàm chuyển sóng (onSwitchToSecondaryWifi) cho quy trình 2 lần kết nối trên iOS."
                )
            }
            emit(
                7,
                step7Title,
                .running,
                "Đang chờ ONT khởi động lại sóng '\(secondarySsid)' (\(secondaryBandLabel)) và kết nối sang sóng mới..."
            )

            let handoverBudgetSec = Double(max(settings.wifiHandoverTimeoutMs, 100)) / 1000.0
            let handoverDeadline = Date().addingTimeInterval(handoverBudgetSec)
            var handoverVerified = false
            var outerAttempt = 0
            var missingDeviceInfoOuterCount = 0

            logDiag(
                .info,
                "STEP7",
                "Bắt đầu Bước 7: target='\(secondarySsid)' (\(secondaryBandLabel)), budget=\(settings.wifiHandoverTimeoutMs)ms."
            )

            while !handoverVerified {
                try Task.checkCancellation()
                outerAttempt += 1
                let remainingHandoverMs = max(50, Int(handoverDeadline.timeIntervalSinceNow * 1000.0))
                let candidateEn0Ip: String?
                do {
                    candidateEn0Ip = try await switchHandler(
                        secondarySsid,
                        secondaryPass,
                        secondaryBand,
                        secMode.rawValue,
                        resolvedOntBaseMac,
                        remainingHandoverMs
                    ) { statusDetail in
                        emit(7, step7Title, .running, statusDetail)
                    }
                } catch let ce as CancellationError {
                    throw ce
                } catch let de as OntDriverException {
                    if !(de is OntNetworkError) {
                        throw de
                    }
                    logDiag(.warn, "STEP7", "Lượt chuyển sóng #\(outerAttempt) chưa sẵn sàng: \(de.localizedDescription)")
                    let remAfterSwitchMs = Int(handoverDeadline.timeIntervalSinceNow * 1000.0)
                    let minInitial = max(10, handoverRetryInitialBackoffMs)
                    if remAfterSwitchMs <= minInitial {
                        throw de
                    }
                    let backoffMs = min(
                        Self.computeReconnectBackoffMs(
                            attempt: outerAttempt,
                            initialBackoffMs: handoverRetryInitialBackoffMs,
                            maxBackoffMs: handoverRetryMaxBackoffMs
                        ),
                        remAfterSwitchMs - 10
                    )
                    try await Task.sleep(nanoseconds: UInt64(max(10, backoffMs)) * 1_000_000)
                    continue
                } catch {
                    throw OntNetworkError(
                        "Không thể chuyển kết nối sang Wi-Fi mới '\(secondarySsid)' (\(secondaryBandLabel)): \(error.localizedDescription)",
                        cause: error
                    )
                }

                do {
                    let remPostDhcpMs = max(0, Int(handoverDeadline.timeIntervalSinceNow * 1000.0))
                    let settleAfterDhcpMs = min(max(0, postDhcpGatewaySettleMs), remPostDhcpMs / 3)
                    if settleAfterDhcpMs > 0 {
                        try await Task.sleep(nanoseconds: UInt64(settleAfterDhcpMs) * 1_000_000)
                    }

                    if isNetworkTransportAlive?() == false {
                        throw OntNetworkError("Liên kết Wi-Fi en0 vừa rớt ngay sau khi cấp DHCP cho '\(secondarySsid)'.")
                    }

                    let probeBudgetMs = min(
                        max(settings.gatewayPostHandoverTimeoutMs, 50),
                        max(50, Int(handoverDeadline.timeIntervalSinceNow * 1000.0))
                    )
                    let probeDeadline = Date().addingTimeInterval(Double(probeBudgetMs) / 1000.0)
                    var reachableOnConn2 = false
                    while Date() < probeDeadline {
                        try Task.checkCancellation()
                        if isNetworkTransportAlive?() == false {
                            throw OntNetworkError("Mất liên kết Wi-Fi en0 khi đang kiểm tra ONT Gateway.")
                        }
                        if try await probeGatewayCheckingCertPin(failOnCertMismatch: true) {
                            reachableOnConn2 = true
                            break
                        }
                        let remProbeMs = max(10, Int(probeDeadline.timeIntervalSinceNow * 1000.0))
                        let sleepProbeMs = min(max(recoveryRetryDelayMs, 15), remProbeMs)
                        try await Task.sleep(nanoseconds: UInt64(sleepProbeMs) * 1_000_000)
                    }
                    if !reachableOnConn2 {
                        let isGatewayReachable = try await probeGatewayCheckingCertPin(failOnCertMismatch: true)
                        if !isGatewayReachable {
                            throw OntNetworkError(
                                "Đã chuyển sang Wi-Fi '\(secondarySsid)' nhưng không truy cập được ONT Gateway tại \(cleanBaseUrl)."
                            )
                        }
                    }
                    logDiag(
                        .info,
                        "STEP7",
                        "ONT Gateway phản hồi hợp lệ ở lượt #\(outerAttempt) (certSha256=\(String((session.lastPeerCertSha256 ?? "none").prefix(12)))...)."
                    )

                    let userExistBudgetMs = settings.gatewayPostHandoverTimeoutMs >= 45_000
                        ? 65_000
                        : max(settings.gatewayPostHandoverTimeoutMs, userExistPollIntervalMs * 2)
                    _ = try await authenticateSecondConnection(
                        newAdminPass: settings.adminNewPass,
                        previousSessionKey: conn1SessionKey,
                        userExistWaitBudgetMs: userExistBudgetMs
                    )

                    try await verifySecondConnectionBandAndMac(
                        localIpv4: candidateEn0Ip,
                        expectedBand: secondaryBand,
                        expectedOntBaseMac: resolvedOntBaseMac,
                        isSameSsidBothBands: wifi24Ssid.caseInsensitiveCompare(wifi5Ssid) == .orderedSame
                    )
                    handoverVerified = true
                } catch let stillPrimary as StillOnPrimaryBandError {
                    // Release session immediately so Band A never holds a "user exist" lock while KTV switches in iOS Settings
                    logDiag(.warn, "STEP7", "Lượt #\(outerAttempt) phát hiện vẫn ở băng tần cũ: \(stillPrimary.message)")
                    await logout()
                    if Date() < handoverDeadline {
                        emit(
                            7,
                            step7Title,
                            .running,
                            "iPhone vẫn đang ở Wi-Fi cũ — hãy chọn '\(secondarySsid)' trong Cài đặt Wi-Fi iOS rồi quay lại ứng dụng..."
                        )
                        let remMs = max(15, Int(handoverDeadline.timeIntervalSinceNow * 1000.0))
                        let retrySleepMs = min(min(max(recoveryRetryDelayMs, 25), 1500), remMs)
                        try await Task.sleep(nanoseconds: UInt64(retrySleepMs) * 1_000_000)
                        continue
                    } else {
                        throw OntDriverException(stillPrimary.message)
                    }
                } catch let missingInfo as MissingDeviceInfoError {
                    missingDeviceInfoOuterCount += 1
                    logDiag(.warn, "STEP7", "Không đọc được deviceinfo2.json ở lượt #\(outerAttempt) (\(missingDeviceInfoOuterCount)/2): \(missingInfo.message)")
                    await logout()
                    if missingDeviceInfoOuterCount >= 2 || Date() >= handoverDeadline {
                        throw OntDriverException(
                            "Địa chỉ MAC của ONT ở Lần kết nối 2 (không xác định) không khớp hoặc không thể đối chiếu với ONT ban đầu (\(resolvedOntBaseMac ?? ""))."
                        )
                    }
                    let remMs = max(15, Int(handoverDeadline.timeIntervalSinceNow * 1000.0))
                    let backoffMs = min(
                        Self.computeReconnectBackoffMs(
                            attempt: outerAttempt,
                            initialBackoffMs: handoverRetryInitialBackoffMs,
                            maxBackoffMs: handoverRetryMaxBackoffMs
                        ),
                        remMs
                    )
                    try await Task.sleep(nanoseconds: UInt64(max(15, backoffMs)) * 1_000_000)
                    continue
                } catch let netErr as OntNetworkError {
                    if isTlsPinMismatch(netErr) {
                        throw OntDriverException("Chứng chỉ TLS của ONT ở Lần kết nối 2 không khớp với Lần 1.", underlyingError: netErr)
                    }
                    logDiag(.warn, "STEP7", "Lượt #\(outerAttempt) gặp lỗi mạng tạm thời: \(netErr.localizedDescription)")
                    await logout()
                    let remMs = Int(handoverDeadline.timeIntervalSinceNow * 1000.0)
                    let minInitial = max(15, handoverRetryInitialBackoffMs)
                    if remMs > minInitial {
                        let backoffMs = min(
                            Self.computeReconnectBackoffMs(
                                attempt: outerAttempt,
                                initialBackoffMs: handoverRetryInitialBackoffMs,
                                maxBackoffMs: handoverRetryMaxBackoffMs
                            ),
                            remMs - 10
                        )
                        emit(
                            7,
                            step7Title,
                            .running,
                            "Kết nối '\(secondarySsid)' (\(secondaryBandLabel)) chưa ổn định, tự động thử lại sau \((backoffMs + 999) / 1000)s (lượt \(outerAttempt + 1))..."
                        )
                        logDiag(.info, "STEP7", "Nghỉ giãn cách \(backoffMs)ms trước lượt Bước 7 #\(outerAttempt + 1).")
                        try await Task.sleep(nanoseconds: UInt64(max(15, backoffMs)) * 1_000_000)
                        continue
                    } else {
                        throw netErr
                    }
                }
            }
            emit(
                7,
                step7Title,
                .success,
                "Đã đứng trên Wi-Fi '\(secondarySsid)' (\(secondaryBandLabel)) & xác thực ONT thành công"
            )

            // Step 8: Configure Remaining Primary Wi-Fi Band (`Band A`) with Read-Only NVRAM Verification on Drop
            let step8Title = "Bước 8/\(totalSteps): Đổi tên & cấu hình Wi-Fi còn lại \(primaryBandLabel)"
            emit(
                8,
                step8Title,
                .running,
                "SSID mới: \(primarySsid) (cấu hình từ kết nối phụ \(secondaryBandLabel))"
            )
            do {
                _ = try await configureWifiBand(
                    unit: primaryUnit,
                    ssid: primarySsid,
                    password: primaryPass,
                    secMode: secMode.rawValue,
                    isConnectedBandFinalStep: false
                )
            } catch let netErr as OntNetworkError {
                emit(
                    8,
                    step8Title,
                    .running,
                    "Đang đồng bộ lại kết nối '\(secondarySsid)' để kiểm tra NVRAM cho '\(primarySsid)'..."
                )
                let readyAfterDrop = try await ensureGatewayReadyOnSecondaryBand(
                    settings: settings,
                    timeoutBudgetMs: settings.postSecondWifiSettleTimeoutMs
                )
                guard readyAfterDrop else {
                    throw OntNetworkError(
                        "Mất kết nối tới ONT trên Wi-Fi '\(secondarySsid)' khi xác nhận cấu hình Wi-Fi \(primaryBandLabel) ('\(primarySsid)').",
                        cause: netErr
                    )
                }
                let savedNvramSsid = await readConfiguredBandSsidFromNvram(unit: primaryUnit)
                if savedNvramSsid == primarySsid {
                    await commitRadioReloadBestEffort()
                } else {
                    _ = try await configureWifiBand(
                        unit: primaryUnit,
                        ssid: primarySsid,
                        password: primaryPass,
                        secMode: secMode.rawValue,
                        isConnectedBandFinalStep: false
                    )
                }
            }
            if settlingDelayMs > 0 {
                try await Task.sleep(nanoseconds: UInt64(settlingDelayMs) * 1_000_000)
            }
            emit(
                8,
                step8Title,
                .success,
                "Đã hoàn tất cấu hình cả 2 băng tần Wi-Fi ('\(wifi24Ssid)' / '\(wifi5Ssid)')"
            )

            // Step 9 (if Firmware Update enabled) OR Clean Logout & Finish (8/8)
            if doFwUpdate, let firmwareURL = fwURL {
                let step9Title = "Bước 9/9: Tải lên & Cập nhật Firmware ONT (Bước cuối)"
                let fwDisplayName = settings.firmwareFileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? firmwareURL.lastPathComponent
                    : settings.firmwareFileName
                emit(
                    9,
                    step9Title,
                    .running,
                    "Đang đồng bộ kết nối '\(secondarySsid)' trước khi tải lên Firmware..."
                )
                let readyForFirmware = try await ensureGatewayReadyOnSecondaryBand(
                    settings: settings,
                    timeoutBudgetMs: settings.postSecondWifiSettleTimeoutMs
                )
                guard readyForFirmware else {
                    throw OntNetworkError(
                        "Đã cấu hình xong cả 2 Wi-Fi ('\(wifi24Ssid)' / '\(wifi5Ssid)'), nhưng mất kết nối tới ONT trước khi nạp Firmware (Bước 9). Hãy kiểm tra kết nối Wi-Fi '\(secondarySsid)' rồi thử lại."
                    )
                }

                let attrs = try? FileManager.default.attributesOfItem(atPath: firmwareURL.path)
                let fileKb = ((attrs?[.size] as? NSNumber)?.int64Value ?? 0) / 1024
                emit(9, step9Title, .running, "Đang tải lên \(fwDisplayName) (\(fileKb) KB)...")

                do {
                    _ = try await updateFirmware(
                        firmwareURL: firmwareURL,
                        uploadTimeoutMs: settings.firmwareUploadTimeoutMs,
                        onProgressPercent: { pct in
                            emit(9, step9Title, .running, "Đang tải lên \(fwDisplayName) (\(pct)% — \(fileKb) KB)...")
                        }
                    )
                } catch let err as OntDriverException {
                    let prefix = "Đã cấu hình xong cả 2 Wi-Fi ('\(wifi24Ssid)' / '\(wifi5Ssid)' — Bước 1-8), nhưng lỗi Cập nhật Firmware (Bước 9): \(err.localizedDescription)"
                    if err is OntNetworkError {
                        throw OntNetworkError(prefix, cause: err)
                    } else if err is OntValidationError {
                        throw OntValidationError(prefix)
                    } else {
                        throw OntDriverException(prefix, underlyingError: err)
                    }
                }

                emit(
                    9,
                    step9Title,
                    .success,
                    "Đã nạp Firmware (\(fwDisplayName)) thành công! ONT đang tự ghi flash & khởi động lại (~2 phút 20 giây)."
                )
                return MobileSetupResult(
                    success: true,
                    soMay: record.slid,
                    slid: record.slid,
                    accountPppoe: record.accountPppoe,
                    newSsid24g: wifi24Ssid,
                    newPass24g: wifi24Pass,
                    newSsid5g: wifi5Ssid,
                    newPass5g: wifi5Pass,
                    updatedAdminPass: settings.adminNewPass,
                    message: "Hoàn tất 9/9 bước! Đã đổi cả 2 Wi-Fi (\(wifi24Ssid) / \(wifi5Ssid)) & nạp Firmware (\(fwDisplayName)). ONT đang tự khởi động lại (~2 phút 20 giây).",
                    beaconVerified: true,
                    firmwareUpdated: true,
                    firmwareFileName: fwDisplayName,
                    configuredSecondaryBandLabel: secondaryBandLabel,
                    configuredSecondarySsid: secondarySsid,
                    configuredSecondaryPass: secondaryPass,
                    wifiSecurityMode: secMode.rawValue,
                    resolvedConnectedBand: effectiveConnectedBand
                )
            }

            await Task.detached {
                await self.logout()
            }.value

            return MobileSetupResult(
                success: true,
                soMay: record.slid,
                slid: record.slid,
                accountPppoe: record.accountPppoe,
                newSsid24g: wifi24Ssid,
                newPass24g: wifi24Pass,
                newSsid5g: wifi5Ssid,
                newPass5g: wifi5Pass,
                updatedAdminPass: settings.adminNewPass,
                message: "Cấu hình hoàn tất 8/8 bước! Đã đổi thành công cả 2 Wi-Fi: \(wifi24Ssid) / \(wifi5Ssid)",
                beaconVerified: true,
                firmwareUpdated: false,
                configuredSecondaryBandLabel: secondaryBandLabel,
                configuredSecondarySsid: secondarySsid,
                configuredSecondaryPass: secondaryPass,
                wifiSecurityMode: secMode.rawValue,
                resolvedConnectedBand: effectiveConnectedBand
            )
        } catch {
            await Task.detached {
                await self.logout()
            }.value
            throw error
        }
    }
}
