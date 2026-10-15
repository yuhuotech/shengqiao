package com.yuhuo.shengqiao.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SyncMark(val audio: String = "", val text: String = "", val summary: String = "",
                    val metadata: String = "", val time: Long = 0, val error: String = "", val remoteId: String = "",
                    val obsidian: String = "")

class Store(context: Context, name: String = "shengqiao.db") : SQLiteOpenHelper(context, name, null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE recordings(id TEXT PRIMARY KEY,title TEXT,path TEXT,size INTEGER,
            duration INTEGER,created INTEGER,transcript TEXT,transcript_json TEXT,summary TEXT,
            metadata TEXT,available INTEGER,seen INTEGER,source_id TEXT,content_sha256 TEXT,modified_at INTEGER,skip_reason TEXT)""")
        db.execSQL("""CREATE TABLE sync_marks(destination TEXT,id TEXT,audio TEXT,text TEXT,
            summary TEXT,metadata TEXT,time INTEGER,error TEXT,remote_id TEXT,obsidian TEXT,PRIMARY KEY(destination,id))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN source_id TEXT")
            db.execSQL("ALTER TABLE recordings ADD COLUMN content_sha256 TEXT")
            db.execSQL("ALTER TABLE recordings ADD COLUMN modified_at INTEGER DEFAULT 0")
            db.execSQL("UPDATE recordings SET source_id=id")
            db.execSQL("ALTER TABLE sync_marks ADD COLUMN remote_id TEXT")
            db.execSQL("UPDATE sync_marks SET remote_id=id")
            db.execSQL("UPDATE sync_marks SET metadata=''")
        }
        if (old < 3) db.execSQL("ALTER TABLE sync_marks ADD COLUMN obsidian TEXT")
        if (old < 4) db.execSQL("ALTER TABLE recordings ADD COLUMN skip_reason TEXT")
    }

    @Synchronized fun replaceSnapshot(snapshot: Snapshot): List<Recording> {
        val previous = recordings()
        val bySource = previous.associateBy { it.sourceId }
        val byHash = previous.filter { it.contentSha256.isNotEmpty() }.associateBy { it.contentSha256 }.toMutableMap()
        // v1 already stored actual uploaded SHA-256. It can identify a renamed source
        // even if the very first v2 scan sees a changed Xiaomi ID and an obsolete path.
        readableDatabase.rawQuery("SELECT id,audio FROM sync_marks WHERE length(audio)=64", null).use { c ->
            val byId = previous.associateBy { it.id }
            while (c.moveToNext()) byId[c.getString(0)]?.let {
                val digest = c.getString(1)
                if (it.contentSha256.isEmpty() || it.contentSha256 == digest) byHash.putIfAbsent(digest, it)
            }
        }
        val resolved = linkedMapOf<String, Recording>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE recordings SET seen=0,available=0")
            for (r in snapshot.recordings.sortedWith(compareByDescending<Recording> { it.available }
                .thenByDescending { bySource.containsKey(it.sourceId) }
                .thenByDescending { it.created }.thenByDescending { it.transcript.isNotBlank() })) {
                // Content identity takes priority over Xiaomi IDs. Keep the original canonical
                // ID (and every destination's receipts) when the source name/path/ID changes.
                val existing = byHash[r.contentSha256] ?: bySource[r.sourceId]
                var canonical = r.copy(id = existing?.id ?: r.id)
                if (existing != null && existing.sourceId != r.sourceId &&
                    r.contentSha256.isNotEmpty() && byHash[r.contentSha256] != null && r.transcript.isBlank()) {
                    canonical = canonical.copy(transcript = existing.transcript, transcriptJson = existing.transcriptJson,
                        summary = r.summary.ifBlank { existing.summary })
                }
                if (existing != null && existing.id != r.id && previous.any { it.id == r.id }) {
                    // Collapse aliases discovered during the first content-hash scan. Keep
                    // successful destination receipts, including their original remote directory.
                    // Existing separate remote copies are never deleted implicitly.
                    db.execSQL("INSERT OR IGNORE INTO sync_marks SELECT destination,?,audio,text,summary,metadata,time,error,COALESCE(NULLIF(remote_id,''),id),obsidian FROM sync_marks WHERE id=?",
                        arrayOf(canonical.id, r.id))
                    db.delete("recordings", "id=?", arrayOf(r.id))
                    db.delete("sync_marks", "id=?", arrayOf(r.id))
                }
                if (resolved.containsKey(canonical.id)) continue
                resolved[canonical.id] = canonical
                if (r.contentSha256.isNotEmpty()) {
                    byHash.entries.removeAll { it.value.id == canonical.id && it.key != r.contentSha256 }
                    byHash[r.contentSha256] = canonical
                }
                val values = ContentValues().apply {
                    put("id", canonical.id); put("title", canonical.title); put("path", canonical.path); put("size", canonical.size)
                    put("duration", canonical.duration); put("created", canonical.created); put("transcript", canonical.transcript)
                    put("transcript_json", canonical.transcriptJson); put("summary", canonical.summary)
                    put("metadata", canonical.metadata); put("available", if (canonical.available) 1 else 0); put("seen", 1)
                    put("source_id", canonical.sourceId); put("content_sha256", canonical.contentSha256); put("modified_at", canonical.modifiedAt)
                    put("skip_reason", canonical.skipReason)
                }
                db.insertWithOnConflict("recordings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            // Retain disappeared items and their successful upload receipts for traceability.
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return resolved.values.toList()
    }

    @Synchronized fun recordings(): List<Recording> {
        return readRecordings(null, null)
    }
    @Synchronized fun recording(id: String): Recording? = readRecordings("id=? OR source_id=?", arrayOf(id, id)).firstOrNull()
    private fun readRecordings(selection: String?, args: Array<String>?): List<Recording> {
        val result = mutableListOf<Recording>()
        readableDatabase.query("recordings", null, selection, args, null, null, "created DESC").use { c ->
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
            fun l(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
            while (c.moveToNext()) result += Recording(s("id"), s("title"), s("path"), l("size"),
                l("duration"), l("created"), s("transcript"), s("transcript_json"), s("summary"),
                s("metadata"), l("available") == 1L, s("source_id").ifBlank { s("id") },
                s("content_sha256"), l("modified_at"), s("skip_reason"))
        }
        return result
    }

    @Synchronized fun mark(destination: String, id: String): SyncMark {
        readableDatabase.query("sync_marks", null, "destination=? AND id=?", arrayOf(destination, id),
            null, null, null).use { c ->
            if (!c.moveToFirst()) return SyncMark()
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
            return SyncMark(s("audio"), s("text"), s("summary"), s("metadata"),
                c.getLong(c.getColumnIndexOrThrow("time")), s("error"), s("remote_id"), s("obsidian"))
        }
    }

    @Synchronized fun marks(destination: String): Map<String, SyncMark> {
        val result = mutableMapOf<String, SyncMark>()
        readableDatabase.query("sync_marks", null, "destination=?", arrayOf(destination), null, null, null).use { c ->
            fun s(name: String) = c.getString(c.getColumnIndexOrThrow(name)).orEmpty()
            while (c.moveToNext()) result[s("id")] = SyncMark(s("audio"), s("text"), s("summary"),
                s("metadata"), c.getLong(c.getColumnIndexOrThrow("time")), s("error"), s("remote_id"), s("obsidian"))
        }
        return result
    }

    @Synchronized fun saveMark(destination: String, id: String, mark: SyncMark) {
        writableDatabase.insertWithOnConflict("sync_marks", null, ContentValues().apply {
            put("destination", destination); put("id", id); put("audio", mark.audio); put("text", mark.text)
            put("summary", mark.summary); put("metadata", mark.metadata); put("time", mark.time)
            put("error", mark.error); put("remote_id", mark.remoteId)
            put("obsidian", mark.obsidian)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun removeDestination(destination: String) {
        writableDatabase.delete("sync_marks", "destination=?", arrayOf(destination))
    }

    fun state(r: Recording, mark: SyncMark, obsidianDestination: String = ""): String =
        SyncStatus.state(r, mark, obsidianDestination)
}
