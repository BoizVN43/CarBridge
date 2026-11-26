package com.carmap.bridge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.platform.LocalContext
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
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
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
            Column(
                modifier = Modifier
                    .widthIn(max = 1140.dp)
                    .fillMaxSize()
                    .padding(horizontal = adaptive.screenPadding)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                BoxTopBar(
                    state = state,
                    adaptive = adaptive,
                    accents = accents,
                    onShowQr = { showQrDialog = true },
                    onToggleBoxService = onToggleBoxService
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.92f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = adaptive.navBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                    ) {
                        BoxStatusHeroCard(state, canDrawOverlays, accents, adaptive)
                        if (!canDrawOverlays) {
                            OverlayWarningCard(accents, adaptive, onOpenSettings)
                        }
                        if (!state.lastReceivedClipboard.isNullOrBlank()) {
                            BoxClipboardCard(state.lastReceivedClipboard, accents, adaptive)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1.18f)
                            .padding(bottom = adaptive.navBottomPadding),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(adaptive.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HistorySectionTitle(state.history.size, accents, adaptive)
                            if (state.history.size >= 2) {
                                HistorySearchBar(searchQuery, onQueryChange = { searchQuery = it })
                            }
                            if (filteredHistory.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EmptyHistoryInline(adaptive)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filteredHistory, key = { it.id }) { rec ->
                                        HistoryItemRow(rec, timeFmt, accents, adaptive, onReplayRecord)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = adaptive.screenPadding,
                    top = 8.dp,
                    end = adaptive.screenPadding,
                    bottom = adaptive.navBottomPadding
                ),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                item {
                    BoxTopBar(
                        state = state,
                        adaptive = adaptive,
                        accents = accents,
                        onShowQr = { showQrDialog = true },
                        onToggleBoxService = onToggleBoxService
                    )
                }
                item { BoxStatusHeroCard(state, canDrawOverlays, accents, adaptive) }
                if (!canDrawOverlays) {
                    item { OverlayWarningCard(accents, adaptive, onOpenSettings) }
                }
                if (!state.lastReceivedClipboard.isNullOrBlank()) {
                    item { BoxClipboardCard(state.lastReceivedClipboard, accents, adaptive) }
                }
                item { HistorySectionTitle(state.history.size, accents, adaptive) }
                if (state.history.size >= 2) {
                    item { HistorySearchBar(searchQuery, onQueryChange = { searchQuery = it }) }
                }
                if (filteredHistory.isEmpty()) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            EmptyHistoryInline(adaptive)
                        }
                    }
                } else {
                    items(filteredHistory, key = { it.id }) { rec ->
                        HistoryItemRow(rec, timeFmt, accents, adaptive, onReplayRecord)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxTopBar(
    state: BridgeUiState,
    adaptive: AdaptiveMetrics,
    accents: CockpitAccents,
    onShowQr: () -> Unit,
    onToggleBoxService: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(adaptive.dp(36f))
                    .clip(CircleShape)
                    .background(if (state.boxServiceRunning) accents.emeraldContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.DirectionsCar,
                    contentDescription = null,
                    tint = if (state.boxServiceRunning) accents.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(adaptive.dp(19f))
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.boxName,
                    fontSize = adaptive.sp(17.5f),
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .clip(CircleShape)
                            .background(if (state.boxServiceRunning) accents.emerald else accents.coral)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (state.boxServiceRunning) {
                            val bleLabel = if (state.bleAdvertising) " + BLE" else ""
                            "Đang lắng nghe · LAN$bleLabel"
                        } else "Đã tạm dừng lắng nghe",
                        fontSize = adaptive.sp(11.5f),
                        fontWeight = FontWeight.Medium,
                        color = if (state.boxServiceRunning) accents.emerald else accents.coral,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CockpitPill(
                onClick = onShowQr,
                shape = RoundedCornerShape(999.dp),
                color = accents.emeraldContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("QR", fontSize = adaptive.sp(11.5f), fontWeight = FontWeight.Bold, color = accents.emerald)
                }
            }
            CockpitPill(
                onClick = { onToggleBoxService(!state.boxServiceRunning) },
                shape = RoundedCornerShape(999.dp),
                color = if (state.boxServiceRunning) accents.coralContainer else Color(0xFF10B981)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (state.boxServiceRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (state.boxServiceRunning) accents.coral else Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = if (state.boxServiceRunning) "Tắt" else "Bật",
                        fontSize = adaptive.sp(11.5f),
                        fontWeight = FontWeight.Bold,
                        color = if (state.boxServiceRunning) accents.coral else Color.White
                    )
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
    adaptive: AdaptiveMetrics
) {
    val heroBrush = remember(accents) {
        Brush.verticalGradient(listOf(accents.heroObsidianTop, accents.heroObsidian))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBrush)
                .padding(adaptive.dp(14f))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "CHẾ ĐỘ DẪN ĐƯỜNG TỰ ĐỘNG",
                            fontSize = adaptive.sp(10f),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = adaptive.sp(0.7f),
                            color = Color(0xFF34D399),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "App bản đồ: ${state.boxPreferredTarget.displayName}",
                            fontSize = adaptive.sp(13.5f),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0x1C34D399)
                    ) {
                        Text(
                            text = "PIN ${state.pairingPin}",
                            fontSize = adaptive.sp(11f),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF34D399),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BoxMetricPill("Tự mở", if (state.autoOpenOnBox) "Bật" else "Tắt", state.autoOpenOnBox, adaptive, Modifier.weight(1f))
                    BoxMetricPill("Mở từ nền", if (canDrawOverlays) "Đã cấp" else "Thiếu", canDrawOverlays, adaptive, Modifier.weight(1f))
                    BoxMetricPill("Đã nhận", "${state.history.size} mục", true, adaptive, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BoxClipboardCard(
    text: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accents.cyan.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(adaptive.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accents.cyanContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CLIPBOARD VỪA NHẬN",
                    fontSize = adaptive.sp(10f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = adaptive.sp(0.7f),
                    color = accents.cyan
                )
                Text(
                    text = text,
                    fontSize = adaptive.sp(13f),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            CockpitPill(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("CarBridge", text))
                    Toast.makeText(context, "Đã chép lại vào Clipboard", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(999.dp),
                color = accents.cyanContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Chép", fontSize = adaptive.sp(11f), fontWeight = FontWeight.Bold, color = accents.cyan)
                }
            }
        }
    }
}

@Composable
private fun OverlayWarningCard(accents: CockpitAccents, adaptive: AdaptiveMetrics, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenSettings),
        shape = RoundedCornerShape(16.dp),
        color = accents.amberContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Layers, contentDescription = null, tint = accents.amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Chưa cấp quyền mở bản đồ từ nền", fontSize = adaptive.sp(12.5f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Cần bật để tự mở Google Maps khi app chạy ngầm", fontSize = adaptive.sp(11f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Cấp ngay", fontSize = adaptive.sp(11.5f), fontWeight = FontWeight.ExtraBold, color = accents.amber)
        }
    }
}

@Composable
private fun HistorySectionTitle(count: Int, accents: CockpitAccents, adaptive: AdaptiveMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                text = "Xóa tất cả",
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
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(17.dp)) },
        placeholder = { Text("Tìm điểm đến đã lưu...") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun EmptyHistoryInline(adaptive: AdaptiveMetrics) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Chưa có điểm đến nào",
            fontSize = adaptive.sp(13.5f),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Chia sẻ vị trí từ Google Maps, Waze, Vietmap hoặc YouTube trên điện thoại để Box tự động mở ngay.",
            fontSize = adaptive.sp(11.5f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryItemRow(
    rec: SharedTransferRecord,
    timeFmt: SimpleDateFormat,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onReplayRecord: (SharedTransferRecord) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when (rec.parsed.kind) {
                            LinkKind.MEDIA -> "Media"
                            LinkKind.WEB -> "Web"
                            LinkKind.FILE -> "Tệp / APK"
                            LinkKind.CLIPBOARD -> "Clipboard"
                            else -> rec.targetProvider.displayName
                        },
                        fontSize = adaptive.sp(10.5f),
                        fontWeight = FontWeight.Bold,
                        color = if (rec.parsed.kind == LinkKind.CLIPBOARD) accents.cyan else accents.emerald
                    )
                    Text(
                        text = "· ${timeFmt.format(Date(rec.timestampMs))} · ${rec.transportUsed}",
                        fontSize = adaptive.sp(10.5f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = rec.parsed.title,
                    fontSize = adaptive.sp(13.5f),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            CockpitPill(
                onClick = { onReplayRecord(rec) },
                shape = RoundedCornerShape(999.dp),
                color = if (rec.parsed.kind == LinkKind.CLIPBOARD) accents.cyanContainer else accents.emeraldContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (rec.parsed.kind) {
                            LinkKind.MEDIA -> Icons.Rounded.MusicNote
                            LinkKind.CLIPBOARD -> Icons.Rounded.ContentCopy
                            else -> Icons.Rounded.Navigation
                        },
                        contentDescription = null,
                        tint = if (rec.parsed.kind == LinkKind.CLIPBOARD) accents.cyan else accents.emerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (rec.parsed.kind) {
                            LinkKind.MEDIA, LinkKind.WEB, LinkKind.FILE -> "Mở"
                            LinkKind.CLIPBOARD -> "Chép"
                            else -> "Dẫn đường"
                        },
                        fontSize = adaptive.sp(11f),
                        fontWeight = FontWeight.Bold,
                        color = if (rec.parsed.kind == LinkKind.CLIPBOARD) accents.cyan else accents.emerald
                    )
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
        shape = RoundedCornerShape(12.dp),
        color = Color(0x14FFFFFF)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = adaptive.sp(10f), color = Color(0xFF94A3B8), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = adaptive.sp(12.5f),
                fontWeight = FontWeight.Bold,
                color = if (highlight) Color(0xFF34D399) else Color(0xFFFB7185),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
