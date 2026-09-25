package com.chen.powermeter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 帧率采集会话 —— 一次「开始采集 → 停止采集」的完整数据集合。
 *
 * ⚠️ 语义与 [PowerSession] **相反**，勿混用同一套生命周期规则：
 * - [PowerSession] 是「自动保存的临时存档」：导出即删、稳态下库里最多留一条；
 * - 本表是**用户资产**：它会出现在「帧率监测」的历史记录列表里供随时回看，
 *   只有用户显式删除才消失。
 *
 * 汇总字段（avg / min / max / 丢帧）在**停止采集时一次性算出并落库**：历史列表要直接
 * 显示它们，而逐条样本重算是 O(n)（1s 采样、半小时就是 1800 行），列表滚动时反复算代价过高。
 */
@Entity(tableName = "frame_sessions")
data class FrameSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 采集开始时间（epoch ms） */
    val startTime: Long,
    /** 采集结束时间（epoch ms） */
    val endTime: Long,
    /** 被测应用包名（timestats 每图层带 uid，据此归属） */
    val packageName: String,
    /** 被测应用显示名；解析不到时回落为包名 */
    val appLabel: String,
    /** 采集期间的设备刷新率 Hz（`dumpsys display` 的 mActiveRenderFrameRate） */
    val refreshRateHz: Int,
    val sampleCount: Int,
    val avgFps: Double,
    val minFps: Double,
    val maxFps: Double,
    /** 平均帧间隔 ms */
    val avgFrameSpaceMs: Double,
    /** 全程累计丢帧数 */
    val jankCount: Int,
    /**
     * 1% Low 帧率：最差 1% 帧的平均帧率（帧加权，CapFrameX 口径的 1s 采样等价实现，
     * 算法见 [com.chen.powermeter.service.FrameRecordController.lowFps]）。
     * 可空 = 旧版本会话未计算（v5 之前落库的记录），界面显示破折号。
     */
    val lowFps1: Double? = null,
    /** 5% Low 帧率：最差 5% 帧的平均帧率，口径同 [lowFps1] */
    val lowFps5: Double? = null,
)
