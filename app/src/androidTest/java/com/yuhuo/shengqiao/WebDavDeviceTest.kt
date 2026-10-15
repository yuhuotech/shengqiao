package com.yuhuo.shengqiao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuhuo.shengqiao.data.DavConfig
import com.yuhuo.shengqiao.data.hash
import com.yuhuo.shengqiao.sync.WebDav
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavDeviceTest {
    @Test fun unicodeAtomicUploadAgainstRealWebDav() {
        val dav = WebDav(DavConfig("http://127.0.0.1:18765/", "test", "test-local-only", "协议测试", true, false))
        dav.testConnection()
        val id = "d".repeat(40)
        dav.recordDirectory(id)
        val text = "[00:00:01] 说话人1\n这是手机端 WebDAV 完整上传验证。"
        val receipt = dav.putText("$id/transcript.txt", text, "text/plain; charset=utf-8")
        assertEquals(hash(text), receipt.sha256)
        assertTrue(dav.exists("$id/transcript.txt", text.toByteArray().size.toLong()))
        dav.putText("$id/manifest.json", JSONObject().put("sourceSha1", id).put("textHash", hash(text)).toString())
        assertEquals(hash(text), dav.readManifest(id)!!.getString("textHash"))
    }
    @Test fun badCredentialsDoNotReportSuccess() {
        val dav = WebDav(DavConfig("http://127.0.0.1:18765/", "test", "wrong", "协议测试", true, false))
        try { dav.prepare(); fail("Expected authentication failure") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("401")) }
    }
}
