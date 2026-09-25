package com.chen.powermeter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chen.powermeter.MainActivity
import com.chen.powermeter.R
import com.chen.powermeter.data.BatteryInfoStore
import com.chen.powermeter.data.PowerSample
import com.chen.powermeter.data.RootPowerReader
import com.chen.powermeter.data.SampleStore
import com.chen.powermeter.data.SessionStats
import com.chen.powermeter.data.db.SessionRecorder
import com.chen.powermeter.util.CsvExporter
import com.chen.powermeter.util.Prefs
import com.chen.powermeter.util.ScreenController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

/**
 * 前台采样服务：
 * - 锁屏后持续读取电量数据（Shizuku / root / 主进程 BatteryManager 三通道，见 [RootPowerReader]）
 * - 常驻通知显示功率 / 电压 / 电流 / 温度，按亮息屏分档节流（见 [maybeNotify]）
 * - 息屏自适应降频：息屏切 5s、亮屏回用户设定值（见 [effectiveIntervalMs]）
 * - 可选 PARTIAL_WAKE_LOCK 保证息屏后采样连续；带 30min 超时 + 续期防泄漏，
 *   且「充电功率监测」开启时强制不持锁（见 [shouldHoldWakeLock]）
 */
class SamplingService : Service() {

    companion object {
        const val ACTION_STOP = "com.chen.powermeter.action.STOP"

        private const val NOTIF_ID = 2001

        /** 自动保存完成的一次性提示通知 */
        private const val NOTIF_ID_AUTO_SAVE = 2002
        private const val CHANNEL_ID = "powermeter_sampling"

        /**
         * 常驻通知的刷新节流下限（档一-2，2026-09-21）。
         *
         * 常驻通知的价值在**亮屏瞥一眼**，息屏时每秒刷一次纯亏（Builder 构建 + notify 跨进程调用）。
         * 故按亮/息屏分档，再叠加「读数是否显著变化」与 60s 保底 —— 详见 [maybeNotify]。
         */
        private const val NOTIFY_MIN_INTERVAL_AWAKE_MS = 5_000L
        private const val NOTIFY_MIN_INTERVAL_SCREEN_OFF_MS = 10_000L

        /** 保底刷新间隔：功率长时间平稳时，「充电中 · 80%」这类信息也要能动一下 */
        private const val NOTIFY_MAX_INTERVAL_MS = 60_000L

        /** 「显著变化」的绝对阈值 W */
        private const val NOTIFY_DELTA_W = 0.5

        /** 「显著变化」的相对阈值（相对上一次已通知的功率） */
        private const val NOTIFY_DELTA_RATIO = 0.10

        /**
         * 息屏后的采样间隔（档一-4）。
         *
         * 屏幕灭了以后 1s 密度的边际价值很低，而每个采样点都是一次取数开销。
         * 与用户设定值取 max：用户自己设了更慢的间隔就尊重用户，不会被这里"提速"。
         */
        private const val SCREEN_OFF_INTERVAL_MS = 5_000L

        /** PARTIAL_WAKE_LOCK 的持有名（便于 dumpsys power 里定位） */
        private const val WAKE_LOCK_TAG = "PowerMeter:Sampling"

        /**
         * wakelock 单次持有时长。**必须带超时** —— 裸 acquire() 一旦漏掉 release 就是永久泄漏。
         * 到期前由续期协程续期，服务停止时释放方法兜底。
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** 续期周期：略早于超时，避免在边界上被系统回收 */
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 25 * 60 * 1000L

        /** 充电功率监测：开始采样后多久自动熄屏 */
        private const val SCREEN_OFF_DELAY_MS = 5_000L

        /**
         * 充电功率监测：判定「无输入电流」的电流阈值 mA（取绝对值比较）。
         *
         * 内核 current_now 在真正停止充电时不一定精确到 0，常见 ±1mA 级抖动
         * （部分机型在做库仑计校准时会短暂上报 ±1mA），故留 1mA 容差而不是写死 == 0.0。
         */
        private const val ZERO_CURRENT_THRESHOLD_MA = 1.0

        /**
         * 充电功率监测：「正在充电」的武装阈值 mA（取绝对值比较）。
         *
         * 本场会话至少出现过一次 ≥ 该值的电流，才认为确实插着充电器在充。
         * 取 50mA 而非更大值：涓流末段的电流本身就只有几十 mA，阈值太高会把
         * 「本就在测涓流」的场景挡在门外、永远不武装。
         */
        private const val ARM_CURRENT_MA = 50.0

        /** 充电功率监测：输入电流归零需连续保持多久才触发自动保存 */
        private const val ZERO_CURRENT_HOLD_MS = 30_000L

        /** 自动保存的文件名前缀，与手动导出的 powermeter_ 区分开 */
        private const val AUTO_SAVE_PREFIX = "powermeter_charge"

        /**
         * 串联双电池的电压换算系数。
         *
         * 口径由用户拍板（2026-09-21，最终版）：**×2 落在电压列**。
         * 内核 battery/voltage_now 上报的是**单节**电芯电压，串联机型整组为两节叠加。
         * 电流、容量保持不变（串联电流处处相等，mAh 口径不受影响）。
         * 小米机型内核口径不同，无需开启本开关。
         */
        private const val SERIES_DUAL_FACTOR = 2.0

        /**
         * 采样序列改为**环形缓冲 + 按需快照**（档二-1，见 [SampleStore]）。
         *
         * 旧实现每次采样都做 `(_samples.value + sample).takeLast(MAX_SAMPLES)` —— 每秒新建一个
         * 等长元素的列表，息屏时照做。现在服务侧只 append（O(1)、零分配），
         * 快照由真正需要的调用方（UI 重组 / 导出落盘）按需索取。
         */
        val sampleVersion: StateFlow<Long> get() = SampleStore.version

        /** 会话统计量：每样本 O(1) 增量更新，取代原先挂在重组上的 O(n) 全量重算 */
        val stats: StateFlow<SessionStats> get() = SampleStore.stats

        fun snapshot(): List<PowerSample> = SampleStore.snapshot()

        val sampleCount: Int get() = SampleStore.sampleCount

        /**
         * 当前运行中的服务实例（弱引用语义：只在运行时非空）。
         * 供 UI 在开关变更后立即同步持锁策略，不必等下一次采样循环。
         */
        @Volatile
        private var instance: SamplingService? = null

        /** 设置项变更后同步持锁策略（服务未运行时为空操作） */
        fun onPrefsChanged() {
            instance?.syncWakeLock()
        }

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _intervalMs = MutableStateFlow(1_000L)
        val intervalMs: StateFlow<Long> = _intervalMs.asStateFlow()

        fun setInterval(ms: Long) {
            _intervalMs.value = ms
        }

        /**
         * 清空采样数据。
         *
         * ⚠️ 必须同时处理落库会话：采样仍在运行时不能只把会话行删掉就完事 ——
         * 后续样本会继续带着那个已删除的 sessionId 写入，撞外键约束。
         * [SessionRecorder.discardAsync] 的 `restart` 参数正是为此：删旧会话 + 立刻开新会话。
         */
        fun clearSamples() {
            SampleStore.clear()
            SessionRecorder.discardAsync(restart = _running.value)
        }
    }

