package com.carmap.bridge.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.carmap.bridge.R
import com.carmap.bridge.ShareReceiverActivity
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.SharedTransferRecord
import com.carmap.bridge.model.ThemeMode
import com.carmap.bridge.network.BoxNetworkConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SampleMapLink(
    val label: String,
    val subtitle: String,
    val rawText: String
)

data class BridgeUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val role: DeviceRole = DeviceRole.PHONE_SENDER,
    val roleConfigured: Boolean = false,
    val boxName: String = "Android Box Mazda CX-5",
    val pairingPin: String = "2689",
    val boxServiceRunning: Boolean = false,
    val bleEnabled: Boolean = true,
    val bleAdvertising: Boolean = false,
    val bleScanning: Boolean = false,
    val preferBleOverLan: Boolean = false,
    val autoOpenOnBox: Boolean = true,
    val simulatePreviewOnEmulator: Boolean = true,
    val boxPreferredTarget: MapProvider = MapProvider.AUTO,
    val phoneTargetOverride: MapProvider = MapProvider.AUTO,
    val discoveredBoxes: List<DiscoveredBox> = emptyList(),
    val pairedBox: DiscoveredBox? = null,
    val history: List<SharedTransferRecord> = emptyList(),
    val lastStatusBanner: String? = null
)

object BridgeRepository {

    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    var deviceInstanceId: String = "cb-local"
        private set

    val sampleLinks = listOf(
        SampleMapLink(
            label = "GMaps · Địa điểm",
            subtitle = "Nhà riêng (51 Ỷ Lan Nguyên Phi)",
            rawText = "Nhà riêng (51 Ỷ Lan Nguyên Phi)\nhttps://maps.google.com/?q=16.038921,108.218543"
        ),
        SampleMapLink(
            label = "GMaps · Lộ trình",
            subtitle = "Đà Nẵng → Cầu Rồng → Bà Nà Hills",
            rawText = "https://www.google.com/maps/dir/16.047079,108.206230/C%E1%BA%A7u+R%E1%BB%93ng,+%C4%90%C3%A0+N%E1%BA%B5ng/B%C3%A0+N%C3%A0+Hills/@16.02,108.12,12z"
        ),
        SampleMapLink(
            label = "Waze",
            subtitle = "Sân bay Tân Sơn Nhất",
            rawText = "https://waze.com/ul?ll=10.818463,106.658825&navigate=yes"
        ),
        SampleMapLink(
            label = "Vietmap",
            subtitle = "Hầm Thủ Thiêm, TP.HCM",
            rawText = "https://maps.vietmap.vn/?lat=10.771742&lng=106.709412&q=H%E1%BA%A7m%20Th%E1%BB%A7%20Thi%C3%AAm"
        ),
        SampleMapLink(
            label = "Tọa độ",
            subtitle = "Hồ Hoàn Kiếm, Hà Nội",
            rawText = "21.028511, 105.854444"
        )
    )

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        deviceInstanceId = AppPreferences.getOrCreateInstanceId(app)
        val loaded = AppPreferences.loadIntoState(app)
        _state.value = loaded
        loaded.pairedBox?.let { pushDirectShareShortcut(app, it) }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val snapshot = _state.value
        ioScope.launch { AppPreferences.saveState(ctx, snapshot) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        persist()
    }

    fun setRole(role: DeviceRole) {
        _state.update { it.copy(role = role, roleConfigured = true) }
        persist()
    }

    fun setBoxServiceRunning(running: Boolean) {
        _state.update { current ->
            val localDemo = DiscoveredBox(
                id = "local-box-demo",
                name = "${current.boxName} (Máy này)",
                host = "127.0.0.1",
                port = BoxNetworkConstants.TCP_PORT,
                isLocalDemo = true
            )
            val boxes = if (running) {
                (listOf(localDemo) + current.discoveredBoxes.filterNot { it.isLocalDemo })
            } else {
                current.discoveredBoxes.filterNot { it.isLocalDemo }
            }
            val paired = when {
                running && current.pairedBox == null -> localDemo
                !running && current.pairedBox?.isLocalDemo == true -> boxes.firstOrNull()
                else -> current.pairedBox
            }
            current.copy(
                boxServiceRunning = running,
                discoveredBoxes = boxes,
                pairedBox = paired
            )
        }
    }

