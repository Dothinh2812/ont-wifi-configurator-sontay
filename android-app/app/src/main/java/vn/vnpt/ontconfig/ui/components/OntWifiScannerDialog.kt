package vn.vnpt.ontconfig.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.vnpt.ontconfig.core.wifi.WifiScanCandidate

@Composable
fun OntWifiScannerDialog(
    candidates: List<WifiScanCandidate>,
    isConnecting: Boolean,
    onRefreshScan: () -> Unit,
    onConnectCandidate: (WifiScanCandidate, String?) -> Unit,
    onBindExistingWifi: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSecuredCandidate by remember { mutableStateOf<WifiScanCandidate?>(null) }
    var passphraseInput by remember { mutableStateOf("1234567890") }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        title = {
            Text(
                text = "Tìm & Bắt Wi-Fi Mặc Định ONT",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Lọc các sóng xuất xưởng GW040-NSv2_2.4G_XXXXXX / 5G_XXXXXX (ưu tiên sóng mạnh nhất & khớp BSSID):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshScan,
                        enabled = !isConnecting,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(0.95f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Quét lại",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onBindExistingWifi,
                        enabled = !isConnecting,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1.05f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Đã bắt Wi-Fi",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (candidates.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Chưa tìm thấy sóng GW040-NSv2_2.4G_XXXXXX ở gần. Hãy kiểm tra nguồn ONT hoặc bấm 'Đã bắt Wi-Fi' nếu máy đã kết nối sẵn Wi-Fi ONT trong Cài đặt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.height(220.dp)
                    ) {
                        items(candidates, key = { "${it.ssid}_${it.bssid}" }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isConnecting) {
                                        if (item.isOpen) {
                                            onConnectCandidate(item, null)
                                        } else {
                                            selectedSecuredCandidate = item
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.ssid,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "BSSID: ${item.bssid} • ${item.band.label} • ${item.rssi} dBm",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Icon(
                                        imageVector = if (item.isOpen) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = if (item.isOpen) "Mạng Open" else "Có mật khẩu"
                                    )
                                }
                            }
                        }
                    }
                }

                val secured = selectedSecuredCandidate
                if (secured != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = { Text("Mật khẩu cho ${secured.ssid}", maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { onConnectCandidate(secured, passphraseInput) },
                        enabled = !isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kết nối ${secured.ssid}", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isConnecting
            ) {
                Text("Đóng", fontWeight = FontWeight.SemiBold)
            }
        }
    )
}
