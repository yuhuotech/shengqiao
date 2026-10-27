package com.yuhuo.shengqiao.sync

import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal object NetworkRetry {
    fun <T> run(cancelled: AtomicBoolean, onRetry: (Int, Exception) -> Unit = { _, _ -> },
                pause: (Long) -> Unit = { Thread.sleep(it) }, action: () -> T): T {
        for (attempt in 1..3) {
            if (cancelled.get()) throw InterruptedIOException("同步已停止")
            try {
                return action()
            } catch (e: Exception) {
                if (cancelled.get() || attempt == 3 || (e !is SocketTimeoutException && e !is SocketException)) throw e
                onRetry(attempt, e)
                repeat(attempt * 10) {
                    if (cancelled.get()) throw InterruptedIOException("同步已停止")
                    try { pause(100) } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("同步已停止")
                    }
                }
            }
        }
        error("unreachable")
    }
}
