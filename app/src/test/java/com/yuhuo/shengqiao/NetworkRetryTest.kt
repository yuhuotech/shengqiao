package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.sync.NetworkRetry
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class NetworkRetryTest {
    @Test fun `retries transient disconnects and timeouts`() {
        var calls = 0
        val retries = mutableListOf<Int>()
        val result = NetworkRetry.run(AtomicBoolean(false), { n, _ -> retries.add(n) }, {}) {
            when (++calls) {
                1 -> throw SocketException("connection abort")
                2 -> throw SocketTimeoutException("timeout")
                else -> "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(listOf(1, 2), retries)
    }

    @Test fun `does not retry non-network errors or cancellation`() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            NetworkRetry.run(AtomicBoolean(false), pause = {}) { calls++; error("failure") }
        }
        assertEquals(1, calls)
        val cancelled = AtomicBoolean(false)
        assertThrows(InterruptedIOException::class.java) {
            NetworkRetry.run(cancelled, onRetry = { _, _ -> cancelled.set(true) }, pause = {}) {
                throw SocketException("connection abort")
            }
        }
    }

    @Test fun `stops after three attempts`() {
        var calls = 0
        assertThrows(SocketTimeoutException::class.java) {
            NetworkRetry.run(AtomicBoolean(false), pause = {}) { calls++; throw SocketTimeoutException() }
        }
        assertEquals(3, calls)
    }
}
