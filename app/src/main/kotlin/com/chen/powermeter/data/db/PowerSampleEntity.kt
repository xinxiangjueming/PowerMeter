package com.chen.powermeter.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chen.powermeter.data.PowerSample

/**
 * 采样样本行 —— 字段与 [PowerSample] 一一对应（含空值语义），落库不做任何降精度。
 *
 * 唯一索引 (sessionId, timeMillis) 的作用：**让重复 flush 幂等**。
 * 10s 定时刷盘与「停止采样时的收尾刷盘」可能写入同一批样本（定时器与收尾几乎同时发生时），
 * 配合 `OnConflictStrategy.REPLACE` 后同一毫秒只保留一行，不会把尾巴写重。
 * 采样间隔最小 500ms，同一会话内 timeMillis 天然不重复。
 *
 * 外键 `ON DELETE CASCADE`：删除会话即连带删掉全部样本，不需要手写两条 DELETE。
 */
@Entity(
    tableName = "power_samples",
    foreignKeys = [
        ForeignKey(
            entity = PowerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        // 最左前缀 sessionId 已覆盖「按会话查询 / 分页 / 级联删除」三类访问，
        // 不为 timeMillis 单独建索引（纯写放大）
        Index(value = ["sessionId", "timeMillis"], unique = true)
    ]
)
data class PowerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMillis: Long,
    val voltageV: Double,
    val voltageOcvV: Double,
    /** 正 = 充电，负 = 放电（与 PowerSample 同口径） */
    val currentMa: Double,
    val fgCurrentMa: Double?,
    val powerW: Double,
    val tempBatteryC: Double,
    val tempUsbC: Double?,
    val tempChargerC: Double?,
    val tempPmicC: Double?,
    val socPct: Int,
    val status: String,
    val chargeType: String,
    val remainingMah: Double?,
    val fullMah: Double?,
    val usbVoltageV: Double?,
    val usbCurrentLimitMa: Double?,
) {
    fun toPowerSample(): PowerSample = PowerSample(
        timeMillis = timeMillis,
        voltageV = voltageV,
        voltageOcvV = voltageOcvV,
        currentMa = currentMa,
        fgCurrentMa = fgCurrentMa,
        powerW = powerW,
        tempBatteryC = tempBatteryC,
        tempUsbC = tempUsbC,
        tempChargerC = tempChargerC,
        tempPmicC = tempPmicC,
        socPct = socPct,
        status = status,
        chargeType = chargeType,
        remainingMah = remainingMah,
        fullMah = fullMah,
        usbVoltageV = usbVoltageV,
        usbCurrentLimitMa = usbCurrentLimitMa,
    )

    companion object {
        fun from(sessionId: Long, s: PowerSample): PowerSampleEntity = PowerSampleEntity(
            sessionId = sessionId,
            timeMillis = s.timeMillis,
            voltageV = s.voltageV,
            voltageOcvV = s.voltageOcvV,
            currentMa = s.currentMa,
            fgCurrentMa = s.fgCurrentMa,
            powerW = s.powerW,
            tempBatteryC = s.tempBatteryC,
            tempUsbC = s.tempUsbC,
            tempChargerC = s.tempChargerC,
            tempPmicC = s.tempPmicC,
            socPct = s.socPct,
            status = s.status,
            chargeType = s.chargeType,
            remainingMah = s.remainingMah,
            fullMah = s.fullMah,
            usbVoltageV = s.usbVoltageV,
            usbCurrentLimitMa = s.usbCurrentLimitMa,
        )
    }
}
