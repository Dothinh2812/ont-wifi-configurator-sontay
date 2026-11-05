package vn.vnpt.ontconfig

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import vn.vnpt.ontconfig.ui.MainViewModel
import vn.vnpt.ontconfig.ui.components.ExecutionProgressSheet
import vn.vnpt.ontconfig.ui.components.OntWifiScannerDialog
import vn.vnpt.ontconfig.ui.screens.ConfigReviewScreen
import vn.vnpt.ontconfig.ui.screens.SubscriberListScreen

private val VnptLightColorScheme = lightColorScheme(
    primary = Color(0xFF005BAA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF005BAA),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF0277BD),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F2FD),
    onSecondaryContainer = Color(0xFF003466),
    tertiary = Color(0xFF00838F),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF2F6),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFF94A3B8),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFF8E0000)
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = VnptLightColorScheme) {
                Surface {
                    val uiState by viewModel.uiState.collectAsState()

                    // [Red Team RT-06] Keep screen awake while 7-step ONT configuration is running
                    // so Android 10-14 does not revoke WifiNetworkSpecifier mid-flight.
                    DisposableEffect(uiState.isRunningSetup) {
                        if (uiState.isRunningSetup) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        onDispose {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }

                    val excelPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            viewModel.importExcelFromUri(uri)
                        }
                    }

                    val firmwarePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) {
                            viewModel.importFirmwareFromUri(uri)
                        }
                    }

                    val wifiPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        viewModel.openWifiScanner()
                    }

                    fun missingWifiPermissions(): Array<String> {
                        val needed = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                        return needed.filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }.toTypedArray()
                    }

                    fun ensurePermissionsAndOpenWifiScanner() {
                        val missing = missingWifiPermissions()
                        if (missing.isEmpty()) {
                            viewModel.openWifiScanner()
                        } else {
                            wifiPermissionLauncher.launch(missing)
                        }
                    }

                    val startupPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        viewModel.bindExistingWifiConnection()
                    }

                    LaunchedEffect(Unit) {
                        val missing = missingWifiPermissions()
                        if (missing.isNotEmpty()) {
                            startupPermissionLauncher.launch(missing)
                        }
                    }

                    val selectedRecord = uiState.selectedRecord
                    BackHandler(enabled = selectedRecord != null && !uiState.isRunningSetup) {
                        viewModel.selectSubscriber(null)
                    }

                    if (selectedRecord != null) {
                        ConfigReviewScreen(
                            record = selectedRecord,
                            settings = uiState.globalSettings,
                            autoSyncWifi5g = uiState.autoSyncWifi5g,
                            connectedWifiSsid = uiState.connectedWifiSsid,
                            connectedBand = uiState.connectedBand,
                            isOntGatewayOnline = uiState.isOntGatewayOnline,
                            statusMessage = uiState.statusMessage,
                            isRunningSetup = uiState.isRunningSetup,
                            isImportingFirmware = uiState.isImportingFirmware,
                            is5GHzSupported = uiState.is5GHzSupported,
                            securityCapabilityWarning = viewModel.wifiConnector.validateHandoverSecurityCapability(
                                uiState.globalSettings.wifiSecurityMode
                            ),
                            onOpenWifiScanner = ::ensurePermissionsAndOpenWifiScanner,
                            onPickFirmwareFile = {
                                firmwarePickerLauncher.launch(
                                    arrayOf("application/octet-stream", "*/*")
                                )
                            },
                            onAutoSyncWifi5gChange = viewModel::setAutoSyncWifi5g,
                            onUpdateRecord = viewModel::updateSelectedRecord,
                            onUpdateSettings = viewModel::updateGlobalSettings,
                            onPrependZeroWifiPass = viewModel::prependLeadingZeroOnSelectedRecord,
                            onSaveChanges = viewModel::saveSelectedRecordChanges,
                            onRunConfiguration = viewModel::runOntConfiguration,
                            onBack = { viewModel.selectSubscriber(null) }
                        )
                    } else {
                        SubscriberListScreen(
                            records = uiState.filteredRecords,
                            allRecords = uiState.allRecords,
                            searchQuery = uiState.searchQuery,
                            selectedStatusFilter = uiState.selectedStatusFilter,
                            connectedWifiSsid = uiState.connectedWifiSsid,
                            connectedBand = uiState.connectedBand,
                            isOntGatewayOnline = uiState.isOntGatewayOnline,
                            consecutiveProbeSuccess = uiState.consecutiveProbeSuccess,
                            statusMessage = uiState.statusMessage,
                            onSearchQueryChange = viewModel::onSearchQueryChange,
                            onStatusFilterChange = viewModel::onStatusFilterChange,
                            onPickExcelFile = {
                                excelPickerLauncher.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            onLoadDemoExcel = viewModel::loadDemoExcelFromAssets,
                            onExportReport = viewModel::exportAndShareExcelReport,
                            onOpenWifiScanner = ::ensurePermissionsAndOpenWifiScanner,
                            onSelectSubscriber = viewModel::selectSubscriber
                        )
                    }

                    if (uiState.showWifiScannerDialog) {
                        OntWifiScannerDialog(
                            candidates = uiState.scannedOntCandidates,
                            isConnecting = uiState.isConnectingWifi,
                            onRefreshScan = viewModel::refreshWifiScan,
                            onConnectCandidate = viewModel::connectToOntCandidate,
                            onBindExistingWifi = viewModel::bindExistingWifiConnection,
                            onDismiss = viewModel::dismissWifiScanner
                        )
                    }

                    if (uiState.showExecutionSheet) {
                        ExecutionProgressSheet(
                            isRunning = uiState.isRunningSetup,
                            steps = uiState.stepProgressList,
                            setupResult = uiState.setupResult,
                            errorMessage = uiState.executionError,
                            onJoinNewWifi = viewModel::joinNewlyConfiguredWifi,
                            onExportAndShareReport = viewModel::exportAndShareExcelReport,
                            onOpenWifiScanner = {
                                viewModel.dismissExecutionSheet()
                                ensurePermissionsAndOpenWifiScanner()
                            },
                            onRetryRun = viewModel::runOntConfiguration,
                            onDismiss = viewModel::dismissExecutionSheet
                        )
                    }
                }
            }
        }
    }
}
