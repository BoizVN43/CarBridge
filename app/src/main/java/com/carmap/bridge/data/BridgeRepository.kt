package com.carmap.bridge.data

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.runtime.Immutable
import com.carmap.bridge.R
import com.carmap.bridge.ShareReceiverActivity
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.ParkingPin
import com.carmap.bridge.model.SavedPlace
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
import java.util.concurrent.ConcurrentHashMap

data class SampleMapLink(
    val label: String,
    val subtitle: String,
    val rawText: String
)

@Immutable
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
    val simulatePreviewOnEmulator: Boolean = false,
    val boxPreferredTarget: MapProvider = MapProvider.AUTO,
    val phoneTargetOverride: MapProvider = MapProvider.AUTO,
    val discoveredBoxes: List<DiscoveredBox> = emptyList(),
    val pairedBox: DiscoveredBox? = null,
    val history: List<SharedTransferRecord> = emptyList(),
    val savedPlaces: List<SavedPlace> = emptyList(),
    val clipboardHistory: List<String> = emptyList(),
    val lastReceivedClipboard: String? = null,
    val parkingPin: ParkingPin? = null,
    val fileTransferProgress: Float? = null,
    val fileTransferLabel: String? = null,
    val pendingQueueTitle: String? = null,
    val lastPingSummary: String? = null,
    val lastStatusBanner: String? = null
)

object BridgeRepository {

    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private val boxLastSeenMap = ConcurrentHashMap<String, Long>()
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

    private fun boxKey(box: DiscoveredBox): String = "${box.channel.name}:${box.id}:${box.host}:${box.port}"

    fun upsertDiscoveredBox(box: DiscoveredBox) {
        if (!box.isLocalDemo && box.id == deviceInstanceId) return
        boxLastSeenMap[boxKey(box)] = System.currentTimeMillis()
        // Fast path: if an identical box is already in state (only heartbeat timestamp changed),
        // skip StateFlow emission completely to prevent unnecessary 2-second UI recompositions!
        val cur = _state.value
        val existing = cur.discoveredBoxes.firstOrNull {
            it.channel == box.channel && it.host == box.host && it.port == box.port &&
                it.id == box.id && it.name == box.name && it.bleAddress == box.bleAddress
        }
        if (existing != null && cur.pairedBox != null) return

        val normalizedBox = box.copy(lastSeenMs = 0L)
        _state.update { current ->
            val filtered = current.discoveredBoxes.filterNot {
                (it.channel == normalizedBox.channel && it.host == normalizedBox.host && it.port == normalizedBox.port) ||
                    (it.channel == normalizedBox.channel && it.id == normalizedBox.id && !it.isLocalDemo)
            }
            val updated = (filtered + normalizedBox).sortedByDescending { it.isLocalDemo }
            val updatedPaired = when {
                current.pairedBox == null -> normalizedBox
                current.pairedBox.id == normalizedBox.id && current.pairedBox.channel == normalizedBox.channel -> normalizedBox
                else -> current.pairedBox
            }
            current.copy(discoveredBoxes = updated, pairedBox = updatedPaired)
        }
    }

    fun pruneStaleBoxes(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        _state.update { current ->
            val kept = current.discoveredBoxes.filter { box ->
                if (box.isLocalDemo || box.id.startsWith("manual-") || box.id.startsWith("qr-")) {
                    true
                } else {
                    val lastSeen = boxLastSeenMap[boxKey(box)] ?: box.lastSeenMs
                    lastSeen >= cutoff
                }
            }
            if (kept.size == current.discoveredBoxes.size) return@update current
            val nextPaired = current.pairedBox?.let { paired ->
                if (kept.any { it.host == paired.host && it.channel == paired.channel } ||
                    paired.id.startsWith("manual-") ||
                    paired.id.startsWith("qr-")
                ) {
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

    fun setPendingQueueTitle(title: String?) {
        _state.update { it.copy(pendingQueueTitle = title) }
    }

    fun setLastPingSummary(summary: String?) {
        _state.update { it.copy(lastPingSummary = summary) }
    }

    fun addSavedPlace(label: String, rawLink: String) {
        val cleanLabel = label.trim()
        val cleanLink = rawLink.trim()
        if (cleanLabel.isBlank() || cleanLink.isBlank()) return
        val item = SavedPlace(
            id = UUID.randomUUID().toString(),
            label = cleanLabel,
            rawLink = cleanLink
        )
        _state.update { cur ->
            cur.copy(savedPlaces = (cur.savedPlaces + item).take(12))
        }
        persist()
    }

    fun removeSavedPlace(id: String) {
        _state.update { cur ->
            cur.copy(savedPlaces = cur.savedPlaces.filterNot { it.id == id })
        }
        persist()
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        persist()
    }

    fun recordClipboardItem(text: String, isReceivedOnBox: Boolean = false) {
        val clean = text.trim()
        if (clean.isBlank()) return
        _state.update { cur ->
            val updated = (listOf(clean) + cur.clipboardHistory.filterNot { it == clean }).take(8)
            cur.copy(
                clipboardHistory = updated,
                lastReceivedClipboard = if (isReceivedOnBox) clean else cur.lastReceivedClipboard
            )
        }
        persist()
    }

    fun clearClipboardHistory() {
        _state.update { it.copy(clipboardHistory = emptyList(), lastReceivedClipboard = null) }
        persist()
    }

    fun setParkingPin(pin: ParkingPin?) {
        _state.update { it.copy(parkingPin = pin) }
        persist()
    }

    fun setFileTransferProgress(progress: Float?, label: String? = null) {
        _state.update { it.copy(fileTransferProgress = progress, fileTransferLabel = label) }
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
            it.copy(history = (listOf(record) + it.history).take(40))
        }
        persist()
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
