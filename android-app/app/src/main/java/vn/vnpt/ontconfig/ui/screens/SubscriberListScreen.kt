package vn.vnpt.ontconfig.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayLesson
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vnpt.ontconfig.core.wifi.WifiBand
import vn.vnpt.ontconfig.data.model.SubscriberRecord
import vn.vnpt.ontconfig.data.model.SubscriberStatus
import vn.vnpt.ontconfig.ui.components.FooterSignatureBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriberListScreen(
    records: List<SubscriberRecord>,
    allRecords: List<SubscriberRecord>,
    searchQuery: String,
    selectedStatusFilter: SubscriberStatus?,
    connectedWifiSsid: String,
    connectedBand: WifiBand,
    isOntGatewayOnline: Boolean,
    consecutiveProbeSuccess: Int,
    statusMessage: String?,
    onSearchQueryChange: (String) -> Unit,
    onStatusFilterChange: (SubscriberStatus?) -> Unit,
    onPickExcelFile: () -> Unit,
    onLoadDemoExcel: () -> Unit,
    onExportReport: () -> Unit,
    onOpenWifiScanner: () -> Unit,
    onSelectSubscriber: (SubscriberRecord) -> Unit
) {
    val totalRecordsCount = allRecords.size
    val readyCount = allRecords.count { it.status == SubscriberStatus.PENDING }
    val successCount = allRecords.count { it.status == SubscriberStatus.SUCCESS }
    val errorCount = allRecords.count {
        it.status == SubscriberStatus.INVALID_DATA || it.status == SubscriberStatus.FAILED
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Cấu Hình ONT VNPT Qua Wi-Fi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "iGate GW040-NSv2 • Đổi Wi-Fi 2.4G ở bước cuối",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
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
            FooterSignatureBar()
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Wi-Fi Status Banner (OntWifiStatusBanner)
            OntWifiStatusBanner(
                connectedWifiSsid = connectedWifiSsid,
                connectedBand = connectedBand,
                isOntGatewayOnline = isOntGatewayOnline,
                consecutiveProbeSuccess = consecutiveProbeSuccess,
                onOpenWifiScanner = onOpenWifiScanner
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Excel Import / Demo / Export Toolbar (Compact padding so text never truncates)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onPickExcelFile,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Nạp Excel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onLoadDemoExcel,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayLesson,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Mẫu Demo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onExportReport,
                    enabled = totalRecordsCount > 0,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1.08f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Xuất Báo Cáo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Instant Search Bar (Single-line label so height stays compact)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = {
                    Text(
                        text = "Tìm ma_tb, SLID, Tên TB, SĐT...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                placeholder = {
                    Text(
                        text = "Nhập Account, SLID hoặc SĐT (có/không số 0)",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa tìm kiếm")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Single-row horizontally scrollable Status Filter Chips with live counts
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = selectedStatusFilter == null,
                    onClick = { onStatusFilterChange(null) },
                    label = { Text("Tất cả ($totalRecordsCount)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedStatusFilter == SubscriberStatus.PENDING,
                    onClick = { onStatusFilterChange(SubscriberStatus.PENDING) },
                    label = { Text("Sẵn sàng ($readyCount)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFEF6C00),
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedStatusFilter == SubscriberStatus.SUCCESS,
                    onClick = { onStatusFilterChange(SubscriberStatus.SUCCESS) },
                    label = { Text("Đã xong ($successCount)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = selectedStatusFilter == SubscriberStatus.INVALID_DATA,
                    onClick = { onStatusFilterChange(SubscriberStatus.INVALID_DATA) },
                    label = { Text("Cần sửa ($errorCount)", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFC62828),
                        selectedLabelColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Subscriber Cards List
            if (records.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = if (totalRecordsCount == 0) {
                            "Chưa có dữ liệu thuê bao. Hãy bấm 'Nạp Excel' để chọn file (.xlsx) hoặc bấm 'Mẫu Demo' để trải nghiệm."
                        } else {
                            "Không tìm thấy thuê bao khớp điều kiện lọc / từ khóa '$searchQuery'."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(records, key = { it.rowIndex }) { record ->
                        SubscriberCard(
                            record = record,
                            onClick = { onSelectSubscriber(record) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OntWifiStatusBanner(
    connectedWifiSsid: String,
    connectedBand: WifiBand,
    isOntGatewayOnline: Boolean,
    @Suppress("UNUSED_PARAMETER") consecutiveProbeSuccess: Int,
    onOpenWifiScanner: () -> Unit
) {
    val bgColor = if (isOntGatewayOnline) {
        Color(0xFFE8F5E9)
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val borderColor = if (isOntGatewayOnline) {
        Color(0xFF81C784)
    } else {
        Color(0xFF90CAF9)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
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
                    val ssidDisplay = connectedWifiSsid.ifBlank { "Chưa bắt Wi-Fi ONT" }
                    Text(
                        text = "$ssidDisplay (${connectedBand.label})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isOntGatewayOnline) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val gatewayText = when {
                        isOntGatewayOnline -> "ONT 192.168.1.1: ONLINE (Đã xác thực)"
                        connectedWifiSsid.isNotBlank() -> "ONT 192.168.1.1: Đang dò cổng..."
                        else -> "ONT 192.168.1.1: Chờ bắt sóng Wi-Fi ONT"
                    }
                    Text(
                        text = gatewayText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOntGatewayOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onOpenWifiScanner,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isOntGatewayOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.WifiFind,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Bắt Wi-Fi ONT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SubscriberCard(
    record: SubscriberRecord,
    onClick: () -> Unit
) {
    val (badgeColor, badgeText, cardBorderColor) = when (record.status) {
        SubscriberStatus.SUCCESS -> Triple(Color(0xFF2E7D32), "THÀNH CÔNG", Color(0xFFA5D6A7))
        SubscriberStatus.INVALID_DATA -> Triple(Color(0xFFC62828), "THIẾU PASS PPPOE", Color(0xFFEF9A9A))
        SubscriberStatus.FAILED -> Triple(Color(0xFFD32F2F), "LỖI CẤU HÌNH", Color(0xFFEF9A9A))
        SubscriberStatus.RUNNING -> Triple(Color(0xFF1565C0), "ĐANG CHẠY", Color(0xFF90CAF9))
        SubscriberStatus.PENDING -> Triple(Color(0xFFEF6C00), "SẴN SÀNG", Color(0xFFCBD5E1))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, cardBorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ma_tb: ${record.accountPppoe} • SLID: ${record.slid}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "${record.tenTb} • SĐT: ${record.soDt.ifBlank { "N/A" }}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = record.diachiTb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Wi-Fi 2.4G: ${record.effectiveWifi24Ssid}  •  5G: ${record.effectiveWifi5Ssid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (record.validationErrors.isNotEmpty() || record.errorMessage.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = record.errorMessage.ifBlank { record.validationErrors.joinToString("; ") },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
