package com.yuhuo.shengqiao.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.Executors

/** Stays alive while Settings displays the short-lived wireless pairing dialog. */
class AdbPairingService : Service() {
    private var discovery: AdbMdns? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var pairing = false

    override fun onCreate() {
        super.onCreate()
        handler.postDelayed({ stopSelf() }, 5 * 60 * 1000L)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        } else if (intent?.action == REPLY) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT)?.toString().orEmpty()
            val host = intent.getStringExtra(HOST).orEmpty()
            val port = intent.getIntExtra(PORT, -1)
            performPairing(host, port, code)
        } else {
            startForeground(ID, notification("请打开无线调试，并选择“使用配对码配对设备”"))
            if (discovery == null) {
                discovery = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
                    val address = host?.hostAddress
                    if (address != null && port > 0) {
                        getSystemService(NotificationManager::class.java).notify(ID,
                            notification("在这里输入系统显示的六位配对码", address, port))
                    } else if (port <= 0) {
                        getSystemService(NotificationManager::class.java).notify(ID,
                            notification("配对窗口已关闭，请重新打开"))
                    }
                }.also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun performPairing(host: String, port: Int, code: String) {
        if (pairing) return
        pairing = true
        startForeground(ID, notification("正在配对，请保持系统配对窗口打开"))
        executor.execute {
            val error = runCatching {
                check(EmbeddedAdb.create(this).pairLocal(host, port, code)) { "配对未成功" }
            }.exceptionOrNull()
            handler.post {
                pairing = false
                if (error == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).notify(ID, notification("配对成功，返回声桥连接读取服务"))
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(ID, notification("配对失败，请重新打开系统配对窗口"))
                }
            }
        }
    }

    private fun notification(message: String, host: String? = null, port: Int = -1): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("声桥无线调试配对")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setAutoCancel(false)
        if (host != null && port > 0) {
            val reply = Intent(this, AdbPairingService::class.java).setAction(REPLY)
                .putExtra(HOST, host).putExtra(PORT, port)
            val pending = PendingIntent.getService(this, 1, reply,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(INPUT).setLabel("输入六位配对码").build()
            builder.addAction(Notification.Action.Builder(null, "输入配对码", pending).addRemoteInput(input).build())
        }
        val stop = PendingIntent.getService(this, 2, Intent(this, AdbPairingService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(Notification.Action.Builder(null, "结束配对", stop).build())
        return builder.build()
    }

    override fun onDestroy() {
        discovery?.stop()
        discovery = null
        executor.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "embedded_adb_pairing"
        private const val ID = 9470
        private const val REPLY = "com.yuhuo.shengqiao.PAIR_REPLY"
        private const val STOP = "com.yuhuo.shengqiao.PAIR_STOP"
        private const val INPUT = "pairing_code"
        private const val HOST = "host"
        private const val PORT = "port"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AdbPairingService::class.java))
        }
    }
}
