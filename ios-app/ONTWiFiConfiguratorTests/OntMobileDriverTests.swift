import Foundation
import XCTest
@testable import ONTWiFiConfigurator

struct RecordedLuciRequest: Sendable {
    let path: String
    let body: String
    let rawData: Data
}

final class MockLuciURLProtocol: URLProtocol {

    static var requestHandler: ((URLRequest, String) throws -> (HTTPURLResponse, Data))?
    static var rawRequestHandler: ((URLRequest, String, Data) throws -> (HTTPURLResponse, Data))?
    private static let lock = NSLock()
    private static var _recordedRequests: [RecordedLuciRequest] = []

    static var recordedRequests: [RecordedLuciRequest] {
        lock.lock()
        defer { lock.unlock() }
        return _recordedRequests
    }

    static func reset() {
        lock.lock()
        _recordedRequests.removeAll()
        requestHandler = nil
        rawRequestHandler = nil
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    private static func readRawBodyData(from request: URLRequest) -> Data {
        if let data = request.httpBody {
            return data
        }
        guard let stream = request.httpBodyStream else {
            return Data()
        }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 16 * 1024
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while stream.hasBytesAvailable {
            let bytesRead = stream.read(&buffer, maxLength: bufferSize)
            if bytesRead > 0 {
                data.append(buffer, count: bytesRead)
            } else {
                break
            }
        }
        return data
    }

    override func startLoading() {
        let rawData = Self.readRawBodyData(from: request)
        let bodyString = String(data: rawData, encoding: .utf8)
            ?? String(data: rawData, encoding: .isoLatin1)
            ?? ""
        let path = request.url?.path ?? ""

        Self.lock.lock()
        Self._recordedRequests.append(
            RecordedLuciRequest(path: path, body: bodyString, rawData: rawData)
        )
        let rawHandler = Self.rawRequestHandler
        let textHandler = Self.requestHandler
        Self.lock.unlock()

        guard rawHandler != nil || textHandler != nil else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        do {
            let (response, data): (HTTPURLResponse, Data)
            if let activeRaw = rawHandler {
                (response, data) = try activeRaw(request, bodyString, rawData)
            } else {
                (response, data) = try textHandler!(request, bodyString)
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            if !data.isEmpty {
                client?.urlProtocol(self, didLoad: data)
            }
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            let nsErr = error as NSError
            if !rawData.isEmpty && nsErr.userInfo["didFlushFullRequestBody"] == nil {
                var info = nsErr.userInfo
                info["didFlushFullRequestBody"] = true
                let annotated = NSError(domain: nsErr.domain, code: nsErr.code, userInfo: info)
                client?.urlProtocol(self, didFailWithError: annotated)
            } else {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }
    }

    override func stopLoading() {}
}

private final class TestLockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: T
    init(_ value: T) { self.storage = value }
    func withValue<R>(_ body: (inout T) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&storage)
    }
    var value: T {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }
}

final class OntMobileDriverTests: XCTestCase {

    private let baseUrl = "https://192.168.1.1"

    private let sampleRecord = SubscriberRecord(
        rowIndex: 2,
        stt: 1,
        soMay: "005501941",
        accountPppoe: "manh9653",
        passPppoe: "6l6s8z2a",
        maMen: "005501941",
        slid: "005501941",
        ssid24g: "Manh Ngoc",
        pass24g: "28121995",
        ssid5g: "Manh Ngoc 5G",
        pass5g: "28121995"
    )

    override func setUp() {
        super.setUp()
        MockLuciURLProtocol.reset()
        OntMobileDriver.clearCachedPasswordsForTest()
    }

    override func tearDown() {
        MockLuciURLProtocol.reset()
        OntMobileDriver.clearCachedPasswordsForTest()
        super.tearDown()
    }

