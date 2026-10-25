package vn.vnpt.ontconfig.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import vn.vnpt.ontconfig.core.driver.MobileSetupResult
import vn.vnpt.ontconfig.core.driver.OntMobileDriver
import vn.vnpt.ontconfig.core.driver.StepProgress
import vn.vnpt.ontconfig.core.driver.StepStatus
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import vn.vnpt.ontconfig.core.wifi.OntWifiConnector
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.core.wifi.WifiScanCandidate
import vn.vnpt.ontconfig.data.excel.ExcelManager
import vn.vnpt.ontconfig.data.local.AppDatabase
import vn.vnpt.ontconfig.data.local.SubscriberEntity
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.SubscriberStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val allRecords: List<SubscriberRecord> = emptyList(),
    val filteredRecords: List<SubscriberRecord> = emptyList(),
    val searchQuery: String = "",
    val selectedStatusFilter: SubscriberStatus? = null,
    val selectedRecord: SubscriberRecord? = null,
    val globalSettings: GlobalSettings = GlobalSettings(),
    val autoSyncWifi5g: Boolean = true,
    val connectedWifiSsid: String = "",
    val connectedWifiBssid: String = "",
    val connectedBand: WifiBand = WifiBand.BAND_2_4_GHZ,
    val isOntGatewayOnline: Boolean = false,
    val consecutiveProbeSuccess: Int = 0,
    val showWifiScannerDialog: Boolean = false,
    val isConnectingWifi: Boolean = false,
    val scannedOntCandidates: List<WifiScanCandidate> = emptyList(),
    val showExecutionSheet: Boolean = false,
    val isRunningSetup: Boolean = false,
    val stepProgressList: List<StepProgress> = defaultSevenSteps(),
    val setupResult: MobileSetupResult? = null,
    val executionError: String? = null,
    val statusMessage: String? = null
) {
    companion object {
        fun defaultSevenSteps(): List<StepProgress> = listOf(
            StepProgress(1, 7, "Bước 1/7: Kiểm tra kết nối & nhận diện ONT Gateway", StepStatus.PENDING),
            StepProgress(2, 7, "Bước 2/7: Đăng nhập & Đổi mật khẩu Admin", StepStatus.PENDING),
            StepProgress(3, 7, "Bước 3/7: Cấu hình WAN PPPoE (VLAN 11)", StepStatus.PENDING),
            StepProgress(4, 7, "Bước 4/7: Cài đặt GPON Password (SLID / Mã MEN)", StepStatus.PENDING),
            StepProgress(5, 7, "Bước 5/7: Kiểm tra & Lưu cấu hình LAN IPv4", StepStatus.PENDING),
            StepProgress(6, 7, "Bước 6/7: Cấu hình Wi-Fi băng tần phụ", StepStatus.PENDING),
            StepProgress(7, 7, "Bước 7/7: Đổi tên & mật khẩu Wi-Fi băng tần kết nối (Bước cuối)", StepStatus.PENDING)
        )
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val dao = AppDatabase.getInstance(appContext).subscriberDao()
    val wifiConnector = OntWifiConnector(appContext)

    // [Red Team RT-10] Shared Mutex barrier between background gateway probe and active setup
    private val probePauseMutex = Mutex()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var probeDriver = OntMobileDriver(
        baseUrl = _uiState.value.globalSettings.baseUrl,
        timeoutMs = 1200L,
        socketFactory = null
    )

    @Volatile
    private var consecutiveMisses = 0

    init {
        loadPersistedRecords()
        wifiConnector.startMonitoringSystemWifi {
            viewModelScope.launch(Dispatchers.IO) {
                performGatewayProbeOnce()
            }
        }
        startBackgroundGatewayProbeLoop()
    }

    private fun loadPersistedRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            val entities = dao.getAll()
            val records = entities.map { it.toRecord() }
            updateRecordsList(records)
        }
    }

    /**
     * Perform a single gateway probe while holding probePauseMutex.
     * Always refreshes the active Wi-Fi Network binding so switching Wi-Fi in Android Settings
     * is detected immediately, and transitions to ONLINE on the first verified ONT HTML signature.
     */
    private suspend fun performGatewayProbeOnce(): Boolean {
        if (_uiState.value.isRunningSetup || !probePauseMutex.tryLock()) {
            return _uiState.value.isOntGatewayOnline
        }
        try {
            wifiConnector.bindCurrentWifiNetworkIfConnected()
            val (ssid, bssid) = wifiConnector.getConnectedSsidAndBssid()
            val socketFactory = wifiConnector.boundWifiNetwork?.socketFactory

            probeDriver.updateSocketFactory(socketFactory)
            val reachable = probeDriver.probeGateway()

            val isOnline = if (reachable) {
                consecutiveMisses = 0
                true
            } else {
                consecutiveMisses++
                if (_uiState.value.isOntGatewayOnline && consecutiveMisses < 2 && wifiConnector.boundWifiNetwork != null) {
                    true
                } else {
                    false
                }
            }
            val consecutiveCount = if (isOnline) 3 else 0

            _uiState.update { state ->
                val effectiveSsid = ssid.ifBlank { state.connectedWifiSsid }
                val effectiveBssid = bssid.ifBlank { state.connectedWifiBssid }
                val effectiveBand = wifiConnector.detectConnectedBand(effectiveSsid)
                state.copy(
                    connectedWifiSsid = effectiveSsid,
                    connectedWifiBssid = effectiveBssid,
                    connectedBand = effectiveBand,
                    isOntGatewayOnline = isOnline,
                    consecutiveProbeSuccess = consecutiveCount
                )
            }
            return isOnline
        } catch (_: Exception) {
            return false
        } finally {
            try {
                probePauseMutex.unlock()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * [Red Team RT-10] Background probe with Probe Pause Barrier.
     * Polls faster (1000ms) while waiting for connection and relaxes (2000ms) once ONLINE.
     */
    private fun startBackgroundGatewayProbeLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val online = performGatewayProbeOnce()
                delay(if (online) 2000L else 1000L)
            }
        }
    }

    private fun updateRecordsList(records: List<SubscriberRecord>, message: String? = null) {
        _uiState.update { state ->
            val filtered = ExcelManager.searchSubscribers(
                records = records,
                query = state.searchQuery,
                statusFilter = state.selectedStatusFilter
            )
            val updatedSelected = state.selectedRecord?.let { sel ->
                records.find { it.rowIndex == sel.rowIndex } ?: sel
            }
            state.copy(
                allRecords = records,
                filteredRecords = filtered,
                selectedRecord = updatedSelected,
                statusMessage = message ?: state.statusMessage
            )
        }
    }

    fun importExcelFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Không thể đọc file Excel từ bộ nhớ")

                ExcelManager.cacheSourceExcel(appContext, bytes)
                val settings = _uiState.value.globalSettings
                val incoming = ExcelManager.parseExcel(
                    inputStream = ByteArrayInputStream(bytes),
                    defaultWifi5Ssid = settings.defaultWifi5Ssid,
                    defaultWifi5Pass = settings.defaultWifi5Pass
                )
                val merged = dao.upsertPreservingEdits(incoming)
                val missingPppoeCount = merged.count { !it.isValid }
                val msg = "Đã nạp ${merged.size} thuê bao (${merged.size - missingPppoeCount} hợp lệ, $missingPppoeCount cần bổ sung TT)."
                updateRecordsList(merged, msg)
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Lỗi nạp file Excel: ${e.message}") }
            }
        }
    }

    fun loadDemoExcelFromAssets() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = appContext.assets.open(ExcelManager.DEMO_ASSET_FILE).use { it.readBytes() }
                ExcelManager.cacheSourceExcel(appContext, bytes)
                val settings = _uiState.value.globalSettings
                val incoming = ExcelManager.parseExcel(
                    inputStream = ByteArrayInputStream(bytes),
                    defaultWifi5Ssid = settings.defaultWifi5Ssid,
                    defaultWifi5Pass = settings.defaultWifi5Pass
                )
                val merged = dao.upsertPreservingEdits(incoming)
                updateRecordsList(
                    merged,
                    "Đã nạp ${merged.size} bản ghi mẫu Demo ẩn danh (an toàn PII)."
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Lỗi nạp dữ liệu mẫu Demo: ${e.message}") }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { state ->
            val filtered = ExcelManager.searchSubscribers(
                records = state.allRecords,
                query = query,
                statusFilter = state.selectedStatusFilter
            )
            state.copy(searchQuery = query, filteredRecords = filtered)
        }
    }

    fun onStatusFilterChange(filter: SubscriberStatus?) {
        _uiState.update { state ->
            val filtered = ExcelManager.searchSubscribers(
                records = state.allRecords,
                query = state.searchQuery,
                statusFilter = filter
            )
            state.copy(selectedStatusFilter = filter, filteredRecords = filtered)
        }
    }

    fun selectSubscriber(record: SubscriberRecord?) {
        _uiState.update { state ->
            val latest = if (record != null) {
                state.allRecords.find { it.rowIndex == record.rowIndex } ?: record
            } else {
                null
            }
            state.copy(selectedRecord = latest)
        }
    }

    fun setAutoSyncWifi5g(enabled: Boolean) {
        _uiState.update { state ->
            val cur = state.selectedRecord
            val syncedRecord = if (enabled && cur != null) {
                val candidate5g = "${cur.ssid24g.trim()} 5G".let {
                    if (it.length > 32) it.substring(0, 32).trim() else it
                }
                cur.copy(ssid5g = candidate5g, pass5g = cur.pass24g)
            } else {
                cur
            }
            state.copy(autoSyncWifi5g = enabled, selectedRecord = syncedRecord)
        }
        val updated = _uiState.value.selectedRecord
        if (enabled && updated != null) {
            updateSelectedRecord(updated)
        }
    }

    /**
     * Immediately updates selectedRecord, allRecords, filteredRecords, AND persists to Room DB
     * so KTV edits on ConfigReviewScreen are never lost if they navigate back or switch Wi-Fi!
     */
    fun updateSelectedRecord(updated: SubscriberRecord) {
        val importCheck = OntInputValidator.validateOnImport(updated)
        val newStatus = when {
            updated.status == SubscriberStatus.SUCCESS -> SubscriberStatus.SUCCESS
            importCheck.isValid -> SubscriberStatus.PENDING
            else -> SubscriberStatus.INVALID_DATA
        }
        val normalized = updated.copy(
            isValid = importCheck.isValid,
            validationErrors = importCheck.errors,
            status = newStatus
        )
        _uiState.update { state ->
            val newAll = state.allRecords.map { existing ->
                if (existing.rowIndex == normalized.rowIndex) normalized else existing
            }
            val newFiltered = ExcelManager.searchSubscribers(
                records = newAll,
                query = state.searchQuery,
                statusFilter = state.selectedStatusFilter
            )
            state.copy(
                selectedRecord = normalized,
                allRecords = newAll,
                filteredRecords = newFiltered
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.update(SubscriberEntity.fromRecord(normalized))
            } catch (_: Exception) {
            }
        }
    }

    fun prependLeadingZeroOnSelectedRecord() {
        val current = _uiState.value.selectedRecord ?: return
        val fixed = OntInputValidator.prependLeadingZeroToWifiPass(
            record = current,
            sync5g = _uiState.value.autoSyncWifi5g
        )
        updateSelectedRecord(fixed)
        saveSelectedRecordChanges()
    }

    fun updateGlobalSettings(newSettings: GlobalSettings) {
        val urlChanged = _uiState.value.globalSettings.baseUrl != newSettings.baseUrl
        _uiState.update { it.copy(globalSettings = newSettings) }
        if (urlChanged) {
            probeDriver = OntMobileDriver(
                baseUrl = newSettings.baseUrl,
                timeoutMs = 1200L,
                socketFactory = wifiConnector.boundWifiNetwork?.socketFactory
            )
        }
    }

    fun saveSelectedRecordChanges() {
        val current = _uiState.value.selectedRecord ?: return
        viewModelScope.launch(Dispatchers.IO) {
            dao.update(SubscriberEntity.fromRecord(current))
            val updatedAll = dao.getAll().map { it.toRecord() }
            updateRecordsList(updatedAll, "Đã lưu thông tin thuê bao ${current.accountPppoe}.")
        }
    }

    fun openWifiScanner() {
        val candidates = wifiConnector.scanOntNetworks(triggerStartScan = true)
        _uiState.update {
            it.copy(
                showWifiScannerDialog = true,
                scannedOntCandidates = candidates
            )
        }
        scheduleScanResultsRefresh()
    }

    fun refreshWifiScan() {
        val candidates = wifiConnector.scanOntNetworks(triggerStartScan = true)
        _uiState.update { it.copy(scannedOntCandidates = candidates) }
        scheduleScanResultsRefresh()
    }

    private fun scheduleScanResultsRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            repeat(4) {
                delay(600L)
                if (!_uiState.value.showWifiScannerDialog) return@launch
                val updated = wifiConnector.scanOntNetworks(triggerStartScan = false)
                _uiState.update { it.copy(scannedOntCandidates = updated) }
            }
        }
    }

    fun dismissWifiScanner() {
        _uiState.update { it.copy(showWifiScannerDialog = false) }
    }

    fun connectToOntCandidate(candidate: WifiScanCandidate, passphrase: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isConnectingWifi = true) }
            try {
                wifiConnector.connectToOntWifi(
                    ssid = candidate.ssid,
                    bssid = candidate.bssid,
                    passphrase = passphrase,
                    timeoutMs = 10000L
                )
                _uiState.update {
                    it.copy(
                        isConnectingWifi = false,
                        showWifiScannerDialog = false,
                        connectedWifiSsid = candidate.ssid,
                        connectedWifiBssid = candidate.bssid,
                        connectedBand = candidate.band,
                        statusMessage = "Đã bắt Wi-Fi ONT: ${candidate.ssid} (${candidate.band.label}). Đang kiểm tra 192.168.1.1..."
                    )
                }
                val online = withContext(Dispatchers.IO) {
                    performGatewayProbeOnce()
                }
                if (online) {
                    _uiState.update {
                        it.copy(
                            statusMessage = "Đã kết nối thành công tới ONT 192.168.1.1 qua ${candidate.ssid} (${candidate.band.label})!"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConnectingWifi = false,
                        statusMessage = "Không thể tự bắt '${candidate.ssid}': ${e.message}"
                    )
                }
            }
        }
    }

    fun bindExistingWifiConnection() {
        viewModelScope.launch {
            val net = withContext(Dispatchers.IO) {
                wifiConnector.bindCurrentWifiNetworkIfConnected()
            }
            val (ssid, bssid) = wifiConnector.getConnectedSsidAndBssid()
            val band = wifiConnector.detectConnectedBand()
            _uiState.update {
                it.copy(
                    showWifiScannerDialog = false,
                    connectedWifiSsid = ssid,
                    connectedWifiBssid = bssid,
                    connectedBand = band,
                    statusMessage = if (net != null) {
                        "Đã gắn kết nối Wi-Fi (${ssid.ifBlank { band.label }}). Đang kiểm tra 192.168.1.1..."
                    } else {
                        "Chưa phát hiện kết nối Wi-Fi trên máy. Hãy kết nối Wi-Fi ONT trước."
                    }
                )
            }
            if (net != null) {
                val online = withContext(Dispatchers.IO) {
                    performGatewayProbeOnce()
                }
                if (online) {
                    _uiState.update {
                        it.copy(
                            statusMessage = "Đã xác thực kết nối ONT 192.168.1.1 sẵn sàng (${ssid.ifBlank { band.label }})!"
                        )
                    }
                }
            }
        }
    }

    /**
     * Execute the 7-step wireless ONT configuration sequence for the selected subscriber.
     */
    fun runOntConfiguration() {
        val record = _uiState.value.selectedRecord ?: return
        val settings = _uiState.value.globalSettings
        val preRun = OntInputValidator.validateBeforeRun(record, settings)
        if (!preRun.isValid) {
            _uiState.update {
                it.copy(statusMessage = "Chưa thể RUN: ${preRun.errors.firstOrNull()}")
            }
            return
        }

        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val startTimeStr = timeFormat.format(Date())

        _uiState.update {
            it.copy(
                showExecutionSheet = true,
                isRunningSetup = true,
                stepProgressList = MainUiState.defaultSevenSteps(),
                setupResult = null,
                executionError = null
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.update(SubscriberEntity.fromRecord(record.copy(startTime = startTimeStr)))
            }

            wifiConnector.bindCurrentWifiNetworkIfConnected()
            val socketFactory = wifiConnector.boundWifiNetwork?.socketFactory
            val (detectedSsid, detectedBssid) = wifiConnector.getConnectedSsidAndBssid()
            val effectiveSsid = detectedSsid.ifBlank { _uiState.value.connectedWifiSsid }
            val effectiveBssid = detectedBssid.ifBlank { _uiState.value.connectedWifiBssid }
            val connectedBand = wifiConnector.detectConnectedBand(effectiveSsid)

            val step7StartMicros = SystemClock.elapsedRealtimeNanos() / 1000L
            var latestAdminPass = record.lastKnownAdminPass

            val driver = OntMobileDriver(
                baseUrl = settings.baseUrl,
                timeoutMs = settings.requestTimeoutMs,
                socketFactory = socketFactory,
                settlingDelayMs = 200L,
                probePauseMutex = probePauseMutex,
                onUnbindNetwork = {
                    wifiConnector.unbindOntWifi(clearSelectedMetadata = false)
                },
                onVerifyNewSsid = { expected24, expected5 ->
                    wifiConnector.verifyNewSsidBroadcast(
                        expectedSsid24g = expected24,
                        expectedSsid5g = expected5,
                        step7StartElapsedMicros = step7StartMicros
                    )
                },
                onAdminPasswordChanged = { newAdminPass ->
                    latestAdminPass = newAdminPass
                    val updatedWithPass = record.copy(
                        startTime = startTimeStr,
                        lastKnownAdminPass = newAdminPass
                    )
                    dao.update(SubscriberEntity.fromRecord(updatedWithPass))
                },
                onRebindWifiNetwork = {
                    var rebound = wifiConnector.bindCurrentWifiNetworkIfConnected()
                    if (rebound == null) {
                        for (waitAttempt in 1..6) {
                            delay(250L)
                            rebound = wifiConnector.bindCurrentWifiNetworkIfConnected()
                            if (rebound != null) break
                        }
                    }
                    if (rebound == null && effectiveSsid.isNotBlank()) {
                        rebound = try {
                            wifiConnector.connectToOntWifi(
                                ssid = effectiveSsid,
                                bssid = effectiveBssid.ifBlank { null },
                                timeoutMs = 6000L
                            )
                        } catch (_: Exception) {
                            wifiConnector.bindCurrentWifiNetworkIfConnected()
                        }
                    }
                    rebound?.socketFactory
                }
            )

            try {
                val result = driver.executeMobileSetup(
                    record = record,
                    settings = settings,
                    connectedBand = connectedBand,
                    bssid = effectiveBssid
                ) { stepUpdate ->
                    _uiState.update { state ->
                        val updatedSteps = state.stepProgressList.map { existing ->
                            if (existing.stepNumber == stepUpdate.stepNumber) stepUpdate else existing
                        }
                        state.copy(stepProgressList = updatedSteps)
                    }
                }

                val endTimeStr = timeFormat.format(Date())
                val completedRecord = record.copy(
                    status = SubscriberStatus.SUCCESS,
                    isValid = true,
                    validationErrors = emptyList(),
                    startTime = startTimeStr,
                    endTime = endTimeStr,
                    errorMessage = "",
                    lastKnownAdminPass = settings.adminNewPass
                )

                withContext(Dispatchers.IO) {
                    dao.update(SubscriberEntity.fromRecord(completedRecord))
                    val all = dao.getAll().map { it.toRecord() }
                    updateRecordsList(all, result.message)
                }

                _uiState.update {
                    it.copy(
                        isRunningSetup = false,
                        setupResult = result,
                        selectedRecord = completedRecord,
                        isOntGatewayOnline = false,
                        consecutiveProbeSuccess = 0
                    )
                }
                notifyCompletionFeedback(success = true)
            } catch (e: Exception) {
                val endTimeStr = timeFormat.format(Date())
                val maskedErr = OntInputValidator.maskSensitiveCredentials(e.message ?: "Lỗi không xác định")
                val failedRecord = record.copy(
                    status = SubscriberStatus.FAILED,
                    startTime = startTimeStr,
                    endTime = endTimeStr,
                    errorMessage = maskedErr,
                    lastKnownAdminPass = latestAdminPass
                )

                withContext(Dispatchers.IO) {
                    dao.update(SubscriberEntity.fromRecord(failedRecord))
                    val all = dao.getAll().map { it.toRecord() }
                    updateRecordsList(all, "Lỗi cấu hình: $maskedErr")
                }

                _uiState.update { state ->
                    val markedSteps = state.stepProgressList.map { st ->
                        if (st.status == StepStatus.RUNNING) {
                            st.copy(status = StepStatus.FAILED, detail = maskedErr)
                        } else {
                            st
                        }
                    }
                    state.copy(
                        isRunningSetup = false,
                        stepProgressList = markedSteps,
                        executionError = maskedErr,
                        selectedRecord = failedRecord
                    )
                }
                notifyCompletionFeedback(success = false)
            }
        }
    }

    fun dismissExecutionSheet() {
        if (!_uiState.value.isRunningSetup) {
            _uiState.update { it.copy(showExecutionSheet = false) }
        }
    }

    fun joinNewlyConfiguredWifi(ssid: String, pass: String) {
        wifiConnector.promptJoinCustomerWifi(ssid, pass)
    }

    /**
     * [Red Team RT-06 & RT-09] Ensure process network is unbound (restoring 4G/5G cellular)
     * before exporting the 30+3 column .xlsx report and launching the Zalo/Email share sheet.
     */
    fun exportAndShareExcelReport() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                wifiConnector.unbindOntWifi()

                val records = dao.getAll().map { it.toRecord() }
                if (records.isEmpty()) return@launch

                val exportsDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outFile = File(exportsDir, "BaoCao_CauHinh_ONT_$timestamp.xlsx")

                val sourceStream = ExcelManager.getCachedSourceExcelStream(appContext)
                FileOutputStream(outFile).use { fos ->
                    ExcelManager.exportResults(sourceStream, fos, records)
                }
                sourceStream?.close()

                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    outFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Báo cáo cấu hình ONT VNPT ($timestamp)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(shareIntent, "Chia sẻ báo cáo Excel qua Zalo / Email").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(chooser)

                _uiState.update {
                    it.copy(statusMessage = "Đã xuất báo cáo: ${outFile.name}")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Lỗi xuất báo cáo Excel: ${e.message}")
                }
            }
        }
    }

    private fun notifyCompletionFeedback(success: Boolean) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            val tone = if (success) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_SUP_ERROR
            toneGen.startTone(tone, if (success) 350 else 600)
        } catch (_: Exception) {
        }

        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                val duration = if (success) 250L else 500L
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        wifiConnector.stopMonitoringSystemWifi()
        wifiConnector.unbindOntWifi()
    }
}
