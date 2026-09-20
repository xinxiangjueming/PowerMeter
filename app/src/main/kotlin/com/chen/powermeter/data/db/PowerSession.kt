package com.chen.powermeter.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 采样会话 —— 一次「开始采样 → 停止采样」的完整数据集合。
 *
 * ⚠️ 语义与历史列表类应用不同：这里的会话是**自动保存的临时存档**，不是用户资产。
 * 生命周期由三条规则决定（见 SessionRecorder）：
 * 1. 采样期间每 10s 增量落样本；
 * 2. 停止采样后保留，等用户点「导出 CSV」；点了就导出并**删除整条会话**；
 * 3. 没点导出就一直留着（进程被杀也不丢），直到用户导出、或开始新一场采样。
 *
 * 因此本表在稳态下**最多只有一行** —— 就是最近一次未导出的那一场；
 * 冷启动时会把更早的存档收敛掉。
 */
@Entity(tableName = "power_sessions")
data class PowerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 会话开始时间（epoch ms） */
    val startTime: Long,
    /** 最后一次落库时间（epoch ms）；停止采样时定格为结束时间 */
    val endTime: Long,
)