    private func makeHTTPResponse(url: URL, statusCode: Int = 200, body: String) -> (HTTPURLResponse, Data) {
        let resp = HTTPURLResponse(
            url: url,
            statusCode: statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (resp, Data(body.utf8))
    }

    private func makeTestSettings(
        wifiSecurityMode: String = WifiSecurityMode.wpa2Wpa3Psk.rawValue,
        enableFirmwareUpdate: Bool = false,
        firmwareURL: URL? = nil
    ) -> GlobalSettings {
        let fwSize: Int64
        if let fwURL = firmwareURL,
           let attrs = try? FileManager.default.attributesOfItem(atPath: fwURL.path),
           let num = attrs[.size] as? NSNumber {
            fwSize = num.int64Value
        } else {
            fwSize = 0
        }
        return GlobalSettings(
            baseUrl: baseUrl,
            wifiSecurityMode: wifiSecurityMode,
            enableFirmwareUpdate: enableFirmwareUpdate,
            firmwareFilePath: firmwareURL?.path ?? "",
            firmwareFileName: firmwareURL?.lastPathComponent ?? "",
            firmwareFileSize: fwSize,
            wifiHandoverTimeoutMs: 150,
            gatewayPostHandoverTimeoutMs: 150,
            postSecondWifiSettleTimeoutMs: 150,
            firmwareUploadTimeoutMs: 5_000
        )
    }

    private func makeAssocDhcpJson(band: WifiBand, ip: String = "192.168.1.3") -> String {
        let ifc = (band == .band5GHz) ? "wl1" : "wl0"
        return """
        {"dhcpInfoList":["\(ip)|DHCP||aa:bb:cc:dd:ee:ff|iPhone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|\(ip)|1|\(ifc)|-45"]}
        """
    }

    private func createTempFirmwareURL(sizeBytes: Int = 72 * 1024) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("fw_ios_GW040_\(UUID().uuidString).signed")
        var bytes = [UInt8](repeating: 0, count: sizeBytes)
        for idx in 0..<sizeBytes {
            switch idx % 4 {
            case 0: bytes[idx] = 0x7F
            case 1: bytes[idx] = 0xFF
            case 2: bytes[idx] = 0x00
            default: bytes[idx] = 0x42
            }
        }
        try Data(bytes).write(to: url)
        return url
    }

    private func makeTestDriver(
        activeBandBox: TestLockedBox<WifiBand>,
        probeMutex: AsyncMutex? = nil,
        isAppInBackground: @escaping @Sendable () -> Bool = { false }
    ) -> OntMobileDriver {
        return OntMobileDriver(
            baseUrl: baseUrl,
            timeoutMs: 1500,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 15,
            wlRestartPollIntervalMs: 10,
            maxWlRestartPolls: 3,
            userExistPollIntervalMs: 20,
            probePauseMutex: probeMutex,
            protocolClasses: [MockLuciURLProtocol.self],
            isAppInBackground: isAppInBackground,
            onSwitchToSecondaryWifi: { _, _, targetBand, _, _, _, _ in
                activeBandBox.withValue { $0 = targetBand }
                return "192.168.1.3"
            }
        )
    }

