package com.yuhuo.shengqiao.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable two-phase MOVE journal. Recovery always precedes any further rename/upload. */
class RemoteRename(private val dav: WebDav, private val id: String) {
    private val journalPath = "$id/.rename.json"

    fun recover() {
        val journal = dav.readJson(journalPath) ?: return
        check(journal.getString("directory") == id) { "远程改名记录不匹配" }
        val moves = journal.getJSONArray("moves")
        // Stage all old names first; this also supports case-only renames on NAS.
        for (i in 0 until moves.length()) {
            val row = moves.getJSONObject(i)
            val from = path(row.getString("from")); val temporary = path(row.getString("temporary"))
            val to = path(row.getString("to")); val size = row.getLong("bytes")
            if (dav.exists(temporary, size)) continue
            if (dav.exists(from)) {
                check(!dav.exists(to) || from.equals(to, ignoreCase = true)) { "远程改名遇到同名文件，未覆盖" }
                dav.moveExisting(from, temporary, size)
            } else check(dav.exists(to, size)) { "远程改名的原文件与目标均不可用，已停止" }
        }
        for (i in 0 until moves.length()) {
            val row = moves.getJSONObject(i)
            val temporary = path(row.getString("temporary")); val to = path(row.getString("to"))
            val size = row.getLong("bytes")
            if (dav.exists(temporary)) dav.moveExisting(temporary, to, size)
            else check(dav.exists(to, size)) { "远程改名恢复校验失败" }
        }
        dav.putText("$id/manifest.json", journal.getJSONObject("manifest").toString())
        dav.delete(journalPath)
    }

    fun rename(remote: JSONObject, desired: JSONObject, title: String): JSONObject {
        val current = RemoteNames.current(remote)
        val moves = JSONArray()
        val token = UUID.randomUUID().toString()
        for (key in listOf("audio", "transcriptText", "transcriptJson", "summary")) {
            val from = current.getString(key); val to = desired.getString(key)
            if (from == to) continue
            val size = dav.fileSize(path(from)) ?: continue
            check(!dav.exists(path(to)) || from.equals(to, ignoreCase = true)) { "远程新文件名已被占用，已停止重命名" }
            moves.put(JSONObject().put("from", from).put("to", to).put("bytes", size)
                .put("temporary", ".rename-$token-$key"))
        }
        val updated = JSONObject(remote.toString()).put("schemaVersion", 2).put("files", desired).put("title", title)
        updated.getJSONObject("audio").put("file", desired.getString("audio"))
        if (moves.length() == 0) return updated
        dav.putText(journalPath, JSONObject().put("directory", id).put("moves", moves).put("manifest", updated).toString())
        recover()
        return updated
    }

    private fun path(name: String) = "$id/${RemoteNames.basename(name)}"
}
