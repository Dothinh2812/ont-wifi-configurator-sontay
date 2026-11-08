import AudioToolbox
import Combine
import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

public struct HandoverPromptState: Equatable, Sendable {
    public let targetSsid: String
    public let targetPass: String
    public let band: WifiBand
    public let securityMode: String
    public let deadline: Date

    public init(
        targetSsid: String,
        targetPass: String,
        band: WifiBand,
        securityMode: String,
        deadline: Date
    ) {
        self.targetSsid = targetSsid
        self.targetPass = targetPass
        self.band = band
        self.securityMode = securityMode
        self.deadline = deadline
    }
}

private final class LockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: T
    init(_ value: T) { self.storage = value }
    var value: T {
        get { lock.lock(); defer { lock.unlock() }; return storage }
        set { lock.lock(); storage = newValue; lock.unlock() }
    }
    func withValue<R>(_ body: (inout T) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&storage)
    }
}

@MainActor
public final class MainViewModel: ObservableObject {

    public static func defaultEightStepsDualWifi() -> [StepProgress] {
        return [
            StepProgress(stepNumber: 1, totalSteps: 8, title: "Bước 1/8: Kiểm tra kết nối & nhận diện ONT Gateway", status: .pending),
            StepProgress(stepNumber: 2, totalSteps: 8, title: "Bước 2/8: Đăng nhập & Đổi mật khẩu Admin", status: .pending),
            StepProgress(stepNumber: 3, totalSteps: 8, title: "Bước 3/8: Cấu hình WAN PPPoE (VLAN 11)", status: .pending),
            StepProgress(stepNumber: 4, totalSteps: 8, title: "Bước 4/8: Cài đặt GPON Password (SLID / Mã MEN)", status: .pending),
            StepProgress(stepNumber: 5, totalSteps: 8, title: "Bước 5/8: Kiểm tra & Lưu cấu hình LAN IPv4", status: .pending),
            StepProgress(stepNumber: 6, totalSteps: 8, title: "Bước 6/8: Đổi tên & cấu hình Wi-Fi phụ", status: .pending),
            StepProgress(stepNumber: 7, totalSteps: 8, title: "Bước 7/8: Đợi ONT phát sóng mới & Chuyển sang Wi-Fi phụ", status: .pending),
            StepProgress(stepNumber: 8, totalSteps: 8, title: "Bước 8/8: Đổi tên & cấu hình Wi-Fi còn lại", status: .pending)
        ]
    }

    public static func defaultNineStepsWithFirmware() -> [StepProgress] {
        return [
            StepProgress(stepNumber: 1, totalSteps: 9, title: "Bước 1/9: Kiểm tra kết nối & nhận diện ONT Gateway", status: .pending),
            StepProgress(stepNumber: 2, totalSteps: 9, title: "Bước 2/9: Đăng nhập & Đổi mật khẩu Admin", status: .pending),
            StepProgress(stepNumber: 3, totalSteps: 9, title: "Bước 3/9: Cấu hình WAN PPPoE (VLAN 11)", status: .pending),
            StepProgress(stepNumber: 4, totalSteps: 9, title: "Bước 4/9: Cài đặt GPON Password (SLID / Mã MEN)", status: .pending),
            StepProgress(stepNumber: 5, totalSteps: 9, title: "Bước 5/9: Kiểm tra & Lưu cấu hình LAN IPv4", status: .pending),
            StepProgress(stepNumber: 6, totalSteps: 9, title: "Bước 6/9: Đổi tên & cấu hình Wi-Fi phụ", status: .pending),
            StepProgress(stepNumber: 7, totalSteps: 9, title: "Bước 7/9: Đợi ONT phát sóng mới & Chuyển sang Wi-Fi phụ", status: .pending),
            StepProgress(stepNumber: 8, totalSteps: 9, title: "Bước 8/9: Đổi tên & cấu hình Wi-Fi còn lại", status: .pending),
            StepProgress(stepNumber: 9, totalSteps: 9, title: "Bước 9/9: Tải lên & Cập nhật Firmware ONT (Bước cuối)", status: .pending)
        ]
    }
    @Published public var allRecords: [SubscriberRecord] = []
    @Published public var filteredRecords: [SubscriberRecord] = []
    @Published public var searchQuery: String = ""
    @Published public var selectedStatusFilter: SubscriberStatus? = nil
    @Published public var selectedRecord: SubscriberRecord? = nil
    @Published public var globalSettings: GlobalSettings = GlobalSettings()
    @Published public var autoSyncWifi5g: Bool = true

    @Published public var en0IpAddress: String = ""
    @Published public var isOnOntSubnet: Bool = false
    @Published public var hasActiveVpn: Bool = false
    @Published public var isOntGatewayOnline: Bool = false
    @Published public var connectedBand: WifiBand = .band24GHz

    @Published public var showWifiGuideSheet: Bool = false {
        didSet {
            if showWifiGuideSheet {
                suppressProbeAfterCompletion = false
            }
        }
    }
    @Published public var showExecutionSheet: Bool = false
    @Published public var isRunningSetup: Bool = false
    @Published public var isImportingFirmware: Bool = false
    @Published public var stepProgressList: [StepProgress] = MainViewModel.defaultEightStepsDualWifi()
    @Published public var handoverPrompt: HandoverPromptState? = nil
    @Published public var setupResult: MobileSetupResult? = nil
    @Published public var executionError: String? = nil
    @Published public var statusMessage: String? = nil
    @Published public var isInBackground: Bool = false
    @Published public var diagnosticLogs: [DiagnosticLogEntry] = []
    @Published public var showDiagnosticLogSheet: Bool = false
    @Published public var isJoiningCustomerWifi: Bool = false
    @Published public var customerWifiJoinStatus: String? = nil
    @Published public var connectedWifiSsid: String = ""
    public var lastQuickConnectedFactorySsid: String? = nil

