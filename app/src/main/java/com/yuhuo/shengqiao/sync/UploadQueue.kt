package com.yuhuo.shengqiao.sync

import java.util.concurrent.atomic.AtomicBoolean

/** Controlled on the service's main thread; cancellation is observed by the upload worker. */
class UploadQueue {
    class Ticket(val id: String, @Volatile var manual: Boolean, var batch: Boolean) {
        val cancelled = AtomicBoolean(false)
        var preempted = false
        @Volatile var abort: (() -> Unit)? = null
        fun cancel(preempt: Boolean = false) {
            preempted = preempt; cancelled.set(true); abort?.invoke()
        }
    }
    private val pending = linkedMapOf<String, Ticket>()
    var active: Ticket? = null
        private set
    val pendingIds get() = pending.keys.toSet()
    val manualIds get() = pending.values.filter { it.manual }.map { it.id }.toSet() + listOfNotNull(active?.takeIf { it.manual }?.id)
    val busy get() = active != null || pending.isNotEmpty()

    fun single(id: String) {
        active?.takeIf { it.id == id && !it.cancelled.get() }?.let { it.manual = true; return }
        pending.getOrPut(id) { Ticket(id, true, false) }.manual = true
        active?.takeIf { !it.manual && !it.cancelled.get() }?.cancel(preempt = true)
    }
    fun batch(ids: List<String>) {
        ids.distinct().forEach { id ->
            if (active?.id == id && active?.cancelled?.get() == false) active!!.batch = true
            else pending.getOrPut(id) { Ticket(id, false, true) }.batch = true
        }
    }
    fun take(): Ticket? {
        check(active == null)
        val ticket = pending.values.firstOrNull { it.manual } ?: pending.values.firstOrNull() ?: return null
        pending.remove(ticket.id); active = ticket; return ticket
    }
    fun finish(ticket: Ticket) {
        check(active === ticket)
        active = null
        if (ticket.preempted && ticket.batch) {
            pending.getOrPut(ticket.id) { Ticket(ticket.id, false, true) }.batch = true
        }
    }
    fun stopBatch() {
        pending.values.forEach { it.batch = false }
        pending.entries.removeAll { !it.value.manual }
        active?.let { if (!it.manual) it.cancel() else it.batch = false }
    }
    fun stopAll() {
        pending.clear(); active?.cancel()
    }
}
