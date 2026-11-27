package com.carmap.bridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.network.BoxNetworkConstants
import com.carmap.bridge.network.CarBridgeConnectionManager
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.ui.theme.CarMapTheme
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import com.carmap.bridge.util.CarBridgeNotifier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class ShareReceiverActivity : ComponentActivity() {

    private val currentIntentState = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeRepository.init(this)
        currentIntentState.value = intent

        setContent {
            val activeIntent = currentIntentState.value ?: intent
            val sharedText = activeIntent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val subject = activeIntent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val fullInput = listOf(subject, sharedText).filter { it.isNotBlank() }.joinToString("\n").trim()
            val targetHostOverride = activeIntent?.getStringExtra("target_box_host")
            val shortcutId = activeIntent?.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)

            val state by BridgeRepository.state.collectAsState()
            var parsed by remember(fullInput) { mutableStateOf<ParsedMapLink?>(null) }
            var sending by remember(fullInput) { mutableStateOf(true) }
            var errorMessage by remember(fullInput) { mutableStateOf<String?>(null) }
            var retryNonce by remember { mutableIntStateOf(0) }

            DisposableEffect(Unit) {
                CarBridgeConnectionManager.retainDiscovery(this@ShareReceiverActivity)
                onDispose {
                    CarBridgeConnectionManager.releaseDiscovery()
                }
            }

            LaunchedEffect(fullInput, retryNonce) {
                if (fullInput.isBlank()) {
                    Toast.makeText(this@ShareReceiverActivity, "Không có liên kết để gửi", Toast.LENGTH_SHORT).show()
                    finish()
                    return@LaunchedEffect
                }

                sending = true
                errorMessage = null

                val referrerTarget = detectSourceMapFromReferrer()
                val (resolved, targetBox) = coroutineScope {
                    val resolvedDef = async { resolveInput(fullInput) }
                    val boxDef = async { findTargetBoxFast(shortcutId, targetHostOverride) }
                    resolvedDef.await() to boxDef.await()
                }
                parsed = resolved

                val autoTarget = when {
                    referrerTarget != MapProvider.AUTO -> referrerTarget
                    resolved.kind == LinkKind.MEDIA || resolved.kind == LinkKind.WEB -> MapProvider.AUTO
                    resolved.sourceProvider != MapProvider.UNKNOWN -> resolved.sourceProvider
                    else -> MapProvider.AUTO
                }

                val res = CarBridgeConnectionManager.sendOrQueue(
                    context = this@ShareReceiverActivity,
                    primaryBox = targetBox,
                    rawText = fullInput,
                    preResolved = resolved,
                    preferredTarget = autoTarget,
                    queueOnFailure = false
                )

                res.onSuccess { msg ->
                    Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { err ->
                    val reason = err.message ?: "Điện thoại chưa kết nối được với Android Box."
                    sending = false
                    errorMessage = reason
                    CarBridgeNotifier.notifyBoxNotConnected(
                        context = this@ShareReceiverActivity,
                        destinationTitle = resolved.title,
                        reason = reason
                    )
                }
            }

            CarMapTheme(themeMode = state.themeMode, applySystemBars = false) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (sending) {
                        SendingStatusOverlayCard(destinationTitle = parsed?.title)
                    } else if (errorMessage != null) {
                        BoxNotConnectedPopupDialog(
                            destinationTitle = parsed?.title ?: fullInput.lines().firstOrNull().orEmpty(),
                            reason = errorMessage!!,
                            onRetry = {
                                errorMessage = null
                                sending = true
                                retryNonce += 1
                            },
                            onQueueForCar = {
                                val resolvedNow = parsed ?: MapLinkResolver.parseImmediate(fullInput)
                                val referrerTarget = detectSourceMapFromReferrer()
                                val autoTarget = when {
                                    referrerTarget != MapProvider.AUTO -> referrerTarget
                                    resolvedNow.sourceProvider != MapProvider.UNKNOWN -> resolvedNow.sourceProvider
                                    else -> MapProvider.AUTO
                                }
                                CarBridgeConnectionManager.enqueuePendingManual(
                                    context = this@ShareReceiverActivity,
                                    rawText = fullInput,
                                    parsed = resolvedNow,
                                    preferredTarget = autoTarget
                                )
                                Toast.makeText(
                                    this@ShareReceiverActivity,
                                    "Đã xếp hàng Smart Queue — sẽ tự gửi khi kết nối Box",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            },
                            onOpenApp = {
                                startActivity(
                                    Intent(this@ShareReceiverActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                )
                                finish()
                            },
                            onDismiss = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun detectSourceMapFromReferrer(): MapProvider {
        val pkg = runCatching { referrer?.host?.lowercase() }.getOrNull() ?: return MapProvider.AUTO
        return when (pkg) {
            "com.google.android.apps.maps" -> MapProvider.GOOGLE_MAPS
            "com.waze" -> MapProvider.WAZE
            "vn.vietmap.live", "vn.vietmap.s2" -> MapProvider.VIETMAP
            else -> MapProvider.AUTO
        }
    }

    private suspend fun resolveInput(rawText: String): ParsedMapLink {
        val media = MediaLinkHelper.extractMediaLink(rawText)
        if (media != null) return MediaLinkHelper.toParsedRecord(rawText, media)
        val web = MediaLinkHelper.extractGenericWebLink(rawText)
        if (web != null) return MediaLinkHelper.toWebParsedRecord(rawText, web)

        val parsed = MapLinkResolver.resolveAndParse(rawText)
        if (parsed.sourceProvider == MapProvider.UNKNOWN &&
            parsed.primaryLat == null &&
            parsed.resolvedUrl.startsWith("http", ignoreCase = true)
        ) {
            val expandedMedia = MediaLinkHelper.extractMediaLink(parsed.resolvedUrl)
            if (expandedMedia != null) return MediaLinkHelper.toParsedRecord(rawText, expandedMedia)
            val expandedWeb = MediaLinkHelper.extractGenericWebLink(parsed.resolvedUrl, allowShortRedirect = true)
            if (expandedWeb != null) return MediaLinkHelper.toWebParsedRecord(rawText, expandedWeb)
        }
        return parsed
    }

    private suspend fun findTargetBoxFast(
        shortcutId: String?,
        targetHostOverride: String?
    ): DiscoveredBox? {
        CarBridgeConnectionManager.probeNow()

        val stateNow = BridgeRepository.state.value
        val shortcutMatch = BridgeRepository.findBoxByShortcutId(shortcutId)?.takeIf { sc ->
            stateNow.discoveredBoxes.any { it.id == sc.id && !it.isLocalDemo }
        }
        val liveBox = shortcutMatch
            ?: targetHostOverride?.let {
                DiscoveredBox("direct-$it", "Android Box ($it)", it, BoxNetworkConstants.TCP_PORT)
            }
            ?: stateNow.discoveredBoxes.firstOrNull { !it.isLocalDemo }
        if (liveBox != null) return liveBox

        // Verify saved LAN/BLE endpoints first (<100ms TCP PING or BLE upgrade) before trusting stale IP
        val fast = CarBridgeConnectionManager.fastReconnectSavedEndpoints(this)
        if (fast != null) return fast

        // Give active UDP/NSD/BLE scan up to 1.2s to spot a Box
        repeat(6) {
            delay(200L)
            val st = BridgeRepository.state.value
            val spotted = st.discoveredBoxes.firstOrNull { !it.isLocalDemo }
            if (spotted != null) return spotted
        }
        return BridgeRepository.state.value.pairedBox
    }
}

@Composable
private fun SendingStatusOverlayCard(destinationTitle: String?) {
    val accents = LocalCockpitAccents.current
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = accents.emerald
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Đang bắn thẳng sang Box...",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (!destinationTitle.isNullOrBlank()) {
                    Text(
                        text = destinationTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxNotConnectedPopupDialog(
    destinationTitle: String,
    reason: String,
    onRetry: () -> Unit,
    onQueueForCar: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val accents = LocalCockpitAccents.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = accents.coral,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Chưa kết nối được với Android Box",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (destinationTitle.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = destinationTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Text(
                text = reason,
                color = accents.amber,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Hãy bật CarBridge trên Android Box và kiểm tra kết nối Wi-Fi / Hotspot / Bluetooth.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onQueueForCar,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tự gửi sau", maxLines = 1)
                }
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Thử lại", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onOpenApp) {
                    Text("Mở CarBridge")
                }
                TextButton(onClick = onDismiss) {
                    Text("Đóng")
                }
            }
        }
    }
}
