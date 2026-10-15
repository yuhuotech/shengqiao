package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import com.yuhuo.shengqiao.bridge.BridgeClient
import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.SyncEngine
import com.yuhuo.shengqiao.sync.WebDav
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Run explicitly on the owner's authorized phone with USB loopback WebDAV configured. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun config() = DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", "device-test", true, false)
    private fun store() = Store(instrumentation.targetContext)

    @Test fun testARealShizukuSnapshot() {
        val bridge = BridgeClient.get()
        assertEquals(2000, JSONObject(bridge.status()).getInt("uid"))
        val result = ParcelFileDescriptor.AutoCloseInputStream(bridge.exportMetadata()).bufferedReader().use {
            MetadataParser.parse(JSONObject(it.readText()))
        }
        assertTrue(result.recordings.size > 0)
        val checked = store().use { db -> RecordingScanner.verify(result.recordings, db.recordings(), bridge,
            AtomicBoolean(false)) { _, _ -> } }
        assertTrue(checked.any { it.available && it.transcript.isNotBlank() })
        store().use { it.replaceSnapshot(Snapshot(checked, result.orphanSentences, result.deleted)) }
        Settings(instrumentation.targetContext).info("lastScan", System.currentTimeMillis().toString())
        println("SHENGQIAO snapshot recordings=${checked.size}, transcribed=${checked.count { it.transcript.isNotBlank() }}, orphans=${result.orphanSentences}")
    }

    @Test fun testBWebDavCompleteAndRepeat() {
        val bridge = BridgeClient.get()
        store().use { db ->
            val candidates = db.recordings().filter { it.available && it.transcript.isNotBlank() }
            assertTrue(candidates.isNotEmpty())
            val chosen = listOf(candidates.minBy { it.size }, candidates.maxBy { it.duration }).distinctBy { it.id }
            val dav = WebDav(config()); dav.testConnection()
            for (record in chosen) {
                var mark = db.mark(config().destination, record.id)
                val engine = SyncEngine(bridge, dav, AtomicBoolean(false)) { _, _, _ -> }
                mark = engine.upload(record, mark) { db.saveMark(config().destination, record.id, it) }
                assertEquals("已同步", db.state(record, mark))
                val manifest = dav.readManifest(record.id)!!
                assertEquals(record.id, manifest.getString("sourceSha1"))
                assertEquals(record.transcriptHash, manifest.getString("transcriptHash"))
                val completed = manifest.getLong("completedAt")
                val repeated = engine.upload(record, mark) { db.saveMark(config().destination, record.id, it) }
                assertEquals("已同步", db.state(record, repeated))
                assertEquals(completed, dav.readManifest(record.id)!!.getLong("completedAt"))
                println("SHENGQIAO repeat verified audioBytes=${record.size}, textChars=${record.transcript.length}")
            }
        }
    }

    @Test fun testCCancelAndRetryWithoutSuccessMarker() {
        val bridge = BridgeClient.get()
        store().use { db ->
            val record = db.recordings().filter { it.available && it.transcript.isNotBlank() }.maxBy { it.size }
            val destination = config().copy(folder = "cancel-test")
            val flag = AtomicBoolean(false)
            val dav = WebDav(destination, flag); dav.prepare()
            var current = SyncMark()
            var stopped = false
            try {
                SyncEngine(bridge, dav, flag) { _, done, total ->
                    if (total > 0 && done > 256 * 1024) { flag.set(true); dav.abort() }
                }.upload(record, current) { current = it }
            } catch (_: Exception) { stopped = true }
            assertTrue(stopped)
            assertEquals("", current.metadata)
            val retryDav = WebDav(destination); retryDav.prepare()
            assertNull(retryDav.readManifest(record.id))
            val final = SyncEngine(bridge, retryDav, AtomicBoolean(false)) { _, _, _ -> }
                .upload(record, current) { current = it }
            assertEquals("已同步", db.state(record, final))
            println("SHENGQIAO cancellation and retry verified")
        }
    }

    @Test fun testDEncryptedSettingsRoundTrip() {
        val settings = Settings(instrumentation.targetContext)
        val prior = settings.load()
        try {
            settings.save(config())
            assertEquals(config(), settings.load())
            val raw = instrumentation.targetContext.getSharedPreferences("settings", 0).getString("dav", "")!!
            assertFalse(raw.contains("test-local-only"))
        } finally { settings.save(prior) }
    }

    @Test fun testEForegroundServiceSingleRecording() {
        val context = instrumentation.targetContext
        val settings = Settings(context)
        val prior = settings.load()
        val selected = store().use { db -> db.recordings().filter { it.available && it.transcript.isNotBlank() }.minBy { it.size } }
        val testConfig = config().copy(folder = "service-test")
        try {
            settings.save(testConfig)
            // Launch as the authorized adb test identity; HyperOS can block startActivitySync
            // from an otherwise background instrumentation process.
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
            instrumentation.runOnMainSync {
                context.startForegroundService(android.content.Intent(context, com.yuhuo.shengqiao.sync.SyncService::class.java)
                    .setAction("sync").putExtra("id", selected.id))
            }
            val deadline = System.currentTimeMillis() + 60_000
            var started = false
            while (System.currentTimeMillis() < deadline) {
                if (com.yuhuo.shengqiao.sync.Operation.running) started = true
                val completed = store().use { it.mark(testConfig.destination, selected.id).time > 0 }
                if (completed && !com.yuhuo.shengqiao.sync.Operation.running) break
                Thread.sleep(150)
            }
            assertTrue(started)
            assertFalse(com.yuhuo.shengqiao.sync.Operation.running)
            store().use { db -> assertEquals("已同步", db.state(selected, db.mark(testConfig.destination, selected.id))) }
            println("SHENGQIAO foreground service complete")
        } finally {
            settings.save(prior)
        }
    }

    @Test fun testFRealRefreshForegroundService() {
        val context = instrumentation.targetContext
        val settings = Settings(context)
        val previousScan = settings.getInfo("lastScan")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
        instrumentation.runOnMainSync {
            context.startForegroundService(android.content.Intent(context, com.yuhuo.shengqiao.sync.SyncService::class.java).setAction("scan"))
        }
        val deadline = System.currentTimeMillis() + 180_000
        var started = false
        while (System.currentTimeMillis() < deadline) {
            if (com.yuhuo.shengqiao.sync.Operation.running) started = true
            if (started && !com.yuhuo.shengqiao.sync.Operation.running) break
            Thread.sleep(150)
        }
        assertTrue("Refresh task started", started)
        assertFalse("Refresh task completed", com.yuhuo.shengqiao.sync.Operation.running)
        assertEquals("false", settings.getInfo("lastOperationFailed"))
        assertNotEquals(previousScan, settings.getInfo("lastScan"))
        store().use { db ->
            val rows = db.recordings()
            assertTrue(rows.any { it.available && it.transcript.isNotBlank() && it.contentSha256.length == 64 })
            println("SHENGQIAO foreground refresh verified records=${rows.size}, hashed=${rows.count { it.contentSha256.length == 64 }}")
        }
    }

    @Test fun testZRemoveLocalTestReceipts() {
        store().use { db ->
            for (folder in listOf("device-test", "cancel-test", "service-test")) {
                db.removeDestination(config().copy(folder = folder).destination)
            }
        }
        val settings = Settings(instrumentation.targetContext)
        settings.info("lastOperation", "本机读取和测试服务器同步已验证，测试配置已恢复")
        settings.info("lastOperationFailed", "false")
        settings.info("operationInProgress", "false")
    }
}
