package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import com.yuhuo.shengqiao.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RecordingAvailabilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Source(val folder: File) : IRecorderBridge.Stub() {
        var busy = false
        var race = false
        var exports = 0
        var growingPath = ""
        var deniedPath = ""
        var metadata = "{\"mRecordFileList\":[],\"sentenceList\":[]}"
        override fun status() = JSONObject().put("recorderBusy", busy).toString()
        override fun exportMetadata(): ParcelFileDescriptor {
            exports++
            if (race) { busy = true; error("Recording started before export") }
            val file = File(folder, "export.json").apply { writeText(metadata) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun fileInfo(path: String) = File(path).let { longArrayOf(if (it.isFile) it.length() else -1, it.lastModified()) }
        override fun openAudio(path: String): ParcelFileDescriptor {
            if (path == deniedPath) error("Temporary read failure")
            val file = File(path)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            if (path == growingPath) file.appendText("growing")
            return descriptor
        }
        override fun destroy() = Unit
    }
    private fun fixture(body: (File, Source) -> Unit) {
        val folder = File(context.cacheDir, "availability-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try { body(folder, Source(folder)) } finally { folder.deleteRecursively() }
    }
    private fun recording(folder: File, id: String, recent: Boolean = false): Recording {
        val file = File(folder, "$id.mp3").apply { writeText("audio $id"); setLastModified(if (recent) System.currentTimeMillis() else 1_000_000) }
        return Recording(id.repeat(40), "录音$id", file.path, file.length(), 1, 1, "", "{}", "", "{}")
    }

    @Test fun recordingInProgressUsesCacheWithoutCallingSystemBackup() = fixture { folder, source ->
        val old = recording(folder, "a").copy(transcript = "上次已有转写")
        source.busy = true
        val loaded = RecorderSnapshot.load(source, listOf(old), folder)
        assertTrue(loaded.cached); assertEquals(0, source.exports)
        assertEquals(old.transcript, loaded.snapshot.recordings.single().transcript)
    }
    @Test fun recordingStartingBetweenStatusAndExportFallsBackToCache() = fixture { folder, source ->
        val old = recording(folder, "a")
        source.race = true
        val loaded = RecorderSnapshot.load(source, listOf(old), folder)
        assertTrue(loaded.cached); assertEquals(1, source.exports)
        assertEquals(old.id, loaded.snapshot.recordings.single().id)
    }
    @Test fun unstableUnreadableAndGrowingFilesDoNotBlockOtherCompletedAudio() = fixture { folder, source ->
        val stable = recording(folder, "a")
        val recent = recording(folder, "b", recent = true)
        val growing = recording(folder, "c")
        val denied = recording(folder, "d")
        source.growingPath = growing.path; source.deniedPath = denied.path
        val rows = RecordingScanner.verify(listOf(recent, growing, denied, stable), emptyList(), source, AtomicBoolean(false)) { _, _ -> }
        assertEquals(4, rows.size)
        assertEquals(3, rows.count { it.skipReason.isNotEmpty() })
        assertEquals(64, rows.single { it.id == stable.id }.contentSha256.length)
        assertTrue(rows.single { it.id == stable.id }.skipReason.isEmpty())
    }
    @Test fun cachedAudioThatChangedIsSkippedUntilFreshSnapshotAndLaterBecomesEligible() = fixture { folder, source ->
        val r = recording(folder, "a")
        File(r.path).appendText("more audio")
        File(r.path).setLastModified(2_000_000)
        val waiting = RecordingScanner.verify(listOf(r), emptyList(), source, AtomicBoolean(false), true) { _, _ -> }.single()
        assertTrue(waiting.skipReason.isNotBlank())
        val complete = RecordingScanner.verify(listOf(waiting.copy(size = File(r.path).length())), listOf(waiting), source,
            AtomicBoolean(false)) { _, _ -> }.single()
        assertTrue(complete.skipReason.isEmpty()); assertEquals(64, complete.contentSha256.length)
    }
    @Test fun idleSourceExportsFreshMetadataInsteadOfKeepingCachedTranscript() = fixture { folder, source ->
        val r = recording(folder, "a")
        source.metadata = JSONObject().put("mRecordFileList", org.json.JSONArray().put(JSONObject()
            .put("sha1", r.id).put("displayName", r.title).put("filePath", r.path).put("size", r.size)))
            .put("sentenceList", org.json.JSONArray().put(JSONObject().put("recordSha1", r.id).put("content", "完整新转写"))).toString()
        val loaded = RecorderSnapshot.load(source, listOf(r), folder)
        assertFalse(loaded.cached); assertEquals(1, source.exports)
        assertTrue(loaded.snapshot.recordings.single().transcript.contains("完整新转写"))
        assertTrue(File(folder, "source-snapshot.json").exists())
    }
}
