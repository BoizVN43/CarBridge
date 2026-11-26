package com.carmap.bridge

import android.app.Application
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.network.CarBridgeConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CarMapApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        BridgeRepository.init(this)
        appScope.launch {
            CarBridgeConnectionManager.init(this@CarMapApp)
        }
    }
}
