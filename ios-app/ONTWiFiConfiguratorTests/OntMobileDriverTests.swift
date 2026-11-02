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
}
