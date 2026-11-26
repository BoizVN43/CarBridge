package com.carmap.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.carmap.bridge.MainActivity
import com.carmap.bridge.NavigationDispatchActivity
import com.carmap.bridge.R
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.launcher.MapIntentLauncher
import com.carmap.bridge.model.BridgeAction
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.RemoteCommand
import com.carmap.bridge.network.BleBoxServerTransport
import com.carmap.bridge.network.BoxNetworkConstants
import com.carmap.bridge.network.BoxServerTransport
import com.carmap.bridge.network.IncomingMapPacket
import com.carmap.bridge.parser.MapLinkResolver
import com.carmap.bridge.parser.MediaLinkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BoxReceiverService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lanTransport: BoxServerTransport? = null
    private var bleTransport: BleBoxServerTransport? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Đang chờ link bản đồ từ điện thoại...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val lan = BoxServerTransport(
            context = this,
            scope = serviceScope,
            boxIdProvider = { BridgeRepository.deviceInstanceId },
            boxNameProvider = { BridgeRepository.state.value.boxName },
            onPacketReceived = { packet, remoteIp ->
                val label = if (remoteIp == "127.0.0.1") "LAN Loopback (<10ms)" else "LAN Socket"
                handleIncomingPacket(packet, remoteIp, label)
            }
        )
        lanTransport = lan

        val ble = BleBoxServerTransport(
            context = this,
            scope = serviceScope,
            boxNameProvider = { BridgeRepository.state.value.boxName },
            onStatusChanged = { active -> BridgeRepository.setBleAdvertising(active) },
            onPacketReceived = { packet, remoteBle ->
                handleIncomingPacket(packet, remoteBle, "Bluetooth LE GATT")
            }
        )
        bleTransport = ble

        serviceScope.launch(Dispatchers.IO) {
            lan.start()
            if (BridgeRepository.state.value.bleEnabled) {
                ble.start()
            }
        }

        registerWifiNetworkCallback()
        BridgeRepository.setBoxServiceRunning(true)
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
            return true to "PONG"
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
                return true to "Đã chép vào Clipboard của Box"
            }

            BridgeAction.REMOTE_CONTROL -> {
                val cmd = runCatching { RemoteCommand.valueOf(packet.rawText.trim()) }.getOrNull()
                    ?: return false to "Lệnh điều khiển không hợp lệ"
                withContext(Dispatchers.Main.immediate) {
                    val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@withContext
                    when (cmd) {
                        RemoteCommand.VOL_UP -> audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                        RemoteCommand.VOL_DOWN -> audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                        )
                        RemoteCommand.MUTE -> audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_TOGGLE_MUTE,
                            AudioManager.FLAG_SHOW_UI
                        )
                        RemoteCommand.PLAY_PAUSE -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        RemoteCommand.NEXT -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
                        RemoteCommand.PREVIOUS -> dispatchMediaKey(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                }
                return true to "Box: ${cmd.label}"
            }

            BridgeAction.MEDIA_LINK -> {
                return handleMediaLink(packet, remoteEndpoint, transportUsed)
            }

            else -> {
                // Auto-detect media URLs even if sent under default MAP action
                val media = MediaLinkHelper.extractMediaLink(packet.rawText)
                if (media != null) {
                    return handleMediaLink(packet, remoteEndpoint, transportUsed)
                }
            }
        }

        val parsed = MapLinkResolver.resolveAndParse(packet.rawText, packet.resolvedUrl)
        val targetPref = if (packet.preferredTarget != MapProvider.AUTO) {
            packet.preferredTarget
        } else {
            state.boxPreferredTarget
        }
        val spec = MapIntentLauncher.buildLaunchSpec(this, parsed, targetPref)

        var openedReal = false
        if (state.autoOpenOnBox) {
            acquireBriefWakeLock()
            withContext(Dispatchers.Main.immediate) {
                if (!state.simulatePreviewOnEmulator && spec.isPackageInstalled) {
                    openedReal = MapIntentLauncher.launchRealMapApp(this@BoxReceiverService, spec)
                }
                if (!openedReal) {
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
        return true to "Box đã mở $serviceName"
    }

    private fun dispatchMediaKey(audio: AudioManager, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun onDestroy() {
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
            context.stopService(Intent(context, BoxReceiverService::class.java))
        }
    }
}
