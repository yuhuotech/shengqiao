package com.yuhuo.shengqiao

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.ui.Appearance
import com.yuhuo.shengqiao.ui.CollapsiblePanel
import com.yuhuo.shengqiao.ui.RecordingPlayer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

class EnhancementsDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun current() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
    private fun await(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < end) { ins.runOnMainSync { done = check() }; if (!done) Thread.sleep(30) }
        assertTrue("Condition should become true", done)
    }
    private fun launch(): MainActivity {
        shell("am start -W -n com.yuhuo.shengqiao/.MainActivity")
        var a: MainActivity? = null
        await { a = current(); a != null }
        return a!!
    }
    private fun finish() = ins.runOnMainSync { current()?.finish() }
    private fun capture(label: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        ins.waitForIdleSync()
        // Let compositor transitions (including the OS launch splash) finish before capture.
        Thread.sleep(400)
        val bitmap = ins.uiAutomation.takeScreenshot()
        File(ins.targetContext.filesDir, "preview-$label.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun drag(a: MainActivity, list: ListView, upward: Boolean) {
        val loc = IntArray(2); var x = 0f; var y = 0f; var distance = 0f
        ins.runOnMainSync {
            list.getLocationInWindow(loc); x = loc[0] + list.width / 2f
            y = loc[1] + list.height * if (upward) 0.8f else 0.2f
            distance = list.height * if (upward) -0.6f else 0.6f
        }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, fraction: Float) = ins.runOnMainSync {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y + distance * fraction, 0)
            a.window.decorView.dispatchTouchEvent(e); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..6) { Thread.sleep(40); event(MotionEvent.ACTION_MOVE, step / 6f) }
        event(MotionEvent.ACTION_UP, 1f)
    }
    @Test fun homePanelCollapsesSearchSticksAndTopPullOrManualButtonExpands() {
        val a = launch()
        try {
            await { !MainActivity::class.java.getDeclaredField("refreshInFlight").apply { isAccessible = true }.getBoolean(a) }
            val records = List(105) { i -> Recording(i.toString(16).padStart(40, '0'), "合成录音 $i", "/test.mp3", 3, 60, 1,
                if (i < 22) "" else "合成转写", "{}", "", "{}") }
            lateinit var list: ListView; lateinit var panel: CollapsiblePanel; var expandedHeight = 0
            ins.runOnMainSync {
                MainActivity::class.java.getDeclaredField("records").apply { isAccessible = true }.set(a, records)
                MainActivity::class.java.getDeclaredField("displayStates").apply { isAccessible = true }.set(a, records.associate { it.id to "已同步" })
                MainActivity::class.java.getDeclaredMethod("filterList").apply { isAccessible = true }.invoke(a)
                MainActivity::class.java.getDeclaredMethod("renderHomeStatus").apply { isAccessible = true }.invoke(a)
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                list = views(a.window.decorView).filterIsInstance<ListView>().single()
                panel = views(a.window.decorView).filterIsInstance<CollapsiblePanel>().single()
                expandedHeight = panel.height
                assertTrue(expandedHeight > 0)
                assertTrue(views(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "105 条录音   105 条已同步" })
                assertTrue(views(a.window.decorView).filterIsInstance<TextView>().any { it.text.startsWith("22 条缺少转写") })
            }
            capture("home-expanded")
            drag(a, list, true)
            await { panel.height == 0 }
            capture("home-collapsed")
            ins.runOnMainSync {
                assertTrue(views(a.window.decorView).filterIsInstance<EditText>().single().isShown)
                assertTrue(views(a.window.decorView).single { it.tag == "panel-toggle" }.isShown)
                list.setSelection(30)
            }
            ins.waitForIdleSync()
            drag(a, list, false)
            ins.runOnMainSync { assertEquals("Downward scroll away from top must not expand", 0, panel.height) }
            ins.runOnMainSync { views(a.window.decorView).single { it.tag == "panel-toggle" }.performClick() }
            await { panel.height == expandedHeight }
            ins.runOnMainSync { assertTrue("Manual expansion preserves list position", list.firstVisiblePosition > 0); panel.settle(true) }
            await { panel.height == 0 }
            ins.runOnMainSync { list.setSelection(0) }
            ins.waitForIdleSync()
            drag(a, list, false)
            await { panel.height == expandedHeight }
        } finally { finish() }
    }
    @Test fun compactDetailContainsPlayerAndCollapsesWithoutHidingToolbarToggle() {
        val a = launch()
        try {
            val r = Recording("e".repeat(40), "播放器合成测试", "/test.mp3", 3, 60, 1, "转写正文。\n".repeat(10_000), "{}", "", "{}")
            ins.runOnMainSync { MainActivity::class.java.getDeclaredMethod("detail", Recording::class.java).apply { isAccessible = true }.invoke(a, r) }
            ins.waitForIdleSync()
            lateinit var list: ListView; lateinit var panel: CollapsiblePanel; var height = 0
            ins.runOnMainSync {
                list = views(a.window.decorView).filterIsInstance<ListView>().single()
                panel = views(a.window.decorView).filterIsInstance<CollapsiblePanel>().single()
                height = panel.height
                assertTrue("Single-line detail header stays compact", height <= a.resources.displayMetrics.density * 170)
                listOf("audio-play", "audio-stop", "audio-seek", "sync-recording").forEach { tag -> assertTrue(views(panel).single { it.tag == tag }.isShown) }
            }
            capture("detail-expanded")
            drag(a, list, true)
            await { panel.height == 0 }
            capture("detail-collapsed")
            ins.runOnMainSync { list.setSelection(15) }
            ins.waitForIdleSync()
            drag(a, list, false)
            await { panel.height == height }
            ins.runOnMainSync {
                assertTrue(views(a.window.decorView).single { it.tag == "toolbar-back" }.isShown)
                views(a.window.decorView).single { it.tag == "panel-toggle" }.performClick()
            }
            await { panel.height == 0 }
            ins.runOnMainSync { views(a.window.decorView).single { it.tag == "panel-toggle" }.performClick() }
            await { panel.height == height }
        } finally { finish() }
    }
    @Test fun playerSupportsPauseSeekStopAndErrorRetryWithSyntheticSilentWav() {
        launch()
        val source = File(ins.targetContext.cacheDir, "synthetic-player.wav")
        val dataSize = 8000 * 2 * 5
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
        source.writeBytes(bytes)
        val state = AtomicReference(RecordingPlayer.State()); val error = AtomicReference("")
        lateinit var player: RecordingPlayer
        try {
            ins.runOnMainSync {
                player = RecordingPlayer(ins.targetContext, { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) }, state::set, error::set)
                player.toggle()
            }
            await { state.get().playing && state.get().position > 250 }
            ins.runOnMainSync { player.pause() }
            val paused = state.get().position
            Thread.sleep(300)
            ins.runOnMainSync { assertFalse(state.get().playing); assertTrue(kotlin.math.abs(paused - state.get().position) < 100); player.seekTo(2400) }
            await { kotlin.math.abs(state.get().position - 2400) < 300 }
            ins.runOnMainSync { player.stop() }
            await { !state.get().playing && state.get().position < 100 }
            assertEquals("", error.get())
            ins.runOnMainSync { player.release(); player = RecordingPlayer(ins.targetContext, { error("Synthetic read failure") }, state::set, error::set); player.toggle() }
            await { error.get().isNotEmpty() && !state.get().loading }
            error.set("")
            ins.runOnMainSync { player.toggle() }
            await { error.get().isNotEmpty() && !state.get().loading }
        } finally { ins.runOnMainSync { player.release() }; source.delete(); finish() }
    }
    @Test fun appearanceSupportsForcedModesAndLiveSystemFollowingOnEmulator() {
        assumeTrue(android.os.Build.MODEL.startsWith("sdk_"))
        val context = ins.targetContext
        val previous = Appearance.selected(context)
        val oldSystemMode = context.getSystemService(UiModeManager::class.java).nightMode
        assumeTrue(oldSystemMode in 0..2)
        launch()
        val fixtureIds = mutableListOf<String>()
        val fixtureDestination = com.yuhuo.shengqiao.data.Settings(context).load().destination
        await { current()?.let { !MainActivity::class.java.getDeclaredField("refreshInFlight").apply { isAccessible = true }.getBoolean(it) } == true }
        ins.runOnMainSync {
            val a = current()!!
            val prefix = java.util.UUID.randomUUID().toString().replace("-", "")
            val records = List(105) { i -> Recording(prefix + i.toString(16).padStart(8, '0'), "外观切换录音$i", "/test.mp3", 3, 60, 1, "", "{}", "", "{}") }
            com.yuhuo.shengqiao.data.Store(context).use { db ->
                records.forEach { r ->
                    fixtureIds += r.id
                    db.saveMark(fixtureDestination, r.id, com.yuhuo.shengqiao.data.SyncMark(audio = "1".repeat(64), metadata = r.metadataHash, time = 1))
                }
            }
            MainActivity::class.java.getDeclaredField("records").apply { isAccessible = true }.set(a, records)
            MainActivity::class.java.getDeclaredField("displayStates").apply { isAccessible = true }.set(a, records.associate { it.id to "已同步" })
            MainActivity::class.java.getDeclaredMethod("filterList").apply { isAccessible = true }.invoke(a)
            MainActivity::class.java.getDeclaredMethod("renderHomeStatus").apply { isAccessible = true }.invoke(a)
        }
        val firstFrames = mutableListOf<String>()
        val lifecycle = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.STARTED) {
                firstFrames += views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.contains("条录音") && it.text.contains("条已同步") }?.text?.toString() ?: "MISSING"
            }
        }
        ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle) }
        fun dark(expected: Boolean) = await { current()?.let { (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == expected } == true }
        fun popup(a: MainActivity) = MainActivity::class.java.getDeclaredField("appearancePopup").apply { isAccessible = true }.get(a) as android.widget.PopupWindow
        fun choose(index: Int) = ins.runOnMainSync {
            val a = current()!!
            a.window.decorView.findViewWithTag<View>("appearance").performClick()
            popup(a).contentView.findViewWithTag<View>("appearance-option-$index").performClick()
        }
        try {
            choose(0)
            shell("cmd uimode night yes"); dark(true)
            capture("home-dark")
            ins.runOnMainSync { current()!!.window.decorView.findViewWithTag<View>("appearance").performClick() }
            capture("appearance-dark")
            ins.runOnMainSync { popup(current()!!).dismiss() }
            ins.runOnMainSync { current()!!.window.decorView.findViewWithTag<View>("settings").performClick() }
            ins.waitForIdleSync()
            capture("settings-dark")
            ins.runOnMainSync { current()!!.window.decorView.findViewWithTag<View>("toolbar-back").performClick() }
            shell("cmd uimode night no"); dark(false)
            ins.runOnMainSync { current()!!.window.decorView.findViewWithTag<View>("appearance").performClick() }
            capture("appearance-light")
            ins.runOnMainSync { popup(current()!!).dismiss() }
            choose(1); dark(true)
            choose(2); dark(false)
            shell("cmd uimode night yes"); dark(false)
            choose(0); dark(true)
            assertTrue(firstFrames.size >= 4)
            assertTrue("Counts must be present before the new Activity's first draw: $firstFrames", firstFrames.all { it == "105 条录音   105 条已同步" })
        } finally {
            ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle) }
            com.yuhuo.shengqiao.data.Store(context).use { db ->
                fixtureIds.forEach { db.writableDatabase.delete("sync_marks", "destination=? AND id=?", arrayOf(fixtureDestination, it)) }
            }
            shell("cmd uimode night ${when (oldSystemMode) { 1 -> "no"; 2 -> "yes"; else -> "auto" }}")
            ins.runOnMainSync { Appearance.select(context, previous) }
            finish()
        }
    }
}
