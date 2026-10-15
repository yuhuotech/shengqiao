package com.yuhuo.shengqiao.sync

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.yuhuo.shengqiao.bridge.BridgeClient
import com.yuhuo.shengqiao.data.*
import org.json.JSONObject
import java.io.InterruptedIOException
import kotlin.concurrent.thread

/** Runs only when JobScheduler grants a window; no resident service, alarm or UI. */
class AutoSyncJobService : JobService() {
    private var run: AutoSync.Run? = null
    private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        if (!AutoSync.enabled(this)) return false
        val task = AutoSync.begin() ?: return false
        run = task; stopped = false
        thread(name = "shengqiao-auto", isDaemon = true) {
            val settings = Settings(this)
            val log = WebDavTrace(this, "auto-sync.log")
            var result = "本轮未执行"
            try {
                log.reset()
                if (Operation.scan.busy || Operation.sync.busy) throw InterruptedIOException("手动任务正在运行，本轮跳过")
                if (!BridgeClient.ready() && SyncDependencies.bridge == null) throw InterruptedIOException("Shizuku 不可用，本轮跳过；服务恢复后下次检查")
                val config = settings.load()
                if (!config.configured) throw InterruptedIOException("尚未配置 WebDAV，本轮跳过")
                val network = getSystemService(ConnectivityManager::class.java)
                val capabilities = network.getNetworkCapabilities(network.activeNetwork)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    throw InterruptedIOException("等待非按流量计费的 Wi-Fi，本轮跳过")
                val bridge = SyncDependencies.bridge()
                if (JSONObject(bridge.status()).optBoolean("recorderBusy")) throw InterruptedIOException("录音机正在录制 / 播放，本轮跳过")
                log.append("导出转写快照，检查增量")
                SyncDependencies.store(this).use { store ->
                    val previous = store.recordings()
                    val loaded = RecorderSnapshot.load(bridge, previous, SyncDependencies.directory(this))
                    val verified = RecordingScanner.verify(loaded.snapshot.recordings, previous, bridge, task.cancelled, loaded.cached) { _, _ -> }
                    if (task.cancelled.get()) throw InterruptedIOException("任务已让位手动操作")
                    val canonical = store.replaceSnapshot(Snapshot(verified, loaded.snapshot.orphanSentences, loaded.snapshot.deleted))
                    settings.info("lastScan", System.currentTimeMillis().toString())
                    val marks = store.marks(config.destination)
                    val pending = canonical.filter { it.available && it.skipReason.isEmpty() && store.state(it, marks[it.id] ?: SyncMark(), config.obsidianDestination) != "已同步" }
                    if (pending.isEmpty()) result = "没有增量，无需上传（未访问 WebDAV）"
                    else {
                        val client = WebDav(config, task.cancelled)
                        val notes = WebDav(config.obsidianConfig, task.cancelled)
                        task.abort = { client.abort(); notes.abort() }
                        var success = 0; var failures = 0
                        for (r in pending) {
                            if (task.cancelled.get()) throw InterruptedIOException("任务已让位手动操作")
                            try {
                                TransferGate.background(task.cancelled) {
                                    if (success == 0 && failures == 0) { client.prepare(); notes.prepare() }
                                    var mark = store.mark(config.destination, r.id)
                                    try {
                                        SyncEngine(bridge, client, task.cancelled, ObsidianSync(notes, config.obsidianDestination)) { _, _, _ -> }
                                            .upload(r, mark) { receipt -> mark = receipt; store.saveMark(config.destination, r.id, receipt) }
                                    } catch (e: Exception) {
                                        if (!task.cancelled.get()) store.saveMark(config.destination, r.id, mark.copy(error = e.message?.take(200).orEmpty()))
                                        throw e
                                    }
                                }
                                success++
                            } catch (e: Exception) { if (task.cancelled.get() || e is InterruptedIOException) throw e; failures++ }
                        }
                        result = "自动同步结束 · $success 条已同步 · $failures 条失败"
                    }
                }
            } catch (e: Exception) {
                result = if (task.manual) "已让位手动操作，下次周期再检查" else if (task.cancelled.get()) "自动检查已停止，下次周期重试"
                    else (e.message ?: "自动检查失败，请查看网络与读取授权").take(200)
            } finally {
                task.abort = null; AutoSync.end(task)
                settings.info("autoLastRun", System.currentTimeMillis().toString()); settings.info("autoLastResult", result)
                log.append(result)
                sendBroadcast(Intent("com.yuhuo.shengqiao.CHANGED").setPackage(packageName))
                Handler(Looper.getMainLooper()).post { if (!stopped) jobFinished(params, false) }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; run?.cancel(); return false }
    override fun onDestroy() { run?.cancel(); super.onDestroy() }
}
