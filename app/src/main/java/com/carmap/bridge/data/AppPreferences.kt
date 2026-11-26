package com.carmap.bridge.data

import android.content.Context
import android.content.SharedPreferences
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.LinkKind
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ParsedMapLink
import com.carmap.bridge.model.SavedPlace
import com.carmap.bridge.model.SharedTransferRecord
import com.carmap.bridge.model.ThemeMode
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.network.BoxNetworkConstants
import org.json.JSONArray
import org.json.JSONObject

data class PersistedOutboxItem(
    val rawText: String,
    val resolvedUrl: String,
    val title: String,
    val preferredTarget: MapProvider,
    val queuedAtMs: Long
)

object AppPreferences {
    private const val PREF_NAME = "carmap_bridge_prefs"

    private const val KEY_DEVICE_INSTANCE_ID = "device_instance_id"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ROLE = "device_role"
    private const val KEY_ROLE_CONFIGURED = "device_role_configured"
    private const val KEY_BOX_NAME = "box_name"
    private const val KEY_PAIRING_PIN = "pairing_pin"
    private const val KEY_BLE_ENABLED = "ble_enabled"
    private const val KEY_PREFER_BLE = "prefer_ble"
    private const val KEY_AUTO_OPEN = "auto_open"
    private const val KEY_SIMULATE_PREVIEW = "simulate_preview_v2"
    private const val KEY_BOX_TARGET = "box_preferred_target"
    private const val KEY_PHONE_TARGET = "phone_target_override"

    private const val KEY_PAIRED_ID = "paired_id"
    private const val KEY_PAIRED_NAME = "paired_name"
    private const val KEY_PAIRED_HOST = "paired_host"
    private const val KEY_PAIRED_PORT = "paired_port"
    private const val KEY_PAIRED_CHANNEL = "paired_channel"
    private const val KEY_PAIRED_BLE = "paired_ble_addr"

