package vn.vnpt.ontconfig.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vnpt.ontconfig.core.driver.MobileSetupResult
import vn.vnpt.ontconfig.core.driver.StepProgress
import vn.vnpt.ontconfig.core.driver.StepStatus

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
    onDismiss: () -> Unit
) {
    // [Red Team RT-06] Block swipe-to-dismiss while setup is running
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
            Text(
                text = when {
                    isRunning -> "🔄 Đang Đổ Cấu Hình ONT Qua Wi-Fi..."
                    setupResult?.success == true -> "✅ Cấu Hình ONT Hoàn Tất (7/7 Bước)"
                    else -> "❌ Cấu Hình Gián Đoạn / Cần Kiểm Tra"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    isRunning -> MaterialTheme.colorScheme.primary
                    setupResult?.success == true -> Color(0xFF1B5E20)
                    else -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            val completedCount = steps.count { it.status == StepStatus.SUCCESS }
            LinearProgressIndicator(
                progress = { completedCount / 7f },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
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

            if (setupResult != null && setupResult.success) {
                Spacer(modifier = Modifier.height(10.dp))
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
                        Text(
                            text = "• Wi-Fi 2.4G: ${setupResult.newSsid24g} (Pass: ${setupResult.newPass24g})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "• Wi-Fi 5G: ${setupResult.newSsid5g} (Pass: ${setupResult.newPass5g})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "• Đã tự động khôi phục mạng 4G/5G để xuất báo cáo Zalo.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        onJoinNewWifi(setupResult.newSsid24g, setupResult.newPass24g)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Kết nối vào Wi-Fi mới để kiểm tra mạng")
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
