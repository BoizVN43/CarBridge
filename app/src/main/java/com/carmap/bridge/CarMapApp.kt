package com.carmap.bridge

import android.app.Application
import android.os.UserManager
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
        val unlocked = runCatching {
            getSystemService(UserManager::class.java)?.isUserUnlocked != false
        }.getOrDefault(true)
        if (!unlocked) return
        runCatching { BridgeRepository.init(this) }
        appScope.launch {
            runCatching { CarBridgeConnectionManager.init(this@CarMapApp) }
        }
    }
}
