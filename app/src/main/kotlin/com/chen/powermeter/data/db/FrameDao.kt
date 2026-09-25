package com.chen.powermeter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 帧率会话与样本的读写。
 *
 * 与 [SampleDao] 的关键差别：**这里没有「导出即删」的语义**。帧率记录是用户资产，
 * 删除只发生在用户显式操作时（帧率监测页历史列表的管理模式，2026-09-25 接入）。
 */
@Dao
interface FrameDao {

    @Insert
    suspend fun insertSession(session: FrameSession): Long

    /** 唯一索引 (sessionId, timeMillis) + REPLACE：重复 flush 同一批样本不会产生重复行 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<FrameSampleEntity>)

    /** 历史列表：按开始时间倒序（最近采集的排在最前） */
    @Query("SELECT * FROM frame_sessions ORDER BY startTime DESC")
    suspend fun allSessions(): List<FrameSession>

    @Query("SELECT * FROM frame_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun session(sessionId: Long): FrameSession?

    @Query("SELECT * FROM frame_samples WHERE sessionId = :sessionId ORDER BY timeMillis ASC")
    suspend fun samples(sessionId: Long): List<FrameSampleEntity>

    @Query("SELECT COUNT(*) FROM frame_samples WHERE sessionId = :sessionId")
    suspend fun countSamples(sessionId: Long): Int

    /** CPU 快样批量落库（250ms 级，一场录制可达数千行，单事务插入） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCpuSamples(samples: List<FrameCpuSampleEntity>)

    @Query("SELECT * FROM frame_cpu_samples WHERE sessionId = :sessionId ORDER BY timeMillis ASC")
    suspend fun cpuSamples(sessionId: Long): List<FrameCpuSampleEntity>

    /** 删除会话（frame_samples 走外键级联删除） */
    @Query("DELETE FROM frame_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM frame_sessions")
    suspend fun deleteAll()
}
