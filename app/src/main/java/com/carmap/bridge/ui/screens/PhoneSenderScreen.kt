package com.carmap.bridge.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.data.BridgeUiState
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.ui.components.CockpitPill
import com.carmap.bridge.ui.components.PhoneQrPairInputDialog
import com.carmap.bridge.ui.components.QrCameraScannerDialog
import com.carmap.bridge.ui.components.cockpitTextFieldColors
import com.carmap.bridge.ui.theme.AdaptiveMetrics
import com.carmap.bridge.ui.theme.CockpitAccents
import com.carmap.bridge.ui.theme.LocalAdaptiveMetrics
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import com.carmap.bridge.util.PairingCodeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhoneSenderScreen(
    state: BridgeUiState,
    clipboardSuggestion: String?,
    onDismissClipboard: () -> Unit,
    onAcceptClipboardPairing: (String) -> Unit,
    onSendLink: (String, MapProvider) -> Unit,
    onStartVoiceSearch: () -> Unit,
    onSendClipboardText: (String) -> Unit,
    onPullClipboardFromBox: () -> Unit,
    onPickFileToSend: () -> Unit,
    onSaveParkingPin: () -> Unit,
    onFindParkedCar: () -> Unit,
    onSendBoxCommand: (cmd: String, label: String) -> Unit,
    onPingBox: () -> Unit,
    onClearPendingQueue: () -> Unit,
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
            val web = if (media == null) MediaLinkHelper.extractGenericWebLink(linkDraft) else null
            when {
                media != null -> MediaLinkHelper.toParsedRecord(linkDraft, media)
                web != null -> MediaLinkHelper.toWebParsedRecord(linkDraft, web)
                else -> MapLinkResolver.parseImmediate(linkDraft)
            }
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
            Column(
                modifier = Modifier
                    .widthIn(max = 1140.dp)
                    .fillMaxSize()
                    .padding(horizontal = adaptive.screenPadding)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
            ) {
                SenderHeader(state, activeBox != null, accents, adaptive, onOpenSettings)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.95f)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = adaptive.navBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(adaptive.sectionGap)
                    ) {
                        HeroBoxTargetCard(state, accents, adaptive, onPingBox)
                        if (!clipboardSuggestion.isNullOrBlank()) {
                            ClipboardSuggestionBanner(
                                text = clipboardSuggestion,
                                accents = accents,
                                adaptive = adaptive,
                                onAction = {
                                    if (PairingCodeHelper.parsePairingUri(clipboardSuggestion) != null) {
                                        onAcceptClipboardPairing(clipboardSuggestion)
                                    } else {
                                        onSendLink(clipboardSuggestion, MapProvider.AUTO)
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
                        ClipboardBridgeCard(
                            state = state,
                            clipDraft = clipDraft,
                            accents = accents,
                            adaptive = adaptive,
                            onClipDraftChange = { clipDraft = it },
                            onSendClipboard = { text ->
                                onSendClipboardText(text)
                                if (text == clipDraft) clipDraft = ""
                            },
                            onPullClipboard = onPullClipboardFromBox
                        )
                        CarToolsCard(
                            state = state,
                            accents = accents,
                            adaptive = adaptive,
                            onPickFileToSend = onPickFileToSend,
                            onSaveParkingPin = onSaveParkingPin,
                            onFindParkedCar = onFindParkedCar,
                            onSendBoxCommand = onSendBoxCommand
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
                            onSendPlace = { onSendLink(it, MapProvider.AUTO) },
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
                            onStartVoice = onStartVoiceSearch,
                            onSendLink = onSendLink
                        )
                        if (state.history.isNotEmpty()) {
                            RecentHistoryCard(state, accents, adaptive, onPickRecent = { linkDraft = it })
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
                item { SenderHeader(state, activeBox != null, accents, adaptive, onOpenSettings) }
                item { HeroBoxTargetCard(state, accents, adaptive, onPingBox) }
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
                                    onSendLink(clipboardSuggestion, MapProvider.AUTO)
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
                        onSendPlace = { onSendLink(it, MapProvider.AUTO) },
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
                        onStartVoice = onStartVoiceSearch,
                        onSendLink = onSendLink
                    )
                }
                item {
                    ClipboardBridgeCard(
                        state = state,
                        clipDraft = clipDraft,
                        accents = accents,
                        adaptive = adaptive,
                        onClipDraftChange = { clipDraft = it },
                        onSendClipboard = { text ->
                            onSendClipboardText(text)
                            if (text == clipDraft) clipDraft = ""
                        },
                        onPullClipboard = onPullClipboardFromBox
                    )
                }
                item {
                    CarToolsCard(
                        state = state,
                        accents = accents,
                        adaptive = adaptive,
                        onPickFileToSend = onPickFileToSend,
                        onSaveParkingPin = onSaveParkingPin,
                        onFindParkedCar = onFindParkedCar,
                        onSendBoxCommand = onSendBoxCommand
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
                text = "Điện thoại · Dẫn đường, Web, APK & Clipboard",
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
    onPingBox: () -> Unit
) {
    val context = LocalContext.current
    val activeBox = state.pairedBox
    var showCameraScanner by remember { mutableStateOf(false) }
    var showManualQr by remember { mutableStateOf(false) }
    val heroBrush = remember(accents) {
        Brush.verticalGradient(listOf(accents.heroObsidianTop, accents.heroObsidian))
    }

    if (showCameraScanner) {
        QrCameraScannerDialog(
            onScanned = { parsed ->
                if (parsed.pin.isNotBlank()) BridgeRepository.updateSettings(pin = parsed.pin)
                BridgeRepository.upsertDiscoveredBox(parsed.box)
                BridgeRepository.selectPairedBox(context, parsed.box)
            },
            onFallbackManual = { showManualQr = true },
            onDismiss = { showCameraScanner = false }
        )
    }
    if (showManualQr) {
        PhoneQrPairInputDialog(
            onParsed = { parsed ->
                if (parsed.pin.isNotBlank()) BridgeRepository.updateSettings(pin = parsed.pin)
                BridgeRepository.upsertDiscoveredBox(parsed.box)
                BridgeRepository.selectPairedBox(context, parsed.box)
            },
            onDismiss = { showManualQr = false }
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x18FFFFFF))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(heroBrush)
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
                            val proximityTag = if (state.proximityOnly2m) " · GẦN ≤2M" else ""
                            val distTag = activeBox?.estimatedDistanceMeters?.let { " · ~${it}m" }.orEmpty()
                            Text(
                                text = (state.lastPingSummary?.let { "ANDROID BOX · $it" } ?: "ANDROID BOX ĐÍCH") + distTag + proximityTag,
                                fontSize = adaptive.sp(10f),
                                fontWeight = FontWeight.Bold,
                                letterSpacing = adaptive.sp(0.6f),
                                color = Color(0xFF34D399),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                            onClick = { showCameraScanner = true },
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x2238BDF8)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Quét QR", tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Quét QR", fontSize = adaptive.sp(11f), color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                            }
                        }
                        CockpitPill(
                            onClick = onPingBox,
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x2210B981)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Speed, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ping", fontSize = adaptive.sp(11f), color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
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
                                    val distSuffix = box.estimatedDistanceMeters?.let { " · ~${it}m" }.orEmpty()
                                    Text(
                                        text = if (box.channel == TransportChannel.BLE) "BLE$distSuffix · ${box.host}" else "LAN$distSuffix · ${box.host}",
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
    onStartVoice: () -> Unit,
    onSendLink: (String, MapProvider) -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ĐIỂM ĐẾN & LIÊN KẾT",
                    fontSize = adaptive.sp(10.5f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = adaptive.sp(0.8f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CockpitPill(
                    onClick = onStartVoice,
                    shape = RoundedCornerShape(999.dp),
                    color = accents.emeraldContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Giọng nói", tint = accents.emerald, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Nói điểm đến", fontSize = adaptive.sp(10.5f), fontWeight = FontWeight.Bold, color = accents.emerald)
                    }
                }
            }

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
                colors = cockpitTextFieldColors(),
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
                                    LinkKind.WEB -> Icons.Rounded.Language
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
                                text = when (liveParsed.kind) {
                                    LinkKind.MEDIA -> "Media · Tự mở phát trên Box"
                                    LinkKind.WEB -> "Browser Cast · Mở trang web trên Box"
                                    else -> "${liveParsed.sourceProvider.displayName} · ${liveParsed.kind.label}"
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

            if (showActionButtons) {
                SenderActionButtons(
                    adaptive = adaptive,
                    sendEnabled = linkDraft.isNotBlank(),
                    onPaste = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                        if (clip.isNotBlank()) {
                            onLinkDraftChange(clip)
                        } else {
                            Toast.makeText(context, "Clipboard đang trống", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSend = { onSendLink(linkDraft, MapProvider.AUTO) }
                )
            }
        }
    }
}

@Composable
private fun ClipboardBridgeCard(
    state: BridgeUiState,
    clipDraft: String,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onClipDraftChange: (String) -> Unit,
    onSendClipboard: (String) -> Unit,
    onPullClipboard: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CLIPBOARD 2 CHIỀU",
                    fontSize = adaptive.sp(10.5f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = adaptive.sp(0.8f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CockpitPill(
                        onClick = onPullClipboard,
                        shape = RoundedCornerShape(999.dp),
                        color = accents.cyanContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Lấy từ Box", fontSize = adaptive.sp(10.5f), fontWeight = FontWeight.Bold, color = accents.cyan)
                        }
                    }
                    if (state.clipboardHistory.isNotEmpty()) {
                        Text(
                            text = "Xóa",
                            fontSize = adaptive.sp(11f),
                            fontWeight = FontWeight.SemiBold,
                            color = accents.coral,
                            modifier = Modifier.clickable { BridgeRepository.clearClipboardHistory() }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = clipDraft,
                onValueChange = onClipDraftChange,
                placeholder = {
                    Text(
                        text = "Nhập văn bản, tài khoản, mật khẩu Wi-Fi...",
                        fontSize = adaptive.sp(12f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                colors = cockpitTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                        if (clip.isNotBlank()) {
                            onSendClipboard(clip)
                        } else {
                            Toast.makeText(context, "Clipboard điện thoại đang trống", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(adaptive.dp(44f)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Dán & Gửi", fontSize = adaptive.sp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = { onSendClipboard(clipDraft) },
                    enabled = clipDraft.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(adaptive.dp(44f)),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accents.cyan,
                        contentColor = if (accents.isDark) Color(0xFF041A24) else Color.White
                    )
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Gửi vào Box", fontSize = adaptive.sp(12f), fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            if (state.clipboardHistory.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.clipboardHistory.take(5).forEach { snippet ->
                        CockpitPill(
                            onClick = { onSendClipboard(snippet) },
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = snippet.lines().firstOrNull().orEmpty().take(24),
                                fontSize = adaptive.sp(11f),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarToolsCard(
    state: BridgeUiState,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onPickFileToSend: () -> Unit,
    onSaveParkingPin: () -> Unit,
    onFindParkedCar: () -> Unit,
    onSendBoxCommand: (cmd: String, label: String) -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var confirmReboot by remember { mutableStateOf(false) }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text("Khởi động lại Android Box?") },
            text = { Text("Box sẽ khởi động lại ngay (yêu cầu đã bật Chế độ Root nâng cao trên Box).") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReboot = false
                        onSendBoxCommand("REBOOT", "Khởi động lại Box")
                    }
                ) {
                    Text("Khởi động lại", color = accents.coral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReboot = false }) { Text("Hủy") }
            }
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "TIỆN ÍCH XE",
                fontSize = adaptive.sp(10.5f),
                fontWeight = FontWeight.Bold,
                letterSpacing = adaptive.sp(0.8f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CockpitPill(
                    onClick = onPickFileToSend,
                    shape = RoundedCornerShape(14.dp),
                    color = accents.cyanContainer,
                    modifier = Modifier
                        .weight(1f)
                        .height(adaptive.dp(44f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "Gửi APK / Ảnh",
                            fontSize = adaptive.sp(12f),
                            fontWeight = FontWeight.Bold,
                            color = accents.cyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                CockpitPill(
                    onClick = if (state.parkingPin != null) onFindParkedCar else onSaveParkingPin,
                    shape = RoundedCornerShape(14.dp),
                    color = accents.emeraldContainer,
                    modifier = Modifier
                        .weight(1f)
                        .height(adaptive.dp(44f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.LocalParking, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (state.parkingPin != null) "Tìm xe (${timeFmt.format(Date(state.parkingPin.savedAtMs))})" else "Ghim chỗ đỗ",
                            fontSize = adaptive.sp(12f),
                            fontWeight = FontWeight.Bold,
                            color = accents.emerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (state.parkingPin != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${state.parkingPin.label} · ${String.format(Locale.US, "%.4f, %.4f", state.parkingPin.lat, state.parkingPin.lng)}",
                        fontSize = adaptive.sp(11f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Cập nhật vị trí",
                        fontSize = adaptive.sp(11f),
                        fontWeight = FontWeight.Bold,
                        color = accents.cyan,
                        modifier = Modifier.clickable(onClick = onSaveParkingPin)
                    )
                }
            }

            if (state.fileTransferProgress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.fileTransferLabel ?: "Đang truyền tệp sang Box...",
                        fontSize = adaptive.sp(11.5f),
                        fontWeight = FontWeight.SemiBold,
                        color = accents.cyan
                    )
                    LinearProgressIndicator(
                        progress = { state.fileTransferProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = accents.emerald
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "ĐIỀU KHIỂN BOX TỪ XA",
                fontSize = adaptive.sp(10f),
                fontWeight = FontWeight.Bold,
                letterSpacing = adaptive.sp(0.8f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    Triple("KEY_HOME", "Home" to Icons.Rounded.Home, accents.cyanContainer to accents.cyan),
                    Triple("FORCE_STOP_MAPS", "Dọn Maps" to Icons.Rounded.CleaningServices, accents.amberContainer to accents.amber),
                    Triple("REBOOT", "Reboot Box" to Icons.Rounded.RestartAlt, accents.coralContainer to accents.coral)
                ).forEach { (cmd, labelIcon, colors) ->
                    val (label, icon) = labelIcon
                    CockpitPill(
                        onClick = {
                            if (cmd == "REBOOT") confirmReboot = true
                            else onSendBoxCommand(cmd, label)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = colors.first,
                        modifier = Modifier
                            .weight(1f)
                            .height(adaptive.dp(40f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = colors.second, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = label,
                                fontSize = adaptive.sp(11f),
                                fontWeight = FontWeight.Bold,
                                color = colors.second,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentHistoryCard(
    state: BridgeUiState,
    accents: CockpitAccents,
    adaptive: AdaptiveMetrics,
    onPickRecent: (String) -> Unit
) {
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var expanded by remember { mutableStateOf(false) }
    val visibleItems = remember(state.history, expanded) {
        if (expanded) state.history else state.history.take(4)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(adaptive.cardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    letterSpacing = adaptive.sp(0.8f),
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
            visibleItems.forEach { rec ->
                val isInfoKind = rec.parsed.kind == LinkKind.MEDIA || rec.parsed.kind == LinkKind.WEB
                CockpitPill(
                    onClick = { onPickRecent(rec.parsed.rawInput) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isInfoKind) accents.cyanContainer else accents.emeraldContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (rec.parsed.kind) {
                                    LinkKind.MEDIA -> Icons.Rounded.MusicNote
                                    LinkKind.WEB -> Icons.Rounded.Language
                                    else -> Icons.Rounded.LocationOn
                                },
                                contentDescription = null,
                                tint = if (isInfoKind) accents.cyan else accents.emerald,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
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
                                text = "${timeFmt.format(Date(rec.timestampMs))} · ${rec.direction} · ${rec.transportUsed}",
                                fontSize = adaptive.sp(10.5f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (rec.parsed.kind != LinkKind.MEDIA &&
                            rec.parsed.kind != LinkKind.WEB &&
                            rec.parsed.kind != LinkKind.CLIPBOARD &&
                            rec.parsed.kind != LinkKind.FILE
                        ) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val label = rec.parsed.title.take(24)
                                        BridgeRepository.addSavedPlace(label, rec.parsed.rawInput)
                                        Toast.makeText(context, "Đã ghim: $label", Toast.LENGTH_SHORT).show()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = "Ghim địa điểm",
                                    tint = accents.cyan,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (state.history.size > 4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (expanded) "Thu gọn" else "Xem tất cả (${state.history.size})",
                        fontSize = adaptive.sp(11.5f),
                        fontWeight = FontWeight.Bold,
                        color = accents.cyan
                    )
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
                    shape = RoundedCornerShape(14.dp),
                    colors = cockpitTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("VD: https://maps.app.goo.gl/... hoặc tọa độ") },
                    minLines = 1,
                    maxLines = 2,
                    shape = RoundedCornerShape(14.dp),
                    colors = cockpitTextFieldColors(),
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
                    contentColor = if (accents.isDark) Color(0xFF042116) else Color.White
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
    sendEnabled: Boolean,
    onPaste: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onPaste,
            modifier = Modifier
                .weight(0.85f)
                .height(adaptive.dp(44f)),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("Dán link", fontSize = adaptive.sp(12f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Button(
            onClick = onSend,
            enabled = sendEnabled,
            modifier = Modifier
                .weight(1.35f)
                .height(adaptive.dp(44f)),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10B981),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("Gửi lên Box", fontSize = adaptive.sp(12.5f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
