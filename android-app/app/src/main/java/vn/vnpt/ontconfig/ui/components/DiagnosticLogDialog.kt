package vn.vnpt.ontconfig.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import vn.vnpt.ontconfig.core.logging.DiagnosticLogEntry
import vn.vnpt.ontconfig.core.logging.LogLevel

enum class DiagnosticLogFilter(val label: String) {
    ALL("Tất cả"),
    WARN_AND_ERROR("Cảnh báo & Lỗi"),
    WIFI_AND_HANDOVER("Wi-Fi & Handover")
}

private val HANDOVER_TAGS = setOf("WIFI", "HANDOVER", "STEP6", "STEP7", "VERIFY", "AUTH")

/**
 * Collects [logEntriesFlow] with 200ms conflated coalescing so bursts of HTTP/Wi-Fi logs
 * never cause per-line Compose recomposition storms.
 */
@Composable
fun collectCoalescedLogEntries(
    logEntriesFlow: StateFlow<List<DiagnosticLogEntry>>,
    flushImmediately: Boolean = false
): State<List<DiagnosticLogEntry>> = produceState(
    initialValue = logEntriesFlow.value,
    key1 = logEntriesFlow,
    key2 = flushImmediately
) {
    value = logEntriesFlow.value
    var isFirstEmission = true
    logEntriesFlow.collect { latest ->
        if (!isFirstEmission && !flushImmediately) {
            delay(200L)
        }
        isFirstEmission = false
        value = logEntriesFlow.value
    }
}

@Composable
fun DiagnosticLogDialog(
    logEntriesFlow: StateFlow<List<DiagnosticLogEntry>>,
    onCopyLogs: () -> Unit,
    onShareLogsTxt: () -> Unit,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val entries by collectCoalescedLogEntries(logEntriesFlow = logEntriesFlow)
    var selectedFilter by remember { mutableStateOf(DiagnosticLogFilter.ALL) }
    val filteredEntries = remember(entries, selectedFilter) {
        when (selectedFilter) {
            DiagnosticLogFilter.ALL -> entries
            DiagnosticLogFilter.WARN_AND_ERROR -> entries.filter { it.level != LogLevel.INFO }
            DiagnosticLogFilter.WIFI_AND_HANDOVER -> entries.filter {
                it.tag in HANDOVER_TAGS ||
                    it.message.contains("Wi-Fi", ignoreCase = true) ||
                    it.message.contains("DHCP", ignoreCase = true)
            }
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📋 Nhật Ký Tiến Trình & Debug ONT",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Hiển thị ${filteredEntries.size}/${entries.size} dòng (đã tự động ẩn mật khẩu)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Đóng")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DiagnosticLogFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = "Chưa có dòng nhật ký nào khớp bộ lọc hiện tại.",
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(filteredEntries, key = { it.seq }) { entry ->
                                val lineColor = when (entry.level) {
                                    LogLevel.ERROR -> Color(0xFFFF8A80)
                                    LogLevel.WARN -> Color(0xFFFFD54F)
                                    LogLevel.INFO -> Color(0xFFE2E8F0)
                                }
                                Text(
                                    text = entry.formatLine(),
                                    color = lineColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (filteredEntries.isNotEmpty()) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(filteredEntries.lastIndex)
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cuộn cuối", fontSize = 11.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onCopyLogs,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Chép Log", fontSize = 11.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onClearLogs,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Xóa Log", fontSize = 11.sp, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onShareLogsTxt,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Chia sẻ (.txt) qua Zalo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text("Đóng", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
