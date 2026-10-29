package com.yuhuo.shengqiao.sync

import android.os.ParcelFileDescriptor
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.RecordingScanner
import com.yuhuo.shengqiao.data.SyncMark
import com.yuhuo.shengqiao.data.hash
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

class SyncEngine(private val bridge: IRecorderBridge, private val dav: WebDav,
                 private val cancelled: AtomicBoolean, private val obsidian: ObsidianSync? = null,
                 private val progress: (String, Long, Long) -> Unit) {
    fun upload(r: Recording, old: SyncMark, save: (SyncMark) -> Unit): SyncMark =
        upload(r, old, RemoteSyncIndex(allowFastPath = false), {}, save)

    fun upload(r: Recording, old: SyncMark, remoteIndex: RemoteSyncIndex,
               ensurePrepared: () -> Unit, save: (SyncMark) -> Unit): SyncMark {
        if (cancelled.get()) throw InterruptedIOException("同步已停止")
        val before = bridge.fileInfo(r.path)
        check(before[0] > 0) { "音频不存在或为空，请刷新录音列表" }
        check(before[1] < System.currentTimeMillis() - 5000) { "录音文件仍在变化，稍后重试" }
        progress("核对录音内容", 0, 0)
        val contentHash = if (r.contentSha256.isNotEmpty() && r.modifiedAt == before[1] && r.size == before[0])
            r.contentSha256 else RecordingScanner.sha256(r, bridge, cancelled)
        check(before.contentEquals(bridge.fileInfo(r.path))) { "录音在核对时发生变化，请刷新后重试" }
        val directory = old.remoteId.ifBlank { r.id }
        val names = RemoteNames.files(r)
        val textHash = r.transcriptHash
        val textPlainHash = if (textHash.isEmpty()) "" else hash(r.transcript)
        val noteFingerprint = if (r.transcript.isNotBlank() && obsidian != null)
            MarkdownExport.fingerprint(r, obsidian.destination) else ""
        progress("比较远程增量索引", 0, 0)
        val cached = if (remoteIndex.allowFastPath) remoteIndex.entry(dav, r.id) else null
        if (cached != null && matchesIndex(cached, r, directory, before[0], before[1], contentHash,
                names, textHash, textPlainHash, noteFingerprint, obsidian?.destination.orEmpty())) {
            val completed = old.copy(audio = contentHash, text = textHash, summary = r.summaryHash,
                metadata = r.metadataHash, time = cached.optLong("completedAt", old.time), error = "",
                remoteId = directory, obsidian = noteFingerprint)
            return completed.also(save)
        }

        ensurePrepared()
        dav.recordDirectory(directory)
        // Invalidate completion before changing any remote names or publishing anything.
        var mark = old.copy(error = "", metadata = "", remoteId = directory)
        save(mark)
        val renamer = RemoteRename(dav, directory)
        renamer.recover()
        var remote = dav.readManifest(directory)
        if (remote != null) check(remote.optString("recordingId", remote.optString("sourceSha1")) in setOf(r.id, directory)) {
            "远程目录已属于另一条录音，已停止以避免覆盖"
        }
        val originalManifest = remote?.toString()
        if (remote != null) {
            progress("核对并重命名远程文件", 0, 0)
            remote = renamer.rename(remote, names, r.title)
        }
        val audioName = names.getString("audio")
        var audio = remote?.optJSONObject("audio")?.takeIf {
            it.optLong("bytes") == before[0] && it.optString("sha256") == contentHash &&
                it.optString("file") == audioName && dav.exists("$directory/$audioName", before[0])
        }?.let { JSONObject(it.toString()) }
        if (audio == null) {
            val receipt = dav.putAtomic("$directory/$audioName",
                ParcelFileDescriptor.AutoCloseInputStream(bridge.openAudio(r.path)), before[0],
                "application/octet-stream") { progress("上传音频", it, before[0]) }
            check(before.contentEquals(bridge.fileInfo(r.path)) && receipt.sha256 == contentHash) {
                "音频在同步过程中发生变化，已保留待重试状态"
            }
            audio = JSONObject().put("file", audioName).put("bytes", receipt.bytes)
                .put("sha256", receipt.sha256).put("fileSha1", receipt.fileSha1)
        }
        audio.put("sourceSha1", r.sourceId).put("sourceModifiedAt", before[1])
        mark = mark.copy(audio = audio.getString("sha256")); save(mark)
        val textJson = "$directory/${names.getString("transcriptJson")}"
        val textPlain = "$directory/${names.getString("transcriptText")}"
        fun remoteTextComplete(path: String, value: String) = dav.exists(path, value.toByteArray(Charsets.UTF_8).size.toLong())
        if (textHash.isNotEmpty() && (remote?.optString("transcriptHash") != textHash ||
                !remoteTextComplete(textJson, r.transcriptJson) || !remoteTextComplete(textPlain, r.transcript))) {
            progress("上传转写", 0, 0)
            dav.putText(textJson, r.transcriptJson)
            dav.putText(textPlain, r.transcript, "text/plain; charset=utf-8")
        }
        mark = mark.copy(text = textHash); save(mark)
        val summaryPath = "$directory/${names.getString("summary")}"
        if (r.summary.isNotBlank() && (remote?.optString("summaryHash") != r.summaryHash || !remoteTextComplete(summaryPath, r.summary))) {
            dav.putText(summaryPath, r.summary, "text/plain; charset=utf-8")
        }
        mark = mark.copy(summary = r.summaryHash); save(mark)
        if (remote?.optString("metadataHash") != r.metadataHash || !remoteTextComplete("$directory/metadata.json", r.metadata)) {
            dav.putText("$directory/metadata.json", r.metadata)
        }
        // Publish last; local success is recorded only after this receipt is saved and verified.
        val manifest = JSONObject().put("schemaVersion", 2).put("recordingId", r.id)
            .put("sourceSha1", r.sourceId).put("contentSha256", contentHash).put("title", r.title).put("files", names)
            .put("audio", audio).put("transcriptHash", textHash).put("summaryHash", r.summaryHash)
            .put("transcriptJsonHash", textHash).put("transcriptTextHash", textPlainHash)
            .put("metadataHash", r.metadataHash).put("transcriptAvailable", textHash.isNotEmpty())
            .put("completedAt", System.currentTimeMillis())
        val original = originalManifest?.let { JSONObject(it) }
        val unchanged = original != null && original.optInt("schemaVersion") == 2 && original.optString("title") == r.title &&
            original.optString("sourceSha1") == r.sourceId && original.optString("transcriptHash") == textHash &&
            original.optString("transcriptJsonHash", original.optString("transcriptHash")) == textHash &&
            original.optString("transcriptTextHash") == textPlainHash &&
            original.optString("summaryHash") == r.summaryHash && original.optString("metadataHash") == r.metadataHash &&
            original.optJSONObject("files")?.toString() == names.toString() &&
            original.optJSONObject("audio")?.toString() == audio.toString()
        check(before.contentEquals(bridge.fileInfo(r.path))) { "音频在同步过程中变化，请刷新重试" }
        if (!unchanged) dav.putText("$directory/manifest.json", manifest.toString())
        var noteReceipt: ObsidianReceipt? = null
        if (obsidian != null && r.transcript.isNotBlank()) {
            progress("同步 Obsidian Markdown", 0, 0)
            noteReceipt = obsidian.uploadWithReceipt(r)
            mark = mark.copy(obsidian = noteReceipt.fingerprint); save(mark)
        }
        val completedAt = System.currentTimeMillis()
        val completed = mark.copy(metadata = r.metadataHash, time = completedAt, error = "")
        remoteIndex.put(dav, r.id, indexRecord(r, directory, before[0], before[1], contentHash, names,
            audio.optString("fileSha1"), textHash, textPlainHash, noteReceipt, obsidian?.destination.orEmpty(), completedAt))
        return completed.also(save)
    }

    private fun matchesIndex(entry: JSONObject, r: Recording, directory: String, audioBytes: Long,
                             sourceModifiedAt: Long, audioHash: String, names: JSONObject,
                             transcriptJsonHash: String, transcriptTextHash: String,
                             noteFingerprint: String, noteDestination: String): Boolean {
        if (entry.optInt("schemaVersion") != 1 || entry.optString("recordingId") != r.id ||
            entry.optString("remoteDirectory") != directory || entry.optString("title") != r.title ||
            entry.optString("sourceSha1") != r.sourceId || entry.optString("contentSha256") != audioHash ||
            entry.optLong("sourceModifiedAt", -1) != sourceModifiedAt) return false
        val indexedNames = entry.optJSONObject("files") ?: return false
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

    private fun indexRecord(r: Recording, directory: String, audioBytes: Long, sourceModifiedAt: Long,
                            audioHash: String, names: JSONObject, audioSha1: String, transcriptJsonHash: String,
                            transcriptTextHash: String, note: ObsidianReceipt?, noteDestination: String,
                            completedAt: Long): JSONObject {
        val summaryBytes = if (r.summaryHash.isEmpty()) 0L else r.summary.toByteArray(Charsets.UTF_8).size.toLong()
        val transcriptJsonBytes = if (transcriptJsonHash.isEmpty()) 0L else r.transcriptJson.toByteArray(Charsets.UTF_8).size.toLong()
        val transcriptTextBytes = if (transcriptTextHash.isEmpty()) 0L else r.transcript.toByteArray(Charsets.UTF_8).size.toLong()
        val files = JSONObject(names.toString()).put("metadata", "metadata.json").put("manifest", "manifest.json")
        if (note?.path?.isNotBlank() == true) files.put("obsidianMarkdown", note.path)
        return JSONObject().put("schemaVersion", 1).put("recordingId", r.id).put("remoteDirectory", directory)
            .put("title", r.title).put("sourceSha1", r.sourceId).put("sourceModifiedAt", sourceModifiedAt)
            .put("contentSha256", audioHash).put("files", files)
            .put("hashes", JSONObject().put("audio", audioHash).put("audioSha1", audioSha1)
                .put("transcriptJson", transcriptJsonHash).put("transcriptText", transcriptTextHash)
                .put("summary", r.summaryHash).put("metadata", r.metadataHash)
                .put("obsidianMarkdown", note?.sha256.orEmpty()))
            .put("bytes", JSONObject().put("audio", audioBytes).put("transcriptJson", transcriptJsonBytes)
                .put("transcriptText", transcriptTextBytes).put("summary", summaryBytes)
                .put("metadata", r.metadata.toByteArray(Charsets.UTF_8).size.toLong())
                .put("obsidianMarkdown", note?.bytes ?: 0L))
            .put("transcriptAvailable", transcriptJsonHash.isNotEmpty())
            .put("obsidian", JSONObject().put("destination", noteDestination)
                .put("fingerprint", note?.fingerprint.orEmpty()).put("path", note?.path.orEmpty())
                .put("sha256", note?.sha256.orEmpty()).put("bytes", note?.bytes ?: 0L))
            .put("completedAt", completedAt)
    }
}
