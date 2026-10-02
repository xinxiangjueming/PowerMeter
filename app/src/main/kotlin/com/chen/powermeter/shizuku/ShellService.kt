package com.chen.powermeter.shizuku

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 逐核频率节点数（与 FrameRateSource.CPU_FAST_CMD 的 awk 循环写死值一致） */
private const val CPU_CORE_COUNT = 8

/** GPU 占用率候选节点（与 FrameRateSource.GPU_BUSY_NODES 同表同序，两处改动必须同步） */
private val GPU_LOAD_NODES = arrayOf(
    "/sys/class/kgsl/kgsl-3d0/gpubusy",
    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
    "/sys/kernel/gpu/gpu_busy",
    "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
    "/sys/kernel/ged/hal/gpu_utilization",
    "/sys/class/misc/mali0/device/utilisation",
    "/sys/module/ged/parameters/gpu_loading",
    "/sys/class/devfreq/gpufreq/mali_ondemand/utilisation",
)

/**
 * GPU 频率候选节点（与 FrameRateSource.GPU_FREQ_NODES 同表同序，两处改动必须同步）。
 * 候选池口径对齐 Metric（com.itos.metric.helper 的 GpuSampler）+ 高通/MTK/Mali/Tegra 常见挂点。
 * ⚠️ 真机定案（24031PN0DC / HyperOS V816，2026-09-29 adb 实测）：本机 kgsl 与 /sys/kernel/gpu
 * 全被 SELinux 拦（cur_freq/gpuclk/gpu_clock 均 Permission denied，同占用率节点的封禁面）——
 * 频率线在本机 Shizuku 模式恒缺（预期），节点可读的机型自动出数。
 */
private val GPU_FREQ_NODES = arrayOf(
    "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
    "/sys/class/kgsl/kgsl-3d0/gpuclk",
    "/sys/kernel/gpu/gpu_clock",
    "/sys/class/misc/mali0/device/clock",
    "/sys/devices/11800000.mali/clock",
    "/sys/devices/14ac0000.mali/clock",
    "/sys/kernel/ged/hal/current_freqency",
    "/sys/kernel/debug/ged/hal/current_freqency",
    "/sys/class/devfreq/gpufreq/cur_freq",
    "/sys/kernel/tegra_gpu/gpu_rate",
)

/** 温感区根目录（同 FrameRateSource.THERMAL_ZONE_DIR） */
private const val THERMAL_ZONE_DIR = "/sys/class/thermal"

/**
 * DDR 频率候选节点（与 FrameRateSource.DDR_FREQ_NODES 同表同序，两处改动必须同步）。
 * 候选池逆向 Metric libmetric_daemon.so 定案：QCOM bus_dcvs/DDR/cur_freq（kHz，
 * 22081212C / SM8475 实测 shell 身份可读、无需 root）/ MTK dvfsrc cur_freq；
 * debugfs clock_measure 与 helio dump 不进池（口径见 FrameRateSource.DDR_FREQ_NODES 注释）。
 */
private val DDR_FREQ_NODES = arrayOf(
    "/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq",
    "/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq",
)

private val WHITESPACE_RE = Regex("\\s+")

/**
 * 运行时 hidden API 豁免，**两级**（任一成功即可）：
 * ① 经典元反射配方（借 `Class.getDeclaredMethod` 自身不受限的元反射接口取
 *    `VMRuntime.setHiddenApiExemptions`）—— 零依赖，但 Android 12+ 已被堵
 *    （本机 24031PN0DC / Android 16 实测 false，2026-09-29 装机定案）；
 * ② LSPosed HiddenApiBypass（org.lsposed.hiddenapibypass，unsafe 构造豁免 Member
 *    的机制，最新 Android 仍有效）—— 失败兜底。
 * 只在 UserService 进程内调用一次 —— 本进程是 uid 2000 的 shell 身份，豁免后才能
 * 反射调用框架隐藏类（ServiceManager / IWindowManager / IActivityTaskManager，见
 * [registerTaskFps]）。两级都失败只影响 TaskFps 算法（注册抛异常 → ERROR → 采样循环
 * 回落 timestats），其余直读/exec 全部不经过它。
 */
