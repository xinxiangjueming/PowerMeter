package com.chen.powermeter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 帧率监测**独立**的 Room 库（2026-09-25 从 powermeter.db 拆出）。
 *
 * 为什么拆：帧率侧处于开发期，schema 随时改、版本随时重置，而功率侧的历史数据必须
 * 长期保留 —— 同一个库文件里两者的诉求互相冲突（破坏性重建会连功率表一起清）。
 * 拆开后 frame.db 与 powermeter.db 互不相干：
 * - 本库不保留迁移历史（用户口径 2026-09-25：帧率库只保留当前 schema），
 *   结构变更直接升版本、整库重建 —— v2（2026-09-25）新增 CPU 快样表
 *   frame_cpu_samples（250ms 级，详情页 CPU 两卡的密集数据源，见
 *   [FrameCpuSampleEntity]），装机后帧率历史清空一次；
 * - [fallbackToDestructiveMigration] 兜住开发期的每次结构变更与版本重置
 *   （帧率数据可弃，整库重建）；
 * - 功率侧的 powermeter.db 走自己的版本链，永不因帧率的改动被触碰。
 */
@Database(
    entities = [FrameSession::class, FrameSampleEntity::class, FrameCpuSampleEntity::class],
    version = 2,
    exportSchema = false
)
abstract class FrameDatabase : RoomDatabase() {

    abstract fun frameDao(): FrameDao

    companion object {
        @Volatile
        private var INSTANCE: FrameDatabase? = null

        fun getInstance(context: Context): FrameDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FrameDatabase::class.java,
                    "frame.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
