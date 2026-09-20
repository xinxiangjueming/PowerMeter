package com.chen.powermeter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时采样序列的环形缓冲 + O(1) 增量统计（档二-1，2026-09-21）。
 *
 * ⚠️ **定位（2026-09-21 落库改造后）**：本缓冲已降级为**界面显示窗口**，不再是数据权威。
 * 采样数据的持久化由 [com.chen.powermeter.data.db.SessionRecorder] 每 10s 写进 Room 负责；
 * 这里只回答一个问题 —— 「曲线上还能画出多少个点」。
 * 因此 [CAPACITY] 是**显示精度**参数，不是"数据会不会丢"的参数。
 *
 * 取代旧实现 `_samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)`：
 * 后者每个采样周期都要新建一个等长元素的新列表 —— 息屏时也照做，白烧 CPU；
 * 同时把 `computeStats` 的 O(n) 重算挂在了每次 UI 重组上。
 *
 * 职责边界：本仓库只负责**写**（[append] / [clear]）与**按需读**（[snapshot]）。
 * 关键在于 [snapshot] 由调用方在真正需要时才调用 —— UI 侧只在重组（= 有帧）时取快照，
 * 息屏无帧即零成本；服务侧的兜底导出只在落盘那一刻取一次。
 *
 * ⚠️ 统计量语义与旧的 `computeStats` 有一处**有意调整**：
 * - 峰值 / 电压范围 / 最高温度 / 平均功率 / 累计 mAh、Wh 改为**本次会话累计**，
 *   不再随窗口滑出而丢失（旧实现窗口一满就把早期峰值悄悄抹掉）；
 * - [SessionStats.sampleCount] 仍是**当前缓冲内**的样本数，与图表一致
 *   （导出的条数来自库，两者可能不同 —— 导出不受窗口限制）；
 * - [SessionStats.durationMs] 为**会话**首末样本时间差（旧实现是窗口首末之差）。
 */
object SampleStore {

    /**
     * 缓冲上限（= 界面曲线的可见点数上限），超出即覆盖最旧的一条。
     *
     * 2026-09-21 用户拍板从 3600 提到 **7200**：1s 间隔 ≈ 2 小时、0.5s 间隔 ≈ 1 小时。
     * 之所以敢翻倍 —— 数据权威已在 Room 里（见 [SessionRecorder]），本缓冲只是显示窗口，
     * 丢的只是「画不出来的旧点」，不影响导出的完整性。
     *
     * ⚠️ 代价：每次版本变化后 UI 会取一次快照，分配量随本值线性增长
     * （7200 × 约 150 B ≈ 1 MB/次，亮屏 1s 档 ≈ 1 MB/s）。若日后要再放大，
     * 应先考虑把 `snapshot()` 从"整体拷贝"改成"只拷贝新增段"。
     */
    const val CAPACITY = 7200

    private val lock = Any()
    private val ring = arrayOfNulls<PowerSample>(CAPACITY)

    /** 下一个写入位置 */
    private var head = 0
    private var size = 0

    private var sessionStartMs = 0L
    private var lastSample: PowerSample? = null

    // ---- 增量统计累加器：每次 append 只做常数次比较与累加 ----
    private var sumPower = 0.0
    private var samplesSeen = 0L
    private var peakCharge = 0.0
    private var peakDischarge = 0.0
    private var minVoltageV = Double.MAX_VALUE
    private var maxVoltageV = -Double.MAX_VALUE
    private var maxTempC = -Double.MAX_VALUE
    private var chargedMah = 0.0
    private var dischargedMah = 0.0
    private var chargedWh = 0.0
    private var dischargedWh = 0.0

    /** 每次 [append] / [clear] 自增。UI 以它作为快照缓存的 key，无需比较整个列表 */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    /** 当前缓冲内样本数 */
    val sampleCount: Int get() = synchronized(lock) { size }

    val isEmpty: Boolean get() = sampleCount == 0

    /** 最近一条样本（不产生任何拷贝） */
    fun latest(): PowerSample? = synchronized(lock) {
        if (size == 0) null else ring[(head - 1 + CAPACITY) % CAPACITY]
    }