    fun upsertDiscoveredBox(box: DiscoveredBox) {
        if (!box.isLocalDemo && box.id == deviceInstanceId) return
        _state.update { current ->
            val filtered = current.discoveredBoxes.filterNot {
                (it.channel == box.channel && it.host == box.host && it.port == box.port) ||
                    (it.channel == box.channel && it.id == box.id && !it.isLocalDemo)
            }
            val updated = (filtered + box).sortedByDescending { it.isLocalDemo }
            val updatedPaired = when {
                current.pairedBox == null -> box
                current.pairedBox.id == box.id && current.pairedBox.channel == box.channel -> box
                else -> current.pairedBox
            }
            current.copy(discoveredBoxes = updated, pairedBox = updatedPaired)
        }
    }

    fun pruneStaleBoxes(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        _state.update { current ->
            val kept = current.discoveredBoxes.filter { box ->
                box.isLocalDemo || box.id.startsWith("manual-") || box.lastSeenMs >= cutoff
            }
            if (kept.size == current.discoveredBoxes.size) return@update current
            val nextPaired = current.pairedBox?.let { paired ->
                if (kept.any { it.host == paired.host && it.channel == paired.channel } || paired.id.startsWith("manual-")) {
                    paired
                } else {
                    kept.firstOrNull()
                }
            }
            current.copy(discoveredBoxes = kept, pairedBox = nextPaired)
        }
    }

    fun setBleAdvertising(active: Boolean) {
        _state.update { it.copy(bleAdvertising = active) }
    }

    fun setBleScanning(active: Boolean) {
        _state.update { it.copy(bleScanning = active) }
    }

    fun setBleEnabled(enabled: Boolean) {
        _state.update { it.copy(bleEnabled = enabled) }
        persist()
    }

    fun setPreferBleOverLan(preferBle: Boolean) {
        _state.update { it.copy(preferBleOverLan = preferBle) }
        persist()
    }

    fun selectPairedBox(context: Context, box: DiscoveredBox) {
        _state.update { it.copy(pairedBox = box, lastStatusBanner = "Đã chọn Box: ${box.name}") }
        pushDirectShareShortcut(context, box)
        persist()
    }

    fun addManualBox(context: Context, host: String, name: String) {
        val box = DiscoveredBox(
            id = "manual-$host",
            name = name.ifBlank { "Box ($host)" },
            host = host.trim(),
            port = BoxNetworkConstants.TCP_PORT
        )
        upsertDiscoveredBox(box)
        selectPairedBox(context, box)
    }

    fun updateSettings(
        boxName: String? = null,
        pin: String? = null,
        autoOpen: Boolean? = null,
        simulatePreview: Boolean? = null,
        boxPreferredTarget: MapProvider? = null,
        phoneTargetOverride: MapProvider? = null
    ) {
        _state.update {
            it.copy(
                boxName = boxName ?: it.boxName,
                pairingPin = pin ?: it.pairingPin,
                autoOpenOnBox = autoOpen ?: it.autoOpenOnBox,
                simulatePreviewOnEmulator = simulatePreview ?: it.simulatePreviewOnEmulator,
                boxPreferredTarget = boxPreferredTarget ?: it.boxPreferredTarget,
                phoneTargetOverride = phoneTargetOverride ?: it.phoneTargetOverride
            )
        }
        persist()
    }

    fun setStatusBanner(msg: String?) {
        _state.update { it.copy(lastStatusBanner = msg) }
    }

    fun recordTransfer(
        direction: String,
        peerName: String,
        parsed: ParsedMapLink,
        targetProvider: MapProvider,
        intentUri: String,
        transportUsed: String,
        status: String
    ) {
        val record = SharedTransferRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            peerName = peerName,
            parsed = parsed,
            targetProvider = targetProvider,
            intentUri = intentUri,
            transportUsed = transportUsed,
            status = status
        )
        _state.update {
            it.copy(history = (listOf(record) + it.history).take(25))
        }
    }

    fun findBoxByShortcutId(shortcutId: String?): DiscoveredBox? {
        if (shortcutId.isNullOrBlank()) return null
        val targetId = shortcutId.removePrefix("box_")
        val cur = _state.value
        if (cur.pairedBox?.id == targetId) return cur.pairedBox
        return cur.discoveredBoxes.firstOrNull { it.id == targetId }
    }

    fun pushDirectShareShortcut(context: Context, box: DiscoveredBox) {
        val app = context.applicationContext
        ioScope.launch {
            runCatching {
                val shortcut = ShortcutInfoCompat.Builder(app, "box_${box.id}")
                    .setShortLabel(box.name)
                    .setLongLabel("Gửi thẳng lên ${box.name}")
                    .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                    .setCategories(setOf("com.carmap.bridge.SHARE_TO_CAR_BOX"))
                    .setLongLived(true)
                    .setIntent(
                        Intent(app, ShareReceiverActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            putExtra("target_box_host", box.host)
                        }
                    )
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
            }
        }
    }
}
