package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.SyncEngine
import com.yuhuo.shengqiao.sync.WebDav
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit two-process experiment. The driver force-stops the emulator at the checkpoint. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRetryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "process-death-test.db"
    private fun source() = File(context.cacheDir, "process-death-test.mp3")
    private fun checkpoint() = File(context.filesDir, "process-death-checkpoint")
    private fun config() = DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", "process-death-test", true, false)
    private val bridge = object : IRecorderBridge.Stub() {
        override fun status() = "{}"
        override fun exportMetadata(): ParcelFileDescriptor = error("unused")
        override fun openAudio(path: String) = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun fileInfo(path: String) = File(path).let { longArrayOf(it.length(), it.lastModified()) }
        override fun destroy() = Unit
    }
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("killUploadTest") == "true")

    @Test fun prepareAndPauseMidUpload() {
        enabled()
        context.deleteDatabase(dbName); checkpoint().delete()
        val source = source().apply { writeBytes(ByteArray(4 * 1024 * 1024) { (it % 239).toByte() }); setLastModified(1_000_000) }
        val raw = Recording("6".repeat(40), "进程终止恢复验证", source.path, source.length(), 60, 1,
            "完整转写内容".repeat(200), "{\"text\":\"完整转写内容\"}", "", "{}")
        val record = RecordingScanner.verify(listOf(raw), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
        Store(context, dbName).use { db ->
            db.replaceSnapshot(Snapshot(listOf(record), 0, 0))
            val dav = WebDav(config()).apply { prepare() }
            SyncEngine(bridge, dav, AtomicBoolean(false)) { step, done, _ ->
                if (step == "上传音频" && done >= 256 * 1024) {
                    checkpoint().writeText("ready")
                    CountDownLatch(1).await(60, TimeUnit.SECONDS)
                    error("The driver must force-stop the app at this checkpoint")
                }
            }.upload(record, SyncMark()) { db.saveMark(config().destination, record.id, it) }
            fail("Expected process termination during upload")
        }
    }

    @Test fun retryInNewProcessAfterForceStop() {
        enabled()
        assertTrue(checkpoint().exists())
        try {
            Store(context, dbName).use { db ->
                val r = db.recordings().single()
                val prior = db.mark(config().destination, r.id)
                assertEquals(0L, prior.time); assertEquals("", prior.metadata)
                val dav = WebDav(config()).apply { prepare() }
                assertNull(dav.readManifest(r.id))
                var firstBytes = 0L
                val final = SyncEngine(bridge, dav, AtomicBoolean(false)) { step, done, _ ->
                    if (step == "上传音频" && firstBytes == 0L) firstBytes = done
                }.upload(r, prior) { db.saveMark(config().destination, r.id, it) }
                assertEquals(128 * 1024L, firstBytes)
                assertEquals(r.contentSha256, final.audio)
                assertEquals("已同步", db.state(r, final))
                assertTrue(dav.exists("${r.id}/进程终止恢复验证.mp3", source().length()))
                assertTrue(dav.exists("${r.id}/进程终止恢复验证.txt", r.transcript.toByteArray().size.toLong()))
            }
        } finally { context.deleteDatabase(dbName); source().delete(); checkpoint().delete() }
    }
}