    /**
     * 追加一条样本并增量更新统计量。全程 O(1)、零列表分配。
     *
     * 累计量用梯形法（与旧 `computeStats` 同一公式）：`Σ (Iₙ₋₁+Iₙ)/2 × Δt`，
     * 按两条通道独立结算 —— `chargedMah` ← currentMa 积分、`chargedWh` ← powerW 积分。
     *
     * ⚠️ 缓冲溢出时只丢弃最旧那条的**存储**，已累加的 Wh/mAh 不回退（会话累计语义）。
     */
    fun append(sample: PowerSample) {
        synchronized(lock) {
            val prev = lastSample
            if (prev == null) {
                sessionStartMs = sample.timeMillis
            } else {
                val dtH = (sample.timeMillis - prev.timeMillis) / 3_600_000.0
                // dtH <= 0（时间戳不前进 / 系统时间被改）时跳过这一段，与旧实现一致
                if (dtH > 0) {
                    val avgCurrentMa = (prev.currentMa + sample.currentMa) / 2.0
                    val avgPowerW = (prev.powerW + sample.powerW) / 2.0
                    if (avgCurrentMa >= 0) {
                        chargedMah += avgCurrentMa * dtH
                        chargedWh += avgPowerW * dtH
                    } else {
                        dischargedMah += -avgCurrentMa * dtH
                        dischargedWh += -avgPowerW * dtH
                    }
                }
            }
            lastSample = sample

            sumPower += sample.powerW
            samplesSeen++
            if (sample.powerW > peakCharge) peakCharge = sample.powerW
            if (sample.powerW < peakDischarge) peakDischarge = sample.powerW
            if (sample.voltageV < minVoltageV) minVoltageV = sample.voltageV
            if (sample.voltageV > maxVoltageV) maxVoltageV = sample.voltageV
            if (sample.tempBatteryC > maxTempC) maxTempC = sample.tempBatteryC

            ring[head] = sample
            head = (head + 1) % CAPACITY
            if (size < CAPACITY) size++

            _stats.value = buildStats()
            _version.value++
        }
    }

    private fun buildStats(): SessionStats {
        val last = lastSample
        if (samplesSeen == 0L || last == null) return SessionStats()
        return SessionStats(
            sampleCount = size,
            durationMs = (last.timeMillis - sessionStartMs).coerceAtLeast(0L),
            avgPowerW = sumPower / samplesSeen,
            peakChargeW = peakCharge,
            peakDischargeW = peakDischarge,
            minVoltageV = if (minVoltageV == Double.MAX_VALUE) 0.0 else minVoltageV,
            maxVoltageV = if (maxVoltageV == -Double.MAX_VALUE) 0.0 else maxVoltageV,
            maxTempC = if (maxTempC == -Double.MAX_VALUE) 0.0 else maxTempC,
            chargedMah = chargedMah,
            dischargedMah = dischargedMah,
            chargedWh = chargedWh,
            dischargedWh = dischargedWh,
        )
    }

    /**
     * 按时间升序导出快照。
     *
     * ⚠️ **只在真正需要时调用** —— 这是唯一会产生 O(n) 分配的地方：
     * 落盘导出、以及 UI 在 [version] 变化后的那一次重组。
     */
    fun snapshot(): List<PowerSample> = synchronized(lock) {
        if (size == 0) {
            emptyList()
        } else {
            val out = ArrayList<PowerSample>(size)
            // 未写满时数据从下标 0 起；写满后最旧的一条就在 head（即将被覆盖的位置）
            val start = if (size < CAPACITY) 0 else head
            for (k in 0 until size) {
                ring[(start + k) % CAPACITY]?.let { out.add(it) }
            }
            out
        }
    }

    /** 清空缓冲与全部统计量（会话复位） */
    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            head = 0
            size = 0
            sessionStartMs = 0L
            lastSample = null
            sumPower = 0.0
            samplesSeen = 0L
            peakCharge = 0.0
            peakDischarge = 0.0
            minVoltageV = Double.MAX_VALUE
            maxVoltageV = -Double.MAX_VALUE
            maxTempC = -Double.MAX_VALUE
            chargedMah = 0.0
            dischargedMah = 0.0
            chargedWh = 0.0
            dischargedWh = 0.0
            _stats.value = SessionStats()
            _version.value++
        }
    }
}
