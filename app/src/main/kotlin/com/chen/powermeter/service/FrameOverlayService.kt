package com.chen.powermeter.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.content.res.Configuration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chen.powermeter.MainActivity
import com.chen.powermeter.R
import com.chen.powermeter.ui.formatLiveFps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 帧率悬浮窗的前台服务（2026-09-22 新增）。
 *
 * 为什么从 Compose 内嵌悬浮窗改成系统悬浮窗：Compose 的 tab 只活在应用自己的窗口里，
 * 一切到被测应用（游戏/视频）它就没了 —— 而帧率监测的核心场景恰恰是「人在别的应用里看帧率」。
 * 改用 `TYPE_APPLICATION_OVERLAY` 后 tab 盖在**任何**应用上方，常显实时帧率，
 * 点按开始/停止录制；需要 `SYSTEM_ALERT_WINDOW` 特殊权限（设置页开关，见 FrameMeterScreen 引导）。
 *
 * 结构（原生 View，不用 ComposeView —— 服务 + overlay 场景下原生视图更轻也更稳）：
 * - **帧率 tab**：43×25dp 胶囊，等宽粗体数字，**不论是否录制都恒显实时帧率**；
 *   未录制 = 浅绿底黑字，录制中 = **热烈红底白字**（2026-09-22 用户口径：录制态必须
 *   一眼可辨，底色直接变红，不再做小红点之类的小装饰）；单指拖动（位置不持久化，
 *   每次开窗回到默认位），轻点 = 出面板 / 停止并落库；
 * - **录制时长窗口**：跟在 tab 正下方、左对齐，半透明黑 `0xCC1E1E24`；仅两行（标题 +
 *   5/10/15/30 四个胶囊），**5s 无点击自动隐藏**，挑完立即按该时长开录（录制中挑则改本场
 *   限时）并收起；**只随轻点 tab 出现**——悬浮窗刚打开时不显示（2026-09-22 用户口径：
 *   开 tab ≠ 挑时长，面板是「点 tab 开始录制」的伴随物）；每次开窗都不预选（见
 *   [FrameRecordController.clearLimit]）

 *
 * 必须是前台服务：悬浮窗本身不能阻止 Android 12+ 冻结缓存进程，进程一冻结，
 * 预览采样循环就停了，帧率会变成一个再也不动的死数字。
 */
class FrameOverlayService : Service() {

