import Foundation

public enum LogLevel: String, Codable, Sendable {
    case info = "INFO"
    case warn = "WARN"
    case error = "ERROR"
}

public struct DiagnosticLogEntry: Identifiable, Equatable, Sendable {
    public var id: Int64 { seq }
    public let seq: Int64
    public let timestamp: String
    public let level: LogLevel
    public let tag: String
    public let message: String

    public init(
        seq: Int64,
        timestamp: String,
        level: LogLevel,
        tag: String,
        message: String
    ) {
        self.seq = seq
        self.timestamp = timestamp
        self.level = level
        self.tag = tag
        self.message = message
    }

    public func formatLine() -> String {
        return "[\(timestamp)] [\(level.rawValue)] [\(tag)] \(message)"
    }
}

/// Thread-safe iOS diagnostic logger with synchronous caller-thread 2-layer secret scrubbing,
/// a 1,500-entry RAM ring buffer with 200ms FIFO main-queue UI coalescing, and 512 KB x 2
/// rotating disk files protected with `completeUntilFirstUserAuthentication` + `isExcludedFromBackup = true`.
public final class AppDiagnosticLogger: @unchecked Sendable {

    public static let maxMemoryEntries: Int = 1_500
    public static let defaultMaxFileBytes: Int64 = 512 * 1024
    private static let minUnkeyedSecretLength: Int = 6
    private static let currentLogFileName = "ont_debug.log"
    private static let prevLogFileName = "ont_debug.prev.log"

    private static let reservedSafeTokens: Set<String> = [
        "admin",
        "root",
        "open",
        "null",
        "true",
        "false",
        "192.168.1.1"
    ]

    private let logDirURL: URL
    private let maxFileBytes: Int64
    private let ioQueue = DispatchQueue(label: "vn.vnpt.ontconfig.diaglogger", qos: .utility)
    private let bufferLock = NSLock()
    private let secretsLock = NSLock()
    private let formatterLock = NSLock()

    private let timestampFormatter: DateFormatter = {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        return df
    }()

    private var nextSeq: Int64 = 0
    private var memoryBuffer: [DiagnosticLogEntry] = []
    private var coalesceScheduled: Bool = false
    private var onSnapshotUpdated: (@Sendable ([DiagnosticLogEntry]) -> Void)?

    private var activeSecretTokens: [String] = []
    private var knownSubscriberSet: [String] = []

    public init(
        logDirURL: URL? = nil,
        maxFileBytes: Int64 = defaultMaxFileBytes,
        onSnapshotUpdated: (@Sendable ([DiagnosticLogEntry]) -> Void)? = nil
    ) {
        if let customURL = logDirURL {
            self.logDirURL = customURL
        } else {
            let baseAppSupport = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first ?? FileManager.default.temporaryDirectory
            self.logDirURL = baseAppSupport
                .appendingPathComponent("ONTWiFiConfigurator", isDirectory: true)
                .appendingPathComponent("logs", isDirectory: true)
        }
        self.maxFileBytes = max(1024, maxFileBytes)
        self.onSnapshotUpdated = onSnapshotUpdated
        self.memoryBuffer.reserveCapacity(min(256, Self.maxMemoryEntries))
    }

    public func setOnSnapshotUpdated(_ handler: (@Sendable ([DiagnosticLogEntry]) -> Void)?) {
        bufferLock.lock()
        self.onSnapshotUpdated = handler
        let initial = self.memoryBuffer
        bufferLock.unlock()
        if let handler = handler {
            DispatchQueue.main.async {
                handler(initial)
            }
        }
    }

