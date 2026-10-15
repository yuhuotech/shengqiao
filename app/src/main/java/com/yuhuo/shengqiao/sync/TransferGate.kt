package com.yuhuo.shengqiao.sync

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Background jobs yield immediately to user work; remote commits never overlap. */
object TransferGate {
    private val lock = ReentrantLock(true)
    fun <T> manual(block: () -> T): T {
        AutoSync.cancelForManual()
        lock.lockInterruptibly()
        return try { block() } finally { lock.unlock() }
    }
    fun <T> background(cancelled: AtomicBoolean, block: () -> T): T {
        if (!lock.tryLock()) throw InterruptedIOException("手动同步优先，本轮自动同步跳过")
        return try {
            if (cancelled.get() || Operation.sync.busy || Operation.scan.busy) throw InterruptedIOException("手动操作优先，本轮自动同步跳过")
            block()
        } finally { lock.unlock() }
    }
}