    /**
     * 服务级 Job：`onDestroy` 时统一取消。
     *
     * ⚠️ 必须显式取消，不能只 cancel [samplingJob]（2026-09-21 修）：
     * [scheduleScreenOff] 的协程带 `delay(SCREEN_OFF_DELAY_MS)`，若用户开始采样后 5s 内点
     * 「停止采样」，服务销毁时该协程仍在 delay 中，随后照样醒来执行熄屏 —— 它只检查
     * 充电监测开关，不看服务是否还在跑，于是出现「已停止采样，屏幕却被熄掉」。
     * 同时该协程 lambda 捕获 Service 实例，不取消会延迟其回收。
     */
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private var samplingJob: Job? = null
    private var wakeLockJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ---- 常驻通知节流状态 ----
    private var lastNotifyAt = 0L
    private var lastNotifiedPowerW = Double.NaN
    private var lastNotifiedSoc = -1
    private var lastNotifiedCharging: Boolean? = null

    /**
     * 屏幕是否点亮（由 ACTION_SCREEN_ON / OFF 广播维护，服务启动时按 [PowerManager.isInteractive] 取初值）。
     * 同时驱动两件事：采样间隔（[effectiveIntervalMs]）与通知节流档位（[maybeNotify]）。
     */
    @Volatile
    private var screenOn = true