    private const val KEY_HISTORY_JSON = "transfer_history_json"
    private const val KEY_SAVED_PLACES_JSON = "saved_places_json"
    private const val KEY_CLIPBOARD_HISTORY_JSON = "clipboard_history_json"
    private const val KEY_PENDING_OUTBOX_JSON = "pending_outbox_json"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getOrCreateInstanceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_DEVICE_INSTANCE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "cb-" + java.util.UUID.randomUUID().toString().take(8)
        p.edit().putString(KEY_DEVICE_INSTANCE_ID, created).apply()
        return created
    }

    fun loadIntoState(context: Context): BridgeUiState {
        val p = prefs(context)
        val theme = runCatching {
            ThemeMode.valueOf(p.getString(KEY_THEME_MODE, ThemeMode.DARK.name)!!)
        }.getOrDefault(ThemeMode.DARK)

        val role = runCatching {
            DeviceRole.valueOf(p.getString(KEY_ROLE, DeviceRole.PHONE_SENDER.name)!!)
        }.getOrDefault(DeviceRole.PHONE_SENDER)

        val boxTarget = runCatching {
            MapProvider.valueOf(p.getString(KEY_BOX_TARGET, MapProvider.AUTO.name)!!)
        }.getOrDefault(MapProvider.AUTO)

        val phoneTarget = runCatching {
            MapProvider.valueOf(p.getString(KEY_PHONE_TARGET, MapProvider.AUTO.name)!!)
        }.getOrDefault(MapProvider.AUTO)

        val pairedHost = p.getString(KEY_PAIRED_HOST, null)
        val pairedBox = if (!pairedHost.isNullOrBlank()) {
            val ch = runCatching {
                TransportChannel.valueOf(p.getString(KEY_PAIRED_CHANNEL, TransportChannel.LAN.name)!!)
            }.getOrDefault(TransportChannel.LAN)
            DiscoveredBox(
                id = p.getString(KEY_PAIRED_ID, "saved-$pairedHost") ?: "saved-$pairedHost",
                name = p.getString(KEY_PAIRED_NAME, "Android Box") ?: "Android Box",
                host = pairedHost,
                port = p.getInt(KEY_PAIRED_PORT, BoxNetworkConstants.TCP_PORT),
                channel = ch,
                bleAddress = p.getString(KEY_PAIRED_BLE, null),
                isLocalDemo = pairedHost == "127.0.0.1"
            )
        } else {
            null
        }

        val history = parseHistoryJson(p.getString(KEY_HISTORY_JSON, null))
        val savedPlaces = parseSavedPlacesJson(p.getString(KEY_SAVED_PLACES_JSON, null))
        val clipboardHistory = parseClipboardHistoryJson(p.getString(KEY_CLIPBOARD_HISTORY_JSON, null))
        val pendingOutbox = loadPendingOutbox(context)

        return BridgeUiState(
            themeMode = theme,
            role = role,
            roleConfigured = p.getBoolean(KEY_ROLE_CONFIGURED, false),
            boxName = p.getString(KEY_BOX_NAME, "Android Box Mazda CX-5") ?: "Android Box Mazda CX-5",
            pairingPin = p.getString(KEY_PAIRING_PIN, "2689") ?: "2689",
            bleEnabled = p.getBoolean(KEY_BLE_ENABLED, true),
            preferBleOverLan = p.getBoolean(KEY_PREFER_BLE, false),
            autoOpenOnBox = p.getBoolean(KEY_AUTO_OPEN, true),
            simulatePreviewOnEmulator = p.getBoolean(KEY_SIMULATE_PREVIEW, false),
            boxPreferredTarget = boxTarget,
            phoneTargetOverride = phoneTarget,
            pairedBox = pairedBox,
            history = history,
            savedPlaces = savedPlaces,
            clipboardHistory = clipboardHistory,
            pendingQueueTitle = pendingOutbox?.title
        )
    }

    fun saveState(context: Context, state: BridgeUiState) {
        prefs(context).edit().apply {
            putString(KEY_THEME_MODE, state.themeMode.name)
            putString(KEY_ROLE, state.role.name)
            putBoolean(KEY_ROLE_CONFIGURED, state.roleConfigured)
            putString(KEY_BOX_NAME, state.boxName)
            putString(KEY_PAIRING_PIN, state.pairingPin)
            putBoolean(KEY_BLE_ENABLED, state.bleEnabled)
            putBoolean(KEY_PREFER_BLE, state.preferBleOverLan)
            putBoolean(KEY_AUTO_OPEN, state.autoOpenOnBox)
            putBoolean(KEY_SIMULATE_PREVIEW, state.simulatePreviewOnEmulator)
            putString(KEY_BOX_TARGET, state.boxPreferredTarget.name)
            putString(KEY_PHONE_TARGET, state.phoneTargetOverride.name)

            val box = state.pairedBox
            if (box != null) {
                putString(KEY_PAIRED_ID, box.id)
                putString(KEY_PAIRED_NAME, box.name)
                putString(KEY_PAIRED_HOST, box.host)
                putInt(KEY_PAIRED_PORT, box.port)
                putString(KEY_PAIRED_CHANNEL, box.channel.name)
                putString(KEY_PAIRED_BLE, box.bleAddress)
            }

            putString(KEY_HISTORY_JSON, serializeHistory(state.history))
            putString(KEY_SAVED_PLACES_JSON, serializeSavedPlaces(state.savedPlaces))
            putString(KEY_CLIPBOARD_HISTORY_JSON, serializeClipboardHistory(state.clipboardHistory))
            apply()
        }
    }

    fun savePendingOutbox(context: Context, item: PersistedOutboxItem?) {
        val editor = prefs(context).edit()
        if (item == null) {
            editor.remove(KEY_PENDING_OUTBOX_JSON)
        } else {
            val json = JSONObject().apply {
                put("rawText", item.rawText)
                put("resolvedUrl", item.resolvedUrl)
                put("title", item.title)
                put("preferredTarget", item.preferredTarget.name)
                put("queuedAtMs", item.queuedAtMs)
            }.toString()
            editor.putString(KEY_PENDING_OUTBOX_JSON, json)
        }
        editor.apply()
    }

    fun loadPendingOutbox(context: Context): PersistedOutboxItem? {
        val raw = prefs(context).getString(KEY_PENDING_OUTBOX_JSON, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            PersistedOutboxItem(
                rawText = o.getString("rawText"),
                resolvedUrl = o.optString("resolvedUrl", ""),
                title = o.optString("title", "Đích đến chờ gửi"),
                preferredTarget = runCatching {
                    MapProvider.valueOf(o.optString("preferredTarget", MapProvider.AUTO.name))
                }.getOrDefault(MapProvider.AUTO),
                queuedAtMs = o.optLong("queuedAtMs", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    private fun serializeHistory(history: List<SharedTransferRecord>): String {
        val arr = JSONArray()
        history.take(40).forEach { rec ->
            arr.put(
                JSONObject().apply {
                    put("id", rec.id)
                    put("ts", rec.timestampMs)
                    put("dir", rec.direction)
                    put("peer", rec.peerName)
                    put("raw", rec.parsed.rawInput)
                    put("url", rec.parsed.resolvedUrl)
                    put("src", rec.parsed.sourceProvider.name)
                    put("kind", rec.parsed.kind.name)
                    put("title", rec.parsed.title)
                    rec.parsed.primaryLat?.let { put("lat", it) }
                    rec.parsed.primaryLng?.let { put("lng", it) }
                    put("target", rec.targetProvider.name)
                    put("intent", rec.intentUri)
                    put("transport", rec.transportUsed)
                    put("status", rec.status)
                }
            )
        }
        return arr.toString()
    }

    private fun parseHistoryJson(raw: String?): List<SharedTransferRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val parsed = ParsedMapLink(
                        rawInput = o.optString("raw", ""),
                        extractedUrl = o.optString("url", "").takeIf { it.isNotBlank() },
                        resolvedUrl = o.optString("url", ""),
                        sourceProvider = runCatching { MapProvider.valueOf(o.optString("src")) }.getOrDefault(MapProvider.UNKNOWN),
                        kind = runCatching { LinkKind.valueOf(o.optString("kind")) }.getOrDefault(LinkKind.UNKNOWN),
                        title = o.optString("title", "Điểm đến"),
                        primaryLat = if (o.has("lat")) o.optDouble("lat") else null,
                        primaryLng = if (o.has("lng")) o.optDouble("lng") else null
                    )
                    add(
                        SharedTransferRecord(
                            id = o.optString("id", "h-$i"),
                            timestampMs = o.optLong("ts", System.currentTimeMillis()),
                            direction = o.optString("dir", ""),
                            peerName = o.optString("peer", ""),
                            parsed = parsed,
                            targetProvider = runCatching { MapProvider.valueOf(o.optString("target")) }.getOrDefault(MapProvider.AUTO),
                            intentUri = o.optString("intent", ""),
                            transportUsed = o.optString("transport", ""),
                            status = o.optString("status", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeSavedPlaces(places: List<SavedPlace>): String {
        val arr = JSONArray()
        places.take(12).forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("label", p.label)
                    put("link", p.rawLink)
                }
            )
        }
        return arr.toString()
    }

    private fun parseSavedPlacesJson(raw: String?): List<SavedPlace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val label = o.optString("label", "").trim()
                    val link = o.optString("link", "").trim()
                    if (label.isNotBlank() && link.isNotBlank()) {
                        add(SavedPlace(id = o.optString("id", "p-$i"), label = label, rawLink = link))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeClipboardHistory(items: List<String>): String {
        val arr = JSONArray()
        items.take(8).forEach { arr.put(it) }
        return arr.toString()
    }

    private fun parseClipboardHistoryJson(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        }.getOrDefault(emptyList())
    }
}
