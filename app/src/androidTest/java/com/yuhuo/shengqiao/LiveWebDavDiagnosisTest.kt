package com.yuhuo.shengqiao

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.data.Settings
import com.yuhuo.shengqiao.sync.WebDav
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Explicit opt-in only. Credentials stay on the phone and go only to the configured origin. */
@RunWith(AndroidJUnit4::class)
class LiveWebDavDiagnosisTest {
    @Test fun verifySavedConfigurationWithoutNetwork() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verifySavedDav") == "true")
        val config = Settings(InstrumentationRegistry.getInstrumentation().targetContext).load()
        check(config.configured && config.user.isNotEmpty() && config.password.isNotEmpty()) { "Saved configuration is incomplete" }
        check(URL(config.url).host == args.getString("expectedHost")) { "Saved origin changed" }
        println("DAV_DIAG saved configuration present; expected origin matches; credentials remain encrypted at rest")
    }
    @Test fun inspectConfiguredAuthenticationWithoutPrintingSecrets() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDavDiagnosis") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Settings(context).load()
        check(config.configured) { "No saved WebDAV configuration" }
        val base = URL(config.url)
        check(base.protocol == "https") { "Live diagnosis requires the configured HTTPS origin" }
        fun flags(value: String) = "length=${value.length},edgeWhitespace=${value != value.trim()}," +
            "nonAscii=${value.any { it.code > 127 }},control=${value.any { it.code < 32 || it.code == 127 }}"
        println("DAV_DIAG user ${flags(config.user)}")
        println("DAV_DIAG password ${flags(config.password)}")
        val origin = config.url.trimEnd('/')
        val targets = listOf("root" to "$origin/", "main" to "$origin/" + config.folder.trim('/').split('/').joinToString("/") { WebDav.encode(it) } + "/",
            "notes" to "$origin/" + config.obsidianFolder.trim('/').split('/').joinToString("/") { WebDav.encode(it) } + "/")
        val basic = Credentials.basic(config.user, config.password, Charsets.UTF_8)
        for ((label, address) in targets) {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.setRequestProperty("Authorization", basic)
                println("DAV_DIAG independent_GET $label status=${connection.responseCode}")
            } finally { connection.disconnect() }
        }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        for ((label, address) in targets) {
            for (method in listOf("PROPFIND", "OPTIONS")) {
                val request = Request.Builder().url(address).method(method, null)
                    .header("Depth", "0").header("Authorization", basic).build()
                client.newCall(request).execute().use {
                    println("DAV_DIAG okhttp_$method $label status=${it.code},protocol=${it.protocol}")
                }
            }
        }
        val xml = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        val request = Request.Builder().url(targets.last().second).method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0").header("Authorization", basic).build()
        client.newCall(request).execute().use { println("DAV_DIAG XML_PROPFIND notes status=${it.code}") }
        if (config.password != config.password.trim()) {
            val trimmed = Credentials.basic(config.user.trim(), config.password.trim(), Charsets.UTF_8)
            val probe = Request.Builder().url(targets.last().second).method("PROPFIND", null)
                .header("Depth", "0").header("Authorization", trimmed).build()
            client.newCall(probe).execute().use { println("DAV_DIAG whitespace_trimmed_PROPFIND notes status=${it.code}") }
        }
    }
}
