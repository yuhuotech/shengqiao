package com.yuhuo.shengqiao.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.yuhuo.shengqiao.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BridgeClient {
    private lateinit var context: Context
    @Volatile private var bridge: IRecorderBridge? = null
    private var connection: ServiceConnection? = null

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        Shizuku.addBinderDeadListener { bridge = null }
    }
    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @Synchronized fun get(): IRecorderBridge {
        bridge?.let { if (it.asBinder().pingBinder()) return it }
        check(ready()) { "请先启动 Shizuku，并允许声桥使用" }
        val latch = CountDownLatch(1)
        val args = Shizuku.UserServiceArgs(ComponentName(context, RecorderBridgeService::class.java))
            .tag("shengqiao-recorder").processNameSuffix("recorder_bridge")
            .daemon(false).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        val listener = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bridge = IRecorderBridge.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { bridge = null; latch.countDown() }
        }
        connection = listener
        Handler(Looper.getMainLooper()).post {
            runCatching { Shizuku.bindUserService(args, listener) }.onFailure { latch.countDown() }
        }
        check(latch.await(25, TimeUnit.SECONDS)) { "读取服务连接超时，请重新启动 Shizuku 后重试" }
        return bridge ?: error("读取服务未连接，请检查 Shizuku 授权")
    }
}
