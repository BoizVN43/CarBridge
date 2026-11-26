package com.carmap.bridge.network

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.carmap.bridge.data.AppPreferences
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.data.PersistedOutboxItem
import com.carmap.bridge.launcher.MapIntentLauncher
import com.carmap.bridge.model.BridgeAction
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.RemoteCommand
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.service.BoxReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class PendingSendItem(
    val rawText: String,
    val resolvedUrl: String,
    val parsed: ParsedMapLink,
    val preferredTarget: MapProvider,
    val action: BridgeAction = BridgeAction.MAP,
    val queuedAtMs: Long = System.currentTimeMillis()
)

object CarBridgeConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean(false)
    private var appContext: Context? = null
    private var client: PhoneDiscoveryAndClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var btReceiver: BroadcastReceiver? = null
    private var pruneJob: Job? = null
    private var activeConsumers = 0

    @Volatile
    private var pendingOutbox: PendingSendItem? = null
    private val flushing = AtomicBoolean(false)

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext
        appContext = app

        // Restore Smart Queue from disk so destinations queued at home auto-send when entering the car
        AppPreferences.loadPendingOutbox(app)?.let { saved ->
            val media = MediaLinkHelper.extractMediaLink(saved.rawText)
            val parsed = if (media != null) {
                MediaLinkHelper.toParsedRecord(saved.rawText, media)
            } else {
                MapLinkResolver.parseImmediate(saved.rawText)
            }
            pendingOutbox = PendingSendItem(
                rawText = saved.rawText,
                resolvedUrl = saved.resolvedUrl,
                parsed = parsed,
                preferredTarget = saved.preferredTarget,
                action = if (media != null) BridgeAction.MEDIA_LINK else BridgeAction.MAP,
                queuedAtMs = saved.queuedAtMs
            )
            BridgeRepository.setPendingQueueTitle(saved.title)
        }

        client = PhoneDiscoveryAndClient(
            context = app,
            scope = scope,
            selfInstanceIdProvider = { BridgeRepository.deviceInstanceId },
            onBoxFound = { box ->
                BridgeRepository.upsertDiscoveredBox(box)
                tryFlushPendingOutbox()
            },
            onBleScanStateChanged = { scanning ->
                BridgeRepository.setBleScanning(scanning)
            }
        )

        registerNetworkWatcher(app)
        registerBluetoothWatcher(app)
    }

    @Synchronized
    fun retainDiscovery(context: Context) {
        init(context)
        activeConsumers += 1
        val bleEnabled = BridgeRepository.state.value.bleEnabled
        client?.startDiscovery(enableBle = bleEnabled)
        client?.sendActiveProbe()
        startPruneLoop()
    }

    @Synchronized
    fun releaseDiscovery() {
        activeConsumers = (activeConsumers - 1).coerceAtLeast(0)
        if (activeConsumers == 0) {
            pruneJob?.cancel()
            pruneJob = null
            client?.stopDiscovery()
        }
    }

    fun refreshBle(enable: Boolean) {
        client?.refreshBleScan(enable)
    }

    fun probeNow() {
        client?.sendActiveProbe()
    }

    fun clearPendingOutbox() {
        pendingOutbox = null
        BridgeRepository.setPendingQueueTitle(null)
        appContext?.let { ctx ->
            scope.launch { AppPreferences.savePendingOutbox(ctx, null) }
        }
    }

    private fun saveQueue(item: PendingSendItem?) {
        pendingOutbox = item
        BridgeRepository.setPendingQueueTitle(item?.parsed?.title)
        appContext?.let { ctx ->
            val persisted = item?.let {
                PersistedOutboxItem(
                    rawText = it.rawText,
                    resolvedUrl = it.resolvedUrl,
                    title = it.parsed.title,
                    preferredTarget = it.preferredTarget,
                    queuedAtMs = it.queuedAtMs
                )
            }
            scope.launch { AppPreferences.savePendingOutbox(ctx, persisted) }
        }
    }

    suspend fun pingActiveBox(context: Context): Result<String> {
        init(context)
        val state = BridgeRepository.state.value
        val box = state.pairedBox ?: state.discoveredBoxes.firstOrNull()
            ?: return Result.failure(IllegalStateException("Chưa có Android Box nào để Ping"))
        val res = client?.pingBox(box) ?: Result.failure(IllegalStateException("Chưa khởi tạo kết nối"))
        res.onSuccess { (channel, rttMs) ->
            val summary = "$channel · ${rttMs}ms"
            BridgeRepository.setLastPingSummary(summary)
            BridgeRepository.setStatusBanner("Kết nối tốt tới ${box.name} ($summary)")
        }.onFailure { err ->
            BridgeRepository.setLastPingSummary("Mất kết nối")
            BridgeRepository.setStatusBanner("Ping thất bại: ${err.message}")
        }
        return res.map { "${it.first} · ${it.second}ms" }
    }

    suspend fun sendClipboardToBox(context: Context, text: String): Result<String> {
        init(context)
        val clean = text.trim()
        if (clean.isBlank()) return Result.failure(IllegalStateException("Nội dung trống"))
        val state = BridgeRepository.state.value
        val box = state.pairedBox ?: state.discoveredBoxes.firstOrNull()
            ?: return Result.failure(IllegalStateException("Chưa thấy Android Box"))
        val res = client?.sendHybridToBox(
            primaryBox = box,
            discoveredBoxes = state.discoveredBoxes,
            preferBle = state.preferBleOverLan,
            pin = state.pairingPin,
            senderName = Build.MODEL ?: "Phone",
            rawText = clean,
            preferredTarget = MapProvider.AUTO,
            action = BridgeAction.CLIPBOARD,
            onWorkingBoxResolved = { BridgeRepository.selectPairedBox(context, it) }
        ) ?: Result.failure(IllegalStateException("Chưa khởi tạo kết nối"))
        res.onSuccess { BridgeRepository.setStatusBanner(it) }
            .onFailure { BridgeRepository.setStatusBanner(it.message ?: "Gửi Clipboard thất bại") }
        return res
    }

    suspend fun sendRemoteCommand(context: Context, command: RemoteCommand): Result<String> {
        init(context)
        val state = BridgeRepository.state.value
        val box = state.pairedBox ?: state.discoveredBoxes.firstOrNull()
            ?: return Result.failure(IllegalStateException("Chưa thấy Android Box"))
        val res = client?.sendHybridToBox(
            primaryBox = box,
            discoveredBoxes = state.discoveredBoxes,
            preferBle = state.preferBleOverLan,
            pin = state.pairingPin,
            senderName = Build.MODEL ?: "Phone",
            rawText = command.name,
            preferredTarget = MapProvider.AUTO,
            action = BridgeAction.REMOTE_CONTROL,
            onWorkingBoxResolved = { BridgeRepository.selectPairedBox(context, it) }
        ) ?: Result.failure(IllegalStateException("Chưa khởi tạo kết nối"))
        res.onSuccess { BridgeRepository.setStatusBanner(it) }
            .onFailure { BridgeRepository.setStatusBanner(it.message ?: "Điều khiển thất bại") }
        return res
    }

    suspend fun sendOrQueue(
        context: Context,
        primaryBox: DiscoveredBox?,
        rawText: String,
        preResolved: ParsedMapLink? = null,
        preferredTarget: MapProvider
    ): Result<String> {
        init(context)
        val media = MediaLinkHelper.extractMediaLink(rawText)
        val action = if (media != null) BridgeAction.MEDIA_LINK else BridgeAction.MAP
        val parsed = when {
            media != null -> MediaLinkHelper.toParsedRecord(rawText, media)
            preResolved != null -> preResolved
            else -> MapLinkResolver.resolveAndParse(rawText)
        }
        val resolvedUrl = parsed.resolvedUrl
        val state = BridgeRepository.state.value
        val box = primaryBox ?: state.pairedBox ?: state.discoveredBoxes.firstOrNull()

        if (box == null) {
            saveQueue(PendingSendItem(rawText, resolvedUrl, parsed, preferredTarget, action))
            client?.sendActiveProbe()
            return Result.failure(IllegalStateException("Chưa thấy Box — đã xếp hàng Smart Queue, sẽ tự gửi ngay khi lên xe"))
        }

        val res = client?.sendHybridToBox(
            primaryBox = box,
            discoveredBoxes = state.discoveredBoxes,
            preferBle = state.preferBleOverLan,
            pin = state.pairingPin,
            senderName = Build.MODEL ?: "Phone",
            rawText = rawText,
            resolvedUrl = resolvedUrl,
            preferredTarget = preferredTarget,
            action = action,
            onWorkingBoxResolved = { working ->
                BridgeRepository.selectPairedBox(context, working)
            }
        ) ?: Result.failure(IllegalStateException("Chưa khởi tạo kết nối"))

        res.onSuccess { msg ->
            saveQueue(null)
            if (!box.isLocalDemo) {
                val spec = if (action == BridgeAction.MAP) {
                    MapIntentLauncher.buildLaunchSpec(context, parsed, preferredTarget)
                } else null
                BridgeRepository.recordTransfer(
                    direction = if (action == BridgeAction.MEDIA_LINK) "GỬI MEDIA" else "ĐÃ GỬI",
                    peerName = box.name,
                    parsed = parsed,
                    targetProvider = spec?.effectiveProvider ?: MapProvider.UNKNOWN,
                    intentUri = spec?.intentUri ?: resolvedUrl,
                    transportUsed = box.channel.label,
                    status = msg
                )
            }
        }.onFailure {
            saveQueue(PendingSendItem(rawText, resolvedUrl, parsed, preferredTarget, action))
            client?.sendActiveProbe()
        }

        return res
    }

    private fun tryFlushPendingOutbox() {
        val pending = pendingOutbox ?: return
        if (!flushing.compareAndSet(false, true)) return
        val ctx = appContext ?: run {
            flushing.set(false)
            return
        }
        scope.launch {
            try {
                val state = BridgeRepository.state.value
                val box = state.pairedBox ?: state.discoveredBoxes.firstOrNull() ?: return@launch
                val res = client?.sendHybridToBox(
                    primaryBox = box,
                    discoveredBoxes = state.discoveredBoxes,
                    preferBle = state.preferBleOverLan,
                    pin = state.pairingPin,
                    senderName = Build.MODEL ?: "Phone",
                    rawText = pending.rawText,
                    resolvedUrl = pending.resolvedUrl,
                    preferredTarget = pending.preferredTarget,
                    action = pending.action,
                    onWorkingBoxResolved = { working -> BridgeRepository.selectPairedBox(ctx, working) }
                )
                if (res?.isSuccess == true) {
                    saveQueue(null)
                    BridgeRepository.setStatusBanner("Smart Queue: Đã tự động gửi ${pending.parsed.title}")
                }
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun startPruneLoop() {
        if (pruneJob?.isActive == true) return
        pruneJob = scope.launch {
            while (isActive) {
                delay(5000L)
                BridgeRepository.pruneStaleBoxes(maxAgeMs = 12_000L)
                tryFlushPendingOutbox()
            }
        }
    }

    private fun registerNetworkWatcher(context: Context) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (activeConsumers > 0) client?.restartNsdAndProbe()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (activeConsumers > 0) client?.restartNsdAndProbe()
                }
            }
            networkCallback = cb
            cm.registerNetworkCallback(req, cb)
        }
    }

    private fun registerBluetoothWatcher(context: Context) {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent?) {
                    if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        if (activeConsumers > 0 && BridgeRepository.state.value.bleEnabled) {
                            client?.refreshBleScan(true)
                        }
                        BoxReceiverService.refreshBle(ctx)
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        client?.refreshBleScan(false)
                        BridgeRepository.setBleAdvertising(false)
                    }
                }
            }
            btReceiver = receiver
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
    }
}
