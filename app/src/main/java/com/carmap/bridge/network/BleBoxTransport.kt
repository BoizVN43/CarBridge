package com.carmap.bridge.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.carmap.bridge.model.BridgeAction
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.TransportChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object BleProximityHelper {
    const val PROXIMITY_2M_RSSI_THRESHOLD = -68
    const val PROXIMITY_2M_KEEP_RSSI_THRESHOLD = -72

    fun estimateDistanceMeters(rssiDbm: Int, txPowerAt1m: Int = -59): Float {
        if (rssiDbm >= 0) return 0.5f
        val exponent = (txPowerAt1m - rssiDbm) / (10.0 * 2.2)
        val raw = 10.0.pow(exponent).toFloat().coerceIn(0.2f, 15.0f)
        return (raw * 10f).roundToInt() / 10f
    }
}

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("c4a90001-7b5d-412c-ab62-3766174fd73b")
    val CHAR_DATA_UUID: UUID = UUID.fromString("c4a90002-7b5d-412c-ab62-3766174fd73b")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val TAG = "CarBridgeBle"
    const val MAX_SERVICE_DATA_NAME_BYTES = 12
}

data class BleStatus(
    val hardwareSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val permissionsGranted: Boolean,
    val advertisingActive: Boolean,
    val scanningActive: Boolean,
    val note: String
)

object BlePermissionsHelper {
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasAllPermissions(context: Context): Boolean = requiredPermissions().all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasScanPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun hasConnectPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    fun hasAdvertisePermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    fun inspectStatus(
        context: Context,
        advertisingActive: Boolean,
        scanningActive: Boolean
    ): BleStatus {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val enabled = adapter?.isEnabled == true
        val perms = hasAllPermissions(context)
        val note = when {
            !hasFeature || adapter == null -> "Thiết bị / Máy ảo không có phần cứng BLE"
            !perms -> "Chưa cấp quyền Bluetooth LE"
            !enabled -> "Bluetooth đang tắt"
            advertisingActive -> "Đang phát GATT Server BLE"
            scanningActive -> "Đang quét Android Box qua BLE"
            else -> "BLE sẵn sàng"
        }
        return BleStatus(
            hardwareSupported = hasFeature && adapter != null,
            bluetoothEnabled = enabled,
            permissionsGranted = perms,
            advertisingActive = advertisingActive,
            scanningActive = scanningActive,
            note = note
        )
    }
}

class BleBoxServerTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val boxNameProvider: () -> String,
    private val onStatusChanged: (Boolean) -> Unit,
    private val onPacketReceived: suspend (IncomingMapPacket, String) -> Pair<Boolean, String>
) {
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private val rxBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val deviceMtus = ConcurrentHashMap<String, Int>()
    private val notifySignals = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    var isAdvertising: Boolean = false
        private set

    companion object {
        private const val MAX_BLE_BUFFER_BYTES = 32_768
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (!BlePermissionsHelper.hasConnectPermission(context) ||
            !BlePermissionsHelper.hasAdvertisePermission(context)
        ) {
            onStatusChanged(false)
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatusChanged(false)
            return
        }

        // Guard against duplicate start() calls leaking BluetoothGattServer / ADVERTISE_FAILED_ALREADY_STARTED
        if (gattServer != null || advertiseCallback != null) {
            stop()
        }

        runCatching {
            val server = manager.openGattServer(context, gattCallback) ?: return
            gattServer = server

            val charData = BluetoothGattCharacteristic(
                BleConstants.CHAR_DATA_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        BleConstants.CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            dataCharacteristic = charData

            val service = BluetoothGattService(
                BleConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            ).apply {
                addCharacteristic(charData)
            }
            server.addService(service)
        }.onFailure {
            Log.w(BleConstants.TAG, "openGattServer failed: ${it.message}")
        }

        val bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            isAdvertising = false
            onStatusChanged(false)
            return
        }
        advertiser = bleAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        // Legacy BLE ScanResponse max 31 bytes: 18 bytes UUID + header + <=12 bytes name = <=30 bytes
        val shortBoxNameBytes = trimUtf8Bytes(boxNameProvider(), BleConstants.MAX_SERVICE_DATA_NAME_BYTES)

        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), shortBoxNameBytes)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                isAdvertising = true
                onStatusChanged(true)
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(BleConstants.TAG, "BLE advertise failed: $errorCode")
                isAdvertising = false
                onStatusChanged(false)
            }
        }
        advertiseCallback = cb
        runCatching { bleAdvertiser.startAdvertising(settings, data, scanResp, cb) }
    }

    private fun trimUtf8Bytes(text: String, maxBytes: Int): ByteArray {
        val sb = StringBuilder()
        var used = 0
        for (ch in text) {
            val bytesLen = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + bytesLen > maxBytes) break
            sb.append(ch)
            used += bytesLen
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        isAdvertising = false
        onStatusChanged(false)
        if (BlePermissionsHelper.hasAdvertisePermission(context)) {
            runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        }
        if (BlePermissionsHelper.hasConnectPermission(context)) {
            runCatching { gattServer?.close() }
        }
        advertiseCallback = null
        advertiser = null
        gattServer = null
        rxBuffers.clear()
        deviceMtus.clear()
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxBuffers.remove(device.address)
                deviceMtus.remove(device.address)
                notifySignals.remove(device.address)?.cancel()
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifySignals.remove(device.address)?.complete(status)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtus[device.address] = mtu
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid != BleConstants.CHAR_DATA_UUID || value == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            val buf = rxBuffers.getOrPut(device.address) { ByteArrayOutputStream() }
            var overflowed = false
            val line: String? = synchronized(buf) {
                if (buf.size() + value.size > MAX_BLE_BUFFER_BYTES) {
                    buf.reset()
                    overflowed = true
                    null
                } else {
                    buf.write(value)
                    val currentBytes = buf.toByteArray()
                    val newlineIdx = currentBytes.indexOf('\n'.code.toByte())
                    if (newlineIdx < 0) {
                        null
                    } else {
                        val extracted = String(currentBytes, 0, newlineIdx, Charsets.UTF_8)
                        buf.reset()
                        if (newlineIdx + 1 < currentBytes.size) {
                            buf.write(currentBytes, newlineIdx + 1, currentBytes.size - newlineIdx - 1)
                        }
                        extracted
                    }
                }
            }
            if (overflowed) {
                scope.launch(Dispatchers.IO) {
                    val errResp = JSONObject().apply {
                        put("ok", false)
                        put("message", "Dữ liệu vượt giới hạn BLE (32KB)")
                    }.toString() + "\n"
                    sendAckChunks(device, errResp.toByteArray(Charsets.UTF_8))
                }
                return
            }
            if (line == null) return

            scope.launch(Dispatchers.IO) {
                val (ok, message) = runCatching {
                    val json = JSONObject(line)
                    val packet = IncomingMapPacket(
                        pin = json.optString("pin", ""),
                        senderName = json.optString("senderName", "Phone BLE"),
                        rawText = json.optString("rawText", ""),
                        resolvedUrl = json.optString("resolvedUrl", ""),
                        preferredTarget = runCatching {
                            MapProvider.valueOf(json.optString("preferredTarget", MapProvider.AUTO.name))
                        }.getOrDefault(MapProvider.AUTO),
                        action = runCatching {
                            BridgeAction.valueOf(json.optString("action", BridgeAction.MAP.name))
                        }.getOrDefault(BridgeAction.MAP)
                    )
                    onPacketReceived(packet, "BLE:${device.address}")
                }.getOrElse {
                    false to "Gói BLE lỗi: ${it.message}"
                }

                val respJson = JSONObject().apply {
                    put("ok", ok)
                    put("boxName", boxNameProvider())
                    put("message", message)
                }.toString() + "\n"

                sendAckChunks(device, respJson.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendAckChunks(device: BluetoothDevice, bytes: ByteArray) {
        val server = gattServer ?: return
        val charData = dataCharacteristic ?: return
        if (!BlePermissionsHelper.hasConnectPermission(context)) return

        val maxChunk = ((deviceMtus[device.address] ?: 23) - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val end = min(offset + maxChunk, bytes.size)
            val slice = bytes.copyOfRange(offset, end)
            val sentSignal = CompletableDeferred<Int>()
            notifySignals[device.address] = sentSignal
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, charData, false, slice) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                charData.value = slice
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, charData, false)
            }
            if (!ok) {
                notifySignals.remove(device.address)
                break
            }
            withTimeoutOrNull(1500L) { sentSignal.await() }
            offset = end
        }
    }
}

