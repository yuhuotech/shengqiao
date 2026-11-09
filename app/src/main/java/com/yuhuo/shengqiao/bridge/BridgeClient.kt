package com.yuhuo.shengqiao.bridge

import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.io.IOException

object BridgeClient {
    private lateinit var context: Context
    private val connectLock = Any()
    private val stateLock = Object()
    @Volatile private var bridge: IRecorderBridge? = null

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        if (ready()) notifyState()
    }

    internal fun accept(binder: IBinder) {
        val candidate = IRecorderBridge.Stub.asInterface(binder)
        binder.linkToDeath({
            synchronized(stateLock) {
                if (bridge?.asBinder() == binder) bridge = null
                stateLock.notifyAll()
            }
            notifyState()
        }, 0)
        synchronized(stateLock) {
            bridge = candidate
            stateLock.notifyAll()
        }
        notifyState()
    }

    private fun notifyState() {
        if (::context.isInitialized) context.sendBroadcast(
            Intent("com.yuhuo.shengqiao.CHANGED").setPackage(context.packageName)
        )
    }

    fun ready(): Boolean = bridge?.asBinder()?.pingBinder() == true
    fun alive(candidate: IRecorderBridge): Boolean = candidate.asBinder().pingBinder()
    fun awaitExisting(timeout: Long): Boolean = awaitBridge(timeout) != null

    /** Runs on a worker. Starts shell once; the Binder remains available after ADB disconnects. */
    fun connect(): IRecorderBridge = synchronized(connectLock) {
        awaitBridge(4000)?.let { return@synchronized it }
        val adb = EmbeddedAdb.create(context)
        try {
            check(adb.discoverAndConnect(context) && adb.isConnected) {
                "未找到无线调试服务，请确认已开启无线调试并连接 WLAN"
            }
            val stream = adb.openStream("shell:${daemonCommand()} sleep 2; echo SHENGQIAO_START")
            val result = try {
                // Keep the launching shell alive briefly while setsid detaches the child.
                val marker = stream.openInputStream().bufferedReader().readLine()
                check(marker == "SHENGQIAO_START") { "读取服务启动器未正常退出：${marker.orEmpty().take(160)}" }
                awaitBridge(15000) ?: throw IOException("读取服务启动超时，请检查无线调试授权")
            } finally {
                runCatching { stream.close() }
            }
            val status = JSONObject(result.status())
            check(status.optInt("uid") == 2000 && status.optBoolean("backupAvailable")) {
                "读取服务未获得所需权限，或当前系统不支持小米备份"
            }
            result
        } finally {
            runCatching { adb.disconnect() }
        }
    }

    /** For an optional USB start from a paired computer. */
    fun usbCommand(): String = "adb shell ${quote(daemonCommand() + " sleep 2")}"

    private fun daemonCommand(): String {
        val apk = quote(context.applicationInfo.sourceDir)
        val packageName = quote(context.packageName)
        val uid = context.applicationInfo.uid
        return "CLASSPATH=$apk /system/bin/setsid /system/bin/app_process /system/bin " +
            "--nice-name=shengqiao.$uid com.yuhuo.shengqiao.bridge.EmbeddedDaemonMain " +
            "$packageName $uid $apk </dev/null >/dev/null 2>&1 &"
    }

    fun get(): IRecorderBridge = awaitBridge(2000) ?: connect()

    private fun awaitBridge(timeout: Long): IRecorderBridge? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        synchronized(stateLock) {
            while (true) {
                bridge?.let { if (it.asBinder().pingBinder()) return it }
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) return null
                stateLock.wait(remaining)
            }
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
