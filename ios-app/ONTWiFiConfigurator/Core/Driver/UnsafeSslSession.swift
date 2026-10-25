import Foundation

public struct HttpResponsePayload: Sendable {
    public let statusCode: Int
    public let body: String
    public let didFlushFullRequestBody: Bool
}

/// Ephemeral URLSession wrapper configured for ONT GW040-NSv2 wireless configuration:
/// 1. `allowsCellularAccess = false` forces CFNetwork/Darwin to route `192.168.1.1` over Wi-Fi (`en0`),
///    never falling back to 4G/5G cellular (`pdp_ip0`) when the ONT has no WAN Internet yet.
/// 2. Blocks all HTTP Redirects (`301/302/307/308`) via `completionHandler(nil)`.
/// 3. Accepts self-signed TLS certificates strictly for `192.168.1.1`, `localhost`, `127.0.0.1` (or test host override).
/// 4. Tracks `didSendBodyData` (`totalBytesSent >= expectedBytes`) per task so Step 7 can distinguish
///    pre-send TCP connect timeouts from post-flush radio reload disconnects.
public final class UnsafeSslSession: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {

    public static let allowedHosts: Set<String> = ["192.168.1.1", "localhost", "127.0.0.1"]

    private let allowedHostOverride: String?
    private let timeoutMs: Int
    private let byteTrackingLock = NSLock()
    private var bytesSentByTaskId: [Int: (sent: Int64, expected: Int64)] = [:]
    private var session: URLSession!

    public init(
        timeoutMs: Int = 5000,
        allowedHostOverride: String? = nil,
        protocolClasses: [AnyClass] = []
    ) {
        self.timeoutMs = timeoutMs
        self.allowedHostOverride = allowedHostOverride
        super.init()

        let config = URLSessionConfiguration.ephemeral
        config.allowsCellularAccess = false
        config.waitsForConnectivity = false
        config.httpShouldSetCookies = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let timeoutSec = max(Double(timeoutMs) / 1000.0, 0.5)
        config.timeoutIntervalForRequest = timeoutSec
        config.timeoutIntervalForResource = timeoutSec
        if !protocolClasses.isEmpty {
            config.protocolClasses = protocolClasses
        }

        self.session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    deinit {
        session?.invalidateAndCancel()
    }

    public func execute(_ request: URLRequest, expectedBodyBytes: Int = 0) async throws -> HttpResponsePayload {
        var req = request
        req.allowsCellularAccess = false
        req.timeoutInterval = max(Double(timeoutMs) / 1000.0, 0.5)

        return try await withCheckedThrowingContinuation { continuation in
            var taskIdBox = 0
            let task = session.dataTask(with: req) { [weak self] data, response, error in
                let flushed = self?.didTaskFlushFullBody(
                    taskIdentifier: taskIdBox,
                    expectedBytes: Int64(expectedBodyBytes)
                ) ?? false
                self?.clearTaskTracking(taskIdentifier: taskIdBox)

                if let err = error {
                    let nsErr = err as NSError
                    var userInfo = nsErr.userInfo
                    let priorFlushed = (userInfo["didFlushFullRequestBody"] as? Bool) ?? false
                    userInfo["didFlushFullRequestBody"] = flushed || priorFlushed
                    let annotatedError = NSError(
                        domain: nsErr.domain,
                        code: nsErr.code,
                        userInfo: userInfo
                    )
                    continuation.resume(throwing: annotatedError)
                    return
                }

                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                let text = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                continuation.resume(
                    returning: HttpResponsePayload(
                        statusCode: code,
                        body: text,
                        didFlushFullRequestBody: flushed
                    )
                )
            }
            taskIdBox = task.taskIdentifier
            recordExpectedBytes(taskIdentifier: taskIdBox, expectedBytes: Int64(expectedBodyBytes))
            task.resume()
        }
    }

    // MARK: - Byte Flush Tracking

    private func recordExpectedBytes(taskIdentifier: Int, expectedBytes: Int64) {
        byteTrackingLock.lock()
        bytesSentByTaskId[taskIdentifier] = (sent: 0, expected: expectedBytes)
        byteTrackingLock.unlock()
    }

    public func didTaskFlushFullBody(taskIdentifier: Int, expectedBytes: Int64) -> Bool {
        byteTrackingLock.lock()
        defer { byteTrackingLock.unlock() }
        guard let entry = bytesSentByTaskId[taskIdentifier] else {
            return false
        }
        let target = max(expectedBytes, entry.expected)
        return target > 0 && entry.sent >= target
    }

    private func clearTaskTracking(taskIdentifier: Int) {
        byteTrackingLock.lock()
        bytesSentByTaskId.removeValue(forKey: taskIdentifier)
        byteTrackingLock.unlock()
    }

    // MARK: - URLSessionDelegate & URLSessionTaskDelegate

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        byteTrackingLock.lock()
        let prevExpected = bytesSentByTaskId[task.taskIdentifier]?.expected ?? 0
        bytesSentByTaskId[task.taskIdentifier] = (
            sent: totalBytesSent,
            expected: max(prevExpected, totalBytesExpectedToSend)
        )
        byteTrackingLock.unlock()
    }

    /// Block all HTTP Redirects (301/302/307/308) to prevent credential leakage to rogue portals.
    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    /// Accept self-signed TLS certificate strictly for ONT gateway host 192.168.1.1 / localhost.
    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        let host = challenge.protectionSpace.host.lowercased()
        let isAllowed = Self.allowedHosts.contains(host) ||
            (allowedHostOverride?.lowercased() == host)

        if isAllowed {
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
