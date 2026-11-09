package com.yuhuo.shengqiao.bridge

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import io.github.muntashirakon.adb.android.AdbMdns
import javax.security.auth.x500.X500Principal

/**
 * A separate ADB identity for this installation. The private key remains in Android Keystore.
 * Only addresses belonging to this phone are accepted: pairing must never grant a remote host
 * access merely because it advertised an ADB service on the local network.
 */
internal class EmbeddedAdb private constructor(private val privateKey: PrivateKey, private val certificate: Certificate) :
    AbsAdbConnectionManager() {
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "ShengQiao"

    fun pairLocal(host: String, port: Int, code: String): Boolean {
        requireLocalAddress(host)
        require(port in 1..65535 && code.matches(Regex("\\d{6}"))) { "配对端口或六位配对码不正确" }
        return pair(host, port, code)
    }

    fun connectLocal(host: String, port: Int): Boolean {
        requireLocalAddress(host)
        require(port in 1..65535) { "无线调试端口不正确" }
        return connect(host, port)
    }

    fun discoverAndConnect(context: Context): Boolean {
        val result = AtomicReference<Pair<String, Int>?>()
        val latch = CountDownLatch(1)
        val discovery = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
            val address = host?.hostAddress
            if (address != null && port > 0 && result.compareAndSet(null, address to port)) latch.countDown()
        }
        discovery.start()
        try {
            check(latch.await(12, TimeUnit.SECONDS)) { "未找到本机无线调试端口，请确认已开启无线调试" }
            val endpoint = requireNotNull(result.get())
            // adbd listens locally; loopback keeps this connection on the device and
            // avoids WLAN power saving or routing policies interrupting a self-connection.
            return runCatching { connectLocal("127.0.0.1", endpoint.second) }.getOrDefault(false)
                .let { connected -> if (connected) true else {
                    disconnect()
                    connectLocal(endpoint.first, endpoint.second)
                } }
        } finally {
            discovery.stop()
        }
    }

    companion object {
        private const val ALIAS = "shengqiao.adb.identity.v1"

        fun create(context: Context): EmbeddedAdb {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
                generator.initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setCertificateSubject(X500Principal("CN=ShengQiao"))
                        .build()
                )
                generator.generateKeyPair()
            }
            return EmbeddedAdb(store.getKey(ALIAS, null) as PrivateKey, store.getCertificate(ALIAS))
        }

        private fun requireLocalAddress(host: String) {
            // Resolve numeric addresses only; do not allow a DNS name to change during connection.
            require(host.matches(Regex("[0-9.]+")) || host.contains(':')) { "请输入手机无线调试页面显示的 IP 地址" }
            val address = InetAddress.getByName(host)
            val local = address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().toList()
                .any { network -> network.inetAddresses.toList().any { it == address } }
            require(local) { "只能连接本机的无线调试地址" }
        }
    }
}
