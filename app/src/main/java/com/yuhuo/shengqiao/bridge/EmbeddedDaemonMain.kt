package com.yuhuo.shengqiao.bridge

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.yuhuo.shengqiao.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Long-lived shell process; Android destroys it on reboot. No periodic polling is required. */
object EmbeddedDaemonMain {
    private const val TAG = "ShengQiaoDaemon"

    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 2000) { "读取服务必须以 shell 身份运行" }
            val packageName = args[0]
            require(packageName.matches(Regex("[a-zA-Z0-9_.]+")))
            val targetUid = args[1].toInt()
            val apkPath = args[2]
            val lockFile = File("/data/local/tmp/shengqiao-$targetUid.lock")
            val lockHandle = RandomAccessFile(lockFile, "rw")
            val lock = lockHandle.channel.tryLock() ?: return
            try {
                Looper.prepareMainLooper()
                val handler = Handler(Looper.getMainLooper())
                val service = RecorderBridgeService(targetUid)
                val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
                val authority = "$packageName.recorderbridge"
                val pending = AtomicBoolean(false)
                val client = AtomicReference<IBinder?>()

                fun attach(attempt: Int = 0) {
                    if (client.get()?.isBinderAlive == true) return
                    if (!File(apkPath).isFile) {
                        Log.i(TAG, "Application package changed; service is stopping")
                        kotlin.system.exitProcess(0)
                        return
                    }
                    var acquired = false
                    try {
                        val get = manager.javaClass.methods.first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
                        val holder = get.invoke(manager, authority, 0, null, authority) ?: error("读取服务入口不可用")
                        acquired = true
                        val provider = holder.javaClass.getField("provider").get(holder)
                        val call = provider.javaClass.methods.first {
                            it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                        }
                        val attribution = AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
                        val extras = Bundle().apply { putBinder("bridge", service); putInt("version", BuildConfig.VERSION_CODE) }
                        val result = call.invoke(provider, attribution, authority, "attach", null, extras) as? Bundle
                        val token = requireNotNull(result?.getBinder("client"))
                        token.linkToDeath({
                            if (client.compareAndSet(token, null)) handler.postDelayed({
                                if (!File(apkPath).isFile) {
                                    Log.i(TAG, "Application package changed; service is stopping")
                                    kotlin.system.exitProcess(0)
                                }
                                // An app may restart before its UID transition arrives. Reattach
                                // only if it is already running; never revive a closed app here.
                                val running = runCatching {
                                    val process = ProcessBuilder("/system/bin/pidof", packageName).start()
                                    try {
                                        process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
                                    } finally {
                                        process.destroy()
                                    }
                                }.getOrDefault(false)
                                if (running) attach()
                            }, 1500)
                        }, 0)
                        client.set(token)
                        Log.i(TAG, "Attached to application")
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to attach: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
                        if (attempt < 4) handler.postDelayed({ attach(attempt + 1) }, 1000L)
                    } finally {
                        if (acquired) runCatching {
                            manager.javaClass.methods.first { it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2 }
                                .invoke(manager, authority, null)
                        }
                    }
                }

                val uidObserver = object : Binder() {
                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        // Obtain transaction IDs dynamically: they vary across Android versions.
                        if (code in observerTransactions.values) {
                            data.enforceInterface("android.app.IUidObserver")
                            val uid = data.readInt()
                            val active = code != observerTransactions["TRANSACTION_onUidStateChanged"] || data.readInt() < 20
                            if (active && uid == targetUid && client.get()?.isBinderAlive != true && pending.compareAndSet(false, true)) {
                                handler.post {
                                    pending.set(false)
                                    attach()
                                }
                            }
                            return true
                        }
                        return super.onTransact(code, data, reply, flags)
                    }
                }
                val stub = Class.forName("android.app.IUidObserver\$Stub")
                val observer = stub.getMethod("asInterface", IBinder::class.java).invoke(null, uidObserver)
                manager.javaClass.methods.first { it.name == "registerUidObserver" && it.parameterTypes.size == 4 }
                    .invoke(manager, observer, 1 or 8, -1, "com.android.shell")
                attach()
                Looper.loop()
                runCatching { manager.javaClass.methods.first { it.name == "unregisterUidObserver" }.invoke(manager, observer) }
            } finally {
                lock.release()
                lockHandle.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to start", e)
        }
    }

    private val observerTransactions: Map<String, Int> by lazy {
        Class.forName("android.app.IUidObserver\$Stub").declaredFields
            .filter { it.name == "TRANSACTION_onUidActive" || it.name == "TRANSACTION_onUidStateChanged" }
            .associate { it.isAccessible = true; it.name to it.getInt(null) }
    }
}
