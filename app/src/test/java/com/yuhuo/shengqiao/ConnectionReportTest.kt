package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.DavConfig
import com.yuhuo.shengqiao.sync.WebDav
import okhttp3.Credentials
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionReportTest {
    @Test fun `authentication rejection reports creation and all file stages as not executed`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"WebDAV\", charset=\"UTF-8\""))
            val logs = mutableListOf<String>(); val reports = mutableListOf<String>()
            val dav = WebDav(DavConfig(server.url("/").toString(), "user", "secret-value", allowHttp = true), diagnostic = logs::add)
            assertThrows(IllegalStateException::class.java) { dav.testConnection(reports::add) }
            assertTrue(reports.last().contains("认证：失败（HTTP 401）"))
            assertTrue(reports.last().contains("目录检查 / 创建：未通过认证，尚未执行创建"))
            assertTrue(reports.last().contains("文件写入：未执行"))
            assertTrue(reports.last().contains("PROPFIND /shengqiao/"))
            assertEquals(1, server.requestCount)
            assertTrue(logs.any { "HTTP 401" in it && "Basic" in it })
            assertFalse(logs.joinToString().contains("secret-value"))
            assertFalse(logs.joinToString().contains(Credentials.basic("user", "secret-value")))
        }
    }
    @Test fun `denied directory creation is distinguished from authentication rejection`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(403))
            var report = ""
            val logs = mutableListOf<String>()
            val dav = WebDav(DavConfig(server.url("/").toString(), "user", "secret-value", allowHttp = true), diagnostic = logs::add)
            assertThrows(IllegalStateException::class.java) { dav.testConnection { report = it } }
            assertTrue(report.contains("认证：未确认"))
            assertTrue(report.contains("目录检查 / 创建：失败"))
            assertTrue(report.contains("MKCOL /shengqiao/"))
            assertTrue(report.contains("HTTP 403"))
            assertTrue(report.contains("文件写入：未执行"))
            assertEquals(2, server.requestCount)
            assertTrue(logs.any { "目录不存在，准备创建" in it })
        }
    }
    @Test fun `MOVE denial preserves passed auth and write stages`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            var report = ""
            val dav = WebDav(DavConfig(server.url("/").toString(), "user", "secret-value", allowHttp = true))
            assertThrows(IllegalStateException::class.java) { dav.testConnection { report = it } }
            assertTrue(report.contains("认证：通过"))
            assertTrue(report.contains("目录检查 / 创建：通过"))
            assertTrue(report.contains("文件写入：通过"))
            assertTrue(report.contains("文件移动：失败"))
            assertTrue(report.contains("文件读回：未执行"))
            assertTrue(report.contains("MOVE /shengqiao/"))
        }
    }
    @Test fun `successful report contains every passed phase and trace contains no credentials`() {
        MockWebServer().use { server ->
            server.dispatcher = RemoteRenameTest.DavDisk()
            val reports = mutableListOf<String>(); val logs = mutableListOf<String>()
            WebDav(DavConfig(server.url("/").toString(), "user", "secret-value", allowHttp = true), diagnostic = logs::add)
                .testConnection(reports::add)
            for (stage in listOf("认证", "目录检查 / 创建", "文件写入", "文件移动", "文件读回", "测试文件清理"))
                assertTrue(reports.last().contains("$stage：通过"))
            assertTrue(logs.any { "PUT " in it && "HTTP 201" in it })
            assertTrue(logs.any { "MOVE " in it && "HTTP 201" in it })
            assertTrue(logs.any { "GET " in it && "HTTP 200" in it })
            assertFalse(logs.joinToString().contains("secret-value"))
            assertFalse(logs.joinToString().contains("Authorization"))
        }
    }
}
