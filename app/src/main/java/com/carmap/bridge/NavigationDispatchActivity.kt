package com.carmap.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.launcher.LaunchSpec
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.ui.theme.CarMapTheme
import com.carmap.bridge.ui.theme.LocalAdaptiveMetrics
import com.carmap.bridge.ui.theme.LocalCockpitAccents

class NavigationDispatchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderIntent(intent)
    }

    private fun renderIntent(intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val kindLabel = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val sourceName = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG).orEmpty()
        val installed = intent.getBooleanExtra(EXTRA_INSTALLED, false)
        val intentUri = intent.getStringExtra(EXTRA_INTENT_URI).orEmpty()
        val resolvedUrl = intent.getStringExtra(EXTRA_RESOLVED_URL).orEmpty()
        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val destination = intent.getStringExtra(EXTRA_DESTINATION)
        val waypoints = intent.getStringArrayListExtra(EXTRA_WAYPOINTS).orEmpty()
        val coords = intent.getStringExtra(EXTRA_COORDS)
        val senderName = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        val remoteIp = intent.getStringExtra(EXTRA_REMOTE_IP).orEmpty()

        setContent {
            val state by BridgeRepository.state.collectAsState()
            CarMapTheme(themeMode = state.themeMode) {
                CarBoxNavigationHud(
                    title = title,
                    kindLabel = kindLabel,
                    sourceName = sourceName,
                    targetName = targetName,
                    targetPkg = targetPkg,
                    isInstalled = installed,
                    intentUri = intentUri,
                    resolvedUrl = resolvedUrl,
                    origin = origin,
                    destination = destination,
                    waypoints = waypoints,
                    coords = coords,
                    senderName = senderName,
                    remoteIp = remoteIp,
                    onLaunchNative = {
                        val ok = runCatching {
                            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(intentUri)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (installed && targetPkg.isNotBlank()) setPackage(targetPkg)
                            }
                            startActivity(viewIntent)
                            true
                        }.getOrDefault(false)
                        if (!ok && resolvedUrl.startsWith("http")) {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl)))
                            }
                        }
                    },
                    onOpenBrowserFallback = {
                        val fallbackUrl = if (resolvedUrl.startsWith("http")) {
                            resolvedUrl
                        } else if (!coords.isNullOrBlank()) {
                            "https://www.openstreetmap.org/?mlat=${coords.substringBefore(",").trim()}&mlon=${coords.substringAfter(",").trim()}#map=16/${coords.replace(", ", "/")}"
                        } else {
                            intentUri
                        }
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_KIND = "extra_kind"
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_TARGET = "extra_target"
        private const val EXTRA_TARGET_PKG = "extra_target_pkg"
        private const val EXTRA_INSTALLED = "extra_installed"
        private const val EXTRA_INTENT_URI = "extra_intent_uri"
        private const val EXTRA_RESOLVED_URL = "extra_resolved_url"
        private const val EXTRA_ORIGIN = "extra_origin"
        private const val EXTRA_DESTINATION = "extra_destination"
        private const val EXTRA_WAYPOINTS = "extra_waypoints"
        private const val EXTRA_COORDS = "extra_coords"
        private const val EXTRA_SENDER = "extra_sender"
        private const val EXTRA_REMOTE_IP = "extra_remote_ip"

        fun launch(
            context: Context,
            parsed: ParsedMapLink,
            spec: LaunchSpec,
            senderName: String,
            remoteIp: String
        ) {
            val coords = if (parsed.primaryLat != null && parsed.primaryLng != null) {
                String.format("%.6f, %.6f", parsed.primaryLat, parsed.primaryLng)
            } else {
                null
            }
            val intent = Intent(context, NavigationDispatchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_TITLE, parsed.title)
                putExtra(EXTRA_KIND, parsed.kind.label)
                putExtra(EXTRA_SOURCE, parsed.sourceProvider.displayName)
                putExtra(EXTRA_TARGET, spec.effectiveProvider.displayName)
                putExtra(EXTRA_TARGET_PKG, spec.targetPackage ?: "")
                putExtra(EXTRA_INSTALLED, spec.isPackageInstalled)
                putExtra(EXTRA_INTENT_URI, spec.intentUri)
                putExtra(EXTRA_RESOLVED_URL, parsed.resolvedUrl)
                putExtra(EXTRA_ORIGIN, parsed.origin?.label)
                putExtra(EXTRA_DESTINATION, parsed.destination?.label)
                putStringArrayListExtra(EXTRA_WAYPOINTS, ArrayList(parsed.waypoints.map { it.label }))
                putExtra(EXTRA_COORDS, coords)
                putExtra(EXTRA_SENDER, senderName)
                putExtra(EXTRA_REMOTE_IP, remoteIp)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun CarBoxNavigationHud(
    title: String,
    kindLabel: String,
    sourceName: String,
    targetName: String,
    targetPkg: String,
    isInstalled: Boolean,
    intentUri: String,
    resolvedUrl: String,
    origin: String?,
    destination: String?,
    waypoints: List<String>,
    coords: String?,
    senderName: String,
    remoteIp: String,
    onLaunchNative: () -> Unit,
    onOpenBrowserFallback: () -> Unit,
    onClose: () -> Unit
) {
    val accents = LocalCockpitAccents.current
    val adaptive = LocalAdaptiveMetrics.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (adaptive.isTwoColumn) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1120.dp)
                        .fillMaxSize()
                        .padding(adaptive.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HudHeaderRow(senderName, remoteIp, accents, onClose)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HudRouteSummaryCard(title, kindLabel, sourceName, targetName, coords, origin, destination, waypoints, accents)
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.95f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HudIntentInfoCard(isInstalled, targetName, accents)
                            HudActionButtons(targetName, onLaunchNative, onOpenBrowserFallback, onClose)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(adaptive.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HudHeaderRow(senderName, remoteIp, accents, onClose)
                    HudRouteSummaryCard(title, kindLabel, sourceName, targetName, coords, origin, destination, waypoints, accents)
                    HudIntentInfoCard(isInstalled, targetName, accents)
                    HudActionButtons(targetName, onLaunchNative, onOpenBrowserFallback, onClose)
                }
            }
        }
    }
}

