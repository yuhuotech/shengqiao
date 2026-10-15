package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.*
import org.junit.Assert.*
import org.junit.Test

class DetailPreviewTest {
    @Test fun `all paragraphs remain available without omitting or duplicating text`() {
        val source = ("[00:00:01] 说话人1\n会议正文。\n\n" + "很长的段落".repeat(600) + "😀\n").repeat(10)
        val parts = DetailPreview.split(source)
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1200 && it.isNotEmpty() })
        assertTrue(parts.size > 10)
    }
    @Test fun `segment boundary never separates a unicode surrogate pair`() {
        val source = "a".repeat(1199) + "😀" + "b".repeat(1300)
        val parts = DetailPreview.split(source)
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
    @Test fun `preview limits preserve complete available text and summary roles`() {
        val r = Recording("a".repeat(40), "标题", "/audio.mp3", 1, 1, 1,
            "正文".repeat(30_000), "{}", "摘要".repeat(20_000), "{}")
        val rows = DetailPreview.blocks(r)
        assertEquals(r.transcript.take(50_000), rows.filter { it.role == PreviewRole.TRANSCRIPT }.joinToString("") { it.text })
        assertEquals(r.summary.take(30_000), rows.filter { it.role == PreviewRole.SUMMARY }.joinToString("") { it.text })
        assertEquals(1, rows.count { it.role == PreviewRole.NOTE })
        assertEquals(1, rows.count { it.role == PreviewRole.HEADING })
    }
    @Test fun `empty transcript retains waiting explanation`() {
        val r = Recording("a".repeat(40), "标题", "/audio.mp3", 1, 1, 1, "", "{}", "", "{}")
        assertTrue(DetailPreview.blocks(r).single().text.contains("尚未生成转写"))
        assertTrue(DetailPreview.split("").isEmpty())
    }
}
