package com.yuhuo.shengqiao

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import com.yuhuo.shengqiao.bridge.BridgeClient
import com.yuhuo.shengqiao.data.*
import com.yuhuo.shengqiao.sync.Operation
import com.yuhuo.shengqiao.sync.SyncService
import com.yuhuo.shengqiao.sync.WebDav
import com.yuhuo.shengqiao.sync.AutoSync
import com.yuhuo.shengqiao.ui.Appearance
import com.yuhuo.shengqiao.ui.RecordingPlayer
import com.yuhuo.shengqiao.ui.CollapsiblePanel
import com.yuhuo.shengqiao.ui.PanelListView
import com.yuhuo.shengqiao.update.ReleaseClient
import com.yuhuo.shengqiao.update.Release
import androidx.core.content.FileProvider
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val blue get() = if (dark) Color.rgb(110, 170, 255) else Color.rgb(18, 105, 235)
    private val ink get() = if (dark) Color.rgb(231, 235, 243) else Color.rgb(28, 38, 53)
    private val muted get() = if (dark) Color.rgb(158, 169, 187) else Color.rgb(111, 124, 142)
    private val background get() = if (dark) Color.rgb(18, 21, 28) else Color.rgb(245, 247, 251)
    private val surface get() = if (dark) Color.rgb(29, 34, 44) else Color.WHITE
    private val softBlue get() = if (dark) Color.rgb(35, 49, 70) else Color.rgb(232, 240, 255)
    private val primaryFill get() = if (dark) Color.rgb(30, 91, 182) else blue
    private val danger get() = if (dark) Color.rgb(255, 142, 138) else Color.rgb(194, 63, 61)
    private val successColor get() = if (dark) Color.rgb(102, 216, 174) else Color.rgb(20, 130, 101)
    private lateinit var store: Store
    private lateinit var settings: Settings
    private lateinit var shell: LinearLayout
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var coverage: TextView
    private lateinit var note: TextView
    private lateinit var scan: Button
    private lateinit var sync: Button
    private lateinit var authorize: Button
    private lateinit var list: ListView
    private lateinit var chips: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val uiWorker = Executors.newFixedThreadPool(2)
    private val updateWorker = Executors.newSingleThreadExecutor()
    private var updateBusy = false
    private val updateGeneration = AtomicLong()
    @Volatile private var activeUpdateClient: ReleaseClient? = null
    private var activeUpdateRelease: Release? = null
    private var activeUpdateStatus: TextView? = null
    private var activeUpdateProxy: Boolean? = null
    private var pendingUpdateInstall = false
    private var homeRoot: LinearLayout? = null
    private var displayStates = emptyMap<String, String>()
    private var refreshInFlight = false
    private var refreshPending = false
    private var pendingConfigReload = false
    private var configEpoch = 0
    private var listDirty = true
    private var page = "home"
    private var filter = "全部"
    private var search = ""
    private var records = emptyList<Recording>()
    private var visible = emptyList<Recording>()
    private var marks = emptyMap<String, SyncMark>()
    private var cacheScan: String? = null
    private var shownCompletion = ""
    private var config = DavConfig()
    private var activeTest: WebDav? = null
    private var audioPlayer: RecordingPlayer? = null
    private var appearancePopup: PopupWindow? = null
    private var restoredPosition: Pair<Int, Int>? = null
    private var homePanel: CollapsiblePanel? = null
    private var restoreCollapsed = false
    private var pendingAction: Pair<String, String?>? = null
    private var detailId: String? = null
    private var detailState: TextView? = null
    private var detailError: TextView? = null
    private var detailSync: Button? = null
    private var detailReadPending = false
    private var detailRefreshPending = false
    private data class RetainedList(val config: DavConfig, val records: List<Recording>, val marks: Map<String, SyncMark>,
        val states: Map<String, String>, val stamp: String?)
    override fun onRetainNonConfigurationInstance(): Any = RetainedList(config, records, marks, displayStates, cacheScan)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (page == "home") refresh() else if (page == "detail") refreshDetail() }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread {
        if (page == "home") refresh()
        resumePending()
    } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { if (page == "home") refresh() } }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, granted ->
        runOnUiThread {
            if (page == "home") refresh()
            if (granted == PackageManager.PERMISSION_GRANTED) resumePending() else pendingAction = null
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        search = state?.getString("search").orEmpty()
        filter = state?.getString("filter") ?: "全部"
        pendingUpdateInstall = state?.getBoolean("pendingUpdateInstall") ?: false
        restoreCollapsed = state?.getBoolean("homeCollapsed") ?: false
        if (state != null) restoredPosition = state.getInt("listPosition") to state.getInt("listOffset")
        store = Store(this); settings = Settings(this)
        shownCompletion = settings.getInfo("lastCompletion")
        val retained = lastNonConfigurationInstance as? RetainedList
        if (retained != null) {
            config = retained.config; records = retained.records; marks = retained.marks
            displayStates = retained.states; cacheScan = retained.stamp
        } else config = runCatching { settings.load() }.getOrElse {
            toast("已保存的凭据无法解密，请重新配置 WebDAV"); DavConfig()
        }
        registerReceiver(receiver, IntentFilter("com.yuhuo.shengqiao.CHANGED"), RECEIVER_NOT_EXPORTED)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permission)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            navigateBack()
        }
        home()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("search", search); outState.putString("filter", filter)
        outState.putBoolean("pendingUpdateInstall", pendingUpdateInstall)
        outState.putBoolean("homeCollapsed", homePanel?.canExpand == true)
        if (::list.isInitialized) {
            outState.putInt("listPosition", list.firstVisiblePosition)
            outState.putInt("listOffset", list.getChildAt(0)?.top ?: 0)
        }
    }
    override fun onStop() { audioPlayer?.pause(); super.onStop() }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) { if (page == "home") refresh(reloadConfig = true); resumePending() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun rounded(color: Int, radius: Int = 14, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); border?.let { setStroke(dp(1), it) }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun LinearLayout.add(view: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
                                 height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(width, height, weight).apply { topMargin = dp(top) })
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minHeight = dp(44); minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) Color.WHITE else blue)
        background = rounded(if (primary) primaryFill else softBlue, 12)
        setOnClickListener { action() }
    }
    private fun icon(resource: Int, label: String, action: () -> Unit) = ImageButton(this).apply {
        contentDescription = label; setImageResource(resource)
        imageTintList = android.content.res.ColorStateList.valueOf(ink)
        setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = ImageView.ScaleType.FIT_CENTER
        val ripple = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId); setOnClickListener { action() }
    }
    private fun appearanceMenu() {
        val anchor = shell.findViewWithTag<View>("appearance") ?: return
        appearancePopup?.dismiss()
        val menu = column().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val popup = PopupWindow(menu, dp(216), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(rounded(surface, 16, if (dark) Color.rgb(52, 61, 76) else Color.rgb(227, 233, 241)))
            elevation = dp(12).toFloat(); isOutsideTouchable = true
        }
        menu.add(text("外观模式", 12f, muted).apply { setPadding(dp(12), dp(6), 0, dp(8)) })
        val selected = Appearance.selected(this)
        val symbols = intArrayOf(R.drawable.ic_appearance, R.drawable.ic_moon, R.drawable.ic_sun)
        Appearance.labels.forEachIndexed { index, label ->
            val chosen = index == selected
            val color = if (chosen) blue else ink
            val option = row().apply {
                tag = "appearance-option-$index"
                setPadding(dp(12), 0, dp(12), 0)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(25, 110, 170, 255)),
                    rounded(if (chosen) softBlue else Color.TRANSPARENT, 10), rounded(Color.WHITE, 10))
                isFocusable = true
                contentDescription = label + if (chosen) "，已选中" else ""
                setOnClickListener {
                    popup.dismiss(); Appearance.select(this@MainActivity, index)
                    anchor.contentDescription = "外观模式：$label"
                }
            }
            option.add(ImageView(this).apply { setImageResource(symbols[index]); imageTintList = android.content.res.ColorStateList.valueOf(color) }, dp(22), dp(22))
            option.add(text(label, 14f, color).apply { setPadding(dp(12), 0, 0, 0) }, 0, weight = 1f)
            option.add(ImageView(this).apply {
                setImageResource(R.drawable.ic_check); imageTintList = android.content.res.ColorStateList.valueOf(blue)
                visibility = if (chosen) View.VISIBLE else View.INVISIBLE
            }, dp(20), dp(20))
            menu.add(option, height = dp(46), top = 2)
        }
        appearancePopup = popup
        popup.setOnDismissListener { if (appearancePopup === popup) appearancePopup = null }
        popup.showAsDropDown(anchor, 0, dp(6), Gravity.END)
    }
    private fun panelToggle(panel: CollapsiblePanel) = icon(R.drawable.ic_expand, "收起面板") { panel.toggle() }.apply {
        tag = "panel-toggle"; rotation = if (panel.startCollapsed) 0f else 180f
        contentDescription = if (panel.startCollapsed) "展开面板" else "收起面板"
        panel.onExpansionChanged = { expanded -> rotation = if (expanded) 180f else 0f; contentDescription = if (expanded) "收起面板" else "展开面板" }
    }
    private fun base(title: String, subtitle: String = "", back: Boolean = false, togglePanel: CollapsiblePanel? = null): LinearLayout {
        shell = column().apply { setBackgroundColor(this@MainActivity.background); clipToPadding = !back }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left + dp(18), bars.top + dp(if (back) 2 else 10), bars.right + dp(18), bars.bottom + dp(10)); insets
        }
        if (back) {
            val toolbar = FrameLayout(this).apply { tag = "secondary-toolbar" }
            toolbar.addView(text(title, 19f).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(48); marginEnd = dp(48)
            })
            toolbar.addView(ImageButton(this).apply {
                tag = "toolbar-back"; contentDescription = "返回"
                setImageResource(R.drawable.ic_back_chevron)
                imageTintList = android.content.res.ColorStateList.valueOf(ink)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                scaleType = ImageView.ScaleType.FIT_CENTER
                val ripple = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                setBackgroundResource(ripple.resourceId)
                elevation = 0f
                setOnClickListener { navigateBack() }
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
            togglePanel?.let { toolbar.addView(panelToggle(it), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)) }
            shell.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                marginStart = -dp(18); marginEnd = -dp(18)
            })
            setContentView(shell)
            return shell
        }
        val heading = row()
        heading.add(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher); clipToOutline = true;
            background = rounded(Color.TRANSPARENT, 10) }, dp(42), dp(42))
        val titles = column().apply { setPadding(dp(12), 0, 0, 0) }
        titles.add(text(title, 22f, bold = true)); titles.add(text(subtitle, 10f, muted))
        heading.add(titles, 0, weight = 1f)
        togglePanel?.let { heading.add(panelToggle(it), dp(44), dp(44)) }
        heading.add(icon(R.drawable.ic_appearance, "外观模式：${Appearance.labels[Appearance.selected(this)]}") { appearanceMenu() }
            .apply { tag = "appearance" }, dp(44), dp(44))
        heading.add(icon(R.drawable.ic_settings, "设置") { settingsPage() }.apply { tag = "settings" }, dp(44), dp(44))
        shell.add(heading, top = 0)
        setContentView(shell)
        return shell
    }

    private fun home() {
        audioPlayer?.release(); audioPlayer = null
        detailId = null; detailState = null; detailError = null; detailSync = null
        page = "home"
        homeRoot?.let {
            shell = it
            setContentView(it)
            renderHomeStatus()
            if (listDirty) filterList()
            refresh()
            return
        }
        val panel = CollapsiblePanel(this).apply { tag = "home-panel"; startCollapsed = restoreCollapsed }
        homePanel = panel
        base("小米声桥", "录音与文字，一起抵达。", togglePanel = panel)
        val card = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(surface) }
        status = text("", 12f, muted)
        summary = text("", 20f, bold = true)
        coverage = text("", 12f, muted).apply { tag = "sync-coverage" }
        note = text("", 12f, muted)
        card.add(status); card.add(summary, top = 6); card.add(coverage, top = 4); card.add(note, top = 4)
        card.add(text("查看任务日志", 12f, blue).apply {
            setOnClickListener { showTaskLog() }
            setPadding(0, dp(5), 0, dp(5))
        }, ViewGroup.LayoutParams.WRAP_CONTENT)
        authorize = button("连接 Shizuku") { connectShizuku() }
        card.add(authorize, top = 10)
        val actions = row()
        scan = button("刷新录音") { if (Operation.scan.busy) stop("scan") else start("scan") }
        sync = button("一键同步", true) { if (Operation.sync.busy) stop("sync") else start("sync") }
        actions.add(scan, 0, dp(44), 1f)
        actions.add(Space(this), dp(10), 1)
        actions.add(sync, 0, dp(44), 1f)
        card.add(actions, top = 12)
        panel.addView(column().apply { setPadding(0, dp(12), 0, 0); add(card) })
        shell.add(panel)
        val input = EditText(this).apply {
            hint = "搜索录音名称"; textSize = 14f; setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0); background = rounded(surface, 12)
            setText(search)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { search = s.toString(); filterList() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        shell.add(input, height = dp(44), top = 12)
        chips = row()
        renderChips()
        shell.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, top = 8)
        list = PanelListView(this).apply {
            this.panel = panel
            divider = null; selector = ColorDrawable(Color.TRANSPARENT); setPadding(0, dp(8), 0, 0)
            clipToPadding = false
            adapter = recordingAdapter
            setOnItemClickListener { _, _, position, _ -> detail(visible[position]) }
        }
        val empty = text("点击“刷新录音”，读取手机里的录音与已有转写。", 14f, muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(25), dp(28), dp(25), dp(28))
        }
        shell.add(empty); list.emptyView = empty
        shell.add(list, height = 0, weight = 1f)
        homeRoot = shell
        if (records.isNotEmpty()) filterList()
        refresh()
    }

    private data class RecordingRow(val root: LinearLayout, val title: TextView, val date: TextView,
                                    val state: TextView, val flags: TextView)

    private val recordingAdapter = object : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = visible[position]
            val holder = convertView?.tag as? RecordingRow ?: run {
                val wrap = column().apply { setPadding(0, 0, 0, dp(7)) }
                val card = column().apply { background = rounded(surface, 12); setPadding(dp(13), dp(11), dp(13), dp(11)) }
                val title = text("", 15f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                val date = text("", 11f, muted)
                val state = text("", 11f)
                val flags = text("", 11f, muted)
                card.add(title)
                val bottom = row()
                bottom.add(date, 0, weight = 1f)
                bottom.add(state, ViewGroup.LayoutParams.WRAP_CONTENT)
                card.add(bottom, top = 5); card.add(flags, top = 3); wrap.add(card)
                RecordingRow(wrap, title, date, state, flags).also { wrap.tag = it }
            }
            val status = when {
                Operation.sync.running && Operation.currentId == r.id -> "同步中"
                r.id in Operation.queuedIds -> if (r.id in Operation.manualIds) "优先排队" else "等待同步"
                else -> displayStates[r.id] ?: "读取中"
            }
            holder.title.text = r.title
            holder.date.text = "${date(r.created)}  ·  ${MetadataParser.formatTime(r.duration)}"
            holder.state.text = status
            holder.state.setTextColor(when {
                status == "已同步" -> successColor
                status == "同步失败" -> danger
                else -> blue
            })
            val flags = if (r.transcript.isBlank()) "缺少转写" else "转写 ${r.transcript.length} 字符"
            holder.flags.text = "$flags${if (r.summary.isNotBlank()) "  ·  有摘要" else ""}  ·  ${bytes(r.size)}"
            // ListView delays item-click dispatch for its pressed-state animation. A child
            // click runs on release and is still cancelled when ListView intercepts a drag.
            // Rebind every time: a recycled card must open its current recording.
            holder.root.setOnClickListener { detail(r) }
            return holder.root
        }
    }

    private fun refresh(reloadConfig: Boolean = false) {
        if (page != "home" || !::status.isInitialized) return
        renderHomeStatus()
        pendingConfigReload = pendingConfigReload || reloadConfig
        if (refreshInFlight) { refreshPending = true; return }
        refreshInFlight = true
        val loadConfig = pendingConfigReload; pendingConfigReload = false
        val epoch = configEpoch
        val priorConfig = config; val priorRecords = records; val priorStamp = cacheScan
        uiWorker.execute {
            try {
                val loadedConfig = if (loadConfig) runCatching { settings.load() }.getOrDefault(priorConfig) else priorConfig
                val stamp = settings.getInfo("lastScan")
                val loaded = if (priorStamp != stamp) store.recordings() else priorRecords
                val loadedMarks = store.marks(loadedConfig.destination)
                val states = loaded.associate {
                    it.id to syncDisplayState(it, loadedMarks[it.id] ?: SyncMark(), loadedConfig.obsidianDestination)
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    refreshInFlight = false
                    if (epoch == configEpoch) {
                        listDirty = listDirty || loaded !== records || loadedMarks != marks || states != displayStates
                        config = loadedConfig; records = loaded; marks = loadedMarks; displayStates = states; cacheScan = stamp
                        if (page == "home") {
                            renderHomeStatus()
                            if (listDirty) filterList()
                        }
                    } else refreshPending = true
                    if (refreshPending) { refreshPending = false; refresh() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    refreshInFlight = false
                    if (!isDestroyed && page == "home") note.text = "本地列表读取失败，请重试刷新"
                }
            }
        }
    }

    private fun TextView.updateText(value: String) { if (text.toString() != value) text = value }

    private fun syncDisplayState(r: Recording, mark: SyncMark, obsidianDestination: String): String {
        val state = store.state(r, mark, obsidianDestination)
        return if (Operation.sync.busy && Operation.currentId == r.id && Operation.currentWasSynced &&
            state != "已同步" && state != "同步失败" && r.available && r.skipReason.isEmpty()) "已同步" else state
    }

    private fun renderHomeStatus() {
        val ready = BridgeClient.ready()
        status.updateText("${if (ready) "● 读取服务已就绪" else "○ 需要连接 Shizuku"}    ·    ${if (config.configured) "WebDAV 已配置" else "未配置 WebDAV"}")
        val completed = displayStates.values.count { it == "已同步" }
        summary.updateText(if (cacheScan == null) "正在读取本地录音…" else "${records.size} 条录音   ${completed} 条已同步")
        val missing = records.count { it.transcript.isBlank() }
        coverage.updateText(if (missing > 0) "$missing 条缺少转写 · 转写生成后可补同步" else "全部录音均有转写")
        coverage.visibility = if (cacheScan == null || records.isEmpty()) View.GONE else View.VISIBLE
        if (::list.isInitialized) (list.emptyView as? TextView)?.updateText(when {
            cacheScan == null -> "正在读取本地录音…"
            records.isEmpty() -> "点击“刷新录音”，读取手机里的录音与已有转写。"
            else -> "没有符合条件的录音"
        })
        val last = when {
            Operation.running || Operation.starting -> Operation.message + if (Operation.byteTotal > 0) "  ${bytes(Operation.bytes)}/${bytes(Operation.byteTotal)}" else ""
            Operation.message.isNotBlank() -> Operation.message
            settings.getInfo("operationInProgress") == "true" -> "上次同步被中断，重新同步会核对已完成文件"
            else -> settings.getInfo("lastOperation").ifBlank { "保留原始录音习惯，自动配对已有转写。" }
        }
        note.updateText(last)
        val busy = Operation.running || Operation.starting
        note.setTextColor(if (!busy && settings.getInfo("lastOperationFailed") == "true") danger else muted)
        authorize.visibility = if (ready) View.GONE else View.VISIBLE
        scan.isEnabled = true
        scan.updateText(if (Operation.scan.busy) "停止刷新" else "刷新录音")
        sync.updateText(if (Operation.sync.busy) "停止同步" else "一键同步")
        val completion = settings.getInfo("lastCompletion")
        if (!busy && completion.isNotEmpty() && shownCompletion != completion) {
            shownCompletion = completion
            if (settings.getInfo("lastOperationFailed") == "true") {
                AlertDialog.Builder(this).setTitle(if (settings.getInfo("lastOperationAction") == "scan") "刷新失败" else "同步未完成")
                    .setMessage(settings.getInfo("lastOperation"))
                    .setPositiveButton("知道了", null).setNeutralButton("查看任务日志") { _, _ -> showTaskLog() }.show()
            }
        }
    }

    private fun renderChips() {
        chips.removeAllViews()
        for (label in listOf("全部", "待同步", "已同步", "失败")) {
            val chip = text(label, 12f, if (filter == label) Color.WHITE else muted).apply {
                gravity = Gravity.CENTER; setPadding(dp(13), dp(7), dp(13), dp(7))
                background = rounded(if (filter == label) primaryFill else Color.TRANSPARENT, 20)
                setOnClickListener { filter = label; renderChips(); filterList() }
            }
            chips.add(chip, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun filterList() {
        visible = records.filter { r ->
            val state = displayStates[r.id] ?: "读取中"
            r.title.contains(search, ignoreCase = true) && when (filter) {
                "已同步" -> state == "已同步"
                "失败" -> state == "同步失败"
                "待同步" -> state != "已同步" && state != "同步失败"
                else -> true
            }
        }
        listDirty = false
        if (::list.isInitialized) recordingAdapter.notifyDataSetChanged()
        restoredPosition?.let { (position, offset) ->
            if (visible.isNotEmpty()) { list.setSelectionFromTop(position, offset); restoredPosition = null }
        }
    }

    private fun detail(r: Recording) {
        audioPlayer?.release(); audioPlayer = null
        page = "detail"
        detailId = r.id
        val panel = CollapsiblePanel(this).apply { tag = "detail-panel" }
        base("录音详情", back = true, togglePanel = panel)
        val header = column().apply { tag = "detail-header" }
        val titleRow = row()
        titleRow.add(text(r.title, 19f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, 0, weight = 1f)
        titleRow.add(button("同步", true) { start("sync", r.id) }.apply {
            tag = "sync-recording"; contentDescription = "同步此条录音"; textSize = 12f
            minHeight = 0; minimumHeight = 0; setPadding(dp(8), 0, dp(8), 0)
            detailSync = this
        }, dp(60), dp(36))
        header.add(titleRow, top = 4)
        val mark = marks[r.id] ?: SyncMark()
        header.add(text((displayStates[r.id] ?: if (r.available) "未同步" else "源音频不可用") +
            if (r.transcript.isBlank()) " · 缺少转写" else "", 12f, blue).apply { detailState = this; maxLines = 2 }, top = 2)
        header.add(text("${date(r.created)}  ·  ${MetadataParser.formatTime(r.duration)}  ·  ${bytes(r.size)}", 11f, muted), top = 2)
        header.add(text(mark.error, 12f, danger).apply { maxLines = 2; visibility = if (mark.error.isBlank()) View.GONE else View.VISIBLE; detailError = this }, top = 3)
        if (r.skipReason.isNotEmpty()) header.add(text(r.skipReason, 12f, muted).apply { maxLines = 2 }, top = 3)
        val controls = row()
        val play = icon(R.drawable.ic_play, "播放") { audioPlayer?.toggle() }.apply { tag = "audio-play"; isEnabled = r.available && r.skipReason.isEmpty() }
        val stop = icon(R.drawable.ic_stop, "停止") { audioPlayer?.stop() }.apply { tag = "audio-stop"; isEnabled = false }
        val elapsed = text("00:00 / ${MetadataParser.formatTime(r.duration)}", 12f, muted).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; tag = "audio-time" }
        controls.add(play, dp(40), dp(40)); controls.add(stop, dp(40), dp(40)); controls.add(elapsed, 0, dp(40), 1f)
        header.add(controls, top = 2)
        val seek = SeekBar(this).apply { tag = "audio-seek"; isEnabled = false; setPadding(dp(8), 0, dp(8), 0) }
        header.add(seek, height = dp(26))
        var dragging = false
        fun clock(position: Int, total: Int) = "${MetadataParser.formatTime(position / 1000L)} / ${MetadataParser.formatTime(total / 1000L)}"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) elapsed.text = clock(progress, bar.max) }
            override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; audioPlayer?.seekTo(bar.progress) }
        })
        audioPlayer = RecordingPlayer(this, { BridgeClient.get().openAudio(r.path) }, { state ->
            play.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
            play.contentDescription = if (state.playing) "暂停" else "播放"
            play.isEnabled = !state.loading && r.available && r.skipReason.isEmpty()
            stop.isEnabled = state.ready || state.loading; seek.isEnabled = state.ready
            if (!dragging) {
                seek.max = state.duration; seek.progress = state.position
                elapsed.text = if (state.loading) "正在读取音频…" else if (state.ready) clock(state.position, state.duration)
                    else "00:00 / ${MetadataParser.formatTime(r.duration)}"
            }
        }, ::toast)
        panel.addView(header)
        shell.add(panel)
        val blocks = DetailPreview.blocks(r)
        val content = PanelListView(this).apply {
            this.panel = panel
            expandOnlyAtTop = false
            tag = "detail-content"
            divider = null; selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; setPadding(0, dp(8), 0, dp(12))
        }
        val section = row().apply { setPadding(0, dp(8), 0, dp(10)) }
        if (mark.time > 0) content.addHeaderView(text("最近同步：${date(mark.time, true)}", 11f, muted), null, false)
        section.add(text("转写正文", 16f, bold = true), 0, weight = 1f)
        if (r.transcript.isNotBlank()) section.add(text("复制全文", 12f, blue).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(12), 0, 0, 0)
            contentDescription = "复制完整转写"
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(r.title, r.transcript))
            }
        }, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addHeaderView(section, null, false)
        content.adapter = object : BaseAdapter() {
            override fun getCount() = blocks.size
            override fun getItem(position: Int) = blocks[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = blocks[position].role == PreviewRole.TRANSCRIPT || blocks[position].role == PreviewRole.SUMMARY
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val block = blocks[position]
                val view = convertView as? TextView ?: text("").apply {
                    includeFontPadding = false
                    breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                }
                val body = block.role == PreviewRole.TRANSCRIPT || block.role == PreviewRole.SUMMARY
                view.setTextIsSelectable(body)
                view.textSize = when (block.role) { PreviewRole.HEADING -> 16f; PreviewRole.NOTE -> 12f; else -> 14f }
                view.setTextColor(if (block.role == PreviewRole.NOTE) muted else ink)
                view.setTypeface(null, if (block.role == PreviewRole.HEADING) Typeface.BOLD else Typeface.NORMAL)
                view.setPadding(0, if (block.role == PreviewRole.HEADING) dp(18) else 0, 0, if (block.role == PreviewRole.HEADING) dp(10) else 0)
                view.text = block.text
                return view
            }
        }
        shell.add(content, height = 0, weight = 1f, top = 8)
        refreshDetail()
    }

    private fun renderDetailTask(r: Recording, mark: SyncMark) {
        val active = Operation.sync.running && Operation.currentId == r.id
        val queued = r.id in Operation.queuedIds
        val state = syncDisplayState(r, mark, config.obsidianDestination)
        val failed = Operation.results[r.id]?.failed == true
        val buttonLabel = when {
                active -> "同步中"
                queued -> "排队中"
                failed || state == "同步失败" -> "重试"
                state == "已同步" -> "已同步"
                state == "待更新" -> "更新"
                else -> "同步"
            }
        detailSync?.apply {
            updateText(buttonLabel)
            contentDescription = if (buttonLabel == "已同步") "此条录音已同步，再次点击可核对" else "同步此条录音"
        }
        detailState?.updateText(when {
            active -> Operation.sync.message + if (Operation.byteTotal > 0) "  ${bytes(Operation.bytes)}/${bytes(Operation.byteTotal)}" else ""
            queued -> "已加入优先同步，等待当前单条完成"
            failed -> "同步失败 · 可点击重试（${Operation.results[r.id]?.message.orEmpty()}）"
            else -> state + if (r.transcript.isBlank()) " · 缺少转写" else ""
        })
        detailError?.apply { updateText(mark.error); visibility = if (mark.error.isBlank()) View.GONE else View.VISIBLE }
    }
    private fun refreshDetail() {
        val id = detailId ?: return
        records.firstOrNull { it.id == id }?.let { renderDetailTask(it, marks[id] ?: SyncMark()) }
        if (detailReadPending) { detailRefreshPending = true; return }
        detailReadPending = true
        detailRefreshPending = false
        val destination = config.destination
        uiWorker.execute {
            val pair = runCatching { store.recording(id)?.let { it to store.mark(destination, it.id) } }.getOrNull()
            runOnUiThread {
                detailReadPending = false
                if (!isDestroyed && page == "detail" && detailId == id && pair != null) {
                    marks = marks + (id to pair.second)
                    val state = syncDisplayState(pair.first, pair.second, config.obsidianDestination)
                    if (displayStates[id] != state) {
                        displayStates = displayStates + (id to state)
                        listDirty = true
                    }
                    renderDetailTask(pair.first, pair.second)
                }
                if (detailRefreshPending) { detailRefreshPending = false; refreshDetail() }
            }
        }
    }

    private fun navigateBack() {
        when {
            page.startsWith("settings-") -> settingsPage()
            page != "home" -> home()
            else -> finish()
        }
    }

    private fun settingsPage() {
        page = "settings"
        base("设置", back = true)
        val content = column()
        fun entry(title: String, summary: String, id: String, action: () -> Unit) {
            val item = row().apply {
                tag = "settings-$id"
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(surface, 12)
                isClickable = true; isFocusable = true
                setOnClickListener { action() }
            }
            val labels = column()
            labels.add(text(title, 15f, bold = true))
            labels.add(text(summary, 12f, muted), top = 4)
            item.add(labels, width = 0, weight = 1f)
            item.add(text("›", 22f, muted).apply { gravity = Gravity.CENTER }, width = dp(28))
            content.add(item, top = 10)
        }
        entry("同步服务器", if (config.configured) "已配置 WebDAV · 管理目录与连接" else "尚未配置 WebDAV", "server") { serverSettingsPage() }
        entry("自动同步", if (AutoSync.enabled(this)) "已开启 · 管理同步与网络条件" else "已关闭 · 管理同步与网络条件", "auto") { autoSettingsPage() }
        entry("应用更新", "当前版本 ${BuildConfig.VERSION_NAME} · 手动检查更新", "update") { updateSettingsPage() }
        entry("读取授权", if (BridgeClient.ready()) "读取服务已就绪" else "需要连接或授权 Shizuku", "access") { accessSettingsPage() }
        content.add(text("声桥 ${BuildConfig.VERSION_NAME}", 11f, muted).apply { gravity = Gravity.CENTER }, top = 24)
        shell.add(ScrollView(this).apply { addView(content) }, height = 0, weight = 1f, top = 8)
    }

    private fun serverSettingsPage() {
        page = "settings-server"
        base("同步服务器", back = true)
        val form = column()
        val trace = com.yuhuo.shengqiao.data.WebDavTrace(this)
        fun input(label: String, value: String, hint: String, secret: Boolean = false): EditText {
            form.add(text(label, 13f, bold = true), top = 16)
            return EditText(this).apply {
                setText(value); this.hint = hint; textSize = 14f; setSingleLine(true)
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                setPadding(dp(12), 0, dp(12), 0); background = rounded(surface, 10)
                importantForAutofill = if (secret) View.IMPORTANT_FOR_AUTOFILL_YES else View.IMPORTANT_FOR_AUTOFILL_AUTO
                if (secret) {
                    val field = this
                    field.tag = "webdav-password"
                    var visible = false
                    val toggle = ImageButton(this@MainActivity).apply {
                        tag = "password-visibility"
                        contentDescription = "显示密码"
                        setImageResource(R.drawable.ic_visibility)
                        imageTintList = android.content.res.ColorStateList.valueOf(muted)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val ripple = android.util.TypedValue()
                        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                        setBackgroundResource(ripple.resourceId)
                    }
                    toggle.setOnClickListener {
                        val start = field.selectionStart; val end = field.selectionEnd
                        visible = !visible
                        field.transformationMethod = if (visible) null else android.text.method.PasswordTransformationMethod.getInstance()
                        toggle.setImageResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
                        toggle.contentDescription = if (visible) "隐藏密码" else "显示密码"
                        if (start >= 0 && end >= 0) field.setSelection(start, end)
                    }
                    field.background = ColorDrawable(Color.TRANSPARENT)
                    field.setPaddingRelative(dp(12), 0, dp(60), 0)
                    val passwordBox = FrameLayout(this@MainActivity).apply {
                        background = rounded(surface, 10)
                        addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        addView(toggle, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
                    }
                    form.add(passwordBox, height = dp(48), top = 7)
                } else form.add(this, height = dp(48), top = 7)
            }
        }
        val url = input("WebDAV 地址", config.url, "https://nas.example.com/dav/")
        val user = input("账号", config.user, "WebDAV 用户名，可留空匿名访问")
        val password = input("密码 / 应用专用密码", config.password, "仅加密保存在本机", true)
        val folder = input("主同步目录", config.folder, "/shengqiao")
        val obsidianFolder = input("Obsidian 转写目录", config.obsidianFolder, "/obsidian/小米录音")
        form.add(text("两个目录均相对于上面的 WebDAV 根目录，自动创建。Markdown 按录音创建月份归档，例如：\n/obsidian/小米录音/202610/10.13客满沟通会.md", 12f, muted), top = 8)
        val http = CheckBox(this).apply { text = "允许 HTTP（未加密，适用于可信内网）"; isChecked = config.allowHttp }
        form.add(http, top = 10)
        form.add(text("主目录保存音频、TXT、时间轴 JSON 和完成标记；Obsidian 目录另存 Markdown 转写，改名时同步移动原笔记。", 12f, muted), top = 8)
        val result = text("", 13f, blue).apply { tag = "connection-report"; setTextIsSelectable(true) }
        fun candidate(): DavConfig = DavConfig(url.text.toString().trim(), user.text.toString(), password.text.toString(),
            folder.text.toString().trim().ifBlank { "/shengqiao" }, http.isChecked, config.wifiOnly,
            obsidianFolder.text.toString().trim().ifBlank { "/obsidian/小米录音" })
        var testing = false
        lateinit var testButton: Button
        lateinit var saveButton: Button
        testButton = button("测试连接") {
            if (Operation.sync.busy) { toast("请等待当前同步结束"); return@button }
            if (testing) return@button
            val value = candidate()
            try { WebDav(value); WebDav(value.obsidianConfig) } catch (e: Exception) { result.text = e.message; return@button }
            testing = true; testButton.isEnabled = false; saveButton.isEnabled = false
            trace.reset()
            trace.append("开始检测；账号${if (value.user.isBlank()) "未填写（匿名）" else "已填写"}；密码${if (value.password.isEmpty()) "未填写" else "已填写"}")
            trace.append("仅记录方法、路径和状态，不记录密码、认证请求头或响应正文")
            result.text = "检测中：准备主目录与 Obsidian 目录…"
            worker.execute {
                var stage = "主同步目录"
                val reports = linkedMapOf("主同步目录" to "待检测", "Obsidian 转写目录" to "待检测")
                fun show(title: String) {
                    val snapshot = "$title\n\n" + reports.entries.joinToString("\n\n") { "${it.key}\n${it.value}" }
                    runOnUiThread { result.text = snapshot }
                }
                try {
                    trace.append("目标：主同步目录")
                    WebDav(value, diagnostic = trace::append).also { activeTest = it }.testConnection {
                        reports["主同步目录"] = it; show("检测中：主同步目录")
                    }
                    stage = "Obsidian 转写目录"
                    trace.append("目标：Obsidian 转写目录")
                    WebDav(value.obsidianConfig, diagnostic = trace::append).also { activeTest = it }.testConnection {
                        reports["Obsidian 转写目录"] = it; show("检测中：Obsidian 转写目录")
                    }
                    show("连接成功：两个目录各阶段均通过")
                } catch (e: Exception) {
                    if (reports["Obsidian 转写目录"] == "待检测") reports["Obsidian 转写目录"] = "未执行（主目录检测未通过）"
                    show("连接失败：$stage")
                } finally {
                    activeTest = null
                    runOnUiThread { testing = false; testButton.isEnabled = true; saveButton.isEnabled = true }
                }
            }
        }
        form.add(testButton, top = 16)
        form.add(result, top = 8)
        form.add(button("查看诊断日志") {
            val log = text(trace.read(), 12f).apply { setTextIsSelectable(true); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            AlertDialog.Builder(this).setTitle("最近一次连接检测日志")
                .setView(ScrollView(this).apply { addView(log) }).setPositiveButton("关闭", null).show()
        }, top = 8)
        saveButton = button("保存配置", true) {
            if (Operation.sync.busy) { toast("请等待当前同步结束"); return@button }
            try {
                val value = candidate(); WebDav(value); WebDav(value.obsidianConfig); AutoSync.cancelForManual(); settings.save(value); configEpoch++; config = value
                toast("配置已保存"); settingsPage()
            } catch (e: Exception) { result.text = e.message }
        }
        form.add(saveButton, top = 12)
        form.add(text("凭据使用 Android Keystore 加密保存在本机。", 11f, muted), top = 18)
        shell.add(ScrollView(this).apply { addView(form) }, height = 0, weight = 1f, top = 8)
    }

    private fun autoSettingsPage() {
        page = "settings-auto"
        base("自动同步", back = true)
        val form = column()
        form.add(Switch(this).apply {
            tag = "auto-sync-enabled"; text = "每小时自动检查并同步"; textSize = 14f
            isChecked = AutoSync.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !config.configured) { toast("请先保存 WebDAV 配置"); isChecked = false }
                else runCatching { AutoSync.setEnabled(this@MainActivity, checked) }.onFailure { toast(it.message ?: "自动同步设置失败") }
            }
        }, height = dp(48), top = 8)
        form.add(Switch(this).apply {
            tag = "sync-wifi-only"; text = "手动同步仅使用 Wi-Fi"; textSize = 14f
            isChecked = config.wifiOnly
            setOnCheckedChangeListener { switch, checked ->
                if (Operation.sync.busy) {
                    toast("请等待当前同步结束")
                    switch.setOnCheckedChangeListener(null); switch.isChecked = config.wifiOnly
                    autoSettingsPage()
                } else {
                    runCatching {
                        val updated = config.copy(wifiOnly = checked)
                        AutoSync.cancelForManual(); settings.save(updated); config = updated; configEpoch++
                    }.onFailure {
                        toast(it.message ?: "网络设置保存失败")
                        switch.setOnCheckedChangeListener(null); switch.isChecked = config.wifiOnly
                        autoSettingsPage()
                    }
                }
            }
        }, height = dp(48), top = 8)
        form.add(text("关闭后，手动同步也可以使用移动网络。自动同步始终需要非按流量计费的 Wi-Fi。", 12f, muted), top = 6)
        form.add(text("不常驻后台。系统约每小时调度，非按流量计费的 Wi-Fi 且电量充足时检查，有增量才上传。省电策略可能延后执行；Shizuku 不可用时跳过，手动操作优先。", 12f, muted), top = 6)
        val autoTime = settings.getInfo("autoLastRun").toLongOrNull()
        form.add(text(if (autoTime == null) "尚无自动检查记录" else "最近检查：${date(autoTime, true)}\n${settings.getInfo("autoLastResult")}", 12f, muted), top = 6)
        shell.add(ScrollView(this).apply { addView(form) }, height = 0, weight = 1f, top = 8)
    }

    private fun updateSettingsPage() {
        page = "settings-update"
        base("应用更新", back = true)
        val form = column()
        form.add(text("当前版本 ${BuildConfig.VERSION_NAME}", 14f, bold = true), top = 10)
        val proxy = Switch(this).apply {
            tag = "update-proxy"
            text = "使用国内 GitHub 代理"; textSize = 14f
            isChecked = settings.getInfo("updateProxy", "true") == "true"
            setOnCheckedChangeListener { _, checked ->
                settings.info("updateProxy", checked.toString())
                val release = activeUpdateRelease
                val status = activeUpdateStatus
                if (updateBusy && release != null && status != null && activeUpdateProxy != checked)
                    restartUpdateDownload(release, checked, status)
            }
        }
        form.add(proxy, height = dp(48), top = 8)
        form.add(text("手动检查新版本；启用代理后，检查和下载会依次尝试多个第三方代理。", 12f, muted), top = 6)
        val updateStatus = text("", 12f, muted)
        val updateButton = button("检查更新") {
            if (updateBusy) { toast("正在处理更新"); return@button }
            updateBusy = true
            val useProxy = proxy.isChecked
            updateStatus.text = "正在检查新版本…"
            updateWorker.execute {
                val result = runCatching { ReleaseClient().latest(useProxy) }
                runOnUiThread {
                    updateBusy = false
                    if (isFinishing || isDestroyed || page != "settings-update") return@runOnUiThread
                    result.onSuccess { release ->
                        if (!ReleaseClient.newer(release.version, BuildConfig.VERSION_NAME)) {
                            updateStatus.text = "当前已是最新版本（${BuildConfig.VERSION_NAME}）"
                        } else {
                            updateStatus.text = "发现新版本 ${release.version}"
                            AlertDialog.Builder(this).setTitle("发现新版本 ${release.version}")
                                .setMessage("当前版本 ${BuildConfig.VERSION_NAME}。点击更新后下载，并通过系统安装界面确认。")
                                .setNegativeButton("稍后", null)
                                .setPositiveButton("更新") { _, _ ->
                                    downloadUpdate(release, proxy.isChecked, updateStatus)
                                }.show()
                        }
                    }.onFailure { updateStatus.text = "检查更新失败：${it.message ?: "请稍后重试"}" }
                }
            }
        }.apply { tag = "check-update" }
        form.add(updateButton, top = 10)
        form.add(updateStatus, top = 6)
        shell.add(ScrollView(this).apply { addView(form) }, height = 0, weight = 1f, top = 8)
    }

    private fun accessSettingsPage() {
        page = "settings-access"
        base("读取授权", back = true)
        val form = column()
        form.add(text(if (BridgeClient.ready()) "读取服务已就绪" else "需要连接或授权 Shizuku", 14f, bold = true), top = 10)
        form.add(text("安装并启动 Shizuku 后，允许声桥读取录音。手机重启后可通过 USB 或自行选择无线调试重新启动服务。", 12f, muted), top = 8)
        form.add(button("打开 / 授权 Shizuku") { connectShizuku() }, top = 10)
        form.add(button("Shizuku 官方使用说明") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/zh-hans/guide/setup/"))) }, top = 8)
        form.add(text("声桥 ${BuildConfig.VERSION_NAME}  ·  仅同步到你配置的服务器\n凭据使用 Android Keystore 加密；不删除手机或 NAS 上的原始录音。", 11f, muted), top = 24)
        shell.add(ScrollView(this).apply { addView(form) }, height = 0, weight = 1f, top = 8)
    }

    private fun updateFile() = File(cacheDir, "updates/update.apk")

    private fun downloadUpdate(release: Release, useProxy: Boolean, status: TextView) {
        if (updateBusy) return
        updateBusy = true
        val generation = updateGeneration.incrementAndGet()
        activeUpdateRelease = release; activeUpdateStatus = status; activeUpdateProxy = useProxy
        status.text = "正在下载更新…"
        enqueueUpdateDownload(release, useProxy, status, generation)
    }

    private fun restartUpdateDownload(release: Release, useProxy: Boolean, status: TextView) {
        val generation = updateGeneration.incrementAndGet()
        activeUpdateClient?.cancel()
        activeUpdateProxy = useProxy
        status.text = "代理已切换，正在取消旧下载并重新开始…"
        enqueueUpdateDownload(release, useProxy, status, generation)
    }

    private fun enqueueUpdateDownload(release: Release, useProxy: Boolean, status: TextView, generation: Long) {
        updateWorker.execute {
            val result = runCatching {
                if (generation != updateGeneration.get()) throw java.io.InterruptedIOException("更新下载已取消")
                val file = updateFile()
                check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) { "无法创建下载目录" }
                val client = ReleaseClient()
                activeUpdateClient = client
                try {
                    if (generation != updateGeneration.get()) client.cancel()
                    client.download(release, useProxy, file) { done, total ->
                        val percent = (done * 100 / total).toInt()
                        runOnUiThread {
                            if (generation == updateGeneration.get() && !isDestroyed && page == "settings-update")
                                status.text = "正在下载更新：$percent%"
                        }
                    }
                    validateUpdate(file, release.version)
                } finally {
                    if (activeUpdateClient === client) activeUpdateClient = null
                }
            }
            runOnUiThread {
                if (generation != updateGeneration.get()) return@runOnUiThread
                updateBusy = false
                activeUpdateClient = null
                activeUpdateRelease = null; activeUpdateStatus = null; activeUpdateProxy = null
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess {
                    status.text = "下载并校验完成"
                    installUpdate()
                }.onFailure {
                    updateFile().delete()
                    status.text = "更新失败：${it.message ?: "请重新检查更新"}"
                }
            }
        }
    }

    private fun validateUpdate(file: File, expectedVersion: String? = null) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("下载的文件不是有效的安装包")
        val installed = packageManager.getPackageInfo(packageName, flags)
        check(archive.packageName == packageName && archive.longVersionCode > installed.longVersionCode &&
            (expectedVersion == null || archive.versionName == expectedVersion)) { "安装包的应用或版本不匹配" }
        val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        val archiveSigners = archive.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        check(!installedSigners.isNullOrEmpty() && installedSigners == archiveSigners) { "安装包签名与当前应用不一致" }
    }

    private fun installUpdate() {
        try {
            validateUpdate(updateFile())
            if (!packageManager.canRequestPackageInstalls()) {
                pendingUpdateInstall = true
                startActivityForResult(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")), 102)
                return
            }
            pendingUpdateInstall = false
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", updateFile())
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { pendingUpdateInstall = false; toast("无法安装更新：${e.message}") }
    }

    @Deprecated("Android install permission settings return a result through the activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102 && pendingUpdateInstall) {
            if (packageManager.canRequestPackageInstalls()) installUpdate()
            else { pendingUpdateInstall = false; toast("未允许安装应用，请授权后重新更新") }
        }
    }

    private fun connectShizuku() {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            if (BridgeClient.ready()) { toast("Shizuku 已授权"); return }
            runCatching { Shizuku.requestPermission(100) }.onFailure { toast("请在 Shizuku 中授权声桥") }
            return
        }
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) AlertDialog.Builder(this).setTitle("读取服务尚未启动")
            .setMessage("不必开启无线调试。你可以用 USB 连接电脑启动 Shizuku；希望离开电脑启动时，再选择无线调试。\n\n服务启动后返回声桥，会自动连接并继续刚才的任务。")
            .setPositiveButton("打开 Shizuku") { _, _ -> startActivity(launch) }
            .setNegativeButton("稍后", null).show()
        else AlertDialog.Builder(this).setTitle("需要 Shizuku")
            .setMessage("声桥通过 Shizuku 读取小米录音与已有转写，无需手动导入。请先安装并启动 Shizuku。")
            .setPositiveButton("前往官方下载") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
            .setNegativeButton("稍后", null).show()
    }
    private fun start(action: String, id: String? = null) {
        if (action == "scan" && Operation.scan.busy) { toast("正在刷新录音"); return }
        if (action == "sync" && id == null && Operation.sync.busy) { toast("批量同步正在进行；可以在详情优先同步单条"); return }
        if (!BridgeClient.ready()) { pendingAction = action to id; connectShizuku(); return }
        if (action == "sync" && !config.configured) { serverSettingsPage(); toast("请先配置 WebDAV"); return }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        Operation.prepare(action, id)
        runCatching { startForegroundService(Intent(this, SyncService::class.java).setAction(action).apply { id?.let { putExtra("id", it) } }) }
            .onFailure {
                if (action == "scan") Operation.scan = Operation.scan.copy(starting = false)
                else {
                    Operation.sync = Operation.sync.copy(starting = false)
                    if (id != null) { Operation.queuedIds = Operation.queuedIds - id; Operation.manualIds = Operation.manualIds - id }
                }
                toast("启动失败：${it.message}")
            }
        if (id == null) home() else refreshDetail()
    }
    private fun showTaskLog() {
        val log = text(com.yuhuo.shengqiao.data.WebDavTrace(this, "task-diagnostic.log").read(), 12f).apply {
            setTextIsSelectable(true); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this).setTitle("最近一次刷新 / 同步日志")
            .setView(ScrollView(this).apply { addView(log) }).setPositiveButton("关闭", null).show()
    }
    private fun stop(action: String) { startService(Intent(this, SyncService::class.java).setAction("stop-$action")) }
    private fun resumePending() {
        val pending = pendingAction ?: return
        if (BridgeClient.ready()) {
            pendingAction = null
            start(pending.first, pending.second)
        }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun date(value: Long, time: Boolean = false): String {
        if (value <= 0) return "时间未知"
        val ms = if (value < 10_000_000_000L) value * 1000 else value
        return SimpleDateFormat(if (time) "yyyy/MM/dd HH:mm" else "yyyy/MM/dd", Locale.CHINA).format(Date(ms))
    }
    private fun bytes(value: Long): String = if (value >= 1024 * 1024) "%.1f MB".format(value / 1048576.0) else "%.0f KB".format(value / 1024.0)
    override fun onDestroy() {
        appearancePopup?.dismiss(); appearancePopup = null
        audioPlayer?.release(); audioPlayer = null
        updateGeneration.incrementAndGet(); activeUpdateClient?.cancel()
        unregisterReceiver(receiver); Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead); Shizuku.removeRequestPermissionResultListener(permission)
        activeTest?.abort(); worker.shutdown(); uiWorker.shutdownNow(); updateWorker.shutdownNow(); store.close(); super.onDestroy()
    }
}
