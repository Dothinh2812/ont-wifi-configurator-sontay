package vn.vnpt.ontconfig.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vnpt.ontconfig.core.driver.MobileSetupResult
import vn.vnpt.ontconfig.core.driver.StepProgress
import vn.vnpt.ontconfig.core.driver.StepStatus
import vn.vnpt.ontconfig.core.logging.DiagnosticLogEntry
import vn.vnpt.ontconfig.core.logging.LogLevel
import vn.vnpt.ontconfig.data.model.WifiSecurityMode
import vn.vnpt.ontconfig.core.wifi.WifiBand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionProgressSheet(
    isRunning: Boolean,
    steps: List<StepProgress>,
    setupResult: MobileSetupResult?,
    errorMessage: String?,
    onJoinNewWifi: (String, String) -> Unit,
    onExportAndShareReport: () -> Unit,
    onOpenWifiScanner: () -> Unit = {},
    onRetryRun: () -> Unit = {},
    logEntriesFlow: StateFlow<List<DiagnosticLogEntry>>? = null,
    onOpenFullLogDialog: () -> Unit = {},
    onCopyDiagnosticLog: () -> Unit = {},
    onShareDiagnosticLog: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var showAdminPass by remember { mutableStateOf(false) }
    var logPanelExpanded by remember { mutableStateOf(!errorMessage.isNullOrBlank()) }
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            logPanelExpanded = true
        }
    }
    var rebootCountdownSec by remember(setupResult) {
        mutableIntStateOf(if (setupResult?.firmwareUpdated == true) 150 else 0)
    }
    LaunchedEffect(setupResult?.firmwareUpdated, setupResult?.success) {
        if (setupResult?.success == true && setupResult.firmwareUpdated) {
            rebootCountdownSec = 150
            while (rebootCountdownSec > 0) {
                delay(1_000L)
                rebootCountdownSec -= 1
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !isRunning }
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (!isRunning) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            val totalSteps = steps.firstOrNull()?.totalSteps ?: steps.size.coerceAtLeast(1)
            val completedCount = steps.count { it.status == StepStatus.SUCCESS }

            Text(
                text = when {
                    isRunning -> "🔄 Đang Đổ Cấu Hình ONT Qua Wi-Fi (2 Lần Kết Nối)..."
                    setupResult?.success == true -> "✅ Cấu Hình ONT Hoàn Tất ($totalSteps/$totalSteps Bước)"
                    else -> "❌ Cấu Hình Gián Đoạn / Cần Kiểm Tra"
                },
                fontWeight = FontWeight.Bold,
                color = when {
                    isRunning -> MaterialTheme.colorScheme.primary
                    setupResult?.success == true -> Color(0xFF1B5E20)
                    else -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { completedCount / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            val isStep7HandoverRunning = isRunning &&
                steps.any { it.stepNumber == 7 && it.status == StepStatus.RUNNING }
            if (isStep7HandoverRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                    border = BorderStroke(1.dp, Color(0xFF1E88E5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📲 Đang Chuyển Kết Nối Sang Wi-Fi Mới (Bước 7)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )
                        Text(
                            text = "Đang đợi ONT khởi động lại sóng, ổn định dịch vụ WPA/DHCP sau khi phát tên Wi-Fi mới và tự động kết nối lại có giãn cách (5s → 10s → 15s → 20s, tối đa 180s). Khi hộp thoại ghép nối của Android hiện lên, hãy bấm 'Kết nối / Connect'!",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1565C0)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                items(steps, key = { it.stepNumber }) { step ->
                    val (icon, tint, containerBg, borderCol) = when (step.status) {
                        StepStatus.PENDING -> Quadruple(
                            Icons.Default.HourglassEmpty,
                            MaterialTheme.colorScheme.outline,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            Color.Transparent
                        )
                        StepStatus.RUNNING -> Quadruple(
                            Icons.Default.Sync,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        StepStatus.SUCCESS -> Quadruple(
                            Icons.Default.CheckCircle,
                            Color(0xFF2E7D32),
                            Color(0xFFE8F5E9),
                            Color(0xFF81C784)
                        )
                        StepStatus.FAILED -> Quadruple(
                            Icons.Default.Error,
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = containerBg),
                        border = if (borderCol != Color.Transparent) BorderStroke(1.dp, borderCol) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = step.status.name,
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = step.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (step.status == StepStatus.FAILED) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                if (step.detail.isNotBlank()) {
                                    Text(
                                        text = step.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (step.status == StepStatus.FAILED) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!errorMessage.isNullOrBlank() && !isRunning) {
                val alreadyShownInStep = steps.any { it.status == StepStatus.FAILED && it.detail == errorMessage }
                Spacer(modifier = Modifier.height(10.dp))
                if (!alreadyShownInStep) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Quick Recovery Action Row directly inside the error sheet
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenWifiScanner,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiFind,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Bắt Wi-Fi ONT",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onRetryRun,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Chạy Lại (RUN)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (logEntriesFlow != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { logPanelExpanded = !logPanelExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "📋 Nhật ký tiến trình & Debug",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (logPanelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (logPanelExpanded) "Thu gọn nhật ký" else "Mở nhật ký"
                            )
                        }

                        if (logPanelExpanded) {
                            // [Red Team RT-08] Collect entriesFlow ONLY while expanded (last 200 lines)
                            val coalescedEntries by collectCoalescedLogEntries(
                                logEntriesFlow = logEntriesFlow,
                                flushImmediately = !isRunning
                            )
                            val recentEntries = remember(coalescedEntries) {
                                coalescedEntries.takeLast(200)
                            }
                            val miniLogListState = rememberLazyListState()
                            LaunchedEffect(recentEntries.lastOrNull()?.seq, isRunning) {
                                if (recentEntries.isNotEmpty()) {
                                    miniLogListState.scrollToItem(recentEntries.lastIndex)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(145.dp)
                                    .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                    .padding(6.dp)
                            ) {
                                if (recentEntries.isEmpty()) {
                                    Text(
                                        text = "Chưa có dòng nhật ký nào trong phiên hiện tại.",
                                        color = Color(0xFF94A3B8),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                } else {
                                    LazyColumn(
                                        state = miniLogListState,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(recentEntries, key = { it.seq }) { entry ->
                                            val color = when (entry.level) {
                                                LogLevel.ERROR -> Color(0xFFFF8A80)
                                                LogLevel.WARN -> Color(0xFFFFD54F)
                                                LogLevel.INFO -> Color(0xFFE2E8F0)
                                            }
                                            Text(
                                                text = entry.formatLine(),
                                                color = color,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                lineHeight = 13.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenFullLogDialog,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.OpenInFull, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Mở rộng", fontSize = 11.sp, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onCopyDiagnosticLog,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Chép Log", fontSize = 11.sp, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onShareDiagnosticLog,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1.25f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Chia sẻ Log (.txt)", fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            if (setupResult != null && setupResult.success) {
                val secModeObj = WifiSecurityMode.fromFormValue(setupResult.wifiSecurityMode)
                    ?: WifiSecurityMode.WPA2_WPA3_PSK
                val isOpenWifi = secModeObj == WifiSecurityMode.OPEN
                if (setupResult.firmwareUpdated) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF8E1)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFFFB300)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdateAlt,
                                    contentDescription = null,
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "🔄 ONT Đang Tự Ghi Flash & Khởi Động Lại (~2 phút 20 giây)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100)
                                )
                            }
                            val countdownText = if (rebootCountdownSec > 0) {
                                val mins = rebootCountdownSec / 60
                                val secs = rebootCountdownSec % 60
                                String.format(
                                    java.util.Locale.US,
                                    "⏳ Thời gian chờ tham khảo: còn %02d:%02d (%ds) — Tuyệt đối không rút nguồn ONT!",
                                    mins,
                                    secs,
                                    rebootCountdownSec
                                )
                            } else {
                                "✅ Đã qua 2 phút 30 giây — ONT đã có thể hoàn tất khởi động lại với Firmware mới (${setupResult.firmwareFileName})!"
                            }
                            Text(
                                text = countdownText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (rebootCountdownSec > 0) Color(0xFFBF360C) else Color(0xFF1B5E20)
                            )
                            Text(
                                text = "• Cả 2 băng tần Wi-Fi (${setupResult.newSsid24g} / ${setupResult.newSsid5g}) và Firmware (${setupResult.firmwareFileName}) đã hoàn tất 100% — không cần đổi Wi-Fi thủ công sau khi ONT khởi động lại.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1B5E20)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val displayedAdminPass = if (showAdminPass) {
                                    setupResult.updatedAdminPass
                                } else {
                                    "••••••••"
                                }
                                Text(
                                    text = "• Web Admin (192.168.1.1): admin / $displayedAdminPass",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4E342E),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { showAdminPass = !showAdminPass },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showAdminPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Ẩn/Hiện mật khẩu Admin",
                                        tint = Color(0xFF4E342E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF81C784)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Thông tin bàn giao khách hàng:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "• Account PPPoE: ${setupResult.accountPppoe} | SLID: ${setupResult.slid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20)
                        )
                        val pass24Display = if (isOpenWifi) {
                            "Không mật khẩu (Open)"
                        } else {
                            "Pass: ${setupResult.newPass24g}"
                        }
                        val pass5Display = if (isOpenWifi) {
                            "Không mật khẩu (Open)"
                        } else {
                            "Pass: ${setupResult.newPass5g}"
                        }
                        Text(
                            text = "• Bảo mật Wi-Fi: ${secModeObj.displayLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "• Wi-Fi 2.4G: ${setupResult.newSsid24g} ($pass24Display)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "• Wi-Fi 5G: ${setupResult.newSsid5g} ($pass5Display)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "• Đã tự động khôi phục mạng 4G/5G để xuất báo cáo Zalo ngay lập tức.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val prefer5gJoin = setupResult.resolvedConnectedBand == WifiBand.BAND_5_GHZ &&
                    setupResult.newSsid5g.isNotBlank()
                val joinSsid = if (prefer5gJoin) {
                    setupResult.newSsid5g
                } else {
                    setupResult.newSsid24g
                }
                val joinPass = if (prefer5gJoin) {
                    setupResult.newPass5g
                } else {
                    setupResult.newPass24g
                }
                val joinLabel = "Kết nối vào Wi-Fi mới ('$joinSsid') để kiểm tra mạng"
                OutlinedButton(
                    onClick = {
                        onJoinNewWifi(joinSsid, joinPass)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(joinLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = onExportAndShareReport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Xuất & Chia Sẻ Báo Cáo (.xlsx) Qua Zalo")
                }
            }

            if (!isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Đóng bảng tiến trình", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
