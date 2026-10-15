package com.yuhuo.shengqiao.sync

import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.hash
import org.json.JSONObject

object RemoteNames {
    fun stem(title: String): String {
        val clean = title.map { if (it.code < 32 || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
            .trim().trimEnd('.').ifBlank { "未命名录音" }
        var shortened = clean
        while (shortened.toByteArray(Charsets.UTF_8).size > 150)
            shortened = shortened.substring(0, shortened.offsetByCodePoints(shortened.length, -1))
        // Avoid aliases after replacing unsupported characters or truncating long names.
        return if (shortened != title) "$shortened-${hash(title).take(10)}" else shortened
    }

    fun files(r: Recording): JSONObject {
        val name = stem(r.title)
        return JSONObject().put("audio", "$name.${r.extension}")
            .put("transcriptText", "$name.txt").put("transcriptJson", "$name.transcript.json")
            .put("summary", "$name.summary.txt")
    }

    fun current(manifest: JSONObject): JSONObject = manifest.optJSONObject("files") ?: JSONObject()
        .put("audio", manifest.getJSONObject("audio").getString("file"))
        .put("transcriptText", "transcript.txt").put("transcriptJson", "transcript.json").put("summary", "summary.txt")

    fun basename(value: String): String {
        require(value.isNotBlank() && value !in setOf(".", "..") && '/' !in value && '\\' !in value) { "服务器文件名记录异常" }
        return value
    }
}
