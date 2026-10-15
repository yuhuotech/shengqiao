package com.yuhuo.shengqiao.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import com.yuhuo.shengqiao.BuildConfig
import com.yuhuo.shengqiao.MainActivity
import com.yuhuo.shengqiao.R
import com.yuhuo.shengqiao.bridge.BridgeClient
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import com.yuhuo.shengqiao.data.*
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** In-process instrumentation substitution only; this service is not exported. */
internal object SyncDependencies {
    var bridge: IRecorderBridge? = null
    var database: String? = null
    var directory: File? = null
    fun bridge() = if (BuildConfig.DEBUG) bridge ?: BridgeClient.get() else BridgeClient.get()
    fun store(context: Context) = Store(context, if (BuildConfig.DEBUG) database ?: "shengqiao.db" else "shengqiao.db")
    fun directory(context: Context) = if (BuildConfig.DEBUG) directory ?: context.filesDir else context.filesDir
}

/** One scanner and one prioritised uploader. Only the uploader writes remote files. */
class SyncService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadExecutor()
    private val uploader = Executors.newSingleThreadExecutor()
    private val queue = UploadQueue()
    private var scanCancelled = AtomicBoolean(false)
    private lateinit var settings: Settings
    private lateinit var trace: WebDavTrace
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private var destroyed = false
    private var lastNotify = 0L
    private var batchWaiting = false
    private var batchIds: Set<String>? = null
    private var skipped = 0
    private var preparationError = ""
    private var syncStopped = false
    private val outcomes = linkedMapOf<String, Outcome>()
    private val requestedDuringScan = mutableSetOf<String>()
    private val uploadedVersions = mutableMapOf<String, Recording>()
    private val refreshedVersions = mutableMapOf<String, Recording>()
    private data class Outcome(val success: Boolean, val missing: Boolean = false, val message: String = "",
                               val record: Recording? = null, val stale: Boolean = false)

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        settings = Settings(this); trace = WebDavTrace(this, "task-diagnostic.log"); trace.reset()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sync", "同步进度", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutoSync.cancelForManual()
        latestStartId = startId
        startForeground(1, notification())
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shengqiao:sync").apply { acquire(60 * 60 * 1000L) }
        settings.info("operationInProgress", "true")
        when (intent?.action) {
            "stop-scan" -> { scanCancelled.set(true); scanState(Operation.scan.copy(message = "正在停止刷新…")) }
            "stop-sync" -> stopUploads()
            "stop" -> { scanCancelled.set(true); stopUploads() }
            "sync" -> {
                val id = intent.getStringExtra("id")
                if (syncStopped || !Operation.sync.running) {
                    outcomes.clear(); preparationError = ""; skipped = 0; syncStopped = false
                }
                if (id == null) {
                    if (!batchWaiting && batchIds == null) {
                        batchWaiting = true
                        syncState(Operation.sync.copy(running = true, starting = false, message = "批量同步正在刷新清单；单条同步可优先执行"))
                        requestScan()
                    }
                } else {
                    if (Operation.scan.busy) requestedDuringScan.add(id)
                    Operation.results = Operation.results - id
                    queue.single(id)
                    trace.append("[单条] 已接受优先请求；不执行全量扫描")
                    syncState(Operation.sync.copy(running = true, starting = false))
                    pump()
                }
            }
            else -> requestScan()
        }
        publishQueue(); changed(); stopIfIdle()
        return START_NOT_STICKY
    }

    private fun requestScan() {
        if (Operation.scan.running) return
        scanCancelled = AtomicBoolean(false)
        val token = scanCancelled
        scanState(TaskState(running = true, message = "正在准备刷新录音…"))
        trace.append("[刷新] 开始独立扫描")
        scanner.execute {
            var result: List<Recording>? = null
            var error: Exception? = null
            try {
                SyncDependencies.store(this).use { store ->
                    post { scanState(Operation.scan.copy(message = "正在导出本机录音与转写…")) }
                    val bridge = SyncDependencies.bridge()
                    val previous = store.recordings()
                    val loaded = RecorderSnapshot.load(bridge, previous, SyncDependencies.directory(this))
                    val snapshot = loaded.snapshot
                    val verified = RecordingScanner.verify(snapshot.recordings, previous, bridge, token, loaded.cached) { index, total ->
                        post { scanState(Operation.scan.copy(message = "刷新录音 · 核对内容 $index/$total")) }
                    }
                    if (token.get()) throw InterruptedIOException("刷新已停止")
                    check(bridge.asBinder().pingBinder()) { "读取服务已断开，保留上次本地列表" }
                    result = store.replaceSnapshot(Snapshot(verified, snapshot.orphanSentences, snapshot.deleted))
                    settings.info("lastScan", System.currentTimeMillis().toString())
                    settings.info("scanNote", "${result!!.size} 条录音 · ${result!!.count { it.transcript.isNotBlank() }} 条有转写" +
                        (if (verified.any { it.skipReason.isNotEmpty() }) " · ${verified.count { it.skipReason.isNotEmpty() }} 条暂缓" else "") +
                        (if (loaded.cached) " · 使用上次清单，新录音 / 新转写待录制结束后刷新" else "") +
                        (if (snapshot.orphanSentences > 0) " · ${snapshot.orphanSentences} 条转写片段待配对" else ""))
                }
            } catch (e: Exception) { error = e }
            post {
                val cancelled = token.get()
                val state = TaskState(message = if (cancelled) "刷新已停止" else error?.let { "刷新失败：${message(it)}" }
                    ?: "已刷新 · ${settings.getInfo("scanNote")}", failed = error != null && !cancelled)
                scanState(state); complete("scan", state); trace.append("[刷新] ${state.message}")
                if (batchWaiting) {
                    batchWaiting = false
                    if (result != null && !cancelled && error == null) {
                        val eligible = result!!.filter { it.available && it.skipReason.isEmpty() }
                        skipped = result!!.size - eligible.size; batchIds = eligible.map { it.id }.toSet()
                        queue.batch(eligible.map { it.id })
                    } else preparationError = if (cancelled) "刷新已停止，批量同步未开始" else "批量同步未开始：${message(error ?: IllegalStateException("刷新失败"))}"
                }
                // Include content arriving after a manual upload began during this scan.
                val fresh = result.orEmpty().associateBy { it.id }
                requestedDuringScan.forEach { id ->
                    val prior = uploadedVersions[id]
                    if (queue.active?.id == id && fresh[id] != null) refreshedVersions[id] = fresh[id]!!
                    if (prior != null && fresh[id] != null && fresh[id] != prior && queue.active?.id != id && id !in queue.pendingIds)
                        queue.single(id)
                }
                requestedDuringScan.clear()
                if (queue.busy) syncState(Operation.sync.copy(running = true, starting = false))
                publishQueue(); pump(); changed(); stopIfIdle()
            }
        }
    }

    private fun pump() {
        if (queue.active != null) { publishQueue(); return }
        val ticket = queue.take()
        if (ticket == null) { finishUploads(); return }
        syncState(TaskState(running = true, currentId = ticket.id, message = if (ticket.manual) "优先同步 · 正在读取此条录音…" else "批量同步 · 正在读取录音…"))
        publishQueue()
        uploader.execute {
            var result = Outcome(false, message = "同步未完成，请重试")
            var mark = SyncMark()
            var config: DavConfig? = null
            var recording: Recording? = null
            try { SyncDependencies.store(this).use { store ->
                try {
                    if (ticket.cancelled.get()) throw InterruptedIOException()
                    config = settings.load()
                    check(config!!.configured) { "请先配置 WebDAV / NAS" }
                    val r = store.recording(ticket.id) ?: error("此条录音已不在本地清单，请刷新后重试")
                    recording = r
                    check(r.available && r.skipReason.isEmpty()) { r.skipReason.ifEmpty { "源录音暂不可用，请刷新后重试" } }
                    val network = getSystemService(ConnectivityManager::class.java)
                    val capabilities = network.getNetworkCapabilities(network.activeNetwork)
                    check(capabilities != null) { "网络未连接，已保留本地数据" }
                    check(!config!!.wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { "已启用仅 Wi-Fi 同步，请连接 Wi-Fi 后重试" }
                    val bridge = SyncDependencies.bridge()
                    val client = WebDav(config!!, ticket.cancelled)
                    val notes = WebDav(config!!.obsidianConfig, ticket.cancelled)
                    ticket.abort = { client.abort(); notes.abort() }
                    TransferGate.manual {
                        progress(ticket, "连接 WebDAV…", 0, 0)
                        client.prepare(); notes.prepare()
                        mark = store.mark(config!!.destination, r.id)
                        SyncEngine(bridge, client, ticket.cancelled, ObsidianSync(notes, config!!.obsidianDestination)) { step, done, total ->
                            progress(ticket, step, done, total)
                        }.upload(r, mark) { receipt -> mark = receipt; store.saveMark(config!!.destination, r.id, receipt) }
                    }
                    result = Outcome(true, r.transcript.isBlank(), record = r, stale = ticket.manual && store.recording(r.id) != r)
                } catch (e: Exception) {
                    if (!ticket.cancelled.get() && recording != null && config != null) {
                        mark = store.mark(config!!.destination, recording!!.id)
                        store.saveMark(config!!.destination, recording!!.id, mark.copy(error = message(e)))
                    }
                    result = Outcome(false, message = message(e), record = recording)
                }
            } } catch (e: Exception) { result = Outcome(false, message = message(e), record = recording) }
            post {
                ticket.abort = null
                val preempted = ticket.preempted; val cancelled = ticket.cancelled.get()
                queue.finish(ticket)
                val refreshed = refreshedVersions.remove(ticket.id)
                if (!cancelled) {
                    result.record?.let { uploadedVersions[ticket.id] = it }
                    if (result.stale || ticket.manual && refreshed != null && refreshed != result.record) {
                        queue.single(ticket.id); trace.append("[单条] 来源内容已更新，继续补同步最新版本")
                    } else {
                        outcomes[ticket.id] = result
                        val text = if (result.success) "单条同步完成" + if (result.missing) " · 缺少转写，后续可补同步" else " · 音频与转写已核对"
                            else "同步失败：${result.message}"
                        Operation.results = Operation.results + (ticket.id to ItemResult(text, !result.success))
                        trace.append("[${if (ticket.manual) "单条" else "批量"}] ${if (result.success) "此条已完成" else result.message}")
                    }
                } else trace.append(if (preempted) "[调度] 暂停当前批量文件，先执行单条；批量稍后整份重试" else "[同步] 当前上传已停止")
                publishQueue(); changed(); pump(); stopIfIdle()
            }
        }
    }
    private fun progress(ticket: UploadQueue.Ticket, step: String, done: Long, total: Long) = post {
        if (queue.active !== ticket || ticket.cancelled.get()) return@post
        syncState(TaskState(running = true, currentId = ticket.id, bytes = done, byteTotal = total,
            message = "${if (ticket.manual) "单条优先同步" else "批量同步"} · $step"))
    }
    private fun finishUploads() {
        if (batchWaiting || queue.busy || !Operation.sync.busy) return
        val selected = batchIds?.let { ids -> outcomes.filterKeys { it in ids } } ?: outcomes
        val ok = selected.values.count { it.success }; val missing = selected.values.count { it.success && it.missing }
        val failures = selected.values.count { !it.success }
        val state = TaskState(failed = failures > 0 || preparationError.isNotEmpty() && !syncStopped && !preparationError.startsWith("刷新已停止"), message = when {
            syncStopped -> "同步已停止，已完成的文件会保留"
            preparationError.isNotEmpty() -> preparationError
            batchIds != null -> "同步结束 · $ok 条已同步 · $missing 条缺少转写 · $skipped 条跳过 · $failures 条失败"
            failures > 0 -> "单条同步失败：${selected.values.first { !it.success }.message}"
            else -> "单条同步完成 · $ok 条已同步" + if (missing > 0) " · $missing 条缺少转写" else ""
        })
        batchIds = null; syncState(state); complete("sync", state)
    }
    private fun stopUploads() {
        batchWaiting = false; batchIds = null; syncStopped = true
        requestedDuringScan.clear(); refreshedVersions.clear()
        queue.stopAll(); Operation.queuedIds = emptySet(); Operation.manualIds = emptySet()
        syncState(Operation.sync.copy(message = "正在停止同步…")); pump()
    }
    private fun scanState(state: TaskState) { Operation.scan = state; update() }
    private fun syncState(state: TaskState) { Operation.sync = state; update() }
    private fun publishQueue() { Operation.queuedIds = queue.pendingIds; Operation.manualIds = queue.manualIds }
    private fun complete(action: String, state: TaskState) {
        Operation.last = state
        settings.info("lastOperation", state.message); settings.info("lastOperationFailed", state.failed.toString())
        settings.info("lastOperationAction", action); settings.info("lastCompletion", System.currentTimeMillis().toString())
    }
    private fun update() {
        val now = System.currentTimeMillis()
        if (now - lastNotify > 300) { lastNotify = now; getSystemService(NotificationManager::class.java).notify(1, notification()); changed() }
    }
    private fun changed() = sendBroadcast(Intent("com.yuhuo.shengqiao.CHANGED").setPackage(packageName))
    private fun post(block: () -> Unit) { main.post { if (!destroyed) block() } }
    private fun stopIfIdle() {
        if (Operation.scan.busy || Operation.sync.busy || queue.busy || batchWaiting) return
        settings.info("operationInProgress", "false")
        if (stopSelfResult(latestStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        }
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "sync").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("声桥 · 录音任务").setContentText(Operation.message.lineSequence().firstOrNull() ?: "正在准备…")
            .setStyle(Notification.BigTextStyle().bigText(Operation.message)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        listOf("scan" to "停止刷新", "sync" to "停止同步").forEachIndexed { index, (action, label) ->
            if ((if (action == "scan") Operation.scan else Operation.sync).busy) builder.addAction(Notification.Action.Builder(null, label,
                PendingIntent.getService(this, index + 1, Intent(this, SyncService::class.java).setAction("stop-$action"), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        return builder.build()
    }
    private fun message(e: Exception): String = when (e) {
        is javax.net.ssl.SSLException -> "HTTPS 证书验证失败，请检查 NAS 证书与域名"
        is java.net.UnknownHostException -> "无法解析服务器域名，请检查地址与网络"
        is java.net.SocketTimeoutException -> "服务器连接超时，可重新同步继续"
        else -> e.message?.take(200) ?: "操作失败，请重试"
    }
    override fun onTimeout(startId: Int, fgsType: Int) { scanCancelled.set(true); stopUploads(); stopSelf() }
    override fun onDestroy() {
        destroyed = true; scanCancelled.set(true); queue.stopAll(); scanner.shutdownNow(); uploader.shutdownNow()
        Operation.scan = Operation.scan.copy(running = false, starting = false)
        Operation.sync = Operation.sync.copy(running = false, starting = false, currentId = "")
        Operation.queuedIds = emptySet(); Operation.manualIds = emptySet()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
}