@Composable
private fun HudHeaderRow(
    senderName: String,
    remoteIp: String,
    accents: com.carmap.bridge.ui.theme.CockpitAccents,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "SẴN SÀNG DẪN ĐƯỜNG",
                    color = accents.emerald,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Từ $senderName ($remoteIp)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Đóng", tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun HudRouteSummaryCard(
    title: String,
    kindLabel: String,
    sourceName: String,
    targetName: String,
    coords: String?,
    origin: String?,
    destination: String?,
    waypoints: List<String>,
    accents: com.carmap.bridge.ui.theme.CockpitAccents
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudBadge(text = kindLabel, bg = Color(0xFF1D4ED8))
                HudBadge(text = "$sourceName → $targetName", bg = Color(0xFF0F766E))
            }

            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (!coords.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = accents.cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Tọa độ: $coords",
                        color = accents.cyan,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (!origin.isNullOrBlank() || waypoints.isNotEmpty() || !destination.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Route, contentDescription = null, tint = accents.amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chi tiết lộ trình", color = accents.amber, style = MaterialTheme.typography.labelLarge)
                        }
                        if (!origin.isNullOrBlank()) {
                            Text("• Xuất phát: $origin", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        waypoints.forEachIndexed { idx, stop ->
                            Text("• Điểm dừng ${idx + 1}: $stop", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!destination.isNullOrBlank()) {
                            Text("• Đích đến: $destination", color = accents.emerald, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudIntentInfoCard(
    isInstalled: Boolean,
    targetName: String,
    accents: com.carmap.bridge.ui.theme.CockpitAccents
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accents.emerald, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (isInstalled) "Đã sẵn sàng mở dẫn đường trên $targetName" else "$targetName chưa cài trên máy — hãy mở bằng trình duyệt hoặc cài $targetName",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun HudActionButtons(
    targetName: String,
    onLaunchNative: () -> Unit,
    onOpenBrowserFallback: () -> Unit,
    onClose: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onLaunchNative,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.White)
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Mở ngay trong $targetName", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onOpenBrowserFallback,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Mở bản đồ trên trình duyệt")
        }

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Map, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Đóng")
        }
    }
}

@Composable
private fun HudBadge(text: String, bg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
