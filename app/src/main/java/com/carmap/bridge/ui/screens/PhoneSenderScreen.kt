package com.carmap.bridge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.data.BridgeUiState
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.RemoteCommand
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.ui.components.CockpitPill
import com.carmap.bridge.ui.theme.AdaptiveMetrics
import com.carmap.bridge.ui.theme.CockpitAccents
import com.carmap.bridge.ui.theme.LocalAdaptiveMetrics
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import com.carmap.bridge.util.PairingCodeHelper

@Composable
fun PhoneSenderScreen(
    state: BridgeUiState,
    clipboardSuggestion: String?,
    onDismissClipboard: () -> Unit,
    onAcceptClipboardPairing: (String) -> Unit,
    onSendLink: (String, MapProvider) -> Unit,
    onSendClipboardText: (String) -> Unit,
    onSendRemoteCommand: (RemoteCommand) -> Unit,
    onPingBox: () -> Unit,
    onClearPendingQueue: () -> Unit,
    onOpenSystemShareSheet: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val accents = LocalCockpitAccents.current
    val adaptive = LocalAdaptiveMetrics.current
    var linkDraft by rememberSaveable { mutableStateOf("") }
    var clipDraft by rememberSaveable { mutableStateOf("") }
    var showAddPlaceDialog by remember { mutableStateOf(false) }

    val liveParsed = remember(linkDraft) {
        if (linkDraft.isBlank()) {
            null
        } else {
            val media = MediaLinkHelper.extractMediaLink(linkDraft)
            if (media != null) MediaLinkHelper.toParsedRecord(linkDraft, media)
            else MapLinkResolver.parseImmediate(linkDraft)
        }
    }
    val activeBox = state.pairedBox

    if (showAddPlaceDialog) {
        AddSavedPlaceDialog(
            initialLink = linkDraft,
            onSave = { label, link ->
                BridgeRepository.addSavedPlace(label, link)
                showAddPlaceDialog = false
            },
            onDismiss = { showAddPlaceDialog = false }
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
                    SenderHeader(state, activeBox != null, accents, adaptive, onOpenSettings)
                    HeroBoxTargetCard(state, accents, adaptive, onPingBox, onOpenSettings)
                    if (!clipboardSuggestion.isNullOrBlank()) {
                        ClipboardSuggestionBanner(
                            text = clipboardSuggestion,
                            accents = accents,
                            adaptive = adaptive,
                            onAction = {
                                if (PairingCodeHelper.parsePairingUri(clipboardSuggestion) != null) {
                                    onAcceptClipboardPairing(clipboardSuggestion)
                                } else {
                                    onSendLink(clipboardSuggestion, state.phoneTargetOverride)
                                }
                                onDismissClipboard()
                            },
                            onDismiss = onDismissClipboard
                        )
                    }
                    if (!state.pendingQueueTitle.isNullOrBlank()) {
                        SmartQueueBanner(state.pendingQueueTitle, accents, adaptive, onClearPendingQueue)
                    }
                    StatusBannerCard(state.lastStatusBanner, accents, adaptive)
                    CarQuickUtilitiesCard(
                        clipDraft = clipDraft,
                        accents = accents,
                        adaptive = adaptive,
                        onClipDraftChange = { clipDraft = it },
                        onSendClipboard = {
                            if (clipDraft.isNotBlank()) {
                                onSendClipboardText(clipDraft)
                                clipDraft = ""
                            }
                        },
                        onSendRemote = onSendRemoteCommand
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = adaptive.navBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                ) {
                    SavedPlacesStrip(
                        state = state,
                        accents = accents,
                        adaptive = adaptive,
                        onSendPlace = { onSendLink(it, state.phoneTargetOverride) },
                        onRemovePlace = { BridgeRepository.removeSavedPlace(it) },
                        onAddClick = { showAddPlaceDialog = true }
                    )
                    DestinationComposeCard(
                        state = state,
                        linkDraft = linkDraft,
                        liveParsed = liveParsed,
                        accents = accents,
                        adaptive = adaptive,
                        showActionButtons = true,
                        onLinkDraftChange = { linkDraft = it },
                        onSendLink = onSendLink,
                        onOpenSystemShareSheet = onOpenSystemShareSheet
                    )
                    if (state.history.isNotEmpty()) {
                        RecentHistoryCard(state, accents, adaptive, onPickRecent = { linkDraft = it })
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
                item { SenderHeader(state, activeBox != null, accents, adaptive, onOpenSettings) }
                item { HeroBoxTargetCard(state, accents, adaptive, onPingBox, onOpenSettings) }
                if (!clipboardSuggestion.isNullOrBlank()) {
                    item {
                        ClipboardSuggestionBanner(
                            text = clipboardSuggestion,
                            accents = accents,
                            adaptive = adaptive,
                            onAction = {
                                if (PairingCodeHelper.parsePairingUri(clipboardSuggestion) != null) {
                                    onAcceptClipboardPairing(clipboardSuggestion)
                                } else {
                                    onSendLink(clipboardSuggestion, state.phoneTargetOverride)
                                }
                                onDismissClipboard()
                            },
                            onDismiss = onDismissClipboard
                        )
                    }
                }
                if (!state.pendingQueueTitle.isNullOrBlank()) {
                    item { SmartQueueBanner(state.pendingQueueTitle, accents, adaptive, onClearPendingQueue) }
                }
                if (!state.lastStatusBanner.isNullOrBlank()) {
                    item { StatusBannerCard(state.lastStatusBanner, accents, adaptive) }
                }
                item {
                    SavedPlacesStrip(
                        state = state,
                        accents = accents,
                        adaptive = adaptive,
                        onSendPlace = { onSendLink(it, state.phoneTargetOverride) },
                        onRemovePlace = { BridgeRepository.removeSavedPlace(it) },
                        onAddClick = { showAddPlaceDialog = true }
                    )
                }
                item {
                    DestinationComposeCard(
                        state = state,
                        linkDraft = linkDraft,
                        liveParsed = liveParsed,
                        accents = accents,
                        adaptive = adaptive,
                        showActionButtons = true,
                        onLinkDraftChange = { linkDraft = it },
                        onSendLink = onSendLink,
                        onOpenSystemShareSheet = onOpenSystemShareSheet
                    )
                }
                item {
                    CarQuickUtilitiesCard(
                        clipDraft = clipDraft,
                        accents = accents,
                        adaptive = adaptive,
                        onClipDraftChange = { clipDraft = it },
                        onSendClipboard = {
                            if (clipDraft.isNotBlank()) {
                                onSendClipboardText(clipDraft)
                                clipDraft = ""
                            }
                        },
                        onSendRemote = onSendRemoteCommand
                    )
                }
                if (state.history.isNotEmpty()) {
                    item { RecentHistoryCard(state, accents, adaptive, onPickRecent = { linkDraft = it }) }
                }
            }
        }
    }
}

@Composable
private fun SenderHeader(
    state: BridgeUiState,
    hasActiveBox: Boolean,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "CarBridge",
                fontSize = adaptive.sp(20f),
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Điện thoại · Bản đồ, Media & Điều khiển xe",
                fontSize = adaptive.sp(11.5f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        CockpitPill(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(999.dp),
            color = if (hasActiveBox) accents.emeraldContainer else accents.coralContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(CircleShape)
                        .background(if (hasActiveBox) accents.emerald else accents.coral)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = when {
                        !hasActiveBox -> "CHƯA GHÉP"
                        state.pairedBox?.channel == TransportChannel.BLE -> "BLE SẴN SÀNG"
                        state.bleScanning -> "LAN + BLE"
                        else -> "LAN SẴN SÀNG"
                    },
                    fontSize = adaptive.sp(10.5f),
                    fontWeight = FontWeight.Bold,
                    color = if (hasActiveBox) accents.emerald else accents.coral,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HeroBoxTargetCard(
    state: BridgeUiState,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onPingBox: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val activeBox = state.pairedBox

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(accents.heroObsidianTop, accents.heroObsidian)))
                .padding(adaptive.dp(12f))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(adaptive.dp(34f))
                                .clip(CircleShape)
                                .background(Color(0x2410B981)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DirectionsCar,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(adaptive.dp(18f))
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(
                                text = state.lastPingSummary?.let { "ANDROID BOX · $it" } ?: "ANDROID BOX ĐÍCH",
                                fontSize = adaptive.sp(10f),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = adaptive.sp(0.6f),
                                color = Color(0xFF34D399),
                                maxLines = 1
                            )
                            Text(
                                text = activeBox?.name ?: "Chưa chọn Box",
                                fontSize = adaptive.sp(14.5f),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CockpitPill(
                            onClick = onPingBox,
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x2210B981)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Speed, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Ping", fontSize = adaptive.sp(11f), color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                            }
                        }
                        CockpitPill(
                            onClick = onOpenSettings,
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x18FFFFFF)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Box", fontSize = adaptive.sp(11f), color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (state.discoveredBoxes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.discoveredBoxes.forEach { box ->
                            val selected = activeBox?.host == box.host && activeBox.channel == box.channel
                            CockpitPill(
                                onClick = { BridgeRepository.selectPairedBox(context, box) },
                                shape = RoundedCornerShape(999.dp),
                                color = if (selected) Color(0x2E10B981) else Color(0x14FFFFFF),
                                border = if (selected) BorderStroke(1.dp, Color(0xFF34D399)) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.WifiTethering,
                                        contentDescription = null,
                                        tint = if (selected) Color(0xFF34D399) else Color(0xFF94A3B8),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (box.channel == TransportChannel.BLE) "BLE · ${box.host}" else "LAN · ${box.host}",
                                        fontSize = adaptive.sp(11f),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color(0xFF34D399) else Color(0xFFCBD5E1)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardSuggestionBanner(
    text: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val isPairing = remember(text) { PairingCodeHelper.parsePairingUri(text) != null }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accents.emeraldContainer,
        border = BorderStroke(1.dp, accents.emerald.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPairing) "Phát hiện mã QR ghép Box" else "Link vừa copy trong Clipboard",
                    fontSize = adaptive.sp(11f),
                    fontWeight = FontWeight.Bold,
                    color = accents.emerald
                )
                Text(
                    text = text.lines().firstOrNull().orEmpty(),
                    fontSize = adaptive.sp(11.5f),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            CockpitPill(
                onClick = onAction,
                shape = RoundedCornerShape(999.dp),
                color = Color(0xFF10B981)
            ) {
                Text(
                    text = if (isPairing) "Ghép" else "Gửi",
                    fontSize = adaptive.sp(11f),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Đóng",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun SmartQueueBanner(
    title: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accents.amberContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accents.amber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Smart Queue · Tự gửi khi gặp Box",
                    fontSize = adaptive.sp(10.5f),
                    fontWeight = FontWeight.Bold,
                    color = accents.amber
                )
                Text(
                    text = title,
                    fontSize = adaptive.sp(12f),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Hủy", fontSize = adaptive.sp(11.5f), color = accents.amber, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusBannerCard(
    banner: String?,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics
) {
    if (banner.isNullOrBlank()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accents.cyanContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = banner,
                fontSize = adaptive.sp(12f),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SavedPlacesStrip(
    state: BridgeUiState,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onSendPlace: (String) -> Unit,
    onRemovePlace: (String) -> Unit,
    onAddClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CockpitPill(
            onClick = onAddClick,
            shape = RoundedCornerShape(999.dp),
            color = accents.emeraldContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    text = if (state.savedPlaces.isEmpty()) "Ghim địa điểm quen" else "Ghim",
                    fontSize = adaptive.sp(11f),
                    fontWeight = FontWeight.Bold,
                    color = accents.emerald
                )
            }
        }
        state.savedPlaces.forEach { place ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onSendPlace(place.rawLink) }
                        .padding(start = 9.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PushPin, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = place.label,
                        fontSize = adaptive.sp(11.5f),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Xóa",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onRemovePlace(place.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationComposeCard(
    state: BridgeUiState,
    linkDraft: String,
    liveParsed: ParsedMapLink?,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    showActionButtons: Boolean,
    onLinkDraftChange: (String) -> Unit,
    onSendLink: (String, MapProvider) -> Unit,
    onOpenSystemShareSheet: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "GỬI BẢN ĐỒ / YOUTUBE / NHẠC",
                fontSize = adaptive.sp(10.5f),
                fontWeight = FontWeight.Bold,
                letterSpacing = adaptive.sp(0.7f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = linkDraft,
                onValueChange = onLinkDraftChange,
                placeholder = {
                    Text(
                        text = "VD: https://maps.app.goo.gl/...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                minLines = 1,
                maxLines = 2,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (liveParsed != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(adaptive.dp(28f))
                                .clip(CircleShape)
                                .background(if (liveParsed.kind == LinkKind.MEDIA) accents.emeraldContainer else accents.cyanContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (liveParsed.kind) {
                                    LinkKind.ROUTE -> Icons.Rounded.Route
                                    LinkKind.MEDIA -> Icons.Rounded.MusicNote
                                    else -> Icons.Rounded.LocationOn
                                },
                                contentDescription = null,
                                tint = if (liveParsed.kind == LinkKind.MEDIA) accents.emerald else accents.cyan,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (liveParsed.kind == LinkKind.MEDIA) {
                                    "Media · Tự mở phát trên Box"
                                } else {
                                    "${liveParsed.sourceProvider.displayName} · ${liveParsed.kind.label}"
                                },
                                fontSize = adaptive.sp(10.5f),
                                fontWeight = FontWeight.Bold,
                                color = if (liveParsed.kind == LinkKind.MEDIA) accents.emerald else accents.cyan
                            )
                            Text(
                                text = liveParsed.title,
                                fontSize = adaptive.sp(13f),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (liveParsed?.kind != LinkKind.MEDIA) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mở bằng:",
                        fontSize = adaptive.sp(11f),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    listOf(MapProvider.AUTO, MapProvider.GOOGLE_MAPS, MapProvider.WAZE, MapProvider.VIETMAP).forEach { target ->
                        val selected = state.phoneTargetOverride == target
                        CockpitPill(
                            onClick = { BridgeRepository.updateSettings(phoneTargetOverride = target) },
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) accents.emeraldContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = target.displayName,
                                fontSize = adaptive.sp(11f),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) accents.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (showActionButtons) {
                SenderActionButtons(
                    adaptive = adaptive,
                    enabled = linkDraft.isNotBlank(),
                    onOpenSystemShareSheet = { onOpenSystemShareSheet(linkDraft) },
                    onSend = { onSendLink(linkDraft, state.phoneTargetOverride) }
                )
            }
        }
    }
}

@Composable
private fun CarQuickUtilitiesCard(
    clipDraft: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onClipDraftChange: (String) -> Unit,
    onSendClipboard: () -> Unit,
    onSendRemote: (RemoteCommand) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ĐIỀU KHIỂN NHẠC & CLIPBOARD BOX",
                fontSize = adaptive.sp(10.5f),
                fontWeight = FontWeight.Bold,
                letterSpacing = adaptive.sp(0.7f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RemoteBtn(Icons.Rounded.SkipPrevious, "Trước", accents.cyan, Modifier.weight(1f)) { onSendRemote(RemoteCommand.PREVIOUS) }
                RemoteBtn(Icons.Rounded.PlayArrow, "Phát", accents.emerald, Modifier.weight(1.1f)) { onSendRemote(RemoteCommand.PLAY_PAUSE) }
                RemoteBtn(Icons.Rounded.SkipNext, "Tiếp", accents.cyan, Modifier.weight(1f)) { onSendRemote(RemoteCommand.NEXT) }
                RemoteBtn(Icons.AutoMirrored.Rounded.VolumeDown, "Vol-", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { onSendRemote(RemoteCommand.VOL_DOWN) }
                RemoteBtn(Icons.AutoMirrored.Rounded.VolumeUp, "Vol+", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f)) { onSendRemote(RemoteCommand.VOL_UP) }
                RemoteBtn(Icons.AutoMirrored.Rounded.VolumeOff, "Mute", accents.coral, Modifier.weight(0.9f)) { onSendRemote(RemoteCommand.MUTE) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = clipDraft,
                    onValueChange = onClipDraftChange,
                    placeholder = {
                        Text(
                            text = "Gửi chữ / pass Wi-Fi vào Clipboard Box...",
                            fontSize = adaptive.sp(12f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onSendClipboard,
                    enabled = clipDraft.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accents.cyan,
                        contentColor = Color(0xFF041A24)
                    )
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Gửi Clipboard", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun RemoteBtn(
    icon: ImageVector,
    desc: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    CockpitPill(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(38.dp)
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RecentHistoryCard(
    state: BridgeUiState,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onPickRecent: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
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
                Text(
                    text = "ĐÃ GỬI GẦN ĐÂY (${state.history.size})",
                    fontSize = adaptive.sp(10.5f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = adaptive.sp(0.7f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Xóa",
                    fontSize = adaptive.sp(11f),
                    fontWeight = FontWeight.SemiBold,
                    color = accents.coral,
                    modifier = Modifier.clickable { BridgeRepository.clearHistory() }
                )
            }
            state.history.take(4).forEach { rec ->
                CockpitPill(
                    onClick = { onPickRecent(rec.parsed.rawInput) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (rec.parsed.kind == LinkKind.MEDIA) Icons.Rounded.MusicNote else Icons.Rounded.LocationOn,
                            contentDescription = null,
                            tint = if (rec.parsed.kind == LinkKind.MEDIA) accents.cyan else accents.emerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rec.parsed.title,
                                fontSize = adaptive.sp(12.5f),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${rec.direction} · ${rec.transportUsed}",
                                fontSize = adaptive.sp(10.5f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSavedPlaceDialog(
    initialLink: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val accents = LocalCockpitAccents.current
    var label by remember { mutableStateOf("") }
    var link by remember { mutableStateOf(initialLink) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Ghim địa điểm 1 chạm", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text("VD: Về nhà, Cơ quan, Trường con...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("VD: https://maps.app.goo.gl/... hoặc tọa độ") },
                    minLines = 1,
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(label, link) },
                enabled = label.isNotBlank() && link.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accents.emerald,
                    contentColor = Color(0xFF042116)
                ),
                shape = RoundedCornerShape(999.dp)
            ) {
                Text("Lưu", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
private fun SenderActionButtons(
    adaptive: AdaptiveMetrics,
    enabled: Boolean,
    onOpenSystemShareSheet: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onOpenSystemShareSheet,
            enabled = enabled,
            modifier = Modifier
                .weight(0.95f)
                .height(adaptive.dp(42f)),
            shape = RoundedCornerShape(13.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("Chia sẻ", fontSize = adaptive.sp(12.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }

        Button(
            onClick = onSend,
            enabled = enabled,
            modifier = Modifier
                .weight(1.3f)
                .height(adaptive.dp(42f)),
            shape = RoundedCornerShape(13.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10B981),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("Gửi lên Box", fontSize = adaptive.sp(12.5f), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
