package com.yuhuo.shengqiao.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.yuhuo.shengqiao.BuildConfig

/** Receives the shell service Binder only from Android's shell or root UID. */
class EmbeddedBinderProvider : ContentProvider() {
    private val clientToken = Binder()
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        check(caller == 2000 || caller == 0) { "仅允许本机调试服务连接" }
        require(method == "attach" && extras?.getInt("version") == BuildConfig.VERSION_CODE) { "读取服务版本不匹配" }
        val binder = requireNotNull(extras.getBinder("bridge"))
        BridgeClient.accept(binder)
        return Bundle().apply { putBinder("client", clientToken) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