    public var securityCapabilityWarning: String? {
        IosWifiMonitor.validateHandoverSecurityCapability(secMode: globalSettings.wifiSecurityMode)
    }
    public let database: AppDatabase
    public let wifiMonitor: IosWifiMonitor
    public let diagnosticLogger: AppDiagnosticLogger
    public let probePauseMutex = AsyncMutex()
    private var probeDriver: OntMobileDriver
    private var backgroundProbeTask: Task<Void, Never>?
    private var consecutiveMisses: Int = 0
    private var suppressProbeAfterCompletion: Bool = false
    private var cachedSourceExcelData: Data?
    private let backgroundFlagBox = LockedBox<Bool>(false)
    private let handoverWakeGeneration = LockedBox<Int>(0)
    #if canImport(UIKit)
    private var bgTaskId: UIBackgroundTaskIdentifier = .invalid
    #endif

    public init(
        database: AppDatabase = .shared,
        wifiMonitor: IosWifiMonitor = IosWifiMonitor(),
        diagnosticLogger: AppDiagnosticLogger = AppDiagnosticLogger(),
        startBackgroundLoops: Bool = true
    ) {
        self.database = database
        self.wifiMonitor = wifiMonitor
        self.diagnosticLogger = diagnosticLogger
        self.probeDriver = OntMobileDriver(
            baseUrl: "https://192.168.1.1",
            timeoutMs: 1200
        )
        diagnosticLogger.setOnSnapshotUpdated { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                self.diagnosticLogs = self.diagnosticLogger.currentEntriesSnapshot()
            }
        }
        cleanupStaleExportFiles()
        loadPersistedRecords()
        loadCachedSourceExcel()
        loadCachedFirmwareOnInit()

