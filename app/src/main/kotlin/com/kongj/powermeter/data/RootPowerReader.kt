package com.kongj.powermeter.data

import java.util.concurrent.TimeUnit

/**
 * 通过 root 读取内核底层电量节点。
 *
 * 数据来源（本机型 Xiaomi 22081212C / SM8475 taro 实测）：
 * 1. /sys/class/power_supply/battery —— 标准内核电量节点（电压、电流、温度、容量）
 * 2. /sys/class/qcom-battery         —— 高通私有节点（燃料计 FG 寄存器、接口温度、快充协议）
 * 3. /sys/class/power_supply/usb     —— USB 输入侧电压与限流
 * 4. /sys/class/thermal/thermal_zone* —— 温感区（电池口、Type-C、充电 IC、PMIC）
 *
 * 两个实测陷阱（已验证，直接影响正确性）：
 * - battery/power_now 与 power_avg 在本机为**恒定值**（10000000 / 5000000），
 *   是充电档位上限而非实测功率，**不可用**；功率必须由 voltage_now × current_now 计算。
 * - battery/charge_counter 的单位在本机是 **mAh 而非标准 µAh**（实测 4454，
 *   而燃料计 fg1_rm = 4457000 µAh），故容量优先取 fg1_rm。
 */
object RootPowerReader {

    private const val PS_BATTERY = "/sys/class/power_supply/battery"
    private const val PS_USB = "/sys/class/power_supply/usb"
    private const val QCOM_BATTERY = "/sys/class/qcom-battery"
    private const val THERMAL_DIR = "/sys/class/thermal"

    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/sbin/su", "/system/xbin/su")

    private val BATTERY_FILES = listOf(
        "capacity", "status", "charge_type", "health", "technology", "model_name",
        "voltage_now", "voltage_ocv", "current_now", "temp",
        "charge_full", "charge_full_design", "charge_counter", "cycle_count",
    )

    private val QCOM_FILES = listOf(
        "fg1_ai", "fg1_rm", "fg1_fcc", "fg1_soh", "fg1_cycle", "connector_temp",
        "power_max", "real_type", "usb_real_type", "typec_mode",
    )

    private val USB_FILES = listOf("voltage_now", "current_now", "online", "type")

    /** 关注的温感区类型 → 语义名 */
    private val THERMAL_TARGETS = setOf("battery", "usb", "charger_therm0", "pm8350c_tz", "pm8350b_tz")

    @Volatile
    private var thermalIndex: Map<String, Int> = emptyMap()

    @Volatile
    private var rootChecked = false

    @Volatile
    var rootAvailable = false
        private set

    /** 最近一次检查的失败原因（用于 UI 提示） */
    @Volatile
    var lastError: String? = null
        private set

    /** 是否有 root 权限；首次调用会探测并建立温感区索引。必须在后台线程调用。 */
    fun checkRoot(): Boolean {
        if (rootChecked) return rootAvailable
        val out = exec("id -u")
        rootAvailable = out?.trim()?.lineSequence()?.lastOrNull()?.trim() == "0"
        rootChecked = true
        lastError = if (rootAvailable) null else "无法获取 root 权限（su 执行失败或未授权）"
        if (rootAvailable) thermalIndex = probeThermal()
        return rootAvailable
    }

    /** 重置探测缓存（例如 root 授权状态变化后） */
    fun reset() {
        rootChecked = false
        rootAvailable = false
        thermalIndex = emptyMap()
    }

    /** 探测温感区 type → zone 编号映射 */
    private fun probeThermal(): Map<String, Int> {
        val cmd = """
            for d in $THERMAL_DIR/thermal_zone*; do printf '%s|%s\n' "$(cat "${'$'}d/type" 2>/dev/null|tr -d '\n')" "${'$'}{d##*_zone}"; done
        """.trimIndent()
        val out = exec(cmd) ?: return emptyMap()
        val map = HashMap<String, Int>()
        out.lineSequence().forEach { line ->
            val parts = line.split("|")
            if (parts.size == 2) {
                val type = parts[0].trim()
                val index = parts[1].trim().toIntOrNull()
                if (index != null && type in THERMAL_TARGETS) map[type] = index
            }
        }
        return map
    }

    /** 读取一次完整快照。必须在后台线程调用。 */
    fun read(): PowerSample? {
        if (!checkRoot()) return null

        val tz = thermalIndex
        val thermalIds = tz.values.joinToString(" ")
        val thermalSection = if (thermalIds.isBlank()) "" else """
            for i in $thermalIds; do printf 'tz%s=' "${'$'}i"; cat "$THERMAL_DIR/thermal_zone${'$'}i/temp" 2>/dev/null|tr -d '\n'; printf '\n'; done;
        """.trimIndent()

        val cmd = buildString {
            append(section(PS_BATTERY, "b_", BATTERY_FILES))
            append(" ")
            append(section(QCOM_BATTERY, "", QCOM_FILES))
            append(" ")
            append(section(PS_USB, "u_", USB_FILES))
            if (thermalSection.isNotBlank()) {
                append(" ")
                append(thermalSection)
            }
        }

        val out = exec(cmd) ?: run {
            lastError = "读取内核节点失败"
            return null
        }
        val sample = parse(out, tz)
        if (sample == null) lastError = "内核节点数据解析失败（缺少 voltage_now）"
        return sample
    }

    /** 生成一段 shell：遍历目录下指定文件，输出 `前缀+文件名=值` */
    private fun section(dir: String, prefix: String, files: List<String>): String = """
        for f in ${files.joinToString(" ")}; do printf '$prefix%s=' "${'$'}f"; cat "$dir/${'$'}f" 2>/dev/null|tr -d '\n'; printf '\n'; done;
    """.trimIndent()

