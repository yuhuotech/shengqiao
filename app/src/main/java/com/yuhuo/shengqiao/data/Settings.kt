package com.yuhuo.shengqiao.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DavConfig(val url: String = "", val user: String = "", val password: String = "",
                     val folder: String = "/shengqiao", val allowHttp: Boolean = false, val wifiOnly: Boolean = true,
                     val obsidianFolder: String = "/obsidian/小米录音") {
    val destination: String get() = hash(url.trimEnd('/') + "\n" + user + "\n" + folder.trim('/'))
    val configured: Boolean get() = url.isNotBlank()
    val obsidianConfig: DavConfig get() = copy(folder = obsidianFolder)
    val obsidianDestination: String get() = obsidianConfig.destination
}

class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("shengqiao-settings", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("shengqiao-settings",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): DavConfig {
        val encrypted = prefs.getString("dav", null) ?: return DavConfig()
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val o = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val configured = o.optString("url").isNotBlank()
        return DavConfig(o.optString("url"), o.optString("user"), o.optString("password"),
            if (configured) o.optString("folder", "/shengqiao") else "/shengqiao",
            o.optBoolean("allowHttp"), o.optBoolean("wifiOnly", true),
            o.optString("obsidianFolder", "/obsidian/小米录音"))
    }
    fun save(config: DavConfig) {
        val o = JSONObject().put("url", config.url).put("user", config.user)
            .put("password", config.password).put("folder", config.folder)
            .put("allowHttp", config.allowHttp).put("wifiOnly", config.wifiOnly)
            .put("obsidianFolder", config.obsidianFolder)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(o.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("dav", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) {
            "服务器配置保存失败"
        }
    }
    fun getInfo(name: String, fallback: String = ""): String = prefs.getString(name, fallback) ?: fallback
    fun info(name: String, value: String) { prefs.edit().putString(name, value).apply() }
}
