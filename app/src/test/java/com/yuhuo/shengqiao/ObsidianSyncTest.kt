package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class ObsidianSyncTest {
    private fun recording(id: String = "a".repeat(40), title: String = "10.13客满沟通会") =
        Recording(id, title, "/test.mp3", 3, 3600, Instant.parse("2026-10-13T03:00:00Z").toEpochMilli(),
            "[00:00:01] 说话人1\n会议内容 **完整保留**", "{}", "会议摘要", "{}")
    private fun run(insensitive: Boolean = false, body: (RemoteRenameTest.DavDisk, ObsidianSync) -> Unit) {
        MockWebServer().use { server ->
            val disk = RemoteRenameTest.DavDisk(insensitive); server.dispatcher = disk
            val config = DavConfig(server.url("/").toString(), folder = "", allowHttp = true)
            body(disk, ObsidianSync(WebDav(config), config.destination))
        }
    }
    @Test fun `default paths are independent siblings and month uses creation time in China`() {
        val config = DavConfig()
        assertEquals("/shengqiao", config.folder)
        assertEquals("/obsidian/小米录音", config.obsidianConfig.folder)
        val r = recording()
        assertEquals("202610/10.13客满沟通会.md", MarkdownExport.path(r))
        assertEquals("202610", MarkdownExport.month(r.copy(created = r.created / 1000)))
        val prior = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("202611", MarkdownExport.month(r.copy(created = Instant.parse("2026-10-31T16:30:00Z").toEpochMilli())))
        } finally { TimeZone.setDefault(prior) }
        assertTrue(MarkdownExport.render(r).contains(r.transcript))
        assertTrue(MarkdownExport.render(r).contains("2026-10-13 11:00"))
        assertTrue(MarkdownExport.render(r).contains("## 摘要"))
        assertTrue(MarkdownExport.render(r).startsWith("---\n"))
        assertTrue(MarkdownExport.render(r).contains("recorded_at: 2026-10-13T11:00:00\n"))
        assertTrue(MarkdownExport.render(r).contains("recorded_month: \"202610\""))
        assertFalse(MarkdownExport.render(r.copy(created = 0)).contains("recorded_at:"))
    }
    @Test fun `rename moves original note and repeated sync does not write`() = run { disk, sync ->
        val r = recording(); sync.upload(r)
        assertTrue(disk.has("/202610/10.13客满沟通会.md"))
        val renamed = r.copy(title = "新的会议 #100%")
        sync.upload(renamed)
        assertFalse(disk.has("/202610/10.13客满沟通会.md"))
        assertTrue(disk.has("/202610/新的会议 #100%.md"))
        assertEquals(1, disk.files.keys.count { it.endsWith(".md") })
        assertTrue(disk.requests.any { it.first == "MOVE" && it.second.endsWith("/10.13客满沟通会.md") })
        val puts = disk.requests.count { it.first == "PUT" }
        sync.upload(renamed)
        assertEquals(puts, disk.requests.count { it.first == "PUT" })
    }
    @Test fun `same month and title never overwrite different recording or existing vault note`() = run { disk, sync ->
        val r = recording()
        disk.put("/202610/10.13客满沟通会.md", "我的手写笔记")
        sync.upload(r)
        sync.upload(recording("b".repeat(40)))
        assertEquals("我的手写笔记", String(disk.files["/202610/10.13客满沟通会.md"]!!))
        assertEquals(3, disk.files.keys.count { it.endsWith(".md") })
        val puts = disk.requests.count { it.first == "PUT" }
        sync.upload(r)
        assertEquals(puts, disk.requests.count { it.first == "PUT" })
    }
    @Test fun `lost rename response recovers and applies the latest title without duplicates`() = run { disk, sync ->
        val r = recording(); sync.upload(r)
        disk.failAfterMarkdownMove = true
        assertThrows(IllegalStateException::class.java) { sync.upload(r.copy(title = "第一次改名")) }
        assertTrue(disk.has("/.shengqiao/${r.id}.rename.json"))
        sync.upload(r.copy(title = "第二次改名"))
        assertTrue(disk.has("/202610/第二次改名.md"))
        assertEquals(1, disk.files.keys.count { it.endsWith(".md") })
        assertFalse(disk.files.keys.any { it.endsWith(".moving") || it.endsWith(".rename.json") })
    }
    @Test fun `lost upload response retries original reserved path instead of creating a copy`() = run { disk, sync ->
        val r = recording(); disk.failAfterMarkdownCommit = true
        assertThrows(IllegalStateException::class.java) { sync.upload(r) }
        sync.upload(r)
        assertTrue(disk.has("/202610/10.13客满沟通会.md"))
        assertEquals(1, disk.files.keys.count { it.endsWith(".md") })
    }
    @Test fun `changed creation month moves note and later transcript edits update same file`() = run { disk, sync ->
        val r = recording(); sync.upload(r)
        val later = r.copy(created = Instant.parse("2026-11-01T03:00:00Z").toEpochMilli(), transcript = "更新后的全文")
        sync.upload(later)
        assertFalse(disk.has("/202610/10.13客满沟通会.md"))
        assertTrue(String(disk.files["/202611/10.13客满沟通会.md"]!!).contains("更新后的全文"))
        assertEquals(1, disk.files.keys.count { it.endsWith(".md") })
        val destination = "target"
        assertNotEquals(MarkdownExport.fingerprint(r, destination), MarkdownExport.fingerprint(later, destination))
        assertNotEquals(MarkdownExport.fingerprint(r, destination), MarkdownExport.fingerprint(r, "other vault"))
    }
    @Test fun `case-only note rename works on case insensitive NAS`() = run(true) { disk, sync ->
        val r = recording(title = "Meeting"); sync.upload(r)
        sync.upload(r.copy(title = "MEETING"))
        assertEquals(1, disk.files.keys.count { it.endsWith(".md") })
        assertTrue(String(disk.files["/202610/meeting.md"]!!).contains("\n# MEETING\n"))
    }
    @Test fun `no transcript does not create empty vault note`() = run { disk, sync ->
        assertEquals("", sync.upload(recording().copy(transcript = "")))
        assertTrue(disk.files.isEmpty())
        assertTrue(disk.requests.isEmpty())
    }
}