    /** 息屏 / 亮屏广播接收器（运行时代码注册，见 [onCreate]） */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    // 息屏后是否继续持锁由 syncWakeLock 内的策略决定（充电监测期间强制不持锁）
                    syncWakeLock()
                    // 通知立即改用息屏档位，不再等下一次采样
                    lastNotifyAt = 0L
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    syncWakeLock()
                    lastNotifyAt = 0L
                }
            }
        }
    }

    // ---- 充电功率监测状态（每场采样会话复位一次）----

    /** 本场会话是否出现过正常输入电流（≥ [ARM_CURRENT_MA]），用作归零判定的「武装」前提 */
    private var currentSeen = false

    /** 当前这段连续「电流为零」区间的起点；电流回升到阈值以上时置 null */
    private var zeroCurrentSince: Long? = null

    /** 本场会话是否已自动保存过 —— 保证一个会话最多自动导出一次 */
    private var autoSaved = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        _intervalMs.value = Prefs.getIntervalMs(this)
        // 初值不能假定"亮着"：服务可能在息屏状态下被拉起（进程重建）
        screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        registerScreenReceiver()
    }

    /**
     * 注册息屏 / 亮屏广播。
     *
     * ⚠️ 必须**运行时代码注册**：`ACTION_SCREEN_ON/OFF` 自 Android 8 起禁止在 Manifest 里
     * 静态注册（隐式广播限制），代码注册不受此限。
     * ⚠️ `RECEIVER_NOT_EXPORTED` 只挡其它应用发来的广播，系统广播照收 —— 且 targetSdk 34+
     * 注册非系统专属广播时必须显式指定导出标志，否则直接抛异常。
     */
    private fun registerScreenReceiver() {
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_running.value) return START_STICKY

        _running.value = true
        _error.value = null
        resetChargeWatch()
        // 通知节流状态复位：新会话的首个样本必须立刻刷新一次（否则会沿用上一场的比较基准）
        lastNotifyAt = 0L
        lastNotifiedPowerW = Double.NaN
        lastNotifiedSoc = -1
        lastNotifiedCharging = null
        startForegroundCompat(buildNotification(null))
        startWakeLockJob()

        // 电池静态信息已迁到进程级仓库（冷启动时 MainActivity 已预读过）；此处强制刷新一次，
        // 拿到最新的循环次数 / 健康度。仓库自带 IO 作用域，无需再包 launch，
        // 也不会像旧实现那样在偶发读取失败时把已有值覆盖成 null。
        BatteryInfoStore.refresh()
        samplingJob = scope.launch { samplingLoop() }
        scheduleScreenOff()
        return START_STICKY
    }

    private suspend fun samplingLoop() {
        // 开场先建会话：样本必须从第一个点起就有归属，否则开头这段只能活在内存缓冲里。
        // 建会话失败（DB 打不开等）不阻断采样 —— 曲线、统计、通知都在内存里照常工作，
        // 只是失去「进程被杀后数据仍在」的保障；导出按钮此时会回落到内存快照路径。
        runCatching { SessionRecorder.start() }

        while (true) {
            val raw = withContext(Dispatchers.IO) { RootPowerReader.read() }
            // 串联双电池换算：在**采样入口**统一处理，下游（曲线、统计、常驻通知、手动导出、
            // 自动保存）全部自动同口径，避免各处各算一遍。
            // 注：充电功率监测的「电流归零」判定不受这里的换算影响 ——
            // 串联回路电流处处相等（只有电压与功率翻倍），currentMa 保持原值。
            //
            // ⚠️ powerW 必须跟着一起翻倍：它是 RootPowerReader 里用 V × I 算好后**存进
            // PowerSample 的成品值**，下游不会拿翻倍后的 voltageV 重算。不显式跟上就会同时
            // 出现「电压 7.4V、电流 1200mA、功率 4.4W」这种自相矛盾的读数。
            //
            // voltageOcvV 也要同步：它与 voltageV 同源（同一组 b_voltage_* 节点），只翻一个会让
            // 「开路电压 − 工作电压 = 内阻压降」这个隐含关系失真。
            // usbVoltageV 是 Type-C 输入侧节点，与电池组无关，**不翻**。
            // 电流 / 容量保持不变（串联电流处处相等）。
            val sample = if (raw != null && Prefs.getSeriesDualBattery(this)) {
                raw.copy(
                    voltageV = raw.voltageV * SERIES_DUAL_FACTOR,
                    voltageOcvV = raw.voltageOcvV * SERIES_DUAL_FACTOR,
                    powerW = raw.powerW * SERIES_DUAL_FACTOR,
                )
            } else {
                raw
            }
            if (sample != null) {
                SampleStore.append(sample)
                // 增量落库：只入队（零阻塞），真正写盘由 SessionRecorder 每 10s 批量做。
                // 采样循环在 Dispatchers.Default 上，这里绝不能出现同步 IO —— 0.5s 档会被拖成抖动
                SessionRecorder.onSample(sample)
                _error.value = null
                maybeNotify(sample)
                watchChargeCurrent(sample)
            } else {
                _error.value = RootPowerReader.lastError ?: getString(R.string.error_read_failed)
            }
            delay(effectiveIntervalMs())
        }
    }

    /**
     * 当前生效的采样间隔：息屏时放宽到 [SCREEN_OFF_INTERVAL_MS]，亮屏时用用户设定值。
     *
     * 取 `max` 而非直接替换：用户自己设了比 5s 更慢的间隔时尊重用户，不被这里"提速"。
     * 200ms 下限沿用旧逻辑，防止极端配置把采样循环打死。
     */
    private fun effectiveIntervalMs(): Long {
        val user = _intervalMs.value.coerceAtLeast(200L)
        return if (screenOn) user else maxOf(user, SCREEN_OFF_INTERVAL_MS)
    }

    // ---------- 充电功率监测 ----------

    private fun resetChargeWatch() {
        currentSeen = false
        zeroCurrentSince = null
        autoSaved = false
    }

    /**
     * 开始采样 5s 后自动熄屏。
     *
     * 熄屏的目的是**去掉屏幕自身那部分耗电**，让电池端功率读数更接近真实充电功率
     * （屏幕亮着时系统功耗可达数瓦，会掩盖涓流阶段的真实功率）。
     *
     * 与采样循环解耦、互不阻塞：这是独立协程，即便 su 卡住也只影响熄屏本身。
     * delay 结束后**重新读一次开关** —— 5s 窗口内用户完全可能又把开关关掉。
     */
    private fun scheduleScreenOff() {
        if (!Prefs.getChargeMonitor(this)) return
        scope.launch {
            delay(SCREEN_OFF_DELAY_MS)
            if (!Prefs.getChargeMonitor(this@SamplingService)) return@launch
            withContext(Dispatchers.IO) { ScreenController.turnScreenOff() }
        }
    }

    /**
     * 充电功率监测判定：输入电流连续为 0（≤ [ZERO_CURRENT_THRESHOLD_MA]）满 [ZERO_CURRENT_HOLD_MS]
     * 即自动导出一次 CSV，**采样本身不打断**。
     *
     * 判据落在 `current_ma` 而非功率上：充满 / 拔枪时电压还在（功率读数会在零点附近飘），
     * 而电流是最干脆的那一个量 —— 涓流截止、已充满、充电器断开，输入电流都会塌到 0。
     *
     * 三道护栏缺一不可：
     * 1. **武装前提** [currentSeen] —— 本场会话至少出现过一次 ≥ [ARM_CURRENT_MA] 的输入电流，
     *    才算"确实在充电"。这条同时兜住了「电流通道不可用」的机器：binder 通道在部分机型上
     *    `getLongProperty(CURRENT_NOW)` 恒返回 0，没设这道判断会在"根本没充上电"的场景下
     *    静默触发一次自动导出；
     * 2. **连续区间** [zeroCurrentSince] —— 电流一旦回到阈值以上就复位，只认连续归零，
     *    避免把若干段零散的零电流时间累加成 30 秒；
     * 3. **幂等** [autoSaved] —— 命中一次后本场会话不再触发。停充以后电流长期为 0，
     *    不拦就会每 30 秒刷出一个新 CSV。
     */
    private fun watchChargeCurrent(sample: PowerSample) {
        if (autoSaved || !Prefs.getChargeMonitor(this)) return

        // 取绝对值：本机充电时 current_now 为负（见 PowerSample 的符号约定），
        // 「有没有输入电流」只看大小、不看方向。
        val absCurrentMa = abs(sample.currentMa)

        if (absCurrentMa > ZERO_CURRENT_THRESHOLD_MA) {
            if (absCurrentMa >= ARM_CURRENT_MA) currentSeen = true
            zeroCurrentSince = null
            return
        }
        if (!currentSeen) return

        val since = zeroCurrentSince
        if (since == null) {
            zeroCurrentSince = sample.timeMillis
            return
        }
        if (sample.timeMillis - since < ZERO_CURRENT_HOLD_MS) return

        autoSaved = true
        zeroCurrentSince = null
        // NonCancellable：服务可能在写盘途中被销毁（scopeJob.cancel），写一半会留下
        // MediaStore 的 IS_PENDING=1 孤儿记录 —— 让本次导出写完再响应取消
        scope.launch(Dispatchers.IO + NonCancellable) { autoSaveCsv() }
    }

    /**
     * 导出**本场采样到目前为止的全量数据**（从开始采样到此刻的完整充电曲线），
     * 数据源与手动「导出 CSV」同一处 —— 都是库里的会话，因此不受内存窗口限制。
     * 文件名加 `powermeter_charge_` 前缀以便与手动导出区分。
     *
     * 全程不触碰 [_running] / [samplingJob] —— 只写文件，采样继续。
     */
    private suspend fun autoSaveCsv() {
        // 优先从落库的会话里导出：不受内存显示窗口（SampleStore.CAPACITY）限制，长测也能导出全量。
        // deleteAfter = false —— 采样还在继续，会话必须留着继续累积；
        // 它的"善后"（停止时删除）由 onDestroy 依据 autoSaved 标记完成。
        val saved = SessionRecorder.exportCurrent(
            context = this,
            prefix = AUTO_SAVE_PREFIX,
            rotate = false,
            deleteAfter = false,
        )
        if (saved.uri != null) {
            notifyAutoSaved(saved.uri, saved.sampleCount)
            return
        }
        // 兜底：会话层无数据（落库失败等）时回落到内存快照，行为与引入 Room 之前一致
        val snapshot = SampleStore.snapshot()
        if (snapshot.isEmpty()) return
        val uri = CsvExporter.export(this, snapshot, prefix = AUTO_SAVE_PREFIX)
        notifyAutoSaved(uri, snapshot.size)
    }

    /** 自动保存完成后发一条一次性通知：熄屏状态下用户看不到 Toast，通知是唯一反馈途径 */
    private fun notifyAutoSaved(uri: Uri?, count: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val text = if (uri != null) {
            val name = CsvExporter.displayNameOf(this, uri) ?: "CSV"
            getString(R.string.notify_auto_saved, name, count)
        } else {
            getString(R.string.notify_auto_save_failed)
        }
        nm.notify(
            NOTIF_ID_AUTO_SAVE,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle(getString(R.string.option_charge_monitor))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
        }
    }

    /**
     * 常驻通知刷新（档一-2）。
     *
     * 两道闸 + 一道保底：
     * 1. **时间闸** —— 亮屏 `NOTIFY_MIN_INTERVAL_AWAKE_MS`（5s）、息屏
     *    `NOTIFY_MIN_INTERVAL_SCREEN_OFF_MS`（10s）之内一律不刷；
     * 2. **变化闸** —— 时间窗到了还要看值是否值得刷：SOC 与充电状态都没变、功率变化
     *    既不到 0.5W 也不到 10% 就跳过，免得通知栏每秒抖一次同样的小数；
     * 3. **保底** —— 距上次刷新超过 [NOTIFY_MAX_INTERVAL_MS] 时无条件刷一次，
     *    否则读数长期平稳（恒温涓流）会让通知看起来像卡死了。
     */
    private fun maybeNotify(sample: PowerSample) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyAt
        val minInterval = if (screenOn) {
            NOTIFY_MIN_INTERVAL_AWAKE_MS
        } else {
            NOTIFY_MIN_INTERVAL_SCREEN_OFF_MS
        }
        if (elapsed < minInterval) return
        if (elapsed < NOTIFY_MAX_INTERVAL_MS && !isNoteworthy(sample)) return

        lastNotifyAt = now
        lastNotifiedPowerW = sample.powerW
        lastNotifiedSoc = sample.socPct
        lastNotifiedCharging = sample.isCharging
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(sample))
    }

    /** 本样本相比「上一次已通知的内容」是否值得打扰用户 */
    private fun isNoteworthy(sample: PowerSample): Boolean {
        if (sample.socPct != lastNotifiedSoc) return true
        if (sample.isCharging != lastNotifiedCharging) return true
        val prev = lastNotifiedPowerW
        if (prev.isNaN()) return true
        val delta = abs(sample.powerW - prev)
        if (delta >= NOTIFY_DELTA_W) return true
        return delta / abs(prev).coerceAtLeast(1e-6) >= NOTIFY_DELTA_RATIO
    }

    /** 点通知回到主界面。常驻通知与自动保存提示共用，避免两处 requestCode / flags 分叉 */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * 构建常驻通知（**实况更新 / Live Update**，机制对齐 SportLink `SportNotificationHelper`）：
     * - **折叠态**：左侧小图标 + 右侧主值（功率）；
     * - **展开态**（[Notification.BigTextStyle] 两行）：第一行 电压 / 电流，第二行 电池温度；
     * - **提升为实况更新**：`android.requestPromotedOngoing`（SDK≥36 且系统允许）+ `miui.focus.param`
     *   （MIUI 焦点通知 / 灵动岛），见 [applyLiveUpdateExtras]。
     *
     * 文案全部走 string resources（[R.string.notify_live_row1] / [R.string.notify_live_row2]）以支持多语言；
     * 数值（含单位）在本函数里拼好作为参数传入，数值模板本身不翻译。
     */
    private fun buildNotification(sample: PowerSample?): Notification {
        // 右侧主值 = 功率（折叠态）；无样本时退回启动文案
        val powerText = if (sample == null) {
            getString(R.string.notify_starting)
        } else {
            "${sample.powerW.f3()} W"
        }
        // 展开态两行（第一行 电压/电流，第二行 电池温度）；
        // 刚启动（无样本）时留空，BigTextStyle 不挂。
        // 功率只在折叠态右侧主值出现，不再进展开行。
        val liveBody = sample?.let { s ->
            getString(R.string.notify_live_row1, "${s.voltageV.f3()} V", "${s.currentMa.f0()} mA") +
                "\n" +
                getString(R.string.notify_live_row2, "${s.tempBatteryC.f1()} ℃")
        }
        val statusText = when {
            sample == null -> getString(R.string.notify_starting)
            sample.isCharging -> getString(R.string.notify_title_charging, sample.socPct)
            else -> getString(R.string.notify_title_discharging, sample.socPct)
        }
        val pi = contentIntent()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(powerText)
            .setContentText(statusText)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi)

        if (liveBody != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(liveBody))
        }
        applyLiveUpdateExtras(builder, title = powerText, body = liveBody ?: statusText)
        return builder.build()
    }

    /**
     * 把常驻通知提升为「实况更新」（口径对齐 SportLink `SyncNotificationHelper.applyLiveUpdateExtras`）：
     * 1. Android 16+ 的 `android.requestPromotedOngoing`（**仅当系统允许提升时**才加，否则会被忽略）；
     * 2. MIUI / HyperOS 的 `miui.focus.param`（焦点通知 / 灵动岛实时更新）。
     *
     * 两者都通过 extras 下发；任一失败都不影响基础常驻通知，故内部全部 runCatching 吞异常。
     */
    private fun applyLiveUpdateExtras(builder: Notification.Builder, title: String, body: String) {
        val extras = Bundle()
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm?.canPostPromotedNotifications() == true) {
                    extras.putBoolean("android.requestPromotedOngoing", true)
                }
            }
        }
        buildMiuiFocusParam(title, body)?.let { extras.putString("miui.focus.param", it) }
        if (!extras.isEmpty) builder.setExtras(extras)
    }

    /** MIUI 焦点通知参数（与 SportLink 同结构）：baseInfo 承载标题 / 正文，供灵动岛与小窗实时更新 */
    private fun buildMiuiFocusParam(title: String, body: String): String? = runCatching {
        JSONObject().apply {
            put(
                "param_v2",
                JSONObject().apply {
                    put("protocol", 1)
                    put("updatable", true)
                    put("enableFloat", true)
                    put("ticker", title)
                    put("baseInfo", JSONObject().apply {
                        put("title", title)
                        put("content", body)
                        put("type", 2)
                    })
                },
            )
        }.toString()
    }.getOrNull()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name_sampling),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_desc_sampling)
                    setShowBadge(false)
                    // 与 SportLink 通知渠道同口径：锁屏可见，实况更新（Live Update）卡片才能上锁屏
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    // ---------- wakelock（档一-1）----------

    /**
     * 是否应当持有 wakelock。三条判据，任一不满足即释放：
     * 1. **充电功率监测开启时绝不持锁** —— 该场景要测的是"电池真实在被充多少瓦"，
     *    而 CPU 不睡本身就是一笔负载，会直接抬高电池端读数、污染涓流段。
     *    这是**测量精度**问题，不只是耗电问题；顺便此场景本来就会自动熄屏，
     *    息屏后采样间隔已放宽到 5s，靠系统 suspend 省电正是想要的。
     * 2. 用户关掉「锁屏保持采样」→ 不持锁（息屏后允许系统休眠，采样出现间隙无害）。
     * 3. 其余情况（用户开关开启且非充电监测）→ 持锁，保证息屏后仍按设定间隔出点。
     *
     * ⚠️ 注意第 2 条与「息屏自适应降频」的关系：本开关是**唯一**决定息屏后是否持续
     * 唤醒 CPU 的地方。若把它理解成"息屏一律释放"，这个开关就彻底失去意义了
     * —— 开与关的行为将完全相同。
     */
    private fun shouldHoldWakeLock(): Boolean =
        !Prefs.getChargeMonitor(this) && Prefs.getWakeLock(this)

    /**
     * 启动续期协程：既负责首次获取，也负责在 `WAKE_LOCK_TIMEOUT_MS`（30min）超时前续上。
     *
     * 用协程而不是在采样循环里顺手续期，是因为采样循环在息屏 + 不持锁时会被系统拖慢，
     * 反过来影响续期时机；独立协程在持锁状态下能稳定醒来。
     */
    private fun startWakeLockJob() {
        if (wakeLockJob?.isActive == true) return
        wakeLockJob = scope.launch {
            while (true) {
                syncWakeLock()
                delay(WAKE_LOCK_RENEW_INTERVAL_MS)
            }
        }
    }

    /**
     * 按当前策略获取 / 续期 / 释放 wakelock。
     *
     * 非引用计数的锁（`setReferenceCounted(false)`）在已持有时再次 `acquire(timeout)`
     * 只是把到期时间往后推 —— 这正是续期要的语义，因此这里可以无脑无条件 acquire。
     */
    private fun syncWakeLock() {
        if (!shouldHoldWakeLock()) {
            releaseWakeLock()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = wakeLock ?: pm
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        // 会话收尾（收尾刷盘 + 定格结束时间 + 按需删除）。
        // ⚠️ 必须在 scopeJob.cancel() **之前**发起，且它跑在 SessionRecorder 自带的 IO scope 上
        //    —— 挂在本服务的 scope 上会被下面这行取消连坐，收尾批次直接丢失。
        // 删除策略：本场已被「充电功率监测」自动保存过 ⇒ 用户手里已有 CSV，会话不留；
        //          否则保留在库里等用户点「导出 CSV」，没点就由下次冷启动的孤儿清理删掉。
        SessionRecorder.stopAsync(delete = autoSaved)
        // 取消服务级 Job：samplingJob、续期协程与 scheduleScreenOff 的 delay 协程一并结束，
        // 避免服务销毁后仍有协程在跑（见 scopeJob 的注释）
        scopeJob.cancel()
        samplingJob = null
        wakeLockJob = null
        releaseWakeLock()
        runCatching { unregisterReceiver(screenReceiver) }
        instance = null
        _running.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /**
     * 常驻通知右侧主值（功率）沿用项目精度约定 3 位小数，且**只显示「数值 + 单位」**
     * （如 `35.250 W`），**不加**「功率」标签 —— 实况通知折叠态空间有限，一个带单位的数值
     * 比「功率 35.250 W」更紧凑。展开态第二行才带标签（见 [R.string.notify_live_row2]）。
     */
    private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

    /** 常驻通知里的电池温度按用户约定取 1 位小数（2026-09-21） */
    private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

    /** 电流 mA 整数档（2026-09-21 用户约定）：内核只上报 mA 整数，显示与 CSV 记录同口径 */
    private fun Double.f0(): String = String.format(Locale.US, "%.0f", this)
}
