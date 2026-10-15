package com.yuhuo.shengqiao.bridge

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Runs only inside a Shizuku UserService (shell UID), never in the application process. */
class RecorderBridgeService : IRecorderBridge.Stub() {
    private val lock = Any()
    private val source = "com.android.soundrecorder"
    private val metadataPath = "/storage/emulated/0/Android/data/$source/files/backup/backup.json"

    override fun status(): String = identity {
        JSONObject().put("uid", android.os.Process.myUid()).put("source", source)
            .put("backupAvailable", backupService() != null)
            .put("recorderBusy", RecorderActivity.blockingService(serviceDump()) != null).toString()
    }

    private fun serviceDump(): String {
        val process = ProcessBuilder("dumpsys", "activity", "services", source).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "无法检查录音机状态，已停止导出以保护正在进行的录音" }
        return result
    }

    override fun exportMetadata(): ParcelFileDescriptor = identity {
        synchronized(lock) {
            val uid = android.os.Process.myUid()
            check(uid == 2000 || uid == 0) { "读取服务没有 shell 权限，请重新启动 Shizuku" }
            val services = serviceDump()
            // Avoid interrupting an active recording or playback service.
            check(RecorderActivity.blockingService(services) == null) { "录音机正在录制或播放，请结束后再刷新 / 同步" }
            val service = backupService() ?: error("此系统没有小米本地备份服务")
            val api = Class.forName("miui.app.backup.IBackupManager")
            check(api.getMethod("isServiceIdle").invoke(service) as Boolean) { "系统正在备份，请稍后重试" }
            val observer = Class.forName("miui.app.backup.IBackupServiceStateObserver")
            val acquired = api.getMethod("acquire", observer, IBinder::class.java)
                .invoke(service, null, Binder()) as Boolean
            check(acquired) { "未能取得本地备份权限，请检查 Shizuku 是否以 shell 身份运行" }
            val target = File.createTempFile("shengqiao-meta-", ".bak", File("/data/local/tmp"))
            Os.chmod(target.path, 384) // 0600; snapshots never enter shared user storage.
            try {
                val started = System.currentTimeMillis()
                ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY).use { out ->
                    api.getMethod("backupPackage", ParcelFileDescriptor::class.java,
                        ParcelFileDescriptor::class.java, String::class.java, Int::class.javaPrimitiveType,
                        String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Class.forName("miui.app.backup.IPackageBackupRestoreObserver"))
                        .invoke(service, out, null, source, 3, null, null, false, false, false, false, null)
                }
                val json = File(metadataPath)
                check(target.length() > 1024 && json.isFile && json.lastModified() >= started - 2000) {
                    "录音机未生成新的转写快照；系统版本可能需要适配"
                }
                check(json.length() in 2..128L * 1024 * 1024) { "转写快照大小异常，已停止读取" }
                // Validate shape before the app replaces its previous local snapshot.
                json.inputStream().bufferedReader().use { reader ->
                    val root = JSONObject(reader.readText())
                    check(root.has("mRecordFileList") && root.has("sentenceList")) { "转写导出格式不兼容" }
                }
                ParcelFileDescriptor.open(json, ParcelFileDescriptor.MODE_READ_ONLY).also { json.delete() }
            } catch (e: InvocationTargetException) {
                throw IllegalStateException(e.targetException.message ?: "系统备份导出失败")
            } finally {
                target.delete()
                // Only the snapshot file created by this invocation is removed, never source audio.
                // The open descriptor remains readable by the client after unlinking.
                runCatching { api.getMethod("release", observer).invoke(service, *arrayOfNulls<Any>(1)) }
            }
        }
    }

    override fun openAudio(path: String): ParcelFileDescriptor = identity {
        val file = audioFile(path)
        check(file.isFile) { "原始录音文件不存在" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun fileInfo(path: String): LongArray = identity {
        val file = audioFile(path)
        longArrayOf(if (file.isFile) file.length() else -1L, file.lastModified())
    }

    private fun audioFile(path: String): File {
        val file = File(path).canonicalFile
        val allowed = listOf("/storage/emulated/0/Android/data/$source/files/",
            "/storage/emulated/0/MIUI/sound_recorder/")
        check(allowed.any { file.path.startsWith(it) } && "/.trash/" !in file.path &&
            file.extension.lowercase() in setOf("mp3", "m4a", "wav", "amr", "aac")) { "此路径不是可读取的录音文件" }
        return file
    }

    private fun backupService(): Any? {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "MiuiBackup") as? IBinder ?: return null
        return Class.forName("miui.app.backup.IBackupManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    private fun <T> identity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        try { return block() } finally { Binder.restoreCallingIdentity(token) }
    }

    override fun destroy() { System.exit(0) }
}
