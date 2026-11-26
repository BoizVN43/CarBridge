package com.carmap.bridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DirectionsCar
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.network.BoxNetworkConstants
import com.carmap.bridge.network.CarBridgeConnectionManager
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.ui.components.CockpitPill
import com.carmap.bridge.ui.theme.CarMapTheme
import com.carmap.bridge.ui.theme.LocalCockpitAccents
import kotlinx.coroutines.launch

class ShareReceiverActivity : ComponentActivity() {

    private val currentIntentState = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIntentState.value = intent

        setContent {
            val activeIntent = currentIntentState.value ?: intent
            val sharedText = activeIntent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val subject = activeIntent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val fullInput = listOf(subject, sharedText).filter { it.isNotBlank() }.joinToString("\n")
            val targetHostOverride = activeIntent?.getStringExtra("target_box_host")
            val shortcutId = activeIntent?.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)

            val state by BridgeRepository.state.collectAsState()
            val scope = rememberCoroutineScope()
            var parsed by remember(fullInput) { mutableStateOf<ParsedMapLink?>(null) }
            var selectedTarget by remember { mutableStateOf(state.phoneTargetOverride) }
            var sending by remember { mutableStateOf(false) }
            var searchingBox by remember { mutableStateOf(true) }
            var statusMessage by remember(fullInput) { mutableStateOf<String?>(null) }

            val activeBox = state.pairedBox
                ?: BridgeRepository.findBoxByShortcutId(shortcutId)
                ?: targetHostOverride?.let {
                    DiscoveredBox("direct-$it", "Android Box ($it)", it, BoxNetworkConstants.TCP_PORT)
                }
                ?: state.discoveredBoxes.firstOrNull()

            DisposableEffect(Unit) {
                CarBridgeConnectionManager.retainDiscovery(this@ShareReceiverActivity)
                onDispose {
                    CarBridgeConnectionManager.releaseDiscovery()
                }
            }

            LaunchedEffect(fullInput) {
                CarBridgeConnectionManager.probeNow()
                val resolved = MapLinkResolver.resolveAndParse(fullInput)
                parsed = resolved

                launch {
                    kotlinx.coroutines.delay(2500L)
                    searchingBox = false
                }

                val targetBoxNow = BridgeRepository.state.value.pairedBox
                    ?: BridgeRepository.findBoxByShortcutId(shortcutId)
                    ?: activeBox

                // Direct Share 1 chạm: tự động gửi thẳng nếu mở từ shortcut của Box
                if (!shortcutId.isNullOrBlank() && targetBoxNow != null && fullInput.isNotBlank()) {
                    sending = true
                    val res = CarBridgeConnectionManager.sendOrQueue(
                        context = this@ShareReceiverActivity,
                        primaryBox = targetBoxNow,
                        rawText = fullInput,
                        preResolved = resolved,
                        preferredTarget = selectedTarget
                    )
                    sending = false
                    res.onSuccess { msg ->
                        Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_SHORT).show()
                        finish()
                    }.onFailure { err ->
                        statusMessage = err.message ?: "Lỗi gửi nhanh"
                    }
                }
            }

            CarMapTheme(themeMode = state.themeMode, applySystemBars = false) {
                ShareSheetDialog(
                    rawInput = fullInput,
                    parsed = parsed,
                    activeBox = activeBox,
                    searchingBox = searchingBox,
                    selectedTarget = selectedTarget,
                    sending = sending,
                    statusMessage = statusMessage,
                    onSelectTarget = { selectedTarget = it },
                    onSend = {
                        sending = true
                        statusMessage = null
                        scope.launch {
                            val result = CarBridgeConnectionManager.sendOrQueue(
                                context = this@ShareReceiverActivity,
                                primaryBox = activeBox,
                                rawText = fullInput,
                                preResolved = parsed,
                                preferredTarget = selectedTarget
                            )
                            sending = false
                            result.onSuccess { msg ->
                                Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_SHORT).show()
                                finish()
                            }.onFailure { err ->
                                statusMessage = err.message ?: "Lỗi gửi"
                            }
                        }
                    },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareSheetDialog(
    rawInput: String,
    parsed: ParsedMapLink?,
    activeBox: DiscoveredBox?,
    searchingBox: Boolean,
    selectedTarget: MapProvider,
    sending: Boolean,
    statusMessage: String?,
    onSelectTarget: (MapProvider) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val accents = LocalCockpitAccents.current
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = accents.emerald)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Gửi bản đồ lên Android Box",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (parsed == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Đang giải nén & phân tích link bản đồ...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${parsed.sourceProvider.displayName} · ${parsed.kind.label}",
                            color = accents.cyan,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(parsed.title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        if (parsed.primaryLat != null && parsed.primaryLng != null) {
                            Text(
                                "Tọa độ: ${String.format("%.6f, %.6f", parsed.primaryLat, parsed.primaryLng)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Text("Mở trên Box bằng:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(MapProvider.AUTO, MapProvider.GOOGLE_MAPS, MapProvider.WAZE, MapProvider.VIETMAP).forEach { target ->
                    val selected = selectedTarget == target
                    CockpitPill(
                        onClick = { onSelectTarget(target) },
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) accents.emeraldContainer else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = target.displayName,
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) accents.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Text(
                when {
                    activeBox != null -> "Box đích: ${activeBox.name} (${activeBox.host})"
                    searchingBox -> "Đang tìm Android Box qua LAN / BLE..."
                    else -> "Chưa tìm thấy Android Box. Hãy bật dịch vụ trên Box!"
                },
                color = when {
                    activeBox != null -> accents.emerald
                    searchingBox -> accents.cyan
                    else -> accents.coral
                },
                style = MaterialTheme.typography.bodySmall
            )

            if (!statusMessage.isNullOrBlank()) {
                Text(statusMessage, color = accents.amber, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Hủy")
                }
                Button(
                    onClick = onSend,
                    enabled = activeBox != null && !sending && rawInput.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.4f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (sending) "Đang gửi..." else "Gửi ngay", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
}
