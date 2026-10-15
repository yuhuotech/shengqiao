package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.DavConfig
import com.yuhuo.shengqiao.sync.DavAuthentication
import com.yuhuo.shengqiao.sync.WebDav
import okhttp3.Challenge
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DavAuthenticationTest {
    @Test fun `RFC 2617 MD5 digest matches published response`() {
        val challenge = Challenge("Digest", mapOf("realm" to "testrealm@host.com", "nonce" to "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            "qop" to "auth", "opaque" to "5ccc069c403ebaf9f0171e9517f40e41"))
        val header = DavAuthentication.digestHeader(challenge, "Mufasa", "Circle Of Life", "GET", "/dir/index.html", 1, "0a4f113b")
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("nc=00000001"))
    }
    @Test fun `RFC 7616 SHA256 digest matches published response`() {
        val challenge = Challenge("Digest", mapOf("realm" to "http-auth@example.org", "algorithm" to "SHA-256",
            "nonce" to "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", "qop" to "auth, auth-int"))
        val header = DavAuthentication.digestHeader(challenge, "Mufasa", "Circle of Life", "GET", "/dir/index.html", 1,
            "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ")
        assertTrue(header.contains("response=\"753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1\""))
    }

    private fun verify(request: RecordedRequest, password: String, nonce: String): Boolean {
        val header = request.getHeader("Authorization") ?: return false
        if (!header.startsWith("Digest ")) return false
        val params = Regex("(\\w+)=(?:\"([^\"]*)\"|([^, ]+))").findAll(header).associate {
            it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        if (params["nonce"] != nonce || params["uri"] != request.path || params["qop"] != "auth") return false
        val algorithm = params["algorithm"] ?: return false
        fun h(s: String) = WebDav.hex(MessageDigest.getInstance(algorithm.removeSuffix("-SESS")).digest(s.toByteArray()))
        val ha1 = h("webdav:test:$password").let {
            if (algorithm.endsWith("-SESS")) h("$it:$nonce:${params["cnonce"]}") else it
        }
        val ha2 = h("${request.method}:${request.path}")
        return params["response"] == h("$ha1:$nonce:${params["nc"]}:${params["cnonce"]}:auth:$ha2")
    }

    @Test fun `Digest-only WebDAV negotiates and uploads moves reads and deletes with correct per-method digest`() {
        for (algorithm in listOf("MD5", "SHA-256", "MD5-SESS", "SHA-256-SESS")) {
            MockWebServer().use { server ->
                val disk = RemoteRenameTest.DavDisk()
                var nonce = "initial"; var unauthorized = 0; var valid = 0
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (!verify(request, "correct", nonce)) {
                            unauthorized++
                            return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate",
                                "Digest realm=\"test\", nonce=\"$nonce\", algorithm=$algorithm, qop=\"auth\"")
                        }
                        valid++
                        return disk.dispatch(request)
                    }
                }
                val dav = WebDav(DavConfig(server.url("/dav").toString(), "webdav", "correct", allowHttp = true))
                dav.testConnection()
                assertEquals(1, unauthorized)
                assertTrue(valid > 5)
                assertTrue(disk.files.isEmpty())
                nonce = "renewed"
                dav.prepare()
                assertEquals(2, unauthorized)
            }
        }
    }
    @Test fun `wrong Digest password fails without attempting creation or looping`() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Digest realm=\"test\", nonce=\"fixed\", qop=\"auth\", algorithm=MD5")
            }
            val error = assertThrows(IllegalStateException::class.java) {
                WebDav(DavConfig(server.url("/").toString(), "webdav", "wrong", allowHttp = true)).prepare()
            }
            assertTrue(error.message!!.contains("401"))
            assertEquals(2, server.requestCount)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
        }
    }
    @Test fun `streamed upload rejected by auth is never replayed`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate",
                "Digest realm=\"test\", nonce=\"fixed\", qop=\"auth\", algorithm=MD5"))
            server.enqueue(MockResponse().setResponseCode(204))
            val dav = WebDav(DavConfig(server.url("/").toString(), "webdav", "correct", folder = "", allowHttp = true))
            assertThrows(IllegalStateException::class.java) { dav.putText("note.md", "hello") }
            assertEquals("PUT", server.takeRequest().method)
            assertEquals("DELETE", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `Digest credentials are never forwarded to redirect origin`() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate",
                "Digest realm=\"test\", nonce=\"fixed\", qop=\"auth\""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/")))
            val dav = WebDav(DavConfig(server.url("/").toString(), "webdav", "correct", allowHttp = true))
            assertThrows(IllegalStateException::class.java) { dav.prepare() }
            assertEquals(0, other.requestCount)
            assertEquals(2, server.requestCount)
        } }
    }
}
