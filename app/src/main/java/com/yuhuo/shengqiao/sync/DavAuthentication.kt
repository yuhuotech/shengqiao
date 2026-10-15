package com.yuhuo.shengqiao.sync

import okhttp3.Challenge
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Response
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Per-client, same-origin credentials. No redirects and no replay of streamed PUT bodies. */
internal class DavAuthentication(private val user: String, private val password: String,
                                 private val cnonce: () -> String = { UUID.randomUUID().toString().replace("-", "") }) {
    private var digest: Challenge? = null
    private var count = 0
    private var basicCharset: Charset = Charsets.UTF_8

    @Synchronized fun authorization(url: HttpUrl, method: String): String? {
        if (user.isEmpty()) return null
        val challenge = digest ?: return Credentials.basic(user, password, basicCharset)
        count++
        return digestHeader(challenge, user, password, method,
            url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""), count, cnonce())
    }

    @Synchronized fun challenge(response: Response): Boolean {
        if (user.isEmpty()) return false
        val choices = response.challenges()
        val selected = choices.firstOrNull { it.scheme.equals("Digest", true) && supported(it) }
        if (selected != null) {
            val prior = digest
            if (prior != null && prior.authParams == selected.authParams &&
                selected.authParams["stale"] != "true") return false
            digest = selected; count = 0
            return true
        }
        if (digest == null) {
            val basic = choices.firstOrNull { it.scheme.equals("Basic", true) } ?: return false
            val charset = basic.charset
            if (charset != basicCharset) { basicCharset = charset; return true }
        }
        return false
    }

    companion object {
        private fun algorithm(c: Challenge) = c.authParams["algorithm"].orEmpty().ifBlank { "MD5" }.uppercase(Locale.ROOT)
        private fun supported(c: Challenge): Boolean = algorithm(c) in setOf(
            "MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS", "SHA-512-256", "SHA-512-256-SESS") &&
            c.authParams["nonce"] != null && c.realm != null &&
            (c.authParams["qop"] == null || c.authParams["qop"]!!.split(',').any { it.trim().equals("auth", true) })

        internal fun digestHeader(c: Challenge, user: String, password: String, method: String,
                                  uri: String, count: Int, cnonce: String): String {
            require(supported(c)) { "服务器的 Digest 算法或 qop 暂不支持" }
            val algorithm = algorithm(c)
            val name = algorithm.removeSuffix("-SESS").let { if (it == "SHA-512-256") "SHA-512/256" else it }
            val charset = if (c.authParams["charset"].equals("UTF-8", true)) Charsets.UTF_8 else Charsets.ISO_8859_1
            fun h(value: String) = WebDav.hex(MessageDigest.getInstance(name).digest(value.toByteArray(charset)))
            fun quoted(value: String): String {
                require(value.none { it.code < 32 || it.code == 127 }) { "认证信息包含无效控制字符" }
                return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
            val realm = c.realm!!; val nonce = c.authParams.getValue("nonce")!!
            val qop = if (c.authParams["qop"] == null) null else "auth"
            val nc = "%08x".format(Locale.ROOT, count)
            val ha1 = h("$user:$realm:$password").let {
                if (algorithm.endsWith("-SESS")) h("$it:$nonce:$cnonce") else it
            }
            val ha2 = h("$method:$uri")
            val result = if (qop == null) h("$ha1:$nonce:$ha2") else h("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
            val userHash = c.authParams["userhash"].equals("true", true)
            val fields = mutableListOf<String>()
            if (userHash) fields += "username=${quoted(h("$user:$realm"))}"
            else if (user.any { it.code > 127 }) {
                require(charset == Charsets.UTF_8) { "Digest 非 ASCII 账号需要服务器支持 UTF-8" }
                fields += "username*=UTF-8''${WebDav.encode(user).replace("*", "%2A")}"
            } else fields += "username=${quoted(user)}"
            fields += listOf("realm=${quoted(realm)}", "nonce=${quoted(nonce)}", "uri=${quoted(uri)}",
                "algorithm=$algorithm", "response=${quoted(result)}")
            c.authParams["opaque"]?.let { fields += "opaque=${quoted(it)}" }
            if (qop != null) fields += listOf("qop=$qop", "nc=$nc", "cnonce=${quoted(cnonce)}")
            else if (algorithm.endsWith("-SESS")) fields += "cnonce=${quoted(cnonce)}"
            if (userHash) fields += "userhash=true"
            return "Digest " + fields.joinToString(", ")
        }
    }
}
