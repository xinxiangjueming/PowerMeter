package com.chen.powermeter.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import com.chen.powermeter.R
import com.chen.powermeter.data.FrameHistoryStore
import com.chen.powermeter.data.FrameRateSource
import com.chen.powermeter.data.FrameSample
import com.chen.powermeter.data.RootPowerReader
import com.chen.powermeter.data.db.FrameSampleEntity
import com.chen.powermeter.data.db.FrameCpuSampleEntity
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.data.db.FrameDatabase
import com.chen.powermeter.util.ShizukuHelper
import com.chen.powermeter.util.appString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "FrameRecordController"

/**
 * 帧率录制控制器（进程级单例）。
 *
 * 为什么放在进程级单例而不是 Composable：`FrameMeterScreen` 会因旋转 / 深浅色切换重建，
 * 而一场录制长达 5~30 分钟 —— 录到一半被重建打断等于白录。采集循环跑在自带的
 * [SupervisorJob] + IO 作用域上，与任何 Activity 的生命周期都无关。
 *
 * ⚠️ 本阶段**没有前台服务**：息屏后系统随时可能挂起进程，长时录制请在亮屏下进行。
 * 需要息屏录制时应把它搬进 `SamplingService` 那样的前台服务（下一轮的事）。
 *
 * 采集节奏（2026-09-25 重构为**子拍**制）：
 * - **每个子拍**（[SUB_TICK_MS] = 250ms）：CPU 快样一条命令（/proc/stat 差分 + 逐核频率，
 *   [FrameRateSource.readCpuFastSample]）—— CPU 使用率/频率是快变量，1s 一点会把真实
 *   抖动与频率升降挡全部摊平（用户对照 Scene 工具箱实测反馈"明显不如"）；
 * - **每个完整拍**（每 [BEAT_SUBTICKS] 个子拍 ≈ 1s）：`dumpsys SurfaceFlinger --timestats`
 *   → 与上一拍做**差分**得到本拍帧率；电量四项（电压 / 电流 / 功率 / 电池温度）同样
 *   每秒一拍，走功率侧同一条取数链 [RootPowerReader.read]（root 机器 sysfs 节点、
 *   Shizuku 机器 BatteryManagerSource 实时电流），与帧率逐秒对齐才能回答
 *   "掉帧的那一刻是不是正好在发热 / 拉电流"；
 * - **每 [SLOW_POLL_EVERY] 个完整拍**：前台应用包名、刷新率、虚拟温度（这几项变化慢、
 *   但每条命令都要起进程）。中间周期沿用上一次的值。
 *
 * 落库路径只有一条 —— [finishAndPersist]，由「限时到点」「通道失效」「用户手动停止」
 * 三种结束方式共用。⚠️ 样本列表因此是**对象字段**而不是循环局部变量：手动停止会
 * `cancel()` 掉采集协程，局部变量随协程一起消失，已录到的整场就丢了。
 */
object FrameRecordController {

    private const val SAMPLE_INTERVAL_MS = 1_000L

    /**
     * 录制循环的子拍间隔（ms）：CPU 快样（使用率差分 + 逐核频率）的采样周期。
     * 完整拍（timestats 差分 / 电量 / 落库样本）每 [BEAT_SUBTICKS] 个子拍跑一次 ≈ 1s，
     * 帧率样本仍是 1Hz。250ms 的依据：用户对照 Scene 工具箱的 CPU 卡实测（2026-09-25）
     * —— Scene 约 4Hz，使用率抖动与频率升降挡清晰可见；1s 一点全部摊平（"明显不如"）。
     */
    private const val SUB_TICK_MS = 250L

    /** 每 N 个子拍跑一次完整拍（4 × 250ms ≈ 1s） */
    private const val BEAT_SUBTICKS = 4

    /**
     * 帧率汇总的合理上限容差（fps，2026-09-25 加）。
     *
     * 背景：60Hz 锁帧的游戏，厂商性能面板会按 61Hz 的口径展示、抬高"平均帧率"的观感。
     * 本应用虽是 timestats 差分自算（不走厂商口径），但差分窗口的**边界效应**同样能给出
     * 超过刷新率的单点值 —— T 秒窗口内最多合成 refresh×T+1 帧，1s 周期下 fps 估计可到
     * refresh+1.0。汇总时以「当前刷新率 + 本容差」为上限，超限值不参与 avg / min / max。
     *
     * 取 0.5 的取舍：能拦下"61 on 60Hz"这类虚高（含边界尖峰 60.6+），又不会把正常
     * 60.0~60.4 的窗口抖动误杀；被丢的只是略高于刷新率的毛刺，对均值影响 <0.1fps。
     */
    private const val FPS_CEILING_TOLERANCE = 0.5

    /**
     * 实时帧率差分的物理上限容差（fps，2026-09-25 加）。
     *
     * 单表面 T 秒窗口内最多呈现 refresh×T + 1 帧（差分窗口边界效应），1s 级 dt 下差分结果
     * 不可能超过刷新率 +1。**差分超限 = 计数被污染**：AOSP 16 的逐图层计数只随真实呈现递增
     * （见 FrameRateSource.Timestats.perLayerFrames 的两级 key 说明），能超限只剩 ROM 私改
     * timestats、或 shell 层收窄把别处的 totalFrames 行误归入目标图层这类非原生因素 ——
     * 照常出数就是悬浮 tab 冒 1000+ 的假帧率（2026-09-25 用户实测反馈）。
     *
     * 超限拍的处理：**拒绝出数**（读数保持上一拍、不产样本），但**基线照常推进** ——
     * 一次性跳变下一拍自愈；持续性跳变每拍都会走到同一条 warning（tag=FrameRecordController，
     * 带图层名与 prev/cur 原值），真机 logcat 一眼定位是哪个图层、涨了多少。
     */
    private const val FPS_LIVE_CEILING_TOLERANCE = 1.0

    /**
     * 单个采样周期的最短等待。补偿式等待的下界 —— 本轮工作耗时逼近 1s 时（
     * 低端机 + thermalservice 慢），不留这一档会让循环变成无间隔硬轮询，
     * 反过来把被测应用的帧率压下去。（**预览循环**用；录制循环的子拍下限见
     * [MIN_SUB_TICK_MS]）
     */
    private const val MIN_CYCLE_MS = 200L

    /**
     * 录制循环**子拍**的最短等待（ms）：完整拍的全部取数命令会吃掉大半秒，该子拍必然
     * 超时 —— 下限只防"快样 exec 完立即下一拍"的硬轮询，取 30ms 足够。若沿用 1s 循环的
     * 200ms 下限，四个子拍的等待底仓就吃掉 0.8s，拍周期会被顶到 1.6s+（每秒多跑的
     * 快样命令叠加完整拍耗时本来就逼近 1s，没有余量再垫 200ms/拍的下限）。
     */
    private const val MIN_SUB_TICK_MS = 30L

