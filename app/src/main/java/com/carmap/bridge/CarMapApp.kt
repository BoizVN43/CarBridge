package com.carmap.bridge

import android.app.Application
import com.carmap.bridge.data.BridgeRepository
import com.carmap.bridge.network.CarBridgeConnectionManager

class CarMapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeRepository.init(this)
        CarBridgeConnectionManager.init(this)
    }
}
