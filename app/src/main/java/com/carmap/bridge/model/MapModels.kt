package com.carmap.bridge.model

enum class ThemeMode(val label: String) {
    SYSTEM("Hệ thống"),
    LIGHT("Sáng"),
    DARK("Tối")
}

enum class DeviceRole(val title: String) {
    PHONE_SENDER("Điện thoại (Gửi)"),
    CAR_BOX_RECEIVER("Android Box (Nhận)")
}

enum class BridgeAction {
    MAP,
    MEDIA_LINK,
    CLIPBOARD,
    REMOTE_CONTROL,
    PING
}

enum class RemoteCommand(val label: String) {
    PLAY_PAUSE("Phát / Tạm dừng"),
    NEXT("Bài tiếp"),
    PREVIOUS("Bài trước"),
    VOL_UP("Tăng âm lượng"),
    VOL_DOWN("Giảm âm lượng"),
    MUTE("Tắt tiếng")
}

enum class MapProvider(
    val displayName: String,
    val packageNames: List<String>
) {
    AUTO("Theo app gốc", emptyList()),
    GOOGLE_MAPS("Google Maps", listOf("com.google.android.apps.maps")),
    WAZE("Waze", listOf("com.waze")),
    VIETMAP("Vietmap", listOf("vn.vietmap.live", "vn.vietmap.s2")),
    UNKNOWN("Bản đồ khác", emptyList())
}

enum class LinkKind(val label: String) {
    PLACE("Địa điểm"),
    COORDINATES("Tọa độ"),
    ROUTE("Lộ trình đã lập"),
    MEDIA("Video / Nhạc"),
    CLIPBOARD("Clipboard"),
    UNKNOWN("Liên kết")
}

data class MapWaypoint(
    val label: String,
    val lat: Double? = null,
    val lng: Double? = null
) {
    fun hasCoords(): Boolean = lat != null && lng != null
    fun coordString(): String = if (lat != null && lng != null) {
        String.format("%.6f, %.6f", lat, lng)
    } else {
        label
    }
}

data class ParsedMapLink(
    val rawInput: String,
    val extractedUrl: String?,
    val resolvedUrl: String,
    val sourceProvider: MapProvider,
    val kind: LinkKind,
    val title: String,
    val origin: MapWaypoint? = null,
    val destination: MapWaypoint? = null,
    val waypoints: List<MapWaypoint> = emptyList(),
    val primaryLat: Double? = null,
    val primaryLng: Double? = null
)

enum class TransportChannel(val label: String) {
    LAN("LAN / Hotspot"),
    BLE("Bluetooth LE")
}

data class DiscoveredBox(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val channel: TransportChannel = TransportChannel.LAN,
    val bleAddress: String? = null,
    val isLocalDemo: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class SavedPlace(
    val id: String,
    val label: String,
    val rawLink: String
)

data class SharedTransferRecord(
    val id: String,
    val timestampMs: Long,
    val direction: String,
    val peerName: String,
    val parsed: ParsedMapLink,
    val targetProvider: MapProvider,
    val intentUri: String,
    val transportUsed: String,
    val status: String
)
