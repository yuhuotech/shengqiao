package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.update.Release
import com.yuhuo.shengqiao.update.ReleaseClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class ReleaseClientTest {
    private val tag = "v1.2.3"
    private val name = "xiaomi-shengqiao-release-$tag.apk"
    private val apkUrl = "https://github.com/yuhuotech/shengqiao/releases/download/$tag/$name"
    private val contents = "apk-content".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(contents).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun metadata(url: String = apkUrl) = """{"tag_name":"$tag","draft":false,"prerelease":false,"assets":[{"name":"$name","state":"uploaded","browser_download_url":"$url","size":${contents.size},"digest":"sha256:$hash"}]}"""

    @Test fun `checks proxies in order and validates release metadata`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setBody(metadata()))
            val client = ReleaseClient("https://api.github.com/test", listOf(server.url("/one/").toString(), server.url("/two/").toString()))
            val release = client.latest(true)
            assertEquals("1.2.3", release.version)
            assertEquals(hash, release.sha256)
            assertTrue(server.takeRequest().path!!.startsWith("/one/"))
            assertTrue(server.takeRequest().path!!.startsWith("/two/"))
        }
    }

    @Test fun `rejects altered asset url and malformed metadata`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(metadata("https://attacker.example/app.apk")))
            val client = ReleaseClient(server.url("/latest").toString())
            assertThrows(IllegalStateException::class.java) { client.latest(false) }
        }
    }

    @Test fun `invalid proxy metadata falls back to next proxy`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>proxy challenge</html>"))
            server.enqueue(MockResponse().setBody(metadata()))
            val client = ReleaseClient("https://api.github.com/test", listOf(server.url("/one/").toString(), server.url("/two/").toString()))
            assertEquals("1.2.3", client.latest(true).version)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `retries a corrupt download on the second proxy`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("bad"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(contents)))
            val target = Files.createTempFile("update-test", ".apk").toFile()
            try {
                val client = ReleaseClient(proxies = listOf(server.url("/one/").toString(), server.url("/two/").toString()))
                client.download(Release("1.2.3", apkUrl, contents.size.toLong(), hash), true, target)
                assertArrayEquals(contents, target.readBytes())
                assertEquals(2, server.requestCount)
            } finally { target.delete() }
        }
    }

    @Test fun `direct mode does not use proxies and failed download is removed`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val target = Files.createTempFile("update-test", ".apk").toFile()
            val client = ReleaseClient(proxies = listOf("https://unused.invalid/"))
            assertThrows(IllegalStateException::class.java) {
                client.download(Release("1.2.3", server.url("/apk").toString(), contents.size.toLong(), hash), false, target)
            }
            assertFalse(target.exists())
            assertEquals("/apk", server.takeRequest().path)
        }
    }

    @Test fun `versions are compared numerically`() {
        assertTrue(ReleaseClient.newer("1.10.0", "1.9.9"))
        assertFalse(ReleaseClient.newer("1.2.3", "1.2.3"))
        assertFalse(ReleaseClient.newer("0.9.9", "1.0.0"))
        assertThrows(IllegalArgumentException::class.java) { ReleaseClient.newer("1.2.3-beta", "1.0.0") }
    }
}
