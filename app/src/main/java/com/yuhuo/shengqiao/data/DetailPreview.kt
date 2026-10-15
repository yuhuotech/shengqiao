package com.yuhuo.shengqiao.data

enum class PreviewRole { TRANSCRIPT, SUMMARY, HEADING, NOTE }
data class PreviewBlock(val text: String, val role: PreviewRole)

/** Keep all preview text available while each visible TextView lays out a short segment. */
object DetailPreview {
    fun split(value: String, limit: Int = 1200): List<String> {
        require(limit >= 2)
        val result = ArrayList<String>()
        var start = 0
        while (start < value.length) {
            var end = (start + limit).coerceAtMost(value.length)
            if (end < value.length) {
                val paragraph = value.lastIndexOf('\n', end - 1)
                val sentence = value.lastIndexOf('。', end - 1)
                val boundary = if (paragraph >= start + limit / 3) paragraph else sentence
                if (boundary >= start + limit / 3) end = boundary + 1
                if (end < value.length && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
            }
            result += value.substring(start, end)
            start = end
        }
        return result
    }

    fun blocks(r: Recording): List<PreviewBlock> = buildList {
        val body = r.transcript.ifBlank { "小米录音机尚未生成转写。音频可以先同步，之后补传文字。" }.take(50_000)
        addAll(split(body).map { PreviewBlock(it, PreviewRole.TRANSCRIPT) })
        if (r.transcript.length > 50_000) add(PreviewBlock("此处预览前 50,000 字符，同步时会保存完整转写。", PreviewRole.NOTE))
        if (r.summary.isNotBlank()) {
            add(PreviewBlock("AI 摘要", PreviewRole.HEADING))
            addAll(split(r.summary.take(30_000)).map { PreviewBlock(it, PreviewRole.SUMMARY) })
        }
    }
}
