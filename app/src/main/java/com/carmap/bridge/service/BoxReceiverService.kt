package com.carmap.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.carmap.bridge.MainActivity
import com.carmap.bridge.NavigationDispatchActivity
import com.carmap.bridge.R
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.launcher.MapIntentLauncher
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.network.BleBoxServerTransport
import com.carmap.bridge.network.BoxNetworkConstants
import com.carmap.bridge.network.BoxServerTransport
import com.carmap.bridge.network.IncomingMapPacket
import com.carmap.bridge.parser.MapLinkResolver
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
        val state = BridgeRepository.state.value
        if (state.pairingPin.isNotBlank() && packet.pin != state.pairingPin) {
            return false to "Sai mã PIN ghép nối"
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
