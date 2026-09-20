package com.chen.powermeter.data.db

import android.content.Context
import android.net.Uri
import android.util.Log
import com.chen.powermeter.data.PowerSample
import com.chen.powermeter.util.CsvExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SessionRecorder"

/**
 * 采样会话录制器（Room 增量落库）—— 单消费者 Channel 架构，参考 heartratecomparison 的
 * `CsvRecorder`（同一作者、同一套取舍），只是把「秒级批量」换成「10 秒定时刷盘」。
 *
 * 为什么用 Channel + 单消费者：
 * - 采样循环运行在 `Dispatchers.Default` 上，**绝不能**在采样点里做 DB 写（IO 抖动会直接
 *   体现为采样间隔抖动，0.5s 档尤其明显）。[onSample] 只做一次 `trySend`，零阻塞。
 * - 全部可变状态（pending / sessionId）只由消费循环一条协程访问，无需加锁。
 * - Channel 容量 UNLIMITED：宁可让 IO 端慢慢消化，也不丢样本。
 *
 * 数据安全边界：**进程被杀最多丢 10 秒**（上次刷盘之后到被杀之前的样本）。
 *
 * 会话生命周期（本项目的核心约定，与「历史列表」类应用相反）：
 * ```
 * start ──(每 10s 增量 flush)──► stop ──► 等用户点「导出 CSV」
 *                                  │            │
 *                                  │            ├─ 点了 → 导出全量 CSV → 删除会话
 *                                  │            └─ 没点 → 保留为「最近一次未导出会话」
 *                                  │                     ├─ 用户导出 → 删除
 *                                  │                     ├─ 用户开始新一场 → 删除
 *                                  │                     └─ 冷启动 → 只留最新的一条，更早的删掉
 *                                  └─ 充电监测已自动保存过 → 停止即删除（用户已拿到文件）
 * ```
 * 一句话：库里**最多存在一条**"待导出"的会话，就是最近那一场。
 */
object SessionRecorder {

    /** 定时刷盘间隔：权衡「进程被杀的丢失窗口」与「写放大」。10s 是最丢得起又不过频的档 */
    private const val FLUSH_INTERVAL_MS = 10_000L

    /** 导出时的分页大小：整会话可能上万行，分页写出避免一次性 load 进内存 */
    const val PAGE_SIZE = 5_000

    // ── 消息协议（所有状态变更路由到单消费者）──
    private sealed interface Msg {
        data class Start(val deferred: CompletableDeferred<Long>) : Msg
        data class Sample(val sample: PowerSample) : Msg
        data class Flush(val deferred: CompletableDeferred<Unit>? = null) : Msg
        data class Stop(val deferred: CompletableDeferred<Unit>, val delete: Boolean) : Msg
        data class Delete(
            val deferred: CompletableDeferred<Unit>,
            val restart: Boolean,
            /** 待删除的会话；null = 当前会话 */
            val id: Long? = null,
        ) : Msg
        data class Prune(val deferred: CompletableDeferred<Unit>) : Msg
    }

    private lateinit var dao: SampleDao

    /**
     * 初始化完成标志。
     * 用独立的布尔量而不是 `::dao.isInitialized`：后者在 object 里需要写成 `this::dao`，
     * 且 `lateinit` 未初始化时访问会抛异常 —— 这里要的是「静默降级」，不是抛错。
     */
    @Volatile
    private var ready = false
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<Msg>(Channel.UNLIMITED)

    /**
     * 当前会话 ID：0 = 无会话。
     * 由消费循环写、外部读，故 volatile；Long 的读写本身是原子的。
     */
    @Volatile
    private var sessionId: Long = 0L

    /** 待落库样本：只在消费循环内访问 */
    private val pending = ArrayList<PowerSampleEntity>()

    private var flushJob: Job? = null

    val hasSession: Boolean get() = sessionId != 0L

    fun init(context: Context) {
        dao = PowerMeterDatabase.getInstance(context).sampleDao()
        ready = true
        ioScope.launch { processingLoop() }
    }

    // ── 外部 API ──────────────────────────────────────────

    /** 采样循环调用：零阻塞，只入队 */
    fun onSample(sample: PowerSample) {
        if (!ready) return
        channel.trySend(Msg.Sample(sample))
    }

