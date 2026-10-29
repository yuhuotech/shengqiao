package com.yuhuo.shengqiao.sync

import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.hash
import org.json.JSONObject

data class ObsidianReceipt(val fingerprint: String, val path: String, val sha256: String, val bytes: Long)

/** The hidden per-record receipt keeps flat Markdown names independent of recording identity. */
class ObsidianSync(private val dav: WebDav, val destination: String) {
    fun upload(r: Recording): String = uploadWithReceipt(r).fingerprint

    fun uploadWithReceipt(r: Recording): ObsidianReceipt {
        if (r.transcript.isBlank()) return ObsidianReceipt("", "", "", 0)
        require(r.id.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}")))
        dav.ensureDirectory(".shengqiao")
        val statePath = ".shengqiao/${r.id}.json"
        val pendingPath = ".shengqiao/${r.id}.rename.json"
        recover(pendingPath, statePath, r.id)
        var state = dav.readJson(statePath)
        if (state != null) check(state.getString("id") == r.id) { "Obsidian 同步记录不匹配" }
        dav.ensureDirectory(MarkdownExport.month(r))
        val preferred = MarkdownExport.path(r)
        var desired = preferred
        val oldPath = state?.getString("path")?.let(::validatePath)
        // A human-created note or another recording must never be overwritten.
        if (desired != oldPath && dav.exists(desired) &&
            !(oldPath != null && oldPath.equals(desired, ignoreCase = true))) {
            desired = preferred.removeSuffix(".md") + "-${r.id}.md"
            check(desired == oldPath || !dav.exists(desired)) { "Obsidian 目标笔记已被占用，未覆盖" }
        }
        if (oldPath != null && oldPath != desired) {
            val bytes = dav.fileSize(oldPath)
            if (bytes != null) {
                val updated = JSONObject(state.toString()).put("path", desired)
                val pending = JSONObject().put("id", r.id).put("from", oldPath).put("to", desired)
                    .put("temporary", ".shengqiao/${r.id}.moving").put("bytes", bytes).put("state", updated)
                dav.putText(pendingPath, pending.toString())
                recover(pendingPath, statePath, r.id)
                state = updated
            } else state = null
        }
        val markdown = MarkdownExport.render(r)
        val digest = hash(markdown)
        val bytes = markdown.toByteArray(Charsets.UTF_8).size.toLong()
        if (state == null || state.optString("hash") != digest || !dav.exists(desired, bytes)) {
            // Reserve the stable path before PUT. Retrying after a lost upload response
            // updates this same path instead of creating a second suffixed note.
            val reserved = JSONObject().put("id", r.id).put("path", desired).put("hash", "")
            dav.putText(statePath, reserved.toString())
            dav.putText(desired, markdown, "text/markdown; charset=utf-8")
            dav.putText(statePath, reserved.put("hash", digest).put("bytes", bytes).toString())
        }
        return ObsidianReceipt(MarkdownExport.fingerprint(r, destination), desired, digest, bytes)
    }

    private fun recover(pendingPath: String, statePath: String, id: String) {
        val pending = dav.readJson(pendingPath) ?: return
        check(pending.getString("id") == id) { "Obsidian 改名记录不匹配" }
        val from = validatePath(pending.getString("from")); val to = validatePath(pending.getString("to"))
        val temporary = pending.getString("temporary")
        check(temporary == ".shengqiao/$id.moving") { "Obsidian 改名临时路径异常" }
        val size = pending.getLong("bytes")
        if (!dav.exists(temporary, size)) {
            if (dav.exists(from)) dav.moveExisting(from, temporary, size)
            else check(dav.exists(to, size)) { "Obsidian 原笔记和目标均不可用" }
        }
        if (dav.exists(temporary)) dav.moveExisting(temporary, to, size)
        check(dav.exists(to, size)) { "Obsidian 改名校验失败" }
        dav.putText(statePath, pending.getJSONObject("state").toString())
        dav.delete(pendingPath)
    }

    private fun validatePath(path: String): String {
        val parts = path.split('/')
        require(parts.size == 2 && (parts[0].matches(Regex("[0-9]{6}")) || parts[0] == "日期未知") &&
            RemoteNames.basename(parts[1]).endsWith(".md")) { "Obsidian 笔记路径异常" }
        return path
    }
}
