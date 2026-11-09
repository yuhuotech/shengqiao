package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.bridge.BridgeClient
import com.yuhuo.shengqiao.data.DavConfig
import com.yuhuo.shengqiao.data.MetadataParser
import com.yuhuo.shengqiao.data.RecordingScanner
import com.yuhuo.shengqiao.data.SyncMark
import com.yuhuo.shengqiao.sync.ObsidianSync
import com.yuhuo.shengqiao.sync.RemoteNames
import com.yuhuo.shengqiao.sync.SyncEngine
import com.yuhuo.shengqiao.sync.WebDav
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in local DAV integration check against a separate shell bridge and temporary server. */
@RunWith(AndroidJUnit4::class)
class EmbeddedBridgeSyncTest {
    @Test fun realRecorderFileTransfersThroughBinderToTemporaryDav() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddedSync") == "true")
        assertTrue("shell bridge must already be running", BridgeClient.awaitExisting(10000))
        val bridge = BridgeClient.get()
        val metadata = ParcelFileDescriptor.AutoCloseInputStream(bridge.exportMetadata())
            .bufferedReader().use { JSONObject(it.readText()) }
        val candidates = MetadataParser.parse(metadata).recordings
            .filter { it.size in 1..(5 * 1024 * 1024L) && it.transcript.isNotBlank() }
            .sortedBy { it.size }
        val source = candidates.first { bridge.fileInfo(it.path)[0] > 0 }
        val record = RecordingScanner.verify(listOf(source), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
        assertTrue(record.contentSha256.isNotBlank())
        val config = DavConfig("http://127.0.0.1:18765/", "test", "test-local-only",
            folder = "embedded-acceptance", allowHttp = true, obsidianFolder = "embedded-notes")
        val dav = WebDav(config).apply { prepare() }
        val notes = WebDav(config.obsidianConfig).apply { prepare() }
        val result = SyncEngine(bridge, dav, AtomicBoolean(false), ObsidianSync(notes, config.obsidianDestination)) { _, _, _ -> }
            .upload(record, SyncMark()) { }
        assertEquals(record.contentSha256, result.audio)
        assertEquals(record.transcriptHash, result.text)
        val files = RemoteNames.files(record)
        assertTrue(dav.exists("${record.id}/${files.getString("audio")}", record.size))
        assertTrue(dav.exists("${record.id}/${files.getString("transcriptJson")}"))
        println("EMBEDDED_SYNC_OK bytes=${record.size} transcript=true")
    }
}
