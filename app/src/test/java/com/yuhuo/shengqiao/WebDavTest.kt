package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.DavConfig
import com.yuhuo.shengqiao.sync.WebDav
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class WebDavTest {
    @Test fun `PUT uses temporary name and verifies moved file before returning success`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", 3))
            val dav = WebDav(DavConfig(server.url("/dav").toString(), "user", "pass", "声桥", true))
            val result = dav.putAtomic("abc/audio.mp3", "abc".byteInputStream(), 3, "audio/mpeg")
            assertEquals(3L, result.bytes)
            assertEquals(WebDav.hex(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray())), result.sha256)
            val put = server.takeRequest()
            assertEquals("PUT", put.method); assertTrue(put.path!!.contains("/.upload-"))
            assertTrue(put.path!!.contains("%E5%A3%B0%E6%A1%A5"))
            assertEquals("abc", put.body.readUtf8())
            assertEquals("Basic dXNlcjpwYXNz", put.getHeader("Authorization"))
            val move = server.takeRequest()
            assertEquals("MOVE", move.method); assertTrue(move.getHeader("Destination")!!.endsWith("/abc/audio.mp3"))
            assertEquals("HEAD", server.takeRequest().method)
        }
    }
    @Test fun `redirect does not forward credentials to a different server`() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/")))
            val dav = WebDav(DavConfig(server.url("/").toString(), "user", "pass", "", true))
            assertThrows(IllegalStateException::class.java) { dav.prepare() }
            assertEquals(0, other.requestCount)
        } }
    }
    @Test fun `partial upload failure does not publish a final file`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(507))
            server.enqueue(MockResponse().setResponseCode(204))
            val dav = WebDav(DavConfig(server.url("/").toString(), folder = "", allowHttp = true))
            assertThrows(IllegalStateException::class.java) { dav.putText("abc/transcript.txt", "正文") }
            assertEquals("PUT", server.takeRequest().method)
            assertEquals("DELETE", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `HTTP and path traversal require correct configuration`() {
        assertThrows(IllegalArgumentException::class.java) { WebDav(DavConfig("http://localhost/")) }
        assertThrows(IllegalArgumentException::class.java) { WebDav(DavConfig("https://localhost/", folder = "../private")) }
        assertThrows(IllegalArgumentException::class.java) { WebDav(DavConfig("https://user:pass@localhost/")) }
        assertNotEquals(DavConfig("https://a/").destination, DavConfig("https://b/").destination)
        assertNotEquals(DavConfig("https://a/", "one").destination, DavConfig("https://a/", "two").destination)
    }
    @Test fun `cancel prevents starting a new network operation`() {
        MockWebServer().use { server ->
            val flag = AtomicBoolean(true)
            val dav = WebDav(DavConfig(server.url("/").toString(), folder = "", allowHttp = true), flag)
            assertThrows(java.io.InterruptedIOException::class.java) { dav.prepare() }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `existing target requires neither root access nor redundant MKCOL`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207))
            WebDav(DavConfig(server.url("/dav").toString(), folder = "/obsidian/小米录音", allowHttp = true)).prepare()
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("/dav/obsidian/%E5%B0%8F%E7%B1%B3%E5%BD%95%E9%9F%B3/", request.path)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `missing shengqiao directory is created automatically`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            WebDav(DavConfig(server.url("/dav").toString(), allowHttp = true)).prepare()
            assertEquals("PROPFIND", server.takeRequest().method)
            val create = server.takeRequest()
            assertEquals("MKCOL", create.method); assertEquals("/dav/shengqiao/", create.path)
        }
    }
    @Test fun `missing nested month creates its missing parent and retries leaf`() {
        MockWebServer().use { server ->
            for (code in listOf(404, 409, 404, 201, 201)) server.enqueue(MockResponse().setResponseCode(code))
            WebDav(DavConfig(server.url("/dav").toString(), folder = "/obsidian/小米录音", allowHttp = true))
                .ensureDirectory("202610")
            val requests = (0 until 5).map { server.takeRequest() }
            assertEquals(listOf("PROPFIND", "MKCOL", "PROPFIND", "MKCOL", "MKCOL"), requests.map { it.method })
            assertTrue(requests[3].path!!.endsWith("/%E5%B0%8F%E7%B1%B3%E5%BD%95%E9%9F%B3/"))
            assertTrue(requests.last().path!!.endsWith("/202610/"))
        }
    }
    @Test fun `401 shows exact method path and authentication scheme without credentials`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"vault\", charset=\"UTF-8\""))
            val error = assertThrows(IllegalStateException::class.java) {
                WebDav(DavConfig(server.url("/dav").toString(), "webdav", "private-password", allowHttp = true)).prepare()
            }
            assertTrue(error.message!!.contains("MKCOL /dav/shengqiao/"))
            assertTrue(error.message!!.contains("401")); assertTrue(error.message!!.contains("Basic"))
            assertFalse(error.message!!.contains("private-password"))
            assertEquals(2, server.requestCount)
        }
    }
}
