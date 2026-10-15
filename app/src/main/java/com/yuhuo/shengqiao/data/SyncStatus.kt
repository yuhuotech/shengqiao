package com.yuhuo.shengqiao.data

/** Completion means all content currently available at the source has been synced. */
object SyncStatus {
    fun state(r: Recording, mark: SyncMark, obsidianDestination: String = ""): String = when {
        r.skipReason.isNotEmpty() -> "文件未稳定 · 暂缓同步"
        mark.error.isNotBlank() -> "同步失败"
        !r.available -> "源文件不可用"
        mark.audio.isEmpty() -> "未同步"
        mark.text != r.transcriptHash || mark.metadata != r.metadataHash || mark.summary != r.summaryHash -> "待更新"
        r.contentSha256.isNotEmpty() && mark.audio != r.contentSha256 -> "待更新"
        r.transcript.isNotBlank() && obsidianDestination.isNotEmpty() &&
            mark.obsidian != com.yuhuo.shengqiao.sync.MarkdownExport.fingerprint(r, obsidianDestination) -> "待更新"
        else -> "已同步"
    }
}
