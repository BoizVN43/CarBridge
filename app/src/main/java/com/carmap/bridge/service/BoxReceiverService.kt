package com.carmap.bridge.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.carmap.bridge.MainActivity
import com.carmap.bridge.NavigationDispatchActivity
import com.carmap.bridge.R
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.launcher.MapIntentLauncher
import com.carmap.bridge.model.BridgeAction
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.network.BleBoxServerTransport
import com.carmap.bridge.network.BlePermissionsHelper
import com.carmap.bridge.network.BoxNetworkConstants
import com.carmap.bridge.network.BoxServerTransport
import com.carmap.bridge.network.IncomingMapPacket
import com.carmap.bridge.network.LanBroadcastHelper
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.parser.MediaLinkHelper
import com.carmap.bridge.update.AppUpdater
import com.carmap.bridge.util.RootHelper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BoxReceiverService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lanTransport: BoxServerTransport? = null
    private var bleTransport: BleBoxServerTransport? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var radioWakeReceiver: BroadcastReceiver? = null
    private var receivedCount: Int = 0
    private var lastReceivedSummary: String? = null
    private var bleActiveNow: Boolean = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        runCatching { BridgeRepository.init(this) }
        createNotificationChannel()
        val notification = buildNotification("Đang chờ link bản đồ từ điện thoại...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgsType = if (BlePermissionsHelper.hasAllPermissions(this)) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            runCatching {
                startForeground(NOTIF_ID, notification, fgsType)
            }.onFailure {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (BridgeRepository.state.value.autoStartOnBoot) {
            BoxWatchdogJobService.schedule(this)
        }

        val lan = BoxServerTransport(
            context = this,
            scope = serviceScope,
            boxIdProvider = { BridgeRepository.deviceInstanceId },
            boxNameProvider = { BridgeRepository.state.value.boxName },
            onPacketReceived = { packet, remoteIp ->
                val label = if (remoteIp == "127.0.0.1") "LAN Loopback (<10ms)" else "LAN Socket"
                handleIncomingPacket(packet, remoteIp, label)
            },
            onFilePinCheck = { pin ->
                val expected = BridgeRepository.state.value.pairingPin
                expected.isBlank() || pin == expected
            },
            onFileReceived = { fileName, mimeType, file, pin, remoteIp ->
                handleIncomingFile(fileName, mimeType, file, pin, remoteIp)
            }
        )
        lanTransport = lan

        val ble = BleBoxServerTransport(
            context = this,
            scope = serviceScope,
            boxNameProvider = { BridgeRepository.state.value.boxName },
            onStatusChanged = { active ->
                bleActiveNow = active
                BridgeRepository.setBleAdvertising(active)
                updateNotificationStatus()
            },
            onPacketReceived = { packet, remoteBle ->
                handleIncomingPacket(packet, remoteBle, "Bluetooth LE GATT")
            }
        )
        bleTransport = ble

        serviceScope.launch(Dispatchers.IO) {
            BoxServerTransport.cleanupOldReceivedFiles(this@BoxReceiverService)
            if (BridgeRepository.state.value.rootModeEnabled) {
                val hasRoot = RootHelper.checkRootAccess()
                BridgeRepository.setRootDetected(hasRoot)
                if (hasRoot) {
                    RootHelper.applySystemPrivileges(packageName)
                    if (BridgeRepository.state.value.rootAutoEnableRadios) {
                        RootHelper.ensureRadiosEnabled(this@BoxReceiverService)
                    }
                }
            }
            lan.start()
            if (BridgeRepository.state.value.bleEnabled) {
                ble.start()
            }
            // Box boot warmup: Wi-Fi & Bluetooth chips on Android Boxes often initialize 3–20s after boot.
            // Retry BLE advertising & LAN beacon bursts automatically so Phone connects the instant radios are up.
            for (waitMs in longArrayOf(2_000L, 4_000L, 7_000L, 12_000L)) {
                delay(waitMs)
                if (!isRunning) break
                if (BridgeRepository.state.value.bleEnabled && !ble.isAdvertising) {
                    ble.start()
                }
                lan.sendImmediateBeaconBurst()
            }
        }

        registerWifiNetworkCallback()
        registerRadioWakeReceiver()
        BridgeRepository.setBoxServiceRunning(true)
    }

    private fun registerRadioWakeReceiver() {
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent?) {
                    when (intent?.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            if (st == BluetoothAdapter.STATE_ON && BridgeRepository.state.value.bleEnabled) {
                                serviceScope.launch(Dispatchers.IO) {
                                    delay(500L)
                                    bleTransport?.start()
                                }
                            } else if (st == BluetoothAdapter.STATE_OFF) {
                                bleTransport?.stop()
                            }
                        }
                        WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                            val st = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                            if (st == WifiManager.WIFI_STATE_ENABLED) {
                                lanTransport?.sendImmediateBeaconBurst()
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            // Box waking from ACC sleep: re-announce on LAN & ensure BLE is advertising
                            serviceScope.launch(Dispatchers.IO) {
                                if (BridgeRepository.state.value.bleEnabled && bleTransport?.isAdvertising != true) {
                                    bleTransport?.start()
                                }
                                lanTransport?.sendImmediateBeaconBurst()
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            radioWakeReceiver = receiver
            registerReceiver(receiver, filter)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (BridgeRepository.state.value.autoStartOnBoot) {
            // Survive "Clear All Recents" on Android Box ROMs
            BoxWatchdogJobService.triggerImmediate(this, delayMs = 1_500L)
            runCatching {
                val restartIntent = Intent(this, BootReceiver::class.java).apply {
                    action = BootReceiver.ACTION_SERVICE_RESTART
                }
                val pi = PendingIntent.getBroadcast(
                    this,
                    48291,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                am?.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2_000L,
                    pi
                )
            }
        }
    }

    private fun registerWifiNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lanTransport?.sendImmediateBeaconBurst()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    lanTransport?.sendImmediateBeaconBurst()
                }
            }
            wifiNetworkCallback = cb
            cm.registerNetworkCallback(req, cb)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_BLE) {
            if (BridgeRepository.state.value.bleEnabled) {
                bleTransport?.start()
            } else {
                bleTransport?.stop()
            }
        }
        return START_STICKY
    }

    private suspend fun handleIncomingPacket(
        packet: IncomingMapPacket,
        remoteEndpoint: String,
        transportUsed: String
    ): Pair<Boolean, String> {
        if (packet.action == BridgeAction.PING) {
            val lanIp = LanBroadcastHelper.localIpv4Address().orEmpty()
            return true to if (lanIp.isNotBlank()) "PONG|$lanIp" else "PONG"
        }

        val state = BridgeRepository.state.value
        if (state.pairingPin.isNotBlank() && packet.pin != state.pairingPin) {
            return false to "Sai mã PIN ghép nối"
        }

        when (packet.action) {
            BridgeAction.CLIPBOARD -> {
                val text = packet.rawText.trim()
                if (text.isBlank()) return false to "Nội dung Clipboard trống"
                withContext(Dispatchers.Main.immediate) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("CarBridge", text))
                    Toast.makeText(this@BoxReceiverService, "CarBridge: Đã chép vào Clipboard", Toast.LENGTH_SHORT).show()
                }
                val parsedClip = ParsedMapLink(
                    rawInput = text,
                    extractedUrl = null,
                    resolvedUrl = text,
                    sourceProvider = MapProvider.UNKNOWN,
                    kind = LinkKind.CLIPBOARD,
                    title = "Clipboard: ${text.take(48)}"
                )
                BridgeRepository.recordClipboardItem(text, isReceivedOnBox = true)
                BridgeRepository.recordTransfer(
                    direction = "NHẬN CLIPBOARD",
                    peerName = "${packet.senderName} ($remoteEndpoint)",
                    parsed = parsedClip,
                    targetProvider = MapProvider.UNKNOWN,
                    intentUri = "",
                    transportUsed = transportUsed,
                    status = "Đã chép vào Clipboard Box"
                )
                BridgeRepository.setStatusBanner("Box đã chép Clipboard (${text.length} ký tự)")
                noteReceivedItem("Clipboard: ${text.take(28)}")
                return true to "Đã chép vào Clipboard của Box"
            }

            BridgeAction.PULL_CLIPBOARD -> {
                val clipText = withContext(Dispatchers.Main.immediate) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(this@BoxReceiverService)?.toString()?.trim()
                }.orEmpty()
                    .ifBlank { BridgeRepository.cachedBoxClipboard.orEmpty() }
                    .ifBlank { state.lastReceivedClipboard.orEmpty() }
                if (clipText.isBlank()) {
                    return false to "Clipboard trên Box đang trống"
                }
                return true to clipText
            }

            BridgeAction.WEB_LINK -> {
                return handleWebLink(packet, remoteEndpoint, transportUsed)
            }

            BridgeAction.MEDIA_LINK -> {
                return handleMediaLink(packet, remoteEndpoint, transportUsed)
            }

            BridgeAction.BOX_SYSTEM_CMD -> {
                if (state.pairingPin.isBlank()) {
                    return false to "Hãy đặt mã PIN ghép nối trên Box để bật điều khiển Root từ xa"
                }
                if (!state.rootModeEnabled || (!RootHelper.isRootGranted && !RootHelper.checkRootAccess())) {
                    return false to "Cần bật Chế độ Root nâng cao trên Android Box"
                }
                val res = RootHelper.executeBoxSystemCommand(packet.rawText.trim())
                if (res.first) {
                    BridgeRepository.setStatusBanner("Root Box: ${res.second}")
                }
                return res
            }

            else -> {
                // Auto-detect media URLs or generic web URLs
                val media = MediaLinkHelper.extractMediaLink(packet.rawText)
                if (media != null) {
                    return handleMediaLink(packet, remoteEndpoint, transportUsed)
                }
                val webUrl = MediaLinkHelper.extractGenericWebLink(packet.rawText)
                if (webUrl != null) {
                    return handleWebLink(packet, remoteEndpoint, transportUsed)
                }
            }
        }

        val parsed = MapLinkResolver.resolveAndParse(packet.rawText, packet.resolvedUrl)
        // If a short redirect expanded to a non-map Media or Web URL, route accordingly
        if (parsed.sourceProvider == MapProvider.UNKNOWN &&
            parsed.primaryLat == null &&
            parsed.resolvedUrl.startsWith("http", ignoreCase = true)
        ) {
            if (MediaLinkHelper.extractMediaLink(parsed.resolvedUrl) != null) {
                return handleMediaLink(packet.copy(resolvedUrl = parsed.resolvedUrl), remoteEndpoint, transportUsed)
            }
            if (MediaLinkHelper.extractGenericWebLink(parsed.resolvedUrl, allowShortRedirect = true) != null) {
                return handleWebLink(packet.copy(resolvedUrl = parsed.resolvedUrl), remoteEndpoint, transportUsed)
            }
        }
        val targetPref = if (packet.preferredTarget != MapProvider.AUTO) {
            packet.preferredTarget
        } else {
            state.boxPreferredTarget
        }
        val spec = MapIntentLauncher.buildLaunchSpec(this, parsed, targetPref)

        var openedReal = false
        if (state.autoOpenOnBox) {
            acquireBriefWakeLock()
            if (!state.simulatePreviewOnEmulator && spec.isPackageInstalled) {
                if (state.rootModeEnabled && RootHelper.isRootGranted) {
                    val (rootOk, _) = RootHelper.launchMapSmartRoot(
                        context = this@BoxReceiverService,
                        spec = spec,
                        autoSplitEnabled = state.rootAutoSplitScreen
                    )
                    openedReal = rootOk
                }
                if (!openedReal) {
                    withContext(Dispatchers.Main.immediate) {
                        openedReal = MapIntentLauncher.launchRealMapApp(this@BoxReceiverService, spec)
                    }
                }
            }
            if (!openedReal) {
                withContext(Dispatchers.Main.immediate) {
                    NavigationDispatchActivity.launch(
                        context = this@BoxReceiverService,
                        parsed = parsed,
                        spec = spec,
                        senderName = packet.senderName,
                        remoteIp = remoteEndpoint
                    )
                }
            }
        }

        BridgeRepository.recordTransfer(
            direction = "NHẬN TRÊN BOX",
            peerName = "${packet.senderName} ($remoteEndpoint)",
            parsed = parsed,
            targetProvider = spec.effectiveProvider,
            intentUri = spec.intentUri,
            transportUsed = transportUsed,
            status = if (openedReal) "Đã mở ${spec.effectiveProvider.displayName}" else "Đã bật HUD điều hướng Box"
        )
        BridgeRepository.setStatusBanner("Box vừa nhận ($transportUsed): ${parsed.title}")
        noteReceivedItem(parsed.title)

        return true to "Box đã nhận & mở ${spec.effectiveProvider.displayName}"
    }

    private suspend fun handleMediaLink(
        packet: IncomingMapPacket,
        remoteEndpoint: String,
        transportUsed: String
    ): Pair<Boolean, String> {
        val media = MediaLinkHelper.extractMediaLink(packet.rawText)
        val url = media?.url ?: packet.resolvedUrl.ifBlank { packet.rawText.trim() }
        val serviceName = media?.serviceName ?: "Media"
        val parsed = if (media != null) {
            MediaLinkHelper.toParsedRecord(packet.rawText, media)
        } else {
            ParsedMapLink(
                rawInput = packet.rawText,
                extractedUrl = url,
                resolvedUrl = url,
                sourceProvider = MapProvider.UNKNOWN,
                kind = LinkKind.MEDIA,
                title = "Mở media: ${url.take(48)}"
            )
        }

        acquireBriefWakeLock()
        val opened = withContext(Dispatchers.Main.immediate) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                true
            }.getOrDefault(false)
        }

        BridgeRepository.recordTransfer(
            direction = "NHẬN MEDIA",
            peerName = "${packet.senderName} ($remoteEndpoint)",
            parsed = parsed,
            targetProvider = MapProvider.UNKNOWN,
            intentUri = url,
            transportUsed = transportUsed,
            status = if (opened) "Đã mở $serviceName" else "Đã nhận link $serviceName"
        )
        BridgeRepository.setStatusBanner("Box vừa mở $serviceName")
        noteReceivedItem(parsed.title)
        return true to "Box đã mở $serviceName"
    }

    private suspend fun handleWebLink(
        packet: IncomingMapPacket,
        remoteEndpoint: String,
        transportUsed: String
    ): Pair<Boolean, String> {
        val url = MediaLinkHelper.extractGenericWebLink(packet.rawText)
            ?: packet.resolvedUrl.ifBlank { packet.rawText.trim() }
        val parsed = MediaLinkHelper.toWebParsedRecord(packet.rawText, url)
        acquireBriefWakeLock()
        val opened = withContext(Dispatchers.Main.immediate) {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
                true
            }.getOrDefault(false)
        }
        BridgeRepository.recordTransfer(
            direction = "NHẬN WEB",
            peerName = "${packet.senderName} ($remoteEndpoint)",
            parsed = parsed,
            targetProvider = MapProvider.UNKNOWN,
            intentUri = url,
            transportUsed = transportUsed,
            status = if (opened) "Đã mở trình duyệt trên Box" else "Đã nhận link web"
        )
        BridgeRepository.setStatusBanner("Box đã mở trang web: ${parsed.title}")
        noteReceivedItem(parsed.title)
        return true to "Box đã mở trang web"
    }

    private suspend fun handleIncomingFile(
        fileName: String,
        mimeType: String,
        file: File,
        pin: String,
        remoteIp: String
    ): Pair<Boolean, String> {
        val state = BridgeRepository.state.value
        if (state.pairingPin.isNotBlank() && pin != state.pairingPin) {
            file.delete()
            return false to "Sai mã PIN ghép nối"
        }

        val isApk = fileName.endsWith(".apk", ignoreCase = true) ||
            mimeType.contains("android.package-archive", ignoreCase = true)
        acquireBriefWakeLock()
        val silentInstalled = if (isApk &&
            state.pairingPin.isNotBlank() &&
            state.rootModeEnabled &&
            state.rootSilentInstall &&
            RootHelper.isRootGranted
        ) {
            val (ok, _) = RootHelper.silentInstallApk(file)
            ok
        } else {
            false
        }
        val opened = if (silentInstalled) {
            withContext(Dispatchers.Main.immediate) {
                Toast.makeText(this@BoxReceiverService, "CarBridge Root: Đã cài ngầm $fileName", Toast.LENGTH_LONG).show()
            }
            true
        } else {
            withContext(Dispatchers.Main.immediate) {
                if (isApk) {
                    Toast.makeText(this@BoxReceiverService, "CarBridge: Đã nhận APK $fileName", Toast.LENGTH_LONG).show()
                    AppUpdater.launchApkInstaller(this@BoxReceiverService, file)
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            this@BoxReceiverService,
                            "${packageName}.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType.ifBlank { "image/*" })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        Toast.makeText(this@BoxReceiverService, "CarBridge: Đã nhận tệp $fileName", Toast.LENGTH_SHORT).show()
                        true
                    }.getOrDefault(false)
                }
            }
        }

        val parsed = ParsedMapLink(
            rawInput = file.absolutePath,
            extractedUrl = null,
            resolvedUrl = file.absolutePath,
            sourceProvider = MapProvider.UNKNOWN,
            kind = LinkKind.FILE,
            title = if (isApk) "APK: $fileName" else "Ảnh/Tệp: $fileName"
        )
        BridgeRepository.recordTransfer(
            direction = "NHẬN TỆP",
            peerName = remoteIp,
            parsed = parsed,
            targetProvider = MapProvider.UNKNOWN,
            intentUri = file.absolutePath,
            transportUsed = "LAN Siêu tốc",
            status = if (isApk) {
                when {
                    silentInstalled -> "Đã cài đặt APK im lặng qua Root"
                    opened -> "Đang mở cài đặt APK"
                    else -> "Đã nhận APK (cần cấp quyền cài nguồn lạ)"
                }
            } else {
                if (opened) "Đã mở tệp trên Box" else "Đã lưu trên Box"
            }
        )
        BridgeRepository.setStatusBanner("Box đã nhận tệp: $fileName")
        noteReceivedItem(fileName)
        return true to if (isApk) "Box đã nhận APK $fileName" else "Box đã nhận $fileName"
    }

    override fun onDestroy() {
        isRunning = false
        runCatching {
            radioWakeReceiver?.let { unregisterReceiver(it) }
        }
        radioWakeReceiver = null
        runCatching {
            wifiNetworkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(it)
            }
        }
        wifiNetworkCallback = null
        lanTransport?.stop()
        bleTransport?.stop()
        lanTransport = null
        bleTransport = null
        BridgeRepository.setBleAdvertising(false)
        BridgeRepository.setBoxServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun acquireBriefWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CarBridge:NavWake"
            )
            wl.acquire(4000L)
        }
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CarBridge Box Receiver",
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun noteReceivedItem(title: String) {
        receivedCount += 1
        lastReceivedSummary = title.take(36)
        updateNotificationStatus()
    }

    private fun updateNotificationStatus() {
        runCatching {
            val channelLabel = if (bleActiveNow) "LAN + BLE" else "LAN"
            val text = if (receivedCount > 0 && !lastReceivedSummary.isNullOrBlank()) {
                "$channelLabel · Đã nhận $receivedCount mục · Gần nhất: $lastReceivedSummary"
            } else {
                "$channelLabel · Đang chờ link bản đồ từ điện thoại..."
            }
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car_map)
            .setContentTitle("Android Box đang lắng nghe")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "carmap_box_receiver"
        private const val NOTIF_ID = 4829
        private const val ACTION_REFRESH_BLE = "com.carmap.bridge.REFRESH_BLE"

        fun start(context: Context) {
            val intent = Intent(context, BoxReceiverService::class.java)
            context.startForegroundService(intent)
        }

        fun refreshBle(context: Context) {
            if (!BridgeRepository.state.value.boxServiceRunning) return
            val intent = Intent(context, BoxReceiverService::class.java).apply {
                action = ACTION_REFRESH_BLE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            BoxWatchdogJobService.cancel(context)
            context.stopService(Intent(context, BoxReceiverService::class.java))
        }
    }
}
