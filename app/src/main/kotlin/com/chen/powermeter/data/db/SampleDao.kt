package com.chen.powermeter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SampleDao {

    @Insert
    suspend fun insertSession(session: PowerSession): Long

    /** 唯一索引 (sessionId, timeMillis) + REPLACE：重复 flush 同一批样本不会产生重复行 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<PowerSampleEntity>)

    @Query("UPDATE power_sessions SET endTime = :endTime WHERE id = :sessionId")
    suspend fun updateEndTime(sessionId: Long, endTime: Long)

    /** 已落库样本数（COUNT 走索引；导出与收尾时各查一次，不在每次 flush 上查） */
    @Query("SELECT COUNT(*) FROM power_samples WHERE sessionId = :sessionId")
    suspend fun countSamples(sessionId: Long): Int

    /**
     * 分页取样本（导出用）：整会话可能上万行，一次性 load 进内存有 OOM 风险，
     * 故按 offset 分页流式写出。SQL 层已按 timeMillis 升序。
     */
    @Query(
        "SELECT * FROM power_samples WHERE sessionId = :sessionId " +
            "ORDER BY timeMillis ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun samplesPage(sessionId: Long, limit: Int, offset: Int): List<PowerSampleEntity>

    @Query("SELECT * FROM power_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun session(sessionId: Long): PowerSession?

    @Query("SELECT * FROM power_sessions ORDER BY id DESC LIMIT 1")
    suspend fun latestSession(): PowerSession?

    /** 删除会话（power_samples 走外键级联删除） */
    @Query("DELETE FROM power_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    /** 清空全部会话 */
    @Query("DELETE FROM power_sessions")
    suspend fun deleteAllSessions()

    /**
     * 只保留指定会话，其余全删（冷启动清理）。
     *
     * 为什么不直接全删：停止采样后进程被杀是常态（HyperOS 回收很快），用户往往还没来得及
     * 点导出。约定是**保留最近一次未导出的会话**，直到他导出、或开始新一场采样。
     * 保留 `id` 最大的那一条即可 —— 会话是顺序创建的，id 即时间序。
     */
    @Query("DELETE FROM power_sessions WHERE id != :keepId")
    suspend fun deleteAllExcept(keepId: Long)
}
