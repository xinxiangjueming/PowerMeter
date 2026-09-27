package com.chen.powermeter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 帧率子拍点（250ms 级，2026-09-27 加）—— FPS 曲线的密集数据源 + MIN / 1%/5% Low 的取值源。
 *
 * 背景：帧率差分原本只按完整拍（1s）跑，短时深低谷被 1s 窗口摊薄 —— 同一场王者，
 * Scene 记到 91fps 的低谷、本应用只有 101（2026-09-27 用户对照实测）。差分提到每个
 * 250ms 子拍后，短窗能抓 250ms 级低谷，MIN / 低分位随之变深，悬浮 tab 也获得
 * Scene 式的 4Hz 响应。
 *
 * 与 [FrameCpuSampleEntity] 同构的两处约定：
 * 1. 唯一索引 (sessionId, timeMillis) + REPLACE ⇒ 重复 flush 幂等；子拍间隔 ≥250ms，
 *    同一会话内 timeMillis 天然不重复；
 * 2. 外键 `ON DELETE CASCADE` ⇒ 删除会话即连带删掉全部子拍点。
 *
 * ⚠️ **只存「读数路径」上的子拍**（目标已锁定、timestats 读到且认领到图层）——与 1s
 * 样本（[FrameSampleEntity]）的落库口径一致，通道失效 / 目标未锁定的拍不留行，曲线在
 * 该区段断线。fps=null = 本子拍无有效差分（基线重建拍 / 差分被物理上限守卫拒绝），
 * 画图断线、统计跳过。
 *
 * ⚠️ 短窗的固有物理：250ms 窗最多容纳 refresh×0.25+1 帧，单点 fps 合法上限可到
 * refresh+4（窗口两端各粘一个 vsync 的边界效应，1s 窗是 refresh+1）——曲线轴顶随实测
 * 最大点抬高（FrameDetailActivity.fpsAxisMax）；MAX 汇总仍走 1s 样本口径，见
 * FrameRecordController.finishAndPersist。
 *
 * ⚠️ 录制循环内存里的实体 sessionId=0，落库时由 FrameRecordController.copy 补上。
 */
@Entity(
    tableName = "frame_fps_samples",
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
data class FrameFpsSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 0,
    val timeMillis: Long,
    /**
     * 本子拍（~250ms 差分窗口）的帧率；null = 无有效差分（基线重建 / 守卫拒收）。
     * 0 的语义与 [FrameSampleEntity.fps] 同源：「本子拍没有合成帧」，不是「帧率掉到 0」。
     */
    val fps: Double?,
) {
    companion object {
        fun from(timeMillis: Long, fps: Double?): FrameFpsSampleEntity =
            FrameFpsSampleEntity(timeMillis = timeMillis, fps = fps)
    }
}