    private fun parse(out: String, tz: Map<String, Int>): PowerSample? {
        val m = HashMap<String, String>()
        out.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }

        val voltageUv = m.dbl("b_voltage_now") ?: return null
        val voltageV = voltageUv / 1_000_000.0

        // 内核约定：current_now 负 = 充电（本机 status=Charging 时实测为负值，已验证）。
        // 应用层统一为正 = 充电，故取反。
        val currentUa = m.dbl("b_current_now") ?: 0.0
        val currentMa = -currentUa / 1_000.0
        val powerW = voltageV * currentMa / 1_000.0

        // 温感区：thermal_zone 单位为毫摄氏度
        val indexToName = tz.entries.associate { (k, v) -> v to k }
        val temps = HashMap<String, Double>()
        m.forEach { (key, value) ->
            if (key.startsWith("tz")) {
                val idx = key.removePrefix("tz").toIntOrNull() ?: return@forEach
                val name = indexToName[idx] ?: return@forEach
                val t = value.toDoubleOrNull() ?: return@forEach
                temps[name] = t / 1_000.0
            }
        }

        // 剩余容量：优先燃料计 fg1_rm（µAh），回退到 charge_counter 并做 1000 倍量级校准
        val fgRmUah = m.dbl("fg1_rm")
        val designUah = m.dbl("b_charge_full_design")
        val chargeCounter = m.dbl("b_charge_counter")
        val remainingMah = when {
            fgRmUah != null -> fgRmUah / 1_000.0
            chargeCounter != null && designUah != null ->
                if (chargeCounter * 1_000.0 <= designUah * 1.5) chargeCounter else chargeCounter / 1_000.0
            chargeCounter != null -> chargeCounter / 1_000.0
            else -> null
        }

        val fgAiUa = m.dbl("fg1_ai")

        return PowerSample(
            timeMillis = System.currentTimeMillis(),
            voltageV = voltageV,
            voltageOcvV = (m.dbl("b_voltage_ocv") ?: voltageUv) / 1_000_000.0,
            currentMa = currentMa,
            fgCurrentMa = fgAiUa?.let { -it / 1_000.0 },
            powerW = powerW,
            tempBatteryC = (m.dbl("b_temp") ?: 0.0) / 10.0,
            tempUsbC = temps["usb"] ?: m.dbl("connector_temp")?.let { it / 10.0 },
            tempChargerC = temps["charger_therm0"],
            tempPmicC = temps["pm8350c_tz"] ?: temps["pm8350b_tz"],
            socPct = m.int("b_capacity") ?: 0,
            status = m["b_status"].orEmpty(),
            chargeType = m["b_charge_type"].orEmpty(),
            remainingMah = remainingMah,
            fullMah = m.dbl("b_charge_full")?.let { it / 1_000.0 },
            usbVoltageV = m.dbl("u_voltage_now")?.let { it / 1_000_000.0 },
            usbCurrentLimitMa = m.dbl("u_current_now")?.let { it / 1_000.0 },
        )
    }

    /** 电池静态信息（健康度、循环次数、型号等），变化缓慢，按需调用 */
    fun readBatteryInfo(): BatteryInfo? {
        if (!checkRoot()) return null
        val cmd = """
            for f in fg1_soh fg1_cycle fg1_fcc fg1_qmax model_name; do printf '%s=' "${'$'}f"; cat "$QCOM_BATTERY/${'$'}f" 2>/dev/null|tr -d '\n'; printf '\n'; done;
            for f in model_name technology health cycle_count charge_full charge_full_design; do printf 'b_%s=' "${'$'}f"; cat "$PS_BATTERY/${'$'}f" 2>/dev/null|tr -d '\n'; printf '\n'; done;
        """.trimIndent()
        val out = exec(cmd) ?: return null
        val m = HashMap<String, String>()
        out.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return BatteryInfo(
            model = m["model_name"] ?: m["b_model_name"],
            technology = m["b_technology"],
            health = m["b_health"],
            sohPct = m.int("fg1_soh"),
            cycleCount = m.int("fg1_cycle") ?: m.int("b_cycle_count"),
            fullMah = m.dbl("b_charge_full")?.let { it / 1_000.0 },
            designMah = m.dbl("b_charge_full_design")?.let { it / 1_000.0 },
            maxChargeW = m.dbl("power_max"),
        )
    }

    private fun HashMap<String, String>.dbl(key: String): Double? = this[key]?.toDoubleOrNull()

    private fun HashMap<String, String>.int(key: String): Int? = this[key]?.toIntOrNull()

    private fun exec(cmd: String, timeoutMs: Long = 5_000L): String? {
        for (su in SU_CANDIDATES) {
            var process: Process? = null
            try {
                process = ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start()
                val buffer = StringBuilder()
                val reader = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(buffer) { buffer.append(line).append('\n') }
                        }
                    }
                }.apply { isDaemon = true }
                reader.start()
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                reader.join(500)
                if (!finished) {
                    process.destroyForcibly()
                    continue
                }
                val out = synchronized(buffer) { buffer.toString() }
                if (out.isNotBlank()) return out
            } catch (_: Exception) {
                process?.destroyForcibly()
            }
        }
        return null
    }
}

/** 电池静态信息 */
data class BatteryInfo(
    val model: String?,
    val technology: String?,
    val health: String?,
    val sohPct: Int?,
    val cycleCount: Int?,
    val fullMah: Double?,
    val designMah: Double?,
    val maxChargeW: Double?,
)
