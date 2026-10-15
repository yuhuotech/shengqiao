package com.yuhuo.shengqiao.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

data class Recording(
    val id: String, val title: String, val path: String, val size: Long, val duration: Long,
    val created: Long, val transcript: String, val transcriptJson: String, val summary: String,
    val metadata: String, val available: Boolean = true,
    val sourceId: String = id, val contentSha256: String = "", val modifiedAt: Long = 0,
    val skipReason: String = ""
) {
    val transcriptHash: String by lazy { if (transcript.isBlank()) "" else hash(transcriptJson) }
    val metadataHash: String by lazy { hash(metadata) }
    val summaryHash: String by lazy { if (summary.isBlank()) "" else hash(summary) }
    internal val markdownFingerprints = java.util.concurrent.ConcurrentHashMap<String, String>()
    val extension: String get() = path.substringAfterLast('.', "mp3").lowercase()
}

data class Snapshot(val recordings: List<Recording>, val orphanSentences: Int, val deleted: Int)

object MetadataParser {
    fun parse(root: JSONObject): Snapshot {
        require(root.has("mRecordFileList")) { "录音机导出格式已变化，未找到录音列表" }
        val sentences = mutableMapOf<String, MutableList<JSONObject>>()
        root.optJSONArray("sentenceList")?.let { list ->
            for (i in 0 until list.length()) {
                val row = list.optJSONObject(i) ?: continue
                val key = row.optString("recordSha1").lowercase()
                sentences.getOrPut(key) { mutableListOf() }.add(row)
            }
        }
        val summaries = mutableMapOf<String, String>()
        root.optJSONArray("mSumaryList")?.let { list ->
            for (i in 0 until list.length()) {
                val row = list.optJSONObject(i) ?: continue
                val body = row.optString("summaryResult", "")
                if (body.isNotBlank()) summaries[row.optString("sha1").lowercase()] = body
            }
        }
        val deleted = mutableSetOf<String>()
        root.optJSONArray("mDeleteLocalRecordList")?.let { list ->
            for (i in 0 until list.length()) deleted += list.optJSONObject(i)?.optString("sha1")?.lowercase() ?: ""
        }
        val result = linkedMapOf<String, Recording>()
        val records = root.getJSONArray("mRecordFileList")
        for (i in 0 until records.length()) {
            val row = records.optJSONObject(i) ?: continue
            val id = row.optString("sha1").lowercase()
            val path = row.optString("filePath")
            if (!id.matches(Regex("[a-f0-9]{40}")) || id in deleted || "/.trash/" in path) continue
            if (path.substringAfterLast('.', "").lowercase() !in setOf("mp3", "m4a", "wav", "amr", "aac")) continue
            val title = row.optString("displayName").ifBlank {
                row.optString("fileName").substringBeforeLast('.').ifBlank { "未命名录音" }
            }
            val duration = row.optLong("duration")
            val created = row.optLong("createTime")
            val parts = sentences[id].orEmpty().sortedWith(compareBy(
                { timeline(it).optLong(0, Long.MAX_VALUE) }, { it.optLong("segId") }, { it.optLong("id") }))
            val transcriptParts = JSONArray()
            val text = StringBuilder()
            for (part in parts) {
                val words = runCatching { JSONArray(part.optString("lyric")) }.getOrDefault(JSONArray())
                val content = part.optString("content").ifBlank {
                    (0 until words.length()).joinToString("") { words.optString(it) }
                }
                if (content.isBlank()) continue
                val times = timeline(part)
                val start = if (times.length() > 0) times.optLong(0) else -1L
                val end = if (times.length() > 0) times.optLong(times.length() - 1) else -1L
                val speaker = part.optString("speaker").ifBlank { "说话人${part.optInt("speakerId") + 1}" }
                if (text.isNotEmpty()) text.append("\n\n")
                if (start >= 0) text.append("[").append(formatTime(start / 1000)).append("] ")
                text.append(speaker).append('\n').append(content)
                transcriptParts.put(JSONObject().put("text", content).put("speaker", speaker)
                    .put("speakerId", part.optInt("speakerId")).put("startMs", start).put("endMs", end)
                    .put("timeline", times).put("words", words))
            }
            val transcriptJson = JSONObject().put("schemaVersion", 1).put("sourceSha1", id)
                .put("sentences", transcriptParts).toString()
            val metadata = JSONObject().put("schemaVersion", 1).put("source", "xiaomi-recorder")
                .put("sourceSha1", id).put("title", title).put("durationSeconds", duration)
                .put("createdAt", created).put("audioBytes", row.optLong("size"))
                .put("audioExtension", path.substringAfterLast('.').lowercase()).toString()
            result[id] = Recording(id, title, path, row.optLong("size"), duration, created,
                text.toString(), transcriptJson, summaries[id].orEmpty(), metadata)
        }
        val orphan = sentences.filterKeys { it !in result }.values.sumOf { it.size }
        return Snapshot(result.values.sortedByDescending { it.created }, orphan, deleted.size)
    }

    private fun timeline(row: JSONObject): JSONArray =
        runCatching { JSONArray(row.optString("timeLine")) }.getOrDefault(JSONArray())

    fun formatTime(seconds: Long): String = "%02d:%02d:%02d".format(
        seconds.coerceAtLeast(0) / 3600, seconds.coerceAtLeast(0) / 60 % 60, seconds.coerceAtLeast(0) % 60)
}
