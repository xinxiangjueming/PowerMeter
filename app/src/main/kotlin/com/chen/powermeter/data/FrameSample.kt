package com.chen.powermeter.data

/**
 * 一次帧率采样快照。
 *
 * 字段口径对齐 Kite 采集表（FPS / FrameSpace / CPUClock / 电流 / 电压 / 功率 /
 * 电池温度 / 虚拟温度），数据源分两路：
 * - [fps] / [frameSpaceMs] / [missedFrames] —— `dumpsys SurfaceFlinger --timestats`
 *   的每图层 totalFrames 差分与 presentToPresent 直方图；
 * - [cpuMhz] —— `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq`（8 核实时值）；
 * - [cpuUsagePct] —— `/proc/stat` 首行逐拍差分（全核合计，本应用对 Kite 口径的扩展字段）；
 * - 电流 / 电压 / 功率 / 电池温度 —— `RootPowerReader.read()`，与功率监测**同一条取数链**
 *   （root 机器走 sysfs 节点，Shizuku 机器走 BatteryManagerSource：`CURRENT_NOW` 实时电流 +
 *   粘性广播电压 / 温度，符号口径"正=充电"与 [PowerSample] 完全一致）。
 *   ⚠️ 单位统一**毫口径**：currentMa / voltageMv / powerMw（Kite CSV 表头同源），
 *   RootPowerReader 返回的 V/W 在采样构造时 ×1000，App 显示时再 ÷1000 换回；
 * - [tempVirtualC] —— `/sys/class/thermal/thermal_zone*` 的 CPU 代表温感区（5s 抽稀）。
 *
 * ⚠️ [fps] 为 0 的语义是「本采样周期内没有合成帧」（屏幕静止、熄屏、目标应用不在前台），
 * **不是**「帧率掉到 0」。统计与绘图都按这个约定处理，不要当成异常值剔除或补成上一帧的值。
 */
data class FrameSample(
    val timeMillis: Long,
    /** 该周期内目标图层的合成帧数换算成的每秒帧数 */
    val fps: Double,
    /** 平均帧间隔 ms（presentToPresent 直方图均值）；无帧时为 0 */
    val frameSpaceMs: Double,
    /** 该周期内丢失的帧数（timestats 的 missedFrames 增量） */
    val missedFrames: Int,
    /** 8 个 CPU 核心的当前频率 MHz，按 cpu0..cpu7 顺序；核心数不足时补 0 */
    val cpuMhz: List<Double>,
    /**
     * CPU 全核使用率 %（采样周期内的平均值，/proc/stat 差分）。
     *
     * ⚠️ 可空语义与电量四项一致：null = 该周期没有有效差分（录制刚开始还没建立基线 /
     * 命令失败），详情页显示破折号而不是谎报 0。
     */
    val cpuUsagePct: Double? = null,
    /**
     * 电流 mA：正 = 充电，负 = 放电（与 [PowerSample] 同口径 —— 数据源同为
     * [RootPowerReader]，root 机器 sysfs 与 Shizuku 机器 BatteryManagerSource 都已取反）。
     *
     * ⚠️ **可空 = 该采样周期取数通道不可用**（Shizuku 未绑定且无 su 等）；
     * 空值在详情页显示为破折号，而不是谎报 0。
     */
    val currentMa: Double? = null,
    /**
     * 电压 mV：正 = 充电侧电压（与 [PowerSample] 同源 —— 数据源同为
     * [RootPowerReader]，root 机器 sysfs 与 Shizuku 机器 BatteryManagerSource）。
     *
     * ⚠️ **单位口径：毫（mV/mW/mA）**，与 Kite 采集表（CSV 表头 current[mA] /
     * voltage[mV] / power[mW]）同源 —— [RootPowerReader] 返回的是 V/W，采样构造时
     * ×1000 统一成毫，库里三列单位自洽；App 显示时再 ÷1000 换回 V/W。
     *
     * ⚠️ **可空 = 该采样周期取数通道不可用**（Shizuku 未绑定且无 su 等）；
     * 空值在详情页显示为破折号，而不是谎报 0。
     */
    val voltageMv: Double? = null,
    /** 功率 mW（来源同 [voltageMv]：[RootPowerReader] 的 powerW ×1000；显示时 ÷1000 回 W） */
    val powerMw: Double? = null,
    val tempBatteryC: Double? = null,
    /** 虚拟温度（Kite 的 virTemp）：`thermal_zone*` 里 CPU 代表温感区的温度（5s 抽稀） */
    val tempVirtualC: Double? = null,
    /**
     * GPU 温感区温度 ℃（Temperature 卡的 GPU 线，v7 起采集，5s 抽稀）。
     * 可空 = 机型没有 GPU 温感区 / 旧会话未采集（曲线断线，不画成 0）。
     */
    val gpuTempC: Double? = null,
    /**
     * 电池容量 %（Power 卡的 Capacity 线，v7 起采集；来自功率链 PowerSample.socPct）。
     * 可空 = 通道不可用 / SOC 上报 0 / 旧会话未采集（断线处理）。
     */
    val capacityPct: Double? = null,
    /**
     * GPU 占用率 %（FPS 卡右轴可切换的 GPU(%) 线，v8 起采集；kgsl gpu_busy_percentage）。
     * ⚠️ 实机验证（24031PN0DC / HyperOS V816）：shell 对 /sys/class/kgsl **全目录拒绝**
     * （SELinux，同 power_supply），本机 Shizuku 模式恒 null——该选项按数据自适应隐藏；
     * root 机器或 kgsl 可读的 ROM 可采。可空 = 不可读 / 旧会话（断线处理）。
     */
    val gpuLoadPct: Double? = null,
    /**
     * CPU 逐核使用率 %（v9 起采集，/proc/stat 逐核行差分；下标 = 核心号 cpu0..）。
     * null = 该核本周期无有效差分（基线未建立 / 离线核）/ 旧会话未采集（断线处理）。
     * 详情页 CPU Usage 卡按簇聚合画线（0~1 / 2~4 / 5~6 / 7，8 核机型的典型簇划分）。
     */
    val cpuCoreUsagePct: List<Double?> = emptyList(),
) {

    /** CPU 各核有效频率的均值 MHz（跳过为 0 的下线核，避免拉低均值） */
    val cpuAvgMhz: Double
        get() {
            val active = cpuMhz.filter { it > 0.0 }
            return if (active.isEmpty()) 0.0 else active.average()
        }
}
