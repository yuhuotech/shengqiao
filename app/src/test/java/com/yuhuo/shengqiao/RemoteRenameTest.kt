package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.DavConfig
import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.sync.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class RemoteRenameTest {
    private val id = "a".repeat(40)
    private fun names(title: String) = RemoteNames.files(Recording(id, title, "/audio.mp3", 3, 1, 1, "", "", "", ""))

    class DavDisk(val insensitive: Boolean = false) : Dispatcher() {
        val files = ConcurrentHashMap<String, ByteArray>()
        val requests = mutableListOf<Pair<String, String>>()
        val uploadedBodies = mutableListOf<String>()
        val directories = mutableSetOf("/")
        var failAfterMove = false
        var failAfterMarkdownMove = false
        var failAfterMarkdownCommit = false
        private fun key(path: String) = if (insensitive) path.lowercase() else path
        fun put(path: String, value: String) { files[key(path)] = value.toByteArray() }
        fun has(path: String) = files.containsKey(key(path))
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = key(request.requestUrl!!.toUri().path)
            val method = request.method!!
            synchronized(requests) { requests += method to path }
            val response = MockResponse()
            return when (method) {
                "GET" -> files[path]?.let { response.setBody(String(it)) } ?: response.setResponseCode(404)
                "HEAD" -> files[path]?.let { response.setHeader("Content-Length", it.size) } ?: response.setResponseCode(404)
                "PUT" -> {
                    val body = request.body.readByteArray()
                    uploadedBodies += String(body); files[path] = body; response.setResponseCode(201)
                }
                "MOVE" -> {
                    val to = key(URI(request.getHeader("Destination")!!).path)
                    when {
                        !files.containsKey(path) -> response.setResponseCode(404)
                        files.containsKey(to) && request.getHeader("Overwrite") == "F" -> response.setResponseCode(412)
                        else -> {
                            files[to] = files.remove(path)!!
                            if (failAfterMarkdownMove && to.endsWith(".moving")) {
                                failAfterMarkdownMove = false; response.setResponseCode(500)
                            } else if (failAfterMarkdownCommit && to.endsWith(".md")) {
                                failAfterMarkdownCommit = false; response.setResponseCode(500)
                            } else if (failAfterMove && "/.rename-" in to) {
                                failAfterMove = false; response.setResponseCode(500)
                            } else response.setResponseCode(201)
                        }
                    }
                }
                "DELETE" -> { files.remove(path); response.setResponseCode(204) }
                "PROPFIND" -> response.setResponseCode(if (path.trimEnd('/') + "/" in directories) 207 else 404)
                "MKCOL" -> {
                    directories += path.trimEnd('/') + "/"
                    response.setResponseCode(201)
                }
                else -> response.setResponseCode(201)
            }
        }
    }

    private fun fixture(disk: DavDisk, legacy: Boolean = false, title: String = "原来的录音"): JSONObject {
        val files = if (legacy) JSONObject().put("audio", "audio.mp3").put("transcriptText", "transcript.txt")
            .put("transcriptJson", "transcript.json").put("summary", "summary.txt") else names(title)
        for (key in files.keys()) disk.put("/$id/${files.getString(key)}", "payload-$key")
        val manifest = JSONObject().put("schemaVersion", if (legacy) 1 else 2).put("sourceSha1", id).put("title", title)
            .put("audio", JSONObject().put("file", files.getString("audio")))
        if (!legacy) manifest.put("files", files)
        disk.put("/$id/manifest.json", manifest.toString())
        return manifest
    }

    private fun run(disk: DavDisk = DavDisk(), body: (DavDisk, WebDav) -> Unit) {
        MockWebServer().use { server ->
            server.dispatcher = disk
            body(disk, WebDav(DavConfig(server.url("/").toString(), folder = "", allowHttp = true)))
        }
    }

    @Test fun `renames audio and transcripts using MOVE without uploading payloads`() = run { disk, dav ->
        val old = fixture(disk)
        val before = disk.files.filterKeys { !it.endsWith("manifest.json") }.values.map { String(it) }.sorted()
        val desired = names("新的录音 #100%")
        RemoteRename(dav, id).rename(old, desired, "新的录音 #100%")
        assertFalse(disk.has("/$id/原来的录音.mp3"))
        for (key in desired.keys()) assertTrue(disk.has("/$id/${desired.getString(key)}"))
        assertEquals(before, disk.files.filterKeys { !it.endsWith("manifest.json") }.values.map { String(it) }.sorted())
        assertFalse(disk.requests.any { it.first == "PUT" && !it.second.contains("/.upload-") })
        assertFalse(disk.uploadedBodies.any { it in before })
        assertEquals(5, disk.files.size)
        val count = disk.requests.count { it.first == "PUT" }
        RemoteRename(dav, id).rename(dav.readManifest(id)!!, desired, "新的录音 #100%")
        assertEquals(count, disk.requests.count { it.first == "PUT" })
    }

    @Test fun `lost MOVE response recovers before second rename without duplicates`() = run { disk, dav ->
        val old = fixture(disk)
        disk.failAfterMove = true
        assertThrows(IllegalStateException::class.java) { RemoteRename(dav, id).rename(old, names("第一次"), "第一次") }
        assertTrue(disk.has("/$id/.rename.json"))
        RemoteRename(dav, id).recover()
        RemoteRename(dav, id).rename(dav.readManifest(id)!!, names("第二次"), "第二次")
        assertEquals(5, disk.files.size)
        assertTrue(disk.has("/$id/第二次.mp3"))
        assertFalse(disk.files.keys.any { "/.rename" in it || "/.upload-" in it })
    }

    @Test fun `existing conflicting target stops without touching original or target`() = run { disk, dav ->
        val old = fixture(disk)
        disk.put("/$id/新的.mp3", "someone else's file")
        assertThrows(IllegalStateException::class.java) { RemoteRename(dav, id).rename(old, names("新的"), "新的") }
        assertTrue(disk.has("/$id/原来的录音.mp3"))
        assertEquals("someone else's file", String(disk.files["/$id/新的.mp3"]!!))
        assertFalse(disk.requests.any { it.first == "MOVE" || it.first == "PUT" })
    }

    @Test fun `case-only rename works on a case insensitive NAS`() = run(DavDisk(true)) { disk, dav ->
        val old = fixture(disk, title = "Meeting")
        RemoteRename(dav, id).rename(old, names("MEETING"), "MEETING")
        assertEquals(5, disk.files.size)
        assertEquals("MEETING.mp3", dav.readManifest(id)!!.getJSONObject("audio").getString("file"))
    }

    @Test fun `v1 fixed filenames migrate using existing bytes`() = run { disk, dav ->
        val old = fixture(disk, legacy = true)
        RemoteRename(dav, id).rename(old, names("迁移后"), "迁移后")
        assertEquals(5, disk.files.size)
        assertFalse(disk.has("/$id/audio.mp3"))
        assertFalse(disk.has("/$id/transcript.txt"))
        assertTrue(disk.has("/$id/迁移后.mp3"))
    }

    @Test fun `unsafe and long titles remain single unique UTF8 path segments`() {
        val first = RemoteNames.stem("../a/b:*? ")
        assertFalse(first.contains('/')); assertFalse(first.contains('*'))
        assertNotEquals(RemoteNames.stem("a/b"), RemoteNames.stem("a\\b"))
        val long = RemoteNames.stem("录音😀".repeat(200))
        assertTrue(long.toByteArray().size < 200)
        assertTrue(long.toByteArray().toString(Charsets.UTF_8).none { it == '\uFFFD' })
        val boundary = RemoteNames.stem("a".repeat(149) + "😀")
        assertEquals("a".repeat(149), boundary.substringBeforeLast('-'))
    }
}
