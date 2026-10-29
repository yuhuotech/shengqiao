package com.yuhuo.shengqiao.sync

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
