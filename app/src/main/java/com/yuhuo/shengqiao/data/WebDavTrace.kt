package com.yuhuo.shengqiao.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Request summaries only: never Authorization, credentials, challenges, or response bodies. */
class WebDavTrace(context: Context, name: String = "webdav-diagnostic.log") {
    private val file = File(context.filesDir, name)
    @Synchronized fun reset() { file.writeText("") }
    @Synchronized fun append(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
        val line = message.replace('\r', ' ').replace('\n', ' ').take(600)
        val prior = if (file.exists()) file.readLines().takeLast(199) else emptyList()
        file.writeText((prior + "$time $line").joinToString("\n", postfix = "\n"))
    }
    @Synchronized fun read(): String = if (file.exists()) file.readText() else "暂无诊断日志，请先点击“测试连接”。"
}
