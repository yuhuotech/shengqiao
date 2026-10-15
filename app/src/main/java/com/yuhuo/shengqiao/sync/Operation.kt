package com.yuhuo.shengqiao.sync

data class TaskState(val running: Boolean = false, val starting: Boolean = false,
    val message: String = "", val failed: Boolean = false, val currentId: String = "",
    val progress: Int = 0, val total: Int = 0, val bytes: Long = 0, val byteTotal: Long = 0) {
    val busy get() = running || starting
}
data class ItemResult(val message: String, val failed: Boolean, val time: Long = System.currentTimeMillis())

object Operation {
    @Volatile var scan = TaskState()
    @Volatile var sync = TaskState()
    @Volatile var last = TaskState()
    @Volatile var queuedIds = emptySet<String>()
    @Volatile var manualIds = emptySet<String>()
    @Volatile var results = emptyMap<String, ItemResult>()
    val running get() = scan.running || sync.running
    val starting get() = scan.starting || sync.starting
    private val foreground get() = if (sync.busy) sync else if (scan.busy) scan else last
    val failed get() = foreground.failed
    val message get() = listOfNotNull(scan.takeIf { it.busy }?.message, sync.takeIf { it.busy }?.message)
        .joinToString("\n").ifEmpty { last.message }
    val currentId get() = sync.currentId
    val action get() = if (sync.busy) "sync" else "scan"
    val byteTotal get() = sync.byteTotal
    val bytes get() = sync.bytes
    fun prepare(action: String, id: String?) {
        if (action == "scan") scan = scan.copy(starting = true, failed = false, message = "正在准备刷新录音…")
        else {
            if (id != null) { manualIds = manualIds + id; queuedIds = queuedIds + id; results = results - id }
            if (!sync.busy) sync = TaskState(starting = true, message = if (id == null) "正在准备同步…" else "正在准备单条同步…")
        }
    }
}
