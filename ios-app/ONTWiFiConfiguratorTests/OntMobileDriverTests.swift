import Foundation
import XCTest
@testable import ONTWiFiConfigurator

final class MockLuciURLProtocol: URLProtocol {

    static var requestHandler: ((URLRequest, String) throws -> (HTTPURLResponse, Data))?
    private static let lock = NSLock()
    private static var _recordedRequests: [(path: String, body: String)] = []

    static var recordedRequests: [(path: String, body: String)] {
        lock.lock()
        defer { lock.unlock() }
        return _recordedRequests
    }

    static func reset() {
        lock.lock()
        _recordedRequests.removeAll()
        requestHandler = nil
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    private static func readBodyString(from request: URLRequest) -> String {
        if let data = request.httpBody {
            return String(data: data, encoding: .utf8) ?? ""
        }
        guard let stream = request.httpBodyStream else {
            return ""
        }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 4096
        var buffer = [UInt8](repeating: 0, count: bufferSize)
        while stream.hasBytesAvailable {
            let bytesRead = stream.read(&buffer, maxLength: bufferSize)
            if bytesRead > 0 {
                data.append(buffer, count: bytesRead)
            } else {
                break
            }
        }
        return String(data: data, encoding: .utf8) ?? ""
    }

    override func startLoading() {
        let bodyString = Self.readBodyString(from: request)
        let path = request.url?.path ?? ""

        Self.lock.lock()
        Self._recordedRequests.append((path: path, body: bodyString))
        let handler = Self.requestHandler
        Self.lock.unlock()

        guard let activeHandler = handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        do {
            let (response, data) = try activeHandler(request, bodyString)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            if !data.isEmpty {
                client?.urlProtocol(self, didLoad: data)
            }
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

private final class TestLockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: T
    init(_ value: T) { self.storage = value }
    func withValue(_ body: (inout T) -> Void) {
        lock.lock()
        body(&storage)
        lock.unlock()
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

    func testExecuteMobileSetupOn24GHzConfigures5GHzInStep6And24GHzLastInStep7WithServiceId1AndCallsLogout() async throws {
        let probeMutex = AsyncMutex()

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path

            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html><title>VNPT iGate GW040-NSv2</title></html>")
            case "/cgi-bin/luci/login.cmd":
                if body.contains("pass=admin") {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"1","numberLogin":"0","timeoutLogin":"0"}
                    """)
                } else {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_999","numberLogin":"0","timeoutLogin":"0"}
                    """)
                }
            case "/cgi-bin/luci/accesscontrolcfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
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

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            probePauseMutex: probeMutex,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        let result = try await driver.executeMobileSetup(
            record: sampleRecord,
            settings: GlobalSettings(baseUrl: baseUrl),
            connectedBand: .band24GHz
        )

        XCTAssertTrue(result.success)
        XCTAssertFalse(probeMutex.isLocked)

        let recorded = MockLuciURLProtocol.recordedRequests
        let editWanBody = recorded.first(where: { $0.path == "/cgi-bin/luci/editwan.json" })?.body ?? ""
        let sendWanBody = recorded.first(where: { $0.path == "/cgi-bin/luci/sendwan.cmd" })?.body ?? ""
        XCTAssertTrue(editWanBody.contains("serviceId=1"), "Expected serviceId=1 in editwan.json: \(editWanBody)")
        XCTAssertTrue(sendWanBody.contains("serviceId=1"), "Expected serviceId=1 in sendwan.cmd: \(sendWanBody)")

        let wirelessCalls = recorded.filter { $0.path == "/cgi-bin/luci/wirelesscfg.json" }
        XCTAssertEqual(wirelessCalls.count, 2)
        XCTAssertTrue(wirelessCalls[0].body.contains("wl_unit=1"), "Step 6 must configure 5GHz (wl_unit=1) first")
        XCTAssertTrue(wirelessCalls[1].body.contains("wl_unit=0"), "Step 7 must configure 2.4GHz (wl_unit=0) LAST")
        XCTAssertEqual(recorded.last?.path, "/cgi-bin/luci/logout.cmd")
    }

    func testExecuteMobileSetupOn5GHzReversesBandOrderSo24GHzIsStep6And5GHzIsStep7Last() async throws {
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
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        let result = try await driver.executeMobileSetup(
            record: sampleRecord,
            settings: GlobalSettings(baseUrl: baseUrl),
            connectedBand: .band5GHz
        )

        XCTAssertTrue(result.success)
        let wirelessCalls = MockLuciURLProtocol.recordedRequests.filter { $0.path == "/cgi-bin/luci/wirelesscfg.json" }
        XCTAssertEqual(wirelessCalls.count, 2)
        XCTAssertTrue(wirelessCalls[0].body.contains("wl_unit=0"))
        XCTAssertTrue(wirelessCalls[1].body.contains("wl_unit=1"))
    }

    func testStep7HandlesSocketDisconnectDuringRadioReloadWhenOldGatewayDisappears() async throws {
        var wirelessCallCount = 0
        var step7Dropped = false

        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            if step7Dropped {
                throw URLError(.cannotConnectToHost)
            }
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_drop"}
                """)
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wirelesscfg.json":
                wirelessCallCount += 1
                if wirelessCallCount == 2 {
                    step7Dropped = true
                    throw URLError(.networkConnectionLost)
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
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        let result = try await driver.executeMobileSetup(
            record: sampleRecord,
            settings: GlobalSettings(baseUrl: baseUrl),
            connectedBand: .band24GHz
        )

        XCTAssertTrue(result.success)
    }

    func testMidFlightErrorAtStep4StillInvokesLogoutCmdInCleanup() async throws {
        var logoutCalled = false

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
                logoutCalled = true
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            }
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        do {
            _ = try await driver.executeMobileSetup(
                record: sampleRecord,
                settings: GlobalSettings(baseUrl: baseUrl)
            )
            XCTFail("Expected error at Step 4")
        } catch {
            // Expected
        }

        XCTAssertTrue(logoutCalled, "Mandatory logout.cmd must execute on mid-flight failure")
    }

    func testSmartRetryScopesPasswordCachePerRecordAndStopsAtNumberLogin2() async throws {
        var loginBodies: [String] = []

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>GW040</html>")
            case "/cgi-bin/luci/login.cmd":
                loginBodies.append(body)
                if body.contains("pass=Passwd2%40") || body.contains("pass=Passwd2@") {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_retry","numberLogin":"0"}
                    """)
                } else {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}
                    """)
                }
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
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        var retryRecord = sampleRecord
        retryRecord.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: retryRecord,
            settings: GlobalSettings(baseUrl: baseUrl)
        )

        XCTAssertTrue(result.success)
        XCTAssertEqual(loginBodies.count, 1)
        XCTAssertTrue(loginBodies[0].contains("Passwd2"))

        // Fresh record (different subscriber / ONT) must NOT inherit cached password from previous subscriber
        loginBodies.removeAll()
        var freshDifferentRecord = sampleRecord
        freshDifferentRecord.rowIndex = 99
        freshDifferentRecord.soMay = "009999999"
        freshDifferentRecord.slid = "009999999"
        freshDifferentRecord.lastKnownAdminPass = ""

        do {
            _ = try await driver.executeMobileSetup(
                record: freshDifferentRecord,
                settings: GlobalSettings(baseUrl: baseUrl)
            )
            XCTFail("Expected OntAccountLockoutWarning when numberLogin >= 2")
        } catch let error as OntAccountLockoutWarning {
            XCTAssertTrue(error.localizedDescription.contains("2"))
        }
    }

    func testConfigureWifiBandAllSixSecurityModesAndConflictMeshNoRetry() async throws {
        var wirelessBodies: [String] = []
        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            if path == "/cgi-bin/luci/wirelesscfg.json" {
                wirelessBodies.append(body)
            }
            return self.makeHTTPResponse(url: url, body: """
            {"status":"OK","_wlrestart_":"0"}
            """)
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 5,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        for mode in WifiSecurityMode.allCases {
            wirelessBodies.removeAll()
            let pw = mode.requiresPassword ? "28121995" : ""
            _ = try await driver.configureWifiBand(
                unit: 0,
                ssid: "Manh Ngoc",
                password: pw,
                secMode: mode.rawValue,
                isConnectedBandFinalStep: false
            )
            XCTAssertEqual(wirelessBodies.count, 1)
            let body = wirelessBodies[0]
            XCTAssertTrue(body.contains("sec_mode=\(mode.rawValue)"))
            XCTAssertTrue(body.contains("wl_akm_psk=\(mode.akmPsk)"))
            XCTAssertTrue(body.contains("wl_akm_psk2=\(mode.akmPsk2)"))
            XCTAssertTrue(body.contains("wl_crypto=\(mode.crypto)"))
        }

        // Verify conflict_mesh in Step 7 throws OntDriverException (not OntNetworkError) without retrying 3x (RT-01)
        var step7Attempts = 0
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
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wirelesscfg.json":
                if body.contains("wl_unit=0") {
                    step7Attempts += 1
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

        do {
            _ = try await driver.executeMobileSetup(
                record: sampleRecord,
                settings: GlobalSettings(baseUrl: baseUrl, wifiSecurityMode: "open"),
                connectedBand: .band24GHz
            )
            XCTFail("Expected OntDriverException on conflict_mesh")
        } catch let err as OntNetworkError {
            XCTFail("conflict_mesh must not be wrapped as OntNetworkError: \(err)")
        } catch let err as OntDriverException {
            XCTAssertTrue(err.localizedDescription.contains("Mesh"))
        }
        XCTAssertEqual(step7Attempts, 1, "conflict_mesh must not retry 3 times in Step 7")
    }

    func testResolveConnectedBandFromOntParses2DWirelessInfoListAndOverridesMisdetectedBand() async throws {
        let resolvedBands = TestLockedBox<[WifiBand]>([])

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
                return self.makeHTTPResponse(url: url, body: """
                {"dhcpInfoList":["192.168.1.2|DHCP||aa:bb:cc:dd:ee:ff|iPhone|WiFi|1|wl0"],"wlAssociatedDevice":["aa:bb:cc:dd:ee:ff|192.168.1.2|1|wl1.1|-48"]}
                """)
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
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        var record = sampleRecord
        record.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: record,
            settings: GlobalSettings(baseUrl: baseUrl),
            connectedBand: .band24GHz,
            localWifiIpv4: "192.168.1.2",
            onBandResolved: { band in
                resolvedBands.withValue { $0.append(band) }
            }
        )

        XCTAssertTrue(result.success)
        XCTAssertEqual(result.resolvedConnectedBand, .band5GHz)
        XCTAssertEqual(resolvedBands.value, [.band5GHz])

        let recorded = MockLuciURLProtocol.recordedRequests
        let paths = recorded.map { $0.path }
        guard let loginIdx = paths.firstIndex(of: "/cgi-bin/luci/login.cmd"),
              let dhcpIdx = paths.firstIndex(of: "/cgi-bin/luci/dhcpclient.json"),
              let devIdx = paths.firstIndex(of: "/cgi-bin/luci/deviceinfo2.json"),
              let wanIdx = paths.firstIndex(of: "/cgi-bin/luci/wancfg.json") else {
            XCTFail("Expected login.cmd -> dhcpclient.json -> deviceinfo2.json -> wancfg.json call order")
            return
        }
        XCTAssertTrue(loginIdx < dhcpIdx && dhcpIdx < devIdx && devIdx < wanIdx)

        let wirelessCalls = recorded.filter { $0.path == "/cgi-bin/luci/wirelesscfg.json" }
        XCTAssertEqual(wirelessCalls.count, 2)
        XCTAssertTrue(wirelessCalls[0].body.contains("wl_unit=0"), "Step 6 must configure 2.4GHz (wl_unit=0) first when iPhone is actually on 5GHz")
        XCTAssertTrue(wirelessCalls[1].body.contains("wl_unit=1"), "Step 7 must configure 5GHz (wl_unit=1) LAST")
    }

    func testEnsureGatewayReadyBeforeStep7RecoversFromFailAuthenAndUserExistWithoutDoubleLogin() async throws {
        var wlRestartCalls = 0
        var reloginCalls = 0
        var releasedSessions: [String] = []
        var step7SessionKeys: [String] = []

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                if wlRestartCalls == 0 {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_ios_init","numberLogin":"0","timeoutLogin":"0"}
                    """)
                } else {
                    reloginCalls += 1
                    if reloginCalls == 1 && !releasedSessions.contains("sess_ios_init") {
                        return self.makeHTTPResponse(url: url, body: """
                        {"status":"user exist"}
                        """)
                    }
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_ios_new","numberLogin":"0","timeoutLogin":"0"}
                    """)
                }
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[],"sessionKey":"0"}
                """)
            case "/cgi-bin/luci/wlrestart.json":
                wlRestartCalls += 1
                if wlRestartCalls == 2 {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"fail authen"}
                    """)
                }
                return self.makeHTTPResponse(url: url, body: """
                {"_wlrestart_":"0"}
                """)
            case "/cgi-bin/luci/wirelesscfg.json":
                if body.contains("wl_unit=0") {
                    if let range = body.range(of: "sessionKey=") {
                        let tail = body[range.upperBound...]
                        let key = tail.split(separator: "&").first.map(String.init) ?? ""
                        step7SessionKeys.append(key)
                    }
                }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            case "/cgi-bin/luci/logout.cmd":
                if let range = body.range(of: "sessionKey=") {
                    let tail = body[range.upperBound...]
                    let key = tail.split(separator: "&").first.map(String.init) ?? ""
                    releasedSessions.append(key)
                }
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            }
        }

        let driver = OntMobileDriver(
            baseUrl: baseUrl,
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        var record = sampleRecord
        record.lastKnownAdminPass = "Passwd2@"
        let result = try await driver.executeMobileSetup(
            record: record,
            settings: GlobalSettings(baseUrl: baseUrl),
            connectedBand: .band24GHz
        )

        XCTAssertTrue(result.success)
        XCTAssertTrue(releasedSessions.contains("sess_ios_init"))
        XCTAssertEqual(reloginCalls, 2)
        XCTAssertEqual(step7SessionKeys, ["sess_ios_new"])

        // Verify no double-login when numberLogin == 2 during ensureGatewayReadyBeforeStep7
        var lockoutReloginCalls = 0
        var gateTriggered = false
        MockLuciURLProtocol.requestHandler = { req, _ in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                if !gateTriggered {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"OK","firstlog":"0","sessionKey":"sess_lock","numberLogin":"0","timeoutLogin":"0"}
                    """)
                } else {
                    lockoutReloginCalls += 1
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"FAIL","numberLogin":"2","timeoutLogin":"0"}
                    """)
                }
            case "/cgi-bin/luci/wancfg.json":
                return self.makeHTTPResponse(url: url, body: """
                {"wanCfg":[]}
                """)
            case "/cgi-bin/luci/wlrestart.json":
                if !gateTriggered {
                    gateTriggered = true
                    return self.makeHTTPResponse(url: url, body: """
                    {"_wlrestart_":"0"}
                    """)
                } else {
                    return self.makeHTTPResponse(url: url, body: """
                    {"status":"fail authen"}
                    """)
                }
            default:
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK"}
                """)
            }
        }

        do {
            _ = try await driver.executeMobileSetup(
                record: record,
                settings: GlobalSettings(baseUrl: baseUrl),
                connectedBand: .band24GHz
            )
            XCTFail("Expected OntAccountLockoutWarning on numberLogin == 2")
        } catch is OntAccountLockoutWarning {
            // Expected immediate propagation without double-login
        }
        XCTAssertEqual(lockoutReloginCalls, 1, "Must NEVER double-call login when numberLogin == 2")
    }

    func testStep7Attempt3ThrowsNetworkErrorInsteadOfFalseSuccessWhenOldGatewayRemainsOnline() async throws {
        var unit0Attempts = 0

        MockLuciURLProtocol.requestHandler = { req, body in
            let url = req.url ?? URL(string: "https://192.168.1.1/")!
            let path = url.path.isEmpty ? "/" : url.path
            if path == "/cgi-bin/luci/wirelesscfg.json" && body.contains("wl_unit=0") {
                unit0Attempts += 1
                throw URLError(.networkConnectionLost)
            }
            switch path {
            case "/":
                return self.makeHTTPResponse(url: url, body: "<html>VNPT iGate GW040-NSv2</html>")
            case "/cgi-bin/luci/login.cmd":
                return self.makeHTTPResponse(url: url, body: """
                {"status":"OK","firstlog":"0","sessionKey":"sess_ios_ok","numberLogin":"0","timeoutLogin":"0"}
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
            settlingDelayMs: 10,
            recoveryRetryDelayMs: 20,
            protocolClasses: [MockLuciURLProtocol.self]
        )

        var record = sampleRecord
        record.lastKnownAdminPass = "Passwd2@"
        do {
            _ = try await driver.executeMobileSetup(
                record: record,
                settings: GlobalSettings(baseUrl: baseUrl),
                connectedBand: .band24GHz
            )
            XCTFail("Expected OntNetworkError when Step 7 drops across all 3 attempts while old Wi-Fi remains online")
        } catch is OntNetworkError {
            // Expected
        }
        XCTAssertEqual(unit0Attempts, 3, "Must verify all 3 Step 7 attempts before failing")
    }
}
