package com.yuhuo.shengqiao

import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.bridge.BridgeClient
import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class IdentityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "a".repeat(40)
    private fun record(source: String = id, title: String = "原名", digest: String = "d".repeat(64)) =
        Recording(source, title, "/test/$title.mp3", 3, 1, 1, "配对转写", "{\"sentences\":[\"配对转写\"]}", "", title,
            sourceId = source, contentSha256 = digest, modifiedAt = 1)
    private fun isolated(name: String, body: (Store) -> Unit) {
        context.deleteDatabase(name)
        try { Store(context, name).use(body) } finally { context.deleteDatabase(name) }
    }

    @Test fun sourceIdAndNameChangePreserveIdentityTranscriptAndAllDestinationReceipts() = isolated("identity-test.db") { db ->
        db.replaceSnapshot(Snapshot(listOf(record()), 0, 0))
        db.saveMark("nas1", id, SyncMark(audio = "d".repeat(64), metadata = "原名", time = 10))
        db.saveMark("nas2", id, SyncMark(audio = "d".repeat(64), metadata = "原名", time = 20))
        val renamed = record("b".repeat(40), "新名").copy(transcript = "", transcriptJson = "{}")
        val result = db.replaceSnapshot(Snapshot(listOf(renamed), 0, 0)).single()
        assertEquals(id, result.id)
        assertEquals(renamed.sourceId, result.sourceId)
        assertEquals("配对转写", result.transcript)
        assertEquals("新名", db.recordings().single().title)
        assertEquals(10L, db.mark("nas1", result.id).time)
        assertEquals(20L, db.mark("nas2", result.id).time)
        assertEquals("待更新", db.state(result, db.mark("nas1", id)))
        db.replaceSnapshot(Snapshot(listOf(renamed, record()), 0, 0))
        assertEquals(1, db.recordings().size)
    }

    @Test fun identicalNamesWithDifferentBytesRemainSeparate() = isolated("identity-test.db") { db ->
        val results = db.replaceSnapshot(Snapshot(listOf(record(), record("b".repeat(40), digest = "e".repeat(64))), 0, 0))
        assertEquals(2, results.size)
        assertEquals(2, db.recordings().size)
    }

    @Test fun historicalUploadHashDoesNotMergeDifferentCurrentContent() = isolated("identity-test.db") { db ->
        db.replaceSnapshot(Snapshot(listOf(record()), 0, 0))
        db.saveMark("nas", id, SyncMark(audio = "d".repeat(64)))
        val edited = record(digest = "e".repeat(64))
        db.replaceSnapshot(Snapshot(listOf(edited), 0, 0))
        val results = db.replaceSnapshot(Snapshot(listOf(record("b".repeat(40)), edited), 0, 0))
        assertEquals(2, results.size)
        assertEquals(2, db.recordings().size)
    }

    @Test fun upgradeV1PreservesReceiptsAndUsesTheirHashToRecognizeRename() {
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
                db.execSQL("CREATE TABLE recordings(id TEXT PRIMARY KEY,title TEXT,path TEXT,size INTEGER,duration INTEGER,created INTEGER,transcript TEXT,transcript_json TEXT,summary TEXT,metadata TEXT,available INTEGER,seen INTEGER)")
                db.execSQL("CREATE TABLE sync_marks(destination TEXT,id TEXT,audio TEXT,text TEXT,summary TEXT,metadata TEXT,time INTEGER,error TEXT,PRIMARY KEY(destination,id))")
                db.execSQL("INSERT INTO recordings VALUES(?, '原名','/old.mp3',3,1,1,'旧转写','{}','','原名',1,1)", arrayOf(id))
                db.execSQL("INSERT INTO sync_marks VALUES('nas',?,?,'','','原名',99,'')", arrayOf(id, "d".repeat(64)))
                db.version = 1
            }
            Store(context, name).use { db ->
                assertEquals(id, db.recordings().single().sourceId)
                val result = db.replaceSnapshot(Snapshot(listOf(record("b".repeat(40), "新名")), 0, 0)).single()
                assertEquals(id, result.id)
                assertEquals(id, db.mark("nas", id).remoteId)
                assertEquals(99L, db.mark("nas", id).time)
                assertEquals(1, db.recordings().size)
            }
        } finally { context.deleteDatabase(name) }
    }

    /** Real audio + real NAS protocol, simulated Recorder metadata rename; source is never modified. */
    @Test fun realAudioRenameUsesOnlyMovesAndRepeatDoesNotUploadAudio() = isolated("rename-flow-test.db") { db ->
        val bridge = BridgeClient.get()
        val snapshot = ParcelFileDescriptor.AutoCloseInputStream(bridge.exportMetadata()).bufferedReader().use {
            MetadataParser.parse(JSONObject(it.readText()))
        }
        val original = snapshot.recordings.filter { it.transcript.isNotBlank() }.minBy { it.size }
        val verified = RecordingScanner.verify(listOf(original), emptyList(), bridge, AtomicBoolean(false)) { _, _ -> }.single()
        val first = db.replaceSnapshot(Snapshot(listOf(verified), 0, 0)).single()
        val dav = WebDav(DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", "rename-device-test", true, false))
        dav.prepare()
        var audioUploads = 0
        val engine = SyncEngine(bridge, dav, AtomicBoolean(false)) { step, _, _ -> if (step == "上传音频") audioUploads++ }
        var mark = engine.upload(first, SyncMark()) { db.saveMark("nas", first.id, it) }
        val before = dav.readManifest(first.id)!!
        val hashBefore = before.getJSONObject("audio").getString("sha256")
        audioUploads = 0
        val updated = verified.copy(id = "f".repeat(40), sourceId = "f".repeat(40), title = "改名验证 #100%",
            metadata = JSONObject(verified.metadata).put("title", "改名验证 #100%").toString())
        val renamed = db.replaceSnapshot(Snapshot(listOf(updated), 0, 0)).single()
        assertEquals(first.id, renamed.id)
        assertEquals(first.transcript, renamed.transcript)
        mark = engine.upload(renamed, mark) { db.saveMark("nas", renamed.id, it) }
        assertEquals(0, audioUploads)
        val after = dav.readManifest(first.id)!!
        assertEquals(hashBefore, after.getJSONObject("audio").getString("sha256"))
        assertEquals("改名验证 #100%.mp3", after.getJSONObject("audio").getString("file"))
        assertFalse(dav.exists("${first.id}/${before.getJSONObject("audio").getString("file")}"))
        val completed = after.getLong("completedAt")
        engine.upload(renamed, mark) { }
        assertEquals(0, audioUploads)
        assertEquals(completed, dav.readManifest(first.id)!!.getLong("completedAt"))
        assertEquals("已同步", db.state(renamed, db.mark("nas", renamed.id)))
        println("SHENGQIAO real audio content identity + source-ID/title rename + no audio reupload verified")
    }
}
