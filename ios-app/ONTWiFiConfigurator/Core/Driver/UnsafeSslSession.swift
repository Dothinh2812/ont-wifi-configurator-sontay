import CryptoKit
import Foundation
import Security

public struct HttpResponsePayload: Sendable {
    public let statusCode: Int
    public let body: String
    public let didFlushFullRequestBody: Bool
}

/// Ephemeral URLSession wrapper configured for ONT GW040-NSv2 wireless configuration:
/// 1. `allowsCellularAccess = false` forces CFNetwork/Darwin to route `192.168.1.1` over Wi-Fi (`en0`),
///    never falling back to 4G/5G cellular (`pdp_ip0`) when the ONT has no WAN Internet yet.
/// 2. Blocks all HTTP Redirects (`301/302/307/308`) via `completionHandler(nil)`.
/// 3. Accepts self-signed TLS certificates strictly for `192.168.1.1`, `localhost`, `127.0.0.1` (or test host override),
///    capturing Connection 1's leaf X.509 SHA-256 fingerprint and enforcing `pinnedCertSha256` on Connection 2.
/// 4. Supports both form `execute(...)` and streamed 32 KB chunked `uploadMultipartFile(...)` (`uploadTask(withStreamedRequest:)`).
public final class UnsafeSslSession: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {

    public static let allowedHosts: Set<String> = ["192.168.1.1", "localhost", "127.0.0.1"]
    public static let tlsPinErrorDomain = "OntTlsPinError"
    public static let tlsPinErrorCode = -1202

    private let allowedHostOverride: String?
    private let timeoutMs: Int
    private let protocolClasses: [AnyClass]
    private let byteTrackingLock = NSLock()
    private var bytesSentByTaskId: [Int: (sent: Int64, expected: Int64)] = [:]
    private var _lastPeerCertSha256: String?
    private var _pinnedCertSha256: String?
    private var _didRejectPinnedCert: Bool = false
    private var session: URLSession!

    public var lastPeerCertSha256: String? {
        byteTrackingLock.lock()
        defer { byteTrackingLock.unlock() }
        return _lastPeerCertSha256
    }

    public var pinnedCertSha256: String? {
        get {
            byteTrackingLock.lock()
            defer { byteTrackingLock.unlock() }
            return _pinnedCertSha256
        }
        set {
            byteTrackingLock.lock()
            _pinnedCertSha256 = newValue?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            _didRejectPinnedCert = false
            byteTrackingLock.unlock()
        }
    }

    public func resetPeerCertificateTracking() {
        byteTrackingLock.lock()
        _lastPeerCertSha256 = nil
        _pinnedCertSha256 = nil
        _didRejectPinnedCert = false
        byteTrackingLock.unlock()
    }

