package com.chen.powermeter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chen.powermeter.data.FrameSample

/**
 * 帧率样本行 —— 字段与 [FrameSample] 一一对应，落库不做任何降精度。
 *
 * 与 [PowerSampleEntity] 同构的两处约定：
 * 1. 唯一索引 (sessionId, timeMillis) + `OnConflictStrategy.REPLACE` ⇒ 重复 flush 幂等；
 *    采样间隔为秒级，同一会话内 timeMillis 天然不重复。
 * 2. 外键 `ON DELETE CASCADE` ⇒ 删除会话即连带删掉全部样本，不需要手写两条 DELETE。
 *
 * ⚠️ CPU 频率存成 8 个独立列而非序列化字符串：详情页要按核心画曲线，
 *    存字符串每次取数都要解析一遍，且无法在 SQL 层做任何聚合。
 */
@Entity(
    tableName = "frame_samples",
    foreignKeys = [
        ForeignKey(
            entity = FrameSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["sessionId", "timeMillis"], unique = true)
    ]
)
data class FrameSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMillis: Long,
    val fps: Double,
    val frameSpaceMs: Double,
    val missedFrames: Int,
    val cpu0Mhz: Double,
    val cpu1Mhz: Double,
    val cpu2Mhz: Double,
    val cpu3Mhz: Double,
    val cpu4Mhz: Double,
    val cpu5Mhz: Double,
    val cpu6Mhz: Double,
    val cpu7Mhz: Double,
    /**
     * CPU 全核使用率 %（/proc/stat 差分，2026-09-22 加）。
     * 可空语义与电量五列一致：null = 该周期没有有效差分（录制第一拍还没基线 / 命令失败），
     * 列可空而不是填 0，是为了让「没采」与「采到 0」在库里可区分。
     */
    val cpuUsagePct: Double?,
    /**
     * CPU 逐核使用率 %（v9 起，/proc/stat 逐核行差分；下标 = 核心号）。
     * 与频率同理存 8 个独立列；null = 该核无有效差分（基线未建 / 离线核）/ 旧会话未采集。
     */
    val cpu0UsagePct: Double?,
    val cpu1UsagePct: Double?,
    val cpu2UsagePct: Double?,
    val cpu3UsagePct: Double?,
    val cpu4UsagePct: Double?,
    val cpu5UsagePct: Double?,
    val cpu6UsagePct: Double?,
    val cpu7UsagePct: Double?,
    /**
     * 电量类字段**可空**（对应 [FrameSample] 的同名可空字段）：
     * 通道取不到数（Shizuku 未绑定 / 无 su）的周期这几列为空，详情页显示破折号。
     * 列可空而不是填 0，是为了让「没采」与「采到 0」在库里可区分。
     */
    val currentMa: Double?,
    /** 电压 mV（RootPowerReader 的 V ×1000 落库，毫口径与 Kite CSV 同源，见 [FrameSample]） */
    val voltageMv: Double?,
    /** 功率 mW（×1000 落库；App 显示时 ÷1000 回 W） */
    val powerMw: Double?,
    val tempBatteryC: Double?,
    val tempVirtualC: Double?,
    /** GPU 温感区温度 ℃（v7 起采集；无 GPU 温感区机型 / 旧会话为 null） */
    val gpuTempC: Double?,
    /** 电池容量 %（v7 起采集，来自功率链 socPct；通道不可用 / 旧会话为 null） */
    val capacityPct: Double?,
    /** GPU 占用率 %（v8 起采集，kgsl gpu_busy_percentage；节点不可读 / 旧会话为 null） */
    val gpuLoadPct: Double?,
) {
    fun toFrameSample(): FrameSample = FrameSample(
        timeMillis = timeMillis,
        fps = fps,
        frameSpaceMs = frameSpaceMs,
        missedFrames = missedFrames,
        cpuMhz = listOf(cpu0Mhz, cpu1Mhz, cpu2Mhz, cpu3Mhz, cpu4Mhz, cpu5Mhz, cpu6Mhz, cpu7Mhz),
        cpuUsagePct = cpuUsagePct,
        cpuCoreUsagePct = listOf(
            cpu0UsagePct, cpu1UsagePct, cpu2UsagePct, cpu3UsagePct,
            cpu4UsagePct, cpu5UsagePct, cpu6UsagePct, cpu7UsagePct,
        ),
        currentMa = currentMa,
        voltageMv = voltageMv,
        powerMw = powerMw,
        tempBatteryC = tempBatteryC,
        tempVirtualC = tempVirtualC,
        gpuTempC = gpuTempC,
        capacityPct = capacityPct,
        gpuLoadPct = gpuLoadPct,
    )

    companion object {
        /** 核心数不足 8 时补 0 —— 列是 NOT NULL，缺位必须显式填 */
        private fun List<Double>.core(index: Int): Double = getOrNull(index) ?: 0.0

        fun from(sessionId: Long, s: FrameSample): FrameSampleEntity = FrameSampleEntity(
            sessionId = sessionId,
            timeMillis = s.timeMillis,
            fps = s.fps,
            frameSpaceMs = s.frameSpaceMs,
            missedFrames = s.missedFrames,
            cpu0Mhz = s.cpuMhz.core(0),
            cpu1Mhz = s.cpuMhz.core(1),
            cpu2Mhz = s.cpuMhz.core(2),
            cpu3Mhz = s.cpuMhz.core(3),
            cpu4Mhz = s.cpuMhz.core(4),
            cpu5Mhz = s.cpuMhz.core(5),
            cpu6Mhz = s.cpuMhz.core(6),
            cpu7Mhz = s.cpuMhz.core(7),
            cpuUsagePct = s.cpuUsagePct,
            cpu0UsagePct = s.cpuCoreUsagePct.getOrNull(0),
            cpu1UsagePct = s.cpuCoreUsagePct.getOrNull(1),
            cpu2UsagePct = s.cpuCoreUsagePct.getOrNull(2),
            cpu3UsagePct = s.cpuCoreUsagePct.getOrNull(3),
            cpu4UsagePct = s.cpuCoreUsagePct.getOrNull(4),
            cpu5UsagePct = s.cpuCoreUsagePct.getOrNull(5),
            cpu6UsagePct = s.cpuCoreUsagePct.getOrNull(6),
            cpu7UsagePct = s.cpuCoreUsagePct.getOrNull(7),
            currentMa = s.currentMa,
            voltageMv = s.voltageMv,
            powerMw = s.powerMw,
            tempBatteryC = s.tempBatteryC,
            tempVirtualC = s.tempVirtualC,
            gpuTempC = s.gpuTempC,
            capacityPct = s.capacityPct,
            gpuLoadPct = s.gpuLoadPct,
        )
    }
}