private fun exemptHiddenApi(): Boolean {
    // ① 元反射配方
    val meta = runCatching {
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val empty = emptyArray<Class<*>>()
        val getDeclaredMethod = Class::class.java.getDeclaredMethod(
            "getDeclaredMethod", String::class.java, empty.javaClass,
        )
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod.invoke(
            vmRuntime, "getRuntime", empty.javaClass,
        ) as Method
        val setExemptions = getDeclaredMethod.invoke(
            vmRuntime, "setHiddenApiExemptions", arrayOf("L").javaClass,
        ) as Method
        val args = arrayOfNulls<Any>(1).also { it[0] = arrayOf("L") }
        setExemptions.invoke(getRuntime.invoke(null), *args)
    }
    if (meta.isSuccess) return true
    // ② HiddenApiBypass（空串 = 豁免全部签名）
    val bypass = runCatching {
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
    }
    val ok = bypass.getOrDefault(false)
    Log.i(
        "PowerMeterShell",
        "exemptHiddenApi: meta=${meta.isSuccess}, bypass=$ok" +
            (meta.exceptionOrNull()?.let { ", metaErr=${it.message}" } ?: "") +
            (bypass.exceptionOrNull()?.let { ", bypassErr=${it.message}" } ?: ""),
    )
    return ok
}

/**
 * 运行在 Shizuku 进程中的命令执行服务，权限身份 = **shell（uid 2000）**，不是 root。
 *
 * 由 Shizuku 的 UserService 机制通过 [Shizuku.UserServiceArgs] 里的 ComponentName
 * **反射加载**，因此**不需要、也不能**在 AndroidManifest 注册。
 * R8 混淆会重命名类名 → bindUserService 永久失败（表现为「已授权但读不到数据」），
 * 故本类加 @Keep，并在 proguard-rules.pro 保留 `-keep class com.chen.powermeter.shizuku.** { *; }`
 * （双保险，口径对齐 fold shizuku/ShellService.kt:7-16）。
 *
 * ⚠️ 与 fold 的差异：**不做危险字符过滤**。
 * fold 的文件浏览器需要拼接用户的目录路径，存在命令注入面，故其 ShellService 用
 * isCommandSafe 禁掉了 `;` `|` `$(` 等；而本应用执行的全部命令都由
 * [com.chen.powermeter.data.RootPowerReader] / [com.chen.powermeter.util.ScreenController]
 * **内部常量拼接**（读 /sys 节点、注入电源键），不含任何用户输入，且必须使用
 * `for ...; do ... done;`、管道 `|tr`、命令替换 `$(cat ...)` 来构造复合读取命令
 * （见 RootPowerReader.section / probeThermal）。此处过滤既无防御价值又会误伤自身命令，
 * 故有意省略。
 */
