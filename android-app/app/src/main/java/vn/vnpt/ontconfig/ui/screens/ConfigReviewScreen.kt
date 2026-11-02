package vn.vnpt.ontconfig.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vnpt.ontconfig.core.validation.OntInputValidator
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.data.excel.ExcelManager
import vn.vnpt.ontconfig.data.model.GlobalSettings
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import vn.vnpt.ontconfig.ui.components.FooterSignatureBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigReviewScreen(
    record: SubscriberRecord,
    settings: GlobalSettings,
    autoSyncWifi5g: Boolean,
    connectedWifiSsid: String,
    connectedBand: WifiBand,
    isOntGatewayOnline: Boolean,
    statusMessage: String? = null,
    isRunningSetup: Boolean = false,
    isImportingFirmware: Boolean = false,
    onOpenWifiScanner: () -> Unit,
    onPickFirmwareFile: () -> Unit = {},
    onAutoSyncWifi5gChange: (Boolean) -> Unit,
    onUpdateRecord: (SubscriberRecord) -> Unit,
    onUpdateSettings: (GlobalSettings) -> Unit,
    onPrependZeroWifiPass: () -> Unit,
    onSaveChanges: () -> Unit,
    onRunConfiguration: () -> Unit,
    onBack: () -> Unit
) {
    val validation = OntInputValidator.validateBeforeRun(record, settings)
    var showPppoePass by remember { mutableStateOf(record.passPppoe.isBlank()) }
    var showWifiPass by remember { mutableStateOf(true) }
    var showAdminPass by remember { mutableStateOf(false) }
    var secModeDropdownExpanded by remember { mutableStateOf(false) }
    val selectedSecMode = WifiSecurityMode.fromFormValue(settings.wifiSecurityMode)
        ?: WifiSecurityMode.WPA2_WPA3_PSK
    val requiresWifiPass = selectedSecMode.requiresPassword

    val safeKeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrect = false,
        keyboardType = KeyboardType.Ascii
    )

    val isUsingDefaultMenSsid = record.slid.isNotBlank() &&
        record.ssid24g == "VNPT_${record.slid.trim()}"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Kiểm Tra & Sửa Cấu Hình ONT",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "ma_tb: ${record.accountPppoe} • SLID: ${record.slid}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onSaveChanges,
                            enabled = !isRunningSetup && !isImportingFirmware,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier.weight(0.75f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Lưu lại",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onRunConfiguration,
                            enabled = validation.isValid && !isRunningSetup && !isImportingFirmware,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B5E20),
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1.55f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = when {
                                    isImportingFirmware -> "ĐANG NẠP FILE FIRMWARE..."
                                    settings.enableFirmwareUpdate && isOntGatewayOnline -> "RUN • ĐỔ CẤU HÌNH + UP FW"
                                    settings.enableFirmwareUpdate -> "RUN • ĐỔ CẤU HÌNH + UP FW (TỰ DÒ)"
                                    isOntGatewayOnline -> "RUN • ĐỔ CẤU HÌNH ONT"
                                    else -> "RUN • ĐỔ CẤU HÌNH (TỰ DÒ)"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                FooterSignatureBar()
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Wi-Fi Status & Quick Switch Bar directly on Review Screen
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isOntGatewayOnline) {
                        Color(0xFFE8F5E9)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    if (isOntGatewayOnline) Color(0xFF81C784) else Color(0xFF90CAF9)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isOntGatewayOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = if (isOntGatewayOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.padding(end = 8.dp)) {
                            Text(
                                text = "Sóng: ${connectedWifiSsid.ifBlank { "Chưa bắt Wi-Fi ONT" }} (${connectedBand.label})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isOntGatewayOnline) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isOntGatewayOnline) {
                                    "ONT 192.168.1.1: Sẵn sàng đổ cấu hình"
                                } else {
                                    "ONT 192.168.1.1: Chưa kết nối sóng ONT"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOntGatewayOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(
                        onClick = onOpenWifiScanner,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiFind,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bắt Wi-Fi ONT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Validation Error Banner placed prominently near the top so KTV sees what's missing immediately
            if (!validation.isValid) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = "⚠️ Cần bổ sung/kiểm tra trước khi bấm RUN:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        validation.errors.forEach { err ->
                            Text(
                                text = "• $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Section 1: Read-only Subscriber Info
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "1. Thông Tin Thuê Bao (Từ Excel)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• Tên TB: ${record.tenTb}  |  SĐT: ${record.soDt.ifBlank { "N/A" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "• Địa chỉ: ${record.diachiTb}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Tổ: ${record.toDiaBan.ifBlank { "N/A" }}  |  NVKT: ${record.nvkt.ifBlank { "N/A" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 2: Editable GPON SLID & WAN PPPoE Parameters
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "2. Tham Số GPON & WAN PPPoE (Tự động lưu)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val slidInvalid = record.slid.trim().isEmpty() || record.slid.trim().length > 10
                    OutlinedTextField(
                        value = record.slid,
                        onValueChange = { newSlid ->
                            val oldSlid = record.slid.trim()
                            val wasDefaultSsid = record.ssid24g.isBlank() ||
                                record.ssid24g == "VNPT_$oldSlid" ||
                                record.ssid24g == "VNPT_${record.soMay.trim()}"
                            val updatedSsid24g = if (wasDefaultSsid) {
                                if (newSlid.trim().isNotEmpty()) "VNPT_${newSlid.trim()}" else ""
                            } else {
                                record.ssid24g
                            }
                            val updatedSsid5g = if (wasDefaultSsid && autoSyncWifi5g) {
                                ExcelManager.deriveWifi5Ssid(updatedSsid24g, "", settings.defaultWifi5Ssid)
                            } else {
                                record.ssid5g
                            }
                            onUpdateRecord(
                                record.copy(
                                    slid = newSlid,
                                    soMay = newSlid,
                                    ssid24g = updatedSsid24g,
                                    ssid5g = updatedSsid5g
                                )
                            )
                        },
                        label = {
                            Text(
                                text = "SLID / Mã MEN (${record.slid.length}/10)",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        isError = slidInvalid,
                        keyboardOptions = safeKeyboardOptions,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = record.accountPppoe,
                            onValueChange = { newAcc ->
                                onUpdateRecord(record.copy(accountPppoe = newAcc))
                            },
                            label = {
                                Text(
                                    text = "Account (ma_tb)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            isError = record.accountPppoe.isBlank(),
                            keyboardOptions = safeKeyboardOptions,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = record.passPppoe,
                            onValueChange = { newPass ->
                                onUpdateRecord(record.copy(passPppoe = newPass))
                            },
                            label = {
                                Text(
                                    text = "Pass PPPoE *",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            placeholder = {
                                Text(
                                    text = "Nhập mật khẩu...",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            },
                            isError = record.passPppoe.isBlank(),
                            visualTransformation = if (showPppoePass) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPppoePass = !showPppoePass }) {
                                    Icon(
                                        imageVector = if (showPppoePass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Ẩn/Hiện mật khẩu PPPoE"
                                    )
                                }
                            },
                            keyboardOptions = safeKeyboardOptions,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = settings.adminNewPass,
                        onValueChange = { newAdminPass ->
                            onUpdateSettings(settings.copy(adminNewPass = newAdminPass))
                        },
                        label = {
                            Text(
                                text = "Mật khẩu Admin ONT mới (8..32 ký tự ASCII)",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        isError = !OntInputValidator.validatePasswordComplexity(settings.adminNewPass),
                        visualTransformation = if (showAdminPass) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showAdminPass = !showAdminPass }) {
                                Icon(
                                    imageVector = if (showAdminPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Ẩn/Hiện mật khẩu Admin"
                                )
                            }
                        },
                        keyboardOptions = safeKeyboardOptions,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Section 3: Editable Wi-Fi 2.4G & 5G Parameters (Step 6 & Final Step 7)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "3. Tham Số Wi-Fi 2.4G & 5G (Đổi sóng ở bước cuối)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (isUsingDefaultMenSsid) {
                        Surface(
                            color = Color(0xFFFFF3E0),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "ℹ️ File Excel để trống tên Wi-Fi nên đang điền mặc định '${record.ssid24g}'. Bạn có thể đổi theo yêu cầu khách hàng:",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE65100),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = secModeDropdownExpanded,
                        onExpandedChange = {
                            if (!isRunningSetup && !isImportingFirmware) {
                                secModeDropdownExpanded = !secModeDropdownExpanded
                            }
                        }
                    ) {
                        OutlinedTextField(
                            value = selectedSecMode.displayLabel,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isRunningSetup && !isImportingFirmware,
                            label = {
                                Text(
                                    text = "Chuẩn bảo mật Wi-Fi (2.4G & 5G)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = secModeDropdownExpanded)
                            },
                            singleLine = true,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = secModeDropdownExpanded,
                            onDismissRequest = { secModeDropdownExpanded = false }
                        ) {
                            WifiSecurityMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.displayLabel) },
                                    onClick = {
                                        secModeDropdownExpanded = false
                                        onUpdateSettings(settings.copy(wifiSecurityMode = mode.formValue))
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tự động đồng bộ Wi-Fi 5G = '{Tên 2.4G} 5G'",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = autoSyncWifi5g,
                            onCheckedChange = onAutoSyncWifi5gChange
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = record.ssid24g,
                            onValueChange = { newSsid24 ->
                                val updated5g = if (autoSyncWifi5g) {
                                    val candidate = "${newSsid24.trim()} 5G"
                                    if (candidate.length > 32) candidate.substring(0, 32).trim() else candidate
                                } else {
                                    record.ssid5g
                                }
                                onUpdateRecord(record.copy(ssid24g = newSsid24, ssid5g = updated5g))
                            },
                            label = {
                                Text(
                                    text = "Tên Wi-Fi 2.4G",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            isError = record.ssid24g.trim().length !in 1..32,
                            keyboardOptions = safeKeyboardOptions,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = record.pass24g,
                            onValueChange = { newPass24 ->
                                val updatedPass5g = if (autoSyncWifi5g) newPass24 else record.pass5g
                                onUpdateRecord(record.copy(pass24g = newPass24, pass5g = updatedPass5g))
                            },
                            label = {
                                Text(
                                    text = if (requiresWifiPass) {
                                        "Pass 2.4G (${record.pass24g.length} KT, ≥8)"
                                    } else {
                                        "Pass 2.4G (Open - Không mật khẩu)"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            isError = (requiresWifiPass && record.effectiveWifi24Pass.length !in 8..63) ||
                                !OntInputValidator.isPrintableAscii(record.pass24g) ||
                                record.pass24g.any { it in OntInputValidator.UNSAFE_SHELL_METACHARS },
                            visualTransformation = if (showWifiPass) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showWifiPass = !showWifiPass }) {
                                    Icon(
                                        imageVector = if (showWifiPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Ẩn/Hiện mật khẩu Wi-Fi"
                                    )
                                }
                            },
                            keyboardOptions = safeKeyboardOptions,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // [Red Team RT-03] 1-tap button to prepend '0' when Wi-Fi password has 7 digits
                    if (validation.canPrependZero24g || validation.canPrependZero5g) {
                        Button(
                            onClick = onPrependZeroWifiPass,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF6C00)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Thêm số 0 ở đầu (0${record.pass24g.trim()}) cho đủ 8 ký tự WPA2",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = record.ssid5g,
                            onValueChange = { newSsid5 ->
                                onUpdateRecord(record.copy(ssid5g = newSsid5))
                            },
                            enabled = !autoSyncWifi5g,
                            label = {
                                Text(
                                    text = "Tên Wi-Fi 5G",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            isError = record.ssid5g.trim().length !in 1..32,
                            keyboardOptions = safeKeyboardOptions,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = record.pass5g,
                            onValueChange = { newPass5 ->
                                onUpdateRecord(record.copy(pass5g = newPass5))
                            },
                            enabled = !autoSyncWifi5g,
                            label = {
                                Text(
                                    text = if (requiresWifiPass) {
                                        "Pass 5G (${record.pass5g.length} KT, ≥8)"
                                    } else {
                                        "Pass 5G (Open - Không mật khẩu)"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            isError = (requiresWifiPass && record.effectiveWifi5Pass.length !in 8..63) ||
                                !OntInputValidator.isPrintableAscii(record.pass5g) ||
                                record.pass5g.any { it in OntInputValidator.UNSAFE_SHELL_METACHARS },
                            keyboardOptions = safeKeyboardOptions,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Section 4: Firmware Update Before Final Connected Wi-Fi Step
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdateAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "4. Cập Nhật Firmware ONT (Trước bước đổi Wi-Fi chính)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nâng cấp Firmware trước bước đổi Wi-Fi kết nối chính",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.enableFirmwareUpdate,
                            enabled = !isRunningSetup && !isImportingFirmware,
                            onCheckedChange = { enabled ->
                                onUpdateSettings(settings.copy(enableFirmwareUpdate = enabled))
                            }
                        )
                    }

                    val hasValidFirmwareFile = settings.firmwareFilePath.isNotBlank() &&
                        OntInputValidator.isValidFirmwareExtension(settings.firmwareFileName) &&
                        settings.firmwareFileSize in OntInputValidator.MIN_FIRMWARE_SIZE_BYTES..OntInputValidator.MAX_FIRMWARE_SIZE_BYTES

                    if (hasValidFirmwareFile) {
                        val sizeMb = String.format(
                            java.util.Locale.US,
                            "%.2f",
                            settings.firmwareFileSize / (1024.0 * 1024.0)
                        )
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0xFF81C784)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📦 File Firmware: ${settings.firmwareFileName} ($sizeMb MB)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1B5E20),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else if (settings.enableFirmwareUpdate) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ Chưa chọn file Firmware (.signed / .bin / .img) hợp lệ (64 KB – 64 MB).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onPickFirmwareFile,
                        enabled = !isRunningSetup && !isImportingFirmware,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (hasValidFirmwareFile) {
                                "Đổi file Firmware (.signed / .bin / .img)"
                            } else {
                                "Chọn file Firmware (.signed / .bin / .img)"
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (settings.enableFirmwareUpdate) {
                        Text(
                            text = "ℹ️ Khi bật: App sẽ đổ Bước 1–6 (WAN, SLID, LAN, Wi-Fi băng tần phụ), sau đó tải lên & cập nhật Firmware ở Bước 7. Bước đổi Wi-Fi kết nối chính sẽ thực hiện thủ công sau khi ONT khởi động lại xong (~2 phút).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