    companion object {
        /** 与帧率录制共用同一个低重要性渠道，用户可一并关闭这两类常驻通知 */
        private const val CHANNEL_ID = "frame_record"

        private const val NOTIFY_ID = 0x104

        /** 录制时长可选项（分钟），与旧的 Compose 时长窗口同一组值 */
        private val DURATIONS = intArrayOf(5, 10, 15, 30)

        /**
         * miuix 官方默认主色（蓝）。
         *
         * 取证而非拍脑袋：`miuix-ui 0.9.2` → `miuix-ui-android-0.9.2-sources.jar`
         * → `commonMain/.../theme/Colors.kt:342` 的 light palette `primary = Color(0xFF3482FF)`
         * （深色主题为 `0xFF277AF7`）。悬浮窗盖在第三方应用上，底色明暗不可控，
         * 故统一取 light 那一档，白字对比度约 3.6:1、足够醒目。
         */
        private const val COLOR_MIUIX_BLUE = 0xFF3482FF.toInt()

        /**
         * tab 底色 / 时长胶囊选中色的浅绿（Material Green 200）——**未录制态**。
         *
         * 亮度高 ⇒ 走 [textColorFor] 会自动配黑字。
         */
        private const val COLOR_MINT = 0xFFA5D6A7.toInt()

        /**
         * tab **录制中**的底色（Material Red 600，「热烈红」，2026-09-22 用户指定方向）。
         *
         * 相对亮度约 0.14 → [textColorFor] 自动配白字；与浅绿未录制态对比强烈，
         * 录制是否在进行，余光扫一眼底色就能分辨。
         */
        private const val COLOR_RECORDING_RED = 0xFFE53935.toInt()

        /**
         * 时长窗口底色：**半透明黑**（用户指定）。
         *
         * 取 `-0x1E1E24` 那档深灰黑再压到 80% 不透明：文字一律走白/浅色，
         * 四个时长胶囊的高亮靠独立的浅绿底，底色本身不做任何额外处理。
         */
        private const val COLOR_PANEL_BG = 0xCC1E1E24.toInt()

        /** 未选中胶囊的填充：半透明白，压在半透明黑面板上仍能看出边界 */
        private const val COLOR_CHIP_IDLE = 0x33FFFFFF.toInt()

        private val _running = MutableStateFlow(false)

        /** 悬浮窗是否在显示；UI 的 FAB 据此切换 + / × 图标 */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FrameOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FrameOverlayService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    /** tab 根视图（等宽帧率数字，恒显实时值） */
    private var pillView: TextView? = null
    /**
     * tab 当前底色，文字色由它反算（红底白字 / 绿底黑字）。
     * 写入方 [applyPillState] 与 [updatePillText] 都跑在主线程（Main.immediate），无需并发防护。
     */
    private var pillBgColor: Int = COLOR_MINT
    private var pillParams: WindowManager.LayoutParams? = null
    /** tab 尺寸（attach 时按当前 density 算一次；onConfigurationChanged 重贴边时要用） */
    private var pillW = 0
    private var pillH = 0
    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var chipViews: List<TextView> = emptyList()
    private var viewsAdded = false
    private var subscribed = false

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val hidePanelRunnable = Runnable { hidePanel() }

    // ── 生命周期 ──────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 悬浮窗一出现，帧率读数就该是活的（预览采样，不产样本不落库）
        FrameRecordController.startPreview()
        // 重新开窗 = 新一轮选择：四个时长胶囊都不高亮（详见 FrameRecordController.clearLimit）
        FrameRecordController.clearLimit()
        _running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ startForeground 必须先行（startForegroundService 后 5s 内完成，同 FrameRecordService）
        startForeground(NOTIFY_ID, buildNotification())
        if (!viewsAdded) {
            addViews()
            viewsAdded = true
        }
        if (!subscribed) {
            subscribed = true
            observeController()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (viewsAdded) {
            windowManager?.let { wm ->
                pillView?.let { runCatching { wm.removeView(it) } }
                panelView?.let { runCatching { wm.removeView(it) } }
            }
            viewsAdded = false
        }
        pillView = null
        panelView = null
        // 关悬浮窗 = 一并停掉预览；若还在录制，先停并落库（与旧的 Compose 悬浮窗同一语义）
        if (FrameRecordController.recording.value) FrameRecordController.stop()
        FrameRecordController.stopPreview()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    // ── 视图搭建 ──────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addViews() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        pillW = dp(43f)
        pillH = dp(25f)

        // tab 内只有一行等宽数字（恒为实时帧率），不挂任何状态装饰
        val pill = TextView(this).apply {
            text = formatLiveFps(FrameRecordController.fps.value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8.5f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setSingleLine(true)
        }
        pillView = pill
        applyPillState(FrameRecordController.recording.value)

        val params = WindowManager.LayoutParams(
            pillW,
            pillH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_LAYOUT_NO_LIMITS：允许窗口越过状态栏 / 导航栏占到**真正的屏幕边缘**。
            // 系统悬浮球（同持 SYSTEM_ALERT_WINDOW）能贴边就是靠它；不加的话 WindowManager 会把
            // 布局框定在内容区内，表现为"左右差一截、上下压根贴不到顶/底"。
            // "能被移到屏幕外"这个副作用由拖动时的 clamp 兜住 —— 能贴边，不会丢。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认位：安全区右缘留 4dp、垂直居中；每次开窗都从这里出发（2026-09-22 用户口径：
            // 不再记忆上次拖动到哪，位置是每次的现场决定）
            val safe = safeDragBounds(pillW, pillH)
            x = (safe.right - dp(4f)).coerceAtLeast(safe.left)
            y = safe.top + (safe.height() - pillH) / 2
        }
        pillParams = params

        // 拖动 = 移动悬浮窗；位移没超过 touch slop 的抬手 = 轻点（开始/停止录制）
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        pill.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = pillParams?.x ?: 0
                    startY = pillParams?.y ?: 0
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        pillParams?.let { p ->
                            // ⚠️ 范围必须**每次实时取**：旋转后旧 bounds/insets 会失效，用户就会遇到
                            // 「竖屏只能在一小块区域里拖」这种看着像 ROM 限制、实则是度量陈旧的问题。
                            // 2026-09-22 用户口径收紧：旧实现允许往边缘里**藏半个** tab（模仿系统
                            // 悬浮球），但配合 FLAG_LAYOUT_NO_LIMITS 会把可点区域真拖出屏外、或压进
                            // 状态栏/导航条底下 —— 那些区域在 z 序上盖过 APPLICATION_OVERLAY，
                            // tab 变成看得见摸不着，拖进去就再也救不回来。现在一律夹在
                            // 「完整可见 + 完整可点」的安全区（见 [safeDragBounds]）。
                            val safe = safeDragBounds(pillW, pillH)
                            p.x = clampInt(startX + dx.toInt(), safe.left, safe.right)
                            p.y = clampInt(startY + dy.toInt(), safe.top, safe.bottom)
                            runCatching { wm.updateViewLayout(pill, p) }
                            // 时长窗口是另一个 overlay 窗口，不会自动跟着 tab 走 —— 手动同步，
                            // 否则拖完 tab 面板会孤零零留在旧位置
                            updatePanelPosition()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onPillTapped()
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(pill, params) }
        // ⚠️ 刻意**不**在这里 showPanel()（2026-09-22 用户口径）：时长窗口是「轻点 tab 开录」的
        // 伴随物 —— 面板出现 = 用户已经点了 tab 开始录制。悬浮窗刚打开时只出 tab；
        // 开窗即弹面板会让"还没决定录不录"的用户被迫先看时长选择。停止录制也不弹（见 onPillTapped）。
    }

    /**
     * 轻点 tab：录制中 → 停止并落库，时长窗口**一并收起**；未录制 → **直接开始录制**，
     * 同时把时长窗口带出来。
     *
     * 时长窗口是「可选的限时」，不是开录的前置条件：默认 `limitMinutes == null`（每次 `stop()`
     * 后都会 `clearLimit()`），于是这一场是**无限录制**，必须由用户再点一次 tab 才停；
     * 只有在窗口里挑了 5/10/15/30，才会按本场开始时间推算到点自动停。
     *
     * ⚠️ 停止录制**不弹**时长窗口（2026-09-25 用户反馈）：停止是「收工」动作，跟着弹出
     * 「挑下一场时长」属于打扰 —— 想再录，用户自然会再点 tab（那时才弹窗口）。
     */
    private fun onPillTapped() {
        if (FrameRecordController.recording.value) {
            FrameRecordController.stop()
            // 停止录制后回到预览：悬浮窗还开着，帧率读数不该变成死的
            FrameRecordController.startPreview()
            // 收工即收起：面板可能还在 5s 自动隐藏的窗口期内（开录后没挑时长就立刻点停止）
            hidePanel()
        } else {
            FrameRecordController.start(FrameRecordController.limitMinutes.value)
            showPanel()
        }
    }

    /**
     * 窗口里挑完时长：录制中就改本场限时（到点推算自**本场开始时间**），未录制则直接按该时长开录。
     * 窗口随即收起，别让它在被测画面上多留一帧。
     */
    private fun applyLimit(minutes: Int) {
        FrameRecordController.clearError()
        if (FrameRecordController.recording.value) {
            FrameRecordController.setLimit(minutes)
        } else {
            FrameRecordController.start(minutes)
        }
        refreshChips()
        hidePanel()
    }

    /**
     * tab 外观：底色 + 帧率数字。
     *
     * 未录制 = 浅绿底黑字；录制中 = 热烈红底白字（2026-09-22 用户口径：录制态一眼可辨）。
     * 帧率数字**两种状态下都恒显实时值** —— 「这场录没录」由底色承担，数字不掺状态语义。
     */
    private fun applyPillState(recording: Boolean) {
        pillBgColor = if (recording) COLOR_RECORDING_RED else COLOR_MINT
        pillView?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12.5f).toFloat()
            setColor(pillBgColor)
        }
        updatePillText(FrameRecordController.fps.value)
    }

