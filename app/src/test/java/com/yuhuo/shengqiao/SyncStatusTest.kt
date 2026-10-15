package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.MarkdownExport
import org.junit.Assert.*
import org.junit.Test

class SyncStatusTest {
    private val r = Recording("a".repeat(40), "会议", "/test.mp3", 3, 60, 1, "", "{}", "", "{}", contentSha256 = "b".repeat(64))
    private fun completed(r: Recording) = SyncMark(audio = r.contentSha256, text = r.transcriptHash,
        summary = r.summaryHash, metadata = r.metadataHash, time = 1,
        obsidian = if (r.transcript.isBlank()) "" else MarkdownExport.fingerprint(r, "vault"))
    @Test fun `completed audio without transcript is synced instead of pending`() {
        assertEquals("已同步", SyncStatus.state(r, completed(r), "vault"))
        assertEquals("未同步", SyncStatus.state(r, SyncMark(), "vault"))
        assertEquals("待更新", SyncStatus.state(r, completed(r).copy(metadata = ""), "vault"))
    }
    @Test fun `new transcript invalidates completion until both destinations are updated`() {
        val next = r.copy(transcript = "新转写", transcriptJson = "{\"text\":\"新转写\"}")
        assertEquals("待更新", SyncStatus.state(next, completed(r), "vault"))
        assertEquals("待更新", SyncStatus.state(next, completed(next).copy(obsidian = ""), "vault"))
        assertEquals("已同步", SyncStatus.state(next, completed(next), "vault"))
    }
    @Test fun `errors changed audio and unstable files never inflate synced count`() {
        assertEquals("同步失败", SyncStatus.state(r, completed(r).copy(error = "失败")))
        assertEquals("待更新", SyncStatus.state(r, completed(r).copy(audio = "c".repeat(64))))
        assertNotEquals("已同步", SyncStatus.state(r.copy(skipReason = "仍在录制"), completed(r)))
        assertNotEquals("已同步", SyncStatus.state(r.copy(available = false), completed(r)))
    }
    @Test fun `105 completed recordings including 22 without transcript count as 105`() {
        val records = List(105) { if (it < 22) r else r.copy(transcript = "转写", transcriptJson = "{\"text\":\"转写\"}") }
        assertEquals(22, records.count { it.transcript.isBlank() })
        assertEquals(105, records.count { SyncStatus.state(it, completed(it), "vault") == "已同步" })
    }
}
