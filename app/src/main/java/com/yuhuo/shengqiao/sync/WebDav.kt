package com.yuhuo.shengqiao.sync

import com.yuhuo.shengqiao.data.DavConfig
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class UploadReceipt(val bytes: Long, val sha256: String, val fileSha1: String = "")
class DavHttpException(val status: Int, val method: String, message: String) : IllegalStateException(message)

class WebDav(private val config: DavConfig, private val cancelled: AtomicBoolean = AtomicBoolean(false),
             private val diagnostic: (String) -> Unit = {}) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val base: String
    private val root: String
    private val authentication = DavAuthentication(config.user, config.password)
    @Volatile private var activeCall: Call? = null
    init {
        val uri = URI(config.url.trim())
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "请输入有效的 WebDAV 地址，账号请填写在独立输入框" }
        require(uri.scheme == "https" || config.allowHttp) { "HTTP 未加密，请使用 HTTPS，或在设置中允许 HTTP" }
        require(config.folder.split('/').none { it == "." || it == ".." || '\\' in it }) { "保存目录不能包含 . 或 .." }
        require('\n' !in config.user && '\r' !in config.user && ':' !in config.user) { "账号包含无效字符" }
        base = uri.toASCIIString().trimEnd('/')
        root = config.folder.split('/').filter { it.isNotBlank() }.joinToString("/") { encode(it) }
    }
    fun abort() { cancelled.set(true); activeCall?.cancel() }
    private fun checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("同步已停止") }
    private fun address(path: String = ""): String {
        require(path.split('/').none { it == "." || it == ".." || '\\' in it }) { "远程文件路径无效" }
        val encoded = path.split('/').joinToString("/") { encode(it) }
        return base + listOf(root, encoded).filter { it.isNotEmpty() }
            .joinToString("/", prefix = if (root.isEmpty() && encoded.isEmpty()) "" else "/")
    }
    private fun request(url: String, method: String, body: RequestBody? = null,
                        headers: Map<String, String> = emptyMap()): Response {
        checkCancelled()
        val builder = Request.Builder().url(url).method(method, body).header("User-Agent", "ShengQiao/0.5.0")
        // Some DAV servers incorrectly send an HTML body for HEAD 404. Do not let
        // those unread bytes contaminate the next HTTP/1.1 response in the pool.
        if (method == "HEAD") builder.header("Connection", "close")
        headers.forEach { (key, value) -> builder.header(key, value) }
        repeat(3) { attempt ->
            checkCancelled()
            val target = builder.build().url
            authentication.authorization(target, method)?.let { builder.header("Authorization", it) }
            val call = client.newCall(builder.build())
            activeCall = call
            // Close the race where a priority request cancelled us just before activeCall was set.
            if (cancelled.get()) { call.cancel(); checkCancelled() }
            val path = displayPath(target.encodedPath)
            val started = System.nanoTime()
            diagnostic("请求 ${attempt + 1}：$method $path")
            val response = try { call.execute() } catch (e: Exception) {
                diagnostic("请求失败：$method $path；${e.javaClass.simpleName}")
                throw e
            }
            val schemes = response.challenges().map { it.scheme }.filter {
                it.matches(Regex("[A-Za-z0-9_-]{1,30}"))
            }.distinct().joinToString("/")
            diagnostic("响应：$method $path → HTTP ${response.code}；${(System.nanoTime() - started) / 1_000_000} ms" +
                if (schemes.isNotEmpty()) "；认证方式 $schemes" else "")
            if (response.code != 401 || attempt == 2 || !authentication.challenge(response)) return response
            // PUT audio is streamed and cannot be replayed. prepare()/HEAD/PROPFIND
            // negotiate Digest first; nonce expiry during upload fails for a safe retry.
            if (body?.isOneShot() == true) return response
            response.close()
        }
        error("认证协商失败")
    }
    private fun failure(response: Response): Nothing {
        val code = response.code
        val path = displayPath(response.request.url.encodedPath)
        val auth = response.challenges().map { it.scheme }.distinct()
            .filter { it.matches(Regex("[A-Za-z0-9_-]{1,30}")) }.joinToString(" / ")
        val detail = when (code) {
        401 -> "认证未通过（HTTP 401）" + if (auth.isNotEmpty()) "，服务器要求 $auth 认证" else "，服务器未说明认证方式"
        403 -> "此操作未获授权（HTTP 403），请检查该路径的操作权限"
        in 300..399 -> "WebDAV 地址发生重定向，请填写重定向后的实际地址"
        404 -> "WebDAV 目录不存在（HTTP 404），请检查服务器地址"
        405, 501 -> "服务器不支持所需的 WebDAV 操作（HTTP $code）"
        409 -> "父目录不存在或目标路径冲突（HTTP 409）"
        507 -> "NAS 存储空间不足（HTTP 507）"
        else -> "WebDAV 请求失败（HTTP $code）"
        }
        throw DavHttpException(code, response.request.method, "${response.request.method} $path：$detail")
    }
    private fun directoryExists(url: String): Boolean {
        request(url.trimEnd('/') + "/", "PROPFIND", headers = mapOf("Depth" to "0")).use {
            if (it.code == 404) return false
            if (it.code !in setOf(200, 207)) failure(it)
            return true
        }
    }
    private fun mkdir(url: String) {
        if (directoryExists(url)) { diagnostic("目录已存在，跳过创建：${displayPath(URI(url).rawPath)}"); return }
        diagnostic("目录不存在，准备创建：${displayPath(URI(url).rawPath)}")
        check(url.trimEnd('/') != base) { "WebDAV 入口不存在，请核对地址" }
        // Inspect the actual target first. Existing shared directories may be readable
        // while MKCOL on them (or access to their parent) is denied by the server.
        val first = request(url.trimEnd('/') + "/", "MKCOL")
        val missingParent = first.use {
            if (it.code in setOf(200, 201, 204)) return
            if (it.code == 405 && directoryExists(url)) return // concurrently created
            if (it.code != 409) failure(it)
            true
        }
        if (missingParent) {
            val parent = url.trimEnd('/').substringBeforeLast('/')
            check(parent.length >= base.length && parent.startsWith(base)) { "无法创建 WebDAV 目录" }
            if (parent != base) mkdir(parent)
            request(url.trimEnd('/') + "/", "MKCOL").use {
                if (it.code == 405 && directoryExists(url)) return
                if (it.code !in setOf(200, 201, 204)) failure(it)
            }
        }
    }
    fun prepare() {
        if (root.isEmpty()) check(directoryExists(base)) { "WebDAV 入口不存在，请核对地址" }
        else mkdir(address())
    }
    fun testConnection(report: (String) -> Unit = {}) {
        val states = linkedMapOf("认证" to "检查中", "目录检查 / 创建" to "待检测", "文件写入" to "待检测",
            "文件移动" to "待检测", "文件读回" to "待检测", "测试文件清理" to "待检测")
        fun publish() = report(states.entries.joinToString("\n") { "${it.key}：${it.value}" })
        var stage = "目录检查 / 创建"
        var prepared = false
        val name = ".shengqiao-test-" + UUID.randomUUID()
        var deleted = false
        var attemptedUpload = false
        try {
            publish()
            diagnostic("阶段：认证与目录检查")
            prepare(); prepared = true
            states["认证"] = if (config.user.isBlank()) "通过（匿名访问）" else "通过（目标目录操作获准）"
            states[stage] = "通过（已存在或已自动创建）"
            stage = "文件写入"; states[stage] = "检查中"; publish()
            attemptedUpload = true
            val data = "声桥连接测试".toByteArray(Charsets.UTF_8)
            putAtomic(name, data.inputStream(), data.size.toLong(), "text/plain; charset=utf-8", phase = { phase ->
                if (phase == "MOVE") {
                    states["文件写入"] = "通过"
                    stage = "文件移动"; states[stage] = "检查中"; publish()
                } else if (phase == "VERIFY") { states["文件移动"] = "通过"; publish() }
            })
            states["文件移动"] = "通过（移动及大小校验完成）"
            check(exists(name)) { "服务器没有保存测试文件" }
            stage = "文件读回"; states[stage] = "检查中"; publish()
            request(address(name), "GET").use {
                if (it.code != 200) failure(it)
                check(it.body!!.byteStream().use { body -> body.readNBytes(128) }
                    .toString(Charsets.UTF_8) == "声桥连接测试") { "服务器读回测试文件不一致" }
            }
            states[stage] = "通过（内容一致）"
            stage = "测试文件清理"; states[stage] = "检查中"; publish()
            delete(name); deleted = true
            check(!exists(name)) { "服务器未删除测试文件" }
            states[stage] = "通过"; publish()
            diagnostic("检测完成：所有阶段通过")
        } catch (e: Exception) {
            if (e is DavHttpException && e.status == 401) {
                states["认证"] = if (prepared) "曾通过，当前操作认证被拒绝（HTTP 401）" else "失败（HTTP 401）"
                states[stage] = if (!prepared && e.method == "PROPFIND") "未通过认证，尚未执行创建" else "未完成（当前操作认证被拒绝）"
            } else {
                if (!prepared) states["认证"] = "未确认，见失败详情"
                states[stage] = "失败"
            }
            for ((key, value) in states.toMap()) if (value == "待检测") states[key] = "未执行"
            report(states.entries.joinToString("\n") { "${it.key}：${it.value}" } + "\n失败详情：${e.message}")
            diagnostic("检测终止：$stage；${e.javaClass.simpleName}")
            throw e
        } finally { if (attemptedUpload && !deleted) runCatching { delete(name) } }
    }
    fun recordDirectory(id: String) {
        require(id.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}")))
        mkdir(address(id))
    }
    fun ensureDirectory(path: String) {
        require(path.isNotBlank()) { "目录路径不能为空" }
        mkdir(address(path))
    }
    fun readManifest(id: String): JSONObject? = readJson("$id/manifest.json")
    fun readJson(path: String): JSONObject? {
        request(address(path), "GET").use { response ->
            if (response.code == 404) return null
            if (response.code != 200) failure(response)
            val bytes = response.body!!.byteStream().use { it.readNBytes(262145) }
            require(bytes.size <= 262144) { "服务器完成标记格式异常" }
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
    fun fileSize(path: String): Long? {
        request(address(path), "HEAD").use {
            if (it.code == 404) return null
            if (it.code !in setOf(200, 204)) failure(it)
            return it.header("Content-Length")?.toLongOrNull()
                ?: error("服务器未返回文件大小，无法安全重命名")
        }
    }
    /** Rename existing bytes only. Never PUT a replacement or overwrite another file. */
    fun moveExisting(from: String, to: String, size: Long) {
        check(exists(from, size)) { "待重命名的远程原文件不存在或大小变化" }
        check(!exists(to)) { "远程新文件名已被占用，已停止重命名" }
        request(address(from), "MOVE", headers = mapOf("Destination" to address(to), "Overwrite" to "F")).use {
            if (it.code !in setOf(200, 201, 204)) failure(it)
        }
        check(exists(to, size) && !exists(from)) { "远程重命名未通过校验，请重新同步恢复" }
    }
    fun exists(path: String, size: Long? = null): Boolean {
        request(address(path), "HEAD").use {
            if (it.code == 404) return false
            if (it.code !in setOf(200, 204)) failure(it)
            return size == null || it.header("Content-Length")?.toLongOrNull() == size
        }
    }
    fun putText(path: String, text: String, type: String = "application/json; charset=utf-8"): UploadReceipt {
        val data = text.toByteArray(Charsets.UTF_8)
        return putAtomic(path, data.inputStream(), data.size.toLong(), type)
    }
    fun putAtomic(path: String, input: InputStream, length: Long, type: String,
                  expectedSha1: String? = null, phase: (String) -> Unit = {}, progress: (Long) -> Unit = {}): UploadReceipt {
        val temporary = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } +
            ".upload-" + UUID.randomUUID()
        var committed = false
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        var count = 0L
        try {
            checkCancelled()
            val body = object : RequestBody() {
                override fun contentType() = type.toMediaType()
                override fun contentLength() = length
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n); sha256.update(buffer, 0, n); sha1.update(buffer, 0, n)
                        count += n; progress(count)
                    }
                    check(count == length) { "录音文件在读取时发生变化，稍后刷新重试" }
                    if (expectedSha1 != null) check(hex(sha1.digest()) == expectedSha1) {
                        "音频摘要与录音机记录不一致，已停止提交此录音，请刷新后重试"
                    }
                }
            }
            phase("PUT")
            request(address(temporary), "PUT", body).use {
                if (it.code !in setOf(200, 201, 204)) failure(it)
            }
            phase("MOVE")
            request(address(temporary), "MOVE", headers = mapOf("Destination" to address(path), "Overwrite" to "T")).use {
                if (it.code !in setOf(200, 201, 204)) failure(it)
                committed = true
            }
            phase("VERIFY")
            check(exists(path, length)) { "服务器文件大小校验未通过" }
            return UploadReceipt(count, hex(sha256.digest()), if (expectedSha1 == null) hex(sha1.digest()) else expectedSha1)
        } finally {
            input.close()
            if (!committed && !cancelled.get()) runCatching { delete(temporary) }
        }
    }
    fun delete(path: String) {
        request(address(path), "DELETE").use {
            if (it.code !in setOf(200, 202, 204, 404)) failure(it)
        }
    }
    companion object {
        private fun displayPath(value: String): String = java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            .replace('\n', ' ').replace('\r', ' ').take(180)
        fun encode(part: String): String = URLEncoder.encode(part, "UTF-8").replace("+", "%20")
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