    /** 开启新会话；返回 sessionId。须在采样循环开始前完成，否则样本无归属 */
    suspend fun start(): Long {
        val deferred = CompletableDeferred<Long>()
        channel.send(Msg.Start(deferred))
        return deferred.await()
    }

    /**
     * 停止采样：收尾刷盘 + 定格结束时间。
     * @param delete true = 直接删除整条会话（用户已通过「充电功率监测」的自动保存拿到 CSV）
     *
     * 走自带 IO scope 而非调用方 scope：服务 onDestroy 会立刻 cancel 自己的 scope，
     * 挂在这里的收尾协程会被连坐取消。
     */
    fun stopAsync(delete: Boolean) {
        if (!ready) return
        ioScope.launch { stop(delete) }
    }

    private suspend fun stop(delete: Boolean) {
        val deferred = CompletableDeferred<Unit>()
        channel.send(Msg.Stop(deferred, delete))
        deferred.await()
    }

    /**
     * 清空采样数据（「清空采样数据」按钮）。
     * @param restart true = 采样仍在运行，删掉旧会话后立刻开一个空的继续记
     */
    fun discardAsync(restart: Boolean) {
        if (!ready) return
        ioScope.launch {
            val deferred = CompletableDeferred<Unit>()
            channel.send(Msg.Delete(deferred, restart))
            deferred.await()
        }
    }

    /**
     * 冷启动清理：**保留最近一次会话，删掉更早的**。
     *
     * 语义说明：库里的会话都是「停止后没点导出」的存档。理论上可以全删（用户没要），
     * 但 HyperOS 上「停止采样 → 切走 → 进程被回收」发生得很快，用户常常还没来得及导出，
     * 全删等于把刚测完的数据直接丢掉。折中方案是保留最新的一条，让「打开应用 → 点导出」
     * 这条路始终可用；而这条存档在用户**导出**或**开始新一场采样**时被清掉，不会长期堆积。
     */
    fun pruneOldSessionsAsync() {
        if (!ready) return
        ioScope.launch {
            val deferred = CompletableDeferred<Unit>()
            channel.send(Msg.Prune(deferred))
            deferred.await()
        }
    }

    /**
     * 把当前会话导出为 CSV，然后删除会话。
     *
     * @param rotate true = 采样仍在运行 → 删掉旧会话后立刻开新会话，后续样本写进新会话
     *               （否则会出现「样本继续往一个已删除的 sessionId 写」的外键错误）
     * @param deleteAfter false = 只导出、保留会话（「充电功率监测」的自动保存走这条路：
     *                    采样不中断，会话要继续累积，最终由 stop 决定是否删除）
     * @return 导出结果；无会话或无样本时 uri 为 null
     *
     * ⚠️ 必须在 IO 线程调用：内部有 DB 分页读与 MediaStore 写。
     */
    suspend fun exportCurrent(
        context: Context,
        prefix: String,
        rotate: Boolean,
        deleteAfter: Boolean = true,
    ): ExportResult {
        if (!ready) return ExportResult(null, 0)
        val id = sessionId.takeIf { it != 0L } ?: dao.latestSession()?.id
        if (id == null || id == 0L) return ExportResult(null, 0)

        val count = dao.countSamples(id)
        if (count == 0) {
            // 空会话不留残骸（例如开启采样后立刻停止、一个样本都没采到）
            if (deleteAfter) deleteSession(id, rotate)
            return ExportResult(null, 0)
        }

        val uri = CsvExporter.exportPaged(context, prefix, count) { offset, limit ->
            dao.samplesPage(id, limit, offset).map { it.toPowerSample() }
        }
        if (uri != null && deleteAfter) deleteSession(id, rotate)
        return ExportResult(uri, count)
    }

    private suspend fun deleteSession(id: Long, restart: Boolean) {
        val deferred = CompletableDeferred<Unit>()
        channel.send(Msg.Delete(deferred, restart, id))
        deferred.await()
    }

    // ── 消费循环 ──────────────────────────────────────────

