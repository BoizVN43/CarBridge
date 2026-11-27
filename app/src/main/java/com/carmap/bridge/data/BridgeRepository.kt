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
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.network.BoxNetworkConstants
import com.carmap.bridge.widget.CarBridgeWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

@Immutable
data class BridgeUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val role: DeviceRole = DeviceRole.PHONE_SENDER,
    val roleConfigured: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val lastBootStartNote: String? = null,
    val boxName: String = "Android Box Mazda CX-5",
    val pairingPin: String = "2689",
    val boxServiceRunning: Boolean = false,
    val bleEnabled: Boolean = true,
    val bleAdvertising: Boolean = false,
    val bleScanning: Boolean = false,
    val preferBleOverLan: Boolean = false,
    val proximityOnly2m: Boolean = false,
    val autoConnectOnCarBt: Boolean = true,
    val lastKnownLanHost: String? = null,
    val lastKnownLanPort: Int = BoxNetworkConstants.TCP_PORT,
    val lastKnownBleAddress: String? = null,
    val lastAutoConnectNote: String? = null,
    val rootDetected: Boolean = false,
    val rootModeEnabled: Boolean = false,
    val rootAutoSplitScreen: Boolean = true,
    val rootSilentInstall: Boolean = true,
    val rootAutoEnableRadios: Boolean = false,
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
    private var configPersistJob: Job? = null
    private val boxLastSeenMap = ConcurrentHashMap<String, Long>()
    var deviceInstanceId: String = "cb-local"
        private set

    @Volatile
    var cachedBoxClipboard: String? = null
        private set

    fun updateCachedBoxClipboard(text: String?) {
        val clean = text?.trim()?.takeIf { it.isNotBlank() }
        if (clean != null) {
            cachedBoxClipboard = clean
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        deviceInstanceId = AppPreferences.getOrCreateInstanceId(app)
        // Fast synchronous load of lightweight config only (no JSON parsing on Main Thread)
        val core = AppPreferences.loadFastCoreState(app)
        _state.value = core
        core.pairedBox?.let { pushDirectShareShortcut(app, it) }

        // Load heavier JSON collections asynchronously off Main Thread
        ioScope.launch {
            val heavy = AppPreferences.loadHeavyCollections(app)
            _state.update { cur ->
                cur.copy(
                    history = if (cur.history.isEmpty()) heavy.history else cur.history,
                    savedPlaces = if (cur.savedPlaces.isEmpty()) heavy.savedPlaces else cur.savedPlaces,
                    clipboardHistory = if (cur.clipboardHistory.isEmpty()) heavy.clipboardHistory else cur.clipboardHistory,
                    parkingPin = cur.parkingPin ?: heavy.parkingPin,
                    pendingQueueTitle = cur.pendingQueueTitle ?: heavy.pendingQueueTitle
                )
            }
            if (heavy.savedPlaces.isNotEmpty()) {
                CarBridgeWidgetProvider.requestUpdate(app)
            }
        }
    }

    private fun persistConfig(debounced: Boolean = false) {
        val ctx = appContext ?: return
        if (debounced) {
            configPersistJob?.cancel()
            configPersistJob = ioScope.launch {
                delay(350L)
                AppPreferences.saveConfigOnly(ctx, _state.value)
            }
        } else {
            configPersistJob?.cancel()
            val snapshot = _state.value
            ioScope.launch { AppPreferences.saveConfigOnly(ctx, snapshot) }
        }
    }

    private fun persistCollections() {
        val ctx = appContext ?: return
        val snapshot = _state.value
        ioScope.launch { AppPreferences.saveCollectionsOnly(ctx, snapshot) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        persistConfig()
    }

    fun setRole(role: DeviceRole) {
        _state.update { it.copy(role = role, roleConfigured = true) }
        persistConfig()
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        _state.update { it.copy(autoStartOnBoot = enabled) }
        persistConfig()
    }

    fun setRootDetected(detected: Boolean) {
        _state.update { it.copy(rootDetected = detected) }
    }

    fun setRootModeEnabled(enabled: Boolean) {
        _state.update { it.copy(rootModeEnabled = enabled) }
        persistConfig()
    }

    fun setRootAutoSplitScreen(enabled: Boolean) {
        _state.update { it.copy(rootAutoSplitScreen = enabled) }
        persistConfig()
    }

    fun setRootSilentInstall(enabled: Boolean) {
        _state.update { it.copy(rootSilentInstall = enabled) }
        persistConfig()
    }

    fun setRootAutoEnableRadios(enabled: Boolean) {
        _state.update { it.copy(rootAutoEnableRadios = enabled) }
        persistConfig()
    }

    fun setAutoConnectOnCarBt(enabled: Boolean) {
        _state.update { it.copy(autoConnectOnCarBt = enabled) }
        persistConfig()
    }

    fun recordAutoConnectNote(triggerLabel: String) {
        val ctx = appContext
        val fmt = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault())
        val note = "${fmt.format(java.util.Date())} ($triggerLabel)"
        _state.update { it.copy(lastAutoConnectNote = note) }
        if (ctx != null) {
            ioScope.launch { AppPreferences.recordAutoConnectEvent(ctx, triggerLabel) }
        }
    }

    fun rememberLanEndpoint(host: String, port: Int = BoxNetworkConstants.TCP_PORT) {
        val clean = host.trim()
        if (clean.isBlank() || clean == "127.0.0.1") return
        val cur = _state.value
        if (cur.lastKnownLanHost == clean && cur.lastKnownLanPort == port) return
        _state.update { it.copy(lastKnownLanHost = clean, lastKnownLanPort = port) }
        persistConfig()
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
        // Fast path: if an identical box is already in state (only heartbeat timestamp or tiny RSSI jitter changed),
        // skip StateFlow emission completely to prevent unnecessary UI recompositions!
        val cur = _state.value
        val existing = cur.discoveredBoxes.firstOrNull {
            it.channel == box.channel && it.host == box.host && it.port == box.port &&
                it.id == box.id && it.name == box.name && it.bleAddress == box.bleAddress
        }
        val distUnchanged = if (existing != null) {
            val oldD = existing.estimatedDistanceMeters
            val newD = box.estimatedDistanceMeters
            (oldD == null && newD == null) ||
                (oldD != null && newD != null && abs(oldD - newD) < 0.25f)
        } else {
            false
        }
        if (existing != null && distUnchanged && cur.pairedBox != null) return

        val normalizedBox = box.copy(lastSeenMs = 0L)
        var endpointsChanged = false
        _state.update { current ->
            val filtered = current.discoveredBoxes.filterNot {
                (it.channel == normalizedBox.channel && it.host == normalizedBox.host && it.port == normalizedBox.port) ||
                    (it.channel == normalizedBox.channel && it.id == normalizedBox.id && !it.isLocalDemo)
            }
            val updated = (filtered + normalizedBox).sortedByDescending { it.isLocalDemo }
            val updatedPaired = when {
                current.pairedBox == null -> normalizedBox
                current.pairedBox.id == normalizedBox.id && current.pairedBox.channel == normalizedBox.channel -> normalizedBox
                // Auto-upgrade from BLE (or manual) to live LAN when both are present unless preferBleOverLan is on
                !current.preferBleOverLan &&
                    current.pairedBox.channel == TransportChannel.BLE &&
                    normalizedBox.channel == TransportChannel.LAN &&
                    !normalizedBox.isLocalDemo -> normalizedBox
                else -> current.pairedBox
            }
            val nextLanHost = if (!normalizedBox.isLocalDemo &&
                normalizedBox.channel == TransportChannel.LAN &&
                normalizedBox.host.isNotBlank() &&
                normalizedBox.host != "127.0.0.1"
            ) normalizedBox.host else current.lastKnownLanHost
            val nextLanPort = if (!normalizedBox.isLocalDemo && normalizedBox.channel == TransportChannel.LAN) {
                normalizedBox.port
            } else current.lastKnownLanPort
            val nextBleAddr = if (!normalizedBox.bleAddress.isNullOrBlank()) {
                normalizedBox.bleAddress
            } else current.lastKnownBleAddress

            endpointsChanged = nextLanHost != current.lastKnownLanHost ||
                nextLanPort != current.lastKnownLanPort ||
                nextBleAddr != current.lastKnownBleAddress ||
                (updatedPaired != current.pairedBox && !normalizedBox.isLocalDemo)

            current.copy(
                discoveredBoxes = updated,
                pairedBox = updatedPaired,
                lastKnownLanHost = nextLanHost,
                lastKnownLanPort = nextLanPort,
                lastKnownBleAddress = nextBleAddr
            )
        }
        if (endpointsChanged) {
            persistConfig()
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
        persistConfig()
    }

    fun setPreferBleOverLan(preferBle: Boolean) {
        _state.update { it.copy(preferBleOverLan = preferBle) }
        persistConfig()
    }

    fun setProximityOnly2m(enabled: Boolean) {
        _state.update { cur ->
            val filtered = if (enabled) {
                cur.discoveredBoxes.filter { box ->
                    box.isLocalDemo ||
                        box.id.startsWith("manual-") ||
                        box.id.startsWith("qr-") ||
                        (box.estimatedDistanceMeters != null && box.estimatedDistanceMeters <= 2.3f)
                }
            } else {
                cur.discoveredBoxes
            }
            val nextPaired = cur.pairedBox?.let { paired ->
                if (!enabled ||
                    paired.isLocalDemo ||
                    paired.id.startsWith("manual-") ||
                    paired.id.startsWith("qr-") ||
                    filtered.any { it.host == paired.host && it.channel == paired.channel }
                ) {
                    paired
                } else {
                    filtered.firstOrNull()
                }
            }
            cur.copy(
                proximityOnly2m = enabled,
                discoveredBoxes = filtered,
                pairedBox = nextPaired
            )
        }
        persistConfig()
    }

    fun selectPairedBox(context: Context, box: DiscoveredBox) {
        _state.update { cur ->
            val nextLanHost = if (!box.isLocalDemo && box.channel == TransportChannel.LAN && box.host.isNotBlank() && box.host != "127.0.0.1") {
                box.host
            } else cur.lastKnownLanHost
            val nextLanPort = if (!box.isLocalDemo && box.channel == TransportChannel.LAN) box.port else cur.lastKnownLanPort
            val nextBleAddr = if (!box.bleAddress.isNullOrBlank()) box.bleAddress else cur.lastKnownBleAddress
            cur.copy(
                pairedBox = box,
                lastKnownLanHost = nextLanHost,
                lastKnownLanPort = nextLanPort,
                lastKnownBleAddress = nextBleAddr,
                lastStatusBanner = "Đã chọn Box: ${box.name}"
            )
        }
        pushDirectShareShortcut(context, box)
        persistConfig()
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
        val isTypingEdit = (boxName != null || pin != null) &&
            autoOpen == null && simulatePreview == null &&
            boxPreferredTarget == null && phoneTargetOverride == null
        persistConfig(debounced = isTypingEdit)
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
        persistCollections()
        appContext?.let { CarBridgeWidgetProvider.requestUpdate(it) }
    }

    fun removeSavedPlace(id: String) {
        _state.update { cur ->
            cur.copy(savedPlaces = cur.savedPlaces.filterNot { it.id == id })
        }
        persistCollections()
        appContext?.let { CarBridgeWidgetProvider.requestUpdate(it) }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        persistCollections()
    }

    fun recordClipboardItem(text: String, isReceivedOnBox: Boolean = false) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (isReceivedOnBox) {
            cachedBoxClipboard = clean
        }
        _state.update { cur ->
            val updated = (listOf(clean) + cur.clipboardHistory.filterNot { it == clean }).take(8)
            cur.copy(
                clipboardHistory = updated,
                lastReceivedClipboard = if (isReceivedOnBox) clean else cur.lastReceivedClipboard
            )
        }
        persistCollections()
    }

    fun clearClipboardHistory() {
        _state.update { it.copy(clipboardHistory = emptyList(), lastReceivedClipboard = null) }
        persistCollections()
    }

    fun setParkingPin(pin: ParkingPin?) {
        _state.update { it.copy(parkingPin = pin) }
        persistCollections()
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
        persistCollections()
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
