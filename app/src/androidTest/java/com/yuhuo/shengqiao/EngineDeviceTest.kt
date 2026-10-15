package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic source bytes exercise the same scanner/store/engine without altering Recorder. */
@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val bridge = object : IRecorderBridge.Stub() {
        override fun status() = "{}"
        override fun exportMetadata(): ParcelFileDescriptor = error("unused")
        override fun openAudio(path: String) = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun fileInfo(path: String) = File(path).let { longArrayOf(it.length(), it.lastModified()) }
        override fun destroy() = Unit
    }

    @Test fun restartedEngineOverwritesTruncatedFilesDespiteExistingSuccessReceipts() {
        val source = File(context.cacheDir, "integrity-retry.mp3").apply {
            writeBytes(ByteArray(512_000) { (it % 251).toByte() }); setLastModified(1_000_000)
        }
        val dbName = "integrity-retry.db"
        context.deleteDatabase(dbName)
        try {
            val config = DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", folder = "integrity-retry-test",
                allowHttp = true, obsidianFolder = "/integrity-retry-notes")
            val raw = Recording("7".repeat(40), "中断完整性验证", source.path, source.length(), 60,
                java.time.Instant.parse("2026-10-15T03:00:00Z").toEpochMilli(), "完整转写正文，中文需要按照 UTF-8 字节数校验。".repeat(40),
                "{\"sentences\":[\"完整转写\"]}", "完整会议摘要", "{\"title\":\"中断完整性验证\"}")
            val r = RecordingScanner.verify(listOf(raw), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
            val main = WebDav(config).apply { prepare() }
            val notes = WebDav(config.obsidianConfig).apply { prepare() }
            Store(context, dbName).use { db ->
                db.replaceSnapshot(Snapshot(listOf(r), 0, 0))
                SyncEngine(bridge, main, AtomicBoolean(false), ObsidianSync(notes, config.obsidianDestination)) { _, _, _ -> }
                    .upload(r, SyncMark()) { db.saveMark(config.destination, r.id, it) }
            }
            val names = RemoteNames.files(r)
            val audioPath = "${r.id}/${names.getString("audio")}"
            val textPath = "${r.id}/${names.getString("transcriptText")}"
            val jsonPath = "${r.id}/${names.getString("transcriptJson")}"
            val summaryPath = "${r.id}/${names.getString("summary")}"
            main.putText(audioPath, "partial")
            main.putText(textPath, "半")
            main.putText(jsonPath, "{")
            main.putText(summaryPath, "半")
            main.putText("${r.id}/metadata.json", "{")
            notes.putText(MarkdownExport.path(r), "# 半")
            var audioUploads = 0
            var textUploads = 0
            val restartedMain = WebDav(config).apply { prepare() }
            val restartedNotes = WebDav(config.obsidianConfig).apply { prepare() }
            Store(context, dbName).use { db ->
                val saved = db.recordings().single()
                val engine = SyncEngine(bridge, restartedMain, AtomicBoolean(false), ObsidianSync(restartedNotes, config.obsidianDestination)) { step, _, _ ->
                    if (step == "上传音频") audioUploads++
                    if (step == "上传转写") textUploads++
                }
                val final = engine.upload(saved, db.mark(config.destination, r.id)) { db.saveMark(config.destination, r.id, it) }
                assertTrue(audioUploads > 0); assertEquals(1, textUploads)
                assertEquals(r.contentSha256, final.audio)
                assertEquals("已同步", db.state(r, final, config.obsidianDestination))
                fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size.toLong()
                assertTrue(restartedMain.exists(audioPath, source.length()))
                assertTrue(restartedMain.exists(textPath, bytes(r.transcript)))
                assertTrue(restartedMain.exists(jsonPath, bytes(r.transcriptJson)))
                assertTrue(restartedMain.exists(summaryPath, bytes(r.summary)))
                assertTrue(restartedMain.exists("${r.id}/metadata.json", bytes(r.metadata)))
                assertTrue(restartedNotes.exists(MarkdownExport.path(r), bytes(MarkdownExport.render(r))))
                audioUploads = 0; textUploads = 0
                engine.upload(saved, final) { }
                assertEquals(0, audioUploads); assertEquals(0, textUploads)
            }
        } finally { source.delete(); context.deleteDatabase(dbName) }
    }

    @Test fun audioCanSyncBeforeTranscriptAndLaterOnlyTextAndMarkdownAreAdded() {
        val source = File(context.cacheDir, "pending-transcript.mp3").apply {
            writeText("synthetic completed audio waiting for transcription"); setLastModified(1_000_000)
        }
        try {
            val config = DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", folder = "pending-transcript-test",
                allowHttp = true, obsidianFolder = "/pending-transcript-notes")
            val dav = WebDav(config).apply { prepare() }
            val notes = WebDav(config.obsidianConfig).apply { prepare() }
            val raw = Recording("8".repeat(40), "等待转写的录音", source.path, source.length(), 60,
                java.time.Instant.parse("2026-10-15T03:00:00Z").toEpochMilli(), "", "{}", "", "{}")
            val r = RecordingScanner.verify(listOf(raw), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
            var audioUploads = 0
            val engine = SyncEngine(bridge, dav, AtomicBoolean(false), ObsidianSync(notes, config.obsidianDestination)) { step, _, _ ->
                if (step == "上传音频") audioUploads++
            }
            var mark = engine.upload(r, SyncMark()) { }
            assertTrue(dav.exists("${r.id}/等待转写的录音.mp3"))
            assertFalse(dav.exists("${r.id}/等待转写的录音.txt"))
            assertFalse(notes.exists("202610/等待转写的录音.md"))
            audioUploads = 0
            val partial = r.copy(transcript = "已有的一部分转写", transcriptJson = "{\"text\":\"已有的一部分转写\"}")
            mark = engine.upload(partial, mark) { }
            assertEquals(0, audioUploads)
            assertTrue(dav.exists("${r.id}/等待转写的录音.txt"))
            assertTrue(notes.exists("202610/等待转写的录音.md"))
            val full = partial.copy(transcript = "完整转写已经生成", transcriptJson = "{\"text\":\"完整转写已经生成\"}")
            val final = engine.upload(full, mark) { }
            assertEquals(0, audioUploads)
            assertEquals(full.transcriptHash, final.text)
            assertEquals(MarkdownExport.fingerprint(full, config.obsidianDestination), final.obsidian)
        } finally { source.delete() }
    }

    @Test fun dualDirectorySyncExportsMonthlyMarkdownAndRetriesVaultFailureWithoutAudioUpload() {
        val source = File(context.cacheDir, "dual-sync.mp3").apply {
            writeText("synthetic dual directory audio"); setLastModified(1_000_000)
        }
        val dbName = "dual-sync-test.db"
        context.deleteDatabase(dbName)
        try {
            Store(context, dbName).use { db ->
                val config = DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", allowHttp = true)
                val raw = Recording("9".repeat(40), "10.13客满沟通会", source.path, source.length(), 60,
                    java.time.Instant.parse("2026-10-13T03:00:00Z").toEpochMilli(), "双目录完整转写", "{\"text\":\"双目录完整转写\"}", "会议摘要", "{}")
                val r = RecordingScanner.verify(listOf(raw), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
                db.replaceSnapshot(Snapshot(listOf(r), 0, 0))
                val main = WebDav(config).apply { prepare() }
                val notes = WebDav(config.obsidianConfig).apply { prepare() }
                val exporter = ObsidianSync(notes, config.obsidianDestination)
                var mark = SyncMark()
                val save: (SyncMark) -> Unit = { mark = it; db.saveMark(config.destination, r.id, it) }
                SyncEngine(bridge, main, AtomicBoolean(false), exporter) { _, _, _ -> }.upload(r, mark, save)
                assertTrue(main.exists("${r.id}/10.13客满沟通会.mp3", source.length()))
                assertTrue(main.exists("${r.id}/10.13客满沟通会.txt"))
                assertTrue(notes.exists("202610/10.13客满沟通会.md"))
                assertEquals("已同步", db.state(r, mark, config.obsidianDestination))
                val badNotes = WebDav(config.obsidianConfig.copy(password = "wrong"))
                try {
                    SyncEngine(bridge, main, AtomicBoolean(false), ObsidianSync(badNotes, config.obsidianDestination)) { _, _, _ -> }
                        .upload(r, mark, save)
                    fail("Expected Obsidian auth failure")
                } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("401")) }
                assertEquals("", mark.metadata)
                var audioUploads = 0
                SyncEngine(bridge, main, AtomicBoolean(false), exporter) { step, _, _ -> if (step == "上传音频") audioUploads++ }
                    .upload(r, mark, save)
                assertEquals(0, audioUploads)
                assertEquals("已同步", db.state(r, mark, config.obsidianDestination))
                val other = config.copy(obsidianFolder = "/自定义仓库/录音")
                assertEquals("待更新", db.state(r, mark, other.obsidianDestination))
                val customNotes = WebDav(other.obsidianConfig).apply { prepare() }
                SyncEngine(bridge, main, AtomicBoolean(false), ObsidianSync(customNotes, other.obsidianDestination)) { step, _, _ ->
                    if (step == "上传音频") audioUploads++
                }.upload(r, mark, save)
                assertTrue(customNotes.exists("202610/10.13客满沟通会.md"))
                assertEquals(0, audioUploads)
                assertEquals("已同步", db.state(r, mark, other.obsidianDestination))
            }
        } finally { context.deleteDatabase(dbName); source.delete() }
    }

    @Test fun legacyRemoteLayoutMigratesWithoutReuploadingAudioOrTranscript() {
        val source = File(context.cacheDir, "legacy-test.mp3").apply {
            writeText("synthetic v1 recording"); setLastModified(1_000_000)
        }
        try {
            val raw = Recording("b".repeat(40), "旧版迁移验证", source.path, source.length(), 1, 1,
                "旧版转写", "{\"text\":\"旧版转写\"}", "", "{\"title\":\"旧版迁移验证\"}")
            val r = RecordingScanner.verify(listOf(raw), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
            val dav = WebDav(DavConfig("http://127.0.0.1:18765/", "test", "test-local-only",
                "legacy-engine-test-${java.util.UUID.randomUUID()}", true, false))
            dav.prepare(); dav.recordDirectory(r.id)
            val receipt = dav.putAtomic("${r.id}/audio.mp3", source.inputStream(), source.length(), "audio/mpeg")
            dav.putText("${r.id}/transcript.txt", r.transcript)
            dav.putText("${r.id}/transcript.json", r.transcriptJson)
            dav.putText("${r.id}/metadata.json", r.metadata)
            dav.putText("${r.id}/manifest.json", JSONObject().put("schemaVersion", 1).put("sourceSha1", r.id)
                .put("audio", JSONObject().put("file", "audio.mp3").put("bytes", source.length())
                    .put("sha256", receipt.sha256).put("fileSha1", receipt.fileSha1)
                    .put("sourceSha1", r.id).put("sourceModifiedAt", source.lastModified()))
                .put("title", r.title).put("transcriptHash", r.transcriptHash).put("summaryHash", "")
                .put("metadataHash", r.metadataHash).toString())
            var uploadedPayload = false
            val mark = SyncEngine(bridge, dav, AtomicBoolean(false)) { step, _, _ ->
                if (step == "上传音频" || step == "上传转写") uploadedPayload = true
            }.upload(r, SyncMark(audio = receipt.sha256, metadata = r.metadataHash)) { }
            assertFalse(uploadedPayload)
            assertFalse(dav.exists("${r.id}/audio.mp3")); assertFalse(dav.exists("${r.id}/transcript.txt"))
            assertTrue(dav.exists("${r.id}/旧版迁移验证.mp3", source.length()))
            assertEquals(2, dav.readManifest(r.id)!!.getInt("schemaVersion"))
            assertEquals(r.contentSha256, mark.audio)
        } finally { source.delete() }
    }

    @Test fun fileRenameAndSourceIdChangeKeepOneRemoteAudioAndTranscript() {
        val folder = File(context.cacheDir, "rename-test").apply { mkdirs() }
        val firstFile = File(folder, "old.mp3")
        firstFile.writeBytes(ByteArray(40_000) { (it % 233).toByte() })
        firstFile.setLastModified(System.currentTimeMillis() - 60_000)
        val name = "engine-rename-test.db"
        context.deleteDatabase(name)
        try {
            Store(context, name).use { db ->
                fun r(source: String, title: String, file: File) = Recording(source, title, file.path, file.length(), 1, 1,
                    "测试转写", "{\"text\":\"测试转写\"}", "测试摘要", JSONObject().put("title", title).toString())
                fun resolve(raw: Recording): Recording {
                    val verified = RecordingScanner.verify(listOf(raw), db.recordings(), bridge, AtomicBoolean(false)) { _, _ -> }
                    return db.replaceSnapshot(Snapshot(verified, 0, 0)).single()
                }
                val first = resolve(r("c".repeat(40), "测试旧名", firstFile))
                val dav = WebDav(DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", "synthetic-rename-test", true, false))
                dav.prepare()
                var audioUploads = 0; var transcriptUploads = 0
                val engine = SyncEngine(bridge, dav, AtomicBoolean(false)) { step, _, _ ->
                    if (step == "上传音频") audioUploads++
                    if (step == "上传转写") transcriptUploads++
                }
                var mark = engine.upload(first, SyncMark()) { db.saveMark("nas", first.id, it) }
                val firstRemote = dav.readManifest(first.id)!!
                val newFile = File(folder, "renamed.mp3")
                assertTrue(firstFile.renameTo(newFile))
                // A rename may change mtime on some providers. Hash equality must still skip upload.
                newFile.setLastModified(System.currentTimeMillis() - 30_000)
                val renamed = resolve(r("e".repeat(40), "测试新名 #100%", newFile))
                assertEquals(first.id, renamed.id)
                assertEquals(first.contentSha256, renamed.contentSha256)
                assertEquals(1, db.recordings().size)
                audioUploads = 0; transcriptUploads = 0
                mark = engine.upload(renamed, mark) { db.saveMark("nas", renamed.id, it) }
                assertEquals(0, audioUploads); assertEquals(0, transcriptUploads)
                val result = dav.readManifest(first.id)!!
                assertEquals(first.contentSha256, result.getJSONObject("audio").getString("sha256"))
                assertFalse(dav.exists("${first.id}/${firstRemote.getJSONObject("audio").getString("file")}"))
                assertTrue(dav.exists("${first.id}/测试新名 #100%.mp3", 40_000))
                val completedAt = result.getLong("completedAt")
                engine.upload(renamed, mark) { }
                assertEquals(completedAt, dav.readManifest(first.id)!!.getLong("completedAt"))
                assertEquals(0, audioUploads); assertEquals("已同步", db.state(renamed, mark))
                // Identical title, different bytes must never merge or replace the first recording.
                val other = File(folder, "different.mp3").apply { writeText("different bytes"); setLastModified(1_000_000) }
                val distinct = resolve(r("f".repeat(40), renamed.title, other))
                assertNotEquals(renamed.id, distinct.id)
                engine.upload(distinct, SyncMark()) { }
                assertTrue(dav.exists("${renamed.id}/测试新名 #100%.mp3", 40_000))
                println("SHENGQIAO synthetic real-file rename + changed mtime/sourceID + zero audio/text reuploads + repeated sync verified")
            }
        } finally { context.deleteDatabase(name); folder.deleteRecursively() }
    }
}