    /** 慢速采集（前台应用 / 刷新率 / 温度）的抽稀倍率（按**完整拍**计，5 拍 = 5s） */
    private const val SLOW_POLL_EVERY = 5

    /** 错误码：无可用取数通道（既无 Shizuku 也无 root） */
    const val ERROR_NO_ACCESS = 1

    /** 错误码：落库失败（已录到的数据未能保存） */
    const val ERROR_SAVE_FAILED = 2

    /**
     * 错误码：读不到前台应用（`dumpsys activity` 失败 / 输出异常）。
     *
     * ⚠️ 自 2026-09-22 起自身界面**不再排除**（用户口径：任何界面都实时显示帧率，
     * 停在本应用页面上照样测自己的渲染帧率），「目标未锁定」只剩命令失败这一种来源。
     */
    const val ERROR_NO_TARGET_APP = 3

    /**
     * 差分基线的有效窗口（秒）：距上一拍超过它 = 基线**陈旧**（开悬浮窗后第一次采样 /
     * 长暂停后恢复），窗口横跨了停顿期，差分没有意义 —— 作废重建，本拍只建基线。
     * 正常拍间隔 1~2.5s（reset 拍最慢），取 5s 留足余量；不设这道闸，重开悬浮窗后
     * 第一拍会拿"几小时前的累计值 ÷ 秒级 dt"算出假尖峰。
     */
    private const val STALE_DIFF_WINDOW_SEC = 5.0

    /** 连续多少轮读不到目标应用才提示（跳过头一两轮的切换延迟） */
    private const val NO_TARGET_HINT_TICKS = 3

    /**
     * 连续多少轮读不到累计帧数才判定**通道真的不可用**并停止录制（1s 一拍 ≈ 6 秒）。
     *
     * ⚠️ 为什么不是首轮失败就停（2026-09-22 修）：功率侧的取数循环是「每个采样周期重试一次、
     * 失败不缓存」，所以 Shizuku 的 UserService 绑定晚生效一两秒、su 授权框刚点完这类**暂时**
     * 不可用都能自愈；而录制原本首轮 `readTimestats()` 返回 null 就 break —— 真机实测点了四次
     * 录制，每次都 **124ms 就中止**（两条 dumpsys 根本没跑完），用户看到的就是「点了没反应」。
     * 现在给它一个宽限期：这期间 tab 保持红色、读数显示「—」、面板直接给出**具体原因**，
     * 通道一恢复就继续录（且差分基线已作废，不会算出假尖峰）。
     */
    private const val MAX_READ_FAILURES = 6

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Toast 必须回主线程发（采集循环跑在 IO） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 录制采集循环句柄；⚠️ 停止时必须显式 cancel（项目约定：不留悬挂协程） */
    private var job: Job? = null

    /** 预览采集循环句柄（悬浮窗打开但尚未录制时） */
    private var previewJob: Job? = null

    /**
     * 录制期间持有的 CPU 唤醒锁。
     *
     * 前台服务只保证**进程**不被冻结 / 回收，不保证**CPU** 不休眠 —— 息屏录制时
     * 没有这把锁，采集循环会被 suspend 到下次亮屏，录出一段假的"0 帧"空洞。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    // ── 本场录制的累积状态：只由采集协程写、由 finishAndPersist 读 ──
    private val pending = ArrayList<FrameSample>()

    /**
     * CPU 快样缓冲（250ms 子拍写入，完整拍只读聚合窗口）：与 [pending] 同一条落库路径。
     * ⚠️ 内存里实体的 sessionId=0，finishAndPersist 拿到会话 id 后 copy 补上
     * （见 [FrameCpuSampleEntity]）。
     */
    private val pendingCpu = ArrayList<FrameCpuSampleEntity>()
    private var sessionPkg = ""
    private var sessionRefreshHz = 0
    private var sessionStartWall = 0L
    private var sessionEndWall = 0L

    // ── timestats 差分基线（预览 / 录制两循环**共用**）─────────────
    //
    // ⚠️ 两个循环互斥（start 会先 stopPreview），同一时刻只有一个在跑，无需并发防护。
    // ⚠️ 基线是**对象字段**而不是循环局部变量，为的是「预览 → 录制无缝交棒」（2026-09-25
    //   用户反馈「点录制瞬间 tab 变 —，过一会才恢复」的根治）：预览一直在出数，点录制
    //   若把基线/目标清空重来，录制首拍要么重新 reset timestats（clear 后要 2~3 拍才有
    //   差分），要么首拍只建基线 —— 用户看到的就是一个好好的数字突然变「—」。
    //   交棒后：目标没变（用户点录制时通常就停在当前页面）→ 录制第一拍直接续上预览的
    //   差分，读数连续；只有目标真的变了才走 reset + 基线作废。
    /** 基线对应的目标包名（与 sessionPkg 不同则下一拍先 reset 差分） */
    private var diffPkg = ""
    /** 各图层上一拍的累计帧数；空 = 尚无基线（首拍只建基线不出数） */
    private var diffPerLayer: Map<String, Long> = emptyMap()
    /** 上一拍全局 missedFrames（丢帧差分基线） */
    private var diffMissed = 0L
    /** 上一拍 timestats 快照时刻（readTimestats 返回处打点，**非拍首** —— 见采集循环 diffAt 赋值处） */
    private var diffAt = 0L