    private suspend fun processingLoop() {
        for (msg in channel) {
            when (msg) {
                is Msg.Start -> {
                    try {
                        // 丢弃上一场残留（Stop 之后才入队的零星样本）：否则会被冠上新 sessionId
                        // 写进新会话，凭空多出几个时间戳错位的点
                        pending.clear()
                        // 上一场停止后没点导出、就又开始新一场 ⇒ 按约定在此时删掉它。
                        // 这一步同时兜住了「用户同一进程内反复采样」的堆积，不必等冷启动清理
                        val stale = sessionId
                        if (stale != 0L) runCatching { dao.deleteSession(stale) }
                        startFlushTimer()
                        sessionId = dao.insertSession(
                            PowerSession(
                                startTime = System.currentTimeMillis(),
                                endTime = System.currentTimeMillis(),
                            )
                        )
                        Log.d(TAG, "会话已创建: sessionId=$sessionId")
                        msg.deferred.complete(sessionId)
                    } catch (e: Exception) {
                        Log.e(TAG, "创建会话失败", e)
                        msg.deferred.completeExceptionally(e)
                    }
                }

                is Msg.Sample -> {
                    // 无会话（尚未 start / 已被清理）时静默丢弃：写下去会撞外键约束
                    val id = sessionId
                    if (id != 0L) pending += PowerSampleEntity.from(id, msg.sample)
                }

                is Msg.Flush -> {
                    try {
                        flushPending()
                    } catch (e: Exception) {
                        Log.e(TAG, "定时刷盘失败", e)
                    }
                    msg.deferred?.complete(Unit)
                }

                is Msg.Stop -> {
                    stopFlushTimer()
                    try {
                        flushPending()
                        // 空会话（开了没采到点、或数据刚被清空）没有导出价值，直接删掉，
                        // 免得在库里留一条待清理的残骸
                        val empty = sessionId != 0L && dao.countSamples(sessionId) == 0
                        if (msg.delete || empty) {
                            dao.deleteSession(sessionId)
                            sessionId = 0L
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "结束会话失败", e)
                    }
                    msg.deferred.complete(Unit)
                }

                is Msg.Delete -> {
                    try {
                        dao.deleteSession(msg.id ?: sessionId)
                        pending.clear()
                        if (msg.restart) {
                            startFlushTimer()
                            sessionId = dao.insertSession(
                                PowerSession(
                                    startTime = System.currentTimeMillis(),
                                    endTime = System.currentTimeMillis(),
                                )
                            )
                        } else {
                            stopFlushTimer()
                            sessionId = 0L
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "删除会话失败", e)
                    }
                    msg.deferred.complete(Unit)
                }

                is Msg.Prune -> {
                    try {
                        val keep = dao.latestSession()?.id
                        if (keep == null) {
                            dao.deleteAllSessions()
                            sessionId = 0L
                        } else {
                            dao.deleteAllExcept(keep)
                            // ⚠️ 把保留的会话记到 sessionId 上，而不是留 0：
                            // Msg.Start 靠 sessionId 判断「上一场有没有导出」，留 0 会让这条
                            // 存档逃过「开始新一场即删除」的规则，变成永远删不掉的残留
                            sessionId = keep
                        }
                        stopFlushTimer()
                    } catch (e: Exception) {
                        Log.e(TAG, "冷启动清理失败", e)
                    }
                    msg.deferred.complete(Unit)
                }
            }
        }
    }

    /** 写出待落库样本并定格 endTime；无待写样本时只更新 endTime（让会话看起来是活的） */
    private suspend fun flushPending() {
        val id = sessionId
        if (id == 0L) return
        if (pending.isNotEmpty()) {
            val batch = pending.toList()
            pending.clear()
            dao.insertSamples(batch)
        }
        dao.updateEndTime(id, System.currentTimeMillis())
    }

    private fun startFlushTimer() {
        stopFlushTimer()
        flushJob = ioScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                channel.trySend(Msg.Flush())
            }
        }
    }

    private fun stopFlushTimer() {
        flushJob?.cancel()
        flushJob = null
    }
}

/** 导出结果：[uri] 为 null 表示导出失败或无数据；[sampleCount] 为库中的实际条数 */
data class ExportResult(val uri: Uri?, val sampleCount: Int)