    /**
     * 只刷数字：**恒为实时帧率**，与录制状态无关（未采到有效差分显示「—」）。
     *
     * fps 为 null → NaN，走 `formatLiveFps` 的「—」分支；禁止用 0.0 兜底（假读数）。
     * 文字色跟随 [pillBgColor] 反算（红底白 / 绿底黑），不再写死浅绿那一档 ——
     * 否则录制态换成红底后，黑字压在红底上对比度不足。
     */
    private fun updatePillText(fps: Double?) {
        pillView?.apply {
            text = formatLiveFps(fps ?: Double.NaN)
            setTextColor(textColorFor(pillBgColor))
        }
    }

    // ── 录制时长窗口：仅两行（标题 + 四个胶囊），5s 无点击自动隐藏 ──

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f).toFloat()
                setColor(COLOR_PANEL_BG)
            }
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.frame_record_duration)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(0xE6FFFFFF.toInt())
            },
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipViews = DURATIONS.map { minutes ->
            TextView(this).apply {
                text = minutes.toString()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(30f), 1f).apply {
                    if (minutes != DURATIONS.last()) marginEnd = dp(6f)
                }
                setOnClickListener { applyLimit(minutes) }
            }
        }
        chipViews.forEach { row.addView(it) }
        root.addView(row)
        refreshChips()
        return root
    }

    private fun refreshChips() {
        val selected = FrameRecordController.limitMinutes.value
        chipViews.forEachIndexed { index, chip ->
            val isSelected = DURATIONS[index] == selected
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(15f).toFloat()
                setColor(if (isSelected) COLOR_MINT else COLOR_CHIP_IDLE)
            }
            // 选中 = 浅绿底 + miuix 蓝字（用户指定搭配）；两项都不走 [textColorFor] 的反算逻辑，
            // 因为这里要的是"品牌色深色"，不是"自动黑白"。实测对比度约 2.2:1，短数字够读；
            // 若后续觉得糊，把这里换成深色主题的 miuix 蓝 0xFF277AF7 即可抬到 2.45:1。
            chip.setTextColor(if (isSelected) COLOR_MIUIX_BLUE else Color.WHITE)
            chip.setTypeface(Typeface.create(Typeface.MONOSPACE, if (isSelected) Typeface.BOLD else Typeface.NORMAL))
        }
    }

    private fun showPanel() {
        val wm = windowManager ?: return
        if (pillParams == null) return
        if (panelView == null) {
            val panel = buildPanel()
            panelView = panel
            panel.measure(
                View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.UNSPECIFIED,
            )
            val params = WindowManager.LayoutParams(
                dp(186f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 同 tab：允许越过系统条，跟 tab 一起贴到真正的屏幕边缘
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            panelParams = params
            // addView 前先算位置；这一步里的 updateViewLayout 尚未 attach、会被 runCatching 吞掉，
            // 但 params 是按引用改的，紧接着的 addView 用的就是算好的坐标
            updatePanelPosition()
            runCatching { wm.addView(panel, params) }
            // 首次显示时 measuredHeight 还是 0（上方用的是 80dp 估算）：布局完成后用真实
            // 高度再算一次，"下方放不下翻到上方"的判定才准（否则面板下缘可能压过导航条）
            panel.post { updatePanelPosition() }
        } else {
            // 已在显示：tab 可能刚被拖过，重算一次位置即可，不必重建
            updatePanelPosition()
        }
        scheduleHide()
    }

    /**
     * 面板位置：**tab 正下方、与 tab 左对齐**，横向/纵向都夹回屏内；下方放不下就翻到上方。
     *
     * 拖动 tab 时必须同步调用 —— 面板是独立的 overlay 窗口，不会跟着 tab 自动走。
     * 旋转时也必须重算（[onConfigurationChanged] 调用）：面板坐标是绝对像素，竖屏算好的
     * y 一转横屏就可能整体落到新屏幕高度之外（2026-09-25 用户实测：横屏面板跑出屏幕）。
     *
     * ⚠️ 必须走 clampInt 而不是 coerceIn：窗口高度/宽度在极端屏（矮屏放不下 80dp 面板）
     * 下会让上界小于下界，coerceIn 直接抛 IllegalArgumentException（2026-09-22 实测崩过）
     */
    private fun updatePanelPosition() {
        val wm = windowManager ?: return
        val p = pillParams ?: return
        val view = panelView ?: return
        val params = panelParams ?: return
        val panelW = params.width
        val panelH = view.measuredHeight.coerceAtLeast(dp(80f))
        // 面板与 tab 同一安全区约束（左上角坐标范围），不得压进系统栏/刘海
        val safe = safeDragBounds(panelW, panelH)
        params.x = clampInt(p.x, safe.left, safe.right)
        val below = p.y + dp(25f) + dp(8f)
        // ⚠️ 两档位置都过 clampInt：正常时序下 below 必在安全区内，但旋转（onConfigurationChanged
        //    的重算）等时序里 p.y 是夹取后的新值、面板真实高度又与估算有差，不夹就有越界口子
        params.y = if (below > safe.bottom) {
            clampInt(p.y - panelH - dp(8f), safe.top, safe.bottom)
        } else {
            clampInt(below, safe.top, safe.bottom)
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hidePanelRunnable)
        handler.postDelayed(hidePanelRunnable, 5_000L)
    }

    private fun hidePanel() {
        val wm = windowManager ?: return
        panelView?.let { panel ->
            runCatching { wm.removeView(panel) }
            panelView = null
            panelParams = null
            chipViews = emptyList()
        }
    }

    // ── 状态订阅：帧率数字 + 录制态配色 ────────────────────

    private fun observeController() {
        scope.launch {
            FrameRecordController.recording.collect { recording ->
                applyPillState(recording)
            }
        }
        scope.launch {
            FrameRecordController.fps.collect { fps ->
                updatePillText(fps)
            }
        }
    }

    // ── 通知 / 渠道 / 工具 ────────────────────────────────

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(getString(R.string.notify_overlay_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_frame_record),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_desc_frame_record)
                setShowBadge(false)
            },
        )
    }

    /** 亮度反算文字色（口径同 ui/ColorPickerDialog.onColorFor：相对亮度 > 0.55 配黑字） */
    private fun textColorFor(bg: Int): Int {
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        fun linear(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
        return if (luminance > 0.55) 0xFF1B1B1B.toInt() else Color.WHITE
    }

    /**
     * tab / 面板允许的**左上角坐标**范围：整屏 bounds 内、避开系统栏（2026-09-22 起）。
     *
     * 为什么 insets 不能省：`TYPE_APPLICATION_OVERLAY` 在 z 序上**低于**状态栏/导航条，
     * 配合 `FLAG_LAYOUT_NO_LIMITS` 把窗口拖进那两条区域会被系统栏盖住 —— 看得见摸不着。
     * 所以可放范围 = [maximumWindowMetrics].bounds 再向内收系统栏。
     *
     * ⚠️ **横向只避 systemBars，不避 displayCutout**（2026-09-25 用户口径：横屏 tab 要
     * 贴住左右物理边缘，"按竖屏时的高度放左右两边"）：挖孔条带里没有系统 UI、不拦触摸，
     * 覆盖窗口在挖孔区域内照样可点 —— 旧实现把挖孔 inset 也算进横向避让，横屏旋转后
     * 挖孔转到左右两侧，安全区被推离物理边缘，默认位与拖动都贴不了边。系统栏（状态栏/
     * 导航条）竖屏横屏都在上下短边，横向 systemBars inset 恒为 0，等于全程放开左右贴边。
     * 纵向保留挖孔避让：竖屏顶部的挖孔条带要避开（别挡前摄）。
     *
     * ⚠️ 必须每次实时取（同拖动时的口径）：`maximumWindowMetrics` 随旋转刷新，
     * 用启动时的快照会出现「只能在旧方向的范围里拖」的陈旧度量问题。
     *
     * @return 左上角允许范围；屏幕极端小到放不下 view 时 right/bottom 收敛到 left/top，
     *   由 [clampInt] 的 min>max 分支钉死在左上角。
     */
    private fun safeDragBounds(viewW: Int, viewH: Int): Rect {
        val wm = windowManager ?: return Rect()
        val metrics = wm.maximumWindowMetrics
        val bounds = metrics.bounds
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val cutout = metrics.windowInsets.getInsets(WindowInsets.Type.displayCutout())
        val left = bounds.left + bars.left
        val right = (bounds.right - bars.right - viewW).coerceAtLeast(left)
        val top = bounds.top + maxOf(bars.top, cutout.top)
        val bottom = (bounds.bottom - maxOf(bars.bottom, cutout.bottom) - viewH).coerceAtLeast(top)
        return Rect(left, top, right, bottom)
    }

    private fun clampInt(v: Int, min: Int, max: Int): Int = if (min > max) min else v.coerceIn(min, max)

    /**
     * 旋转后重新贴边（2026-09-25 用户口径：横屏 tab 也要靠边，"按竖屏时的高度放左右两边"）：
     * 横向贴回更近的那一侧物理边缘（左右两侧都可停靠），纵向保持原高度、夹进新方向的安全区
     * —— 竖/横屏的安全区都是近似居中的带子，夹取后就是"与竖屏相同的比例高度"。
     * 同时必须重算面板位置：面板坐标是绝对像素，竖屏算好的 y 一转横屏就可能整体落到
     * 新屏幕高度之外（用户实测：横屏面板跑出屏幕）。
     * ⚠️ 回调时 maximumWindowMetrics 个别 ROM 上尚未刷到新方向，post 一拍再取新度量。
     * 面板若在显示，[updatePanelPosition] 内部走同一套 [safeDragBounds] 自行夹取。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val p = pillParams ?: return@post
            val wm = windowManager ?: return@post
            val safe = safeDragBounds(pillW, pillH)
            val hugLeft = p.x + pillW / 2f < (safe.left + safe.right) / 2f
            p.x = if (hugLeft) safe.left + dp(4f) else safe.right - dp(4f)
            p.y = clampInt(p.y, safe.top, safe.bottom)
            runCatching { wm.updateViewLayout(pillView, p) }
            updatePanelPosition()
        }
    }
}
