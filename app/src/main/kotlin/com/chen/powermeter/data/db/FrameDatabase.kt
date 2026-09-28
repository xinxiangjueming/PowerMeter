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
 *   ⚠️ 2026-09-27 起口径收紧：正装机对比场次的历史数据不能清，结构变更改走**真实
 *   迁移**（v2→v3 fps 子拍表、v3→v4 索引修复、v4→v5 p2pHist 列），破坏性兜底只接
 *   意外版本跳变；
 * - [fallbackToDestructiveMigration] 兜住开发期的意外版本跳变
 *   （帧率数据可弃，整库重建）；
 * - 功率侧的 powermeter.db 走自己的版本链，永不因帧率的改动被触碰。
 */
@Database(
    entities = [FrameSession::class, FrameSampleEntity::class, FrameCpuSampleEntity::class, FrameFpsSampleEntity::class],
    version = 7,
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

        /**
         * v4 → v5（2026-09-28）：frame_samples 加 `p2pHist` 列 —— 本秒**帧间隔分布**
         * （presentToPresent 差集直方图的序列化，`ms:count,ms:count`，见
         * [FrameSampleEntity.p2pHist]）。逐帧卡顿判定 / 卡顿率 / 稳帧指数的数据源。
         *
         * ⚠️ 动机 = 详情页 Jank 卡三档全零（用户报障）：旧口径把 PerfDog 的 **单帧**
         * 83/125ms 绝对门槛套在 **1s 平均帧时间**上 —— 门槛数值没随聚合粒度换算，
         * "整秒均值 > 83ms" 意味着该秒平均帧率 < 12fps，正常录制永不触发。2026-09-28
         * 王者实测一整场 1s 均值 max=13.31ms（门槛的 16%），三档全零，而同场 FPS 最低
         * 62.8、累计 vsync 缺口 ~2257 帧 —— 掉帧真实发生、判定粒度错配。分布落库后
         * 门槛回到**单帧**口径（判定在 UI 层，见 FrameDetailActivity 的逐帧卡顿判定）。
         *
         * 真实迁移（同 2→3 的教训：DDL 必须与实体生成的期望 schema 逐字符核对，
         * 可空列 = `TEXT` 不带 NOT NULL；装机后旧会话该列为 null，UI 走旧口径回退）。
         */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `p2pHist` TEXT")
            }
        }

        /**
         * v5 → v6（2026-09-28）：电流 / 功率两列改**绝对值口径**（用户口径：库与顶部卡片
         * 不出现负号）。历史行按旧"正=充电"符号落库，放电为负 —— 迁移就地把负数行取
         * abs 纠正成正数；新数据在采集侧（FrameRecordController）已直接落正数，本条
         * 只管旧数据。纯数据 UPDATE、不动 schema，Room 只校验结构所以安全；
         * 电压 / 温度等其余列无符号问题，不动。
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `frame_samples` SET `currentMa` = abs(`currentMa`) " +
                        "WHERE `currentMa` IS NOT NULL"
                )
                db.execSQL(
                    "UPDATE `frame_samples` SET `powerMw` = abs(`powerMw`) " +
                        "WHERE `powerMw` IS NOT NULL"
                )
            }
        }

        /**
         * v6 → v7（2026-09-29）：① frame_samples 加 `gpuFreqMhz` 列（GPU 频率 MHz，与
         * gpuLoadPct 同一条命令取回，2026-09-29 加的第三条可选数据线，见
         * FrameRateSource.readGpuLoadFreq）；② frame_sessions 加 `fpsSource` 列
         * （本场生效的帧率采样源 sf_latency / task_fps / timestats，帧间隔口径的依据，
         * 见 FrameSession.fpsSource 与 FpsAlgorithm）。
         *
         * 真实迁移（装机对比批次的历史不能清）：可空列不带 NOT NULL，ALTER TABLE
         * ADD COLUMN；旧会话两列为 null，UI 走缺测/回退口径。DDL 与实体生成 schema
         * 的逐字符核对记录在编译后核对 FrameDatabase_Impl.kt（批次三十七的教训）。
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `gpuFreqMhz` REAL")
                db.execSQL("ALTER TABLE `frame_sessions` ADD COLUMN `fpsSource` TEXT")
            }
        }

        fun getInstance(context: Context): FrameDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FrameDatabase::class.java,
                    "frame.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