    public static func certificateSha256Hex(_ cert: SecCertificate) -> String {
        let derData = SecCertificateCopyData(cert) as Data
        let digest = SHA256.hash(data: derData)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    public init(
        timeoutMs: Int = 5000,
        allowedHostOverride: String? = nil,
        protocolClasses: [AnyClass] = []
    ) {
        self.timeoutMs = timeoutMs
        self.allowedHostOverride = allowedHostOverride
        self.protocolClasses = protocolClasses
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
                    if self?.consumeTlsPinRejectionFlag() == true {
                        continuation.resume(
                            throwing: NSError(
                                domain: Self.tlsPinErrorDomain,
                                code: Self.tlsPinErrorCode,
                                userInfo: [
                                    NSLocalizedDescriptionKey: "Chứng chỉ bảo mật TLS của ONT ở Lần kết nối 2 không khớp với ONT ban đầu — nghi ngờ bắt nhầm Wi-Fi khác."
                                ]
                            )
                        )
                        return
                    }
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

    /// Stream-assemble a multipart/form-data payload on disk in 32 KB chunks (`completeUntilFirstUserAuthentication`)
    /// and upload via `uploadTask(withStreamedRequest:)` with explicit `Content-Length`, `protocolClasses`,
    /// `withTaskCancellationHandler`, and `finishTasksAndInvalidate()` cleanup.
    public func uploadMultipartFile(
        url: URL,
        headers: [String: String] = [:],
        fileFieldName: String = "file",
        fileURL: URL,
        safeFileName: String,
        formFields: [(String, String)],
        timeoutOverrideMs: Int = 180_000,
        onProgressPercent: (@Sendable (Int) -> Void)? = nil
    ) async throws -> HttpResponsePayload {
        let boundary = "----OntBoundary\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let tempMultipartURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("ont_multipart_\(UUID().uuidString).tmp")

        defer {
            try? FileManager.default.removeItem(at: tempMultipartURL)
        }

        let totalMultipartBytes = try Self.assembleMultipartTempFile(
            tempMultipartURL: tempMultipartURL,
            boundary: boundary,
            fileFieldName: fileFieldName,
            fileURL: fileURL,
            safeFileName: safeFileName,
            formFields: formFields
        )

        let timeoutSec = max(Double(timeoutOverrideMs) / 1000.0, Double(timeoutMs) / 1000.0, 5.0)
        let config = URLSessionConfiguration.ephemeral
        config.allowsCellularAccess = false
        config.waitsForConnectivity = false
        config.httpShouldSetCookies = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = timeoutSec
        config.timeoutIntervalForResource = timeoutSec
        if !protocolClasses.isEmpty {
            config.protocolClasses = protocolClasses
        }

        let currentPin = self.pinnedCertSha256
        let uploadDelegate = StreamedMultipartUploadDelegate(
            multipartFileURL: tempMultipartURL,
            expectedTotalBytes: totalMultipartBytes,
            allowedHostOverride: allowedHostOverride,
            pinnedCertSha256: currentPin,
            onObservedPeerCertSha256: { [weak self] sha in
                self?.recordObservedCertIfNeeded(sha)
            },
            onProgressPercent: onProgressPercent
        )
        let uploadSession = URLSession(configuration: config, delegate: uploadDelegate, delegateQueue: nil)
        defer {
            uploadSession.finishTasksAndInvalidate()
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.allowsCellularAccess = false
        req.timeoutInterval = timeoutSec
        for (headerKey, headerVal) in headers {
            req.setValue(headerVal, forHTTPHeaderField: headerKey)
        }
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue(String(totalMultipartBytes), forHTTPHeaderField: "Content-Length")
        req.setValue("close", forHTTPHeaderField: "Connection")
        req.httpBodyStream = InputStream(url: tempMultipartURL)

        try Task.checkCancellation()
        let uploadTask = uploadSession.uploadTask(withStreamedRequest: req)

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                if uploadDelegate.attachContinuation(continuation) {
                    uploadTask.resume()
                }
            }
        } onCancel: {
            uploadTask.cancel()
        }
    }

    private static func assembleMultipartTempFile(
        tempMultipartURL: URL,
        boundary: String,
        fileFieldName: String,
        fileURL: URL,
        safeFileName: String,
        formFields: [(String, String)]
    ) throws -> Int64 {
        FileManager.default.createFile(
            atPath: tempMultipartURL.path,
            contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        guard let outStream = OutputStream(url: tempMultipartURL, append: false) else {
            throw NSError(
                domain: "UnsafeSslSession",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Không thể khởi tạo luồng đệm multipart tạm cho Firmware."]
            )
        }
        outStream.open()
        defer { outStream.close() }

        func writeAll(_ data: Data) throws {
            try data.withUnsafeBytes { rawBuf in
                guard let base = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return }
                var offset = 0
                while offset < data.count {
                    let written = outStream.write(base.advanced(by: offset), maxLength: data.count - offset)
                    if written <= 0 {
                        throw outStream.streamError ?? NSError(
                            domain: "UnsafeSslSession",
                            code: -2,
                            userInfo: [NSLocalizedDescriptionKey: "Lỗi ghi luồng multipart tạm trên bộ nhớ máy."]
                        )
                    }
                    offset += written
                }
            }
        }

        let fileHeader =
            "--\(boundary)\r\n" +
            "Content-Disposition: form-data; name=\"\(fileFieldName)\"; filename=\"\(safeFileName)\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        try writeAll(Data(fileHeader.utf8))

        guard let inStream = InputStream(url: fileURL) else {
            throw NSError(
                domain: "UnsafeSslSession",
                code: -3,
                userInfo: [NSLocalizedDescriptionKey: "Không thể mở file Firmware tại '\(fileURL.lastPathComponent)'."]
            )
        }
        inStream.open()
        defer { inStream.close() }

        let chunkSize = 32 * 1024
        var buffer = [UInt8](repeating: 0, count: chunkSize)
        while inStream.hasBytesAvailable {
            let bytesRead = inStream.read(&buffer, maxLength: chunkSize)
            if bytesRead > 0 {
                var offset = 0
                while offset < bytesRead {
                    let written = buffer.withUnsafeBufferPointer { ptr in
                        outStream.write(ptr.baseAddress!.advanced(by: offset), maxLength: bytesRead - offset)
                    }
                    if written <= 0 {
                        throw outStream.streamError ?? NSError(
                            domain: "UnsafeSslSession",
                            code: -4,
                            userInfo: [NSLocalizedDescriptionKey: "Lỗi sao chép khối nhị phân Firmware."]
                        )
                    }
                    offset += written
                }
            } else if bytesRead < 0 {
                throw inStream.streamError ?? NSError(
                    domain: "UnsafeSslSession",
                    code: -5,
                    userInfo: [NSLocalizedDescriptionKey: "Lỗi đọc luồng nhị phân Firmware."]
                )
            } else {
                break
            }
        }

        var trailer = "\r\n"
        for (fieldName, fieldVal) in formFields {
            trailer += "--\(boundary)\r\n"
            trailer += "Content-Disposition: form-data; name=\"\(fieldName)\"\r\n\r\n"
            trailer += "\(fieldVal)\r\n"
        }
        trailer += "--\(boundary)--\r\n"
        try writeAll(Data(trailer.utf8))

        let attrs = try FileManager.default.attributesOfItem(atPath: tempMultipartURL.path)
        return (attrs[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func recordObservedCertIfNeeded(_ sha256Hex: String) {
        byteTrackingLock.lock()
        if _lastPeerCertSha256 == nil {
            _lastPeerCertSha256 = sha256Hex
        }
        byteTrackingLock.unlock()
    }

    private func consumeTlsPinRejectionFlag() -> Bool {
        byteTrackingLock.lock()
        defer { byteTrackingLock.unlock() }
        let rejected = _didRejectPinnedCert
        _didRejectPinnedCert = false
        return rejected
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

    /// Accept self-signed TLS certificate strictly for ONT gateway host and enforce `pinnedCertSha256` when set.
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
        guard isAllowed else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        let certs = SecTrustCopyCertificateChain(serverTrust) as? [SecCertificate]
        let leafSha = certs?.first.map(Self.certificateSha256Hex)

        byteTrackingLock.lock()
        if let sha = leafSha, _lastPeerCertSha256 == nil {
            _lastPeerCertSha256 = sha
        }
        let expectedPin = _pinnedCertSha256
        if let pin = expectedPin, !pin.isEmpty, leafSha != pin {
            _didRejectPinnedCert = true
            byteTrackingLock.unlock()
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        byteTrackingLock.unlock()

        completionHandler(.useCredential, URLCredential(trust: serverTrust))
    }
}

private final class StreamedMultipartUploadDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let multipartFileURL: URL
    private let expectedTotalBytes: Int64
    private let allowedHostOverride: String?
    private let pinnedCertSha256: String?
    private let onObservedPeerCertSha256: @Sendable (String) -> Void
    private let onProgressPercent: (@Sendable (Int) -> Void)?

    private let lock = NSLock()
    private var continuation: CheckedContinuation<HttpResponsePayload, Error>?
    private var pendingEarlyResult: Result<HttpResponsePayload, Error>?
    private var responseData = Data()
    private var httpStatusCode: Int = 0
    private var maxBytesSent: Int64 = 0
    private var lastReportedPercent: Int = -1
    private var didRejectPinnedCert: Bool = false
    private var didFinish: Bool = false

    init(
        multipartFileURL: URL,
        expectedTotalBytes: Int64,
        allowedHostOverride: String?,
        pinnedCertSha256: String?,
        onObservedPeerCertSha256: @escaping @Sendable (String) -> Void,
        onProgressPercent: (@Sendable (Int) -> Void)?
    ) {
        self.multipartFileURL = multipartFileURL
        self.expectedTotalBytes = expectedTotalBytes
        self.allowedHostOverride = allowedHostOverride
        self.pinnedCertSha256 = pinnedCertSha256?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        self.onObservedPeerCertSha256 = onObservedPeerCertSha256
        self.onProgressPercent = onProgressPercent
    }

    func attachContinuation(_ cont: CheckedContinuation<HttpResponsePayload, Error>) -> Bool {
        lock.lock()
        if let early = pendingEarlyResult {
            pendingEarlyResult = nil
            lock.unlock()
            cont.resume(with: early)
            return false
        }
        self.continuation = cont
        lock.unlock()
        return true
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        needNewBodyStream completionHandler: @escaping (InputStream?) -> Void
    ) {
        completionHandler(InputStream(url: multipartFileURL))
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        var percentToEmit: Int?
        lock.lock()
        if totalBytesSent > maxBytesSent {
            maxBytesSent = totalBytesSent
        }
        let denom = max(expectedTotalBytes, totalBytesExpectedToSend)
        if denom > 0 {
            let pct = min(100, max(0, Int((totalBytesSent * 100) / denom)))
            if pct == 100 || pct - lastReportedPercent >= 2 {
                lastReportedPercent = pct
                percentToEmit = pct
            }
        }
        lock.unlock()

        if let pct = percentToEmit {
            onProgressPercent?(pct)
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        httpStatusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        responseData.append(data)
        lock.unlock()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    func urlSession(
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
        let isAllowed = UnsafeSslSession.allowedHosts.contains(host) ||
            (allowedHostOverride?.lowercased() == host)
        guard isAllowed else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        let certs = SecTrustCopyCertificateChain(serverTrust) as? [SecCertificate]
        let leafSha = certs?.first.map(UnsafeSslSession.certificateSha256Hex)
        if let sha = leafSha {
            onObservedPeerCertSha256(sha)
        }
        if let pin = pinnedCertSha256, !pin.isEmpty, leafSha != pin {
            lock.lock()
            didRejectPinnedCert = true
            lock.unlock()
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: serverTrust))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        guard !didFinish else {
            lock.unlock()
            return
        }
        didFinish = true
        let cont = continuation
        continuation = nil
        let flushed = expectedTotalBytes > 0 && maxBytesSent >= expectedTotalBytes
        let rejectedPin = didRejectPinnedCert
        let statusCode = (task.response as? HTTPURLResponse)?.statusCode ?? httpStatusCode
        let bodyText = String(data: responseData, encoding: .utf8) ?? ""

        let result: Result<HttpResponsePayload, Error>
        if let err = error {
            if rejectedPin {
                result = .failure(
                    NSError(
                        domain: UnsafeSslSession.tlsPinErrorDomain,
                        code: UnsafeSslSession.tlsPinErrorCode,
                        userInfo: [
                            NSLocalizedDescriptionKey: "Chứng chỉ bảo mật TLS của ONT không khớp khi tải lên Firmware."
                        ]
                    )
                )
            } else {
                let nsErr = err as NSError
                var userInfo = nsErr.userInfo
                let priorFlushed = (userInfo["didFlushFullRequestBody"] as? Bool) ?? false
                userInfo["didFlushFullRequestBody"] = flushed || priorFlushed
                result = .failure(NSError(domain: nsErr.domain, code: nsErr.code, userInfo: userInfo))
            }
        } else {
            result = .success(
                HttpResponsePayload(
                    statusCode: statusCode,
                    body: bodyText,
                    didFlushFullRequestBody: true
                )
            )
        }

        if cont == nil {
            pendingEarlyResult = result
        }
        lock.unlock()

        cont?.resume(with: result)
    }
}
