package com.carmap.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.model.DeviceRole

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        BridgeRepository.init(context)
        if (BridgeRepository.state.value.role == DeviceRole.CAR_BOX_RECEIVER) {
            runCatching { BoxReceiverService.start(context) }
        }
    }
}