class BlePhoneClient(
    private val context: Context,
    private val onBoxFound: (DiscoveredBox) -> Unit,
    private val onScanStateChanged: (Boolean) -> Unit,
    private val proximityOnly2mProvider: () -> Boolean = { false }
) {
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var currentScanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY
    private val emaRssiMap = ConcurrentHashMap<String, Double>()
    private val nearbySeenAtMap = ConcurrentHashMap<String, Long>()
    private val nearbyDistMap = ConcurrentHashMap<String, Float>()
    var isScanning: Boolean = false
        private set

    fun hasRecentNearbyBleSighting(maxAgeMs: Long = 12_000L): Boolean {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return nearbySeenAtMap.values.any { it >= cutoff }
    }

    fun nearestBleDistanceMeters(maxAgeMs: Long = 12_000L): Float? {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        return nearbySeenAtMap.entries
            .filter { it.value >= cutoff }
            .mapNotNull { nearbyDistMap[it.key] }
            .minOrNull()
    }

    @Synchronized
    fun setBatterySavingScanMode(balanced: Boolean) {
        if (!isScanning) return
        val desired = if (balanced) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        if (desired == currentScanMode) return
        stopScan()
        startScan(balancedMode = balanced)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScan(balancedMode: Boolean = false) {
        val desiredMode = if (balancedMode) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY
        if (isScanning && currentScanMode == desiredMode) return
        if (isScanning) {
            stopScan()
        }
        if (!BlePermissionsHelper.hasScanPermission(context)) {
            onScanStateChanged(false)
            return
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val bleScanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || bleScanner == null) {
            onScanStateChanged(false)
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(desiredMode)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val addr = device.address
                val rawRssi = result.rssi
                val now = System.currentTimeMillis()
                val prevEma = emaRssiMap[addr]
                val smoothedRssi = if (prevEma == null) {
                    rawRssi.toDouble()
                } else {
                    prevEma * 0.65 + rawRssi * 0.35
                }
                emaRssiMap[addr] = smoothedRssi
                val rssiInt = smoothedRssi.roundToInt()
                val distMeters = BleProximityHelper.estimateDistanceMeters(rssiInt)

                val wasNearbyRecently = (nearbySeenAtMap[addr] ?: 0L) >= now - 10_000L
                val isWithin2m = rssiInt >= BleProximityHelper.PROXIMITY_2M_RSSI_THRESHOLD ||
                    (wasNearbyRecently && rssiInt >= BleProximityHelper.PROXIMITY_2M_KEEP_RSSI_THRESHOLD)

                if (isWithin2m) {
                    nearbySeenAtMap[addr] = now
                    nearbyDistMap[addr] = distMeters
                }

                if (proximityOnly2mProvider() && !isWithin2m) {
                    return
                }

                val svcName = result.scanRecord
                    ?.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
                    ?.toString(Charsets.UTF_8)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val devName = svcName
                    ?: result.scanRecord?.deviceName
                    ?: if (BlePermissionsHelper.hasConnectPermission(context)) device.name else null
                onBoxFound(
                    DiscoveredBox(
                        id = "ble-$addr",
                        name = "${devName ?: "Android Box"} (BLE)",
                        host = addr,
                        port = 0,
                        channel = TransportChannel.BLE,
                        bleAddress = addr,
                        rssiDbm = rssiInt,
                        estimatedDistanceMeters = distMeters
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(BleConstants.TAG, "BLE scan failed: $errorCode")
                isScanning = false
                onScanStateChanged(false)
            }
        }

        scanner = bleScanner
        scanCallback = cb
        currentScanMode = desiredMode
        runCatching {
            bleScanner.startScan(filters, settings, cb)
            isScanning = true
            onScanStateChanged(true)
        }.onFailure {
            isScanning = false
            onScanStateChanged(false)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        onScanStateChanged(false)
        if (BlePermissionsHelper.hasScanPermission(context)) {
            runCatching { scanCallback?.let { scanner?.stopScan(it) } }
        }
        scanCallback = null
        scanner = null
    }

    @SuppressLint("MissingPermission")
    suspend fun sendToBox(
        bleAddress: String,
        pin: String,
        senderName: String,
        rawText: String,
        resolvedUrl: String = "",
        preferredTarget: MapProvider,
        action: BridgeAction = BridgeAction.MAP
    ): Result<String> {
        val firstAttempt = sendToBoxOnce(bleAddress, pin, senderName, rawText, resolvedUrl, preferredTarget, action)
        if (firstAttempt.isSuccess) return firstAttempt
        val errMsg = firstAttempt.exceptionOrNull()?.message.orEmpty()
        // GATT error 133 or transient BLE link drop: quick 250ms retry
        if (errMsg.contains("133") || errMsg.contains("Mất kết nối BLE")) {
            delay(250L)
            return sendToBoxOnce(bleAddress, pin, senderName, rawText, resolvedUrl, preferredTarget, action)
        }
        return firstAttempt
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendToBoxOnce(
        bleAddress: String,
        pin: String,
        senderName: String,
        rawText: String,
        resolvedUrl: String,
        preferredTarget: MapProvider,
        action: BridgeAction
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!BlePermissionsHelper.hasConnectPermission(context)) {
                error("Thiếu quyền BLUETOOTH_CONNECT")
            }
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: error("Không có BluetoothManager")
            val adapter = manager.adapter ?: error("Thiết bị không hỗ trợ Bluetooth")
            if (!adapter.isEnabled) error("Bluetooth đang tắt")

            val device = adapter.getRemoteDevice(bleAddress)
            val payload = (JSONObject().apply {
                put("pin", pin)
                put("senderName", senderName)
                put("rawText", rawText)
                if (resolvedUrl.isNotBlank()) put("resolvedUrl", resolvedUrl)
                put("preferredTarget", preferredTarget.name)
                put("action", action.name)
            }.toString() + "\n").toByteArray(Charsets.UTF_8)

            var gattRef: BluetoothGatt? = null
            try {
                withTimeout(10_000L) {
                    val readyDeferred = CompletableDeferred<Pair<BluetoothGatt, BluetoothGattCharacteristic>>()
                    val ackDeferred = CompletableDeferred<String>()
                    val negotiatedMtu = java.util.concurrent.atomic.AtomicInteger(23)
                    val ackBuffer = ByteArrayOutputStream()
                    var currentWriteSignal: CompletableDeferred<Boolean>? = null
                    val servicesStarted = java.util.concurrent.atomic.AtomicBoolean(false)

                    val cb = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                val err = IllegalStateException("Mất kết nối BLE ($status)")
                                if (!readyDeferred.isCompleted) readyDeferred.completeExceptionally(err)
                                if (!ackDeferred.isCompleted) ackDeferred.completeExceptionally(err)
                                currentWriteSignal?.complete(false)
                                return
                            }
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                val requested = gatt.requestMtu(512)
                                if (!requested) {
                                    if (servicesStarted.compareAndSet(false, true)) {
                                        gatt.discoverServices()
                                    }
                                } else {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        delay(1200L)
                                        if (!readyDeferred.isCompleted && servicesStarted.compareAndSet(false, true)) {
                                            runCatching { gatt.discoverServices() }
                                        }
                                    }
                                }
                            }
                        }

                        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                negotiatedMtu.set(mtu)
                            }
                            if (servicesStarted.compareAndSet(false, true)) {
                                gatt.discoverServices()
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                readyDeferred.completeExceptionally(IllegalStateException("Không tìm thấy BLE Service"))
                                return
                            }
                            val charData = gatt.getService(BleConstants.SERVICE_UUID)
                                ?.getCharacteristic(BleConstants.CHAR_DATA_UUID)
                            if (charData == null) {
                                readyDeferred.completeExceptionally(IllegalStateException("Box không có CarBridge BLE Characteristic"))
                                return
                            }
                            gatt.setCharacteristicNotification(charData, true)
                            val cccd = charData.getDescriptor(BleConstants.CCCD_UUID)
                            if (cccd != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(cccd)
                                }
                            } else {
                                readyDeferred.complete(gatt to charData)
                            }
                        }

                        override fun onDescriptorWrite(
                            gatt: BluetoothGatt,
                            descriptor: BluetoothGattDescriptor,
                            status: Int
                        ) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                readyDeferred.completeExceptionally(IllegalStateException("Không bật được Notify BLE ($status)"))
                                return
                            }
                            readyDeferred.complete(gatt to descriptor.characteristic)
                        }

                        override fun onCharacteristicWrite(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int
                        ) {
                            currentWriteSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
                        }

                        @Deprecated("Deprecated in API 33")
                        override fun onCharacteristicChanged(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic
                        ) {
                            @Suppress("DEPRECATION")
                            val chunk = characteristic.value ?: return
                            handleAckChunk(chunk, ackBuffer, ackDeferred)
                        }

                        override fun onCharacteristicChanged(
                            gatt: BluetoothGatt,
                            characteristic: BluetoothGattCharacteristic,
                            value: ByteArray
                        ) {
                            handleAckChunk(value, ackBuffer, ackDeferred)
                        }
                    }

                    val gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
                    gattRef = gatt
                    val (connectedGatt, charData) = readyDeferred.await()

                    val chunkSize = (negotiatedMtu.get() - 3).coerceAtLeast(20)
                    var offset = 0
                    while (offset < payload.size) {
                        val end = min(offset + chunkSize, payload.size)
                        val chunk = payload.copyOfRange(offset, end)
                        val signal = CompletableDeferred<Boolean>()
                        currentWriteSignal = signal

                        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            connectedGatt.writeCharacteristic(
                                charData,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            charData.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            @Suppress("DEPRECATION")
                            charData.value = chunk
                            @Suppress("DEPRECATION")
                            connectedGatt.writeCharacteristic(charData)
                        }

                        if (!initiated) error("Ghi gói BLE thất bại")
                        val writeOk = signal.await()
                        if (!writeOk) error("Box từ chối gói BLE")
                        offset = end
                    }

                    val respStr = ackDeferred.await()
                    val resp = JSONObject(respStr)
                    if (!resp.optBoolean("ok", false)) {
                        error(resp.optString("message", "Box BLE từ chối gói tin"))
                    }
                    resp.optString("message", "Đã gửi qua BLE tới $bleAddress")
                }
            } finally {
                runCatching { gattRef?.disconnect() }
                runCatching { gattRef?.close() }
            }
        }
    }

    private fun handleAckChunk(
        chunk: ByteArray,
        ackBuffer: ByteArrayOutputStream,
        ackDeferred: CompletableDeferred<String>
    ) {
        if (ackDeferred.isCompleted) return
        ackBuffer.write(chunk)
        val all = ackBuffer.toByteArray()
        val nl = all.indexOf('\n'.code.toByte())
        if (nl >= 0) {
            ackDeferred.complete(String(all, 0, nl, Charsets.UTF_8))
        }
    }
}
