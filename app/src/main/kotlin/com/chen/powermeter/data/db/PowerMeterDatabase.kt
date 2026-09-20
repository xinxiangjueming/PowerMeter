package com.chen.powermeter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 采样数据库 —— Room 默认建在应用私有目录 `/data/data/<pkg>/databases/powermeter.db`，
 * 天然满足「数据只存私有目录、不需要任何存储权限」。
 */
@Database(
    entities = [PowerSession::class, PowerSampleEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PowerMeterDatabase : RoomDatabase() {

    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var INSTANCE: PowerMeterDatabase? = null

        fun getInstance(context: Context): PowerMeterDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PowerMeterDatabase::class.java,
                    "powermeter.db"
                ).build().also { INSTANCE = it }
            }
    }
}
