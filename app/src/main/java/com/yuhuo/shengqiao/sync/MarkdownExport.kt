package com.yuhuo.shengqiao.sync

import com.yuhuo.shengqiao.data.MetadataParser
import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.hash
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MarkdownExport {
    // Use the recording's creation date in China, independent of the sync date/timezone.
    private fun date(r: Recording, pattern: String): String {
        if (r.created <= 0) return "日期未知"
        val millis = if (r.created < 10_000_000_000L) r.created * 1000 else r.created
        return SimpleDateFormat(pattern, Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(millis))
    }
    fun month(r: Recording): String = if (r.created <= 0) "日期未知" else date(r, "yyyyMM")
    fun path(r: Recording): String = "${month(r)}/${RemoteNames.stem(r.title)}.md"
    fun render(r: Recording): String = buildString {
        append("---\n")
        append("shengqiao_id: \"").append(r.id).append("\"\n")
        if (r.created > 0) append("recorded_at: ").append(date(r, "yyyy-MM-dd'T'HH:mm:ss")).append('\n')
        append("recorded_month: \"").append(month(r)).append("\"\n")
        append("source: xiaomi-recorder\n---\n\n")
        append("# ").append(r.title.replace('\n', ' ').replace('\r', ' ')).append("\n\n")
        append("- 录音时间：").append(date(r, "yyyy-MM-dd HH:mm")).append('\n')
        append("- 时长：").append(MetadataParser.formatTime(r.duration)).append("\n\n")
        if (r.summary.isNotBlank()) append("## 摘要\n\n").append(r.summary).append("\n\n")
        append("## 转写正文\n\n").append(r.transcript).append('\n')
    }
    fun fingerprint(r: Recording, destination: String): String =
        r.markdownFingerprints.getOrPut(destination) {
            hash(destination + "\n" + r.id + "\n" + path(r) + "\n" + render(r))
        }
}
