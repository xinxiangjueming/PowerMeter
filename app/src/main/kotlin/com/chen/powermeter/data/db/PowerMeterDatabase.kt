package com.chen.powermeter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 功率监测的 Room 库（powermeter.db）。
 *
 * ⚠️ 2026-09-25 拆库：帧率监测的两张表（frame_sessions / frame_samples）迁出到独立的
 * [FrameDatabase]（frame.db）——帧率侧开发期 schema 随时重置，而功率侧的历史数据必须
 * 长期保留（用户口径：**功率页的 Room 数据不要受影响**），同一文件里两者互相牵连。
 * 拆库后本库只声明功率两表，帧率侧的任何改动都不再触碰功率数据。
 *
 * 迁移链 v1→v10 原样保留：老安装沿链升级，功率两表自 v1 起结构未变，行数据全程不动；
 * 中间各条涉及帧率表的结构变更照常执行（此刻帧率表还在），到 v9→v10 统一删除。
 * ⚠️ **刻意不设** fallbackToDestructiveMigration：功率数据宁可崩在迁移缺失上，
 * 也不能静默清库。
 */
@Database(
    entities = [
        PowerSession::class,
        PowerSampleEntity::class,
    ],
    version = 10,
    exportSchema = false
)
abstract class PowerMeterDatabase : RoomDatabase() {

    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var INSTANCE: PowerMeterDatabase? = null