        let wakeBox = self.handoverWakeGeneration
        if startBackgroundLoops {
            wifiMonitor.startMonitoring { [weak self] _ in
                wakeBox.withValue { $0 += 1 }
                Task { @MainActor [weak self] in
                    _ = await self?.performGatewayProbeOnce()
                }
            }
            startBackgroundGatewayProbeLoop()
        }
    }

    deinit {
        backgroundProbeTask?.cancel()
        wifiMonitor.stopMonitoring()
    }

    public func loadPersistedRecords() {
        let records = database.getAll()
        updateRecordsList(records)
    }

    private func applyWifiSnapshot(_ snapshot: WifiInterfaceSnapshot) {
        en0IpAddress = snapshot.en0IpAddress
        isOnOntSubnet = snapshot.isOnOntSubnet
        hasActiveVpn = snapshot.hasActiveVpnInterface
        if !snapshot.isOnOntSubnet {
            connectedWifiSsid = ""
        }
    }

    /// Single serialized gateway probe entry point guarded by `!isRunningSetup && probePauseMutex.tryLock()`.
    @discardableResult
    public func performGatewayProbeOnce() async -> Bool {
        if isRunningSetup || suppressProbeAfterCompletion || !probePauseMutex.tryLock() {
            return isOntGatewayOnline
        }
        defer {
            probePauseMutex.unlock()
        }

        let wasOnline = isOntGatewayOnline
        let snapshot = wifiMonitor.inspectInterfaces()
        if globalSettings.baseUrl.contains("192.168.1.") && !snapshot.isOnOntSubnet {
            consecutiveMisses = 2
            applyWifiSnapshot(snapshot)
            self.isOntGatewayOnline = false
            if wasOnline {
                diagnosticLogger.log(.info, tag: "PROBE", "ONT Gateway 192.168.1.1 chuyển sang OFFLINE (en0 chưa thuộc dải 192.168.1.x).")
            }
            return false
        }
        let reachable = await probeDriver.probeGateway()

        let isOnline: Bool
        if reachable {
            consecutiveMisses = 0
            isOnline = true
        } else {
            consecutiveMisses += 1
            isOnline = (isOntGatewayOnline && consecutiveMisses < 2 && snapshot.isOnOntSubnet)
        }

        applyWifiSnapshot(snapshot)
        self.isOntGatewayOnline = isOnline
        if wasOnline != isOnline {
            diagnosticLogger.log(
                .info,
                tag: "PROBE",
                isOnline
                    ? "ONT Gateway 192.168.1.1 chuyển sang ONLINE (en0=\(snapshot.en0IpAddress))."
                    : "ONT Gateway 192.168.1.1 chuyển sang OFFLINE."
            )
        }
        return isOnline
    }

    public func openDiagnosticLogSheet() {
        guard !showExecutionSheet && !showWifiGuideSheet else { return }
        showDiagnosticLogSheet = true
    }

    public func clearDiagnosticLogs() {
        let logger = diagnosticLogger
        Task.detached(priority: .utility) {
            logger.clearLogs()
            await MainActor.run { [weak self] in
                self?.statusMessage = "Đã xóa sạch nhật ký tiến trình debug."
            }
        }
    }

    public func copyDiagnosticLogsToPasteboard() {
        let header = buildDeviceDiagnosticHeader()
        let logger = diagnosticLogger
        Task.detached(priority: .userInitiated) {
            let text = logger.exportCombinedLogText(
                deviceHeader: header,
                maskSubscriberIdentifiers: true
            )
            await MainActor.run { [weak self] in
                #if canImport(UIKit)
                UIPasteboard.general.setItems(
                    [[UIPasteboard.typeAutomatic: text]],
                    options: [
                        .localOnly: true,
                        .expirationDate: Date().addingTimeInterval(180)
                    ]
                )
                #endif
                self?.statusMessage = "Đã chép Nhật ký Debug (tự xóa khỏi bộ nhớ tạm sau 180s)."
            }
        }
    }

    public func exportDiagnosticLogToTempFile() throws -> URL {
        cleanupStaleExportFiles()
        let exportedText = diagnosticLogger.exportCombinedLogText(
            deviceHeader: buildDeviceDiagnosticHeader(),
            maskSubscriberIdentifiers: true
        )
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let fileName = "ONT_Debug_Log_\(formatter.string(from: Date())).txt"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try Data(exportedText.utf8).write(
            to: tempURL,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
        return tempURL
    }

    private func buildDeviceDiagnosticHeader() -> String {
        let settings = globalSettings
        #if canImport(UIKit)
        let sysVer = UIDevice.current.systemVersion
        let model = UIDevice.current.model
        #else
        let sysVer = "unknown"
        let model = "iOS"
        #endif
        return [
            "ONT Wi-Fi Configurator — Diagnostic Log",
            "Platform: iOS \(sysVer) (\(model)) | en0=\(en0IpAddress.isEmpty ? "none" : en0IpAddress)",
            "Timeouts: Handover=\(settings.wifiHandoverTimeoutMs)ms, Gateway=\(settings.gatewayPostHandoverTimeoutMs)ms, PreFW=\(settings.postSecondWifiSettleTimeoutMs)ms",
            "Security Mode: \(settings.wifiSecurityMode) | Firmware Update: \(settings.enableFirmwareUpdate)"
        ].joined(separator: "\n")
    }

    public func onScenePhaseActive() {
        isInBackground = false
        backgroundFlagBox.value = false
        handoverWakeGeneration.withValue { $0 += 1 }
        if isRunningSetup {
            renewBackgroundTaskIfNeeded()
        }
        Task {
            _ = await wifiMonitor.awaitWifiIpv4Ready(maxWaitMs: 1200)
            _ = await performGatewayProbeOnce()
        }
    }

    public func onScenePhaseBackground() {
        isInBackground = true
        backgroundFlagBox.value = true
    }

    private func renewBackgroundTaskIfNeeded() {
        #if canImport(UIKit)
        guard bgTaskId == .invalid else { return }
        bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "ONTTwoConnectionSetup") { [weak self] in
            Task { @MainActor [weak self] in
                self?.endActiveBackgroundTask()
            }
        }
        #endif
    }

    private func endActiveBackgroundTask() {
        #if canImport(UIKit)
        if bgTaskId != .invalid {
            UIApplication.shared.endBackgroundTask(bgTaskId)
            bgTaskId = .invalid
        }
        #endif
    }
    private func startBackgroundGatewayProbeLoop() {
        backgroundProbeTask?.cancel()
        backgroundProbeTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { break }
                let online = await self.performGatewayProbeOnce()
                let sleepNs: UInt64 = online ? 2_000_000_000 : 1_000_000_000
                try? await Task.sleep(nanoseconds: sleepNs)
            }
        }
    }

    // MARK: - Excel Import / Demo / Export

    public func importExcelData(_ data: Data) {
        do {
            saveCachedSourceExcel(data)
            let incoming = try ExcelManager.parseExcel(
                data: data,
                defaultWifi5Ssid: globalSettings.defaultWifi5Ssid,
                defaultWifi5Pass: globalSettings.defaultWifi5Pass
            )
            let merged = database.upsertPreservingEdits(records: incoming)
            let missingCount = merged.filter { !$0.isValid }.count
            let validCount = merged.count - missingCount
            let msg = "Đã nạp \(merged.count) thuê bao (\(validCount) hợp lệ, \(missingCount) cần bổ sung TT)."
            updateRecordsList(merged, message: msg)
        } catch {
            statusMessage = "Lỗi nạp file Excel: \(error.localizedDescription)"
        }
    }

    public func loadDemoExcelFromBundle() {
        let bundle = Bundle.main
        let url = bundle.url(forResource: ExcelManager.demoAssetFileName, withExtension: "xlsx")
            ?? bundle.url(forResource: ExcelManager.demoAssetFileName, withExtension: "xlsx", subdirectory: "Resources")
        guard let validURL = url, let data = try? Data(contentsOf: validURL) else {
            statusMessage = "Không tìm thấy tệp mẫu sample-demo.xlsx trong Bundle."
            return
        }
        do {
            saveCachedSourceExcel(data)
            let incoming = try ExcelManager.parseExcel(
                data: data,
                defaultWifi5Ssid: globalSettings.defaultWifi5Ssid,
                defaultWifi5Pass: globalSettings.defaultWifi5Pass
            )
            let merged = database.upsertPreservingEdits(records: incoming)
            updateRecordsList(merged, message: "Đã nạp \(merged.count) bản ghi mẫu Demo ẩn danh (an toàn PII).")
        } catch {
            statusMessage = "Lỗi nạp dữ liệu mẫu Demo: \(error.localizedDescription)"
        }
    }

    public func exportExcelReportToTempFile() throws -> URL {
        cleanupStaleExportFiles()
        let exportedData = try ExcelManager.exportResults(
            sourceExcelData: cachedSourceExcelData,
            records: allRecords
        )
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let fileName = "KetQua_CauHinh_ONT_\(formatter.string(from: Date())).xlsx"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try exportedData.write(
            to: tempURL,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
        return tempURL
    }
    // MARK: - Search, Filter & Record Editing

    public func onSearchQueryChange(_ query: String) {
        searchQuery = query
        filteredRecords = ExcelManager.searchSubscribers(
            records: allRecords,
            query: query,
            statusFilter: selectedStatusFilter
        )
    }

    public func onStatusFilterChange(_ filter: SubscriberStatus?) {
        selectedStatusFilter = filter
        filteredRecords = ExcelManager.searchSubscribers(
            records: allRecords,
            query: searchQuery,
            statusFilter: filter
        )
    }

    public func selectSubscriber(_ record: SubscriberRecord?) {
        if let rec = record {
            suppressProbeAfterCompletion = false
            selectedRecord = allRecords.first(where: { $0.rowIndex == rec.rowIndex }) ?? rec
        } else {
            selectedRecord = nil
        }
    }

    public func setAutoSyncWifi5g(_ enabled: Bool) {
        autoSyncWifi5g = enabled
        guard enabled, var cur = selectedRecord else { return }
        let candidate5g = "\(cur.ssid24g.trimmingCharacters(in: .whitespacesAndNewlines)) 5G"
        cur.ssid5g = candidate5g.count > 32
            ? String(candidate5g.prefix(32)).trimmingCharacters(in: .whitespacesAndNewlines)
            : candidate5g
        cur.pass5g = cur.pass24g
        updateSelectedRecord(cur)
    }

    public func updateSelectedRecord(_ updated: SubscriberRecord) {
        let importCheck = OntInputValidator.validateOnImport(updated)
        var normalized = updated
        normalized.isValid = importCheck.isValid
        normalized.validationErrors = importCheck.errors
        if normalized.status != .success {
            normalized.status = importCheck.isValid ? .pending : .invalidData
        }

        selectedRecord = normalized
        allRecords = allRecords.map { $0.rowIndex == normalized.rowIndex ? normalized : $0 }
        filteredRecords = ExcelManager.searchSubscribers(
            records: allRecords,
            query: searchQuery,
            statusFilter: selectedStatusFilter
        )
        database.update(record: normalized)
    }

    public func prependLeadingZeroOnSelectedRecord() {
        guard let current = selectedRecord else { return }
        let fixed = OntInputValidator.prependLeadingZeroToWifiPass(current, sync5g: autoSyncWifi5g)
        updateSelectedRecord(fixed)
        saveSelectedRecordChanges()
    }

    public func saveSelectedRecordChanges() {
        guard let current = selectedRecord else { return }
        database.update(record: current)
        let refreshed = database.getAll()
        updateRecordsList(refreshed, message: "Đã lưu thông tin thuê bao \(current.accountPppoe).")
    }

    public func updateGlobalSettings(_ newSettings: GlobalSettings) {
        let urlChanged = globalSettings.baseUrl != newSettings.baseUrl
        let fwToggleChanged = globalSettings.enableFirmwareUpdate != newSettings.enableFirmwareUpdate
        globalSettings = newSettings
        if fwToggleChanged && !isRunningSetup {
            stepProgressList = newSettings.enableFirmwareUpdate
                ? Self.defaultNineStepsWithFirmware()
                : Self.defaultEightStepsDualWifi()
        }
        if urlChanged {
            probeDriver = OntMobileDriver(
                baseUrl: newSettings.baseUrl,
                timeoutMs: 1200
            )
        }
    }

    // MARK: - Streaming Firmware File Import (32 KB chunks, Security-Scoped Resource Safe)

    private func firmwareCacheDirectoryURL() -> URL {
        return database.databaseURL
            .deletingLastPathComponent()
            .appendingPathComponent("firmware_cache", isDirectory: true)
    }

    private func loadCachedFirmwareOnInit() {
        let fwDir = firmwareCacheDirectoryURL()
        guard let items = try? FileManager.default.contentsOfDirectory(
            at: fwDir,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        let validFiles = items.compactMap { url -> (URL, Date, Int64)? in
            guard OntInputValidator.isValidFirmwareExtension(url.lastPathComponent),
                  let vals = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey]),
                  let size = vals.fileSize.map(Int64.init),
                  (OntInputValidator.minFirmwareSizeBytes...OntInputValidator.maxFirmwareSizeBytes).contains(size) else {
                return nil
            }
            return (url, vals.contentModificationDate ?? .distantPast, size)
        }
        guard let latest = validFiles.max(by: { $0.1 < $1.1 }),
              globalSettings.firmwareFilePath.isEmpty else {
            return
        }
        globalSettings.firmwareFilePath = latest.0.path
        globalSettings.firmwareFileName = latest.0.lastPathComponent
        globalSettings.firmwareFileSize = latest.2
    }

    public func importFirmwareFile(from sourceURL: URL) {
        guard !isRunningSetup && !isImportingFirmware else { return }
        isImportingFirmware = true
        statusMessage = "Đang kiểm tra và sao chép file Firmware..."

        let destDir = firmwareCacheDirectoryURL()
        Task {
            do {
                let imported = try await Task.detached(priority: .userInitiated) { () -> (destURL: URL, displayName: String, sizeBytes: Int64) in
                    let accessed = sourceURL.startAccessingSecurityScopedResource()
                    defer {
                        if accessed {
                            sourceURL.stopAccessingSecurityScopedResource()
                        }
                    }

                    let rawName = sourceURL.lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
                    let displayName = rawName.isEmpty ? "firmware.signed" : rawName
                    guard OntInputValidator.isValidFirmwareExtension(displayName) else {
                        throw NSError(
                            domain: "FirmwareImport",
                            code: 1,
                            userInfo: [NSLocalizedDescriptionKey: "Định dạng file '\(displayName)' không hợp lệ (chỉ hỗ trợ .signed, .bin, .img)."]
                        )
                    }

                    let fm = FileManager.default
                    try fm.createDirectory(
                        at: destDir,
                        withIntermediateDirectories: true,
                        attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
                    )
                    var dirVals = URLResourceValues()
                    dirVals.isExcludedFromBackup = true
                    var mutableDir = destDir
                    try? mutableDir.setResourceValues(dirVals)

                    let tmpURL = fm.temporaryDirectory.appendingPathComponent("fw_import_\(UUID().uuidString).tmp")
                    fm.createFile(
                        atPath: tmpURL.path,
                        contents: nil,
                        attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
                    )
                    defer {
                        try? fm.removeItem(at: tmpURL)
                    }

                    guard let inStream = InputStream(url: sourceURL),
                          let outStream = OutputStream(url: tmpURL, append: false) else {
                        throw NSError(
                            domain: "FirmwareImport",
                            code: 2,
                            userInfo: [NSLocalizedDescriptionKey: "Không thể mở luồng đọc/ghi file Firmware."]
                        )
                    }
                    inStream.open()
                    outStream.open()
                    defer {
                        inStream.close()
                        outStream.close()
                    }

                    let chunkSize = 32 * 1024
                    var buffer = [UInt8](repeating: 0, count: chunkSize)
                    var totalBytes: Int64 = 0

                    while inStream.hasBytesAvailable {
                        let readCount = inStream.read(&buffer, maxLength: chunkSize)
                        if readCount > 0 {
                            totalBytes += Int64(readCount)
                            if totalBytes > OntInputValidator.maxFirmwareSizeBytes {
                                throw NSError(
                                    domain: "FirmwareImport",
                                    code: 3,
                                    userInfo: [NSLocalizedDescriptionKey: "File Firmware vượt quá giới hạn tối đa 64 MB."]
                                )
                            }
                            var offset = 0
                            while offset < readCount {
                                let written = buffer.withUnsafeBufferPointer { ptr in
                                    outStream.write(ptr.baseAddress!.advanced(by: offset), maxLength: readCount - offset)
                                }
                                if written <= 0 {
                                    throw outStream.streamError ?? NSError(
                                        domain: "FirmwareImport",
                                        code: 4,
                                        userInfo: [NSLocalizedDescriptionKey: "Lỗi ghi file Firmware vào bộ nhớ đệm."]
                                    )
                                }
                                offset += written
                            }
                        } else if readCount < 0 {
                            throw inStream.streamError ?? NSError(
                                domain: "FirmwareImport",
                                code: 5,
                                userInfo: [NSLocalizedDescriptionKey: "Lỗi đọc file Firmware đã chọn."]
                            )
                        } else {
                            break
                        }
                    }

                    guard (OntInputValidator.minFirmwareSizeBytes...OntInputValidator.maxFirmwareSizeBytes).contains(totalBytes) else {
                        throw NSError(
                            domain: "FirmwareImport",
                            code: 6,
                            userInfo: [NSLocalizedDescriptionKey: "Dung lượng file Firmware không hợp lệ (\(totalBytes / 1024) KB — yêu cầu 64 KB đến 64 MB)."]
                        )
                    }

                    let safeName = OntInputValidator.sanitizeFirmwareFilename(displayName)
                    let destURL = destDir.appendingPathComponent(safeName)
                    if let existingItems = try? fm.contentsOfDirectory(at: destDir, includingPropertiesForKeys: nil) {
                        for item in existingItems where item.path != destURL.path {
                            try? fm.removeItem(at: item)
                        }
                    }
                    if fm.fileExists(atPath: destURL.path) {
                        try? fm.removeItem(at: destURL)
                    }
                    try fm.moveItem(at: tmpURL, to: destURL)
                    try? fm.setAttributes(
                        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                        ofItemAtPath: destURL.path
                    )
                    var destVals = URLResourceValues()
                    destVals.isExcludedFromBackup = true
                    var mutableDest = destURL
                    try? mutableDest.setResourceValues(destVals)

                    return (destURL, displayName, totalBytes)
                }.value

                var updatedSettings = globalSettings
                updatedSettings.enableFirmwareUpdate = true
                updatedSettings.firmwareFilePath = imported.destURL.path
                updatedSettings.firmwareFileName = imported.displayName
                updatedSettings.firmwareFileSize = imported.sizeBytes
                globalSettings = updatedSettings
                if !isRunningSetup {
                    stepProgressList = Self.defaultNineStepsWithFirmware()
                }
                isImportingFirmware = false
                let sizeMb = String(format: "%.2f", Double(imported.sizeBytes) / (1024.0 * 1024.0))
                statusMessage = "Đã nạp file Firmware: \(imported.displayName) (\(sizeMb) MB)"
            } catch {
                isImportingFirmware = false
                statusMessage = "Lỗi chọn file Firmware: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Two-Connection 8/9-Step Execution with Background Task & Hybrid Handover

    public func runOntConfiguration() {
        guard !isRunningSetup && !isImportingFirmware else { return }
        guard let record = selectedRecord else { return }
        let settings = globalSettings
        if let secErr = IosWifiMonitor.validateHandoverSecurityCapability(secMode: settings.wifiSecurityMode) {
            statusMessage = "Chưa thể RUN: \(secErr)"
            return
        }
        let preRun = OntInputValidator.validateBeforeRun(record, settings: settings)
        guard preRun.isValid else {
            statusMessage = "Chưa thể RUN: \(preRun.errors.first ?? "Dữ liệu chưa hợp lệ")"
            return
        }

        suppressProbeAfterCompletion = false
        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "HH:mm:ss"
        let startTimeStr = timeFormatter.string(from: Date())

        showExecutionSheet = true
        isRunningSetup = true
        handoverPrompt = nil
        stepProgressList = settings.enableFirmwareUpdate
            ? Self.defaultNineStepsWithFirmware()
            : Self.defaultEightStepsDualWifi()
        setupResult = nil
        executionError = nil

        var runningRecord = record
        runningRecord.startTime = startTimeStr
        database.update(record: runningRecord)

        let activeBand = connectedBand

        Task {
            #if canImport(UIKit)
            UIApplication.shared.isIdleTimerDisabled = true
            self.renewBackgroundTaskIfNeeded()
            defer {
                UIApplication.shared.isIdleTimerDisabled = false
                self.endActiveBackgroundTask()
            }
            #endif

            let diagLogger = self.diagnosticLogger
            diagLogger.registerActiveSecrets(
                [
                    settings.adminDefaultPass,
                    settings.adminNewPass,
                    record.lastKnownAdminPass,
                    record.passPppoe,
                    record.effectiveWifi24Pass(secMode: settings.wifiSecurityMode),
                    record.effectiveWifi5Pass(secMode: settings.wifiSecurityMode),
                    settings.defaultWifi5Pass
                ],
                subscriberIdentifiers: [record.accountPppoe, record.slid]
            )
            defer {
                diagLogger.flushUiSnapshotImmediately()
                diagLogger.clearActiveSecrets()
                if let lastSsid = self.lastQuickConnectedFactorySsid {
                    self.wifiMonitor.removeTemporaryOpenWifiIfNeeded(ssid: lastSsid)
                    self.lastQuickConnectedFactorySsid = nil
                }
            }
            diagLogger.log(
                .info,
                tag: "RUN",
                "=== BẮT ĐẦU PHIÊN CẤU HÌNH ONT iOS: account=\(record.accountPppoe), SLID=\(record.slid), SSID_24='\(record.effectiveWifi24Ssid)', SSID_5='\(record.effectiveWifi5Ssid)', sec=\(settings.wifiSecurityMode), fw=\(settings.enableFirmwareUpdate) ==="
            )

            var snapshot = self.wifiMonitor.inspectInterfaces()
            if settings.baseUrl.contains("192.168.1.") && !snapshot.isOnOntSubnet {
                snapshot = await self.wifiMonitor.awaitWifiIpv4Ready(maxWaitMs: 1200)
            }
            self.applyWifiSnapshot(snapshot)

            if settings.baseUrl.contains("192.168.1.") && !snapshot.isOnOntSubnet {
                let vpnHint = snapshot.hasActiveVpnInterface
                    ? " Phát hiện đang bật VPN (1.1.1.1 / WireGuard) — vui lòng tắt VPN."
                    : ""
                let noSubnetMsg =
                    "iPhone chưa có địa chỉ IP 192.168.1.x trên card Wi-Fi (en0). Vui lòng kết nối Wi-Fi ONT trong Cài đặt iOS trước khi bấm RUN.\(vpnHint)"
                diagLogger.log(.error, tag: "RUN", noSubnetMsg)
                self.isRunningSetup = false
                self.executionError = noSubnetMsg
                self.statusMessage = noSubnetMsg
                self.notifyCompletionFeedback(success: false)
                return
            }

            let latestAdminPassBox = LockedBox<String>(record.lastKnownAdminPass)
            let bgBox = self.backgroundFlagBox
            let wakeBox = self.handoverWakeGeneration
            let monitorRef = self.wifiMonitor
            let dbRef = self.database
            let localIpv4 = snapshot.en0IpAddress
            let handoverAttemptBox = LockedBox<Int>(0)
            let lastObservedEn0Box = LockedBox<String>(localIpv4)
            let joinedOpenSsidBox = LockedBox<String>("")
            let entitlementModeBox = LockedBox<Bool?>(nil)

            defer {
                let openSsid = joinedOpenSsidBox.value
                if !openSsid.isEmpty {
                    monitorRef.removeTemporaryOpenWifiIfNeeded(ssid: openSsid, securityMode: settings.wifiSecurityMode)
                }
                self.handoverPrompt = nil
            }

            let driver = OntMobileDriver(
                baseUrl: settings.baseUrl,
                timeoutMs: settings.requestTimeoutMs,
                settlingDelayMs: 200,
                probePauseMutex: self.probePauseMutex,
                onAdminPasswordChanged: { newAdminPass in
                    latestAdminPassBox.value = newAdminPass
                    var updatedWithPass = record
                    updatedWithPass.startTime = startTimeStr
                    updatedWithPass.lastKnownAdminPass = newAdminPass
                    dbRef.update(record: updatedWithPass)
                },
                isAppInBackground: {
                    bgBox.value
                },
                isNetworkTransportAlive: {
                    monitorRef.inspectInterfaces().isOnOntSubnet
                },
                onLog: { level, tag, message in
                    diagLogger.log(level, tag: tag, message)
                },
                onSwitchToSecondaryWifi: { [weak self] targetSsid, targetPass, targetBand, targetSecMode, _, timeoutMs, onDetail in
                    let attemptIdx = handoverAttemptBox.withValue { val -> Int in
                        val += 1
                        return val
                    }
                    let deadline = Date().addingTimeInterval(Double(max(timeoutMs, 1000)) / 1000.0)
                    let startWakeGen = wakeBox.value
                    let priorIp = lastObservedEn0Box.value

                    if attemptIdx == 1 {
                        onDetail("Đang đợi ONT khởi động lại sóng & ổn định dịch vụ cho '\(targetSsid)' (\(targetBand.label)) — chờ 8s...")
                        diagLogger.log(.info, tag: "HANDOVER", "Bước 7 lượt 1: chờ lắng đầu 8s để ONT khởi động wlmngr + udhcpd cho '\(targetSsid)' (\(targetBand.label)).")
                        let initialSettleMs = min(8_000, max(500, timeoutMs / 4))
                        try await Task.sleep(nanoseconds: UInt64(initialSettleMs) * 1_000_000)
                    }
                    if let lastSsid = self.lastQuickConnectedFactorySsid {
                        self.wifiMonitor.removeTemporaryOpenWifiIfNeeded(ssid: lastSsid)
                        self.lastQuickConnectedFactorySsid = nil
                    }


                    // [Red Team RT-01] Entitled auto-join branch (6s -> 12s -> 18s spaced retries) vs Sideloadly manual flow
                    if entitlementModeBox.value != false {
                        let autoBackoffsMs = [6_000, 12_000, 18_000]
                        var autoJoinRound = 0
                        while Date() < deadline {
                            try Task.checkCancellation()
                            autoJoinRound += 1
                            if bgBox.value {
                                break
                            }
                            let remMs = max(1_000, Int(deadline.timeIntervalSinceNow * 1000.0))
                            let dhcpBudgetMs = min(15_000, max(1_500, remMs - 500))
                            onDetail("Đang ghép nối tự động Wi-Fi '\(targetSsid)' (\(targetBand.label), lượt \(autoJoinRound))...")
                            let joinResult = await monitorRef.tryJoinWifiDetailed(
                                ssid: targetSsid,
                                passphrase: targetPass,
                                securityMode: targetSecMode,
                                dhcpWaitMs: dhcpBudgetMs,
                                onOpenProfileApplied: {
                                    joinedOpenSsidBox.value = targetSsid
                                }
                            )

                            switch joinResult {
                            case .joined(let readySnap):
                                entitlementModeBox.value = true
                                lastObservedEn0Box.value = readySnap.en0IpAddress
                                diagLogger.log(.info, tag: "HANDOVER", "Ghép nối tự động '\(targetSsid)' thành công (en0=\(readySnap.en0IpAddress)).")
                                return readySnap.en0IpAddress

                            case .entitlementUnavailable(let reason):
                                entitlementModeBox.value = false
                                diagLogger.log(.info, tag: "HANDOVER", reason)

                            case .transientFailure(let reason):
                                entitlementModeBox.value = true
                                diagLogger.log(.warn, tag: "HANDOVER", "Lượt auto-join #\(autoJoinRound) chưa sẵn sàng: \(reason)")
                                let backoffTargetMs = autoBackoffsMs[min(autoJoinRound - 1, autoBackoffsMs.count - 1)]
                                let remSleepMs = Int(deadline.timeIntervalSinceNow * 1000.0)
                                if remSleepMs <= 1_000 {
                                    break
                                }
                                let actualBackoffMs = min(backoffTargetMs, remSleepMs)
                                var sleptMs = 0
                                while sleptMs < actualBackoffMs && Date() < deadline {
                                    try Task.checkCancellation()
                                    let snap = monitorRef.inspectInterfaces()
                                    if snap.isOnOntSubnet && (snap.en0IpAddress != priorIp || wakeBox.value != startWakeGen) {
                                        lastObservedEn0Box.value = snap.en0IpAddress
                                        diagLogger.log(.info, tag: "HANDOVER", "Phát hiện iOS tự hoàn tất chuyển mạng sang en0=\(snap.en0IpAddress) trong lúc nghỉ giãn cách.")
                                        return snap.en0IpAddress
                                    }
                                    if sleptMs % 1_000 == 0 {
                                        let secLeft = max(1, (actualBackoffMs - sleptMs + 999) / 1_000)
                                        onDetail("ONT đang ổn định dịch vụ '\(targetSsid)' — tự động kết nối lại sau \(secLeft)s...")
                                    }
                                    let stepMs = min(250, actualBackoffMs - sleptMs)
                                    try await Task.sleep(nanoseconds: UInt64(stepMs) * 1_000_000)
                                    sleptMs += stepMs
                                }
                            }

                            if entitlementModeBox.value == false {
                                break
                            }
                        }
                    }

                    if entitlementModeBox.value == false {
                        let requiresPass = WifiSecurityMode(rawValue: targetSecMode)?.requiresPassword ?? true
                        await MainActor.run { [weak self] in
                            guard let self = self else { return }
                            let firstPrompt = (self.handoverPrompt == nil)
                            self.handoverPrompt = HandoverPromptState(
                                targetSsid: targetSsid,
                                targetPass: targetPass,
                                band: targetBand,
                                securityMode: targetSecMode,
                                deadline: deadline
                            )
                            #if canImport(UIKit)
                            if firstPrompt && requiresPass && !targetPass.isEmpty {
                                UIPasteboard.general.setItems(
                                    [[UIPasteboard.typeAutomatic: targetPass]],
                                    options: [
                                        .localOnly: true,
                                        .expirationDate: Date().addingTimeInterval(120)
                                    ]
                                )
                            }
                            #endif
                        }
                    }

                    // [Red Team RT-01 & RT-07] Never return Band A's unchanged IP at 0ms or 3s!
                    // Wait for ipChanged, wakeChanged (NWPathMonitor / return from iOS Settings), or 15s foreground check.
                    var waitedForegroundTicks = 0
                    while Date() < deadline {
                        try Task.checkCancellation()
                        let snap = monitorRef.inspectInterfaces()
                        let wakeChanged = wakeBox.value != startWakeGen
                        let ipChanged = snap.isOnOntSubnet && snap.en0IpAddress != priorIp

                        if snap.isOnOntSubnet && (ipChanged || wakeChanged || (!bgBox.value && waitedForegroundTicks >= 60)) {
                            lastObservedEn0Box.value = snap.en0IpAddress
                            diagLogger.log(.info, tag: "HANDOVER", "Kiểm tra kết nối Lần 2 trên en0=\(snap.en0IpAddress) (ipChanged=\(ipChanged), wakeChanged=\(wakeChanged)).")
                            return snap.en0IpAddress
                        }
                        try await Task.sleep(nanoseconds: 250_000_000)
                        if !bgBox.value {
                            waitedForegroundTicks += 1
                        }
                    }

                    let finalSnap = await monitorRef.awaitWifiIpv4Ready(maxWaitMs: 1_500)
                    if finalSnap.isOnOntSubnet && (finalSnap.en0IpAddress != priorIp || wakeBox.value != startWakeGen) {
                        lastObservedEn0Box.value = finalSnap.en0IpAddress
                        return finalSnap.en0IpAddress
                    }

                    throw OntNetworkError(
                        "Hết thời gian chờ (\(timeoutMs / 1000)s) kết nối sang Wi-Fi mới '\(targetSsid)' (\(targetBand.label)) trong Cài đặt iOS."
                    )
                }
            )
            do {
                let result = try await driver.executeMobileSetup(
                    record: record,
                    settings: settings,
                    connectedBand: activeBand,
                    localWifiIpv4: localIpv4,
                    onBandResolved: { [weak self] resolvedBand, _ in
                        Task { @MainActor [weak self] in
                            self?.connectedBand = resolvedBand
                        }
                    }
                ) { [weak self] stepUpdate in
                    Task { @MainActor [weak self] in
                        guard let self = self else { return }
                        if stepUpdate.stepNumber == 7 && stepUpdate.status == .success {
                            self.handoverPrompt = nil
                        }
                        let exists = self.stepProgressList.contains { $0.stepNumber == stepUpdate.stepNumber }
                        if exists {
                            self.stepProgressList = self.stepProgressList.map { existing in
                                existing.stepNumber == stepUpdate.stepNumber ? stepUpdate : existing
                            }
                        } else {
                            self.stepProgressList = (self.stepProgressList + [stepUpdate]).sorted { $0.stepNumber < $1.stepNumber }
                        }
                    }
                }

                let endTimeStr = timeFormatter.string(from: Date())
                var completed = record
                completed.status = .success
                completed.isValid = true
                completed.validationErrors = []
                completed.startTime = startTimeStr
                completed.endTime = endTimeStr
                completed.errorMessage = ""
                completed.lastKnownAdminPass = settings.adminNewPass

                database.update(record: completed)
                let all = database.getAll()
                updateRecordsList(all, message: result.message)

                diagLogger.log(.info, tag: "RUN", "=== HOÀN TẤT THÀNH CÔNG: \(result.message) ===")
                suppressProbeAfterCompletion = true
                handoverPrompt = nil
                isRunningSetup = false
                setupResult = result
                selectedRecord = completed
                isOntGatewayOnline = false
                notifyCompletionFeedback(success: true)
            } catch {
                let endTimeStr = timeFormatter.string(from: Date())
                let maskedErr = OntInputValidator.maskSensitiveCredentials(error.localizedDescription)
                diagLogger.log(.error, tag: "RUN", "=== PHIÊN CẤU HÌNH THẤT BẠI: \(maskedErr) ===")

                var failed = record
                failed.status = .failed
                failed.startTime = startTimeStr
                failed.endTime = endTimeStr
                failed.errorMessage = maskedErr
                failed.lastKnownAdminPass = latestAdminPassBox.value

                database.update(record: failed)
                let all = database.getAll()
                updateRecordsList(all, message: "Lỗi cấu hình: \(maskedErr)")

                stepProgressList = stepProgressList.map { st in
                    if st.status == .running {
                        var failedStep = st
                        failedStep.status = .failed
                        failedStep.detail = maskedErr
                        return failedStep
                    }
                    return st
                }
                handoverPrompt = nil
                isRunningSetup = false
                executionError = maskedErr
                selectedRecord = failed
                notifyCompletionFeedback(success: false)
            }
        }
    }

    public func dismissExecutionSheet() {
        guard !isRunningSetup else { return }
        showExecutionSheet = false
    }

    // MARK: - Post-Setup Customer Wi-Fi & Quick Factory ONT Connection

    @MainActor
    public func joinNewlyConfiguredWifi(ssid: String, pass: String) {
        guard !isJoiningCustomerWifi && !isRunningSetup else { return }
        isJoiningCustomerWifi = true
        suppressProbeAfterCompletion = false
        customerWifiJoinStatus = "Đang kết nối vào Wi-Fi mới '\(ssid)'..."

        let res = setupResult
        let secMode = res?.wifiSecurityMode ?? globalSettings.wifiSecurityMode
        let diagLogger = self.diagnosticLogger
        diagLogger.registerActiveSecrets(
            [pass, res?.newPass24g ?? "", res?.newPass5g ?? ""],
            subscriberIdentifiers: [res?.accountPppoe ?? "", res?.slid ?? ""]
        )

        Task {
            defer {
                self.isJoiningCustomerWifi = false
                if !self.isRunningSetup {
                    diagLogger.clearActiveSecrets()
                }
            }

            let joinResult = await self.wifiMonitor.tryJoinWifiDetailed(
                ssid: ssid,
                passphrase: pass,
                securityMode: secMode,
                dhcpWaitMs: 15_000,
                stabilityHoldMs: 1_200,
                joinOnce: false
            )

            switch joinResult {
            case .joined(let snap):
                self.applyWifiSnapshot(snap)
                self.connectedWifiSsid = ssid
                self.customerWifiJoinStatus = "Đã kết nối thành công vào '\(ssid)'!"
                self.statusMessage = "Đã kết nối thành công vào Wi-Fi mới '\(ssid)'."
                _ = await self.performGatewayProbeOnce()

            case .entitlementUnavailable(let reason):
                self.customerWifiJoinStatus = reason
                self.statusMessage = reason
                #if canImport(UIKit)
                if !pass.isEmpty {
                    UIPasteboard.general.setItems(
                        [[UIPasteboard.typeAutomatic: pass]],
                        options: [
                            .localOnly: true,
                            .expirationDate: Date().addingTimeInterval(120)
                        ]
                    )
                }
                #endif

            case .transientFailure(let reason):
                self.customerWifiJoinStatus = "Chưa thể kết nối: \(reason)"
                self.statusMessage = "Chưa thể kết nối vào '\(ssid)': \(reason)"
            }
        }
    }

    @MainActor
    public func quickConnectOntWifi(ssid: String) async -> HotspotJoinResult {
        suppressProbeAfterCompletion = false
        connectedBand = IosWifiMonitor.detectBandFromSsidHint(ssid)
        lastQuickConnectedFactorySsid = ssid

        let result = await wifiMonitor.tryJoinWifiDetailed(
            ssid: ssid,
            passphrase: "",
            securityMode: WifiSecurityMode.open.rawValue,
            dhcpWaitMs: 15_000,
            stabilityHoldMs: 1_200,
            joinOnce: true
        )

        if case .joined(let snap) = result {
            applyWifiSnapshot(snap)
            connectedWifiSsid = ssid
            _ = await performGatewayProbeOnce()
        }

        return result
    }

    private func notifyCompletionFeedback(success: Bool) {
        AudioServicesPlaySystemSound(success ? 1057 : 1053)
        #if canImport(UIKit)
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(success ? .success : .error)
        #endif
    }

    private func updateRecordsList(_ records: [SubscriberRecord], message: String? = nil) {
        allRecords = records
        filteredRecords = ExcelManager.searchSubscribers(
            records: records,
            query: searchQuery,
            statusFilter: selectedStatusFilter
        )
        if let sel = selectedRecord {
            selectedRecord = records.first(where: { $0.rowIndex == sel.rowIndex }) ?? sel
        }
        if let msg = message {
            statusMessage = msg
        }
    }

    private func sourceCacheFileURL() -> URL {
        return database.databaseURL
            .deletingLastPathComponent()
            .appendingPathComponent(ExcelManager.sourceCacheFileName)
    }

    private func saveCachedSourceExcel(_ data: Data) {
        cachedSourceExcelData = data
        let url = sourceCacheFileURL()
        try? data.write(
            to: url,
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try? mutableURL.setResourceValues(values)
    }

    private func loadCachedSourceExcel() {
        let url = sourceCacheFileURL()
        cachedSourceExcelData = try? Data(contentsOf: url)
    }

    private func cleanupStaleExportFiles() {
        let tmpDir = FileManager.default.temporaryDirectory
        guard let items = try? FileManager.default.contentsOfDirectory(
            at: tmpDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return
        }
        for item in items {
            let name = item.lastPathComponent
            let ext = item.pathExtension.lowercased()
            let isStaleExcel = name.hasPrefix("KetQua_CauHinh_ONT_") && ext == "xlsx"
            let isStaleLog = name.hasPrefix("ONT_Debug_Log_") && ext == "txt"
            if isStaleExcel || isStaleLog {
                try? FileManager.default.removeItem(at: item)
            }
        }
    }
}
