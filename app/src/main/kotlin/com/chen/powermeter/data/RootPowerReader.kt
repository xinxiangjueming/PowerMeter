package com.chen.powermeter.data

import com.chen.powermeter.util.ShizukuHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * 读取内核底层电量节点。
 *
 * 取数权限有**两条通道**（2026-09-21 起）：
 * 1. **Shizuku**（优先）—— 非 root 机器经 adb / 无线调试授权，以 **shell（uid 2000）** 身份执行命令。
 *    ⚠️ 真机实测（24031PN0DC / Android 16 / HyperOS V816）：该 ROM 的 SELinux 拦截 shell 读
 *    全部 power_supply / qcom-battery 节点（目录 mode 正常仍被拒），**此通道在该机型测不到功率**；
 *    其它 ROM 是否可读取决于厂商策略。
 * 2. **root（su）**（兜底）—— 已 root 机器沿用旧路径；Shizuku 不可用时自动回退，行为与改造前一致。
 *
 * 注意 Shizuku **不是 root**：adb 模式下身份是 shell，能读到哪些节点完全取决于 ROM 的
 * SELinux 策略（本机连标准 battery 节点都拦，远不止 qcom 私有节点）。
 * 故 [read] 带 **binder 兜底**：sysfs 读不到时改走 `cmd battery get -f current_now` +
 * `dumpsys battery`（BatteryService/health HAL，shell 身份实测 0.07s，详见 [readViaBinder]）。
 * 若 Shizuku 以 root 模式启动（uid 0），则等价于 root 通道。
 *
 * ⚠️ 另一处身份差异：**电池静态信息（[readBatteryInfo]）仅 root 通道提供**，Shizuku 模式下
 * 整张电池卡片不显示，详见该方法 KDoc。
 *
 * ⚠️ 电池节点目录不硬编码：部分机型没有 `/sys/class/power_supply/battery`（或该目录下无
 * voltage_now）而只有 bms，故由 [detectBatteryDir] 在**当前身份**下实测选定。
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

    /** 当前取数通道 */
    enum class AccessMode { NONE, SHIZUKU, ROOT }

    private const val PS_BATTERY = "/sys/class/power_supply/battery"
    private const val PS_BMS = "/sys/class/power_supply/bms"
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

    /** [readBatteryInfo] 需要的高通私有节点（与 [QCOM_FILES] 是不同子集，勿合并） */
    private val QCOM_INFO_FILES = listOf("fg1_soh", "fg1_cycle", "fg1_fcc", "fg1_qmax", "model_name")

    /** [readBatteryInfo] 需要的标准节点 */
    private val BATTERY_INFO_FILES = listOf(
        "model_name", "technology", "health", "cycle_count", "charge_full", "charge_full_design",
    )

    /** 关注的温感区类型 → 语义名 */
    private val THERMAL_TARGETS = setOf("battery", "usb", "charger_therm0", "pm8350c_tz", "pm8350b_tz")

    @Volatile
    private var thermalIndex: Map<String, Int> = emptyMap()

    // ---- 电池节点目录探测 ----
    // 部分机型没有 /sys/class/power_supply/battery（或该目录下无 voltage_now），只有 bms，
    // 因此不能假定目录名，必须在**当前身份**下实测哪一份可读（详见 [detectBatteryDir]）。
    @Volatile
    private var batteryDir: String = PS_BATTERY

    /** 是否已得出过确定结论（通道不可用时不置位，留给下次重探） */
    @Volatile
    private var batteryDirChecked = false

    /** 上一次探测的结论：voltage_now 是否真的可读 */
    @Volatile
    private var batteryReadable = false

    // ---- 权限探测缓存 ----
    // su 探测只做一次（fork su 有成本）；Shizuku 状态改为**每次**读 StateFlow（很廉价），
    // 因为 UserService 绑定是异步的：冷启动首帧时可能尚未连上，若把「无权限」缓存下来，
    // 绑定完成后就永远不会恢复。只有正结果（hasAccess = true）才缓存。
    @Volatile
    private var suChecked = false

    @Volatile
    private var suAvailable = false

    @Volatile
    private var hasAccess = false

    /** 当前取数通道（UI 可据此显示「Shizuku / root / 无」） */
    @Volatile
    var accessMode: AccessMode = AccessMode.NONE
        private set

    /** 最近一次检查的失败原因（用于 UI 提示） */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 当前是否由 **binder 兜底**出数（sysfs 通道不可用，典型 = SELinux 拦截的 Shizuku 机器）。
     * UI 据此切换指标卡片布局：开路电压顶替接口温度的位置、接口/充电 IC 温度卡片隐藏
     * （binder 通道拿不到这两个节点，用户拍板 2026-09-21）。
     */
    private val _binderFallback = MutableStateFlow(false)
    val binderFallback: StateFlow<Boolean> = _binderFallback

    /**
     * 是否有可用取数通道（Shizuku 或 root）。必须在后台线程调用。
     *
     * 判定顺序：Shizuku 已绑定 → root(su) → 都没有。
     * 失败**不缓存**，采样循环下一次调用会重新判定（Shizuku 绑定完成后自动恢复）。
     */
    fun checkAccess(): Boolean {
        if (hasAccess) return true

        // 1. Shizuku（shell 身份，非 root 机器的主力通道）
        if (ShizukuHelper.serviceBound.value) {
            markPositive(AccessMode.SHIZUKU)
            return true
        }

        // 2. root（su）—— 只探测一次
        if (!suChecked) {
            suChecked = true
            val out = execRaw("id -u")
            suAvailable = out?.trim()?.lineSequence()?.lastOrNull()?.trim() == "0"
        }
        if (suAvailable) {
            markPositive(AccessMode.ROOT)
            return true
        }

        // 3. 都没有：给出可操作的提示
        accessMode = AccessMode.NONE
        lastError = when {
            ShizukuHelper.available.value && ShizukuHelper.granted.value ->
                "Shizuku 已授权但服务未就绪（正在自动重试绑定）"
            ShizukuHelper.available.value ->
                "Shizuku 正在运行但本应用未获授权，请在「采样设置」里授权"
            else ->
                "无 root 权限，且未安装/运行 Shizuku —— 无法读取底层电量节点"
        }
        return false
    }

    private fun markPositive(mode: AccessMode) {
        accessMode = mode
        hasAccess = true
        lastError = null
        if (thermalIndex.isEmpty()) thermalIndex = probeThermal()
        detectBatteryDir()
    }

    /** 重置探测缓存（例如 Shizuku 授权状态变化后） */
    fun reset() {
        suChecked = false
        suAvailable = false
        hasAccess = false
        accessMode = AccessMode.NONE
        thermalIndex = emptyMap()
        batteryDir = PS_BATTERY
        batteryDirChecked = false
        batteryReadable = false
        _binderFallback.value = false
    }

    /**
     * 探测**当前身份**真正可读的电池节点目录：battery 优先，回退 bms。
     *
     * 用 shell 内建 `[ -r ... ]` 判定 —— 它检查的正是执行该命令的那个进程身份
     * （Shizuku 下为 shell，su 下为 root）能否打开该文件，因此结论与后续 `cat` 完全一致，
     * 不会出现"探测说能读、实际读不到"的错配。
     *
     * 这一步同时承担**诊断职责**：若无任何目录可读，[read] 会据此给出准确原因，
     * 而不是笼统地报"解析失败"。
     *
     * @return true = voltage_now 可读
     */
    private fun detectBatteryDir(): Boolean {
        if (batteryDirChecked) return batteryReadable
        if (accessMode == AccessMode.NONE) return false // 通道不可用，结论无意义

        val cmd = "for d in $PS_BATTERY $PS_BMS; do " +
            "if [ -r \"${'$'}d/voltage_now\" ]; then echo \"${'$'}d\"; break; fi; done"
        val out = exec(cmd) ?: return false // 命令本身失败：不算探测过，下次重试

        val dir = out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it == PS_BATTERY || it == PS_BMS }
        batteryDirChecked = true
        batteryReadable = dir != null
        if (dir != null) batteryDir = dir
        return batteryReadable
    }

    /**
     * 探测温感区 type → zone 编号映射。
     *
     * 走单进程 awk：本机 **105 个温感区**，旧写法每区 fork 两次（`cat` + `tr`）实测 **4.78s**，
     * awk 版 **0.02s**。这是"点开始采样后要等好几秒才出第一个点"的直接成因。
     */
    private fun probeThermal(): Map<String, Int> {
        // FILENAME 反推 zone 编号：/sys/class/thermal/thermal_zone12/type → 12
        val fast = exec(
            "awk 'FNR==1{z=FILENAME;sub(/.*thermal_zone/,\"\",z);sub(/\\/.*/,\"\",z);" +
                "gsub(/\\n/,\"\",${'$'}0);print ${'$'}0 \"|\" z}' " +
                "$THERMAL_DIR/thermal_zone*/type 2>/dev/null",
        )
        return parseThermal(fast ?: exec(legacyThermalCmd()))
    }

    /** 旧写法（awk 不可用时的回退）：每 zone fork 两次（`cat` + `tr`） */
    private fun legacyThermalCmd(): String = """
        for d in $THERMAL_DIR/thermal_zone*; do printf '%s|%s\n' "$(cat "${'$'}d/type" 2>/dev/null|tr -d '\n')" "${'$'}{d##*_zone}"; done
    """.trimIndent()

    private fun parseThermal(out: String?): Map<String, Int> {
        if (out == null) return emptyMap()
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
        if (!checkAccess()) return null
        readSysfs()?.let {
            lastError = null // 清掉上一轮失败残留的错误文案，避免「有数据还挂着报错」
            _binderFallback.value = false
            return it
        }
        // sysfs 读不到（SELinux 拦截 / 节点缺失 / 两条命令通道都失败）→ binder 兜底。
        // 失败时 readSysfs 已在 lastError 留下证据，readViaBinder 会把两条通道的原因汇总。
        _binderFallback.value = true
        return readViaBinder()
    }

    /** sysfs 通道：awk 快路径 + shell 循环回退。失败返回 null 并把原因写入 [lastError] */
    private fun readSysfs(): PowerSample? {
        // 节点可读性自检：失败时给出**准确**原因（目录不存在 / 路径不符 / 身份不足以读该节点），
        // 而不是让下游笼统地报"解析失败"。
        if (!detectBatteryDir()) {
            lastError = "读不到 $batteryDir/voltage_now —— " +
                "${accessModeLabel()}身份无法打开该节点（机型节点路径不同，或受 SELinux 限制）"
            return null
        }

        val tz = thermalIndex
        val entries = buildList {
            BATTERY_FILES.forEach { add("b_$it" to "$batteryDir/$it") }
            QCOM_FILES.forEach { add(it to "$QCOM_BATTERY/$it") }
            USB_FILES.forEach { add("u_$it" to "$PS_USB/$it") }
            tz.forEach { (_, idx) -> add("tz$idx" to "$THERMAL_DIR/thermal_zone$idx/temp") }
        }

        // 快路径：单进程 awk 一次读完（本机实测 0.01s）
        val fastOut = exec(awkDump(entries))
        var out = fastOut
        var channel = "awk"
        var sample = fastOut?.let { parse(it, tz) }

        // 回退：个别 ROM 裁剪了 /system/bin/awk 或行为异常 → 退回逐文件 shell 循环（实测 1.4s）
        if (sample == null) {
            channel = "shell 循环"
            out = exec(legacyDump(tz))
            sample = out?.let { parse(it, tz) }
        }

        if (sample == null) {
            // 自检通过却仍解析不出电压，只可能是"节点存在但内容为空/非数字"，或两条命令通道都失败。
            // 把通道名与原始输出摘要带上，用户截图即可定位（无需再装 logcat）。
            lastError = "解析失败：$batteryDir/voltage_now 为空或非数字" +
                "（通道：$channel）。原始输出：${out?.asSummary() ?: "命令未返回"}"
        }
        return sample
    }

    /**
     * binder 兜底通道（2026-09-21 用户拍板）：sysfs 被 SELinux 拦截时的非 root 出数路径。
     *
     * 数据源 = `cmd battery get -f current_now`（BATTERY_PROPERTY_CURRENT_NOW，经 health HAL）
     * + `dumpsys battery`（BatteryService 快照：电压 mV / 温度 0.1°C / 电量 % / 状态 / 剩余容量 µAh）
     * + 温感区仍走 sysfs awk（本机 SELinux 不拦 thermal_zone，见文件头实测记录）。
     *
     * ⚠️ 电流符号：Android 文档称 BATTERY_PROPERTY_CURRENT_NOW 正=充电，但本机实测
     * （24031PN0DC / HyperOS V816）status=Charging 时恒为负 —— 该属性是 HAL 原始值透传，
     * 遵循**内核约定（负=充电）**，与 sysfs current_now 同号，故取与 [readSysfs] 相同的取反口径。
     * 换 ROM 若发现「充电时功率为负」，即该 ROM 按文档取号，届时再适配。
     */
    private fun readViaBinder(): PowerSample? {
        val sysfsError = lastError
        val out = exec(binderDump()) ?: run {
            lastError = "$sysfsError；binder 兜底命令未返回输出"
            return null
        }
        val sample = parseBinder(out, thermalIndex)
        if (sample == null) {
            lastError = "$sysfsError；binder 兜底输出缺少电压字段。原始输出：${out.asSummary()}"
        }
        return sample
    }

    /**
     * binder 兜底的一次性读取命令（单次 exec ≈ 0.07s，真机实测）。
     *
     * - `-f` 强制刷新 health HAL 快照（本机支持）；旧 ROM 不认 `-f` 时 `||` 回退不带 `-f`；
     * - 各段 `2>/dev/null` 吞掉不支持 ROM 的报错；
     * - 尾部 `; true` 兜底退出码：[ShellService] 契约是非 0 = 失败（返回 `ERROR:` 前缀字符串），
     *   不能让 thermal awk 段（awk 缺失时 rc=127）污染整条命令的结果。
     */
    private fun binderDump(): String {
        val tzEntries = thermalIndex.values.map { "tz$it" to "$THERMAL_DIR/thermal_zone$it/temp" }
        return buildString {
            append("cmd battery get -f current_now 2>/dev/null || cmd battery get current_now 2>/dev/null")
            append("; dumpsys battery 2>/dev/null")
            if (tzEntries.isNotEmpty()) {
                append("; ")
                append(awkDump(tzEntries))
            }
            append("; true")
        }
    }

    private fun parseBinder(out: String, tz: Map<String, Int>): PowerSample? {
        var currentUa: Double? = null
        val d = HashMap<String, String>()
        out.lineSequence().forEach { line ->
            val t = line.trim()
            // 第一条纯数字行 = current_now（µA）；cmd 的报错行与 dumpsys 各行均非纯数字，不会误命中
            if (currentUa == null && t.matches(Regex("-?\\d+"))) {
                currentUa = t.toDoubleOrNull()
                return@forEach
            }
            val i = t.indexOf(':')
            if (i > 0) d[t.substring(0, i)] = t.substring(i + 1).trim()
        }

        val voltageMv = d["voltage"]?.toDoubleOrNull() ?: return null
        val voltageV = voltageMv / 1000.0
        val currentMa = -(currentUa ?: 0.0) / 1000.0
        val temps = tempsFrom(d, tz)

        return PowerSample(
            timeMillis = System.currentTimeMillis(),
            voltageV = voltageV,
            // binder 无 OCV，与 sysfs 缺 ocv 时同口径：用工作电压顶替
            voltageOcvV = voltageV,
            currentMa = currentMa,
            fgCurrentMa = null,
            powerW = voltageV * currentMa / 1000.0,
            tempBatteryC = d["temperature"]?.toDoubleOrNull()?.let { it / 10.0 }
                ?: temps["battery"] ?: 0.0,
            tempUsbC = temps["usb"],
            tempChargerC = temps["charger_therm0"],
            tempPmicC = temps["pm8350c_tz"] ?: temps["pm8350b_tz"],
            socPct = d["level"]?.toIntOrNull() ?: 0,
            status = binderStatusStr(d["status"]),
            chargeType = "", // binder 通道无内核 charge_type
            remainingMah = d["Charge counter"]?.toDoubleOrNull()?.let { it / 1000.0 },
            fullMah = null,
            usbVoltageV = null,
            usbCurrentLimitMa = null,
        )
    }

    /** BatteryManager 的 status 常量 → 与内核 b_status 同形的字符串 */
    private fun binderStatusStr(v: String?): String = when (v?.toIntOrNull()) {
        2 -> "Charging"
        3 -> "Discharging"
        4 -> "Not charging"
        5 -> "Full"
        else -> "Unknown"
    }

    /** 旧写法的完整读取命令（[awkDump] 不可用时的回退），输出语义与 [awkDump] 完全一致 */
    private fun legacyDump(tz: Map<String, Int>): String {
        val thermalIds = tz.values.joinToString(" ")
        val thermalSection = if (thermalIds.isBlank()) "" else """
            for i in $thermalIds; do printf 'tz%s=' "${'$'}i"; cat "$THERMAL_DIR/thermal_zone${'$'}i/temp" 2>/dev/null|tr -d '\n'; printf '\n'; done;
        """.trimIndent()
        return buildString {
            append(section(batteryDir, "b_", BATTERY_FILES))
            append(" ")
            append(section(QCOM_BATTERY, "", QCOM_FILES))
            append(" ")
            append(section(PS_USB, "u_", USB_FILES))
            if (thermalSection.isNotBlank()) {
                append(" ")
                append(thermalSection)
            }
        }.trim()
    }

    /**
     * 确保做过一次 su 探测（幂等）。
     *
     * 单独抽出来是因为 [checkAccess] 在 Shizuku 可用时会**提前返回**，压根不会探测 su；
     * 而 [readBatteryInfo] 需要的是"这台机器有没有 root"这个**独立事实**，
     * 不能用"当前通道是不是 root"代替 —— 否则 root 机器只要同时开着 Shizuku，
     * 通道就会优先走 Shizuku，电池卡片会被连带藏掉。
     */
    private fun ensureSuChecked() {
        if (suChecked) return
        suChecked = true
        suAvailable = execRaw("id -u")?.trim()?.lineSequence()?.lastOrNull()?.trim() == "0"
    }

    /** 当前通道的可读名称，用于拼装用户可理解的错误文案 */
    private fun accessModeLabel(): String = when (accessMode) {
        AccessMode.SHIZUKU -> "Shizuku(shell)"
        AccessMode.ROOT -> "root"
        AccessMode.NONE -> "无权限"
    }

    /** 把原始输出压成单行摘要（供 UI 显示，便于定位"读不到 / 路径不符"） */
    private fun String.asSummary(limit: Int = 200): String =
        replace('\n', '|').replace("||", "|").take(limit)

    /**
     * 单进程 awk 一次读完整批节点（方案 A，2026-09-21 设备实测确定）。
     *
     * 动机：旧写法对每个文件 fork 两次（`cat` + `tr`），本机 35 个节点 ≈ 70 次 fork。
     * 设备实测（Xiaomi 24031PN0DC / Android 16 / HyperOS V816，shell 身份）：
     *
     * | 实现              | 14 个节点 | 35 个节点 | 105 个温感区 |
     * |------------------|----------|----------|-------------|
     * | 旧逐文件 shell 循环 | 0.84s    | 1.37s    | 4.78s       |
     * | awk 单进程         | —        | 0.01s    | 0.02s       |
     *
     * ⇒ 采样间隔设 1s 时，旧实现的真实周期被拖到约 3s（上一轮 logcat 里 binderTransact
     * 周期 3.1s 即此），且首帧还要多等 [probeThermal] 的约 4.8s。
     *
     * `getline v < file` 读不到（不存在 / 无权限）返回 ≤0，本函数据此**跳过**该键 ——
     * 与旧写法 `2>/dev/null` 的语义一致（不产生该键，而非产生空值），故 [parse] 无需改动。
     * 尾部 `2>/dev/null` 同时兜住"awk 不存在"：那时 stdout 为空 → [exec] 判空 → 触发回退。
     *
     * @param entries 键 → 绝对路径（二者均不得含空格；本应用全部是 sysfs 常量路径）
     */
    private fun awkDump(entries: List<Pair<String, String>>): String {
        val keys = entries.joinToString(" ") { it.first }
        val paths = entries.joinToString(" ") { it.second }
        return "awk 'BEGIN{n=split(\"$keys\",k,\" \");m=split(\"$paths\",p,\" \");" +
            "for(i=1;i<=n&&i<=m;i++){if((getline v < p[i])>0){gsub(/\\n/,\"\",v);print k[i] \"=\" v}}}' 2>/dev/null"
    }

    /** 旧写法（[awkDump] 不可用时的回退）：遍历目录下指定文件，输出 `前缀+文件名=值` */
    private fun section(dir: String, prefix: String, files: List<String>): String = """
        for f in ${files.joinToString(" ")}; do printf '$prefix%s=' "${'$'}f"; cat "$dir/${'$'}f" 2>/dev/null|tr -d '\n'; printf '\n'; done;
    """.trimIndent()

    /** 从键值表提取温感区读数（tz&lt;N&gt;，毫摄氏度 → °C），sysfs 与 binder 两条解析共用 */
    private fun tempsFrom(m: Map<String, String>, tz: Map<String, Int>): HashMap<String, Double> {
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
        return temps
    }

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
        val temps = tempsFrom(m, tz)

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

    /**
     * 电池静态信息（健康度、循环次数、型号等），变化缓慢，按需调用。
     *
     * ⚠️ **仅 root 通道提供**（用户拍板 2026-09-21）。这张卡片的核心字段（循环次数、SOH、
     * 满充容量）取自高通私有 qcom-battery 节点，shell 身份（Shizuku）读不到，只会渲染出
     * 一张大面积缺项的空壳卡片，反而误导；故 Shizuku 模式下整张卡片不显示 ——
     * 返回 null，UI 侧 `if (batteryInfo != null)` 自然不渲染。
     * 请勿在 UI 侧为此新增「未 root」提示或占位卡片。
     */
    fun readBatteryInfo(): BatteryInfo? {
        if (!checkAccess()) return null
        // 判据是"这台机器有没有 root"这一**独立事实**，不是"当前通道是不是 root" ——
        // root 机器若同时开着 Shizuku，通道会优先走 Shizuku（功率读数同源无差别），
        // 用 accessMode 判定会把 root 机器的电池卡片一并藏掉。详见 [ensureSuChecked]。
        ensureSuChecked()
        if (!suAvailable) return null
        if (!detectBatteryDir()) return null

        val entries = buildList {
            QCOM_INFO_FILES.forEach { add(it to "$QCOM_BATTERY/$it") }
            BATTERY_INFO_FILES.forEach { add("b_$it" to "$batteryDir/$it") }
        }
        val out = exec(awkDump(entries)) ?: exec(legacyBatteryInfoCmd()) ?: return null
        val m = HashMap<String, String>()
        out.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val info = BatteryInfo(
            model = m["model_name"] ?: m["b_model_name"],
            technology = m["b_technology"],
            health = m["b_health"],
            sohPct = m.int("fg1_soh"),
            cycleCount = m.int("fg1_cycle") ?: m.int("b_cycle_count"),
            fullMah = m.dbl("b_charge_full")?.let { it / 1_000.0 },
            designMah = m.dbl("b_charge_full_design")?.let { it / 1_000.0 },
            maxChargeW = m.dbl("power_max"),
        )
        // 一个字段都没读到 → 返回 null 而非空壳对象。否则 UI 会渲染出一张全是 "—" 的卡片，
        // 这正是「纯 Shizuku 模式下还能看到电池卡片」的直接成因之一。
        return info.takeUnless { it.isBlank() }
    }

    /** 旧写法的电池信息读取命令（[awkDump] 不可用时的回退），输出语义与 [awkDump] 一致 */
    private fun legacyBatteryInfoCmd(): String = """
        ${section(QCOM_BATTERY, "", QCOM_INFO_FILES)}
        ${section(batteryDir, "b_", BATTERY_INFO_FILES)}
    """.trimIndent()

    private fun HashMap<String, String>.dbl(key: String): Double? = this[key]?.toDoubleOrNull()

    private fun HashMap<String, String>.int(key: String): Int? = this[key]?.toIntOrNull()

    /**
     * 执行命令：**Shizuku 优先，root 兜底**。
     *
     * - Shizuku 已绑定 → 走 shell 身份；返回空（非 0 退出码）时继续尝试 su；
     * - 已知无 root（su 探测过且不可用）→ 直接返回 null，不再 fork su（避免每次采样白花 4 次进程创建）；
     * - 其余情况走 su。
     */
    private fun exec(cmd: String, timeoutMs: Long = 5_000L): String? {
        if (ShizukuHelper.serviceBound.value) {
            val out = ShizukuHelper.execSync(cmd)
            if (!out.isNullOrBlank()) return out
        }
        if (suChecked && !suAvailable) return null
        return execRaw(cmd, timeoutMs)
    }

    /** 仅走 su 通道（root 探测，以及 Shizuku 不可用时的兜底） */
    private fun execRaw(cmd: String, timeoutMs: Long = 5_000L): String? {
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

/** 所有字段均为空 = 一个节点都没读到（用于滤掉空壳对象，避免渲染空卡片） */
private fun BatteryInfo.isBlank(): Boolean =
    model == null && technology == null && health == null && sohPct == null &&
        cycleCount == null && fullMah == null && designMah == null && maxChargeW == null

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
