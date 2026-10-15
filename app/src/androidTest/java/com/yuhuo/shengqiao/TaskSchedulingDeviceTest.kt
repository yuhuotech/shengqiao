package com.yuhuo.shengqiao

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real service intents with synthetic source/remote data; never runs on the owner's phone. */
class TaskSchedulingDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private lateinit var directory: File
    private lateinit var dbName: String
    private lateinit var saved: DavConfig
    private lateinit var savedInfo: Map<String, String>
    private var savedAutomatic = false
    private lateinit var server: MockWebServer
    private lateinit var disk: Disk
    private lateinit var config: DavConfig
    private val exports = AtomicInteger()
    private var gate: CountDownLatch? = null
    private var snapshot = JSONObject()
    private lateinit var bridge: IRecorderBridge
    private val enteredExport = CountDownLatch(1)
    private fun store() = Store(context, dbName)
    private fun waitUntil(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30)
        assertTrue("Task should reach expected state", check())
    }
    private fun start(action: String, id: String? = null) = ins.runOnMainSync {
        context.startForegroundService(Intent(context, SyncService::class.java).setAction(action).apply { id?.let { putExtra("id", it) } })
    }
    private class Disk : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        var blockedId = ""
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val blockOnce = AtomicBoolean()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.toUri().path
            val method = request.method!!
            requests += method to path
            val response = MockResponse()
            return when (method) {
                "PROPFIND" -> response.setResponseCode(207)
                "MKCOL" -> response.setResponseCode(201)
                "HEAD" -> files[path]?.let { response.setHeader("Content-Length", it.size) } ?: response.setResponseCode(404)
                "GET" -> files[path]?.let { response.setBody(Buffer().write(it)) } ?: response.setResponseCode(404)
                "PUT" -> {
                    files[path] = request.body.readByteArray()
                    if (blockedId.isNotEmpty() && "/$blockedId/" in path && blockOnce.compareAndSet(false, true)) {
                        blocked.countDown(); release.await(10, TimeUnit.SECONDS)
                    }
                    response.setResponseCode(201)
                }
                "MOVE" -> {
                    val destination = URI(request.getHeader("Destination")!!).path
                    val bytes = files.remove(path)
                    if (bytes == null) response.setResponseCode(404)
                    else { files[destination] = bytes; response.setResponseCode(201) }
                }
                "DELETE" -> { files.remove(path); response.setResponseCode(204) }
                else -> response.setResponseCode(405)
            }
        }
    }
    @Before fun setup() {
        Assume.assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val settings = Settings(context); saved = settings.load()
        savedAutomatic = AutoSync.enabled(context); AutoSync.setEnabled(context, false)
        savedInfo = listOf("lastScan", "scanNote", "lastOperation", "lastOperationFailed", "lastOperationAction", "lastCompletion", "operationInProgress", "autoLastRun", "autoLastResult")
            .associateWith { settings.getInfo(it) }
        directory = File(context.cacheDir, "task-test-${System.nanoTime()}").apply { mkdirs() }
        dbName = "task-test-${System.nanoTime()}.db"
        server = MockWebServer(); disk = Disk(); server.dispatcher = disk; server.start()
        config = DavConfig(server.url("/").toString(), folder = "/main", allowHttp = true, wifiOnly = false, obsidianFolder = "/notes")
        settings.save(config)
        bridge = object : IRecorderBridge.Stub() {
            override fun status() = "{\"recorderBusy\":false}"
            override fun exportMetadata(): ParcelFileDescriptor {
                exports.incrementAndGet(); enteredExport.countDown(); gate?.await(12, TimeUnit.SECONDS)
                val file = File(directory, "export.json").apply { writeText(snapshot.toString()) }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun openAudio(path: String) = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            override fun fileInfo(path: String) = File(path).let { longArrayOf(it.length(), it.lastModified()) }
            override fun destroy() = Unit
        }
        SyncDependencies.bridge = bridge; SyncDependencies.database = dbName; SyncDependencies.directory = directory
        Operation.scan = TaskState(); Operation.sync = TaskState(); Operation.last = TaskState(); Operation.results = emptyMap()
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
    }
    private fun source(count: Int, transcribed: Boolean): List<Recording> {
        val rows = JSONArray(); val text = JSONArray()
        repeat(count) { i ->
            val id = (i + 1).toString(16).padStart(40, '0')
            val file = File(directory, "source-$i.mp3").apply { writeText("synthetic service source $i"); setLastModified(1_000_000) }
            rows.put(JSONObject().put("sha1", id).put("filePath", file.path).put("displayName", "合成录音$i")
                .put("size", file.length()).put("duration", 60).put("createTime", 1_000_000L + i))
            if (transcribed) text.put(JSONObject().put("recordSha1", id).put("content", "刚生成的完整转写$i").put("segId", 1))
        }
        snapshot = JSONObject().put("mRecordFileList", rows).put("sentenceList", text)
        val parsed = MetadataParser.parse(snapshot)
        return RecordingScanner.verify(parsed.recordings, emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }
    }
    private fun seed(records: List<Recording>) = store().use { it.replaceSnapshot(Snapshot(records, 0, 0)) }
    private fun complete(r: Recording) = store().use { db ->
        val main = WebDav(config).apply { prepare() }; val notes = WebDav(config.obsidianConfig).apply { prepare() }
        SyncEngine(bridge, main, AtomicBoolean(false), ObsidianSync(notes, config.obsidianDestination)) { _, _, _ -> }
            .upload(r, SyncMark()) { db.saveMark(config.destination, r.id, it) }
    }
    @Test fun manualTextSyncCompletesWhileRefreshIsBlockedAndDoesNotUploadAudioAgain() {
        val audio = source(1, false).single(); seed(listOf(audio)); complete(audio)
        val full = source(1, true).single(); seed(listOf(full))
        val puts = disk.requests.count { it.first == "PUT" && "/${full.id}/" in it.second }
        gate = CountDownLatch(1); start("scan"); assertTrue(enteredExport.await(5, TimeUnit.SECONDS))
        start("sync", full.id)
        waitUntil { Operation.results[full.id]?.failed == false }
        assertTrue("Refresh remains blocked but single sync has finished", Operation.scan.running)
        assertEquals(1, exports.get())
        assertTrue(disk.files.keys.any { it.endsWith("合成录音0.txt") })
        assertTrue(disk.files.keys.any { it.startsWith("/notes/") && it.endsWith(".md") })
        val mainPuts = disk.requests.count { it.first == "PUT" && "/${full.id}/" in it.second }
        // Exactly text JSON, TXT and manifest; metadata and audio remain unchanged.
        assertEquals(puts + 3, mainPuts)
        start("stop-scan"); gate!!.countDown(); waitUntil { !Operation.running }
        store().use { assertEquals("已同步", it.state(full, it.mark(config.destination, full.id), config.obsidianDestination)) }
    }
    @Test fun manualPreemptsBlockedBatchThenBatchResumesAndBothFilesAreComplete() {
        val records = source(2, true); seed(records)
        val slow = records.first(); val priority = records.last()
        disk.blockedId = slow.id
        start("sync"); assertTrue(disk.blocked.await(5, TimeUnit.SECONDS))
        start("sync", priority.id)
        waitUntil { Operation.results[priority.id]?.failed == false }
        assertEquals("Priority file completed before blocked batch response was released", 1L, disk.release.count)
        disk.release.countDown()
        waitUntil { !Operation.running }
        store().use { db -> records.forEach { assertEquals("已同步", db.state(it, db.mark(config.destination, it.id), config.obsidianDestination)) } }
        assertFalse(Operation.failed)
        assertEquals(2, disk.files.keys.count { it.endsWith(".mp3") })
        records.forEach { r ->
            assertArrayEquals(File(r.path).readBytes(), disk.files.entries.single { "/${r.id}/" in it.key && it.key.endsWith(".mp3") }.value)
        }
    }
    @Test fun transcriptArrivingAfterManualAudioSyncIsAutomaticallyPickedUpWhenRefreshFinishes() {
        val audio = source(1, false).single(); seed(listOf(audio)); complete(audio)
        gate = CountDownLatch(1); start("scan"); assertTrue(enteredExport.await(5, TimeUnit.SECONDS))
        start("sync", audio.id); waitUntil { Operation.results[audio.id]?.failed == false }
        source(1, true); gate!!.countDown()
        waitUntil { !Operation.running && disk.files.keys.any { it.endsWith(".md") } }
        store().use { db -> val fresh = db.recording(audio.id)!!; assertTrue(fresh.transcript.isNotBlank()); assertEquals("已同步", db.state(fresh, db.mark(config.destination, fresh.id), config.obsidianDestination)) }
        assertEquals(1, exports.get())
    }
    private fun startAutomatic() {
        Settings(context).info("autoLastRun", "")
        AutoSync.setEnabled(context, true)
        val job = context.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(AutoSync.JOB_ID)!!
        assertEquals(60 * 60_000L, job.intervalMillis)
        assertTrue(job.isPersisted); assertTrue(job.isRequireBatteryNotLow)
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
            "cmd jobscheduler run -f com.yuhuo.shengqiao ${AutoSync.JOB_ID}")).use { it.readBytes() }
    }
    private fun awaitAutomatic() = waitUntil { Settings(context).getInfo("autoLastRun").isNotEmpty() && !AutoSync.running }
    @Test fun automaticWithoutChangesDoesNotMakeAnyWebDavRequest() {
        val r = source(1, true).single(); seed(listOf(r)); complete(r); disk.requests.clear()
        startAutomatic(); awaitAutomatic()
        assertTrue(Settings(context).getInfo("autoLastResult"), Settings(context).getInfo("autoLastResult").contains("没有增量"))
        assertEquals(0, disk.requests.size); assertEquals(1, exports.get())
        assertFalse(Operation.running)
    }
    @Test fun automaticPicksUpNewTranscriptWithoutReuploadingAudio() {
        val audio = source(1, false).single(); seed(listOf(audio)); complete(audio); source(1, true)
        disk.requests.clear(); startAutomatic(); awaitAutomatic()
        assertTrue(Settings(context).getInfo("autoLastResult"), Settings(context).getInfo("autoLastResult").contains("1 条已同步"))
        assertEquals(3, disk.requests.count { it.first == "PUT" && "/${audio.id}/" in it.second })
        assertEquals(1, disk.files.keys.count { it.endsWith(".mp3") })
        assertTrue(disk.files.keys.any { it.endsWith(".md") })
    }
    @Test fun foregroundSinglePreemptsAutomaticUploadAndBackgroundJobExits() {
        val records = source(2, true); seed(records)
        disk.blockedId = records.first().id
        startAutomatic(); assertTrue(disk.blocked.await(5, TimeUnit.SECONDS))
        val priority = records.last(); start("sync", priority.id)
        waitUntil { Operation.results[priority.id]?.failed == false }
        assertEquals(1L, disk.release.count)
        awaitAutomatic()
        assertTrue(Settings(context).getInfo("autoLastResult").contains("让位手动"))
        disk.release.countDown(); waitUntil { !Operation.running }
    }
    @After fun cleanup() {
        if (!::directory.isInitialized) return
        gate?.countDown(); disk.release.countDown()
        AutoSync.setEnabled(context, false)
        waitUntil { !AutoSync.running }
        ins.runOnMainSync { context.stopService(Intent(context, SyncService::class.java)) }
        ins.waitForIdleSync()
        Settings(context).save(saved); savedInfo.forEach { (key, value) -> Settings(context).info(key, value) }
        AutoSync.setEnabled(context, savedAutomatic)
        SyncDependencies.bridge = null; SyncDependencies.database = null; SyncDependencies.directory = null
        server.close(); context.deleteDatabase(dbName); directory.deleteRecursively()
    }
}
