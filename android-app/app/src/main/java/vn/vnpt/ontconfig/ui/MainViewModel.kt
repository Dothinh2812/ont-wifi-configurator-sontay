package vn.vnpt.ontconfig.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
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
import vn.vnpt.ontconfig.core.driver.WifiHandoverContext
import vn.vnpt.ontconfig.core.logging.AppDiagnosticLogger
import vn.vnpt.ontconfig.core.logging.LogLevel
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
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.SocketFactory

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
    val isImportingFirmware: Boolean = false,
    val stepProgressList: List<StepProgress> = defaultEightStepsDualWifi(),
    val setupResult: MobileSetupResult? = null,
    val executionError: String? = null,
    val statusMessage: String? = null,
    val is5GHzSupported: Boolean = true,
    val showDiagnosticLogDialog: Boolean = false
) {
    companion object {
        fun defaultEightStepsDualWifi(): List<StepProgress> = listOf(
            StepProgress(1, 8, "Bước 1/8: Kiểm tra kết nối & nhận diện ONT Gateway", StepStatus.PENDING),
            StepProgress(2, 8, "Bước 2/8: Đăng nhập & Đổi mật khẩu Admin", StepStatus.PENDING),
            StepProgress(3, 8, "Bước 3/8: Cấu hình WAN PPPoE (VLAN 11)", StepStatus.PENDING),
            StepProgress(4, 8, "Bước 4/8: Cài đặt GPON Password (SLID / Mã MEN)", StepStatus.PENDING),
            StepProgress(5, 8, "Bước 5/8: Kiểm tra & Lưu cấu hình LAN IPv4", StepStatus.PENDING),
            StepProgress(6, 8, "Bước 6/8: Đổi tên & cấu hình Wi-Fi phụ", StepStatus.PENDING),
            StepProgress(7, 8, "Bước 7/8: Đợi ONT phát sóng mới & Chuyển sang Wi-Fi phụ", StepStatus.PENDING),
            StepProgress(8, 8, "Bước 8/8: Đổi tên & cấu hình Wi-Fi còn lại", StepStatus.PENDING)
        )

        fun defaultNineStepsWithFirmware(): List<StepProgress> = listOf(
            StepProgress(1, 9, "Bước 1/9: Kiểm tra kết nối & nhận diện ONT Gateway", StepStatus.PENDING),
            StepProgress(2, 9, "Bước 2/9: Đăng nhập & Đổi mật khẩu Admin", StepStatus.PENDING),
            StepProgress(3, 9, "Bước 3/9: Cấu hình WAN PPPoE (VLAN 11)", StepStatus.PENDING),
            StepProgress(4, 9, "Bước 4/9: Cài đặt GPON Password (SLID / Mã MEN)", StepStatus.PENDING),
            StepProgress(5, 9, "Bước 5/9: Kiểm tra & Lưu cấu hình LAN IPv4", StepStatus.PENDING),
            StepProgress(6, 9, "Bước 6/9: Đổi tên & cấu hình Wi-Fi phụ", StepStatus.PENDING),
            StepProgress(7, 9, "Bước 7/9: Đợi ONT phát sóng mới & Chuyển sang Wi-Fi phụ", StepStatus.PENDING),
            StepProgress(8, 9, "Bước 8/9: Đổi tên & cấu hình Wi-Fi còn lại", StepStatus.PENDING),
            StepProgress(9, 9, "Bước 9/9: Tải lên & Cập nhật Firmware ONT (Bước cuối)", StepStatus.PENDING)
        )
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val dao = AppDatabase.getInstance(appContext).subscriberDao()
    val diagnosticLogger = AppDiagnosticLogger(appContext)
    val wifiConnector = OntWifiConnector(appContext, onLog = diagnosticLogger::log)
    // [Red Team RT-10] Shared Mutex barrier between background gateway probe and active setup
    private val probePauseMutex = Mutex()

    private val _uiState = MutableStateFlow(
        MainUiState(is5GHzSupported = wifiConnector.is5GHzBandSupported())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile
    private var probeDriver = OntMobileDriver(
        baseUrl = _uiState.value.globalSettings.baseUrl,
        timeoutMs = 1200L,
        socketFactory = null
    )

    @Volatile
    private var consecutiveMisses = 0

    // [Red Team RT-01 & RT-07] Suppress passive Wi-Fi auto-bind & probe after setup completion
    // (both 7-step and 8-step firmware) EVEN after ExecutionProgressSheet is dismissed, preserving
    // 4G/5G cellular for Zalo report sharing and isolating the ONT during ~2-minute flash writes.
    @Volatile
    private var suppressAutoBindAfterCompletion = false

    @Volatile
    private var lastShareLaunchRealtimeMs: Long = -1_000_000L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cleanupStaleExportFiles(File(appContext.cacheDir, "exports"))
        }
        loadPersistedRecords()
        loadCachedFirmwareOnInit()
        wifiConnector.startMonitoringSystemWifi(
            shouldAutoBind = {
                !suppressAutoBindAfterCompletion &&
                    !_uiState.value.isRunningSetup &&
                    !_uiState.value.isConnectingWifi
            }
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                performGatewayProbeOnce()
            }
        }
        startBackgroundGatewayProbeLoop()
    }

    private fun loadCachedFirmwareOnInit() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fwDir = File(appContext.noBackupFilesDir, "firmware_cache")
                val cachedFile = fwDir.listFiles()
                    ?.filter {
                        it.isFile &&
                            OntInputValidator.isValidFirmwareExtension(it.name) &&
                            it.length() in OntInputValidator.MIN_FIRMWARE_SIZE_BYTES..OntInputValidator.MAX_FIRMWARE_SIZE_BYTES
                    }
                    ?.maxByOrNull { it.lastModified() }
                if (cachedFile != null) {
                    _uiState.update { state ->
                        if (state.globalSettings.firmwareFilePath.isBlank()) {
                            state.copy(
                                globalSettings = state.globalSettings.copy(
                                    firmwareFilePath = cachedFile.absolutePath,
                                    firmwareFileName = cachedFile.name,
                                    firmwareFileSize = cachedFile.length()
                                )
                            )
                        } else {
                            state
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
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
        if (_uiState.value.isRunningSetup || suppressAutoBindAfterCompletion || !probePauseMutex.tryLock()) {
            return _uiState.value.isOntGatewayOnline
        }
        try {
            wifiConnector.bindCurrentWifiNetworkIfConnected()
            val (ssid, bssid) = wifiConnector.getConnectedSsidAndBssid()
            val boundNet = wifiConnector.boundWifiNetwork
            if (boundNet == null) {
                val wasOnline = _uiState.value.isOntGatewayOnline
                consecutiveMisses = 2
                _uiState.update { state ->
                    state.copy(
                        connectedWifiSsid = "",
                        connectedWifiBssid = "",
                        isOntGatewayOnline = false,
                        consecutiveProbeSuccess = 0
                    )
                }
                if (wasOnline) {
                    diagnosticLogger.log(LogLevel.INFO, "PROBE", "ONT Gateway 192.168.1.1 chuyển sang OFFLINE (chưa có mạng Wi-Fi gắn kết).")
                }
                return false
            }

            probeDriver.updateSocketFactory(boundNet.socketFactory)
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
            val wasOnline = _uiState.value.isOntGatewayOnline
            val consecutiveCount = if (isOnline) 3 else 0

            _uiState.update { state ->
                val effectiveSsid = ssid.ifBlank { state.connectedWifiSsid }
                val effectiveBssid = bssid.ifBlank { state.connectedWifiBssid }
                val sel = state.selectedRecord
                val effectiveBand = wifiConnector.detectConnectedBand(
                    ssidHint = effectiveSsid,
                    configured24Ssid = sel?.effectiveWifi24Ssid,
                    configured5Ssid = sel?.effectiveWifi5Ssid
                )
                state.copy(
                    connectedWifiSsid = effectiveSsid,
                    connectedWifiBssid = effectiveBssid,
                    connectedBand = effectiveBand,
                    isOntGatewayOnline = isOnline,
                    consecutiveProbeSuccess = consecutiveCount
                )
            }
            if (wasOnline != isOnline) {
                diagnosticLogger.log(
                    LogLevel.INFO,
                    "PROBE",
                    if (isOnline) {
                        "ONT Gateway 192.168.1.1 chuyển sang ONLINE qua '${ssid.ifBlank { "Wi-Fi ONT" }}'."
                    } else {
                        "ONT Gateway 192.168.1.1 chuyển sang OFFLINE."
                    }
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
        if (record != null) {
            suppressAutoBindAfterCompletion = false
        }
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
        val prevSettings = _uiState.value.globalSettings
        val urlChanged = prevSettings.baseUrl != newSettings.baseUrl
        val fwToggleChanged = prevSettings.enableFirmwareUpdate != newSettings.enableFirmwareUpdate
        _uiState.update { state ->
            val updatedSteps = if (fwToggleChanged && !state.isRunningSetup) {
                if (newSettings.enableFirmwareUpdate) {
                    MainUiState.defaultNineStepsWithFirmware()
                } else {
                    MainUiState.defaultEightStepsDualWifi()
                }
            } else {
                state.stepProgressList
            }
            state.copy(
                globalSettings = newSettings,
                stepProgressList = updatedSteps
            )
        }
        if (urlChanged) {
            probeDriver = OntMobileDriver(
                baseUrl = newSettings.baseUrl,
                timeoutMs = 1200L,
                socketFactory = wifiConnector.boundWifiNetwork?.socketFactory
            )
        }
    }

    /**
     * [Red Team RT-04] Stream and cache selected firmware image from Android SAF URI into
     * non-exported `noBackupFilesDir/firmware_cache/` via atomic `.tmp` copy.
     */
    fun importFirmwareFromUri(uri: Uri) {
        if (_uiState.value.isRunningSetup || _uiState.value.isImportingFirmware) return
        _uiState.update {
            it.copy(
                isImportingFirmware = true,
                statusMessage = "Đang kiểm tra và nạp file Firmware..."
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val tmpFile = File(appContext.cacheDir, "fw_upload_${SystemClock.elapsedRealtime()}.tmp")
            try {
                var displayName = ""
                var reportedSize = -1L
                appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx).orEmpty().trim()
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            reportedSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
                if (displayName.isBlank()) {
                    displayName = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringAfterLast(':')
                        ?.trim()
                        .orEmpty()
                        .ifBlank { "firmware.signed" }
                }
                if (!OntInputValidator.isValidFirmwareExtension(displayName)) {
                    throw IllegalArgumentException(
                        "Định dạng file '$displayName' không hợp lệ (chỉ hỗ trợ .signed, .bin, .img)."
                    )
                }
                if (
                    reportedSize > 0L &&
                    reportedSize !in OntInputValidator.MIN_FIRMWARE_SIZE_BYTES..OntInputValidator.MAX_FIRMWARE_SIZE_BYTES
                ) {
                    throw IllegalArgumentException(
                        "Dung lượng file Firmware không hợp lệ (${reportedSize / 1024} KB — yêu cầu từ 64 KB đến 64 MB)."
                    )
                }

                var totalBytes = 0L
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            totalBytes += read
                            if (totalBytes > OntInputValidator.MAX_FIRMWARE_SIZE_BYTES) {
                                throw IllegalArgumentException("File Firmware vượt quá giới hạn tối đa 64 MB.")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                } ?: throw IllegalArgumentException("Không thể mở luồng đọc file Firmware từ bộ nhớ.")

                if (totalBytes !in OntInputValidator.MIN_FIRMWARE_SIZE_BYTES..OntInputValidator.MAX_FIRMWARE_SIZE_BYTES) {
                    throw IllegalArgumentException(
                        "Dung lượng file Firmware không hợp lệ (${totalBytes / 1024} KB — yêu cầu từ 64 KB đến 64 MB)."
                    )
                }

                val fwDir = File(appContext.noBackupFilesDir, "firmware_cache").apply { mkdirs() }
                val safeName = OntInputValidator.sanitizeFirmwareFilename(displayName)
                val destFile = File(fwDir, safeName)
                fwDir.listFiles()?.forEach { existing ->
                    if (existing.absolutePath != destFile.absolutePath) {
                        try {
                            existing.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (destFile.exists()) {
                    destFile.delete()
                }
                val moved = tmpFile.renameTo(destFile)
                if (!moved) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                val finalSize = destFile.length()
                val sizeMbStr = String.format(Locale.US, "%.2f", finalSize / (1024.0 * 1024.0))

                _uiState.update { state ->
                    state.copy(
                        isImportingFirmware = false,
                        globalSettings = state.globalSettings.copy(
                            enableFirmwareUpdate = true,
                            firmwareFilePath = destFile.absolutePath,
                            firmwareFileName = displayName,
                            firmwareFileSize = finalSize
                        ),
                        stepProgressList = if (!state.isRunningSetup) {
                            MainUiState.defaultNineStepsWithFirmware()
                        } else {
                            state.stepProgressList
                        },
                        statusMessage = "Đã nạp file Firmware: $displayName ($sizeMbStr MB)"
                    )
                }
            } catch (e: Exception) {
                try {
                    if (tmpFile.exists()) tmpFile.delete()
                } catch (_: Exception) {
                }
                _uiState.update { state ->
                    state.copy(
                        isImportingFirmware = false,
                        statusMessage = "Lỗi chọn file Firmware: ${e.message}"
                    )
                }
            }
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
        suppressAutoBindAfterCompletion = false
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
        suppressAutoBindAfterCompletion = false
        if (!passphrase.isNullOrBlank()) {
            diagnosticLogger.registerActiveSecrets(listOf(passphrase))
        }
        diagnosticLogger.log(
            LogLevel.INFO,
            "SCANNER",
            "Chọn kết nối Wi-Fi ONT '${candidate.ssid}' (${candidate.band.label}, freq=${candidate.frequencyMhz}MHz, RSSI=${candidate.rssi}dBm, open=${candidate.isOpen})."
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isConnectingWifi = true) }
            try {
                wifiConnector.connectToOntWifi(
                    ssid = candidate.ssid,
                    bssid = candidate.bssid,
                    passphrase = passphrase,
                    timeoutMs = 35_000L,
                    securityMode = if (candidate.isOpen && passphrase.isNullOrBlank()) {
                        WifiSecurityMode.OPEN.formValue
                    } else {
                        WifiSecurityMode.WPA2_WPA3_PSK.formValue
                    },
                    frequencyMhz = candidate.frequencyMhz
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
                val maskedMsg = OntInputValidator.maskSensitiveCredentials(e.message ?: "Lỗi kết nối Wi-Fi")
                diagnosticLogger.log(
                    LogLevel.ERROR,
                    "SCANNER",
                    "Không thể bắt Wi-Fi '${candidate.ssid}': $maskedMsg"
                )
                _uiState.update {
                    it.copy(
                        isConnectingWifi = false,
                        statusMessage = "Không thể tự bắt '${candidate.ssid}': $maskedMsg"
                    )
                }
            } finally {
                if (!_uiState.value.isRunningSetup) {
                    diagnosticLogger.clearActiveSecrets()
                }
            }
        }
    }

    fun bindExistingWifiConnection() {
        suppressAutoBindAfterCompletion = false
        viewModelScope.launch {
            val net = withContext(Dispatchers.IO) {
                wifiConnector.bindCurrentWifiNetworkIfConnected()
            }
            val (ssid, bssid) = wifiConnector.getConnectedSsidAndBssid()
            val sel = _uiState.value.selectedRecord
            val band = wifiConnector.detectConnectedBand(
                ssidHint = ssid,
                configured24Ssid = sel?.effectiveWifi24Ssid,
                configured5Ssid = sel?.effectiveWifi5Ssid
            )
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
     * [Red Team RT-03] Rebind strictly to the active target Wi-Fi (`Band A` during Connection 1,
     * or `SSID_B` with `allowSubnetFallbackWhenSsidUnknown = false` and `excludedNetwork = conn1Network`
     * during Connection 2), using a 25s window so Android's system specifier dialog does not flap.
     */
    private suspend fun rebindOntWifiSocketFactory(
        ssid: String,
        passphrase: String?,
        securityMode: String,
        band: WifiBand,
        isSecondaryConnection: Boolean,
        excludedNetwork: Network?,
        timeoutMs: Long = 35_000L
    ): SocketFactory? {
        diagnosticLogger.log(
            LogLevel.INFO,
            "REBIND",
            "Yêu cầu rebind Wi-Fi '$ssid' (${band.label}, secondary=$isSecondaryConnection, timeout=${timeoutMs}ms)."
        )
        if (!isSecondaryConnection) {
            var rebound = wifiConnector.bindCurrentWifiNetworkIfConnected()
            var remaining = 6
            while (rebound == null && remaining-- > 0) {
                delay(250L)
                rebound = wifiConnector.bindCurrentWifiNetworkIfConnected()
            }
            if (rebound == null && ssid.isNotBlank()) {
                rebound = try {
                    wifiConnector.connectToOntWifi(
                        ssid = ssid,
                        bssid = null,
                        passphrase = passphrase,
                        timeoutMs = timeoutMs,
                        securityMode = securityMode,
                        allowSubnetFallbackWhenSsidUnknown = true
                    )
                } catch (_: Exception) {
                    wifiConnector.bindCurrentWifiNetworkIfConnected()
                }
            }
            return rebound?.socketFactory
        }

        if (ssid.isBlank()) return null
        val existingTarget = wifiConnector.findAlreadyConnectedTargetNetwork(
            targetSsid = ssid,
            excludedNetwork = excludedNetwork
        )
        if (existingTarget != null) {
            return existingTarget.socketFactory
        }

        return try {
            val net = wifiConnector.connectToOntWifi(
                ssid = ssid,
                bssid = null,
                passphrase = passphrase,
                timeoutMs = timeoutMs,
                securityMode = securityMode,
                frequencyMhz = if (band == WifiBand.BAND_5_GHZ) 5180 else 2412,
                allowSubnetFallbackWhenSsidUnknown = false,
                excludedNetwork = excludedNetwork
            )
            net.socketFactory
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Execute the automated 2-connection Dual-Wi-Fi + Final Firmware Update workflow.
     */
    fun runOntConfiguration() {
        if (_uiState.value.isRunningSetup || _uiState.value.isImportingFirmware) return
        val record = _uiState.value.selectedRecord ?: return
        val settings = _uiState.value.globalSettings
        val preRun = OntInputValidator.validateBeforeRun(record, settings)
        if (!preRun.isValid) {
            _uiState.update {
                it.copy(statusMessage = "Chưa thể RUN: ${preRun.errors.firstOrNull()}")
            }
            return
        }

        // [Red Team RT-06] Validate OS Wi-Fi security handover capability BEFORE Step 1 mutates the ONT
        val secCapError = wifiConnector.validateHandoverSecurityCapability(settings.wifiSecurityMode)
        if (secCapError != null) {
            _uiState.update {
                it.copy(statusMessage = "Chưa thể RUN: $secCapError")
            }
            return
        }

        suppressAutoBindAfterCompletion = false
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val startTimeStr = timeFormat.format(Date())
        val initialSteps = if (settings.enableFirmwareUpdate) {
            MainUiState.defaultNineStepsWithFirmware()
        } else {
            MainUiState.defaultEightStepsDualWifi()
        }
        val supports5Ghz = wifiConnector.is5GHzBandSupported()

        _uiState.update {
            it.copy(
                showExecutionSheet = true,
                isRunningSetup = true,
                stepProgressList = initialSteps,
                setupResult = null,
                executionError = null,
                is5GHzSupported = supports5Ghz
            )
        }
        viewModelScope.launch {
            diagnosticLogger.registerActiveSecrets(
                secrets = listOf(
                    settings.adminDefaultPass,
                    settings.adminNewPass,
                    record.lastKnownAdminPass,
                    record.passPppoe,
                    record.effectiveWifi24Pass(settings.wifiSecurityMode),
                    record.effectiveWifi5Pass(settings.wifiSecurityMode),
                    settings.defaultWifi5Pass
                ),
                subscriberIdentifiers = listOf(record.accountPppoe, record.slid)
            )
            diagnosticLogger.log(
                LogLevel.INFO,
                "RUN",
                "=== BẮT ĐẦU PHIÊN CẤU HÌNH ONT: account=${record.accountPppoe}, SLID=${record.slid}, SSID_24='${record.effectiveWifi24Ssid}', SSID_5='${record.effectiveWifi5Ssid}', sec=${settings.wifiSecurityMode}, fw=${settings.enableFirmwareUpdate}, 5GHzSupported=$supports5Ghz ==="
            )
            var latestAdminPass = record.lastKnownAdminPass
            try {
                withContext(Dispatchers.IO) {
                    dao.update(SubscriberEntity.fromRecord(record.copy(startTime = startTimeStr)))
                }

                wifiConnector.bindCurrentWifiNetworkIfConnected()
                val conn1Network = wifiConnector.boundWifiNetwork
                if (conn1Network == null) {
                    val noWifiErr = "Chưa kết nối Wi-Fi của ONT. Vui lòng bấm 'Bắt Wi-Fi ONT' hoặc kết nối Wi-Fi ONT trong Cài đặt máy trước khi bấm RUN."
                    diagnosticLogger.log(LogLevel.ERROR, "RUN", noWifiErr)
                    _uiState.update { state ->
                        state.copy(
                            isRunningSetup = false,
                            executionError = noWifiErr,
                            statusMessage = noWifiErr
                        )
                    }
                    notifyCompletionFeedback(success = false)
                    return@launch
                }
                val socketFactory = conn1Network.socketFactory
                val (detectedSsid, detectedBssid) = wifiConnector.getConnectedSsidAndBssid()
                val effectiveSsid = detectedSsid.ifBlank { _uiState.value.connectedWifiSsid }
                val effectiveBssid = detectedBssid.ifBlank { _uiState.value.connectedWifiBssid }
                val connectedBand = wifiConnector.detectConnectedBand(
                    ssidHint = effectiveSsid,
                    configured24Ssid = record.effectiveWifi24Ssid,
                    configured5Ssid = record.effectiveWifi5Ssid
                )
                val localWifiIpv4 = wifiConnector.getBoundWifiIpv4Address()
                diagnosticLogger.log(
                    LogLevel.INFO,
                    "RUN",
                    "Lần kết nối 1: SSID='${effectiveSsid.ifBlank { "<unknown>" }}', BSSID='${effectiveBssid.ifBlank { "N/A" }}', band=${connectedBand.label}, localIP=${localWifiIpv4 ?: "unknown"}."
                )

                var currentTargetSsid = effectiveSsid
                var currentTargetPass: String? = null
                var currentTargetBand = connectedBand
                var currentTargetSecMode = settings.wifiSecurityMode
                var isOnSecondaryConnection = false
                var currentExcludedNetwork: Network? = conn1Network

                val driver = OntMobileDriver(
                    baseUrl = settings.baseUrl,
                    timeoutMs = settings.requestTimeoutMs,
                    socketFactory = socketFactory,
                    settlingDelayMs = 400L,
                    allowSingleBand24gFallback = !supports5Ghz,
                    probePauseMutex = probePauseMutex,
                    onUnbindNetwork = {
                        wifiConnector.unbindOntWifi(clearSelectedMetadata = false)
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
                        rebindOntWifiSocketFactory(
                            ssid = currentTargetSsid,
                            passphrase = currentTargetPass,
                            securityMode = currentTargetSecMode,
                            band = currentTargetBand,
                            isSecondaryConnection = isOnSecondaryConnection,
                            excludedNetwork = if (isOnSecondaryConnection) currentExcludedNetwork else null,
                            timeoutMs = 35_000L
                        )
                    },
                    isNetworkTransportAlive = {
                        wifiConnector.boundWifiNetwork != null
                    },
                    onLog = diagnosticLogger::log,
                    onSwitchToSecondaryWifi = { targetSsid, targetPass, targetBand, targetSecMode, bssidHint, handoverTimeoutMs, minScanMicros, outerAttempt, lockToBssid, onDetail ->
                        val excludedForHandover = if (
                        !supports5Ghz &&
                        effectiveSsid.isNotBlank() &&
                        effectiveSsid.equals(targetSsid, ignoreCase = true)
                    ) {
                        null
                    } else {
                        conn1Network
                    }
                    val conn2Network = wifiConnector.switchToConfiguredSecondaryWifi(
                        targetSsid = targetSsid,
                        passphrase = targetPass,
                        targetBand = targetBand,
                        securityMode = targetSecMode,
                        targetBssidHint = bssidHint,
                        excludedNetwork = excludedForHandover,
                        timeoutMs = handoverTimeoutMs,
                        minScanTimestampMicros = minScanMicros,
                        outerAttemptIndex = outerAttempt,
                        lockToBssidHint = lockToBssid,
                        onStatusUpdate = onDetail
                    )
                    currentTargetSsid = targetSsid
                    currentTargetPass = targetPass
                    currentTargetBand = targetBand
                    currentTargetSecMode = targetSecMode
                    isOnSecondaryConnection = true
                    currentExcludedNetwork = excludedForHandover
                    val conn2Ipv4 = wifiConnector.getBoundWifiIpv4Address()
                    _uiState.update { state ->
                        state.copy(
                            connectedWifiSsid = targetSsid,
                            connectedBand = targetBand
                        )
                    }
                    WifiHandoverContext(
                        socketFactory = conn2Network.socketFactory,
                        localWifiIpv4 = conn2Ipv4
                    )
                }
            )
                val result = driver.executeMobileSetup(
                    record = record,
                    settings = settings,
                    connectedBand = connectedBand,
                    bssid = effectiveBssid,
                    localWifiIpv4 = localWifiIpv4,
                    onBandResolved = { resolvedBand, ontBaseMac ->
                        if (!isOnSecondaryConnection) {
                            currentTargetBand = resolvedBand
                        }
                        _uiState.update { state ->
                            state.copy(
                                connectedBand = resolvedBand,
                                connectedWifiBssid = state.connectedWifiBssid.ifBlank { ontBaseMac.orEmpty() }
                            )
                        }
                    }
                ) { stepUpdate ->
                    _uiState.update { state ->
                        val exists = state.stepProgressList.any { it.stepNumber == stepUpdate.stepNumber }
                        val updatedSteps = if (exists) {
                            state.stepProgressList.map { existing ->
                                if (existing.stepNumber == stepUpdate.stepNumber) stepUpdate else existing
                            }
                        } else {
                            (state.stepProgressList + stepUpdate).sortedBy { it.stepNumber }
                        }
                        state.copy(stepProgressList = updatedSteps)
                    }
                }

                suppressAutoBindAfterCompletion = true
                wifiConnector.unbindOntWifi(clearSelectedMetadata = false)

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

                diagnosticLogger.log(LogLevel.INFO, "RUN", "=== HOÀN TẤT THÀNH CÔNG: ${result.message} ===")
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
                diagnosticLogger.log(
                    LogLevel.ERROR,
                    "RUN",
                    "=== PHIÊN CẤU HÌNH THẤT BẠI (${e.javaClass.simpleName}): $maskedErr ==="
                )
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
            } finally {
                // [Red Team RT-03] Clear active run secrets strictly AFTER catch finishes logging
                diagnosticLogger.clearActiveSecrets()
            }
        }
    }

    fun dismissExecutionSheet() {
        if (!_uiState.value.isRunningSetup) {
            _uiState.update { it.copy(showExecutionSheet = false) }
        }
    }

    fun joinNewlyConfiguredWifi(ssid: String, pass: String) {
        suppressAutoBindAfterCompletion = false
        val res = _uiState.value.setupResult
        val secMode = res?.wifiSecurityMode
            ?: _uiState.value.globalSettings.wifiSecurityMode
        val (secondarySsid, secondaryPass) = if (res != null) {
            if (ssid.equals(res.newSsid24g, ignoreCase = true)) {
                res.newSsid5g to res.newPass5g
            } else {
                res.newSsid24g to res.newPass24g
            }
        } else {
            "" to ""
        }
        wifiConnector.promptJoinCustomerWifi(
            ssid = ssid,
            passphrase = pass,
            securityMode = secMode,
            secondarySsid = secondarySsid,
            secondaryPassphrase = secondaryPass
        )
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
                cleanupStaleExportFiles(exportsDir)
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

                lastShareLaunchRealtimeMs = SystemClock.elapsedRealtime()
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

    fun openDiagnosticLogDialog() {
        _uiState.update { it.copy(showDiagnosticLogDialog = true) }
    }

    fun dismissDiagnosticLogDialog() {
        _uiState.update { it.copy(showDiagnosticLogDialog = false) }
    }

    fun clearDiagnosticLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            diagnosticLogger.clearLogs()
            _uiState.update { it.copy(statusMessage = "Đã xóa sạch nhật ký tiến trình debug.") }
        }
    }

    fun copyDiagnosticLogsToClipboard() {
        viewModelScope.launch {
            try {
                val header = buildDeviceDiagnosticHeader()
                val logText = withContext(Dispatchers.IO) {
                    diagnosticLogger.exportCombinedLogText(
                        deviceHeader = header,
                        maskSubscriberIdentifiers = true
                    )
                }
                val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    val clip = ClipData.newPlainText("ONT_Debug_Log", logText)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    cm.setPrimaryClip(clip)
                    _uiState.update {
                        it.copy(statusMessage = "Đã chép Nhật ký Debug (đã ẩn mật khẩu & định danh) vào bộ nhớ tạm.")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Không thể chép nhật ký: ${e.message}")
                }
            }
        }
    }

    fun exportAndShareDiagnosticLog() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!_uiState.value.isRunningSetup) {
                    wifiConnector.unbindOntWifi(clearSelectedMetadata = false)
                }
                val exportsDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                cleanupStaleExportFiles(exportsDir)
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outFile = File(exportsDir, "ONT_Debug_Log_$timestamp.txt")
                val exportedText = diagnosticLogger.exportCombinedLogText(
                    deviceHeader = buildDeviceDiagnosticHeader(),
                    maskSubscriberIdentifiers = true
                )
                outFile.writeText(exportedText, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    outFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Nhật ký Debug ONT VNPT ($timestamp)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                lastShareLaunchRealtimeMs = SystemClock.elapsedRealtime()
                val chooser = Intent.createChooser(shareIntent, "Chia sẻ Nhật ký Debug (.txt) qua Zalo / Telegram").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(chooser)
                _uiState.update {
                    it.copy(statusMessage = "Đã xuất Nhật ký Debug: ${outFile.name}")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Lỗi chia sẻ Nhật ký Debug: ${e.message}")
                }
            }
        }
    }

    fun isShareInGraceWindow(): Boolean =
        (SystemClock.elapsedRealtime() - lastShareLaunchRealtimeMs) in 0L..120_000L

    private fun buildDeviceDiagnosticHeader(): String {
        val settings = _uiState.value.globalSettings
        return buildString {
            appendLine("ONT Wi-Fi Configurator — Diagnostic Log")
            appendLine("Platform: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("5GHz Supported: ${wifiConnector.is5GHzBandSupported()} | Location Enabled: ${wifiConnector.isLocationServiceEnabled()}")
            appendLine("Timeouts: Handover=${settings.wifiHandoverTimeoutMs}ms, Gateway=${settings.gatewayPostHandoverTimeoutMs}ms, PreFW=${settings.postSecondWifiSettleTimeoutMs}ms")
            append("Security Mode: ${settings.wifiSecurityMode} | Firmware Update: ${settings.enableFirmwareUpdate}")
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
        cleanupStaleExportFiles(File(appContext.cacheDir, "exports"))
        diagnosticLogger.close()
    }

    companion object {
        fun cleanupStaleExportFiles(exportsDir: File) {
            try {
                if (!exportsDir.exists() || !exportsDir.isDirectory) return
                exportsDir.listFiles()?.forEach { file ->
                    val isStaleExcel = file.name.endsWith(".xlsx", ignoreCase = true)
                    val isStaleDebugLog = file.name.startsWith("ONT_Debug_Log_") &&
                        file.name.endsWith(".txt", ignoreCase = true)
                    if (file.isFile && (isStaleExcel || isStaleDebugLog)) {
                        runCatching { file.delete() }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
