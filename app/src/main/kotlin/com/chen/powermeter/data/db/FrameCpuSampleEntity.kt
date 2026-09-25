package com.chen.powermeter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chen.powermeter.data.FrameRateSource

/**
 * CPU 快样行（250ms 级，2026-09-25 加）—— CPU Usage / CPU Frequency 两卡的密集数据源。
 *
 * 背景：CPU 使用率与频率是快变量，1s 样本（[FrameSampleEntity]）里的 CPU 字段是把
 * 250ms 级抖动摊平后的均值 —— 用户对照 Scene 工具箱实测「明显不如」：频率升降挡只剩
 * 台阶、使用率毛刺全无。快变量**单独建表**而不是抬高 1s 样本的采样率：帧率 / 电量 /
 * 温度的逐秒对齐口径（Kite 每秒行）不动，1s 样本的 CPU 字段改为快样的窗口均值。
 *
 * 与 [FrameSampleEntity] 同构的两处约定：
 * 1. 唯一索引 (sessionId, timeMillis) + REPLACE ⇒ 重复 flush 幂等；快样间隔 ≥250ms，
 *    同一会话内 timeMillis 天然不重复；
 * 2. 外键 `ON DELETE CASCADE` ⇒ 删除会话即连带删掉全部快样。
 *
 * ⚠️ 录制循环内存里的实体 sessionId=0，落库时由 FrameRecordController.copy 补上。
 */
@Entity(
    tableName = "frame_cpu_samples",
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
data class FrameCpuSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 0,
    val timeMillis: Long,
    /**
     * CPU 全核合计使用率 %（/proc/stat 差分，差分窗口 = 两次快样的实际间隔）。
     * null = 本拍无有效差分（首拍基线未建 / 读数失败），与 1s 样本同口径：可空区分
     * 「没采」与「采到 0」。
     */
    val totalPct: Double?,
    /** 逐核使用率 %（下标 = 核心号）；null = 该核无有效差分（基线未建 / 离线核） */
    val cpu0UsagePct: Double?,
    val cpu1UsagePct: Double?,
    val cpu2UsagePct: Double?,
    val cpu3UsagePct: Double?,
    val cpu4UsagePct: Double?,
    val cpu5UsagePct: Double?,
    val cpu6UsagePct: Double?,
    val cpu7UsagePct: Double?,
    /** 逐核实时频率 MHz；null = 该核 scaling_cur_freq 没读到（离线 / SELinux 拦截） */
    val cpu0Mhz: Double?,
    val cpu1Mhz: Double?,
    val cpu2Mhz: Double?,
    val cpu3Mhz: Double?,
    val cpu4Mhz: Double?,
    val cpu5Mhz: Double?,
    val cpu6Mhz: Double?,
    val cpu7Mhz: Double?,
) {
    /** 逐核使用率视图（下标 = 核心号，详情页按簇聚合计数用） */
    val coreUsagePct: List<Double?>
        get() = listOf(
            cpu0UsagePct, cpu1UsagePct, cpu2UsagePct, cpu3UsagePct,
            cpu4UsagePct, cpu5UsagePct, cpu6UsagePct, cpu7UsagePct,
        )

    /** 逐核频率视图（下标 = 核心号） */
    val mhzList: List<Double?>
        get() = listOf(cpu0Mhz, cpu1Mhz, cpu2Mhz, cpu3Mhz, cpu4Mhz, cpu5Mhz, cpu6Mhz, cpu7Mhz)

    companion object {
        /** 核心数不足 8 时该列留 null（可空列，缺位 = 没读到，不与 0 混淆） */
        fun from(timeMillis: Long, s: FrameRateSource.CpuFastSample): FrameCpuSampleEntity =
            FrameCpuSampleEntity(
                timeMillis = timeMillis,
                totalPct = s.totalPct,
                cpu0UsagePct = s.corePct.getOrNull(0),
                cpu1UsagePct = s.corePct.getOrNull(1),
                cpu2UsagePct = s.corePct.getOrNull(2),
                cpu3UsagePct = s.corePct.getOrNull(3),
                cpu4UsagePct = s.corePct.getOrNull(4),
                cpu5UsagePct = s.corePct.getOrNull(5),
                cpu6UsagePct = s.corePct.getOrNull(6),
                cpu7UsagePct = s.corePct.getOrNull(7),
                cpu0Mhz = s.mhz.getOrNull(0),
                cpu1Mhz = s.mhz.getOrNull(1),
                cpu2Mhz = s.mhz.getOrNull(2),
                cpu3Mhz = s.mhz.getOrNull(3),
                cpu4Mhz = s.mhz.getOrNull(4),
                cpu5Mhz = s.mhz.getOrNull(5),
                cpu6Mhz = s.mhz.getOrNull(6),
                cpu7Mhz = s.mhz.getOrNull(7),
            )
    }
}
