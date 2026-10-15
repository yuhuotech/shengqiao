package com.yuhuo.shengqiao

import android.app.Application
import com.yuhuo.shengqiao.bridge.BridgeClient

class SyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.yuhuo.shengqiao.ui.Appearance.apply(this)
        com.yuhuo.shengqiao.sync.AutoSync.schedule(this)
        BridgeClient.initialize(this)
    }
}