    /**
     * 逐图层差分出实时帧率并推进基线；无可差分（首拍 / 基线陈旧 / 图层全换）时返回 null。
     *
     * ⚠️ 帧率 = **单图层差分的最大值**，不是认领总和的差分（原因见
     * [FrameRateSource.Timestats.perLayerFrames]）：
     * 转场瞬间新旧 Surface 共存，总和差分会给出超过刷新率的假帧率（120Hz 屏实测 160+）；
     * 逐图层取 max 恰好选中「正在动画的那个表面」，单表面物理上不可能超过刷新率。
     *
     * ⚠️ 新出现的图层本拍不计（[diffPerLayer] 里没有它的基线）：它的累计从 0 起跳，
     * 若照常差分会算出「0 → 累计值」的假尖峰；下一拍起自然纳入。
     *
     * ⚠️ 基线陈旧（距上一拍超 [STALE_DIFF_WINDOW_SEC]，典型 = 重开悬浮窗后的第一拍）
     * 时本拍只建基线不出数：作废旧基线、下一拍重建 —— 不会拿"停顿前的累计值 ÷ 秒级
     * dt"算出假值。返回 null 的拍调用方不产样本，但拍尾照常推进 diffMissed，
     * 下一拍的丢帧差分基线同步就位。
     *
     * ⚠️ **物理上限守卫**（2026-09-25 加，口径见 [FPS_LIVE_CEILING_TOLERANCE]）：差分结果
     * 超过「当前刷新率 +1」即视为计数被污染，拒绝出数并打 warning 留痕（图层名 / prev /
     * cur / dt 全带上，真机 logcat 直接定位污染源）。**基线照常推进**：一次性跳变（如
     * statsd 拉 atom 清零后又涨回、误认领行只出现一拍）下一拍自愈；持续性跳变则每拍
     * 留痕、读数保持最后一个可信值 —— 宁可「—」也不显示 1000+ 的假帧率。
     *
     * ⚠️ @param snapshotAt 必须是**本拍 timestats 快照的落地时刻**（readTimestats 返回处
     * 打点），不能用拍首时间：差分窗口必须与两次快照的实际间隔对齐，拍首与快照之间隔着
     * 本拍的全部取数命令，错位量随命令耗时不等而波动（事故记录见采集循环 diffAt 赋值处）。
     */
    private fun updateFpsFromDiff(stats: FrameRateSource.Timestats, snapshotAt: Long): Double? {
        val dtSec = (snapshotAt - diffAt) / 1_000.0
        val usable = diffPerLayer.isNotEmpty() && dtSec > 0.0 && dtSec <= STALE_DIFF_WINDOW_SEC
        var bestFps = -1.0
        var bestLayer: String? = null
        var bestPrev = 0L
        var bestCur = 0L
        if (usable) {
            for ((layer, cur) in stats.perLayerFrames) {
                val prev = diffPerLayer[layer] ?: continue
                if (cur >= prev) {
                    val f = (cur - prev) / dtSec
                    if (f > bestFps) {
                        bestFps = f
                        bestLayer = layer
                        bestPrev = prev
                        bestCur = cur
                    }
                }
            }
        }
        // ⚠️ 基线推进先于守卫判定：污染值进基线后，下一拍的差分即回归正常（一次性跳变自愈）；
        //    守卫只挡住「本拍读数与本拍样本」，不影响差分窗口的连续性
        diffPerLayer = if (usable || diffPerLayer.isEmpty()) stats.perLayerFrames else emptyMap()
        if (bestFps < 0.0) return null
        if (sessionRefreshHz > 0 && bestFps > sessionRefreshHz + FPS_LIVE_CEILING_TOLERANCE) {
            Log.w(
                TAG,
                "帧率差分超刷新率上限，本拍拒绝出数（基线已照常推进）：" +
                    "raw=${"%.1f".format(bestFps)} fps vs refresh=${sessionRefreshHz}Hz, " +
                    "dt=${"%.2f".format(dtSec)}s, layer=$bestLayer, " +
                    "Δ=${bestCur - bestPrev} (prev=$bestPrev → cur=$bestCur)",
            )
            return null
        }
        _fps.value = bestFps
        return bestFps
    }

    /** 限时到点时刻（epoch ms）；0 = 不限时。由 [start] / [setLimit] 写，采集循环读 */
    private var deadlineMs = 0L

    /**
     * 预览中：悬浮窗打开、还没点录制。
     *
     * 与 [recording] 互斥 —— 两个循环都跑 `--timestats` 会各自 fork 进程、还会互相抢
     * CPU，测出来的帧率就是被自己拉低的。开始录制前必须先停预览。
     */
    private val _previewing = MutableStateFlow(false)
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** 本场录制的**起始帧率**（第一个有效采样值）；未录制 / 还没采到时为 NaN */
    private val _startFps = MutableStateFlow(Double.NaN)
    val startFps: StateFlow<Double> = _startFps.asStateFlow()

    /**
     * 最近一个采样周期算出的帧率（未舍入；UI 按精度分级显示）。
     *
     * ⚠️ `NaN` = **还没有一帧有效差分**，UI 必须显示「—」而不是 0.0（见
     * `FrameMeterScreen.formatFrameFps`）。两种情形都会落到 NaN：通道未打通（每个周期都没读到
     * 累计帧数）与首轮只建基线 —— 之前这里用 0.0 兜底，结果通道不通时悬浮 tab 一直显示
     * 「0.0」，看着像一个真实读数，用户根本判断不出是「没通」还是「真 0 帧」（2026-09-22 实测反馈）。
     */
    private val _fps = MutableStateFlow(Double.NaN)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    /** 已录制时长 ms（UI 用于倒计时） */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** 设定的录制时长（分钟）；null = 不限时，需手动停止 */
    private val _limitMinutes = MutableStateFlow<Int?>(null)
    val limitMinutes: StateFlow<Int?> = _limitMinutes.asStateFlow()

    /** 取数失败原因（错误码）；非 null 时 UI 展示对应文案 */
    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    /**
     * 取数失败的**具体原因**（人话，直接显示在面板上），与 [ERROR_NO_ACCESS] 搭配使用。
     *
     * 功率侧早就有这套准确文案（`RootPowerReader.lastError`：Shizuku 未就绪 / 未授权 / 无 root），
     * 帧率侧原先一律笼统报「无可用取数通道」—— 用户拿到这句话无法判断该去授权、该等绑定、
     * 还是该去装 Shizuku（2026-09-22 用户质问「为什么功率监测一点问题没有」即由此而来）。
     * 判不出来时为 null，UI 回落到通用的 `frame_error_no_access`。
     */
    private val _errorDetail = MutableStateFlow<String?>(null)
    val errorDetail: StateFlow<String?> = _errorDetail.asStateFlow()

    // ── 前台化与唤醒锁 ────────────────────────────────────

