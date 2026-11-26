package com.carmap.bridge.data

import android.content.Context
import android.content.SharedPreferences
import com.carmap.bridge.model.DeviceRole
import com.carmap.bridge.model.DiscoveredBox
import com.carmap.bridge.model.MapProvider
import com.carmap.bridge.model.ThemeMode
import com.carmap.bridge.model.TransportChannel
import com.carmap.bridge.network.BoxNetworkConstants

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
    private const val KEY_SIMULATE_PREVIEW = "simulate_preview"
    private const val KEY_BOX_TARGET = "box_preferred_target"
    private const val KEY_PHONE_TARGET = "phone_target_override"

    private const val KEY_PAIRED_ID = "paired_id"
    private const val KEY_PAIRED_NAME = "paired_name"
    private const val KEY_PAIRED_HOST = "paired_host"
    private const val KEY_PAIRED_PORT = "paired_port"
    private const val KEY_PAIRED_CHANNEL = "paired_channel"
    private const val KEY_PAIRED_BLE = "paired_ble_addr"

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

        return BridgeUiState(
            themeMode = theme,
            role = role,
            roleConfigured = p.getBoolean(KEY_ROLE_CONFIGURED, false),
            boxName = p.getString(KEY_BOX_NAME, "Android Box Mazda CX-5") ?: "Android Box Mazda CX-5",
            pairingPin = p.getString(KEY_PAIRING_PIN, "2689") ?: "2689",
            bleEnabled = p.getBoolean(KEY_BLE_ENABLED, true),
            preferBleOverLan = p.getBoolean(KEY_PREFER_BLE, false),
            autoOpenOnBox = p.getBoolean(KEY_AUTO_OPEN, true),
            simulatePreviewOnEmulator = p.getBoolean(KEY_SIMULATE_PREVIEW, true),
            boxPreferredTarget = boxTarget,
            phoneTargetOverride = phoneTarget,
            pairedBox = pairedBox
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
            apply()
        }
    }
}
