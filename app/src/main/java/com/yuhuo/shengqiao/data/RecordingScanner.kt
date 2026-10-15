package com.yuhuo.shengqiao.data

import android.os.ParcelFileDescriptor
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Hash actual bytes; Xiaomi's sourceSha1 is only a transcript association key. */
object RecordingScanner {
    fun verify(records: List<Recording>, previous: List<Recording>, bridge: IRecorderBridge,
               cancelled: AtomicBoolean, cachedSnapshot: Boolean = false, progress: (Int, Int) -> Unit): List<Recording> {
        val cached = previous.associateBy { it.sourceId }
        return records.mapIndexed { index, r ->
            if (cancelled.get()) throw InterruptedIOException("同步已停止")
            progress(index + 1, records.size)
            val stat = try { bridge.fileInfo(r.path) } catch (e: Exception) {
                check(bridge.asBinder().pingBinder()) { "读取服务已断开，保留上次本地列表" }
                return@mapIndexed r.copy(skipReason = "此文件暂时无法读取，下次刷新重试")
            }
            if (stat[0] <= 0) return@mapIndexed r.copy(available = false, skipReason = "")
            val prior = cached[r.sourceId]
            // Do not cache an in-progress recording or falsely declare its hash stable.
            if (stat[1] >= System.currentTimeMillis() - 5000 || (cachedSnapshot &&
                    ((prior?.size ?: r.size) != stat[0] || (prior != null && prior.modifiedAt > 0 && prior.modifiedAt != stat[1]))))
                return@mapIndexed r.copy(available = true, skipReason = "文件正在录制或变化，已跳过；结束后刷新重试")
            try {
                val digest = if (prior != null && prior.contentSha256.isNotEmpty() &&
                    prior.path == r.path && prior.size == stat[0] && prior.modifiedAt == stat[1]) prior.contentSha256
                else sha256(r, bridge, cancelled)
                if (!stat.contentEquals(bridge.fileInfo(r.path))) r.copy(skipReason = "读取期间文件发生变化，已跳过")
                else r.copy(size = stat[0], contentSha256 = digest, modifiedAt = stat[1], skipReason = "", available = true)
            } catch (e: Exception) {
                if (cancelled.get() || e is InterruptedIOException) throw e
                check(bridge.asBinder().pingBinder()) { "读取服务已断开，保留上次本地列表" }
                r.copy(skipReason = "此文件暂时无法读取，下次刷新重试")
            }
        }
    }

    fun sha256(r: Recording, bridge: IRecorderBridge, cancelled: AtomicBoolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ParcelFileDescriptor.AutoCloseInputStream(bridge.openAudio(r.path)).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (cancelled.get()) throw InterruptedIOException("同步已停止")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
