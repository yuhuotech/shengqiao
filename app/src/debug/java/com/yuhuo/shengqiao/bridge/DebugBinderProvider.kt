package com.yuhuo.shengqiao.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Device test hook; absent from release builds and restricted to the shell. */
class DebugBinderProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000)
        return Bundle().apply {
            when (method) {
                "reset-test-key" -> {
                    check(requireNotNull(context).packageName.endsWith(".debug"))
                    java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null)
                        deleteEntry("shengqiao.adb.identity.v1")
                    }
                    putBoolean("reset", true)
                }
                "connect" -> putString("status", BridgeClient.connect().status())
                "command" -> putString("command", BridgeClient.usbCommand())
                "exec" -> {
                    val adb = EmbeddedAdb.create(requireNotNull(context))
                    check(adb.discoverAndConnect(requireNotNull(context)))
                    val command = requireNotNull(extras?.getString("command"))
                    val stream = adb.openStream("shell:$command")
                    putString("output", runCatching { stream.openInputStream().bufferedReader().readText() }.getOrElse { it.toString() })
                    stream.close()
                    adb.disconnect()
                }
                "probe" -> {
                    putBoolean("ready", BridgeClient.ready())
                    if (BridgeClient.ready()) putString("status", BridgeClient.get().status())
                }
            }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