    /**
     * 把进程提到前台 + 拿 CPU 唤醒锁。
     *
     * 两者缺一不可，且解决的是两个不同的问题：
     * - 前台服务 → 进程不被冻结 / 回收（Android 12+ 的缓存进程冻结尤其致命）；
     * - 唤醒锁 → CPU 在息屏后不休眠，采集循环才能继续按 1s 跑。
     */
    private fun enterRecordingMode() {
        FrameRecordService.ensureRunning(appContext)
        val pm = appContext.getSystemService(PowerManager::class.java) ?: return
        runCatching {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PowerMeter:frameRecord",
            ).apply {
                // 非引用计数：acquire/release 成对调用，重复 acquire 不会叠加计数
                setReferenceCounted(false)
                // 1 小时上限兜底：即使 release 路径因异常没走到，也不会永久霸占 CPU
                acquire(60 * 60 * 1000L)
            }
        }
    }

    /** 退出录制模式：撤前台服务 + 放锁。两步都必须容错，绝不能因为一步失败卡住另一处 */
    private fun exitRecordingMode() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
        FrameRecordService.shutdown(appContext)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 开始录制。
     *
     * @param limitMinutes 录制时长上限（分钟）；null = 不限时（用户手动点停止）
     */
    /**
     * 打开悬浮窗后的**预览采样**：只更新 [fps]，不产样本、不落库。
     *
     * 存在的原因是"当前帧率"这个读数在录制之前就该是活的 —— 用户要靠它判断
     * 值不值得录、以及被测应用是不是真的在前台。预览与录制共用同一套取数，
     * 所以预览时看到的帧率和录出来的第一条是对得上的。
     */
    fun startPreview() {
        if (_previewing.value || _recording.value) return
        // ⚠️ 这里**不做**权限预探测：判定通道要 fork su、必须上后台线程，而本函数由 UI 调用。
        //    真伪交给采集循环首轮实际执行命令时判定（失败即报错并停下）。
        _error.value = null
        _errorDetail.value = null
        // 新一场预览从"无读数"开始：上一场残留的旧帧率不许冒充当前值
        _fps.value = Double.NaN
        _previewing.value = true
        previewJob = scope.launch { previewLoop() }
    }

    /** 停止预览采样 */
    fun stopPreview() {
        if (!_previewing.value) return
        _previewing.value = false
        previewJob?.cancel()
        previewJob = null
    }

    fun start(limitMinutes: Int?) {
        if (_recording.value) return
        // 预览与录制都跑 --timestats，同时跑只会互相干扰；录制前先停预览
        stopPreview()
        // ⚠️ 刻意**不**在这里做权限预探测：本函数由 UI 线程调用，而探 su 要 fork 进程并阻塞，
        //    会把主线程卡住（症状就是"点了完全没反应"）；何况自己写的 su 判定口径一旦与
        //    RootPowerReader 不一致，还会在已授权 root 的机器上误报"无可用通道"。
        //    现在直接进入录制（tab 立刻变红，用户马上有反馈），通道真伪由采集循环
        //    首轮实际执行命令时判定：失败则置 error 并停止。
        _error.value = null
        _errorDetail.value = null
        _limitMinutes.value = limitMinutes
        _elapsedMs.value = 0L
        // ⚠️ _fps / sessionPkg / diff* 基线**刻意不清**（2026-09-25 改）：预览正在出数，
        //    点录制的瞬间清掉它们，tab 会先变「—」、等 2~3 拍才恢复（用户实测反馈）。
        //    实时读数是"当前帧率"的展示而非"本场统计"，预览的最后一个值就是真实值，
        //    录制循环第一拍直接续上预览的差分基线，数字无缝衔接。
        //    本场自己的统计从下一拍开始积累（startFps 首个有效采样才记）。
        _startFps.value = Double.NaN
        pending.clear()
        pendingCpu.clear()
        sessionRefreshHz = 0
        sessionStartWall = System.currentTimeMillis()
        sessionEndWall = sessionStartWall
        deadlineMs = limitMinutes?.let { sessionStartWall + it * 60_000L } ?: 0L
        _recording.value = true
        enterRecordingMode()
        job = scope.launch { loop(limitMinutes) }
    }

    /**
     * 录制中改设定时长（用户在录制开始后才挑 5 / 10 / 15 / 30）。
     *
     * 到点时刻按**本场开始时间**推算，而不是从设定的这一刻起算 —— 用户看到的是
     * 「这场录 5 分钟」，不是「从现在起再录 5 分钟」。
     */
    fun setLimit(minutes: Int) {
        _limitMinutes.value = minutes
        deadlineMs = sessionStartWall + minutes * 60_000L
    }

    /** 停止录制并落库。从 UI 线程调用也安全：收尾走自带 IO 作用域 */
    fun stop() {
        if (!_recording.value) return
        _recording.value = false
        job?.cancel()
        job = null
        exitRecordingMode()
        // ⚠️ 会话字段必须**就地快照**再交给异步落库：sessionPkg / diff* 现在是预览与录制
        //    共用的交棒字段，stop() 返回后 UI 会立刻 startPreview()，新预览协程会接管
        //    sessionPkg —— 异步落库若晚于它，落库的包名就被预览锁到的新目标污染了
        // （2026-09-25 引入交棒时一并修复；预览用局部变量的旧实现没有这条竞态）
        val pkg = sessionPkg
        val refreshHz = sessionRefreshHz
        val startWall = sessionStartWall
        val endWall = sessionEndWall
        scope.launch { finishAndPersist(pkg, refreshHz, startWall, endWall) }
        // 本场已结束，时长选择随之作废（见 [clearLimit]）
        clearLimit()
    }

    /**
     * 清空时长选择，回到「未选」—— 四个胶囊都不高亮。
     *
     * 用户口径（2026-09-22）：**不记忆上次的选择**，每次开悬浮窗都重新挑。
     * 时长是一次性的现场决定，「上次选了 5 分钟」不构成「这次也要 5 分钟」的默认；
     * 给个预选反而容易被误按成直接开录。
     *
     * 录制中不允许清：本场 deadlineMs 仍挂着这个值，清了会让倒计时失去依据。
     * 落库路径不受影响 —— `loop(limitMinutes)` 收的是当时那份参数拷贝，不回读这里。
     */
    fun clearLimit() {
        if (_recording.value) return
        _limitMinutes.value = null
    }

    /** 清空错误提示（UI 展示过之后调用） */
    fun clearError() {
        _error.value = null
        _errorDetail.value = null
    }

    /**
     * 判定「读不到数据」的**具体原因**，供 UI 直接展示。
     *
     * 口径与功率侧完全一致：优先用同一个 [RootPowerReader] 的判定与文案（它失败时会把
     * `lastError` 写成「Shizuku 已授权但服务未就绪（正在自动重试绑定）」「Shizuku 正在运行但
     * 本应用未获授权」「无 root 权限且未安装/运行 Shizuku」这类可操作的话）。
     *
     * ⚠️ 两道判据都要看：`checkAccess()` 的 true 可能是**粘性**的（它内部 `if (hasAccess) return true`，
     * 一旦成功过就永久为真，Shizuku 事后死掉也不复查），所以再用 `ShizukuHelper` 的三态复核一次，
     * 否则会把「Shizuku 已死」误报成「通道正常、命令失败」。
     *
     * ⚠️ 必须在 IO 线程调用（`checkAccess()` 在需要时会 fork 一次 su 做探测）。
     */
    private fun diagnoseNoAccess(): String {
        val access = RootPowerReader.checkAccess()
        Log.w(
            TAG,
            "读不到帧数据：checkAccess=$access, " +
                "shizuku{available=${ShizukuHelper.available.value}, " +
                "granted=${ShizukuHelper.granted.value}, bound=${ShizukuHelper.serviceBound.value}}, " +
                "su=${RootPowerReader.rootAvailable.value}, accessMode=${RootPowerReader.accessMode}, " +
                "lastError=${RootPowerReader.lastError}",
        )
        if (!access) {
            // 判定为「无通道」时 lastError 已经是可操作的具体原因（见 RootPowerReader.checkAccess）
            RootPowerReader.lastError?.let { return it }
        }
        return when {
            // Shizuku 真的连着、su 也探到了 → 通道在，那就是命令这一层的问题（详情看 FrameRateSource 日志）
            ShizukuHelper.serviceBound.value -> appString(R.string.frame_error_command_failed)
            !ShizukuHelper.available.value -> appString(R.string.error_no_access)
            !ShizukuHelper.granted.value -> appString(R.string.error_shizuku_not_granted)
            else -> appString(R.string.error_shizuku_service_not_ready)
        }
    }

    /**
     * 录制未能开始时的可见反馈（Toast）。
     *
     * 与 UI 面板里的错误行**不重复**：那一行只有停在帧率页面上才看得到，而用户的习惯是
     * 点完录制就切到被测应用 —— 切走之后唯一能收到的反馈就是这条 Toast。
     * 文案复用常驻通知的「帧率录制已中止：无可用取数通道」（三语齐备，不为 Toast 另立文案）。
     */
    private fun toastRecordingNotStarted() {
        mainHandler.post {
            runCatching {
                Toast.makeText(
                    appContext,
                    appString(R.string.notify_frame_no_access),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // ── 采集循环 ──────────────────────────────────────────

    private suspend fun loop(limitMinutes: Int?) {
        /** 虚拟温度（CPU 代表温感区）：变化慢，按 5s 抽稀，中间周期沿用上一次的值 */
        var virtualTempC: Double? = null
        /** GPU 温感区温度：同 5s 抽稀；机型无 GPU 温感区时恒 null（曲线断线） */
        var gpuTempC: Double? = null
        /** GPU 占用率 %：快变量，完整拍直读 kgsl（一条 cat）；节点不可读时恒 null */
        var gpuLoadPct: Double? = null
        var tick = 0
        var beat = 0
        /** 本拍 CPU 聚合窗口在 [pendingCpu] 里的起点（上一完整拍结束位置） */
        var cpuFastFrom = 0
        /** 连续读不到累计帧数的完整拍轮数；成功一轮即归零（宽限期见 [MAX_READ_FAILURES]） */
        var readFailures = 0

        // suspend 函数里没有 CoroutineScope 接收者，isActive 要显式从 coroutineContext 取
        while (coroutineContext.isActive) {
            val cycleStart = System.currentTimeMillis()

            // ── CPU 快样：每个子拍都采（250ms 级，对齐 Scene 工具箱的密度观感；1s 一点
            //    会把使用率抖动 / 频率升降挡全部摊平 —— 2026-09-25 用户对照实测反馈）。
            //    串行跑在主循环里：SuSession 常驻 shell 靠 stdin 喂命令，不能另起协程并发。
            FrameRateSource.readCpuFastSample()?.let { fs ->
                pendingCpu.add(FrameCpuSampleEntity.from(System.currentTimeMillis(), fs))
            }
            // stop() cancel 后立即作废本拍：快样命令是阻塞调用，不能让已停止的拍继续走完整拍
            if (!coroutineContext.isActive) break

            // ── 完整拍（每 BEAT_SUBTICKS 个子拍 ≈ 1s）：timestats 差分 / 电量 / 落库样本 ──
            if (tick % BEAT_SUBTICKS == 0) {
                val now = cycleStart

                // 前台应用：**未锁定前每轮都试**。不排除自身包名（2026-09-22 用户口径：
                // 任何界面都实时显示帧率）—— 停在本应用页面时测的就是自己的渲染帧率。
                // ⚠️ start() 已从预览交棒 sessionPkg（通常非空），所以首拍只有 beat==0 会刷新
                if (beat % SLOW_POLL_EVERY == 0 || sessionPkg.isEmpty()) {
                    FrameRateSource.readTopPackage()
                        .takeIf { it.isNotEmpty() }
                        ?.let { sessionPkg = it }
                    // readTopPackage 也是阻塞调用：cancel 检查同下
                    if (!coroutineContext.isActive) break
                }
                // GPU 占用率：快变量，完整拍直读（一条 cat，代价可忽略）
                FrameRateSource.readGpuLoadPct()?.let { gpuLoadPct = it }
                // 刷新率与虚拟温度变化慢、且每条命令都要起进程，保持抽稀。
                // ⚠️ 刷新率**每轮慢速拍都重读**（读不到不清零，2026-09-25 从「只在为 0 时读」改）：
                //    智能刷新 / LTPO 会在 60↔120 间切换，而差分的物理上限守卫（updateFpsFromDiff）
                //    用的是「当前」刷新率 —— 锁死首拍值，切换后要么误杀合法读数、要么放行不了虚高。
                if (beat % SLOW_POLL_EVERY == 0) {
                    FrameRateSource.readRefreshRateHz().takeIf { it > 0 }?.let { sessionRefreshHz = it }
                    // CPU / GPU 温度同一条温感区命令一次取回（原本两个函数各 fork 一轮
                    // thermal_zone 遍历，慢速拍平白多花一倍耗时，采样周期被拉得更长）
                    FrameRateSource.readCpuGpuTempsC().let { (tCpu, tGpu) ->
                        tCpu?.let { virtualTempC = it }
                        tGpu?.let { gpuTempC = it }
                    }
                }

                // ⚠️ 目标应用刚锁定（或中途换台）时：差分基线作废（timestats 里认领的图层累计
                //    帧数会从 0 跳到该图层的累计值，不重置就会算出巨大的假帧率尖峰），
                //    同时重置 timestats 跟踪表：图层数超上限后新图层的跟踪被静默拒绝，
                //    认领到的只会是冻结的历史累计值（差分恒 0）—— clear 后前台图层才会被
                //    重新跟踪（根因与实证见 FrameRateSource.resetTimestats）。
                //    ⚠️ 预览交棒场景：sessionPkg == diffPkg（用户点录制时通常停在当前页面）
                //    → 两个都不触发，第一拍直接续差分，读数无缝衔接。
                if (sessionPkg != diffPkg) {
                    diffPkg = sessionPkg
                    diffPerLayer = emptyMap()
                    diffMissed = 0L
                    if (sessionPkg.isNotEmpty()) FrameRateSource.resetTimestats()
                }

                // 提示态（不是错误）：还停在 PowerMeter 自己页面上时必然出现，切到被测应用即消失
                if (sessionPkg.isEmpty()) {
                    if (beat >= NO_TARGET_HINT_TICKS) _error.value = ERROR_NO_TARGET_APP
                } else if (_error.value == ERROR_NO_TARGET_APP) {
                    _error.value = null
                }

                val stats = FrameRateSource.readTimestats(sessionPkg)
                // ⚠️ 快照落地时刻（readTimestats 返回处）：差分窗口以此打点，理由见下方 diffAt 赋值处
                val statsAt = System.currentTimeMillis()
                // 同上：cancel 后本拍作废，不写共享基线（readTimestats 阻塞期间可能已被 stop()）
                if (!coroutineContext.isActive) break
                if (stats == null) {
                    // 这一轮读不到累计帧数 —— **先不判死**（宽限期见 [MAX_READ_FAILURES]）。
                    // 读数清零成"无"：通道死了还挂着最后一帧的旧数字，界面会继续骗人
                    readFailures++
                    _fps.value = Double.NaN
                    // 差分基线一并作废：否则通道恢复后第一轮会拿"几秒前的累计值 ÷ 1 秒"算出假尖峰
                    diffPerLayer = emptyMap()
                    diffMissed = 0L
                    // 只在一段失败的开头判定一次原因（内部要 fork su 探测，不必每秒重算）
                    if (readFailures == 1) _errorDetail.value = diagnoseNoAccess()
                    if (readFailures >= MAX_READ_FAILURES) {
                        // 一个样本都没采到 = 录制**根本没跑起来**（典型：无 root / Shizuku 未运行）。
                        // 必须补一条 Toast：悬浮 tab 的红→紫闪只持续一百多毫秒，肉眼等于没有，
                        // 用户只会认为"点了没反应"（2026-09-22 实测反馈）。中途失效则不发 ——
                        // 那时数据已落库、用户可能正在被测应用里，弹窗反而是打扰。
                        _error.value = ERROR_NO_ACCESS
                        if (pending.isEmpty()) toastRecordingNotStarted()
                        break
                    }
                } else {
                    readFailures = 0
                    // 通道自愈：宽限期内恢复时把错误提示一并撤掉（同功率侧：成功即清错误）
                    if (_error.value == ERROR_NO_ACCESS) {
                        _error.value = null
                        _errorDetail.value = null
                    }

                    // ⚠️ 目标未锁定（典型：点完录制还停在 PowerMeter 自己的页面上）：
                    // 此时 timestats 里一个图层都认领不到，`totalFrames` 恒为 0 ——
                    // 照常把 0 赋给读数，悬浮窗就变成一个**恒 0 的假读数**，用户无从分辨
                    // 「通道没通」还是「真的零帧」（2026-09-22 实测：用户在自己的帧率页上一看
                    // tab 永远是 0.0，据此判定「帧率根本测不了」）。
                    // 正确口径：没有目标 = 没有读数（NaN → UI 显示「—」），不产样本。
                    if (sessionPkg.isEmpty()) {
                        _fps.value = Double.NaN
                        diffPerLayer = emptyMap()
                    } else if (stats.totalFrames == 0L) {
                        // ⚠️ 第二道防线（2026-09-22）：目标已锁定但认领到的累计帧数为 0，
                        //    即 timestats 里没有任何图层含目标包名（认领失败 / 目标自 timestats
                        //    启用起一帧都没渲染过）。照常赋值就会造出「恒 0.0 的假读数」，
                        //    且把 0 帧样本写进 pending 污染整场会话。
                        //    口径：无读数（NaN → UI 显示「—」），不产样本，基线一并作废。
                        _fps.value = Double.NaN
                        diffPerLayer = emptyMap()
                    } else {
                        // 逐图层差分取 max（假尖峰 / 转场叠加的治理见 [updateFpsFromDiff]）；
                        // 返回 null（首拍建基线 / 图层全换）= 本拍无可差分，不产样本不更新读数
                        val fpsValue = updateFpsFromDiff(stats, statsAt)
                        if (fpsValue != null) {
                            // 起始帧率 = 本场第一个有效采样值（只记一次），供悬浮窗作为对比基准
                            if (_startFps.value.isNaN()) _startFps.value = fpsValue
                            // 电量四项（电压 / 电流 / 功率 / 电池温度）与帧率**同频**（每秒一次）：
                            // 要能和帧率逐秒对齐，才能回答"掉帧的那一刻是不是正好在发热 / 拉电流"。
                            // 数据源 = 功率侧同一条取数链（root 机器 sysfs 节点、Shizuku 机器
                            // BatteryManagerSource 实时电流，符号口径"正=充电"两页一致）——
                            // 不再自采 dumpsys battery/thermalservice（后者在 22081212C 无 ibat，
                            // 且符号未取反、单位靠启发式，见 FrameRateSource 的说明）
                            val power = RootPowerReader.read()
                            // 1s 样本的 CPU 字段 = 本拍窗口内快样的均值（250ms 快样聚合成
                            // 与 Kite 每秒行对齐的口径；250ms 密集明细另有 pendingCpu 落库）
                            val (cpuTotal, cpuCores, cpuMhzAvg) = aggregateCpuWindow(cpuFastFrom)
                            pending += FrameSample(
                                timeMillis = now,
                                fps = fpsValue,
                                frameSpaceMs = stats.frameSpaceMs,
                                missedFrames = (stats.missedFrames - diffMissed).coerceAtLeast(0).toInt(),
                                cpuMhz = cpuMhzAvg,
                                cpuUsagePct = cpuTotal,
                                cpuCoreUsagePct = cpuCores,
                                currentMa = power?.currentMa,
                                // RootPowerReader 返回 V/W，这里 ×1000 统一成毫口径落库
                                // （mV/mW/mA 与 Kite CSV 表头同源，App 显示时 ÷1000 换回）
                                voltageMv = power?.voltageV?.times(1_000.0),
                                powerMw = power?.powerW?.times(1_000.0),
                                tempBatteryC = power?.tempBatteryC,
                                tempVirtualC = virtualTempC,
                                gpuTempC = gpuTempC,
                                // 容量 % 来自功率链的 SOC（0 = 上报缺失，按缺测处理，不画成 0）
                                capacityPct = power?.socPct?.takeIf { it > 0 }?.toDouble(),
                                gpuLoadPct = gpuLoadPct,
                            )
                        }
                        // ⚠️ 基线推进放在每拍结尾：目标为空 / 认领为 0 的分支已作废基线，
                        //    这里照常推进成功拍的丢帧差分基线
                        diffMissed = stats.missedFrames
                    }
                }
                // ⚠️ diffAt 记**快照时刻**（statsAt，readTimestats 返回处）而非拍首 now：
                //    拍首到快照之间隔着本拍全部取数命令（GPU 占用 / timestats / 电量，
                //    慢速拍还多跑刷新率 + 温度），各拍耗时相差几百 ms —— 按拍首差分，窗口
                //    与真实呈现窗口错位，且随慢速轮询**周期性振荡**：慢速拍快照被推后 →
                //    下一拍差分窗被压短 → 120Hz 实测掉到 ~95（2026-09-25 用户实测
                //    「一堆 95Hz」）；反向拍窗口被拉长 → 差分超 refresh+1 被上限守卫拒收 →
                //    周期性丢样本（同日反馈「数据很少」）。快照时刻的抖动只剩命令耗时的
                //    波动（±几十 ms），差分窗口才与帧数增量真正对应。
                diffAt = statsAt
                sessionEndWall = now
                _elapsedMs.value = now - sessionStartWall

                // deadlineMs 是对象字段：用户可能在录制中途才挑时长（setLimit），
                // 每轮重新读而不是用循环开始时算好的常量
                if (deadlineMs > 0L && now >= deadlineMs) break

                // 本拍 CPU 聚合窗口推进到缓冲末尾（无论本拍是否产出样本：每个样本行只
                // 反映自己那一秒的 CPU 均值，不推进会把下一拍的均值窗口拉长一倍）
                cpuFastFrom = pendingCpu.size
                beat++
            }

            tick++
            // 补偿式等待（子拍口径）：按"距下一个子拍还差多少"来等，快样的名义间隔才稳；
            // 完整拍耗时超 250ms 时该子拍必然超时，只留 [MIN_SUB_TICK_MS] 防硬轮询 ——
            // 此时拍周期整体拉长，但帧率差分用的是真实快照时间戳，读数不受影响
            val spent = System.currentTimeMillis() - cycleStart
            delay((SUB_TICK_MS - spent).coerceAtLeast(MIN_SUB_TICK_MS))
        }

        // 正常结束（限时到点 / 通道失效）→ 收尾落库。
        // ⚠️ 若走到这里是因 job.cancel()（用户手动停止），本协程会在下一行之前被取消，
        //   落库由 stop() 里另起的 finishAndPersist 完成 —— 两条路径互斥，不会重复写。
        if (coroutineContext.isActive) {
            _recording.value = false
            job = null
            exitRecordingMode()
            // 会话字段就地快照（理由同 stop()：之后 startPreview 会接管共享字段）
            val pkg = sessionPkg
            val refreshHz = sessionRefreshHz
            val startWall = sessionStartWall
            val endWall = sessionEndWall
            finishAndPersist(pkg, refreshHz, startWall, endWall)
            // 限时到点 / 通道失效后回到预览态：悬浮窗还开着，帧率读数不该变成死的 0
            if (_previewing.value.not()) startPreview()
        }
    }

    /**
     * 把 [from] 起（上一完整拍之后）的 CPU 快样聚合成 1s 样本的 CPU 三件套：
     * (全核合计使用率, 逐核使用率, 逐核频率 MHz) —— 都取窗口内**有效快样的均值**
     * （缺测核 = null；频率缺核补 0.0，与 [FrameSample.cpuMhz] 的 0 补位约定一致，
     * 详情页画线时跳过）。Kite 每秒行、FPS 卡右轴 CPU(%) 吃这套均值；250ms 密集
     * 明细本身走 [pendingCpu] 落库，详情页 CPU 两卡直接画快样。
     */
    private fun aggregateCpuWindow(from: Int): Triple<Double?, List<Double?>, List<Double>> {
        if (from >= pendingCpu.size) return Triple(null, emptyList(), emptyList())
        val window = pendingCpu.subList(from, pendingCpu.size)
        val total = window.mapNotNull { it.totalPct }.takeIf { it.isNotEmpty() }?.average()
        val coreN = window.maxOfOrNull { it.coreUsagePct.size } ?: 0
        val corePct = (0 until coreN).map { i ->
            window.mapNotNull { it.coreUsagePct.getOrNull(i) }
                .takeIf { it.isNotEmpty() }?.average()
        }
        val mhzN = window.maxOfOrNull { it.mhzList.size } ?: 0
        val mhz = (0 until mhzN).map { i ->
            window.mapNotNull { it.mhzList.getOrNull(i) }
                .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        }
        return Triple(total, corePct, mhz)
    }

    /**
     * 预览循环：与 [loop] 同一套取数，但不写 [pending]、不落库、不受限时约束。
     *
     * ⚠️ 与录制循环的区别：**读不到数据也不退场**，一直按 1s 重试（口径同功率侧的取数循环：
     * 失败不缓存、每个周期重新判定）。预览只是"活的读数"，通道一旦恢复（Shizuku 绑定完成、
     * 授权通过）它自己就活过来；原先一读不到就 break，等于用户必须点「重试」才能自救。
     */
    private suspend fun previewLoop() {
        var tick = 0
        /** 连续读不到数据的轮数，仅用于「原因只判定一次」 */
        var readFailures = 0

        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            // 同录制循环：未锁定目标应用前每轮都试；不排除自身（任何界面都实时显示帧率）。
            // ⚠️ 直接写共享的 sessionPkg（不再是局部变量）：start() 开始录制时要从这里交棒
            if (tick % SLOW_POLL_EVERY == 0 || sessionPkg.isEmpty()) {
                FrameRateSource.readTopPackage()
                    .takeIf { it.isNotEmpty() }
                    ?.let { sessionPkg = it }
            }
            // stopPreview() cancel 后立即作废本拍：下方 readTimestats 也是阻塞调用，
            // 不能让已停止的预览拍把旧快照写回共享基线（与录制循环的交棒竞态，
            // 旧基线会让下一拍差分窗口错位、算出假尖峰）
            if (!coroutineContext.isActive) break
            // 刷新率慢速拍重读（读不到不清零）：预览的差分同样走物理上限守卫（updateFpsFromDiff），
            // 没有这个值守卫不生效 —— 悬浮 tab 在预览态也会冒 1000+（用户报的场景正是预览）
            if (tick % SLOW_POLL_EVERY == 0) {
                FrameRateSource.readRefreshRateHz().takeIf { it > 0 }?.let { sessionRefreshHz = it }
            }
            if (sessionPkg != diffPkg) {
                diffPkg = sessionPkg
                diffPerLayer = emptyMap()
                // 同录制循环：新目标首次锁定前清空跟踪表，否则认领到的是冻结累计值（见 resetTimestats）
                if (sessionPkg.isNotEmpty()) FrameRateSource.resetTimestats()
            }
            val stats = FrameRateSource.readTimestats(sessionPkg)
            // 同录制循环：快照落地时刻打点，差分窗口与真实呈现窗口对齐
            val statsAt = System.currentTimeMillis()
            if (!coroutineContext.isActive) break
            if (stats == null) {
                readFailures++
                _error.value = ERROR_NO_ACCESS
                _fps.value = Double.NaN // 同上：通道失效后读数必须是"无"，不是最后一帧的旧值
                diffPerLayer = emptyMap() // 基线作废，恢复后不会算出假尖峰
                if (readFailures == 1) _errorDetail.value = diagnoseNoAccess()
            } else {
                readFailures = 0
                if (_error.value == ERROR_NO_ACCESS) {
                    _error.value = null
                    _errorDetail.value = null
                }
                // 与录制循环同口径：目标未锁定时不产出 0.0 这个假读数（见 loop 的说明）。
                // 悬浮窗开着、人还在自己页面上时这是常态 —— 给「等待识别目标」提示，
                // 而不是一个看起来像真的 0 帧/秒。
                if (sessionPkg.isEmpty()) {
                    _fps.value = Double.NaN
                    diffPerLayer = emptyMap()
                    _error.value = ERROR_NO_TARGET_APP
                } else if (stats.totalFrames == 0L) {
                    // 同录制循环的第二道防线：目标已锁定但一个图层都没认领到（累计 0）
                    // → 无读数（「—」）、基线作废，禁 0.0 假读数。目标其实识别到了，
                    // 不置 ERROR_NO_TARGET_APP（那会在用户已切到被测应用时误报「等待识别」）。
                    _fps.value = Double.NaN
                    diffPerLayer = emptyMap()
                } else {
                    if (_error.value == ERROR_NO_TARGET_APP) _error.value = null
                    // 逐图层差分取 max（同 loop，见 [updateFpsFromDiff]）；预览不产样本
                    updateFpsFromDiff(stats, statsAt)
                }
            }
            diffAt = statsAt
            tick++
            // 补偿式等待（口径同录制循环）：预览拍同样要跑前台包名 / 刷新率 / timestats 几条
            // 命令，固定 delay(1s) 会让实际周期漂到 1.3~2s，悬浮 tab 的更新节奏跟着变慢
            val spent = System.currentTimeMillis() - now
            delay((SAMPLE_INTERVAL_MS - spent).coerceAtLeast(MIN_CYCLE_MS))
        }
        // ⚠️ 循环只会因 stopPreview() 取消而结束（读不到数据也不再 break），
        //    所以 `_previewing` / `previewJob` 的收尾由 stopPreview() 负责，此处不再重复清。
    }

    /**
     * 收尾：把已录到的样本算成一条会话落库，然后刷新历史列表。
     *
     * ⚠️ 会话四项（包名 / 刷新率 / 起止时刻）由调用方**快照传入**而非直读字段：
     * stop() 之后预览协程会接管 sessionPkg 等共享字段（见 stop() 的注释）。
     *
     * ⚠️ 必须在 IO 线程调用（内有 DB 写）。
     */
    private suspend fun finishAndPersist(
        pkg: String,
        refreshHz: Int,
        startWall: Long,
        endWall: Long,
    ) {
        if (pending.isEmpty()) return
        try {
            val dao = FrameDatabase.getInstance(appContext).frameDao()
            // ⚠️ 汇总前丢弃**高于刷新率**的不合理帧率（口径见 [FPS_CEILING_TOLERANCE]）：
            // 60Hz 锁帧被按 61Hz 展示、差分窗口边界尖峰（refresh×T+1 帧的物理上限）都算在内，
            // 超限值不参与 avg / min / max。
            // ⚠️ 只影响汇总数字，样本本体照常落库 —— 那一秒的功率 / 温度 / 丢帧数据仍要用于
            // 详情页与帧率的相关性分析，整条丢弃会在曲线上挖洞。
            // 刷新率未知（0，无法判定）或全部超限（刷新率上报异常）→ 退回全量：
            // 宁可保守也不要 NaN / 少样本，超限样本终归是少数。
            val fpsValues = pending
                .filter { refreshHz <= 0 || it.fps <= refreshHz + FPS_CEILING_TOLERANCE }
                .ifEmpty { pending }
                .map { it.fps }
            val session = FrameSession(
                startTime = startWall,
                endTime = endWall,
                packageName = pkg,
                appLabel = resolveAppLabel(pkg),
                refreshRateHz = refreshHz,
                sampleCount = pending.size,
                avgFps = fpsValues.average(),
                minFps = fpsValues.min(),
                maxFps = fpsValues.max(),
                lowFps1 = lowFps(fpsValues, 0.01),
                lowFps5 = lowFps(fpsValues, 0.05),
                avgFrameSpaceMs = pending.map { it.frameSpaceMs }.average(),
                jankCount = pending.sumOf { it.missedFrames },
            )
            val id = dao.insertSession(session)
            dao.insertSamples(pending.map { FrameSampleEntity.from(id, it) })
            if (pendingCpu.isNotEmpty()) {
                dao.insertCpuSamples(pendingCpu.map { it.copy(sessionId = id) })
            }
            Log.i(
                TAG,
                "已保存帧率会话 id=$id，样本 ${pending.size} 条（CPU 快样 ${pendingCpu.size} 条），pkg=$pkg",
            )
            pending.clear()
            pendingCpu.clear()
            FrameHistoryStore.refresh(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "保存帧率会话失败", e)
            _error.value = ERROR_SAVE_FAILED
        }
    }

    /** 应用显示名；解析不到（应用已卸载 / 包名未知）时回落为包名本身 */
    private fun resolveAppLabel(pkg: String): String {
        if (pkg.isEmpty()) return pkg
        return runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /**
     * 帧加权 X% Low 帧率 —— CapFrameX"1% Low / 5% Low"口径在本应用 1s 采样粒度下的等价实现。
     *
     * 经典定义基于**逐帧** frame time：按帧时间从差到好排序，取最差 X% 的帧求平均帧率。
     * 本应用的样本是 1s 窗口的聚合 fps，没有逐帧数据，等价换算：每个样本视作 fps_i 帧、
     * 每帧帧时间 1000/fps_i 秒；按 fps 升序（帧时间降序）从最差秒开始累计帧数，凑满总帧数的
     * X%（最后一个秒可能只计入一部分帧），Low = 累计帧数 ÷ 累计帧时间。
     *
     * ⚠️ 必须**调和加权**（帧数÷帧时间），不能对入选样本做算术平均 —— 低帧率秒的帧
     * "更少而更慢"，算术平均会把 1% Low 高估（30fps 一秒 vs 60fps 一秒，前者只有一半的帧）。
     * 1s 粒度与逐帧口径在整秒对齐时结果完全一致，粒度差异只体现在秒内抖动被抹平。
     *
     * ⚠️ fps ≤ 0 的样本不计入帧池：0 的语义是"该秒没有合成帧"（息屏 / 目标不在前台），
     * 不是"帧率掉到 0"（见 [FrameSample] 的约定），它们没有帧可参与"最差帧"统计。
     *
     * @param ratio 0.01 = 1% Low，0.05 = 5% Low
     * @return null = 没有正样本（全程无帧 / 空列表），无低帧率可言
     */
    private fun lowFps(values: List<Double>, ratio: Double): Double? {
        val positive = values.filter { it > 0.0 }
        if (positive.isEmpty()) return null
        val target = positive.sum() * ratio
        var used = 0.0          // 已计入"最差帧池"的帧数
        var seconds = 0.0       // 这些帧的呈现耗时合计（秒）
        for (fps in positive.sorted()) {
            if (used >= target) break
            val take = minOf(fps, target - used)
            used += take
            seconds += take / fps
        }
        if (used <= 0.0) return null
        return used / seconds
    }

}