    // 1. 8-Step Dual-Wi-Fi Setup (2.4GHz -> 5GHz) + serviceId=1 Edit WAN + Clean Logout on Both Connections
    func testExecuteMobileSetupOn24GHzConfigures5GHzInStep6SwitchesAndConfigures24GHzInStep8WithServiceId1AndCallsLogout() async throws {
        let probeMutex = AsyncMutex()
        let activeBand = TestLockedBox<WifiBand>(.band24GHz)
        let adminChangedBox = TestLockedBox<Bool>(false)

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path

            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html><title>VNPT iGate GW040-NSv2</title></html>")
            case "/cgi-bin/luci/login.cmd":
                if body.contains("pass=admin") && !adminChangedBox.value {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"1","numberLogin":"0","timeoutLogin":"0"}
                    """)
                } else if body.contains("Passwd2") {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_999","numberLogin":"0","timeoutLogin":"0"}
                    """)
                } else {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}
                    """)
                }
            case "/cgi-bin/luci/accesscontrolcfg.json":
                adminChangedBox.withValue { $0 = true }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            case "/cgi-bin/luci/deviceinfo2.json":
                return self.makeHTTPResponse(url: url, body: """
                {"baseMacAddress":"C4:2C:7B:60:BC:D8\\r\\n"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[{"idx":2,"vlanId":"11","ifcStr":"veip0.11","protocal":"PPPoE"}]}
                """)
            case "/cgi-bin/luci/editwan.json",
                 "/cgi-bin/luci/sendwan.cmd",
                 "/cgi-bin/luci/gponpasswd.json",
                 "/cgi-bin/luci/savelanv4cfg.cmd",
                 "/cgi-bin/luci/wirelesscfg.json",
                 "/cgi-bin/luci/logout.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            case "/cgi-bin/luci/wlrestart.json":
                return self.makeHTTPResponse(url: url, body: """
                {"_wlrestart_":"0"}
                """)
            default:
                return self.makeHTTPResponse(url: url, statusCode: 404, body: "Not Found")
            }
        }

        let driver = makeTestDriver(activeBandBox: activeBand, probeMutex: probeMutex)
        let result = try await driver.executeMobileSetup(
            record: sampleRecord,
            settings: makeTestSettings(),
            connectedBand: .band24GHz
        )

        XCTAssertTrue(result.success)
        XCTAssertFalse(result.firmwareUpdated)
        XCTAssertFalse(probeMutex.isLocked)

        let recorded = MockLuciURLProtocol.recordedRequests
        let editWanBody = recorded.first(where: { $0.path == "/cgi-bin/luci/editwan.json" })?.body ?? ""
        let sendWanBody = recorded.first(where: { $0.path == "/cgi-bin/luci/sendwan.cmd" })?.body ?? ""
        XCTAssertTrue(editWanBody.contains("serviceId=1"))
        XCTAssertTrue(sendWanBody.contains("serviceId=1"))

        let wirelessCalls = recorded.filter { $0.path == "/cgi-bin/luci/wirelesscfg.json" && $0.body.contains("action=Apply") }
        XCTAssertEqual(wirelessCalls.count, 2)
        XCTAssertTrue(wirelessCalls[0].body.contains("wl_unit=1"), "Connection 1 Step 6 configures secondary 5GHz first")
        XCTAssertTrue(wirelessCalls[1].body.contains("wl_unit=0"), "Connection 2 Step 8 configures remaining 2.4GHz")
        XCTAssertEqual(recorded.filter { $0.path == "/cgi-bin/luci/logout.cmd" }.count, 2)
        XCTAssertEqual(recorded.last?.path, "/cgi-bin/luci/logout.cmd")
    }

    // 2. 8-Step Dual-Wi-Fi Setup Starting on 5GHz (5GHz -> 2.4GHz)
    func testExecuteMobileSetupOn5GHzReversesBandOrderSo24GHzIsStep6And5GHzIsStep8() async throws {
        let activeBand = TestLockedBox<WifiBand>(.band5GHz)

        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>iGate GW040</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_5g"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = makeTestDriver(activeBandBox: activeBand)
        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: rec,
            settings: makeTestSettings(),
            connectedBand: .band5GHz
        )

        XCTAssertTrue(result.success)
        let wirelessCalls = MockLuciURLProtocol.recordedRequests.filter {
            $0.path == "/cgi-bin/luci/wirelesscfg.json" && $0.body.contains("action=Apply")
        }
        XCTAssertEqual(wirelessCalls.count, 2)
        XCTAssertTrue(wirelessCalls[0].body.contains("wl_unit=0"))
        XCTAssertTrue(wirelessCalls[1].body.contains("wl_unit=1"))
    }

    // 3. Step 8 Read-Only NVRAM Verification on Transient Socket Drop
    func testStep8RecoversFromTransientDropUsingReadOnlyNvramVerification() async throws {
        let activeBand = TestLockedBox<WifiBand>(.band24GHz)
        let unit0ApplyCount = TestLockedBox<Int>(0)
        let readOnlyQueries = TestLockedBox<[String]>([])

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_nvram"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wirelesscfg.json":
                if body.contains("action=Apply") {
                    if body.contains("wl_unit=0") {
                        let count = unit0ApplyCount.withValue { val -> Int in
                            val += 1
                            return val
                        }
                        if count == 1 {
                            throw URLError(.networkConnectionLost)
                        }
                    }
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK"}
                    """)
                } else {
                    readOnlyQueries.withValue { $0.append(body) }
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","nvramWlSsid":"Manh Ngoc"}
                    """)
                }
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = makeTestDriver(activeBandBox: activeBand)
        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: rec,
            settings: makeTestSettings(),
            connectedBand: .band24GHz
        )

        XCTAssertTrue(result.success)
        XCTAssertEqual(unit0ApplyCount.value, 1, "Must NOT re-apply when read-only NVRAM already matches primarySsid")
        XCTAssertEqual(readOnlyQueries.value.count, 1)
        XCTAssertTrue(readOnlyQueries.value[0].contains("page=ssid.asp&wl_unit=0&wl_bssid=0"))
    }

    // 4. Mid-Flight Error Cleanup & defer Mutex Unlock
    func testMidFlightErrorAtStep4StillInvokesLogoutCmdInCleanup() async throws {
        let logoutCalled = TestLockedBox<Bool>(false)
        let probeMutex = AsyncMutex()
        let activeBand = TestLockedBox<WifiBand>(.band24GHz)

        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_err"}
                """)
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/gponpasswd.json":
                return self.makeHTTPResponse(url: url, statusCode: 500, body: "Internal Error")
            case "/cgi-bin/luci/logout.cmd":
                logoutCalled.withValue { $0 = true }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            }
        }

        let driver = makeTestDriver(activeBandBox: activeBand, probeMutex: probeMutex)
        do {
            _ = try await driver.executeMobileSetup(
                record: sampleRecord,
                settings: makeTestSettings()
            )
            XCTFail("Expected error at Step 4")
        } catch {
            // Expected
        }

        XCTAssertTrue(logoutCalled.value, "Mandatory logout.cmd must execute on mid-flight failure")
        XCTAssertFalse(probeMutex.isLocked, "defer { probePauseMutex?.unlock() } must unlock mutex on error")
    }

    // 5. Smart Retry Per-Record Cache & Zero "admin" Fallback on Connection 2
    func testSmartRetryScopesPasswordCachePerRecordAndStopsAtNumberLogin2() async throws {
        let loginBodies = TestLockedBox<[String]>([])
        let activeBand = TestLockedBox<WifiBand>(.band24GHz)

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>GW040</html>")
            case "/cgi-bin/luci/login.cmd":
                loginBodies.withValue { $0.append(body) }
                if body.contains("pass=Passwd2%40") || body.contains("pass=Passwd2@") {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_retry","numberLogin":"0"}
                    """)
                } else {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}
                    """)
                }
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = makeTestDriver(activeBandBox: activeBand)
        var retryRecord = sampleRecord
        retryRecord.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: retryRecord,
            settings: makeTestSettings()
        )

        XCTAssertTrue(result.success)
        XCTAssertEqual(loginBodies.value.count, 2, "Connection 1 + Connection 2 both use Passwd2@")
        XCTAssertTrue(loginBodies.value.allSatisfy { $0.contains("Passwd2") && !$0.contains("pass=admin") })

        // Fresh record (different subscriber / ONT) must NOT inherit cached password from previous subscriber
        var freshDifferentRecord = sampleRecord
        freshDifferentRecord.rowIndex = 99
        freshDifferentRecord.soMay = "009999999"
        freshDifferentRecord.slid = "009999999"
        freshDifferentRecord.lastKnownAdminPass = ""

        do {
            _ = try await driver.executeMobileSetup(
                record: freshDifferentRecord,
                settings: makeTestSettings()
            )
            XCTFail("Expected OntAccountLockoutWarning when numberLogin >= 2")
        } catch let error as OntAccountLockoutWarning {
            XCTAssertTrue(error.localizedDescription.contains("2"))
        }
    }

    // 6. All 6 Security Modes & Deterministic conflict_mesh Rejection
    func testConfigureWifiBandAllSixSecurityModesAndConflictMeshNoRetry() async throws {
        let wirelessBodies = TestLockedBox<[String]>([])
        let activeBand = TestLockedBox<WifiBand>(.band24GHz)

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            if path == "/cgi-bin/luci/wirelesscfg.json" {
                wirelessBodies.withValue { $0.append(body) }
            }
            return self.makeHTTPResponse(url: url, body: """
            {"status":"OK","_wlrestart_":"0"}
            """)
        }

        let driver = makeTestDriver(activeBandBox: activeBand)

        for mode in WifiSecurityMode.allCases {
            wirelessBodies.withValue { $0.removeAll() }
            let pw = mode.requiresPassword ? "28121995" : ""
            _ = try await driver.configureWifiBand(
                unit: 0,
                ssid: "Manh Ngoc",
                password: pw,
                secMode: mode.rawValue,
                isConnectedBandFinalStep: false
            )
            XCTAssertEqual(wirelessBodies.value.count, 1)
            let body = wirelessBodies.value[0]
            XCTAssertTrue(body.contains("sec_mode=\(mode.rawValue)"))
            XCTAssertTrue(body.contains("wl_akm_psk=\(mode.akmPsk)"))
            XCTAssertTrue(body.contains("wl_akm_psk2=\(mode.akmPsk2)"))
            XCTAssertTrue(body.contains("wl_crypto=\(mode.crypto)"))
        }

        let step8Attempts = TestLockedBox<Int>(0)
        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_mesh"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wirelesscfg.json":
                if body.contains("action=Apply") && body.contains("wl_unit=0") {
                    step8Attempts.withValue { $0 += 1 }
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"conflict_mesh"}
                    """)
                }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"
        do {
            _ = try await driver.executeMobileSetup(
                record: rec,
                settings: makeTestSettings(wifiSecurityMode: "open"),
                connectedBand: .band24GHz
            )
            XCTFail("Expected OntDriverException on conflict_mesh")
        } catch let err as OntNetworkError {
            XCTFail("conflict_mesh must not be wrapped as OntNetworkError: \(err)")
        } catch let err as OntDriverException {
            XCTAssertTrue(err.localizedDescription.contains("Mesh"))
        }
        XCTAssertEqual(step8Attempts.value, 1, "conflict_mesh must not retry in Step 8")
    }

    // 7. Ground-Truth 2D wirelessInfoList & baseMacAddress Resolution
    func testResolveConnectedBandFromOntParses2DWirelessInfoListAndOverridesMisdetectedBand() async throws {
        let resolvedPairs = TestLockedBox<[(WifiBand, String?)]>([])
        let onSecondConn = TestLockedBox<Bool>(false)

        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_ios_band","numberLogin":"0","timeoutLogin":"0"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                if !onSecondConn.value {
                    return self.makeHTTPResponse(url: url, body: """
                    {"dhcpInfoList":["192.168.1.2|DHCP||aa:bb:cc:dd:ee:ff|iPhone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.2|1|wl1.1|-48"]}
                    """)
                } else {
                    return self.makeHTTPResponse(url: url, body: """
                    {"dhcpInfoList":["192.168.1.3|DHCP||aa:bb:cc:dd:ee:ff|iPhone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.3|1|wl0|-44"]}
                    """)
                }
            case "/cgi-bin/luci/deviceinfo2.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wirelessInfoList":[["wl0|1|100|10\\n|GW040-NSv2_2.4G_60BCD8\\n|c4:2c:7b:60:bc:d9\\n","GW040-NSv2_2.4G_60BCD8\\n|wl0"],["wl1|1|100|120\\n|GW040-NSv2_5G_60BCD8\\n|c4:2c:7b:60:bc:da\\n","GW040-NSv2_5G_60BCD8\\n|wl1"]],"baseMacAddress":"C4:2C:7B:60:BC:D8\\n"}
                """)
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 15,
            wlRestartPollIntervalMs: 10,
            maxWlRestartPolls: 3,
            userExistPollIntervalMs: 20,
            protocolClasses: [MockLuciURLProtocol.self],
            onSwitchToSecondaryWifi: { _, _, targetBand, _, _, _, _ in
                XCTAssertEqual(targetBand, .band24GHz)
                onSecondConn.withValue { $0 = true }
                return "192.168.1.3"
            }
        )

        var record = sampleRecord
        record.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: record,
            settings: makeTestSettings(),
            connectedBand: .band24GHz,
            localWifiIpv4: "192.168.1.2",
            onBandResolved: { band, mac in
                resolvedPairs.withValue { $0.append((band, mac)) }
            }
        )

        XCTAssertTrue(result.success)
        XCTAssertEqual(result.resolvedConnectedBand, .band5GHz)
        XCTAssertEqual(resolvedPairs.value.count, 1)
        XCTAssertEqual(resolvedPairs.value[0].0, .band5GHz)
        XCTAssertEqual(resolvedPairs.value[0].1, "C4:2C:7B:60:BC:D8")

        let wirelessCalls = MockLuciURLProtocol.recordedRequests.filter {
            $0.path == "/cgi-bin/luci/wirelesscfg.json" && $0.body.contains("action=Apply")
        }
        XCTAssertEqual(wirelessCalls.count, 2)
        XCTAssertTrue(wirelessCalls[0].body.contains("wl_unit=0"), "Step 6 configures 2.4GHz first when iPhone is on 5GHz")
        XCTAssertTrue(wirelessCalls[1].body.contains("wl_unit=1"), "Step 8 configures 5GHz on Connection 2")
    }

    // 8. Full 9-Step Dual-Wi-Fi + Streamed Binary Multipart Firmware Upload + User-Exist Recovery + Zero "admin" Fallback
    func testNineStepDualWifiAndStreamedBinaryFirmwareUploadFlow() async throws {
        let fwURL = try createTempFirmwareURL(sizeBytes: 72 * 1024)
        defer { try? FileManager.default.removeItem(at: fwURL) }

        let activeBand = TestLockedBox<WifiBand>(.band24GHz)
        let onSecondConn = TestLockedBox<Bool>(false)
        let conn2FirstUserExist = TestLockedBox<Bool>(true)
        let preFwFailAuthenOnce = TestLockedBox<Bool>(true)
        let loginPasswords = TestLockedBox<[String]>([])
        let progressUpdates = TestLockedBox<[StepProgress]>([])

        MockLuciURLProtocol.rawRequestHandler = { req, body, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                let pw = body.components(separatedBy: "pass=").last?.components(separatedBy: "&").first ?? ""
                loginPasswords.withValue { $0.append(pw) }
                if !onSecondConn.value {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_conn1","numberLogin":"0","timeoutLogin":"0"}
                    """)
                }
                let shouldRejectExist = conn2FirstUserExist.withValue { flag -> Bool in
                    if flag { flag = false; return true }
                    return false
                }
                if shouldRejectExist {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"user exist"}
                    """)
                }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_conn2","numberLogin":"0","timeoutLogin":"0"}
                """)
            case "/cgi-bin/luci/deviceinfo2.json":
                return self.makeHTTPResponse(url: url, body: """
                {"baseMacAddress":"A4:F4:C2:60:BC:D8\\n"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wlrestart.json":
                if onSecondConn.value {
                    let expire = preFwFailAuthenOnce.withValue { flag -> Bool in
                        if flag { flag = false; return true }
                        return false
                    }
                    if expire {
                        return self.makeHTTPResponse(url: url, body: """
                        {"status":"fail authen"}
                        """)
                    }
                }
                return self.makeHTTPResponse(url: url, body: """
                {"_wlrestart_":"0"}
                """)
            case "/cgi-bin/luci/updatefwsessioncheck.json":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","sessionKey":"sess_fw_chk"}
                """)
            case "/cgi-bin/luci/imagesize.json":
                XCTAssertTrue(body.contains("sessionKey=sess_fw_chk"))
                return self.makeHTTPResponse(url: url, body: """
                {"ramFree":"251696","sessionKey":"sess_fw_img"}
                """)
            case "/cgi-bin/luci/uploadsoftware.conf":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"valid","sessionKey":"sess_fw_done"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            }
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 15,
            wlRestartPollIntervalMs: 10,
            maxWlRestartPolls: 3,
            userExistPollIntervalMs: 20,
            protocolClasses: [MockLuciURLProtocol.self],
            onSwitchToSecondaryWifi: { ssid, _, targetBand, _, expectedMac, _, _ in
                XCTAssertEqual(ssid, "Manh Ngoc 5G")
                XCTAssertEqual(targetBand, .band5GHz)
                XCTAssertEqual(expectedMac, "A4:F4:C2:60:BC:D8")
                onSecondConn.withValue { $0 = true }
                activeBand.withValue { $0 = targetBand }
                return "192.168.1.3"
            }
        )

        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: rec,
            settings: makeTestSettings(enableFirmwareUpdate: true, firmwareURL: fwURL),
            connectedBand: .band24GHz,
            localWifiIpv4: "192.168.1.2",
            onProgress: { step in
                progressUpdates.withValue { $0.append(step) }
            }
        )

        XCTAssertTrue(result.success)
        XCTAssertTrue(result.firmwareUpdated)
        XCTAssertTrue(loginPasswords.value.allSatisfy { !$0.contains("admin") }, "Must NEVER fall back to default admin pass")
        XCTAssertTrue(progressUpdates.value.allSatisfy { $0.totalSteps == 9 })

        let recorded = MockLuciURLProtocol.recordedRequests
        let paths = recorded.map { $0.path }
        guard let upIdx = paths.firstIndex(of: "/cgi-bin/luci/uploadsoftware.conf") else {
            XCTFail("Missing uploadsoftware.conf request")
            return
        }
        let applyIndices = recorded.enumerated().compactMap { idx, item in
            (item.path == "/cgi-bin/luci/wirelesscfg.json" && item.body.contains("action=Apply")) ? idx : nil
        }
        XCTAssertEqual(applyIndices.count, 2)
        XCTAssertTrue(applyIndices.allSatisfy { $0 < upIdx }, "Both Wi-Fi bands must be saved BEFORE Step 9 firmware upload")

        let uploadReq = recorded[upIdx]
        XCTAssertTrue(uploadReq.body.contains("name=\"file\"; filename=\"\(fwURL.lastPathComponent)\""))
        XCTAssertTrue(uploadReq.body.contains("sess_fw_img"))
        XCTAssertTrue(uploadReq.rawData.contains(0x7F))
        XCTAssertTrue(uploadReq.rawData.contains(0xFF))
        XCTAssertFalse(
            recorded.dropFirst(upIdx + 1).contains { $0.path == "/cgi-bin/luci/logout.cmd" },
            "logout.cmd must NEVER be sent after uploadsoftware.conf returns status=valid"
        )
    }

    // 9. Premature wl0 Rejection on Same-SSID & Neighbor ONT baseMacAddress Rejection
    func testStep7RejectsPrematureSecondConnectionWhileStillOnPrimaryBandWl0AndRejectsMismatchedBaseMac() async throws {
        let step8Called = TestLockedBox<Bool>(false)

        // Case A: Same SSID ("GiaDinh") on both bands, iPhone still associated with wl0 -> must NOT trigger Step 8
        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_same_ssid"}
                """)
            case "/cgi-bin/luci/deviceinfo2.json":
                return self.makeHTTPResponse(url: url, body: """
                {"baseMacAddress":"C4:2C:7B:11:22:33"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                // Still associated with wl0 (2.4GHz) even on Connection 2!
                return self.makeHTTPResponse(url: url, body: """
                {"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.2|1|wl0|-40"]}
                """)
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wirelesscfg.json":
                if body.contains("wl_unit=0") && body.contains("action=Apply") {
                    step8Called.withValue { $0 = true }
                }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 25,
            wlRestartPollIntervalMs: 10,
            maxWlRestartPolls: 2,
            userExistPollIntervalMs: 20,
            protocolClasses: [MockLuciURLProtocol.self],
            onSwitchToSecondaryWifi: { _, _, _, _, _, _, _ in "192.168.1.2" }
        )

        var sameSsidRec = sampleRecord
        sameSsidRec.ssid24g = "GiaDinh"
        sameSsidRec.ssid5g = "GiaDinh"
        sameSsidRec.lastKnownAdminPass = "Passwd2@"

        do {
            _ = try await driver.executeMobileSetup(
                record: sameSsidRec,
                settings: makeTestSettings(),
                connectedBand: .band24GHz,
                localWifiIpv4: "192.168.1.2"
            )
            XCTFail("Expected Step 7 rejection while wlAssociatedDevice is still wl0")
        } catch let err as OntDriverException {
            XCTAssertTrue(err.localizedDescription.contains("2.4GHz") || err.localizedDescription.contains("wl0"))
        }
        XCTAssertFalse(step8Called.value, "Step 8 must NEVER execute while still on primary band wl0")

        // Case B: Neighbor ONT with different baseMacAddress on Connection 2 -> immediate abort
        let onSecond = TestLockedBox<Bool>(false)
        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_mac_chk"}
                """)
            case "/cgi-bin/luci/deviceinfo2.json":
                let mac = onSecond.value ? "DE:AD:BE:EF:00:99" : "C4:2C:7B:11:22:33"
                return self.makeHTTPResponse(url: url, body: """
                {"baseMacAddress":"\(mac)"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: onSecond.value ? .band5GHz : .band24GHz))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let macDriver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 15,
            wlRestartPollIntervalMs: 10,
            maxWlRestartPolls: 2,
            userExistPollIntervalMs: 20,
            protocolClasses: [MockLuciURLProtocol.self],
            onSwitchToSecondaryWifi: { _, _, _, _, _, _, _ in
                onSecond.withValue { $0 = true }
                return "192.168.1.3"
            }
        )

        do {
            _ = try await macDriver.executeMobileSetup(
                record: sameSsidRec,
                settings: makeTestSettings(),
                connectedBand: .band24GHz,
                localWifiIpv4: "192.168.1.2"
            )
            XCTFail("Expected neighbor ONT baseMacAddress mismatch error")
        } catch let err as OntDriverException {
            XCTAssertTrue(err.localizedDescription.contains("MAC"))
        }
    }

    // 10. Post-Flush Firmware Drop While App in Background Clears Session Without Sending logout.cmd (RT-05, RT-07)
    func testUpdateFirmwarePostFlushDropWhileAppInBackgroundClearsSessionWithoutSendingLogoutCmd() async throws {
        let fwURL = try createTempFirmwareURL(sizeBytes: 68 * 1024)
        defer { try? FileManager.default.removeItem(at: fwURL) }

        let activeBand = TestLockedBox<WifiBand>(.band24GHz)
        let uploadAttempted = TestLockedBox<Bool>(false)
        let postUploadLogoutCalled = TestLockedBox<Bool>(false)

        MockLuciURLProtocol.rawRequestHandler = { req, _, rawData in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_bg_fw"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/updatefwsessioncheck.json":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            case "/cgi-bin/luci/imagesize.json":
                return self.makeHTTPResponse(url: url, body: """
                {"ramFree":"251696"}
                """)
            case "/cgi-bin/luci/uploadsoftware.conf":
                XCTAssertGreaterThan(rawData.count, 68 * 1024)
                uploadAttempted.withValue { $0 = true }
                throw URLError(.networkConnectionLost)
            case "/cgi-bin/luci/logout.cmd":
                if uploadAttempted.value {
                    postUploadLogoutCalled.withValue { $0 = true }
                }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        // Simulate app being in background (e.g. incoming phone call / screen lock) right as upload flushes
        let driver = makeTestDriver(activeBandBox: activeBand, isAppInBackground: { true })
        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"

        do {
            _ = try await driver.executeMobileSetup(
                record: rec,
                settings: makeTestSettings(enableFirmwareUpdate: true, firmwareURL: fwURL),
                connectedBand: .band24GHz
            )
            XCTFail("Expected OntNetworkError on post-flush socket drop")
        } catch let err as OntNetworkError {
            XCTAssertTrue(err.localizedDescription.contains("Bước 1-8"))
        }

        XCTAssertTrue(uploadAttempted.value)
        XCTAssertFalse(
            postUploadLogoutCalled.value,
            "logout.cmd must NEVER fire after 100% firmware bytes flushed even when isAppInBackground() == true"
        )
    }

    // 11. Insufficient RAM & Invalid Firmware Signature Cleanly Rotate sessionKey and Logout
    func testUpdateFirmwareInsufficientRamAndInvalidStatusRotateSessionKeyForCleanLogout() async throws {
        let fwURL = try createTempFirmwareURL(sizeBytes: 76 * 1024)
        defer { try? FileManager.default.removeItem(at: fwURL) }

        let activeBand = TestLockedBox<WifiBand>(.band24GHz)
        let logoutBodies = TestLockedBox<[String]>([])

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_ram"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/updatefwsessioncheck.json":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","sessionKey":"sess_chk_1"}
                """)
            case "/cgi-bin/luci/imagesize.json":
                return self.makeHTTPResponse(url: url, body: """
                {"ramFree":"20","sessionKey":"sess_img_low_ram"}
                """)
            case "/cgi-bin/luci/logout.cmd":
                logoutBodies.withValue { $0.append(body) }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = makeTestDriver(activeBandBox: activeBand)
        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"

        do {
            _ = try await driver.executeMobileSetup(
                record: rec,
                settings: makeTestSettings(enableFirmwareUpdate: true, firmwareURL: fwURL),
                connectedBand: .band24GHz
            )
            XCTFail("Expected OntDriverException on low ramFree")
        } catch let err as OntDriverException {
            XCTAssertTrue(err.localizedDescription.contains("RAM"))
        }

        XCTAssertTrue(logoutBodies.value.last?.contains("sessionKey=sess_img_low_ram") == true)
    }

    // 12. Mid-Step-7 Transient Socket Drop Recovery While Switching Wi-Fi in iOS Settings
    func testStep7HandoverRecoversFromTransientSocketDropDuringLiveIosWifiSwitch() async throws {
        let activeBand = TestLockedBox<WifiBand>(.band24GHz)
        let handoverAttempt = TestLockedBox<Int>(0)
        let dropDhcpOnce = TestLockedBox<Bool>(true)

        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_switch"}
                """)
            case "/cgi-bin/luci/dhcpclient.json":
                let shouldDrop = dropDhcpOnce.withValue { flag -> Bool in
                    if handoverAttempt.value == 1 && flag {
                        flag = false
                        return true
                    }
                    return false
                }
                if shouldDrop {
                    throw URLError(.networkConnectionLost)
                }
                return self.makeHTTPResponse(url: url, body: self.makeAssocDhcpJson(band: activeBand.value))
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","_wlrestart_":"0"}
                """)
            }
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 15,
            wlRestartPollIntervalMs: 10,
            maxWlRestartPolls: 2,
            userExistPollIntervalMs: 20,
            protocolClasses: [MockLuciURLProtocol.self],
            onSwitchToSecondaryWifi: { _, _, targetBand, _, _, _, _ in
                let attempt = handoverAttempt.withValue { val -> Int in
                    val += 1
                    return val
                }
                if attempt >= 2 {
                    activeBand.withValue { $0 = targetBand }
                }
                return "192.168.1.3"
            }
        )

        var rec = sampleRecord
        rec.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: rec,
            settings: makeTestSettings(),
            connectedBand: .band24GHz
        )

        XCTAssertTrue(result.success)
        XCTAssertGreaterThanOrEqual(handoverAttempt.value, 2)
    }
}
