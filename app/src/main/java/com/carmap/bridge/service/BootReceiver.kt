package com.carmap.bridge.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.carmap.bridge.data.AppPreferences
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.network.CarBridgeConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        // Ignore Bluetooth turning OFF
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (btState != BluetoothAdapter.STATE_ON) return
        }

        // 1) Android Box role: auto-start & keep BoxReceiverService alive
        if (AppPreferences.shouldAutoStartOnBoot(context)) {
            runCatching {
                runCatching { BridgeRepository.init(context) }
                if (!BoxReceiverService.isRunning) {
                    Log.i("CarBridgeBoot", "Auto-starting BoxReceiverService on $action")
                    BoxReceiverService.start(context)
                    AppPreferences.recordBootAutoStart(context, shortTriggerName(action))
                } else if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    BoxReceiverService.refreshBle(context)
                }
                BoxWatchdogJobService.schedule(context)
            }.onFailure { err ->
                Log.w("CarBridgeBoot", "Direct FGS start deferred on $action: ${err.message}")
                BoxWatchdogJobService.schedule(context)
                BoxWatchdogJobService.triggerImmediate(context, delayMs = 2_000L)
            }
            return
        }

        // 2) Phone role: zero-touch background auto-connect when entering car (BT ACL / BT ON / Boot)
        if (action in PHONE_AUTO_CONNECT_ACTIONS && AppPreferences.shouldAutoConnectOnPhone(context)) {
            val pending = goAsync()
            val appCtx = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    withTimeoutOrNull(8_500L) {
                        BridgeRepository.init(appCtx)
                        CarBridgeConnectionManager.triggerBackgroundAutoConnect(
                            context = appCtx,
                            trigger = shortTriggerName(action)
                        )
                    }
                } catch (e: Exception) {
                    Log.w("CarBridgeBoot", "Phone auto-connect burst failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun shortTriggerName(action: String): String = when (action) {
        BluetoothDevice.ACTION_ACL_CONNECTED -> "BT_CAR"
        BluetoothAdapter.ACTION_STATE_CHANGED -> "BT_ON"
        Intent.ACTION_LOCKED_BOOT_COMPLETED -> "DIRECT_BOOT"
        Intent.ACTION_BOOT_COMPLETED -> "BOOT"
        Intent.ACTION_USER_UNLOCKED -> "UNLOCK"
        Intent.ACTION_POWER_CONNECTED -> "ACC_POWER"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "OTA_UPDATE"
        ACTION_SERVICE_RESTART -> "RESTART"
        else -> action.substringAfterLast('.')
    }

    companion object {
        const val ACTION_SERVICE_RESTART = "com.carmap.bridge.ACTION_SERVICE_RESTART"

        private val PHONE_AUTO_CONNECT_ACTIONS = setOf(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            ACTION_SERVICE_RESTART,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