    public func registerActiveSecrets(
        _ secrets: [String],
        subscriberIdentifiers: [String] = []
    ) {
        secretsLock.lock()
        defer { secretsLock.unlock() }

        var uniqueSecrets = Set<String>()
        for raw in secrets {
            guard !raw.isEmpty else { continue }
            let trimmedLower = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !Self.reservedSafeTokens.contains(trimmedLower) else { continue }

            let variants = [
                raw,
                OntInputValidator.urlEncodeFormValue(raw),
                OntInputValidator.ontEncodeQuery(raw)
            ]
            for candidate in variants {
                let candidateLower = candidate.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if candidate.count >= Self.minUnkeyedSecretLength &&
                    !Self.reservedSafeTokens.contains(candidateLower) {
                    uniqueSecrets.insert(candidate)
                }
            }
        }
        activeSecretTokens = uniqueSecrets.sorted { $0.count > $1.count }

        for rawId in subscriberIdentifiers {
            let clean = rawId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard clean.count >= 4,
                  !Self.reservedSafeTokens.contains(clean.lowercased()) else {
                continue
            }
            if !knownSubscriberSet.contains(clean) {
                if knownSubscriberSet.count >= 128 {
                    knownSubscriberSet.removeFirst()
                }
                knownSubscriberSet.append(clean)
            }
        }
        knownSubscriberSet.sort { $0.count > $1.count }
    }

    public func clearActiveSecrets() {
        secretsLock.lock()
        activeSecretTokens.removeAll()
        secretsLock.unlock()
    }

    public func log(_ level: LogLevel, tag: String, _ rawMessage: String) {
        let cleanTag = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveTag = cleanTag.isEmpty ? "APP" : cleanTag
        let scrubbed = scrubSecretsSync(rawMessage)
        let ts = formattedTimestampNow()

        var shouldScheduleCoalesce = false
        let entry: DiagnosticLogEntry = {
            bufferLock.lock()
            defer { bufferLock.unlock() }
            nextSeq += 1
            let created = DiagnosticLogEntry(
                seq: nextSeq,
                timestamp: ts,
                level: level,
                tag: effectiveTag,
                message: scrubbed
            )
            if memoryBuffer.count >= Self.maxMemoryEntries {
                memoryBuffer.removeFirst(memoryBuffer.count - Self.maxMemoryEntries + 1)
            }
            memoryBuffer.append(created)
            if !coalesceScheduled && onSnapshotUpdated != nil {
                coalesceScheduled = true
                shouldScheduleCoalesce = true
            }
            return created
        }()

        if shouldScheduleCoalesce {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                self?.deliverLatestSnapshotOnMain()
            }
        }

