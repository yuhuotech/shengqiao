package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.PreviewBlock
import com.yuhuo.shengqiao.data.PreviewRole
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in, same-phone before/after navigation timings; never prints recording content. */
@RunWith(AndroidJUnit4::class)
class NavigationPerformanceTest {
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun measureDetailAndBackOnExistingLocalData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureNavigation") == "true")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
        var activity: MainActivity? = null
        var records: List<Recording> = emptyList()
        val limit = SystemClock.elapsedRealtime() + 15_000
        @Suppress("UNCHECKED_CAST")
        while (SystemClock.elapsedRealtime() < limit) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                activity?.let { a ->
                    a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    records = MainActivity::class.java.getDeclaredField("records").apply { isAccessible = true }.get(a) as List<Recording>
                }
            }
            if (activity != null && records.isNotEmpty()) break
            Thread.sleep(100)
        }
        val a = activity ?: error("App not resumed; unlock phone for navigation measurement")
        assertTrue(records.isNotEmpty())
        val record = records.maxBy { it.transcript.length }
        val detail = MainActivity::class.java.getDeclaredMethod("detail", Recording::class.java).apply { isAccessible = true }
        val page = MainActivity::class.java.getDeclaredField("page").apply { isAccessible = true }
        fun measured(expectedPage: String, action: () -> Unit): JSONObject {
            val drawn = CountDownLatch(1)
            var begin = 0L; var returned = 0L; var frame = 0L
            instrumentation.runOnMainSync {
                val observer = a.window.decorView.viewTreeObserver
                observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (page.get(a) != expectedPage) return true
                        a.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                        frame = SystemClock.elapsedRealtimeNanos(); drawn.countDown(); return true
                    }
                })
                begin = SystemClock.elapsedRealtimeNanos()
                action()
                returned = SystemClock.elapsedRealtimeNanos()
                a.window.decorView.invalidate()
            }
            assertTrue("Navigation produced a frame", drawn.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            return JSONObject().put("handlerMs", (returned - begin) / 1_000_000.0).put("frameMs", (frame - begin) / 1_000_000.0)
        }
        val rows = JSONArray()
        try {
            if (InstrumentationRegistry.getArguments().getString("requireHomeReuse") == "true") {
                instrumentation.runOnMainSync {
                    descendants(a.window.decorView).filterIsInstance<ListView>().single().setSelection(5.coerceAtMost(records.lastIndex))
                }
                instrumentation.waitForIdleSync()
            }
            repeat(5) {
                var originalHome: View? = null
                var originalList: ListView? = null
                var originalPosition = 0
                var originalOffset = 0
                instrumentation.runOnMainSync {
                    originalHome = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    originalList = descendants(a.window.decorView).filterIsInstance<ListView>().single()
                    originalPosition = originalList!!.firstVisiblePosition
                    originalOffset = originalList!!.getChildAt(0)?.top ?: 0
                }
                val opened = SystemClock.elapsedRealtimeNanos()
                val forward = measured("detail") { detail.invoke(a, record) }
                if (InstrumentationRegistry.getArguments().getString("requireFullPreview") == "true") {
                    var full = false
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    while (!full && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync {
                            full = descendants(a.window.decorView).filterIsInstance<TextView>()
                                .any { view -> view.tag == "detail-body" && view.text.length == record.transcript.length.coerceAtMost(50_000) }
                            val preview = descendants(a.window.decorView).filterIsInstance<ListView>().firstOrNull { it.tag == "detail-content" }
                            if (preview != null) full = (0 until preview.adapter.count).sumOf { i ->
                                (preview.adapter.getItem(i) as? PreviewBlock)?.takeIf { it.role == PreviewRole.TRANSCRIPT }?.text?.length ?: 0
                            } == record.transcript.length.coerceAtMost(50_000)
                        }
                        if (!full) Thread.sleep(5)
                    }
                    assertTrue("Full preview was prepared", full)
                    forward.put("fullPreviewReadyMs", (SystemClock.elapsedRealtimeNanos() - opened) / 1_000_000.0)
                }
                val back = measured("home") { descendants(a.window.decorView).filterIsInstance<ImageButton>().single { it.tag == "toolbar-back" }.performClick() }
                if (InstrumentationRegistry.getArguments().getString("requireHomeReuse") == "true") {
                    instrumentation.runOnMainSync {
                        assertSame(originalHome, a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                        assertSame(originalList, descendants(a.window.decorView).filterIsInstance<ListView>().single())
                        assertEquals(originalPosition, originalList!!.firstVisiblePosition)
                        assertEquals(originalOffset, originalList!!.getChildAt(0)?.top ?: 0)
                    }
                }
                rows.put(JSONObject().put("detail", forward).put("back", back))
            }
            val taps = JSONArray()
            // A clickable recycled row must still allow ListView to intercept scrolling.
            val scrollLocation = IntArray(2)
            var scrollX = 0f; var scrollY = 0f; var scrollDistance = 0f
            instrumentation.runOnMainSync {
                val list = descendants(a.window.decorView).filterIsInstance<ListView>().single()
                list.getLocationInWindow(scrollLocation)
                scrollX = scrollLocation[0] + list.width / 2f
                scrollY = scrollLocation[1] + list.height * 0.8f
                scrollDistance = list.height * 0.6f
            }
            val scrollDown = SystemClock.uptimeMillis()
            fun scrollEvent(action: Int, fraction: Float) = instrumentation.runOnMainSync {
                val event = MotionEvent.obtain(scrollDown, SystemClock.uptimeMillis(), action, scrollX, scrollY - scrollDistance * fraction, 0)
                a.window.decorView.dispatchTouchEvent(event); event.recycle()
            }
            scrollEvent(MotionEvent.ACTION_DOWN, 0f)
            for (step in 1..5) { Thread.sleep(30); scrollEvent(MotionEvent.ACTION_MOVE, step / 5f) }
            scrollEvent(MotionEvent.ACTION_UP, 1f)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals("Dragging must not open a recording", "home", page.get(a)) }
            fun tapped(expectedPage: String, target: () -> View): JSONObject {
                val location = IntArray(2)
                var x = 0f; var y = 0f
                instrumentation.runOnMainSync {
                    val view = target()
                    view.getLocationInWindow(location)
                    x = location[0] + view.width / 2f; y = location[1] + view.height / 2f
                }
                val down = SystemClock.uptimeMillis()
                fun dispatch(action: Int) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    a.window.decorView.dispatchTouchEvent(event)
                    event.recycle()
                }
                instrumentation.runOnMainSync { dispatch(MotionEvent.ACTION_DOWN) }
                Thread.sleep(30)
                return measured(expectedPage) { dispatch(MotionEvent.ACTION_UP) }
            }
            repeat(5) {
                var expectedTitle = ""
                val forward = tapped("detail") {
                    val list = descendants(a.window.decorView).filterIsInstance<ListView>().single()
                    // The first row can be clipped after a drag; use a fully visible row.
                    val offset = if (list.childCount > 1) 1 else 0
                    expectedTitle = (list.adapter.getItem(list.firstVisiblePosition + offset) as Recording).title
                    list.getChildAt(offset)
                }
                instrumentation.runOnMainSync {
                    val header = descendants(a.window.decorView).single { it.tag == "detail-header" }
                    assertTrue("Recycled row opens its current recording", descendants(header).filterIsInstance<TextView>().any { it.text.toString() == expectedTitle })
                }
                val back = tapped("home") { descendants(a.window.decorView).filterIsInstance<ImageButton>().single { it.tag == "toolbar-back" } }
                taps.put(JSONObject().put("detail", forward).put("back", back))
            }
            val label = InstrumentationRegistry.getArguments().getString("navigationLabel", "sample")!!.replace(Regex("[^a-zA-Z0-9_-]"), "")
            val result = JSONObject().put("records", records.size).put("previewChars", record.transcript.length.coerceAtMost(50_000))
                .put("runs", rows).put("touchReleaseRuns", taps)
            File(instrumentation.targetContext.filesDir, "navigation-$label.json").writeText(result.toString())
            println("NAV_PERFORMANCE $result")
        } finally { instrumentation.runOnMainSync { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); a.finish() } }
    }
}