        /**
         * v1 → v2：新增帧率监测的两张表（历史条目，2026-09-25 拆库后这两表已迁出，
         * 建表 SQL 保留只为让 v1 安装沿链升级；v9→v10 会把帧率表统一删除）。
         *
         * ⚠️ 建表 SQL 必须与 Room 由实体推导出的 schema **逐字一致**（列名、类型、NOT NULL、
         * 外键、索引名 `index_<表>_<列1>_<列2>`），否则 Room 打开时会抛
         * "Migration didn't properly handle"。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `frame_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startTime` INTEGER NOT NULL, " +
                        "`endTime` INTEGER NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`appLabel` TEXT NOT NULL, " +
                        "`refreshRateHz` INTEGER NOT NULL, " +
                        "`sampleCount` INTEGER NOT NULL, " +
                        "`avgFps` REAL NOT NULL, " +
                        "`minFps` REAL NOT NULL, " +
                        "`maxFps` REAL NOT NULL, " +
                        "`avgFrameSpaceMs` REAL NOT NULL, " +
                        "`jankCount` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `frame_samples` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`timeMillis` INTEGER NOT NULL, " +
                        "`fps` REAL NOT NULL, " +
                        "`frameSpaceMs` REAL NOT NULL, " +
                        "`missedFrames` INTEGER NOT NULL, " +
                        "`cpu0Mhz` REAL NOT NULL, " +
                        "`cpu1Mhz` REAL NOT NULL, " +
                        "`cpu2Mhz` REAL NOT NULL, " +
                        "`cpu3Mhz` REAL NOT NULL, " +
                        "`cpu4Mhz` REAL NOT NULL, " +
                        "`cpu5Mhz` REAL NOT NULL, " +
                        "`cpu6Mhz` REAL NOT NULL, " +
                        "`cpu7Mhz` REAL NOT NULL, " +
                        // 电量类字段可空：帧率录制首批只采帧率 + CPU 频率，
                        // 这几列未采集时为 NULL（详情页显示破折号，不谎报 0）
                        "`currentMa` REAL, " +
                        "`voltageV` REAL, " +
                        "`powerW` REAL, " +
                        "`tempBatteryC` REAL, " +
                        "`tempVirtualC` REAL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `frame_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_frame_samples_sessionId_timeMillis` " +
                        "ON `frame_samples` (`sessionId`, `timeMillis`)"
                )
            }
        }

        /**
         * v2 → v3：`frame_samples` 的电量五列 NOT NULL → 可空（对应 [FrameSampleEntity]
         * 的 `Double?` 字段）。（历史条目，帧率表 2026-09-25 已迁出，链路保留理由同 [MIGRATION_1_2]。）
         *
         * ⚠️ **为什么必须升版本**（2026-09-22 真机事故，"结束录制后历史里根本没有记录"）：
         * 电量四项接入时把实体改成了可空、同步改了建表 SQL，但**版本号没动** —— 老库
         * identity hash 不同永远不会重跑迁移，Room 打开时直接抛
         * "Room cannot verify the data integrity"，读写双双失效且只在 logcat 留痕。
         *
         * SQLite 不能 ALTER 列的 NULL 约束 → 按惯例整表重建拷贝。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `frame_samples_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`timeMillis` INTEGER NOT NULL, " +
                        "`fps` REAL NOT NULL, " +
                        "`frameSpaceMs` REAL NOT NULL, " +
                        "`missedFrames` INTEGER NOT NULL, " +
                        "`cpu0Mhz` REAL NOT NULL, " +
                        "`cpu1Mhz` REAL NOT NULL, " +
                        "`cpu2Mhz` REAL NOT NULL, " +
                        "`cpu3Mhz` REAL NOT NULL, " +
                        "`cpu4Mhz` REAL NOT NULL, " +
                        "`cpu5Mhz` REAL NOT NULL, " +
                        "`cpu6Mhz` REAL NOT NULL, " +
                        "`cpu7Mhz` REAL NOT NULL, " +
                        "`currentMa` REAL, " +
                        "`voltageV` REAL, " +
                        "`powerW` REAL, " +
                        "`tempBatteryC` REAL, " +
                        "`tempVirtualC` REAL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `frame_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `frame_samples_new` (" +
                        "`id`, `sessionId`, `timeMillis`, `fps`, `frameSpaceMs`, `missedFrames`, " +
                        "`cpu0Mhz`, `cpu1Mhz`, `cpu2Mhz`, `cpu3Mhz`, " +
                        "`cpu4Mhz`, `cpu5Mhz`, `cpu6Mhz`, `cpu7Mhz`, " +
                        "`currentMa`, `voltageV`, `powerW`, `tempBatteryC`, `tempVirtualC`) " +
                        "SELECT " +
                        "`id`, `sessionId`, `timeMillis`, `fps`, `frameSpaceMs`, `missedFrames`, " +
                        "`cpu0Mhz`, `cpu1Mhz`, `cpu2Mhz`, `cpu3Mhz`, " +
                        "`cpu4Mhz`, `cpu5Mhz`, `cpu6Mhz`, `cpu7Mhz`, " +
                        "`currentMa`, `voltageV`, `powerW`, `tempBatteryC`, `tempVirtualC` " +
                        "FROM `frame_samples`"
                )
                db.execSQL("DROP TABLE `frame_samples`")
                db.execSQL("ALTER TABLE `frame_samples_new` RENAME TO `frame_samples`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_frame_samples_sessionId_timeMillis` " +
                        "ON `frame_samples` (`sessionId`, `timeMillis`)"
                )
            }
        }

        /**
         * v3 → v4：`frame_samples` 新增 CPU 使用率列（2026-09-22，/proc/stat 差分采样）。
         * （历史条目，理由同 [MIGRATION_1_2]。）可空列、无默认值 → 直接 ADD COLUMN。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpuUsagePct` REAL")
            }
        }

        /**
         * v4 → v5：`frame_sessions` 新增 1% Low / 5% Low 帧率两列（2026-09-25）。
         * （历史条目，理由同 [MIGRATION_1_2]。）
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_sessions` ADD COLUMN `lowFps1` REAL")
                db.execSQL("ALTER TABLE `frame_sessions` ADD COLUMN `lowFps5` REAL")
            }
        }

        /**
         * v5 → v6：`frame_samples` 的电压 / 功率统一**毫口径**（2026-09-25）。
         * （历史条目，理由同 [MIGRATION_1_2]。）旧列 `voltageV` / `powerW` 改名为
         * `voltageMv` / `powerMw` 并把老数据 ×1000。RENAME COLUMN 需 SQLite 3.25+
         * —— 本项目 minSdk 30（Android 11 自带 3.28+），安全。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` RENAME COLUMN `voltageV` TO `voltageMv`")
                db.execSQL("ALTER TABLE `frame_samples` RENAME COLUMN `powerW` TO `powerMw`")
                db.execSQL(
                    "UPDATE `frame_samples` SET `voltageMv` = `voltageMv` * 1000 " +
                        "WHERE `voltageMv` IS NOT NULL"
                )
                db.execSQL(
                    "UPDATE `frame_samples` SET `powerMw` = `powerMw` * 1000 " +
                        "WHERE `powerMw` IS NOT NULL"
                )
            }
        }

        /**
         * v6 → v7：`frame_samples` 新增 GPU 温感区温度 / 电池容量 % 两列（2026-09-25）。
         * （历史条目，理由同 [MIGRATION_1_2]。）
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `gpuTempC` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `capacityPct` REAL")
            }
        }

        /**
         * v7 → v8：`frame_samples` 新增 GPU 占用率列（2026-09-25，kgsl gpu_busy_percentage）。
         * （历史条目，理由同 [MIGRATION_1_2]。）
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `gpuLoadPct` REAL")
            }
        }

        /**
         * v8 → v9：`frame_samples` 新增 8 列逐核 CPU 使用率（2026-09-25）。
         * 该版本从未随安装包发布（v9 当天即拆库），加的列在 v9→v10 随帧率表一起删除；
         * 保留本条只为让 v8 安装沿链升到 v9 —— 帧率表此刻还在，ADD COLUMN 后再删。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu0UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu1UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu2UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu3UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu4UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu5UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu6UsagePct` REAL")
                db.execSQL("ALTER TABLE `frame_samples` ADD COLUMN `cpu7UsagePct` REAL")
            }
        }

        /**
         * v9 → v10：**帧率两表迁出**（2026-09-25 拆库，见类 KDoc）。
         *
         * frame_sessions / frame_samples 移到独立的 FrameDatabase（frame.db），本库只保留
         * 功率两表。功率两表沿链升到此处，行数据全程原样不动；本条只删帧率表及其索引
         * （索引随表删除，无需单独 DROP）。
         * ⚠️ 必须升版本：拆库改了实体集（identity hash 变化），只有版本变化才会走迁移、
         * 在迁移结束时重盖 room_master_table 的 identity hash，否则同版本下 hash 校验直接炸。
         * 对新装机无副作用：IF EXISTS，帧率表本就不存在。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `frame_samples`")
                db.execSQL("DROP TABLE IF EXISTS `frame_sessions`")
            }
        }

        fun getInstance(context: Context): PowerMeterDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PowerMeterDatabase::class.java,
                    "powermeter.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10,
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
