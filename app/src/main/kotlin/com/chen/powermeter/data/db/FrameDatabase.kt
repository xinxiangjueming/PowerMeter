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
    entities = [FrameSession::class, FrameSampleEntity::class, FrameCpuSampleEntity::class, FrameFpsSampleEntity::class],
    version = 4,
    exportSchema = false
)
abstract class FrameDatabase : RoomDatabase() {

    abstract fun frameDao(): FrameDao

    companion object {
        @Volatile
        private var INSTANCE: FrameDatabase? = null

        /**
         * v2 → v3（2026-09-27）：新增帧率子拍表 frame_fps_samples（250ms 级差分点，FPS
         * 曲线 / MIN / 低分位的密集数据源，见 [FrameFpsSampleEntity]）。用**真实迁移**
         * 而不是吃 fallbackToDestructiveMigration：装机批次正在对比历史场次（12:54 王者
         * vs Scene/Kite），帧率历史这几场还有用，不该被清。
         *
         * ⚠️⚠️ **第一版迁移的事故（17:21 装机）**：索引写成普通 `CREATE INDEX`，而实体
         * 声明 `unique = true` —— Room 校验期望 `CREATE UNIQUE INDEX`，不匹配 → 数据库
         * 打开抛 "Migration didn't properly handle"，所有读写失败：列表读不出（看起来
         * "旧数据全没了"）、落库全进 catch（"新数据不显示"）。**迁移事务本身回滚了，
         * 数据文件完好** —— 修好索引重装即恢复。教训：Room 迁移的 DDL 必须与实体生成
         * 的期望 schema **逐字符核对**（app/build/generated/ksp/**/FrameDatabase_Impl.kt
         * 里有权威 DDL），尤其索引的 UNIQUE 属性。
         */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `frame_fps_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`timeMillis` INTEGER NOT NULL, " +
                        "`fps` REAL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `frame_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_frame_fps_samples_sessionId_timeMillis` " +
                        "ON `frame_fps_samples` (`sessionId`, `timeMillis`)"
                )
            }
        }

        /**
         * v3 → v4（2026-09-27）：修复 v3 迁移漏写 UNIQUE 的索引。若某台设备上坏结构
         * 已随事务提交（v3 落盘但校验永远失败），单改 2→3 救不回来 —— 本迁移把索引
         * DROP 后按实体期望重建；对索引本来就正确的库是无害幂等操作。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_frame_fps_samples_sessionId_timeMillis`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_frame_fps_samples_sessionId_timeMillis` " +
                        "ON `frame_fps_samples` (`sessionId`, `timeMillis`)"
                )
            }
        }

        fun getInstance(context: Context): FrameDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FrameDatabase::class.java,
                    "frame.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
