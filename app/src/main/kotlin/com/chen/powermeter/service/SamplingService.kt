package com.chen.powermeter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chen.powermeter.MainActivity
import com.chen.powermeter.R
import com.chen.powermeter.data.BatteryInfoStore
import com.chen.powermeter.data.PowerSample
import com.chen.powermeter.data.RootPowerReader
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

/**
 * 前台采样服务：
 * - 锁屏后持续读取底层电量节点（Shizuku 优先、root 兜底，见 [RootPowerReader]）
 * - 常驻通知实时显示功率/电压/电流/温度
 * - 可选 PARTIAL_WAKE_LOCK 保证息屏后采样连续
 */
class SamplingService : Service() {

    companion object {
        const val ACTION_STOP = "com.chen.powermeter.action.STOP"

        private const val NOTIF_ID = 2001

        /** 自动保存完成的一次性提示通知 */
        private const val NOTIF_ID_AUTO_SAVE = 2002
        private const val CHANNEL_ID = "powermeter_sampling"
        private const val MAX_SAMPLES = 3600
        private const val NOTIFY_MIN_INTERVAL_MS = 800L

        /** 充电功率监测：开始采样后多久自动熄屏 */
        private const val SCREEN_OFF_DELAY_MS = 5_000L

        /** 充电功率监测：低功率判定阈值 W（PowerSample.powerW 正 = 充电） */
        private const val LOW_POWER_THRESHOLD_W = 1.0

        /** 充电功率监测：低功率需连续保持多久才触发自动保存 */
        private const val LOW_POWER_HOLD_MS = 5 * 60 * 1000L

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

        private val _samples = MutableStateFlow<List<PowerSample>>(emptyList())
        val samples: StateFlow<List<PowerSample>> = _samples.asStateFlow()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _intervalMs = MutableStateFlow(1_000L)
        val intervalMs: StateFlow<Long> = _intervalMs.asStateFlow()

        fun setInterval(ms: Long) {
            _intervalMs.value = ms
        }

        fun clearSamples() {
            _samples.value = emptyList()
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L

    // ---- 充电功率监测状态（每场采样会话复位一次）----

    /** 本场会话是否出现过正常充电功率（> 阈值），用作低功率判定的「武装」前提 */
    private var chargeSeen = false

    /** 当前这段连续低功率区间的起点；功率回升到阈值以上时置 null */
    private var lowPowerSince: Long? = null

    /** 本场会话是否已自动保存过 —— 保证一个会话最多自动导出一次 */
    private var autoSaved = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        _intervalMs.value = Prefs.getIntervalMs(this)
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
        startForegroundCompat(buildNotification(null))
        acquireWakeLock()

        // 电池静态信息已迁到进程级仓库（冷启动时 MainActivity 已预读过）；此处强制刷新一次，
        // 拿到最新的循环次数 / 健康度。仓库自带 IO 作用域，无需再包 launch，
        // 也不会像旧实现那样在偶发读取失败时把已有值覆盖成 null。
        BatteryInfoStore.refresh()
        samplingJob = scope.launch { samplingLoop() }
        scheduleScreenOff()
        return START_STICKY
    }

    private suspend fun samplingLoop() {
        while (true) {
            val raw = withContext(Dispatchers.IO) { RootPowerReader.read() }
            // 串联双电池换算：在**采样入口**统一处理，下游（曲线、统计、常驻通知、手动导出、
            // 自动保存、充电功率监测的 1W 判定）全部自动同口径，避免各处各算一遍。
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
                _samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)
                _error.value = null
                maybeNotify(sample)
                watchChargePower(sample)
            } else {
                _error.value = RootPowerReader.lastError ?: "读取失败"
            }
            delay(_intervalMs.value.coerceAtLeast(200L))
        }
    }

    // ---------- 充电功率监测 ----------

    private fun resetChargeWatch() {
        chargeSeen = false
        lowPowerSince = null
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
     * 充电功率监测判定：功率连续低于 [LOW_POWER_THRESHOLD_W] 满 [LOW_POWER_HOLD_MS]
     * 即自动导出一次 CSV，**采样本身不打断**。
     *
     * 三道护栏缺一不可：
     * 1. **武装前提** [chargeSeen] —— 本场会话至少出现过一次 > 阈值的功率才算"正在充电"。
     *    没插充电器时 powerW 恒为负值（见 PowerSample 的符号约定），不设这道判断会直接
     *    在"根本没充电"的场景下静默触发；
     * 2. **连续区间** [lowPowerSince] —— 功率一旦回升到阈值以上就复位，只认连续低功率，
     *    避免把若干段零散的低功率时间累加成 5 分钟；
     * 3. **幂等** [autoSaved] —— 命中一次后本场会话不再触发。涓流/已充满阶段功率会长期
     *    低于 1W，不拦就会每 5 分钟刷出一个新 CSV。
     */
    private fun watchChargePower(sample: PowerSample) {
        if (autoSaved || !Prefs.getChargeMonitor(this)) return

        if (sample.powerW >= LOW_POWER_THRESHOLD_W) {
            chargeSeen = true
            lowPowerSince = null
            return
        }
        if (!chargeSeen) return

        val since = lowPowerSince
        if (since == null) {
            lowPowerSince = sample.timeMillis
            return
        }
        if (sample.timeMillis - since < LOW_POWER_HOLD_MS) return

        autoSaved = true
        lowPowerSince = null
        // NonCancellable：服务可能在写盘途中被销毁（scopeJob.cancel），写一半会留下
        // MediaStore 的 IS_PENDING=1 孤儿记录 —— 让本次导出写完再响应取消
        scope.launch(Dispatchers.IO + NonCancellable) { autoSaveCsv() }
    }

    /**
     * 导出**本场采样全量快照**（与手动「导出 CSV」同口径：都是从开始采样到此刻的完整
     * 充电曲线），文件名加 `powermeter_charge_` 前缀以便区分。
     *
     * 全程不触碰 [_running] / [samplingJob] —— 只写文件，采样继续。
     */
    private fun autoSaveCsv() {
        val snapshot = _samples.value
        if (snapshot.isEmpty()) return
        val uri = CsvExporter.export(this, snapshot, prefix = AUTO_SAVE_PREFIX)
        notifyAutoSaved(uri, snapshot.size)
    }

    /** 自动保存完成后发一条一次性通知：熄屏状态下用户看不到 Toast，通知是唯一反馈途径 */
    private fun notifyAutoSaved(uri: Uri?, count: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val text = if (uri != null) {
            val name = displayNameOf(uri) ?: "CSV"
            "已自动保存 $name（$count 条记录，采样继续）"
        } else {
            "自动保存失败，采样继续"
        }
        nm.notify(
            NOTIF_ID_AUTO_SAVE,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle("充电功率监测")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build(),
        )
    }

    /**
     * 取 MediaStore 里那份文件的真实文件名。
     *
     * 不能用 `uri.lastPathSegment` —— 对 MediaStore 的 content:// 记录它返回的是数字 ID
     * 而非 DISPLAY_NAME。
     */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

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

    private fun maybeNotify(sample: PowerSample) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_MIN_INTERVAL_MS) return
        lastNotifyAt = now
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(sample))
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

    private fun buildNotification(sample: PowerSample?): Notification {
        val text = if (sample == null) {
            "正在启动采样…"
        } else {
            "${sample.powerW.f3()} W · ${sample.voltageV.f3()} V · " +
                "${sample.currentMa.f3()} mA · ${sample.tempBatteryC.f1()} ℃"
        }
        val title = when {
            sample == null -> "功率监测"
            sample.isCharging -> "充电中 · ${sample.socPct}%"
            else -> "放电中 · ${sample.socPct}%"
        }
        val pi = contentIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时功率采样", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "锁屏后持续读取底层电量节点"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun acquireWakeLock() {
        if (!Prefs.getWakeLock(this)) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerMeter:Sampling").apply {
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        // 取消服务级 Job：samplingJob 与 scheduleScreenOff 的 delay 协程一并结束，
        // 避免服务销毁后仍有协程在跑（见 scopeJob 的注释）
        scopeJob.cancel()
        samplingJob = null
        releaseWakeLock()
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

    private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

    /** 常驻通知里的电池温度按用户约定取 1 位小数（2026-09-21） */
    private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)
}
