package com.carmap.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.data.BridgeUiState
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.SharedTransferRecord
import com.carmap.bridge.ui.components.BoxQrPairingDialog
import com.carmap.bridge.ui.components.CockpitPill
import com.carmap.bridge.ui.theme.AdaptiveMetrics
import com.carmap.bridge.ui.theme.CockpitAccents
import com.carmap.bridge.ui.theme.LocalAdaptiveMetrics
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BoxDashboardScreen(
    state: BridgeUiState,
    canDrawOverlays: Boolean,
    onToggleBoxService: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onReplayRecord: (SharedTransferRecord) -> Unit
) {
    val accents = LocalCockpitAccents.current
    val adaptive = LocalAdaptiveMetrics.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var showQrDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredHistory = remember(state.history, searchQuery) {
        if (searchQuery.isBlank()) state.history
        else state.history.filter {
            it.parsed.title.contains(searchQuery, ignoreCase = true) ||
                it.peerName.contains(searchQuery, ignoreCase = true)
        }
    }

    if (showQrDialog) {
        BoxQrPairingDialog(
            boxName = state.boxName,
            pin = state.pairingPin,
            onDismiss = { showQrDialog = false }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (adaptive.isTwoColumn) {
            Row(
                modifier = Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxSize()
                    .padding(horizontal = adaptive.screenPadding)
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = adaptive.navBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                ) {
                    BoxHeader(state.boxName, adaptive, accents, onShowQr = { showQrDialog = true }, onOpenSettings = onOpenSettings)
                    BoxStatusHeroCard(state, canDrawOverlays, accents, adaptive, onToggleBoxService)
                    if (!canDrawOverlays) {
                        OverlayWarningCard(accents, adaptive, onOpenSettings)
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1.15f),
                    contentPadding = PaddingValues(bottom = adaptive.navBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                ) {
                    item { HistorySectionTitle(state.history.size, accents, adaptive) }
                    if (state.history.size >= 2) {
                        item { HistorySearchBar(searchQuery, onQueryChange = { searchQuery = it }) }
                    }
                    if (filteredHistory.isEmpty()) {
                        item { EmptyHistoryCard(adaptive) }
                    } else {
                        items(filteredHistory, key = { it.id }) { rec ->
                            HistoryItemCard(rec, timeFmt, accents, adaptive, onReplayRecord)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = adaptive.screenPadding,
                    top = 6.dp,
                    end = adaptive.screenPadding,
                    bottom = adaptive.navBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                item { BoxHeader(state.boxName, adaptive, accents, onShowQr = { showQrDialog = true }, onOpenSettings = onOpenSettings) }
                item { BoxStatusHeroCard(state, canDrawOverlays, accents, adaptive, onToggleBoxService) }
                if (!canDrawOverlays) {
                    item { OverlayWarningCard(accents, adaptive, onOpenSettings) }
                }
                item { HistorySectionTitle(state.history.size, accents, adaptive) }
                if (state.history.size >= 2) {
                    item { HistorySearchBar(searchQuery, onQueryChange = { searchQuery = it }) }
                }
                if (filteredHistory.isEmpty()) {
                    item { EmptyHistoryCard(adaptive) }
                } else {
                    items(filteredHistory, key = { it.id }) { rec ->
                        HistoryItemCard(rec, timeFmt, accents, adaptive, onReplayRecord)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxHeader(
    boxName: String,
    adaptive: AdaptiveMetrics,
    accents: CockpitAccents,
    onShowQr: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "Trạm Android Box",
                fontSize = adaptive.sp(20f),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = boxName,
                fontSize = adaptive.sp(11.5f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CockpitPill(
                onClick = onShowQr,
                shape = RoundedCornerShape(999.dp),
                color = accents.emeraldContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Mã QR", fontSize = adaptive.sp(11f), fontWeight = FontWeight.Bold, color = accents.emerald)
                }
            }
            CockpitPill(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cấu hình", fontSize = adaptive.sp(11f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun BoxStatusHeroCard(
    state: BridgeUiState,
    canDrawOverlays: Boolean,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onToggleBoxService: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(accents.heroObsidianTop, accents.heroObsidian)))
                .padding(adaptive.dp(12f))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(adaptive.dp(38f))
                                .clip(CircleShape)
                                .background(if (state.boxServiceRunning) Color(0x2410B981) else Color(0x18FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DirectionsCar,
                                contentDescription = null,
                                tint = if (state.boxServiceRunning) Color(0xFF34D399) else Color(0xFF94A3B8)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (state.boxServiceRunning) {
                                    val bleLabel = if (state.bleAdvertising) " + BLE" else ""
                                    "ĐANG CHỜ LINK · LAN$bleLabel"
                                } else "ĐÃ TẠM DỪNG",
                                fontSize = adaptive.sp(11f),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = adaptive.sp(0.6f),
                                color = if (state.boxServiceRunning) Color(0xFF34D399) else Color(0xFF94A3B8)
                            )
                            Text(
                                text = "Mở bằng: ${state.boxPreferredTarget.displayName}",
                                fontSize = adaptive.sp(14f),
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    CockpitPill(
                        onClick = { onToggleBoxService(!state.boxServiceRunning) },
                        shape = RoundedCornerShape(999.dp),
                        color = if (state.boxServiceRunning) Color(0x2EFB7185) else Color(0xFF10B981)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (state.boxServiceRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (state.boxServiceRunning) "Tắt" else "Bật",
                                fontSize = adaptive.sp(12f),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxMetricPill("Tự mở", if (state.autoOpenOnBox) "Bật" else "Tắt", state.autoOpenOnBox, adaptive, Modifier.weight(1f))
                    BoxMetricPill("Từ nền", if (canDrawOverlays) "Đã cấp" else "Thiếu", canDrawOverlays, adaptive, Modifier.weight(1f))
                    BoxMetricPill("Đã nhận", "${state.history.size} chuyến", true, adaptive, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OverlayWarningCard(accents: CockpitAccents, adaptive: AdaptiveMetrics, onOpenSettings: () -> Unit) {
    CockpitPill(
        onClick = onOpenSettings,
        shape = RoundedCornerShape(18.dp),
        color = accents.amberContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Layers, contentDescription = null, tint = accents.amber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Chưa cấp quyền mở từ nền (Overlay)", fontSize = adaptive.sp(13f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Nhấn để vào Cài đặt cấp quyền ngay", fontSize = adaptive.sp(11.5f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistorySectionTitle(count: Int, accents: CockpitAccents, adaptive: AdaptiveMetrics) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "LỊCH SỬ ĐIỂM ĐẾN ($count)",
                fontSize = adaptive.sp(10.5f),
                fontWeight = FontWeight.Bold,
                letterSpacing = adaptive.sp(0.7f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (count > 0) {
            Text(
                text = "Xóa lịch sử",
                fontSize = adaptive.sp(11f),
                fontWeight = FontWeight.SemiBold,
                color = accents.coral,
                modifier = Modifier.clickable { BridgeRepository.clearHistory() }
            )
        }
    }
}

@Composable
private fun HistorySearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
        placeholder = { Text("Tìm điểm đến đã lưu...") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EmptyHistoryCard(adaptive: AdaptiveMetrics) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Chưa có điểm đến nào", fontSize = adaptive.sp(14f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Chia sẻ vị trí từ Google Maps, Waze hoặc Vietmap trên điện thoại để tự động mở dẫn đường.",
                fontSize = adaptive.sp(12f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryItemCard(
    rec: SharedTransferRecord,
    timeFmt: SimpleDateFormat,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onReplayRecord: (SharedTransferRecord) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = accents.emeraldContainer) {
                    Text(
                        text = "${rec.parsed.sourceProvider.displayName} → ${rec.targetProvider.displayName}",
                        fontSize = adaptive.sp(11f),
                        fontWeight = FontWeight.Bold,
                        color = accents.emerald,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = timeFmt.format(Date(rec.timestampMs)),
                    fontSize = adaptive.sp(11f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = rec.parsed.title,
                fontSize = adaptive.sp(15f),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${rec.direction} · ${rec.transportUsed} · ${rec.status}",
                fontSize = adaptive.sp(12f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(2.dp))
            CockpitPill(
                onClick = { onReplayRecord(rec) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Navigation, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mở lại trên bản đồ", fontSize = adaptive.sp(12.5f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun BoxMetricPill(
    label: String,
    value: String,
    highlight: Boolean,
    adaptive: AdaptiveMetrics,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0x14FFFFFF)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = adaptive.sp(10.5f), color = Color(0xFF94A3B8), maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = adaptive.sp(12.5f),
                fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFF34D399) else Color(0xFFFB7185),
                maxLines = 1
            )
        }
    }
}
