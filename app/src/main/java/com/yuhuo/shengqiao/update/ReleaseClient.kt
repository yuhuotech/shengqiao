package com.yuhuo.shengqiao.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String)

class ReleaseClient(
    private val apiUrl: String = "https://api.github.com/repos/yuhuotech/shengqiao/releases/latest",
    private val proxies: List<String> = listOf("https://gh-proxy.com/", "https://gh-proxy.org/", "https://gh.llkk.cc/"),
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
) {
    private fun routes(url: String, useProxy: Boolean) = if (useProxy) proxies.map { it + url } else listOf(url)

    private fun <T> attempt(url: String, useProxy: Boolean, block: (String) -> T): T {
        var last: Exception? = null
        for (route in routes(url, useProxy)) {
            try { return block(route) } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw e
                last = e
            }
        }
        throw IllegalStateException(if (useProxy) "所有更新代理均不可用，请稍后重试或关闭代理" else "无法连接 GitHub，请检查网络或开启代理", last)
    }

    fun latest(useProxy: Boolean): Release = attempt(apiUrl, useProxy) { url ->
        client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ShengQiao-Updater").build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("版本信息为空")
            val bytes = body.byteStream().use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "版本信息过大" }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "不是正式版本" }
            val tag = json.getString("tag_name")
            require(tag.matches(Regex("v[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"))) { "版本号格式不支持" }
            val expectedName = "xiaomi-shengqiao-release-$tag.apk"
            val assets = json.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == expectedName && it.optString("state") == "uploaded" }
            require(matches.size == 1) { "正式版本缺少 APK" }
            val asset = matches.single()
            val expectedUrl = "https://github.com/yuhuotech/shengqiao/releases/download/$tag/$expectedName"
            require(asset.getString("browser_download_url") == expectedUrl) { "更新下载地址异常" }
            val size = asset.getLong("size")
            require(size in 1..100L * 1024 * 1024) { "更新文件大小异常" }
            val digest = asset.getString("digest")
            require(digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) { "更新文件缺少 SHA-256 摘要" }
            Release(tag.removePrefix("v"), expectedUrl, size, digest.substringAfter(':').lowercase())
        }
    }

    fun download(release: Release, useProxy: Boolean, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        attempt(release.apkUrl, useProxy) { url ->
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", "ShengQiao-Updater").build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("下载内容为空")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    target.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                check(count <= release.size) { "下载文件大小异常" }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                progress(count, release.size)
                            }
                        }
                    }
                    check(count == release.size) { "下载不完整" }
                    check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == release.sha256) {
                        "下载文件校验失败"
                    }
                }
            } catch (e: Exception) { target.delete(); throw e }
        }
    }

    companion object {
        fun newer(candidate: String, current: String): Boolean {
            fun parse(s: String): List<Long> {
                require(s.matches(Regex("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"))) { "版本号格式不支持" }
                return s.split('.').map(String::toLong)
            }
            return parse(candidate).zip(parse(current)).firstOrNull { it.first != it.second }
                ?.let { it.first > it.second } ?: false
        }
    }
}
