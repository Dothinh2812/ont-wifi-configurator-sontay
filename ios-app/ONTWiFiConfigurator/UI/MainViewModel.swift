import AudioToolbox
import Combine
import Foundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

private final class LockedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: T
    init(_ value: T) { self.storage = value }
    var value: T {
        get { lock.lock(); defer { lock.unlock() }; return storage }
        set { lock.lock(); storage = newValue; lock.unlock() }
    }
}

@MainActor
public final class MainViewModel: ObservableObject {

    public static func defaultSevenSteps() -> [StepProgress] {
        return [
            StepProgress(stepNumber: 1, totalSteps: 7, title: "Bước 1/7: Kiểm tra kết nối & nhận diện ONT Gateway", status: .pending),
            StepProgress(stepNumber: 2, totalSteps: 7, title: "Bước 2/7: Đăng nhập & Đổi mật khẩu Admin", status: .pending),
            StepProgress(stepNumber: 3, totalSteps: 7, title: "Bước 3/7: Cấu hình WAN PPPoE (VLAN 11)", status: .pending),
            StepProgress(stepNumber: 4, totalSteps: 7, title: "Bước 4/7: Cài đặt GPON Password (SLID / Mã MEN)", status: .pending),
            StepProgress(stepNumber: 5, totalSteps: 7, title: "Bước 5/7: Kiểm tra & Lưu cấu hình LAN IPv4", status: .pending),
            StepProgress(stepNumber: 6, totalSteps: 7, title: "Bước 6/7: Cấu hình Wi-Fi băng tần phụ", status: .pending),
            StepProgress(stepNumber: 7, totalSteps: 7, title: "Bước 7/7: Đổi tên & mật khẩu Wi-Fi băng tần kết nối (Bước cuối)", status: .pending)
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
    @Published public var stepProgressList: [StepProgress] = MainViewModel.defaultSevenSteps()
    @Published public var setupResult: MobileSetupResult? = nil
    @Published public var executionError: String? = nil
    @Published public var statusMessage: String? = nil
    @Published public var isInBackground: Bool = false

    public let database: AppDatabase
    public let wifiMonitor: IosWifiMonitor
    public let probePauseMutex = AsyncMutex()

    private var probeDriver: OntMobileDriver
    private var backgroundProbeTask: Task<Void, Never>?
    private var consecutiveMisses: Int = 0
    private var suppressProbeAfterCompletion: Bool = false
    private var cachedSourceExcelData: Data?
    private let backgroundFlagBox = LockedBox<Bool>(false)
    public init(
        database: AppDatabase = .shared,
        wifiMonitor: IosWifiMonitor = IosWifiMonitor(),
        startBackgroundLoops: Bool = true
    ) {
        self.database = database
        self.wifiMonitor = wifiMonitor
        self.probeDriver = OntMobileDriver(
            baseUrl: "https://192.168.1.1",
            timeoutMs: 1200
        )

        loadPersistedRecords()
        loadCachedSourceExcel()

        if startBackgroundLoops {
            wifiMonitor.startMonitoring { [weak self] _ in
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

        let snapshot = wifiMonitor.inspectInterfaces()
        if globalSettings.baseUrl.contains("192.168.1.") && !snapshot.isOnOntSubnet {
            consecutiveMisses = 2
            applyWifiSnapshot(snapshot)
            self.isOntGatewayOnline = false
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
        return isOnline
    }

    public func onScenePhaseActive() {
        isInBackground = false
        backgroundFlagBox.value = false
        Task {
            _ = await wifiMonitor.awaitWifiIpv4Ready(maxWaitMs: 1200)
            _ = await performGatewayProbeOnce()
        }
    }

    public func onScenePhaseBackground() {
        isInBackground = true
        backgroundFlagBox.value = true
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
        let exportedData = try ExcelManager.exportResults(
            sourceExcelData: cachedSourceExcelData,
            records: allRecords
        )
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let fileName = "KetQua_CauHinh_ONT_\(formatter.string(from: Date())).xlsx"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try exportedData.write(to: tempURL, options: [.atomic, .completeFileProtection])
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
        globalSettings = newSettings
        if urlChanged {
            probeDriver = OntMobileDriver(
                baseUrl: newSettings.baseUrl,
                timeoutMs: 1200
            )
        }
    }

    // MARK: - 7-Step Execution with Background Task & Idle Timer Protection

    public func runOntConfiguration() {
        guard !isRunningSetup else { return }
        guard let record = selectedRecord else { return }
        let settings = globalSettings
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
        stepProgressList = Self.defaultSevenSteps()
        setupResult = nil
        executionError = nil

        var runningRecord = record
        runningRecord.startTime = startTimeStr
        database.update(record: runningRecord)

        let activeBand = connectedBand

        Task {
            #if canImport(UIKit)
            UIApplication.shared.isIdleTimerDisabled = true
            var bgTaskId: UIBackgroundTaskIdentifier = .invalid
            bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "ONT7StepSetup") {
                if bgTaskId != .invalid {
                    UIApplication.shared.endBackgroundTask(bgTaskId)
                    bgTaskId = .invalid
                }
            }
            defer {
                UIApplication.shared.isIdleTimerDisabled = false
                if bgTaskId != .invalid {
                    UIApplication.shared.endBackgroundTask(bgTaskId)
                    bgTaskId = .invalid
                }
            }
            #endif

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
                self.isRunningSetup = false
                self.executionError = noSubnetMsg
                self.statusMessage = noSubnetMsg
                self.notifyCompletionFeedback(success: false)
                return
            }

            let latestAdminPassBox = LockedBox<String>(record.lastKnownAdminPass)
            let bgBox = self.backgroundFlagBox
            let dbRef = self.database
            let localIpv4 = snapshot.en0IpAddress

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
                }
            )

            do {
                let result = try await driver.executeMobileSetup(
                    record: record,
                    settings: settings,
                    connectedBand: activeBand,
                    localWifiIpv4: localIpv4,
                    onBandResolved: { [weak self] resolvedBand in
                        Task { @MainActor [weak self] in
                            self?.connectedBand = resolvedBand
                        }
                    }
                ) { [weak self] stepUpdate in
                    Task { @MainActor [weak self] in
                        guard let self = self else { return }
                        self.stepProgressList = self.stepProgressList.map { existing in
                            existing.stepNumber == stepUpdate.stepNumber ? stepUpdate : existing
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

                suppressProbeAfterCompletion = true
                isRunningSetup = false
                setupResult = result
                selectedRecord = completed
                isOntGatewayOnline = false
                notifyCompletionFeedback(success: true)
            } catch {
                let endTimeStr = timeFormatter.string(from: Date())
                let maskedErr = OntInputValidator.maskSensitiveCredentials(error.localizedDescription)

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
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try? mutableURL.setResourceValues(values)
    }

    private func loadCachedSourceExcel() {
        let url = sourceCacheFileURL()
        cachedSourceExcelData = try? Data(contentsOf: url)
    }
}