@Keep
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "ERROR:-1:timeout"
            } else {
                val exitCode = process.exitValue()
                if (exitCode == 0) {
                    output
                } else {
                    "ERROR:$exitCode:${output.trim()}"
                }
            }
        } catch (e: Exception) {
            "ERROR:-1:${e.message}"
        }
    }

    // ── 直读方法（2026-09-29，采样循环去 fork）──────────────────────────
    //
    // 每条 exec = fork sh + fork 命令本体（awk/dumpsys），满载下单次 100~200ms，稳态录制
    // ~13.5 进程/秒把 1s 拍拖到 1.35s（2026-09-28 xlsx 实测）。而本进程就是 uid 2000 /
    // SELinux shell 上下文，对下面这些 /proc、/sys 节点与 awk **同一权限**——直接
    // java.io 读，一次 read() 的成本是 fork 的几十分之一。三个方法的输出与对应 awk
    // 命令逐行同构（FrameRateSource 解析零改动），异常统一走 "ERROR:-1:" 前缀 →
    // 调用方置 null → 回退 exec 通道，功能不中断。
    //
    // ⚠️ 路径全部是内部常量白名单（接口不收路径参数），不做任意文件读工具。

    /** /proc/stat 的 cpu 行原样回传（合计行 + 逐核行），供差分使用率 */
    private fun readProcStatCpuLines(): String? = try {
        File("/proc/stat").readText().lineSequence()
            .filter { it.startsWith("cpu") }
            .joinToString("\n")
    } catch (e: Exception) {
        null
    }

    private fun readNodeFirstLine(path: String): String? = try {
        File(path).bufferedReader().use { r -> r.readLine() }
    } catch (e: Exception) {
        null
    }

    /**
     * CPU 快样：/proc/stat cpu 行 + 逐核 scaling_cur_freq。
     * 输出与 [CPU_FAST_CMD 的 awk]（FrameRateSource）逐行同构：
     * /proc/stat 里以 "cpu" 开头的行原样；随后每核一行 `freqN <值>`（读不到时值留空，
     * 只有前缀和空格——解析按序号留空位，后续核心不会错位）。
     */
    override fun readCpuFastSample(): String = try {
        val sb = StringBuilder()
        readProcStatCpuLines()?.let { sb.append(it) }
        for (i in 0 until CPU_CORE_COUNT) {
            val v = readNodeFirstLine("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                ?.trim().orEmpty()
            sb.append("\nfreq").append(i).append(' ').append(v)
        }
        sb.toString()
    } catch (e: Exception) {
        "ERROR:-1:${e.message}"
    }

    /**
     * GPU 占用率 + 频率：候选节点按序探测（与 FrameRateSource 两张候选表同序），
     * 第一个非空占用率节点命中：gpubusy 是 "busy total" 双数对 → busy÷total×100（total=0 → 0），
     * 其余节点原样回传（Kotlin 侧取首个数字）。全部不可读返回空串（= 无数据，非错误）。
     * 频率段独立探测（GPU_FREQ_NODES 第一个非空值），命中时追加一行 `freq <原始值>`
     * （单位不统一：Hz/kHz/MHz 都有，由 FrameRateSource 按量级换算 MHz）；全不可读则无该行。
     * ⚠️ 输出加行属于**向后兼容扩展**：旧解析只读第一行，不破协议。
     */
    override fun readGpuLoad(): String = try {
        var result = ""
        for (path in GPU_LOAD_NODES) {
            val line = readNodeFirstLine(path) ?: continue
            if (line.isEmpty()) continue
            result = if (path.contains("gpubusy")) {
                val tokens = line.trim().split(WHITESPACE_RE)
                val busy = tokens.getOrNull(0)?.toLongOrNull() ?: 0L
                val total = tokens.getOrNull(1)?.toLongOrNull() ?: 0L
                if (total > 0) String.format(Locale.US, "%.1f", busy * 100.0 / total) else "0"
            } else {
                line
            }
            break
        }
        for (path in GPU_FREQ_NODES) {
            val line = readNodeFirstLine(path) ?: continue
            if (line.isEmpty()) continue
            result += "\nfreq ${line.trim()}"
            break
        }
        result
    } catch (e: Exception) {
        "ERROR:-1:${e.message}"
    }

    /**
     * DDR 频率：候选节点按序探测（与 FrameRateSource.DDR_FREQ_NODES 同序），
     * 第一个非空值原样回传（单位 kHz/Hz/MHz 不统一，由 FrameRateSource 按量级换算 MHz）。
     * 全不可读返回空串（= 无数据，非错误）。输出与 [DDR_FREQ_CMD 的 awk]
     * （FrameRateSource）逐行同构：单行原始值。
     */
    override fun readDdrFreq(): String = try {
        var result = ""
        for (path in DDR_FREQ_NODES) {
            val line = readNodeFirstLine(path) ?: continue
            if (line.isEmpty()) continue
            result = line.trim()
            break
        }
        result
    } catch (e: Exception) {
        "ERROR:-1:${e.message}"
    }

    /**
     * 温感区配对：thermal_zone 目录的 type 与 temp 都读到的区段输出一行 "type temp"。
     * 输出顺序与 awk 版（按 temp 文件序）不同但无影响——解析侧是逐行正则 + 关键字取 max。
     */
    override fun readThermalTemps(): String = try {
        val zones = File(THERMAL_ZONE_DIR).listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name } ?: return ""
        zones.mapNotNull { dir ->
            val type = readNodeFirstLine("${dir.path}/type")?.trim().orEmpty()
            val temp = readNodeFirstLine("${dir.path}/temp")?.trim().orEmpty()
            if (type.isNotEmpty() && temp.isNotEmpty()) "$type $temp" else null
        }.joinToString("\n")
    } catch (e: Exception) {
        "ERROR:-1:${e.message}"
    }

    /**
     * Shizuku server 保留的 destroy 方法（transaction code 16777114）：
     * 主进程 unbind 时被调用。UserService 进程不会自动退出，需在此结束进程。
     */
    override fun destroy() {
        Log.i("PowerMeterShell", "destroy called, exiting process")
        System.exit(0)
    }

    // ── 系统 TaskFpsCallback 桥（2026-09-29，AIDL v3）────────────────────
    //
    // 机制与口径见 IShellService.aidl 的同段注释。三层设计：
    // ① 回调侧**不引用任何框架隐藏类**——手写同 descriptor 的 [taskFpsCallbackBinder]
    //    （ITaskFpsCallback 单方法 onFpsReported(float)，事务码 = FIRST_CALL_TRANSACTION）；
    // ② 注册侧经反射调框架 IWindowManager/IActivityTaskManager——init 时先做 hidden API
    //    豁免（[exemptHiddenApi]），接口参数类型 android.window.ITaskFpsCallback 用
    //    java.lang.reflect.Proxy 动态实现（asBinder 返回我们的 Binder）；
    // ③ taskId 解析优先隐藏 API getFocusedRootTaskInfo（校验 topActivity 包名），
    //    失败回落 exec dumpsys 解析 topResumedActivity 行尾的 `t<NNN>`。
    //
    // ⚠️ WMS 侧按 callback.asBinder() 记账（TaskFpsCallbackController.containsKey）：
    // 重注册必须**先 unregister 再 register**，否则同 binder 会被去重、taskId 不更新；
    // 故缓存同一个 Proxy 实例，[registerTaskFps] 里先注销旧目标再注册新目标。

    private companion object {
        const val TASK_FPS_DESCRIPTOR = "android.window.ITaskFpsCallback"
        const val FIRST_CALL_TRANSACTION = 1
    }

    /** 最近一次系统推送：浮点位模式 + 墙钟 ms（0 = 从未收到） */
    private val taskFpsLatestBits = AtomicLong(0)
    private val taskFpsLatestAt = AtomicLong(0)

    /** 已注册状态（null = 未注册）；@Synchronized 保护与 register/unregister 的互斥 */
    @Volatile
    private var taskFpsRegisteredPkg: String? = null

    @Volatile
    private var taskFpsRegisteredTaskId = 0

    private val taskFpsCallbackBinder = object : Binder(TASK_FPS_DESCRIPTOR) {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == FIRST_CALL_TRANSACTION) {
                return try {
                    data.enforceInterface(TASK_FPS_DESCRIPTOR)
                    val fps = data.readFloat()
                    // fps<=0 / 异常值丢弃（Metric 同款过滤）：系统不会推负值，防御 ROM 私改
                    if (fps > 0.0f && !fps.isNaN()) {
                        taskFpsLatestBits.set(fps.toRawBits().toLong())
                        taskFpsLatestAt.set(System.currentTimeMillis())
                    }
                    true
                } catch (e: Exception) {
                    Log.w("PowerMeterShell", "onFpsReported parse failed: ${e.message}")
                    true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * ITaskFpsCallback 的动态代理：WMS 的 Proxy 桩只调 `callback.asBinder()` 写进 parcel，
     * 拦截它返回我们的 Binder 即可；缓存单例 —— 注册/注销必须用同一个对象（见类注释）。
     */
    private val taskFpsCallbackProxy: Any by lazy {
        val iface = Class.forName(TASK_FPS_DESCRIPTOR)
        Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> taskFpsCallbackBinder
                    // Object 方法回落默认实现，避免 Unhandled proxy invocation
                    "hashCode" -> taskFpsCallbackBinder.hashCode()
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "TaskFpsCallback@powermeter"
                    else -> null
                }
            },
        )
    }

    /** 豁免只需一次；init 块在 UserService 被反射实例化时执行 */
    private val hiddenApiExempted = exemptHiddenApi()

    init {
        Log.i("PowerMeterShell", "UserService v3 init, hiddenApiExempted=$hiddenApiExempted")
    }

    override fun registerTaskFps(packageName: String): String = synchronized(this) {
        // 同包名短路：采集循环每拍都会调（目标未变时是幂等调用），不能每次都注销重注册
        // （WMS 按 asBinder() 记账，重复 register 同 binder 会被去重、白走一遍反射）
        if (taskFpsRegisteredPkg == packageName && taskFpsRegisteredTaskId > 0) {
            return "ok $taskFpsRegisteredTaskId"
        }
        try {
            val taskId = resolveForegroundTaskId(packageName)
                ?: return "ERROR:-2:no-task"
            val iwm = windowManagerProxy()
                ?: return "ERROR:-1:IWindowManager unavailable"
            // 重注册（目标切换）：WMS 按 asBinder() 去重，必须先注销旧 taskId
            if (taskFpsRegisteredPkg != null) {
                runCatching {
                    iwm.javaClass
                        .getMethod("unregisterTaskFpsCallback", taskFpsCallbackProxy.javaClass.interfaces[0])
                        .invoke(iwm, taskFpsCallbackProxy)
                }
                taskFpsRegisteredPkg = null
            }
            iwm.javaClass
                .getMethod(
                    "registerTaskFpsCallback",
                    Int::class.javaPrimitiveType,
                    taskFpsCallbackProxy.javaClass.interfaces[0],
                )
                .invoke(iwm, taskId, taskFpsCallbackProxy)
            taskFpsRegisteredPkg = packageName
            taskFpsRegisteredTaskId = taskId
            taskFpsLatestBits.set(0)
            taskFpsLatestAt.set(0)
            Log.i("PowerMeterShell", "registerTaskFps ok: pkg=$packageName taskId=$taskId")
            "ok $taskId"
        } catch (e: Exception) {
            taskFpsRegisteredPkg = null
            taskFpsRegisteredTaskId = 0
            Log.w("PowerMeterShell", "registerTaskFps failed: ${e.message}")
            "ERROR:-1:${e.message}"
        }
    }

    override fun readTaskFps(): String {
        val at = taskFpsLatestAt.get()
        if (at == 0L) return ""
        return "${Float.fromBits(taskFpsLatestBits.get().toInt())} $at"
    }

    override fun unregisterTaskFps() = synchronized(this) {
        if (taskFpsRegisteredPkg == null) return
        runCatching {
            windowManagerProxy()?.let { iwm ->
                iwm.javaClass
                    .getMethod("unregisterTaskFpsCallback", taskFpsCallbackProxy.javaClass.interfaces[0])
                    .invoke(iwm, taskFpsCallbackProxy)
            }
        }
        taskFpsRegisteredPkg = null
        taskFpsRegisteredTaskId = 0
        taskFpsLatestBits.set(0)
        taskFpsLatestAt.set(0)
    }

    /**
     * IWindowManager 代理（反射 asInterface(ServiceManager.getService("window"))）。
     * null = 框架类缺失 / 服务不可用（罕见 ROM），调用方按注册失败处理。
     */
    private fun windowManagerProxy(): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "window") as? IBinder
            ?: return null
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    /**
     * 解析前台任务 id（必须属于 [packageName]，避免把别的任务注册上）。
     * ① 隐藏 API getFocusedRootTaskInfo（豁免后反射，TaskInfo.taskId/topActivity 公共字段）；
     * ② 回落 exec dumpsys：`ActivityRecord{... u0 <pkg>/... t<NNN>}` 行尾的 tNNN 就是 taskId。
     */
    private fun resolveForegroundTaskId(packageName: String): Int? {
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder
                ?: return@runCatching
            // ⚠️ Context.ACTIVITY_TASK_SERVICE 是 hidden 常量（SDK stub 里没有），用字面值；
            //   与 IActivityTaskManager 的 descriptor 对应的服务名恒为 "activity_task"
            val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
            val atm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val info = atm.javaClass.getMethod("getFocusedRootTaskInfo").invoke(atm) ?: return@runCatching
            val taskId = info.javaClass.getField("taskId").getInt(info)
            val top = info.javaClass.getField("topActivity").get(info) as? ComponentName
            if (taskId > 0 && top?.packageName == packageName) return taskId
        }
        // 回落：dumpsys 解析（快照口径与主进程 readTopPackage 相同的一行）
        return runCatching {
            val pb = ProcessBuilder(
                "sh", "-c",
                "dumpsys activity activities 2>/dev/null | grep topResumedActivity; true",
            )
            val out = pb.start().inputStream.bufferedReader().readText()
            val m = Regex("""u0\s+([A-Za-z0-9_.\-]+)/.*\st(\d+)\}""").find(out) ?: return null
            if (m.groupValues[1] == packageName) m.groupValues[2].toIntOrNull() else null
        }.getOrNull()
    }
}
