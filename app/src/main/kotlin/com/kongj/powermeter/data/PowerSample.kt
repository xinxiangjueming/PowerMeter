package com.kongj.powermeter.data

/**
 * 一次采样快照。
 *
 * 单位约定（与内核节点原始单位无关，此处统一为应用层单位）：
 * - 电压：V（伏特）
 * - 电流：mA（毫安），**正 = 充电（流入电池），负 = 放电**
 * - 功率：W（瓦特），符号跟随电流
 * - 温度：℃（摄氏度）
 */
data class PowerSample(
    val timeMillis: Long,
    /** 电池端电压 V */
    val voltageV: Double,
    /** 开路电压 V（OCV，空载电压，可反映电池内阻压降） */
    val voltageOcvV: Double,
    /** 电流 mA：正 = 充电，负 = 放电 */
    val currentMa: Double,
    /** 燃料计独立测得的电流 mA（正 = 充电），用于交叉校验 */
    val fgCurrentMa: Double?,
    /** 功率 W：正 = 充电，负 = 放电（由 voltage × current 计算，非读取 power_now） */
    val powerW: Double,
    /** 电池温度 ℃ */
    val tempBatteryC: Double,
    /** Type-C 接口温度 ℃ */
    val tempUsbC: Double?,
    /** 充电 IC 温度 ℃ */
    val tempChargerC: Double?,
    /** 主 PMIC 温度 ℃ */
    val tempPmicC: Double?,
    /** 系统 SOC % */
    val socPct: Int,
    /** Charging / Discharging / Full / Not charging */
    val status: String,
    /** Fast / Trickle / None */
    val chargeType: String,
    /** 剩余容量 mAh（来自燃料计 fg1_rm，权威） */
    val remainingMah: Double?,
    /** 当前满充容量 mAh */
    val fullMah: Double?,
    /** USB 输入电压 V */
    val usbVoltageV: Double?,
    /** USB 输入限流 mA（注意：多数机型上报的是限流上限，非实测电流） */
    val usbCurrentLimitMa: Double?,
) {
    val isCharging: Boolean
        get() = status.equals("Charging", ignoreCase = true) ||
            (status.equals("Full", ignoreCase = true) && currentMa >= 0.0)
}

/** 会话统计量 */
data class SessionStats(
    val sampleCount: Int = 0,
    val durationMs: Long = 0L,
    val avgPowerW: Double = 0.0,
    val peakChargeW: Double = 0.0,
    val peakDischargeW: Double = 0.0,
    val minVoltageV: Double = 0.0,
    val maxVoltageV: Double = 0.0,
    val maxTempC: Double = 0.0,
    /** 累计充入电量 mAh */
    val chargedMah: Double = 0.0,
    /** 累计放出电量 mAh */
    val dischargedMah: Double = 0.0,
    /** 累计充入能量 Wh */
    val chargedWh: Double = 0.0,
    /** 累计放出能量 Wh */
    val dischargedWh: Double = 0.0,
)
