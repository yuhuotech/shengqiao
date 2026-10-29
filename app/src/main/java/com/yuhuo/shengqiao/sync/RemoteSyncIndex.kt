package com.yuhuo.shengqiao.sync

import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.SyncMark
import com.yuhuo.shengqiao.data.hash
import org.json.JSONException
import org.json.JSONObject

/** A compact destination-wide receipt used to avoid probing every remote file on each run. */
class RemoteSyncIndex(private val deferWrites: Boolean = false, val allowFastPath: Boolean = true) {
    companion object {
        const val PATH = ".shengqiao-index.json"
        private const val MAX_BYTES = 16 * 1024 * 1024
        private const val SCHEMA_VERSION = 1
    }

    private val records = JSONObject()
    private var loaded = false
    private var dirty = false
    private var writer: WebDav? = null

    @Synchronized fun hasPendingChanges(): Boolean = dirty

    @Synchronized fun entry(dav: WebDav, id: String): JSONObject? {
        load(dav)
        if (dirty) writer = dav
        return records.optJSONObject(id)?.let { JSONObject(it.toString()) }
    }

    fun matches(entry: JSONObject, r: Recording, directory: String, audioBytes: Long,
                sourceModifiedAt: Long, audioHash: String, noteDestination: String): Boolean {
        val transcriptJsonHash = r.transcriptHash
        val transcriptTextHash = if (transcriptJsonHash.isEmpty()) "" else hash(r.transcript)
        val noteFingerprint = if (r.transcript.isNotBlank()) MarkdownExport.fingerprint(r, noteDestination) else ""
        if (entry.optInt("schemaVersion") != 1 || entry.optString("recordingId") != r.id ||
            entry.optString("remoteDirectory") != directory || entry.optString("title") != r.title ||
            entry.optString("sourceSha1") != r.sourceId || entry.optString("contentSha256") != audioHash ||
            entry.optLong("sourceModifiedAt", -1) != sourceModifiedAt) return false
        val indexedNames = entry.optJSONObject("files") ?: return false
        val names = RemoteNames.files(r)
        for (key in listOf("audio", "transcriptText", "transcriptJson", "summary"))
            if (indexedNames.optString(key) != names.optString(key)) return false
        if (indexedNames.optString("metadata") != "metadata.json" ||
            indexedNames.optString("manifest") != "manifest.json") return false
        val hashes = entry.optJSONObject("hashes") ?: return false
        if (hashes.optString("audio") != audioHash || hashes.optString("transcriptJson") != transcriptJsonHash ||
            hashes.optString("transcriptText") != transcriptTextHash || hashes.optString("summary") != r.summaryHash ||
            hashes.optString("metadata") != r.metadataHash) return false
        val sizes = entry.optJSONObject("bytes") ?: return false
        if (sizes.optLong("audio", -1) != audioBytes ||
            sizes.optLong("transcriptJson", -1) != (if (transcriptJsonHash.isEmpty()) 0 else r.transcriptJson.toByteArray(Charsets.UTF_8).size.toLong()) ||
            sizes.optLong("transcriptText", -1) != (if (transcriptTextHash.isEmpty()) 0 else r.transcript.toByteArray(Charsets.UTF_8).size.toLong()) ||
            sizes.optLong("summary", -1) != (if (r.summaryHash.isEmpty()) 0 else r.summary.toByteArray(Charsets.UTF_8).size.toLong()) ||
            sizes.optLong("metadata", -1) != r.metadata.toByteArray(Charsets.UTF_8).size.toLong()) return false
        val notes = entry.optJSONObject("obsidian") ?: return false
        if (noteFingerprint.isEmpty()) return notes.optString("fingerprint").isEmpty()
        return notes.optString("destination") == noteDestination && notes.optString("fingerprint") == noteFingerprint &&
            notes.optString("path").isNotBlank() && notes.optString("sha256").matches(Regex("[a-f0-9]{64}")) &&
            notes.optLong("bytes", -1) >= 0
    }

    fun completedMark(entry: JSONObject, r: Recording, previous: SyncMark, directory: String,
                      noteDestination: String): SyncMark {
        val noteFingerprint = if (r.transcript.isNotBlank()) MarkdownExport.fingerprint(r, noteDestination) else ""
        return previous.copy(audio = r.contentSha256, text = r.transcriptHash, summary = r.summaryHash,
            metadata = r.metadataHash, time = entry.optLong("completedAt", previous.time), error = "",
            remoteId = directory, obsidian = noteFingerprint)
    }

    @Synchronized fun put(dav: WebDav, id: String, entry: JSONObject) {
        require(id.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))) { "远程索引录音 ID 无效" }
        load(dav)
        records.put(id, JSONObject(entry.toString()))
        writer = dav
        dirty = true
        if (!deferWrites) flush(dav)
    }

    @Synchronized fun flush() {
        if (!dirty) return
        flush(writer ?: error("远程索引尚未连接到服务器"))
    }

    @Synchronized fun flush(dav: WebDav) {
        if (!dirty) return
        dav.putText(PATH, JSONObject().put("schemaVersion", SCHEMA_VERSION).put("records", records).toString())
        writer = dav
        dirty = false
    }

    private fun load(dav: WebDav) {
        if (loaded) return
        val root = try { dav.readJson(PATH, MAX_BYTES) } catch (_: JSONException) { null }
        catch (e: DavHttpException) { if (e.status == 403) null else throw e }
        val existing = root?.takeIf { it.optInt("schemaVersion") == SCHEMA_VERSION }?.optJSONObject("records")
        if (existing != null) {
            val keys = existing.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val item = existing.optJSONObject(id) ?: continue
                if (id.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}")) &&
                    item.optString("recordingId") == id && item.optInt("schemaVersion") == SCHEMA_VERSION) {
                    records.put(id, JSONObject(item.toString()))
                }
            }
        }
        writer = dav
        loaded = true
    }
}
