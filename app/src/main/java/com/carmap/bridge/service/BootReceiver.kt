package com.carmap.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.carmap.bridge.data.AppPreferences
import com.carmap.bridge.data.BridgeRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        runCatching {
            if (!AppPreferences.shouldAutoStartOnBoot(context)) return
            runCatching { BridgeRepository.init(context) }
            if (!BridgeRepository.state.value.boxServiceRunning) {
                Log.i("CarBridgeBoot", "Auto-starting BoxReceiverService on $action")
                BoxReceiverService.start(context)
                AppPreferences.recordBootAutoStart(context, action.substringAfterLast('.'))
            }
            BoxWatchdogJobService.schedule(context)
        }.onFailure {
            Log.w("CarBridgeBoot", "Boot auto-start deferred on $action: ${it.message}")
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
