package com.carmap.bridge.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.carmap.bridge.model.BridgeAction
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.TransportChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object BoxNetworkConstants {
    const val TCP_PORT = 48291
    const val UDP_DISCOVERY_PORT = 48292
    const val UDP_PROBE_PORT = 48293
    const val NSD_SERVICE_TYPE = "_carmap._tcp."
    const val TAG = "CarBridgeNet"
}

data class IncomingMapPacket(
    val pin: String,
    val senderName: String,
    val rawText: String,
    val resolvedUrl: String = "",
    val preferredTarget: MapProvider = MapProvider.AUTO,
    val action: BridgeAction = BridgeAction.MAP
)

internal object LanBroadcastHelper {
    fun localIpv4Address(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                        if (fallback == null) fallback = ip
                    }
                }
            }
            fallback
        }.getOrNull()
    }
    fun broadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ifAddr in intf.interfaceAddresses) {
                    val addr = ifAddr.address
                    if (addr is Inet4Address) {
                        ifAddr.broadcast?.let { result.add(it) }
                    }
                }
            }
        }
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        return result.toList()
    }

    fun isLocalDeviceIp(ip: String): Boolean {
        if (ip == "127.0.0.1") return true
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (addr.hostAddress == ip) return true
                }
            }
            false
        }.getOrDefault(false)
    }
}

class BoxServerTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val boxIdProvider: () -> String,
    private val boxNameProvider: () -> String,
    private val onPacketReceived: suspend (IncomingMapPacket, String) -> Pair<Boolean, String>
) {
    private var serverSocket: ServerSocket? = null
    private var udpBeaconSocket: DatagramSocket? = null
    private var udpProbeSocket: DatagramSocket? = null
    private var serverJob: Job? = null
    private var beaconJob: Job? = null
    private var probeJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (serverJob?.isActive == true) return
        scope.launch(Dispatchers.IO) {
            acquireMulticastLock()
            registerNsd()
        }

        // TCP Accept loop with automatic recovery if network interface resets
        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val ss = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(BoxNetworkConstants.TCP_PORT))
                    }
                    serverSocket = ss
                    while (isActive && !ss.isClosed) {
                        val client = ss.accept()
                        launch { handleClient(client) }
                    }
                }.onFailure {
                    if (isActive) {
                        Log.w(BoxNetworkConstants.TAG, "TCP server restarting: ${it.message}")
                    }
                }
                runCatching { serverSocket?.close() }
                serverSocket = null
                if (isActive) delay(1500L)
            }
        }

        // Periodic UDP Beacon across all active subnet broadcasts + 255.255.255.255
        beaconJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val ds = DatagramSocket().apply { broadcast = true }
                    udpBeaconSocket = ds
                    while (isActive && !ds.isClosed) {
                        sendBeacon(ds, null)
                        delay(2000L)
                    }
                }.onFailure {
                    Log.w(BoxNetworkConstants.TAG, "UDP beacon restarting: ${it.message}")
                }
                runCatching { udpBeaconSocket?.close() }
                udpBeaconSocket = null
                if (isActive) delay(1500L)
            }
        }

        // Active Probe Responder: instant <20ms reply when phone sends CARMAP_PROBE
        probeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val ds = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(BoxNetworkConstants.UDP_PROBE_PORT))
                    }
                    udpProbeSocket = ds
                    val buf = ByteArray(512)
                    while (isActive && !ds.isClosed) {
                        val dp = DatagramPacket(buf, buf.size)
                        ds.receive(dp)
                        val msg = String(dp.data, 0, dp.length, Charsets.UTF_8)
                        if (msg.contains("CARMAP_PROBE")) {
                            val senderIp = dp.address
                            if (senderIp != null) {
                                sendBeacon(ds, senderIp)
                            }
                        }
                    }
                }.onFailure {
                    if (isActive) {
                        Log.w(BoxNetworkConstants.TAG, "UDP probe listener restarting: ${it.message}")
                    }
                }
                runCatching { udpProbeSocket?.close() }
                udpProbeSocket = null
                if (isActive) delay(1500L)
            }
        }
    }

    fun sendImmediateBeaconBurst() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val ds = udpBeaconSocket ?: DatagramSocket().apply { broadcast = true }
                sendBeacon(ds, null)
                if (ds !== udpBeaconSocket) ds.close()
            }
            reRegisterNsd()
        }
    }

    private fun sendBeacon(socket: DatagramSocket, unicastTarget: InetAddress?) {
        val payload = JSONObject().apply {
            put("type", "CARMAP_BOX_BEACON")
            put("id", boxIdProvider())
            put("name", boxNameProvider())
            put("port", BoxNetworkConstants.TCP_PORT)
        }.toString().toByteArray(Charsets.UTF_8)

        if (unicastTarget != null) {
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, unicastTarget, BoxNetworkConstants.UDP_DISCOVERY_PORT))
            }
            return
        }

        for (target in LanBroadcastHelper.broadcastAddresses()) {
            runCatching {
                socket.send(DatagramPacket(payload, payload.size, target, BoxNetworkConstants.UDP_DISCOVERY_PORT))
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            val json = JSONObject(line)
            if (json.optString("type") == "PING") {
                val pong = JSONObject().apply {
                    put("ok", true)
                    put("id", boxIdProvider())
                    put("boxName", boxNameProvider())
                }.toString()
                writer.write(pong)
                writer.newLine()
                writer.flush()
                return
            }

            val packet = IncomingMapPacket(
                pin = json.optString("pin", ""),
                senderName = json.optString("senderName", "Điện thoại"),
                rawText = json.optString("rawText", ""),
                resolvedUrl = json.optString("resolvedUrl", ""),
                preferredTarget = runCatching {
                    MapProvider.valueOf(json.optString("preferredTarget", MapProvider.AUTO.name))
                }.getOrDefault(MapProvider.AUTO),
                action = runCatching {
                    BridgeAction.valueOf(json.optString("action", BridgeAction.MAP.name))
                }.getOrDefault(BridgeAction.MAP)
            )
            val remoteIp = s.inetAddress?.hostAddress ?: "LAN"
            val (ok, message) = onPacketReceived(packet, remoteIp)
            val resp = JSONObject().apply {
                put("ok", ok)
                put("boxName", boxNameProvider())
                put("message", message)
            }.toString()
            writer.write(resp)
            writer.newLine()
            writer.flush()
        }
    }

    private fun reRegisterNsd() {
        runCatching { regListener?.let { nsdManager?.unregisterService(it) } }
        regListener = null
        registerNsd()
    }

    private fun registerNsd() {
        runCatching {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = mgr
            val info = NsdServiceInfo().apply {
                serviceName = boxNameProvider()
                serviceType = BoxNetworkConstants.NSD_SERVICE_TYPE
                port = BoxNetworkConstants.TCP_PORT
                setAttribute("id", boxIdProvider())
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            regListener = listener
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        serverJob?.cancel()
        beaconJob?.cancel()
        probeJob?.cancel()
        val ss = serverSocket
        val bs = udpBeaconSocket
        val ps = udpProbeSocket
        val reg = regListener
        val mgr = nsdManager
        val lock = multicastLock
        serverSocket = null
        udpBeaconSocket = null
        udpProbeSocket = null
        regListener = null
        multicastLock = null
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { ss?.close() }
            runCatching { bs?.close() }
            runCatching { ps?.close() }
            runCatching { reg?.let { mgr?.unregisterService(it) } }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = multicastLock ?: wm?.createMulticastLock("CarBridgeBoxMulticast")?.apply {
                setReferenceCounted(false)
            }
            multicastLock = lock
            if (lock != null && !lock.isHeld) lock.acquire()
        }
    }
}

class PhoneDiscoveryAndClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val selfInstanceIdProvider: () -> String = { "" },
    private val onBoxFound: (DiscoveredBox) -> Unit,
    private val onBleScanStateChanged: (Boolean) -> Unit = {}
) {
    private var udpListenJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val nsdQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val nsdResolving = AtomicBoolean(false)
    private val bleClient = BlePhoneClient(
        context = context,
        onBoxFound = onBoxFound,
        onScanStateChanged = onBleScanStateChanged
    )

    fun startDiscovery(enableBle: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            acquireMulticastLock()
            if (enableBle) {
                bleClient.startScan()
            }
            startNsdDiscovery()
            sendActiveProbe()
        }
        if (udpListenJob?.isActive == true) return
        udpListenJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val ds = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(BoxNetworkConstants.UDP_DISCOVERY_PORT))
                    }
                    udpSocket = ds
                    val buf = ByteArray(1024)
                    while (isActive && !ds.isClosed) {
                        val dp = DatagramPacket(buf, buf.size)
                        ds.receive(dp)
                        val msg = String(dp.data, 0, dp.length, Charsets.UTF_8)
                        val json = runCatching { JSONObject(msg) }.getOrNull() ?: continue
                        if (json.optString("type") == "CARMAP_BOX_BEACON") {
                            val host = dp.address?.hostAddress ?: continue
                            val boxId = json.optString("id", host)
                            if (boxId == selfInstanceIdProvider() || LanBroadcastHelper.isLocalDeviceIp(host)) continue
                            onBoxFound(
                                DiscoveredBox(
                                    id = boxId,
                                    name = json.optString("name", "Android Box"),
                                    host = host,
                                    port = json.optInt("port", BoxNetworkConstants.TCP_PORT),
                                    isLocalDemo = host == "127.0.0.1"
                                )
                            )
                        }
                    }
                }.onFailure {
                    if (isActive) {
                        Log.w(BoxNetworkConstants.TAG, "UDP discovery restarting: ${it.message}")
                    }
                }
                runCatching { udpSocket?.close() }
                udpSocket = null
                if (isActive) delay(1500L)
            }
        }
    }

    fun sendActiveProbe() {
        scope.launch(Dispatchers.IO) {
            val probeBytes = """{"type":"CARMAP_PROBE"}""".toByteArray(Charsets.UTF_8)
            runCatching {
                DatagramSocket().use { ds ->
                    ds.broadcast = true
                    for (target in LanBroadcastHelper.broadcastAddresses()) {
                        runCatching {
                            ds.send(DatagramPacket(probeBytes, probeBytes.size, target, BoxNetworkConstants.UDP_PROBE_PORT))
                        }
                    }
                }
            }
        }
    }

    fun restartNsdAndProbe() {
        scope.launch(Dispatchers.IO) {
            runCatching { discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) } }
            discoveryListener = null
            startNsdDiscovery()
            sendActiveProbe()
        }
    }

    private fun startNsdDiscovery() {
        if (discoveryListener != null) return
        runCatching {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = mgr
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.contains("_carmap")) return
                    nsdQueue.add(service)
                    resolveNextNsd(mgr)
                }
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discoveryListener = null
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            discoveryListener = listener
            mgr.discoverServices(BoxNetworkConstants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun resolveNextNsd(mgr: NsdManager) {
        if (!nsdResolving.compareAndSet(false, true)) return
        val next = nsdQueue.poll()
        if (next == null) {
            nsdResolving.set(false)
            return
        }
        @Suppress("DEPRECATION")
        mgr.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                nsdResolving.set(false)
                resolveNextNsd(mgr)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = serviceInfo.host?.hostAddress
                val attrId = serviceInfo.attributes?.get("id")?.toString(Charsets.UTF_8)
                val resolvedId = attrId ?: "nsd-${serviceInfo.serviceName}"
                if (!host.isNullOrBlank() && resolvedId != selfInstanceIdProvider() && !LanBroadcastHelper.isLocalDeviceIp(host)) {
                    onBoxFound(
                        DiscoveredBox(
                            id = resolvedId,
                            name = serviceInfo.serviceName,
                            host = host,
                            port = serviceInfo.port
                        )
                    )
                }
                nsdResolving.set(false)
                resolveNextNsd(mgr)
            }
        })
    }

    fun refreshBleScan(enableBle: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (enableBle) bleClient.startScan() else bleClient.stopScan()
        }
    }

    fun stopDiscovery() {
        udpListenJob?.cancel()
        val us = udpSocket
        val dl = discoveryListener
        val mgr = nsdManager
        val lock = multicastLock
        udpSocket = null
        discoveryListener = null
        multicastLock = null
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { bleClient.stopScan() }
            runCatching { us?.close() }
            runCatching { dl?.let { mgr?.stopServiceDiscovery(it) } }
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    suspend fun pingBox(box: DiscoveredBox): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        if (box.channel == TransportChannel.BLE && !box.bleAddress.isNullOrBlank()) {
            val res = bleClient.sendToBox(
                bleAddress = box.bleAddress,
                pin = "",
                senderName = "Ping",
                rawText = "PING",
                resolvedUrl = "",
                preferredTarget = MapProvider.AUTO,
                action = BridgeAction.PING
            )
            val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            return@withContext res.map { "BLE" to rtt }
        }
        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(box.host, box.port), 1500)
                socket.soTimeout = 2000
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.write("""{"type":"PING"}""")
                writer.newLine()
                writer.flush()
                val respStr = reader.readLine() ?: throw IllegalStateException("Box không phản hồi")
                val resp = JSONObject(respStr)
                if (!resp.optBoolean("ok", false)) throw IllegalStateException("Ping thất bại")
                val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                "LAN" to rtt
            }
        }
    }

    suspend fun sendHybridToBox(
        primaryBox: DiscoveredBox,
        discoveredBoxes: List<DiscoveredBox>,
        preferBle: Boolean,
        pin: String,
        senderName: String,
        rawText: String,
        resolvedUrl: String = "",
        preferredTarget: MapProvider,
        action: BridgeAction = BridgeAction.MAP,
        onWorkingBoxResolved: (DiscoveredBox) -> Unit = {}
    ): Result<String> {
        // Build ordered candidate list: handle DHCP IP change by matching box.id!
        val matchingLanById = discoveredBoxes.firstOrNull {
            it.channel == TransportChannel.LAN && it.id == primaryBox.id && it.host != primaryBox.host
        }
        val anyLan = discoveredBoxes.firstOrNull { it.channel == TransportChannel.LAN }
        val bleCandidate = if (primaryBox.channel == TransportChannel.BLE) {
            primaryBox
        } else {
            discoveredBoxes.firstOrNull { it.channel == TransportChannel.BLE && !it.bleAddress.isNullOrBlank() }
        }

        val orderedCandidates = buildList {
            if (preferBle && bleCandidate?.bleAddress != null) add(bleCandidate)
            add(primaryBox)
            if (matchingLanById != null) add(matchingLanById)
            if (anyLan != null && anyLan !in this) add(anyLan)
            if (bleCandidate != null && bleCandidate !in this) add(bleCandidate)
        }.distinctBy { "${it.channel}:${it.host}:${it.port}" }

        var lastError: Throwable? = null
        for (candidate in orderedCandidates) {
            val res = if (candidate.channel == TransportChannel.BLE && !candidate.bleAddress.isNullOrBlank()) {
                bleClient.sendToBox(candidate.bleAddress, pin, senderName, rawText, resolvedUrl, preferredTarget, action)
            } else {
                sendToBoxWithRetry(candidate, pin, senderName, rawText, resolvedUrl, preferredTarget, action)
            }
            if (res.isSuccess) {
                if (candidate.host != primaryBox.host || candidate.channel != primaryBox.channel) {
                    onWorkingBoxResolved(candidate)
                }
                return res
            }
            lastError = res.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("Không thể kết nối tới Box"))
    }

    private suspend fun sendToBoxWithRetry(
        box: DiscoveredBox,
        pin: String,
        senderName: String,
        rawText: String,
        resolvedUrl: String,
        preferredTarget: MapProvider,
        action: BridgeAction
    ): Result<String> {
        val first = sendToBox(box, pin, senderName, rawText, resolvedUrl, preferredTarget, action)
        if (first.isSuccess) return first
        delay(180L)
        return sendToBox(box, pin, senderName, rawText, resolvedUrl, preferredTarget, action)
    }

    suspend fun sendToBox(
        box: DiscoveredBox,
        pin: String,
        senderName: String,
        rawText: String,
        resolvedUrl: String = "",
        preferredTarget: MapProvider,
        action: BridgeAction = BridgeAction.MAP
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(box.host, box.port), 1500)
                socket.soTimeout = 3500
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val payload = JSONObject().apply {
                    put("pin", pin)
                    put("senderName", senderName)
                    put("rawText", rawText)
                    if (resolvedUrl.isNotBlank()) put("resolvedUrl", resolvedUrl)
                    put("preferredTarget", preferredTarget.name)
                    put("action", action.name)
                }.toString()
                writer.write(payload)
                writer.newLine()
                writer.flush()
                val respStr = reader.readLine() ?: throw IllegalStateException("Box không phản hồi")
                val resp = JSONObject(respStr)
                if (!resp.optBoolean("ok", false)) {
                    throw IllegalStateException(resp.optString("message", "Box từ chối gói tin"))
                }
                resp.optString("message", "Đã gửi tới ${box.name}")
            }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = multicastLock ?: wm?.createMulticastLock("CarBridgePhoneMulticast")?.apply {
                setReferenceCounted(false)
            }
            multicastLock = lock
            if (lock != null && !lock.isHeld) lock.acquire()
        }
    }
}
