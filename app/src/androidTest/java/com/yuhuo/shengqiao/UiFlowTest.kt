package com.yuhuo.shengqiao

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.CheckBox
import android.widget.Switch
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.yuhuo.shengqiao.data.Settings
import com.yuhuo.shengqiao.data.Recording
import com.yuhuo.shengqiao.data.DavConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    private var savedConfig: DavConfig? = null
    @Before fun preserveConfig() {
        savedConfig = Settings(InstrumentationRegistry.getInstrumentation().targetContext).load()
    }
    @After fun restoreConfigEvenIfActivityCannotLaunch() {
        savedConfig?.let { Settings(InstrumentationRegistry.getInstrumentation().targetContext).save(it) }
    }
    @Test fun settingsCanSaveUnicodeFolderAndReturnHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prior = Settings(context).load()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
        var current: MainActivity? = null
        val deadline = System.currentTimeMillis() + 10000
        while (current == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            if (current == null) Thread.sleep(100)
        }
        val activity = current ?: error("声桥未进入前台，无法执行 UI 验证")
        try {
            instrumentation.runOnMainSync {
                fun descendants(view: View): List<View> = listOf(view) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                fun views() = descendants(activity.window.decorView)
                views().filterIsInstance<ListView>().firstOrNull()?.getChildAt(0)?.let { first ->
                    assertTrue("录音卡片不应被状态行撑高", first.height < 180 * context.resources.displayMetrics.density)
                }
                views().single { it.tag == "settings" }.performClick()
                views().single { it.tag == "settings-server" }.performClick()
                val inputs = views().filterIsInstance<EditText>()
                assertEquals(5, inputs.size)
                inputs[0].setText("https://nas.example.com/dav/")
                inputs[1].setText("local-test")
                inputs[2].setText("private-test-secret")
                inputs[3].setText("声桥/会议")
                inputs[4].setText("/obsidian/测试仓库")
                views().filterIsInstance<Button>().single { it.text.toString() == "保存配置" }.performClick()
                assertEquals("声桥/会议", Settings(context).load().folder)
                assertEquals("/obsidian/测试仓库", Settings(context).load().obsidianFolder)
                assertTrue(views().any { it.tag == "settings-server" })
                views().single { it.tag == "toolbar-back" }.performClick()
                assertTrue(views().filterIsInstance<Button>().any { it.text.toString() == "一键同步" })
            }
        } finally {
            Settings(context).save(prior)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun launch(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n com.yuhuo.shengqiao/.MainActivity")).use { it.readBytes() }
        var activity: MainActivity? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (activity == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            if (activity == null) Thread.sleep(100)
        }
        return activity ?: error("声桥未进入前台")
    }

    @Test fun settingsCategoriesNavigateAndPreserveNetworkPreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = Settings(instrumentation.targetContext)
        val prior = settings.load()
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                fun views() = descendants(activity.window.decorView)
                views().single { it.tag == "settings" }.performClick()
                for (category in listOf("server", "auto", "update", "access")) {
                    assertTrue(views().any { it.tag == "settings-$category" })
                }
                views().single { it.tag == "settings-auto" }.performClick()
                val wifi = views().filterIsInstance<Switch>().single { it.tag == "sync-wifi-only" }
                wifi.isChecked = !prior.wifiOnly
                assertEquals(!prior.wifiOnly, settings.load().wifiOnly)
                views().single { it.tag == "toolbar-back" }.performClick()
                views().single { it.tag == "settings-server" }.performClick()
                val inputs = views().filterIsInstance<EditText>()
                inputs[0].setText("https://nas.example.com/dav/")
                views().filterIsInstance<Button>().single { it.text == "保存配置" }.performClick()
                assertEquals(!prior.wifiOnly, settings.load().wifiOnly)
                views().single { it.tag == "settings-update" }.performClick()
                assertTrue(views().any { it.tag == "check-update" })
                assertTrue(views().any { it.tag == "update-proxy" })
                views().single { it.tag == "toolbar-back" }.performClick()
                views().single { it.tag == "settings-access" }.performClick()
                assertTrue(views().filterIsInstance<Button>().any { it.text == "打开 / 授权 Shizuku" })
                views().single { it.tag == "toolbar-back" }.performClick()
                assertTrue(views().any { it.tag == "settings-server" })
            }
        } finally {
            settings.save(prior)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun passwordVisibilityTogglePreservesValueCursorAndDefaultsHiddenOnReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                fun views() = descendants(activity.window.decorView)
                views().single { it.tag == "settings" }.performClick()
                views().single { it.tag == "settings-server" }.performClick()
                val field = views().filterIsInstance<EditText>().single { it.tag == "webdav-password" }
                val toggle = views().filterIsInstance<android.widget.ImageButton>().single { it.tag == "password-visibility" }
                assertTrue(field.transformationMethod is android.text.method.PasswordTransformationMethod)
                field.setText("Synthetic-test-only-321")
                field.setSelection(4)
                toggle.performClick()
                assertNull(field.transformationMethod)
                assertEquals("隐藏密码", toggle.contentDescription.toString())
                assertEquals("Synthetic-test-only-321", field.text.toString())
                assertEquals(4, field.selectionStart)
                toggle.performClick()
                assertTrue(field.transformationMethod is android.text.method.PasswordTransformationMethod)
                assertEquals("Synthetic-test-only-321", field.text.toString())
                assertEquals(4, field.selectionStart)
                // Reopening creates a hidden field; the unsaved test value is discarded.
                MainActivity::class.java.getDeclaredMethod("serverSettingsPage").apply { isAccessible = true }.invoke(activity)
                assertTrue(views().filterIsInstance<EditText>().single { it.tag == "webdav-password" }
                    .transformationMethod is android.text.method.PasswordTransformationMethod)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun refreshButtonShowsImmediateProgressOnRealPhone() {
        org.junit.Assume.assumeTrue(com.yuhuo.shengqiao.bridge.BridgeClient.ready())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text == "刷新录音" }.performClick()
                val views = descendants(activity.window.decorView)
                assertTrue(views.filterIsInstance<Button>().single { it.text == "停止刷新" }.isEnabled)
                assertTrue(views.filterIsInstance<Button>().single { it.text == "一键同步" }.isEnabled)
                assertTrue(views.filterIsInstance<Button>().any { it.text == "停止刷新" })
                assertTrue(views.filterIsInstance<TextView>().any { it.text.startsWith("正在准备刷新录音") })
            }
            val deadline = System.currentTimeMillis() + 90_000
            while ((com.yuhuo.shengqiao.sync.Operation.starting || com.yuhuo.shengqiao.sync.Operation.running) && System.currentTimeMillis() < deadline)
                Thread.sleep(150)
            assertFalse(com.yuhuo.shengqiao.sync.Operation.running)
            assertFalse(com.yuhuo.shengqiao.sync.Operation.failed)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.startsWith("已刷新") })
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text == "刷新录音" }.isEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun detailHeaderRemainsFixedWhileTranscriptScrolls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                val r = Recording("e".repeat(40), "固定头部验证", "/test.mp3", 3, 90, 1,
                    "很长的转写正文\n".repeat(2000), "{}", "摘要", "{}")
                MainActivity::class.java.getDeclaredMethod("detail", Recording::class.java).apply { isAccessible = true }.invoke(activity, r)
            }
            instrumentation.waitForIdleSync()
            var y = 0
            instrumentation.runOnMainSync {
                val header = descendants(activity.window.decorView).single { it.tag == "detail-header" }
                y = header.top
                val scroll = descendants(activity.window.decorView).filterIsInstance<ListView>().single { it.tag == "detail-content" }
                assertTrue(scroll.canScrollVertically(1))
                scroll.setSelection(4)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                val scroll = views.filterIsInstance<ListView>().single { it.tag == "detail-content" }
                assertTrue(scroll.firstVisiblePosition > 0)
                assertEquals(y, views.single { it.tag == "detail-header" }.top)
                assertTrue(views.single { it.tag == "sync-recording" }.isShown)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun detailVirtualPreviewContainsAllTextAndCopiesOriginalFullTranscript() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch()
        val r = Recording("d".repeat(40), "合成转写预览验证", "/test.mp3", 3, 90, 1,
            "长转写内容。\n\n".repeat(10_000), "{}", "摘要正文。".repeat(7000), "{}")
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("detail", Recording::class.java).apply { isAccessible = true }.invoke(activity, r)
                val list = descendants(activity.window.decorView).filterIsInstance<ListView>().single { it.tag == "detail-content" }
                val blocks = (0 until list.adapter.count).mapNotNull { list.adapter.getItem(it) as? com.yuhuo.shengqiao.data.PreviewBlock }
                assertEquals(r.transcript.take(50_000), blocks.filter { it.role == com.yuhuo.shengqiao.data.PreviewRole.TRANSCRIPT }.joinToString("") { it.text })
                assertEquals(r.summary.take(30_000), blocks.filter { it.role == com.yuhuo.shengqiao.data.PreviewRole.SUMMARY }.joinToString("") { it.text })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                val priorClip = clipboard.primaryClip
                try {
                    descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text == "复制全文" }.performClick()
                    assertEquals(r.transcript, clipboard.primaryClip!!.getItemAt(0).text.toString())
                } finally {
                    if (priorClip != null) clipboard.setPrimaryClip(priorClip) else clipboard.clearPrimaryClip()
                }
                val list = descendants(activity.window.decorView).filterIsInstance<ListView>().single { it.tag == "detail-content" }
                assertTrue(list.childCount < list.adapter.count)
                val body = descendants(list).filterIsInstance<TextView>().first { it.isTextSelectable }
                assertTrue(body.isEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun connectionButtonChecksBothDirectoriesAndRejectsWrongPassword() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = Settings(instrumentation.targetContext)
        val prior = settings.load()
        settings.save(DavConfig())
        val activity = launch()
        fun views() = descendants(activity.window.decorView)
        fun waitResult(prefix: String) {
            val deadline = System.currentTimeMillis() + 30_000
            var found = false
            while (!found && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    found = views().filterIsInstance<TextView>().any { it.text.startsWith(prefix) }
                }
                if (!found) Thread.sleep(100)
            }
            assertTrue("连接检测应显示 $prefix", found)
        }
        try {
            instrumentation.runOnMainSync {
                views().single { it.tag == "settings" }.performClick()
                views().single { it.tag == "settings-server" }.performClick()
                val inputs = views().filterIsInstance<EditText>()
                assertEquals("/shengqiao", inputs[3].text.toString())
                assertEquals("/obsidian/小米录音", inputs[4].text.toString())
                inputs[0].setText("http://127.0.0.1:18765/")
                inputs[1].setText("test"); inputs[2].setText("test-local-only")
                views().filterIsInstance<CheckBox>().single { it.text.startsWith("允许 HTTP") }.isChecked = true
                views().filterIsInstance<Button>().single { it.text == "测试连接" }.performClick()
            }
            waitResult("连接成功：")
            instrumentation.runOnMainSync {
                views().filterIsInstance<EditText>()[2].setText("wrong")
                views().filterIsInstance<Button>().single { it.text == "测试连接" }.performClick()
            }
            waitResult("连接失败：")
            instrumentation.runOnMainSync {
                val report = views().filterIsInstance<TextView>().single { it.tag == "connection-report" }.text.toString()
                assertTrue(report.contains("认证：失败（HTTP 401）"))
                assertTrue(report.contains("尚未执行创建"))
                assertTrue(report.contains("Obsidian 转写目录\n未执行"))
                views().filterIsInstance<Button>().single { it.text == "查看诊断日志" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("最近一次连接检测日志").isNotEmpty())
            val log = com.yuhuo.shengqiao.data.WebDavTrace(instrumentation.targetContext).read()
            assertTrue(log.contains("PROPFIND /shengqiao/") && log.contains("HTTP 401"))
            assertFalse(log.contains("Authorization:") || log.contains("test-local-only"))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            // Detection alone must not silently persist the typed credentials.
            assertEquals(DavConfig(), settings.load())
        } finally {
            settings.save(prior)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
