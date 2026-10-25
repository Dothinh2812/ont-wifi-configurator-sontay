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
        totalSteps: Int = 7,
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
        beaconVerified: Bool = false
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

    private let cleanBaseUrl: String
    private let timeoutMs: Int
    private let settlingDelayMs: Int
    private let probePauseMutex: AsyncMutex?
    private let onAdminPasswordChanged: (@Sendable (String) async -> Void)?
    private let isAppInBackground: @Sendable () -> Bool

    private let session: UnsafeSslSession
    private let logoutSession: UnsafeSslSession

    private let stateLock = NSLock()
    private var sessionKey: String = ""
    private var currentUser: String = "admin"

    public init(
        baseUrl: String = "https://192.168.1.1",
        timeoutMs: Int = 5000,
        settlingDelayMs: Int = 200,
        probePauseMutex: AsyncMutex? = nil,
        protocolClasses: [AnyClass] = [],
        onAdminPasswordChanged: (@Sendable (String) async -> Void)? = nil,
        isAppInBackground: @escaping @Sendable () -> Bool = { false }
    ) {
        let trimmed = baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        self.cleanBaseUrl = trimmed
        self.timeoutMs = timeoutMs
        self.settlingDelayMs = settlingDelayMs
        self.probePauseMutex = probePauseMutex
        self.onAdminPasswordChanged = onAdminPasswordChanged
        self.isAppInBackground = isAppInBackground

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

    /// Step 1: Probe root URL and verify both HTTP 200 AND ONT HTML signature ("iGate", "GW040", "VNPT", or "login.js").
    public func probeGateway() async -> Bool {
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
            return false
        }
    }

    private func postForm(
        path: String,
        rawPayload: String,
        useShortLogoutTimeout: Bool = false
    ) async throws -> HttpResponsePayload {
        guard let url = URL(string: "\(cleanBaseUrl)\(path)") else {
            throw OntNetworkError("URL không hợp lệ: \(cleanBaseUrl)\(path)")
        }
        let bodyData = Data(rawPayload.utf8)
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = bodyData
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("\(cleanBaseUrl)/", forHTTPHeaderField: "Referer")
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
            currentSessionKey = "factoryID"
        } else if firstLog == "0" {
            currentSessionKey = stringValue(data, "sessionKey")
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
                let newKey = stringValue(relogData, "sessionKey", default: currentSessionKey)
                currentSessionKey = newKey
                return newKey
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
        isConnectedBandFinalStep: Bool = false
    ) async throws -> WifiApplyOutcome {
        try OntInputValidator.sanitizeInputMetacharacters(ssid)
        try OntInputValidator.sanitizeInputMetacharacters(password)

        let encSsid = OntInputValidator.urlEncodeFormValue(ssid)
        let encPass = OntInputValidator.urlEncodeFormValue(password)

        let cfgPayload =
            "page=ssid.asp&action=Apply&wl_unit=\(unit)&wl_radio=1&wl_bssid=0&wl_bss_enabled=1" +
            "&wl_ssid=\(encSsid)&wl_bss_maxassoc=32&wl_closed=0&sec_mode=wpa2_wpa3_psk" +
            "&wl_wpa_psk=\(encPass)&wl_mode=null&wl_auth=0&wl_auth_mode=radius&wl_osenen=0" +
            "&wl_akm=&wl_akm_wpa=disabled&wl_akm_psk=enabled&wl_akm_wpa2=disabled&wl_akm_psk2=enabled" +
            "&wl_akm_brcm_psk=disabled&wl_preauth=disabled&wl_wep=disabled&wl_crypto=aes" +
            "&wl_radius_ipaddr=0.0.0.0&wl_radius_port=1812&wl_radius_key=1234&sessionKey=\(currentSessionKey)"

        var cfgSavedToNvram = false
        var droppedDuringRadioReload = false

        do {
            let cfgResp = try await postForm(path: "/cgi-bin/luci/wirelesscfg.json", rawPayload: cfgPayload)
            _ = try parseJsonOrThrow(
                stepContext: "Cài đặt Wi-Fi unit=\(unit) (wirelesscfg.json)",
                statusCode: cfgResp.statusCode,
                body: cfgResp.body
            )
            cfgSavedToNvram = true
        } catch {
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
                    rawPayload: "undefined&sessionKey=\(currentSessionKey)"
                )
                let resJson = try parseJsonOrThrow(
                    stepContext: "Khởi động lại driver Wi-Fi (wlrestart.json)",
                    statusCode: resResp.statusCode,
                    body: resResp.body
                )

                if !isConnectedBandFinalStep && stringValue(resJson, "_wlrestart_", default: "0") != "0" {
                    for _ in 1...10 {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
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
                if (isConnectedBandFinalStep || cfgSavedToNvram) && !isAppInBackground() {
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

    private func ensureGatewayReadyBeforeStep7(
        settings: GlobalSettings,
        recordKey: String,
        maxAttempts: Int = 8,
        retryDelayMs: Int = 1200
    ) async -> Bool {
        for attempt in 1...maxAttempts {
            if await probeGateway() {
                do {
                    let resp = try await postForm(
                        path: "/cgi-bin/luci/wlrestart.json",
                        rawPayload: "undefined&sessionKey=\(currentSessionKey)"
                    )
                    if resp.statusCode != 200 || resp.body.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
                        _ = try await loginAndChangePassword(
                            defaultPass: settings.adminDefaultPass,
                            newPass: settings.adminNewPass,
                            recordKey: recordKey,
                            lastKnownAdminPass: settings.adminNewPass
                        )
                    } else if let data = resp.body.data(using: .utf8),
                              let wlJson = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                              stringValue(wlJson, "_wlrestart_", default: "0") != "0" {
                        try? await Task.sleep(nanoseconds: 800_000_000)
                        continue
                    }
                    return true
                } catch {
                    if (try? await loginAndChangePassword(
                        defaultPass: settings.adminDefaultPass,
                        newPass: settings.adminNewPass,
                        recordKey: recordKey,
                        lastKnownAdminPass: settings.adminNewPass
                    )) != nil {
                        return true
                    }
                }
            }

            if attempt < maxAttempts && retryDelayMs > 0 {
                try? await Task.sleep(nanoseconds: UInt64(retryDelayMs) * 1_000_000)
            }
        }
        return false
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
                 NSURLErrorCannotParseResponse,
                 NSURLErrorSecureConnectionFailed:
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
        _ = try? await postForm(
            path: "/cgi-bin/luci/logout.cmd",
            rawPayload: payload,
            useShortLogoutTimeout: true
        )
        currentSessionKey = ""
    }

    /// Execute the 7-step wireless ONT configuration sequence.
    public func executeMobileSetup(
        record: SubscriberRecord,
        settings: GlobalSettings = GlobalSettings(),
        connectedBand: WifiBand = .band24GHz,
        onProgress: (@Sendable (StepProgress) -> Void)? = nil
    ) async throws -> MobileSetupResult {
        let preRunCheck = OntInputValidator.validateBeforeRun(record, settings: settings)
        guard preRunCheck.isValid else {
            throw OntValidationError(preRunCheck.errors.joined(separator: "; "))
        }

        let wifi24Ssid = record.effectiveWifi24Ssid
        let wifi24Pass = record.effectiveWifi24Pass
        let wifi5Ssid = record.effectiveWifi5Ssid
        let wifi5Pass = record.effectiveWifi5Pass
        let recordKey = Self.recordCacheKey(rowIndex: record.rowIndex, soMay: record.soMay)

        func emit(_ step: Int, _ title: String, _ status: StepStatus, _ detail: String) {
            onProgress?(StepProgress(stepNumber: step, totalSteps: 7, title: title, status: status, detail: detail))
        }

        await probePauseMutex?.lock()

        do {
            // Step 1: Probe Gateway & Verify ONT HTML Signature
            let step1Title = "Bước 1/7: Kiểm tra kết nối & nhận diện ONT Gateway"
            emit(1, step1Title, .running, "Đang kiểm tra \(cleanBaseUrl)...")
            guard await probeGateway() else {
                throw OntNetworkError(
                    "Không thể kết nối tới ONT GW040-NSv2 tại \(cleanBaseUrl) (không nhận diện được chữ ký iGate/GW040/VNPT)."
                )
            }
            emit(1, step1Title, .success, "Đã xác nhận kết nối tới ONT GW040-NSv2")

            // Step 2: Smart Login & Change Admin Password
            let step2Title = "Bước 2/7: Đăng nhập & Đổi mật khẩu Admin"
            emit(2, step2Title, .running, "Đang xác thực tài khoản admin...")
            _ = try await loginAndChangePassword(
                defaultPass: settings.adminDefaultPass,
                newPass: settings.adminNewPass,
                recordKey: recordKey,
                lastKnownAdminPass: record.lastKnownAdminPass
            )
            await onAdminPasswordChanged?(settings.adminNewPass)
            emit(2, step2Title, .success, "Đã xác thực và bảo mật tài khoản Admin")

            // Step 3: Idempotent WAN PPPoE VLAN 11
            let step3Title = "Bước 3/7: Cấu hình WAN PPPoE (VLAN \(settings.vlanId))"
            emit(3, step3Title, .running, "Account: \(record.accountPppoe)")
            try await configureWanIdempotent(
                vlanId: settings.vlanId,
                pppUser: record.accountPppoe,
                pppPass: record.passPppoe,
                mtu: settings.mtu
            )
            emit(3, step3Title, .success, "Đã lưu WAN PPPoE (\(record.accountPppoe))")

            // Step 4: GPON SLID / Password
            let step4Title = "Bước 4/7: Cài đặt GPON Password (SLID / Mã MEN)"
            emit(4, step4Title, .running, "SLID: \(record.slid)")
            try await setGponPassword(record.slid)
            emit(4, step4Title, .success, "Đã nạp SLID \(record.slid)")

            // Step 5: LAN IPv4
            let step5Title = "Bước 5/7: Kiểm tra & Lưu cấu hình LAN IPv4"
            emit(5, step5Title, .running, "Gateway IP: \(settings.lanIp)")
            try await configureLan(settings)
            emit(5, step5Title, .success, "Đã lưu cấu hình LAN \(settings.lanIp)")

            // Dynamic Band Ordering: Non-connected band in Step 6, Connected band LAST in Step 7!
            let isConnected24g = (connectedBand == .band24GHz)
            let step6Unit = isConnected24g ? 1 : 0
            let step6BandLabel = isConnected24g ? "5GHz" : "2.4GHz"
            let step6Ssid = isConnected24g ? wifi5Ssid : wifi24Ssid
            let step6Pass = isConnected24g ? wifi5Pass : wifi24Pass

            let step7Unit = isConnected24g ? 0 : 1
            let step7BandLabel = isConnected24g ? "2.4GHz" : "5GHz"
            let step7Ssid = isConnected24g ? wifi24Ssid : wifi5Ssid
            let step7Pass = isConnected24g ? wifi24Pass : wifi5Pass

            // Step 6: Non-connected Wi-Fi band + wlrestart.json + settlingDelayMs (200ms)
            let step6Title = "Bước 6/7: Cấu hình Wi-Fi \(step6BandLabel) (Băng tần phụ)"
            emit(6, step6Title, .running, "SSID: \(step6Ssid)")
            _ = try await configureWifiBand(
                unit: step6Unit,
                ssid: step6Ssid,
                password: step6Pass,
                isConnectedBandFinalStep: false
            )
            if settlingDelayMs > 0 {
                try? await Task.sleep(nanoseconds: UInt64(settlingDelayMs) * 1_000_000)
            }
            emit(6, step6Title, .success, "Đã lưu & khởi động Wi-Fi \(step6BandLabel) (\(step6Ssid))")

            // Step 7: Connected Wi-Fi band LAST + Handle Radio Reload Socket Drop
            let step7Title = "Bước 7/7: Đổi tên & mật khẩu Wi-Fi \(step7BandLabel) (Bước cuối)"
            emit(7, step7Title, .running, "SSID mới: \(step7Ssid) (Sóng mặc định sẽ khởi động lại)")
            var step7Outcome = WifiApplyOutcome(cfgSavedToNvram: false, droppedDuringRadioReload: false)
            var lastStep7Error: Error?

            for step7Attempt in 1...3 {
                do {
                    step7Outcome = try await configureWifiBand(
                        unit: step7Unit,
                        ssid: step7Ssid,
                        password: step7Pass,
                        isConnectedBandFinalStep: true
                    )
                    if step7Outcome.cfgSavedToNvram {
                        break
                    }
                    if step7Outcome.droppedDuringRadioReload {
                        if step7Attempt < 3 {
                            let checkDelay = settlingDelayMs >= 500 ? 1200 : 20
                            let stillOnOldWifi = await ensureGatewayReadyBeforeStep7(
                                settings: settings,
                                recordKey: recordKey,
                                maxAttempts: 3,
                                retryDelayMs: checkDelay
                            )
                            if stillOnOldWifi {
                                emit(
                                    7,
                                    step7Title,
                                    .running,
                                    "Đang đồng bộ lại kết nối ONT để áp dụng Bước 7 (lần \(step7Attempt + 1)/3)..."
                                )
                                continue
                            }
                        }
                        break
                    }
                } catch {
                    lastStep7Error = error
                    if step7Attempt < 3 {
                        emit(
                            7,
                            step7Title,
                            .running,
                            "Đang đồng bộ lại kết nối ONT để áp dụng Bước 7 (lần \(step7Attempt + 1)/3)..."
                        )
                        let waitMs = settlingDelayMs >= 500 ? 1200 : 50
                        try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
                        let gatewayReady = await ensureGatewayReadyBeforeStep7(
                            settings: settings,
                            recordKey: recordKey,
                            maxAttempts: 6,
                            retryDelayMs: waitMs
                        )
                        if !gatewayReady && isPostFlushRadioDrop(error) {
                            step7Outcome = WifiApplyOutcome(cfgSavedToNvram: false, droppedDuringRadioReload: true)
                            break
                        }
                    } else if isPostFlushRadioDrop(error) {
                        step7Outcome = WifiApplyOutcome(cfgSavedToNvram: false, droppedDuringRadioReload: true)
                    } else {
                        throw error
                    }
                }
            }

            if !step7Outcome.cfgSavedToNvram && !step7Outcome.droppedDuringRadioReload {
                throw lastStep7Error ?? OntNetworkError(
                    "Không thể gửi cấu hình Bước 7 cho băng tần \(step7BandLabel) ('\(step7Ssid)')."
                )
            }

            if !step7Outcome.droppedDuringRadioReload {
                await Task.detached {
                    await self.logout()
                }.value
            } else {
                currentSessionKey = ""
            }

            emit(
                7,
                step7Title,
                .success,
                "Đã đổi Wi-Fi sang '\(wifi24Ssid)' / '\(wifi5Ssid)' thành công!"
            )

            probePauseMutex?.unlock()
            return MobileSetupResult(
                success: true,
                soMay: record.soMay,
                slid: record.slid,
                accountPppoe: record.accountPppoe,
                newSsid24g: wifi24Ssid,
                newPass24g: wifi24Pass,
                newSsid5g: wifi5Ssid,
                newPass5g: wifi5Pass,
                updatedAdminPass: settings.adminNewPass,
                message: "Cấu hình hoàn tất 7/7 bước! Wi-Fi mới: \(wifi24Ssid) / \(wifi5Ssid)",
                beaconVerified: step7Outcome.cfgSavedToNvram || step7Outcome.droppedDuringRadioReload
            )
        } catch {
            await Task.detached {
                await self.logout()
            }.value
            probePauseMutex?.unlock()
            throw error
        }
    }
}
