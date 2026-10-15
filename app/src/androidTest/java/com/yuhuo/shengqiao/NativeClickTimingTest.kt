package com.yuhuo.shengqiao

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolate native ListView's touch-up to click dispatch, using a synthetic row only. */
@RunWith(AndroidJUnit4::class)
class NativeClickTimingTest {
    @Test fun measureNativeDispatch() {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureNativeDispatch") == "true")
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
        var activity: MainActivity? = null
        ins.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        }
        val a = activity ?: error("App not resumed")
        val result = JSONObject()
        try {
            for (childClick in listOf(false, true)) {
                val clicked = CountDownLatch(1)
                var clickAt = 0L; var releasedAt = 0L
                lateinit var list: ListView
                ins.runOnMainSync {
                    list = ListView(a)
                    list.setOnItemClickListener { _, _, _, _ -> clickAt = SystemClock.elapsedRealtimeNanos(); clicked.countDown() }
                    list.adapter = object : BaseAdapter() {
                        override fun getCount() = 1
                        override fun getItem(position: Int) = "synthetic"
                        override fun getItemId(position: Int) = 1L
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = TextView(a).apply {
                            text = "合成卡片点击计时"
                            layoutParams = android.widget.AbsListView.LayoutParams(-1, (64 * a.resources.displayMetrics.density).toInt())
                            if (childClick) setOnClickListener { clickAt = SystemClock.elapsedRealtimeNanos(); clicked.countDown() }
                        }
                    }
                    a.setContentView(list)
                }
                ins.waitForIdleSync()
                val location = IntArray(2)
                ins.runOnMainSync { list.getChildAt(0).getLocationOnScreen(location) }
                val down = SystemClock.uptimeMillis()
                fun dispatch(action: Int) = ins.runOnMainSync {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0] + 80f, location[1] + 30f, 0)
                    if (action == MotionEvent.ACTION_UP) releasedAt = SystemClock.elapsedRealtimeNanos()
                    a.window.decorView.dispatchTouchEvent(event); event.recycle()
                }
                dispatch(MotionEvent.ACTION_DOWN); Thread.sleep(30); dispatch(MotionEvent.ACTION_UP)
                assertTrue(clicked.await(3, TimeUnit.SECONDS))
                result.put(if (childClick) "rowClickMs" else "itemClickMs", (clickAt - releasedAt) / 1_000_000.0)
            }
            File(ins.targetContext.filesDir, "native-click-timing.json").writeText(result.toString())
        } finally { ins.runOnMainSync { a.finish() } }
    }
}
