package com.carmap.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.model.DeviceRole

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        runCatching {
            BridgeRepository.init(context)
            val state = BridgeRepository.state.value
            if (state.roleConfigured && state.role == DeviceRole.CAR_BOX_RECEIVER) {
                Log.i("CarBridgeBoot", "Auto-starting BoxReceiverService on $action")
                BoxReceiverService.start(context)
            }
        }.onFailure {
            Log.w("CarBridgeBoot", "Boot auto-start deferred: ${it.message}")
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