        let lineData = Data((entry.formatLine() + "\n").utf8)
        ioQueue.async { [weak self] in
            self?.appendLineToDisk(lineData)
        }
    }

    public func flushUiSnapshotImmediately() {
        DispatchQueue.main.async { [weak self] in
            self?.deliverLatestSnapshotOnMain()
        }
    }

    public func currentEntriesSnapshot() -> [DiagnosticLogEntry] {
        bufferLock.lock()
        defer { bufferLock.unlock() }
        return memoryBuffer
    }

    public func flushForTest() {
        ioQueue.sync {}
    }

    public func exportCombinedLogText(
        deviceHeader: String = "",
        maskSubscriberIdentifiers: Bool = true
    ) -> String {
        let combinedBody: String = ioQueue.sync {
            readDiskOrMemoryLogsLocked()
        }

        var output = ""
        let trimmedHeader = deviceHeader.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedHeader.isEmpty {
            output += trimmedHeader + "\n"
            output += "==================================================\n"
        }
        output += combinedBody

        var sanitized = scrubSecretsSync(output)
        if maskSubscriberIdentifiers {
            sanitized = maskPiiAndMacMiddleOctets(sanitized)
        }
        return sanitized
    }

    public func clearLogs() {
        bufferLock.lock()
        memoryBuffer.removeAll()
        coalesceScheduled = false
        let handler = onSnapshotUpdated
        bufferLock.unlock()

        secretsLock.lock()
        if activeSecretTokens.isEmpty {
            knownSubscriberSet.removeAll()
        }
        secretsLock.unlock()

        ioQueue.sync {
            let fm = FileManager.default
            let currentURL = logDirURL.appendingPathComponent(Self.currentLogFileName)
            let prevURL = logDirURL.appendingPathComponent(Self.prevLogFileName)
            try? fm.removeItem(at: currentURL)
            try? fm.removeItem(at: prevURL)
        }

        if let handler = handler {
            DispatchQueue.main.async {
                handler([])
            }
        }
    }

    private func deliverLatestSnapshotOnMain() {
        bufferLock.lock()
        coalesceScheduled = false
        let snapshot = memoryBuffer
        let handler = onSnapshotUpdated
        bufferLock.unlock()
        handler?(snapshot)
    }

    private func formattedTimestampNow() -> String {
        formatterLock.lock()
        defer { formatterLock.unlock() }
        return timestampFormatter.string(from: Date())
    }

    private func scrubSecretsSync(_ rawText: String) -> String {
        var masked = OntInputValidator.maskSensitiveCredentials(rawText)
        secretsLock.lock()
        let tokens = activeSecretTokens
        secretsLock.unlock()

        for secret in tokens {
            let options: String.CompareOptions = secret.contains("%") ? [.caseInsensitive] : []
            masked = masked.replacingOccurrences(of: secret, with: "***", options: options)
        }
        return masked
    }

    private func maskPiiAndMacMiddleOctets(_ text: String) -> String {
        var masked = text
        if let macRegex = try? NSRegularExpression(
            pattern: "\\b([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}):[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:([0-9A-Fa-f]{2})\\b",
            options: []
        ) {
            let range = NSRange(masked.startIndex..<masked.endIndex, in: masked)
            masked = macRegex.stringByReplacingMatches(
                in: masked,
                options: [],
                range: range,
                withTemplate: "$1:**:**:$2"
            )
        }

        secretsLock.lock()
        let subscriberTokens = knownSubscriberSet
        secretsLock.unlock()

        for token in subscriberTokens {
            let replacement: String
            if token.count >= 5 {
                replacement = "\(token.prefix(2))***\(token.suffix(2))"
            } else {
                replacement = "\(token.prefix(1))**\(token.suffix(1))"
            }
            masked = masked.replacingOccurrences(of: token, with: replacement)
        }
        return masked
    }

    private func appendLineToDisk(_ lineData: Data) {
        let fm = FileManager.default
        do {
            if !fm.fileExists(atPath: logDirURL.path) {
                try fm.createDirectory(
                    at: logDirURL,
                    withIntermediateDirectories: true,
                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
                )
                var dirValues = URLResourceValues()
                dirValues.isExcludedFromBackup = true
                var mutableDir = logDirURL
                try? mutableDir.setResourceValues(dirValues)
            }

            let currentURL = logDirURL.appendingPathComponent(Self.currentLogFileName)
            let prevURL = logDirURL.appendingPathComponent(Self.prevLogFileName)

            if fm.fileExists(atPath: currentURL.path),
               let attrs = try? fm.attributesOfItem(atPath: currentURL.path),
               let fileSize = (attrs[.size] as? NSNumber)?.int64Value,
               fileSize + Int64(lineData.count) > maxFileBytes {
                if fm.fileExists(atPath: prevURL.path) {
                    try? fm.removeItem(at: prevURL)
                }
                try? fm.moveItem(at: currentURL, to: prevURL)
            }

            if !fm.fileExists(atPath: currentURL.path) {
                fm.createFile(
                    atPath: currentURL.path,
                    contents: lineData,
                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
                )
                var fileValues = URLResourceValues()
                fileValues.isExcludedFromBackup = true
                var mutableFile = currentURL
                try? mutableFile.setResourceValues(fileValues)
            } else if let handle = try? FileHandle(forWritingTo: currentURL) {
                defer { try? handle.close() }
                _ = try? handle.seekToEnd()
                try? handle.write(contentsOf: lineData)
            }
        } catch {
            // Keep memory ring buffer authoritative if disk I/O fails
        }
    }

    private func readDiskOrMemoryLogsLocked() -> String {
        let prevURL = logDirURL.appendingPathComponent(Self.prevLogFileName)
        let currentURL = logDirURL.appendingPathComponent(Self.currentLogFileName)
        var combined = ""
        if let prevData = try? Data(contentsOf: prevURL),
           let prevText = String(data: prevData, encoding: .utf8) {
            combined += prevText
        }
        if let curData = try? Data(contentsOf: currentURL),
           let curText = String(data: curData, encoding: .utf8) {
            combined += curText
        }
        if !combined.isEmpty {
            return combined
        }

        bufferLock.lock()
        let memSnapshot = memoryBuffer
        bufferLock.unlock()
        guard !memSnapshot.isEmpty else { return "" }
        return memSnapshot.map { $0.formatLine() }.joined(separator: "\n") + "\n"
    }
}
