package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.sync.UploadQueue
import org.junit.Assert.*
import org.junit.Test

class UploadQueueTest {
    @Test fun `manual preempts batch and interrupted file resumes after priority work`() {
        val queue = UploadQueue(); queue.batch(listOf("a", "b", "c"))
        val a = queue.take()!!
        var aborted = false; a.abort = { aborted = true }
        queue.single("c")
        assertTrue(aborted); assertTrue(a.preempted)
        queue.finish(a)
        val c = queue.take()!!; assertEquals("c", c.id); assertTrue(c.manual); assertTrue(c.batch)
        queue.finish(c)
        val b = queue.take()!!; queue.finish(b)
        val retry = queue.take()!!; assertEquals("a", retry.id); assertFalse(retry.cancelled.get())
        queue.finish(retry); assertFalse(queue.busy)
    }
    @Test fun `clicking current batch item promotes it without parallel duplicate writes`() {
        val queue = UploadQueue(); queue.batch(listOf("a", "b"))
        val a = queue.take()!!; queue.single("a"); queue.single("a")
        assertFalse(a.cancelled.get()); assertTrue(a.manual); assertFalse("a" in queue.pendingIds)
        queue.finish(a); assertEquals("b", queue.take()!!.id)
    }
    @Test fun `rapid repeated priority clicks coalesce and preserve batch membership`() {
        val queue = UploadQueue(); queue.batch(listOf("a", "b"))
        repeat(10) { queue.single("b") }
        assertEquals(setOf("a", "b"), queue.pendingIds)
        val b = queue.take()!!; assertEquals("b", b.id); assertTrue(b.batch)
        queue.finish(b); assertEquals("a", queue.take()!!.id)
    }
    @Test fun `priority requests do not abort another explicit single`() {
        val queue = UploadQueue(); queue.single("a"); val a = queue.take()!!
        queue.single("b"); assertFalse(a.cancelled.get())
        queue.finish(a); assertEquals("b", queue.take()!!.id)
    }
    @Test fun `stop clears preempted retry and next request starts fresh`() {
        val queue = UploadQueue(); queue.batch(listOf("a", "b")); val a = queue.take()!!
        queue.single("b"); queue.stopAll(); queue.finish(a)
        assertFalse(queue.busy)
        queue.single("a"); val next = queue.take()!!
        assertFalse(next.cancelled.get()); assertTrue(next.manual)
    }
    @Test fun `same item can be requested again after completion or failure`() {
        val queue = UploadQueue(); queue.single("a"); val first = queue.take()!!; queue.finish(first)
        queue.single("a"); assertNotSame(first, queue.take())
    }
}
