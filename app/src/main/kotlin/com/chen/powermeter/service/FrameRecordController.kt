package com.chen.powermeter.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import com.chen.powermeter.R
import com.chen.powermeter.data.FpsAlgorithm
import com.chen.powermeter.data.FrameHistoryStore
import com.chen.powermeter.data.FrameRateSource
import com.chen.powermeter.data.FrameSample
import com.chen.powermeter.data.RootPowerReader
import com.chen.powermeter.data.db.FrameSampleEntity
import com.chen.powermeter.data.db.FrameCpuSampleEntity
import com.chen.powermeter.data.db.FrameFpsSampleEntity
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.data.db.FrameDatabase
import com.chen.powermeter.util.Prefs
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
import kotlin.math.abs

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
 * 采集节奏（2026-09-25 重构为**子拍**制；2026-09-27 帧率差分 4Hz 化同日回退 1Hz）：
 * - **每个子拍**（[SUB_TICK_MS] = 250ms）：CPU 快样一条命令（/proc/stat 差分 + 逐核频率，
 *   [FrameRateSource.readCpuFastSample]）—— CPU 使用率/频率是快变量，1s 一点会把真实
 *   抖动与频率升降挡全部摊平（用户对照 Scene 工具箱实测反馈"明显不如"）；
 * - **每个完整拍**（每 [BEAT_SUBTICKS] 个子拍 ≈ 1s）：timestats 差分 + 1s 落库样本
 *   （fps = ΔF ÷ dt，快照实际间隔）。⚠️ 帧率差分曾在 2026-09-27 提到 4Hz 子拍（为抓
 *   250ms 级短谷、悬浮 tab 4Hz 响应），真机实测帧率读数仍异常，用户定案回退 1Hz 口径；
 *   frame_fps_samples 表保留、粒度同为 1Hz。电量四项（电压 / 电流 /
 *   功率 / 电池温度）走功率侧同一条取数链 [RootPowerReader.read]，与帧率逐秒对齐才能
 *   回答"掉帧的那一刻是不是正好在发热 / 拉电流"；
 * - **每 [SLOW_POLL_EVERY] 个完整拍**：前台应用包名、刷新率、虚拟温度（这几项变化慢、
 *   但每条命令都要起进程），三项**错峰**到相邻三拍、每拍至多多跑一条 —— 曾经同拍执行，
 *   单拍叠加 dumpsys activity + dumpsys display + 温感区遍历，游戏满载时该拍被拖到
 *   9~14s（真机 2026-09-26 xlsx 实测平均 5.45s/条）。中间周期沿用上一次的值。
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
     * 帧率汇总（avg / min）的合理上限容差（fps，2026-09-27 从 0.5 放宽到 1.0）。
     *
     * 1s 样本的帧数是单窗差分 ΔF ÷ dt（快照实际间隔 ≈ 1s），物理上限 = refresh×dt+1
     * ≈ refresh+1.0，即 fps 可到 refresh+1.0 —— 这是**窗口相位的合法值**
     * （120Hz 屏偶发 121），不是虚高。旧容差 0.5 把 (refresh+0.5, refresh+1] 段切掉：
     * 这些边界样本不参与 avg，均值被系统性压低（「Scene 120 本应用 115」的构成之一）；
     * 且 max 口径早已保留边界帧（121 如实展示，2026-09-27 用户口径），avg 剔 +0.5
     * 而 max 不剔，口径自相矛盾。
     *
     * 取 1.0 = 放宽到 1s 窗口的物理上限，与 max 一致：边界值保留，不再系统性低估。
     * 真正的虚高（计数污染 / 误认领行）在子拍守卫已被拦（updateFpsFromDiff 的物理上限
     * 守卫 + FrameRateSource 的行距守卫），到不了 1s 样本层；「60Hz 锁帧被按 61Hz 展示」
     * 的厂商语义顾虑同样覆盖：60Hz 的 1s 窗合法上限恰为 61.0，剔除条件是
     * > refresh+1.0，61.0 不会触发。
     *
     * ⚠️ **max / 1% / 5% Low 不受本容差影响**（max 全量取；Low 用子拍点源 lowSource），
     * 见 [finishAndPersist]。
     */
    private const val FPS_CEILING_TOLERANCE = 1.0

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

    /** 慢速采集（前台应用 / 刷新率 / 温度）的抽稀倍率（按**完整拍**计，5 拍 = 5s）。
     *  三项**错峰**分布在 0/1/2 拍执行（见 loop 内注释），任何一拍至多多跑一条慢命令 */
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
     * 连续多少轮读不到累计帧数才判定**通道真的不可用**并停止录制。
     * ⚠️ 2026-09-29 帧率读数下放到 250ms 子拍后，计数粒度从完整拍（≈1s 一轮）变为子拍
     * （4Hz）—— 6 → 16 维持 ≈4s 宽限期（Shizuku 绑定晚生效 / su 授权框刚点完这类
     * 暂时不可用要能自愈；口径见 loop 内 readFailures 分支）。
     *
     * ⚠️ 为什么不是首轮失败就停（2026-09-22 修）：功率侧的取数循环是「每个采样周期重试一次、
     * 失败不缓存」，所以 Shizuku 的 UserService 绑定晚生效一两秒、su 授权框刚点完这类**暂时**
     * 不可用都能自愈；而录制原本首轮 `readTimestats()` 返回 null 就 break —— 真机实测点了四次
     * 录制，每次都 **124ms 就中止**（两条 dumpsys 根本没跑完），用户看到的就是「点了没反应」。
     * 现在给它一个宽限期：这期间 tab 保持红色、读数显示「—」、面板直接给出**具体原因**，
     * 通道一恢复就继续录（且差分基线已作废，不会算出假尖峰）。
     */
    private const val MAX_READ_FAILURES = 16

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

    /**
     * 帧率子拍点缓冲（250ms 差分写入，与 [pending] 同一条落库路径）：FPS 曲线的 4Hz
     * 数据源 + MIN / 1%/5% Low 的取值源。
     * ⚠️ 内存里实体的 sessionId=0，finishAndPersist 拿到会话 id 后 copy 补上
     * （见 [FrameFpsSampleEntity]）。
     */
    private val pendingFps = ArrayList<FrameFpsSampleEntity>()
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

    /**
     * 上一完整拍的 presentToPresent 直方图（帧间隔「当秒」差分基线）；null = 无基线。
     * 1s 样本的 frameSpaceMs = 本拍与上一拍直方图的**差集**加权平均 —— 累计口径是一条
     * 衰减收敛曲线（开局加载的大间隔被稀释到 8.4-8.5），读不出当秒、也判不了
     * "FPS 低谷是真实掉帧（帧时间跳 9+）还是计时伪差（纹丝不动 8.0）"。
     * ⚠️ 只在目标切换（resetTimestats 清直方图）时作废；直方图中途被 statsd 清表的
     * 情形由 [p2pDeltaHistogram] 的负增量检查兜住。
     */
    private var prevP2p: Map<Int, Long>? = null
    /** 上一拍 timestats 快照时刻（readTimestats 返回处打点，**非拍首** —— 见采集循环 diffAt 赋值处） */
    private var diffAt = 0L

    // ── 帧率采样源（2026-09-29 加，FpsAlgorithm 三选一）──────────────
    //
    // 用户选择（Prefs）每拍重读；本字段是**实际生效**的算法 —— 选择的算法在本机不可用时
    // 自动回落 TIMESTATS（SF_LATENCY 已判死 / TASK_FPS 无 Shizuku v3 UserService），
    // 回落不改写用户设置（换机/ROM 更新后选择仍有效）。切换拍把所有差分基线一并作废
    // （跨算法的计数口径不可比，见 [loop] 的算法切换分支）。
    @Volatile
    private var effectiveAlgo = FpsAlgorithm.TIMESTATS

    /** TASK_FPS：最近一次系统推送 (fps, atMillis)；atMillis=0 = 从未收到推送 */
    @Volatile
    private var taskFpsLastPushAt = 0L

    @Volatile
    private var taskFpsLastFps = 0f

    /** TASK_FPS：本拍窗口内收到的新推送值（子拍轮询写入、完整拍聚合后清空） */
    private val taskFpsBeatVals = ArrayList<Float>()

    /** TASK_FPS：已成功注册的目标包名（与 sessionPkg 不同则下一拍重试注册） */
    @Volatile
    private var taskFpsRegisteredPkg = ""

    /**
     * TASK_FPS 推送的**陈旧判定**（ms）：距最近一次推送超过它 = 系统不再上报（典型 =
     * 被测任务静止无帧），本拍按「无帧周期」出 fps=0.0 样本 —— 语义与 timestats 路径的
     * 0 帧样本一致（息屏 / 目标不在前台）。系统推送节奏随 ROM 而异（变化时推或按窗口推），
     * 取 2.5s ≈ 2~3 个推送窗的余量；「恒定帧率不再推」的 ROM 若存在，此拍会被误判成 0 ——
     * 装机后若静止画面恢复时读数正常、恒帧率段却掉 0，把本值放大或改为「任务不变沿用旧值」。
     */
    private const val TASK_FPS_STALE_MS = 2_500L

    /**
     * 守卫拒收分支内**立即重读刷新率**的节拍：限 ≥1s 一次。「刷新率其实没变但守卫持续
     * 拒收」（计数污染 / 刷新率节点读失败等罕见情形）不至于每子拍都白跑一次 ~300ms 的
     * dumpsys display。动机与复验流程见 [updateFpsFromDiff] 的 LTPO 探测注释。
     */
    @Volatile
    private var lastGuardProbeAt = 0L

    /** 一次可用的子拍差分结果：实时读数 + 供 1s 样本合成的窗口增量（帧数与时长） */
    private class DiffResult(val fps: Double, val frames: Long, val dtSec: Double)

    /**
     * 逐图层差分出实时帧率并推进基线；无可差分（首拍 / 基线陈旧 / 图层全换）时返回 null。
     * 返回值带本窗的 Δ帧数与 dt —— 1s 样本的 fps 由本拍窗口内各子拍差分合成（ΣΔF ÷ Σdt）。
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
     * dt"算出假值。返回 null 的拍调用方不产样本点，但拍尾照常推进 diffMissed，
     * 下一拍的丢帧差分基线同步就位。
     *
     * ⚠️ **物理上限守卫**（随窗长缩放）：单表面 dt 秒窗口最多呈现 refresh×dt+1 帧
     * （窗口两端各粘一个 vsync 的边界效应）⇒ fps ≤ refresh + **1/dt**（1s 窗 = refresh+1、
     * 250ms 窗 = refresh+4；固定 +1 会把 4Hz 下约三成的合法边界拍整拍拒收）。差分超限
     * 即视为计数被污染，拒绝出数并打 warning 留痕（图层名 /
     * prev / cur / dt 全带上，真机 logcat 直接定位污染源）。**基线照常推进**：一次性跳变
     * （如 statsd 拉 atom 清零后又涨回、误认领行只出现一拍）下一拍自愈；持续性跳变则
     * 每拍留痕、读数保持最后一个可信值 —— 宁可「—」也不显示 1000+ 的假帧率。
     *
     * ⚠️ @param snapshotAt 必须是**本拍 timestats 快照的落地时刻**（readTimestats 返回处
     * 打点），不能用拍首时间：差分窗口必须与两次快照的实际间隔对齐，拍首与快照之间隔着
     * 本拍的全部取数命令，错位量随命令耗时不等而波动（事故记录见采集循环 diffAt 赋值处）。
     */
    private fun updateFpsFromDiff(stats: FrameRateSource.Timestats, snapshotAt: Long): DiffResult? {
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
        // 物理上限守卫（2026-09-29 抽出为 [checkFpsCeiling]，与 SF latency / TaskFps 两个
        // 新采样源共用同一套阈值与 LTPO 复验逻辑；"基线已照常推进"——见上，本函数在此
        // 调用之前已推进基线，拒收不影响差分窗口的连续性）
        if (sessionRefreshHz > 0 && bestFps > sessionRefreshHz + 1.0 / dtSec) {
            if (!checkFpsCeiling(
                    bestFps,
                    dtSec,
                    "layer=$bestLayer, Δ=${bestCur - bestPrev} (prev=$bestPrev → cur=$bestCur)",
                )
            ) {
                return null
            }
        }
        _fps.value = bestFps
        return DiffResult(bestFps, bestCur - bestPrev, dtSec)
    }

    /**
     * 物理上限守卫（**共享**，2026-09-29 抽出）：单表面 dt 秒窗口最多呈现 refresh×dt+1 帧
     * （窗口两端各粘一个 vsync 的边界效应）⇒ fps ≤ refresh + 1/dt（1s 窗 = refresh+1、
     * 250ms 窗 = refresh+4）。超上限只可能来自「刷新率被误报过低」（LTPO 档位切换）或
     * 「计数污染」—— 立即重读刷新率复验（≥1s 限频），新上限放行、仍超限则拒收。
     *
     * 三个采样源共用（timestats 差分 / SF latency 时间戳差分 / 系统 TaskFps 推送）：
     * 口径必须一致，谁也不能把计数污染放进样本。@param detail 留痕附加信息（图层名 / Δ / 来源）。
     * @return true = 放行（含 LTPO 复验放行）；false = 拒收（调用方本拍不出数）
     */
    private fun checkFpsCeiling(fps: Double, dtSec: Double, detail: String): Boolean {
        if (dtSec <= 0.0) return true // 无有效窗口（零帧拍沿用墙钟 dt 的场景外），不在本守卫职责内
        if (sessionRefreshHz <= 0 || fps <= sessionRefreshHz + 1.0 / dtSec) return true
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastGuardProbeAt >= 1_000L) {
            lastGuardProbeAt = nowMs
            FrameRateSource.readRefreshRateHz().takeIf { it > 0 }?.let { sessionRefreshHz = it }
            if (fps <= sessionRefreshHz + 1.0 / dtSec) {
                Log.i(
                    TAG,
                    "LTPO 档位切换探测命中：刷新率重读为 ${sessionRefreshHz}Hz，" +
                        "本次差分放行：raw=${"%.1f".format(fps)} fps, dt=${"%.2f".format(dtSec)}s, $detail",
                )
                return true
            }
        }
        Log.w(
            TAG,
            "帧率差分超刷新率上限，本拍拒绝出数：" +
                "raw=${"%.1f".format(fps)} fps vs ceiling=${sessionRefreshHz + 1.0 / dtSec}, " +
                "dt=${"%.2f".format(dtSec)}s, $detail",
        )
        return false
    }

    /**
     * 相邻两拍 presentToPresent 直方图的**差集** = 「当秒」帧间隔分布（桶 ms → 帧数）。
     *
     * 口径意义：累计直方图的平均是一条衰减收敛曲线（开局加载的大间隔被后续稀释到
     * ~8.4-8.5），既读不出当秒、也判不了 FPS 低谷的真假 —— 差集口径下，真实掉帧的秒
     * （16ms 桶混入）帧时间跳到 9+，计时伪差的秒纹丝不动 8.0。
     *
     * ⚠️ 差集**整张分布**下传（[FrameSample.p2pHist] → frame.db v5 `p2pHist` 列）：
     * 详情页的逐帧卡顿判定（PerfDog 式 83/125ms **单帧**门槛）、卡顿率、稳帧指数都要
     * 逐帧分布 —— 只落加权均值的话，单帧尖刺被 1s 均值摊薄：2026-09-28 王者实测一整
     * 场 1s 均值 max=13.31ms（83ms 门槛的 16%），83/125ms 门槛的 jank 一场判不出一帧、
     * Jank 卡三档全零（用户报障根因）。
     *
     * @return null = 无差分基线（拍首）/ 直方图中途被清（计数负增长：statsd 拉 atom、
     *   -clear），调用方按缺测处理；空 map = 本秒桶增量全 0（无合成帧）
     */
    private fun p2pDeltaHistogram(cur: Map<Int, Long>, prev: Map<Int, Long>?): Map<Int, Long>? {
        if (prev == null) return null
        val delta = HashMap<Int, Long>()
        for (ms in (cur.keys + prev.keys)) {
            val d = (cur[ms] ?: 0L) - (prev[ms] ?: 0L)
            if (d < 0L) return null
            if (d > 0L) delta[ms] = d
        }
        return delta
    }

    /** 差集分布的加权平均 = 「当秒」平均帧间隔 ms；null/空 → 0.0（缺测，沿用旧口径） */
    private fun p2pDeltaWeightedAvg(delta: Map<Int, Long>?): Double {
        if (delta.isNullOrEmpty()) return 0.0
        var weighted = 0.0
        var total = 0L
        for ((ms, cnt) in delta) {
            weighted += ms.toDouble() * cnt
            total += cnt
        }
        return if (total > 0L) weighted / total else 0.0
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
        pendingFps.clear()
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
        // 共用的交棒字段，stop() 返回后 UI 会立刻 startPreview()，新预览协程会接管
        // sessionPkg —— 异步落库若晚于它，落库的包名就被预览锁到的新目标污染了
        // （2026-09-25 引入交棒时一并修复；预览用局部变量的旧实现没有这条竞态）
        val pkg = sessionPkg
        val refreshHz = sessionRefreshHz
        val startWall = sessionStartWall
        val endWall = sessionEndWall
        // 采样源就地快照（同上：之后预览协程可能切换 effectiveAlgo）
        val algoAtStop = effectiveAlgo
        scope.launch { finishAndPersist(pkg, refreshHz, startWall, endWall, algoAtStop) }
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

    /**
     * 采样源解析与切换（**每子拍**调用，2026-09-29 读数 4Hz 化时从完整拍下放）：
     * 用户选择（Prefs）每拍重读，本机不可用自动回落 TIMESTATS（SF_LATENCY 已判死 /
     * TASK_FPS 无 Shizuku v3 UserService）。回落**不改写** Prefs：换机 / ROM 更新后
     * 用户的选择仍然有效。切换时跨算法的帧数口径不可比（累计差分 vs 原始时间戳窗 vs
     * 系统推送）—— 全部差分基线作废，新算法首拍只建基线（口径同目标切换）。
     */
    private fun resolveAndSwitchAlgo() {
        val wantedAlgo = FpsAlgorithm.fromKey(Prefs.getFpsAlgorithm(appContext))
        val resolvedAlgo = when (wantedAlgo) {
            FpsAlgorithm.SF_LATENCY ->
                if (FrameRateSource.isLatencyDead()) FpsAlgorithm.TIMESTATS else wantedAlgo
            FpsAlgorithm.TASK_FPS ->
                if (FrameRateSource.isTaskFpsSupported()) wantedAlgo else FpsAlgorithm.TIMESTATS
            FpsAlgorithm.TIMESTATS -> wantedAlgo
        }
        if (resolvedAlgo == effectiveAlgo) return
        effectiveAlgo = resolvedAlgo
        diffPerLayer = emptyMap()
        diffMissed = 0L
        prevP2p = null
        diffAt = 0L
        FrameRateSource.resetLatency()
        taskFpsLastPushAt = 0L
        taskFpsLastFps = 0f
        taskFpsBeatVals.clear()
        if (resolvedAlgo != FpsAlgorithm.TASK_FPS && taskFpsRegisteredPkg.isNotEmpty()) {
            FrameRateSource.taskFpsUnregister()
            taskFpsRegisteredPkg = ""
        }
        Log.i(TAG, "帧率采样源生效切换：effective=$resolvedAlgo（选择=$wantedAlgo）")
    }

    /**
     * 目标变化检测（**每子拍**调用）：diff 基线与采样源自带状态随目标作废。
     * timestats 的 -clear 只在 timestats 生效时执行（清跟踪表让被测图层重新进表，
     * 根因与实证见 FrameRateSource.resetTimestats；latency/taskfps 不该白挨这一刀
     * 侵入 —— clear 会干扰同样读 timestats 的厂商组件）。
     * ⚠️ 预览交棒场景：sessionPkg == diffPkg（用户点录制时通常停在当前页面）
     * → 不触发，第一拍直接续差分，读数无缝衔接。
     */
    private fun checkTargetChange() {
        if (sessionPkg == diffPkg) return
        diffPkg = sessionPkg
        diffPerLayer = emptyMap()
        diffMissed = 0L
        // resetTimestats 会清直方图 → 帧间隔差分基线一并作废
        prevP2p = null
        if (sessionPkg.isNotEmpty() && effectiveAlgo == FpsAlgorithm.TIMESTATS) {
            FrameRateSource.resetTimestats()
        }
        // 采样源自带状态随目标作废：latency 的时间戳基线与选层缓存；
        // taskfps 的注册在 TASK_FPS 分支按 taskFpsRegisteredPkg != sessionPkg 自动重注册
        FrameRateSource.resetLatency()
    }

    private suspend fun loop(limitMinutes: Int?) {
        /** 虚拟温度（CPU 代表温感区）：变化慢，按 5s 抽稀，中间周期沿用上一次的值 */
        var virtualTempC: Double? = null
        /** GPU 温感区温度：同 5s 抽稀；机型无 GPU 温感区时恒 null（曲线断线） */
        var gpuTempC: Double? = null
        /** GPU 占用率 %：快变量，完整拍直读（gpubusy 的 busy/total 比值，见
         *  FrameRateSource.readGpuLoadFreq）；候选全不可读的机器恒 null */
        var gpuLoadPct: Double? = null
        /** GPU 频率 MHz：快变量，与占用率**同一条命令/直读**取回（2026-09-29 加）；
         *  本机 kgsl 被拦恒 null（预期），节点可读机型自动出数 */
        var gpuFreqMhz: Double? = null
        var tick = 0
        var beat = 0
        /** 本拍 CPU 聚合窗口在 [pendingCpu] 里的起点（上一完整拍结束位置） */
        var cpuFastFrom = 0
        /** 连续读不到累计帧数的完整拍轮数；成功一轮即归零（宽限期见 [MAX_READ_FAILURES]） */
        var readFailures = 0
        // ── 1s 样本的帧率合成累计器（子拍差分写入、完整拍清零）：ΣΔF ÷ Σdt 口径见循环内注释
        var accFrames = 0L
        var accDtSec = 0.0
        /** SF_LATENCY：本拍四窗的真实逐帧 present 间隔合计（1s 样本的 p2pHist 数据源） */
        val accHist = HashMap<Int, Long>()
        /** TIMESTATS：本拍最后一个有效快照（1s 样本的 p2p/丢帧差分基线推进用） */
        var lastBeatStats: FrameRateSource.Timestats? = null

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

            // ── 每子拍帧率读数（2026-09-29 二次重构：**读数 4Hz、样本仍 1Hz**）──────
            //
            // 「帧率显示很慢、滑动时和 Scene 完全对不上，稳定后才一致」（用户实测）的根因
            // = 实时读数走 1s 整拍窗口：滑动那 0.6s 的 120fps 被摊进含静止段的整秒窗
            // （显示 ~72），而 Scene 的跟随窗短得多 —— 读数滞后半拍、滑动期全程偏低。
            // 修法 = 帧率读数下放到 250ms 子拍（4Hz 发布到悬浮 tab），滑动期每个子窗都
            // 贴着真实帧率；**落库样本仍是 1s**（ΣΔF÷Σdt 把 4 个子窗合成一秒，逐位等于
            // 旧整拍差分 —— 2026-09-27「差分回退 1Hz」定案的是样本粒度，不是读数粒度）。
            // 采样源解析与目标变化检测同步下放：切算法 / 换目标即时生效、基线作废时序正确。
            resolveAndSwitchAlgo()
            checkTargetChange()

            when (effectiveAlgo) {
                FpsAlgorithm.TIMESTATS -> {
                    val stats = FrameRateSource.readTimestats(sessionPkg)
                    // 快照落地时刻打差分窗，而非子拍首（拍首错位的历史教训见
                    // updateFpsFromDiff 注释 —— 子拍下同样成立）
                    val statsAt = System.currentTimeMillis()
                    if (!coroutineContext.isActive) break
                    if (stats == null) {
                        // 读不到累计帧数：宽限期计数（子拍粒度，MAX_READ_FAILURES=16 ≈ 4s）
                        readFailures++
                        _fps.value = Double.NaN
                        diffPerLayer = emptyMap()
                        if (readFailures == 1) _errorDetail.value = diagnoseNoAccess()
                        if (readFailures >= MAX_READ_FAILURES) {
                            _error.value = ERROR_NO_ACCESS
                            if (pending.isEmpty()) toastRecordingNotStarted()
                            break
                        }
                    } else {
                        readFailures = 0
                        if (_error.value == ERROR_NO_ACCESS) {
                            _error.value = null
                            _errorDetail.value = null
                        }
                        if (sessionPkg.isEmpty()) {
                            // 目标未锁定：无读数（「—」），禁 0.0 假读数（2026-09-22 定案）
                            _fps.value = Double.NaN
                            diffPerLayer = emptyMap()
                        } else if (stats.totalFrames == 0L) {
                            // 目标已锁定但没认领到任何图层：同上，无读数、基线作废
                            _fps.value = Double.NaN
                            diffPerLayer = emptyMap()
                        } else {
                            if (_error.value == ERROR_NO_TARGET_APP) _error.value = null
                            // 逐图层差分取 max 并发布（悬浮 tab 4Hz 响应）
                            val diff = updateFpsFromDiff(stats, statsAt)
                            if (diff != null) {
                                if (_startFps.value.isNaN()) _startFps.value = diff.fps
                                accFrames += diff.frames
                                accDtSec += diff.dtSec
                            }
                        }
                        lastBeatStats = stats
                    }
                    diffAt = statsAt
                }

                FpsAlgorithm.SF_LATENCY -> {
                    val ls = FrameRateSource.readLatencySample(sessionPkg)
                    if (!coroutineContext.isActive) break
                    if (sessionPkg.isEmpty()) {
                        _fps.value = Double.NaN
                    } else if (ls == null) {
                        // 首拍建时间戳基线 / 图层待渲染：不出数；通道级失效走宽限链
                        if (!ShizukuHelper.serviceBound.value &&
                            RootPowerReader.accessMode == RootPowerReader.AccessMode.NONE
                        ) {
                            readFailures++
                            if (readFailures == 1) _errorDetail.value = diagnoseNoAccess()
                            if (readFailures >= MAX_READ_FAILURES) {
                                _error.value = ERROR_NO_ACCESS
                                if (pending.isEmpty()) toastRecordingNotStarted()
                                break
                            }
                        }
                    } else if (ls.frames == 0L) {
                        // 本窗无新帧：读数 0.0（「本周期无合成帧」语义）
                        _fps.value = 0.0
                        if (_startFps.value.isNaN()) _startFps.value = 0.0
                        val wallDt = if (diffAt > 0L) (System.currentTimeMillis() - diffAt) / 1000.0 else 0.0
                        if (wallDt > 0.0) accDtSec += wallDt
                    } else {
                        val dtSec = if (ls.fps > 0) ls.frames / ls.fps else 0.0
                        if (!checkFpsCeiling(
                                ls.fps,
                                dtSec,
                                "latency layer=${ls.layerName}" + if (ls.overflow) " [FIFO溢出降级]" else "",
                            )
                        ) {
                            // 守卫拒收：读数保持上一窗（时间戳基线已推进，下一窗自愈）
                        } else {
                            if (_error.value == ERROR_NO_TARGET_APP) _error.value = null
                            _fps.value = ls.fps
                            if (_startFps.value.isNaN()) _startFps.value = ls.fps
                            accFrames += ls.frames
                            accDtSec += dtSec
                            // 真实逐帧 present 间隔按窗累加，1s 样本的 p2pHist = 四窗合计
                            for ((ms, cnt) in ls.p2pHistogram) accHist[ms] = (accHist[ms] ?: 0L) + cnt
                        }
                    }
                    diffAt = System.currentTimeMillis()
                }

                FpsAlgorithm.TASK_FPS -> {
                    // 注册管理：目标变化 / 尚未注册（同包名在 UserService 侧短路幂等）
                    if (sessionPkg.isNotEmpty() && taskFpsRegisteredPkg != sessionPkg) {
                        val (ok, _, err) = FrameRateSource.taskFpsRegister(sessionPkg)
                        if (ok) taskFpsRegisteredPkg = sessionPkg
                        else Log.i(TAG, "TaskFps 注册未就绪：$err（下一子拍重试）")
                    }
                    // 系统推送轮询：新推送**即时发布**（tab 跟随系统节奏，不再等完整拍）
                    FrameRateSource.readTaskFpsSample()?.let { (fps, at) ->
                        if (at > taskFpsLastPushAt) {
                            taskFpsLastPushAt = at
                            taskFpsLastFps = fps
                            taskFpsBeatVals.add(fps)
                            _fps.value = fps.toDouble()
                        }
                    }
                }
            }

            val isBeat = tick % BEAT_SUBTICKS == 0
            if (isBeat) {
                val now = cycleStart

                // 前台应用：**未锁定前每轮都试**。不排除自身包名（2026-09-22 用户口径：
                // 任何界面都实时显示帧率）—— 停在本应用页面上时测的就是自己的渲染帧率。
                // ⚠️ start() 已从预览交棒 sessionPkg（通常非空），所以首拍只有 beat==0 会刷新
                if (beat % SLOW_POLL_EVERY == 0 || sessionPkg.isEmpty()) {
                    FrameRateSource.readTopPackage()
                        .takeIf { it.isNotEmpty() }
                        ?.let { sessionPkg = it }
                    // readTopPackage 也是阻塞调用：cancel 检查同下
                    if (!coroutineContext.isActive) break
                }
                // GPU 占用率 + 频率：快变量，完整拍一条命令/一次直读同时取回
                FrameRateSource.readGpuLoadFreq().let { (load, freq) ->
                    load?.let { gpuLoadPct = it }
                    freq?.let { gpuFreqMhz = it }
                }
                // 慢速项（前台应用 / 刷新率 / 温度）变化慢、每条都要起进程：按 5 拍抽稀之外，
                // 三项**错峰**到相邻三拍（0=前台应用、1=刷新率、2=温度），任何一拍至多多跑一条。
                // ⚠️ 曾经三项同拍执行：单拍叠加 dumpsys activity + dumpsys display + 温感区遍历，
                //    游戏满载时该拍被拖到 9~14s；下一拍差分窗口超过陈旧上限（STALE_DIFF_WINDOW_SEC）
                //    被作废不出样本 —— 真机 xlsx 实测 132 条样本跨 714s、平均 5.45s/条、间隔双峰
                //    1~4s / 9~14s（2026-09-26 定案，2026-09-27 修）。
                // ⚠️ 刷新率**每轮慢速拍都重读**（读不到不清零，2026-09-25 从「只在为 0 时读」改）：
                //    智能刷新 / LTPO 会在 60↔120 间切换，而差分的物理上限守卫（updateFpsFromDiff）
                //    用的是「当前」刷新率 —— 锁死首拍值，切换后要么误杀合法读数、要么放行不了虚高。
                if (beat % SLOW_POLL_EVERY == 1) {
                    FrameRateSource.readRefreshRateHz().takeIf { it > 0 }?.let { sessionRefreshHz = it }
                }
                if (beat % SLOW_POLL_EVERY == 2) {
                    // CPU / GPU 温度同一条温感区命令一次取回（单进程 awk 读完全部温感区）
                    FrameRateSource.readCpuGpuTempsC().let { (tCpu, tGpu) ->
                        tCpu?.let { virtualTempC = it }
                        tGpu?.let { gpuTempC = it }
                    }
                }

                // 提示态（不是错误）：还停在 PowerMeter 自己页面上时必然出现，切到被测应用即消失
                if (sessionPkg.isEmpty()) {
                    if (beat >= NO_TARGET_HINT_TICKS) _error.value = ERROR_NO_TARGET_APP
                } else if (_error.value == ERROR_NO_TARGET_APP) {
                    _error.value = null
                }

                // ── 1s 落库样本组装：fps = ΣΔF÷Σdt（四个子窗合成，逐位 = 旧整拍差分）；
                // 帧间隔 / 丢帧从本拍最后一个有效快照差分（口径同旧实现）。样本口径不变。
                var fps1s: Double? = null
                var p2pDelta: Map<Int, Long>? = null
                var missedDelta = 0
                var frameSpaceDerived: Double? = null
                when (effectiveAlgo) {
                    FpsAlgorithm.TIMESTATS -> {
                        val stats = lastBeatStats
                        if (stats != null && stats.totalFrames > 0L && sessionPkg.isNotEmpty()) {
                            if (accDtSec > 0.0) fps1s = accFrames / accDtSec
                            p2pDelta = p2pDeltaHistogram(stats.p2pHistogram, prevP2p)
                            missedDelta = (stats.missedFrames - diffMissed).coerceAtLeast(0).toInt()
                        }
                    }
                    FpsAlgorithm.SF_LATENCY -> {
                        if (sessionPkg.isNotEmpty() && accDtSec > 0.0) {
                            fps1s = accFrames / accDtSec
                            // 本拍四窗的真实逐帧间隔合计（空 = 本拍无帧）
                            p2pDelta = accHist.toMap()
                        }
                    }
                    FpsAlgorithm.TASK_FPS -> {
                        val fresh = taskFpsBeatVals.toList()
                        taskFpsBeatVals.clear()
                        val beatAt = System.currentTimeMillis()
                        val pushAge = if (taskFpsLastPushAt > 0) beatAt - taskFpsLastPushAt else -1L
                        val beatFps: Double? = when {
                            fresh.isNotEmpty() ->
                                // 本拍有新推送：取均值（推送节奏 ~1s，一般 1~2 条）
                                fresh.map { it.toDouble() }.average()
                            pushAge in 0L..TASK_FPS_STALE_MS ->
                                // 本拍无新推送但推送仍新鲜：沿用最近值（「变化才推」型 ROM
                                // 在恒定帧率段不会重复推送，沿用 = 真实读数）
                                taskFpsLastFps.toDouble()
                            pushAge > TASK_FPS_STALE_MS ->
                                // 推送陈旧（>2.5s）：任务静止无帧 —— 与 timestats 的
                                // Δ=0 拍同语义，出 fps=0.0 样本
                                0.0
                            else -> null // 从未收到推送（注册未生效 / 任务未渲染）：不产样本
                        }
                        if (beatFps != null && sessionPkg.isNotEmpty()) {
                            val wallDt = if (diffAt > 0L) (beatAt - diffAt) / 1000.0 else 1.0
                            if (beatFps <= 0.0 || checkFpsCeiling(beatFps, wallDt, "taskfps")) {
                                fps1s = beatFps
                                // 推导口径：该 fps 下的平均帧间隔（系统不逐帧推时间戳）
                                frameSpaceDerived = if (beatFps > 0.0) 1000.0 / beatFps else 0.0
                                if (wallDt > 0.0) accDtSec += wallDt
                            }
                        }
                    }
                }
                // 帧率采样点落库缓冲（1Hz 粒度）：无读数的拍留 null 行（曲线断线、
                // 统计跳过），行网格均匀，索引制图的时间轴才不失真；通道失效 /
                // 目标未锁定的拍不留行（与旧口径一致）
                if (readFailures == 0 && sessionPkg.isNotEmpty()) {
                    pendingFps.add(FrameFpsSampleEntity.from(now, fps1s))
                }

                // 1s 落库样本组装：fps1s 只喂了 pendingFps 还不够 —— 主样本（含电量 / CPU /
                // 帧间隔 / 丢帧）必须入 [pending]，否则 finishAndPersist 开头 isEmpty()
                // 直接 return，会话行都不写、列表永不出现新场次（2026-09-29 子拍化重构
                // 时本块整块丢失过：pending 恒空且无任何报错，装机后两场录制全没落库）。
                if (fps1s != null && sessionPkg.isNotEmpty()) {
                    // 电量四项（电压 / 电流 / 功率 / 电池温度）与帧率**同频**（每秒一次）：
                    // 要能和帧率逐秒对齐，才能回答"掉帧的那一刻是不是正好在发热 / 拉电流"。
                    // 数据源 = 功率侧同一条取数链（RootPowerReader），符号口径"正=充电"
                    val power = RootPowerReader.read()
                    // 帧间隔口径：timestats / sf_latency 用本拍直方图差集的加权平均（"当秒"
                    // 帧时间；无基线 / 直方图中途被清 → 0.0 断线，同旧口径）；task_fps 无
                    // 逐帧真值，用 1000/fps 推导值
                    val frameSpace = when (effectiveAlgo) {
                        FpsAlgorithm.TASK_FPS -> frameSpaceDerived ?: 0.0
                        else -> p2pDeltaWeightedAvg(p2pDelta)
                    }
                    // 1s 样本的 CPU 字段 = 本拍窗口内快样的均值（250ms 快样聚合成与 Kite
                    // 每秒行对齐的口径；250ms 密集明细另有 pendingCpu 落库）
                    val (cpuTotal, cpuCores, cpuMhzAvg) = aggregateCpuWindow(cpuFastFrom)
                    pending += FrameSample(
                        timeMillis = now,
                        fps = fps1s,
                        frameSpaceMs = frameSpace,
                        // 丢帧增量只有 timestats 口径有；sf_latency / task_fps 无此数据源
                        // 落 0 —— jank 判定已改 p2pHist 逐帧口径，本列只是 timestats 场次
                        // 的兼容数据
                        missedFrames = missedDelta,
                        cpuMhz = cpuMhzAvg,
                        cpuUsagePct = cpuTotal,
                        cpuCoreUsagePct = cpuCores,
                        // 绝对值口径（库与卡片不出现负号，v6 迁移同口径）：取数链本身
                        // "正=充电"，就地取 abs
                        currentMa = power?.currentMa?.let(::abs),
                        // RootPowerReader 返回 V/W，这里 ×1000 统一成毫口径落库
                        // （mV/mW/mA 与 Kite CSV 表头同源，App 显示时 ÷1000 换回）
                        voltageMv = power?.voltageV?.times(1_000.0),
                        powerMw = power?.powerW?.let(::abs)?.times(1_000.0),
                        tempBatteryC = power?.tempBatteryC,
                        tempVirtualC = virtualTempC,
                        gpuTempC = gpuTempC,
                        // 容量 % 来自功率链的 SOC（0 = 上报缺失，按缺测处理，不画成 0）
                        capacityPct = power?.socPct?.takeIf { it > 0 }?.toDouble(),
                        gpuLoadPct = gpuLoadPct,
                        gpuFreqMhz = gpuFreqMhz,
                        // 本秒帧间隔分布（详情页逐帧 jank 判定的数据源）；null = 缺测 → 空表
                        p2pHist = p2pDelta.orEmpty(),
                    )
                }

                accFrames = 0L
                accDtSec = 0.0
                accHist.clear()
                // 丢帧/帧间隔差分基线按完整拍推进（本拍最后一个有效快照）：
                // 仅 timestats 需要（latency 的间隔是逐帧真值、taskfps 是推导值，无基线）
                lastBeatStats?.let {
                    diffMissed = it.missedFrames
                    prevP2p = it.p2pHistogram
                }
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
            // 子拍全部取数（CPU 快样 + timestats）耗时超 250ms 时该子拍必然超时，只留
            // [MIN_SUB_TICK_MS] 防硬轮询 —— 此时拍周期整体拉长，但帧率差分用的是真实
            // 快照时间戳，读数不受影响
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
            val algoAtEnd = effectiveAlgo
            finishAndPersist(pkg, refreshHz, startWall, endWall, algoAtEnd)
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
     * 预览循环：与 [loop] 同一套取数与**同一套 250ms 子拍节奏**（帧率读数 4Hz 发布），
     * 但不写 [pending]、不落库、不受限时约束。
     *
     * ⚠️ 与录制循环的区别：**读不到数据也不退场**，持续按子拍重试（口径同功率侧的取数循环：
     * 失败不缓存、每个周期重新判定）。预览只是"活的读数"，通道一旦恢复（Shizuku 绑定完成、
     * 授权通过）它自己就活过来；原先一读不到就 break，等于用户必须点「重试」才能自救。
     */
    private suspend fun previewLoop() {
        var tick = 0
        /** 连续读不到数据的轮数（子拍粒度，MAX_READ_FAILURES=16 ≈ 4s），仅用于「原因只判定一次」 */
        var readFailures = 0
        // 慢速项抽稀按子拍计：SLOW_POLL_EVERY*BEAT_SUBTICKS = 20 子拍 = 5s（同录制循环节奏）
        val slowEvery = SLOW_POLL_EVERY * BEAT_SUBTICKS

        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            // 同录制循环：未锁定目标应用前每轮都试；不排除自身（任何界面都实时显示帧率）。
            // ⚠️ 直接写共享的 sessionPkg：start() 开始录制时要从这里交棒
            if (tick % slowEvery == 0 || sessionPkg.isEmpty()) {
                FrameRateSource.readTopPackage()
                    .takeIf { it.isNotEmpty() }
                    ?.let { sessionPkg = it }
            }
            // stopPreview() cancel 后立即作废本拍：取数是阻塞调用，不能让已停止的预览拍
            // 把旧快照写回共享基线（交棒竞态，旧基线会让下一拍差分窗错位、算出假尖峰）
            if (!coroutineContext.isActive) break
            // 刷新率慢速拍重读（错峰到 1s 后 = tick%20==4；读不到不清零）：守卫才能跟 LTPO 切换
            if (tick % slowEvery == BEAT_SUBTICKS) {
                FrameRateSource.readRefreshRateHz().takeIf { it > 0 }?.let { sessionRefreshHz = it }
            }
            // 采样源解析 + 目标变化（与录制 loop 共用同一套下放逻辑）
            resolveAndSwitchAlgo()
            checkTargetChange()

            when (effectiveAlgo) {
                FpsAlgorithm.TIMESTATS -> {
                    val stats = FrameRateSource.readTimestats(sessionPkg)
                    // 快照落地时刻打点，差分窗口与真实呈现窗口对齐（同录制循环）
                    val statsAt = System.currentTimeMillis()
                    if (!coroutineContext.isActive) break
                    if (stats == null) {
                        readFailures++
                        _error.value = ERROR_NO_ACCESS
                        _fps.value = Double.NaN // 通道失效后读数必须是"无"，不是最后一帧的旧值
                        diffPerLayer = emptyMap()
                        if (readFailures == 1) _errorDetail.value = diagnoseNoAccess()
                    } else {
                        readFailures = 0
                        if (_error.value == ERROR_NO_ACCESS) {
                            _error.value = null
                            _errorDetail.value = null
                        }
                        if (sessionPkg.isEmpty()) {
                            _fps.value = Double.NaN
                            diffPerLayer = emptyMap()
                            _error.value = ERROR_NO_TARGET_APP
                        } else if (stats.totalFrames == 0L) {
                            _fps.value = Double.NaN
                            diffPerLayer = emptyMap()
                        } else {
                            if (_error.value == ERROR_NO_TARGET_APP) _error.value = null
                            // 逐图层差分取 max 并发布（悬浮 tab 4Hz 响应）；预览不产样本
                            updateFpsFromDiff(stats, statsAt)
                        }
                    }
                    diffAt = statsAt
                }

                FpsAlgorithm.SF_LATENCY -> {
                    val ls = FrameRateSource.readLatencySample(sessionPkg)
                    if (!coroutineContext.isActive) break
                    if (sessionPkg.isEmpty()) {
                        _fps.value = Double.NaN
                        _error.value = ERROR_NO_TARGET_APP
                    } else if (ls == null) {
                        // 首拍建基线 / 图层待渲染：读数「—」；通道级失效走宽限链
                        _fps.value = Double.NaN
                        if (!ShizukuHelper.serviceBound.value &&
                            RootPowerReader.accessMode == RootPowerReader.AccessMode.NONE
                        ) {
                            readFailures++
                            _error.value = ERROR_NO_ACCESS
                            if (readFailures == 1) _errorDetail.value = diagnoseNoAccess()
                        }
                    } else if (ls.frames == 0L) {
                        _fps.value = 0.0
                    } else {
                        val dtSec = if (ls.fps > 0) ls.frames / ls.fps else 0.0
                        if (checkFpsCeiling(ls.fps, dtSec, "latency layer=${ls.layerName}")) {
                            if (_error.value == ERROR_NO_TARGET_APP || _error.value == ERROR_NO_ACCESS) {
                                _error.value = null
                                _errorDetail.value = null
                            }
                            _fps.value = ls.fps
                        }
                    }
                    diffAt = System.currentTimeMillis()
                }

                FpsAlgorithm.TASK_FPS -> {
                    if (sessionPkg.isEmpty()) {
                        _fps.value = Double.NaN
                        _error.value = ERROR_NO_TARGET_APP
                    } else {
                        if (taskFpsRegisteredPkg != sessionPkg) {
                            val (ok, _, err) = FrameRateSource.taskFpsRegister(sessionPkg)
                            if (ok) taskFpsRegisteredPkg = sessionPkg
                            else Log.i(TAG, "预览：TaskFps 注册未就绪：$err（下一子拍重试）")
                        }
                        FrameRateSource.readTaskFpsSample()?.let { (fps, at) ->
                            if (at > taskFpsLastPushAt) {
                                taskFpsLastPushAt = at
                                taskFpsLastFps = fps
                                // 新推送即时发布（tab 跟随系统推送节奏）
                                _fps.value = fps.toDouble()
                                if (_error.value == ERROR_NO_TARGET_APP) _error.value = null
                            }
                        }
                        // 推送陈旧（>2.5s）= 任务静止：读数落 0（与录制循环样本口径一致）
                        if (taskFpsLastPushAt > 0 && now - taskFpsLastPushAt > TASK_FPS_STALE_MS) {
                            _fps.value = 0.0
                        }
                    }
                }
            }
            tick++
            // 补偿式等待（子拍口径同录制循环）：预览每子拍跑帧率取数，4Hz 响应
            val spent = System.currentTimeMillis() - now
            delay((SUB_TICK_MS - spent).coerceAtLeast(MIN_SUB_TICK_MS))
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
        algo: FpsAlgorithm,
    ) {
        if (pending.isEmpty()) return
        try {
            val dao = FrameDatabase.getInstance(appContext).frameDao()
            // ⚠️ avg / min 汇总前剔除**超刷新率+容差**的样本（上限 = 1s 窗物理上限
            // refresh+1.0，口径沿革见 [FPS_CEILING_TOLERANCE]）：边界值本身合法、照常
            // 参与（与 max 一致，不再系统性低估）；子拍守卫拦不住的漏网污染在此兜底。
            // ⚠️ **max 例外**（2026-09-27 用户口径）：max 的语义是"实际记录到的最大帧率"，
            // 全量样本取 max，不剔边界帧 —— 120Hz 屏录出 121 就显示 121（Scene / Kite 同口径）。
            // ⚠️ 只影响汇总数字，样本本体照常落库 —— 那一秒的功率 / 温度 / 丢帧数据仍要用于
            // 详情页与帧率的相关性分析，整条丢弃会在曲线上挖洞。
            // 刷新率未知（0，无法判定）或全部超限（刷新率上报异常）→ 退回全量：
            // 宁可保守也不要 NaN / 少样本，超限样本终归是少数。
            val fpsValues = pending
                .filter { refreshHz <= 0 || it.fps <= refreshHz + FPS_CEILING_TOLERANCE }
                .ifEmpty { pending }
                .map { it.fps }
            // MIN / 1% / 5% Low 的取值源：**帧率采样点表优先**（frame_fps_samples，
            // 2026-09-27 差分回退 1Hz 后与 1s 样本同为 1Hz 粒度；保留独立来源是因两表
            // 落库条件略有差异）。⚠️ fps=0 的点不计入（语义是"该拍没有合成帧"，不是
            // "帧率掉到 0"，与 [lowFps] 的正样本口径一致）。
            val lowSource = pendingFps.mapNotNull { it.fps }.filter { it > 0.0 }.ifEmpty { fpsValues }
            val session = FrameSession(
                startTime = startWall,
                endTime = endWall,
                packageName = pkg,
                appLabel = resolveAppLabel(pkg),
                refreshRateHz = refreshHz,
                // 本场生效的采样源（FPS 卡曲线 / 帧间隔的口径依据；中途自动回落也按
                // 实际生效值落库，2026-09-29 加）
                fpsSource = algo.key,
                sampleCount = pending.size,
                avgFps = fpsValues.average(),
                minFps = lowSource.min(),
                maxFps = pending.maxOf { it.fps },
                lowFps1 = lowFps(lowSource, 0.01),
                lowFps5 = lowFps(lowSource, 0.05),
                avgFrameSpaceMs = pending.map { it.frameSpaceMs }.average(),
                jankCount = pending.sumOf { it.missedFrames },
            )
            val id = dao.insertSession(session)
            dao.insertSamples(pending.map { FrameSampleEntity.from(id, it) })
            if (pendingCpu.isNotEmpty()) {
                dao.insertCpuSamples(pendingCpu.map { it.copy(sessionId = id) })
            }
            if (pendingFps.isNotEmpty()) {
                dao.insertFpsSamples(pendingFps.map { it.copy(sessionId = id) })
            }
            Log.i(
                TAG,
                "已保存帧率会话 id=$id，样本 ${pending.size} 条（CPU 快样 ${pendingCpu.size}、" +
                    "FPS 子拍 ${pendingFps.size}），pkg=$pkg",
            )
            pending.clear()
            pendingCpu.clear()
            pendingFps.clear()
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
