package com.chen.powermeter.data

import android.util.Log
import com.chen.powermeter.util.ShizukuHelper
import java.util.Locale

private const val TAG = "FrameRateSource"

/**
 * 帧率数据源 —— 只负责帧率侧的取数，**全部走 shell（Shizuku）身份即可读**的命令，不需要 root。
 *
 * 口径来自真机实测（Xiaomi 24031PN0DC / Android 16 / HyperOS V816）：
 * - 帧数与丢帧：`dumpsys SurfaceFlinger --timestats -dump`，每图层带 `layerName` / `uid` /
 *   `totalFrames` / `missedFrames`；全局带 `presentToPresent` 直方图（即帧间隔分布）。
 *   ⚠️ dump 随图层累积可膨胀到 1MB+，**必须在 shell 层用 awk 按目标包名收窄**再回传
 *   （Shizuku 通道跨 binder 有 ~1MB 单事务上限，见 [timestatsDumpCmd] 处的事故记录）。
 * - CPU 快样（250ms 级）：`cat /proc/stat` + 逐核 `scaling_cur_freq` 一条命令
 *   （[readCpuFastSample]，2026-09-25 从 1s 级 readCpuUsage/readCpuMhz 合并重构）。
 * - 前台应用：`dumpsys activity activities` 的 `topResumedActivity`。
 * - 刷新率：`dumpsys display` 的 `mActiveRenderFrameRate`。
 * - 虚拟温度：`/sys/class/thermal/thermal_zone*`（CPU 代表温感区，5s 抽稀）。
 *
 * ⚠️ 电量四项（电压 / 电流 / 功率 / 电池温度）**不在本类**：由
 * `FrameRecordController` 直接调 `RootPowerReader.read()`，与功率监测同一条取数链。
 *
 * ⚠️ `--timestats` 是**累计值**：totalFrames 从 SurfaceFlinger 启动起累加（或 `-clear` 之后）。
 * 因此本类只吐累计快照，**帧率由 [com.chen.powermeter.service.FrameRecordController] 做差分**。
 *
 * ⚠️ timestats 输出格式随 Android 版本变化（本项目 minSdk 30、实测机 Android 16），
 * 解析一律「尽力而为」：取不到就返回 0 / 空列表，由上层按"没数据"处理，
 * **绝不猜测或补上一帧的值**（[FrameSample.fps] 为 0 的语义是「本周期没有合成帧」）。
 */
object FrameRateSource {

    /** `--timestats` 单次快照 */
    data class Timestats(
        /** 目标应用图层累计合成帧数（全部认领图层之和）；未匹配到图层时为 0 */
        val totalFrames: Long,
        /** 目标应用图层累计丢帧数 */
        val missedFrames: Long,
        /** 全局 presentToPresent 加权平均帧间隔 ms；解析不到时为 0 */
        val frameSpaceMs: Double,
        /**
         * 认领到的**逐图层**累计帧数（layerName → 该名下全部条目 totalFrames 之和）。
         *
         * ⚠️ AOSP 16 的逐图层聚合是**两级 key**（TimeStats.cpp `flushAvailableRecordsToStatsLocked`）：
         * 外层 TimelineStatsKey = (displayRefreshRateBucket, renderRateBucket)，内层
         * LayerStatsKey = (uid, layerName, gameMode) —— **同一个图层名会因刷新率 / 渲染率 /
         * 游戏模式切换出现多条独立累计条目**，dump 里同名 layerName 不止一段。此处按名求和
         * = 该应用全部表面的总呈现数；AOSP 语义下每条目只随真实呈现递增（整表清零只发生在
         * `-clear` 与 statsd 拉 atom，见 FrameRateSource.resetTimestats），求和的增量
         * ≤ 物理呈现数，单调有界。
         *
         * ⚠️ 帧率差分**必须按单图层算再取最大**（见 FrameRecordController.updateFpsFromDiff），
         * 不能用 [totalFrames] 的总和差分：应用页面转场 / 重建瞬间新旧两个 Surface 短暂共存
         * 且都在渲染，总和的增量 = 两表面之和，120Hz 屏上能差出 160+ 的假帧率（2026-09-25
         * 用户实测反馈「帧率 tab 经常出现 160+」）。「应用的帧率」语义 = 主导表面的呈现率，
         * 单表面物理上不可能超过刷新率；转场期间取 max 恰好等于正在动画的那个表面的帧率。
         * 差分侧另有「超刷新率即拒绝 + 留痕」的物理上限守卫兜底 ROM 私改（同文件）。
         */
        val perLayerFrames: Map<String, Long> = emptyMap(),
    )

    /**
     * 命令执行 —— **直接复用功率侧的权威三通道**（Shizuku → SuSession → fork su）。
     *
     * ⚠️ 这里曾经自己 fork 过一套 su 兜底，结果是「已经授予 root 权限，却报无可用取数通道」：
     * 自带版本的 `id -u` 判定取整段输出、而 Magisk 的 su 常带额外行。特权通道只该有一份实现，
     * 见 [RootPowerReader.execPrivileged] 的注释。
     *
     * ⚠️ **失败必须留痕**（2026-09-22 加）：帧率侧每条命令都要走这里，一旦返回 null，
     * 上游只能看到「读不到帧数」，无从区分「根本没通」与「通了但命令本身失败」。
     * 失败时打一行 warning（命令 + 通道状态），装机定位时直接看 tag `FrameRateSource`。
     *
     * ⚠️ 必须在后台线程调用（内部起进程）。
     *
     * @param allowBlank 开关型命令（timestats 的 `-enable` / `-maxlayers`）正常情况下
     *   **没有任何输出**，必须带此标记才不会被当成失败。
     */
    private fun exec(cmd: String, timeoutMs: Long = 8_000L, allowBlank: Boolean = false): String? {
        val out = RootPowerReader.execPrivileged(cmd, timeoutMs, allowBlank)
        if (out == null) {
            Log.w(
                TAG,
                "特权命令无输出（通道不可用 / 命令失败）：$cmd" +
                    " | shizukuBound=${ShizukuHelper.serviceBound.value}" +
                    " | accessMode=${RootPowerReader.accessMode}" +
                    " | lastError=${RootPowerReader.lastError}",
            )
        }
        return out
    }

    // ── timestats 的开关与读法（2026-09-22 真机 22081212C / Android 15 实测定案）──────
    //
    // 口径来自 AOSP（`frameworks/native/services/surfaceflinger/TimeStats/TimeStats.cpp`）：
    // - `parseArgs()`：`-disable` → `-dump [-maxlayers N]` → `-clear` → `-enable`。
    //   ⇒ **`-maxlayers` 只是 `-dump` 的伴随参数**（本次 dump 截断到第 N 个图层），
    //   单独执行一条 `--timestats -maxlayers 64` 是**空操作**（真机实测 0 行输出，
    //   对"是否记录逐图层"毫无作用 —— 旧注释写反了）。
    // - `dump()` 开头就是 `if (mTimeStats.statsStartLegacy == 0) return;` ⇒ 未 enable 时
    //   `-dump` **静默返回空**（连 "SurfaceFlinger TimeStats:" 头都没有），这正是本机
    //   "root 正常、帧率恒测不到"的直接成因。故 `-enable` 是必需前置。
    // - 不带任何子命令的 `--timestats`：`parseArgs` 一个分支都不进 ⇒ **永远输出空**，
    //   因此读取只用 `-dump`，plain 兜底那条命令是死代码（见 [readTimestats]）。
    @Volatile
    private var timestatsReady = false

    /**
     * `-enable` 必须真的**执行过**才能置位：通道未就绪（Shizuku 绑定中 / 冷启动首拍）时
     * 本次 exec 返回 null，若照样置位就永远不再重试，后续每拍 `-dump` 都因 timestats
     * 未启用而静默返回空 —— 只能靠 [readTimestats] 的兜底重试慢速自愈。
     * 失败不置位，下一拍采样自然重跑（口径同功率侧「失败不缓存」）。
     */
    private fun ensureTimestatsReady() {
        if (timestatsReady) return
        if (exec("dumpsys SurfaceFlinger --timestats -enable", allowBlank = true) != null) {
            timestatsReady = true
        }
    }

    /**
     * 重置 timestats：`-clear` 清空统计与**逐图层跟踪表**，随后重新 `-enable`。
     *
     * ⚠️⚠️ 必要性（2026-09-25 真机 24031PN0DC / Android 16 / HyperOS V816 实测定案，
     * Shizuku 帧率采不到的第三起事故；前两起见 [timestatsDumpCmd] 与 [readTopPackage] 的注释）：
     *
     * 逐图层统计有**跟踪上限**（AOSP 默认 64）：图层数超限后，新渲染的图层请求跟踪被
     * **静默拒绝**，其帧数永远不计入；而图层随开机时长只增不减（本机开机数日实测
     * **304 个**），于是 dump 里能认领到的全是**早已停止渲染的老图层**——累计值冻结在
     * 历史值（如 213），录制期间一帧不涨 → 差分恒 0 → fps 恒 0.0、丢帧恒 0。
     * 全程命令成功、解析成功，**零日志零报错**；全局段（帧间隔 / 刷新率）不受上限影响
     * 照常出数，于是呈现「刷新率 120Hz、帧间隔 16ms 都对，唯独帧率 0.0」的割裂表现。
     *
     * `-clear` 在清零计数的同时**清空跟踪表**（实测 304 → 5）：此后真正渲染出帧的图层
     * （前台应用）会被重新跟踪并从 0 累计——实测 clear 后前台图层 3s 累计 402 帧 ≈ 120fps。
     * 静止图层不会被跟踪（无帧可计，也无需跟踪）。因此本函数应在**目标应用锁定 / 切换**
     * 时调用（见 FrameRecordController 两个采集循环的目标变化分支），保证被测图层必然
     * 进得了跟踪表。
     *
     * ⚠️ 侵入性：`-clear` 同时清空全局段（presentToPresent 直方图等），会干扰同样在读
     * timestats 的组件（厂商游戏工具箱等）。本应用与它们的使用场景互斥（测帧率时人在
     * 被测应用），且 `-enable` 本身已是侵入，权衡下接受；`frameSpaceMs` 是对**当前快照**
     * 直方图的加权平均，clear 后口径不受影响。差分基线由调用方在目标变化时一并作废
     * （prevFrames = -1），不会算出假尖峰。
     *
     * ⚠️ 必须在后台线程调用（两条命令各 ~300ms）；任一条失败都不缓存，下一拍目标变化
     * 时自然重跑。
     */
    fun resetTimestats() {
        exec("dumpsys SurfaceFlinger --timestats -clear", allowBlank = true)
        if (exec("dumpsys SurfaceFlinger --timestats -enable", allowBlank = true) != null) {
            timestatsReady = true
        }
    }

    /**
     * 取累计帧数快照。@param pkg 目标应用包名（用于从图层名里认领该应用的帧）
     *
     * ⚠️ 读数一律走 `-dump`：plain 形态按 AOSP 口径恒为空。
     * 拿到空输出时**自愈重试一次**（重新 `-enable` 再 dump）—— SurfaceFlinger 重启 /
     * stats 被 `statsd` 拉走清空后，`statsStartLegacy` 会归零导致 dump 变空，
     * 不重试的话用户只能重启 App 才能恢复（2026-09-22 实测）。
     */
    fun readTimestats(pkg: String): Timestats? {
        ensureTimestatsReady()
        val out = exec(timestatsDumpCmd(pkg))?.takeIf { it.isNotBlank() }
            ?: exec("dumpsys SurfaceFlinger --timestats -enable", allowBlank = true)
                .let { exec(timestatsDumpCmd(pkg)) }
                ?.takeIf { it.isNotBlank() }
            ?: return null
        return parseTimestats(out, pkg)
    }

    // ── timestats dump 必须在 shell 层收窄（2026-09-25 修，Shizuku 帧率采不到的第二起事故）──
    //
    // ⚠️⚠️ **整份 `--timestats -dump` 不能直接回传**：图层数随开机时长只增不减
    // （每个渲染过的 Activity / SurfaceView 都留一个图层段），24031PN0DC 开机几天后
    // 实测 **304 个图层、1.23MB**——而 Shizuku 通道的整份输出要跨一次 binder 事务回主进程，
    // Parcel 里 String 按 UTF-16 编码体积翻倍 → 2.46MB > 单事务 ~1MB 上限，logcat 里
    // `Large reply transaction of 2461756 bytes`，整次调用作废 → readTimestats 恒 null
    // → 悬浮 tab 恒「—」、录制 6 秒后报「无可用取数通道」。root 通道走 su 的 stdout
    // 管道不跨 binder，所以又见「root 正常、Shizuku 采不到」。
    // （9-22 修 readTopPackage 时已经总结过「大输出必须在 shell 层收窄」，
    //   当时 timestats 图层还少没超限——它是**慢性病**，图层攒够了才发作。）
    //
    // 修法：管道尾接 awk，只保留解析真正需要的两块——
    // ① 全局段：totalFrames / missedFrames / missedFrames 计数行 + `presentToPresent`
    //    直方图（frameSpaceMs 的唯一来源；逐图层段同名直方图拼写是 present2present，
    //    与 presentToPresent 不互含，不会被误收进第一个直方图段）；
    // ② 图层名含目标包名的图层段的 4 个字段行（layerName / totalFrames / droppedFrames /
    //    missedFrames）——每图层几百字节，与 [parseTimestats] 的认领口径（contains(pkg)）一致。
    // 实测收窄后整包 ~2KB（304 图层 / 3 个目标图层），空包名时只留全局段 ~0.8KB。
    //
    // ⚠️ `keep` 在图层段之间**不会复位**：最后一个匹配图层段之后出现的任何 `totalFrames=`
    // 行（ROM 在图层列表后附加的私有统计段）也会被本管道带回 —— 解析侧必须靠行距守卫
    // （[parseTimestats] 的 FIELD_MAX_LINES_FROM_LAYER）把它们挡在认领之外（2026-09-25 加，
    // 真机 tab 冒 1000+ 假帧率的候选根因）。
    //
    // - `index($0, pkg)` 是字面子串匹配不吃正则，包名里的点不用转义；
    //   pkg 为空时 havePkg=0，一个图层都不认领——与 [parseTimestats] 的空包名守卫同口径。
    // - pkg 只保留 `[A-Za-z0-9._-]` 字符再嵌入命令：防注入的 belt-and-suspenders
    //   （正常包名本就这个字符集，来源是 readTopPackage 的白名单正则）。
    // - awk 不命中也不非零退出，无需 `; true` 兜底退出码；
    //   读到 EOF 才结束（无 early exit），不碰 grep -m1 的 SIGPIPE 老坑。
    // - root（su）通道同一命令同样受益：awk 在 /system/bin/awk，且省去逐层管道体积。
    private fun timestatsDumpCmd(pkg: String): String {
        val needle = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        return "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | awk -v pkg='$needle' '" +
            "BEGIN{havePkg=(pkg!=\"\")} " +
            "/layerName/{inLayer=1; keep=(havePkg && index(\$0,pkg)>0)} " +
            "keep && /layerName|totalFrames|droppedFrames|missedFrames/{print;next} " +
            "!inLayer && /presentToPresent/{hist=1;print;next} " +
            "hist{if(\$0 ~ /[0-9]+ms=/)print;else hist=0} " +
            "!inLayer && /totalFrames|droppedFrames|missedFrames/{print}'"
    }

    /**
     * 解析 timestats。
     *
     * 扫描策略：**记住最近一次出现的 layerName**，遇到 `totalFrames=` / `droppedFrames=`
     * 就归属它。这样无论输出是「一行一个字段」还是「一行里逗号分隔多个字段」都能命中 ——
     * 前者 Android 14+ 常见，后者老版本与部分 ROM 常见，写死任一种都会在另一台上全 0。
     *
     * ⚠️ **行距守卫**（2026-09-25 加，真机 tab 冒 1000+ 假帧率的候选根因）：字段行必须紧跟
     * `layerName` 行 ≤[FIELD_MAX_LINES_FROM_LAYER] 行才认领。收窄用 awk 的 `keep` 在图层段
     * 之间**不会主动复位** —— 最后一个匹配图层段之后出现的任何 `totalFrames=` 行（ROM 在
     * 图层列表后附加的私有统计段，HyperOS 疑似存在）都会被 awk 带回，并归到最后一个目标
     * 图层头上；那是**全屏所有图层规模**的计数，每秒增长 ≈ 图层数 × 刷新率 ≈ 上千，恰是
     * tab 冒 1000+ 的量级。原生 AOSP 的图层列表之后没有输出，且 totalFrames 距 layerName
     * 恰 1 行、droppedFrames 恰 2 行，此守卫对原生格式零影响。
     *
     * ⚠️ **丢帧逐行认领**（2026-09-25 修）：missedFrames / droppedFrames 在**各自所在行**上
     * 认领，不再挂在 totalFrames 同一行 —— Android 14+ 逐图层段是逐字段一行，totalFrames
     * 那一行只有它自己，旧写法在这类 ROM 上丢帧恒 0（jankCount / 详情页丢帧列一直假 0）。
     */
    internal fun parseTimestats(out: String, pkg: String): Timestats {
        var currentLayer: String? = null
        var matchedFrames = 0L
        var matchedMissed = 0L
        var sawAny = false
        val perLayer = HashMap<String, Long>()
        // 距最近一条 layerName 行的行数；MAX_VALUE = 还没见过任何图层名（全局段不可认领）
        var linesSinceLayer = Int.MAX_VALUE

        for (rawLine in out.lineSequence()) {
            val line = rawLine.trim()

            val layer = LAYER_RE.find(line)?.groupValues?.getOrNull(1)
            if (layer != null) {
                currentLayer = layer
                linesSinceLayer = 0
            } else if (linesSinceLayer != Int.MAX_VALUE) {
                linesSinceLayer++
            }
            val claimable = pkg.isNotEmpty() && currentLayer != null &&
                linesSinceLayer <= FIELD_MAX_LINES_FROM_LAYER && currentLayer.contains(pkg)

            val frames = FRAMES_RE.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (frames != null) {
                sawAny = true
                // 图层名里通常带包名（"com.x/.Main#0" 或 "SurfaceView - com.x/.Main#0"）；
                // 包名可能为空（前台未知）→ 此时不认领任何图层，宁可显示 0 也不要张冠李戴
                if (claimable) {
                    matchedFrames += frames
                    perLayer[currentLayer] = (perLayer[currentLayer] ?: 0L) + frames
                }
            }
            // ⚠️ 字段名随 Android 版本分叉（真机 22081212C / Android 15 实测）：
            // **逐图层段只有 `droppedFrames`**，`missedFrames` 只出现在全局 Legacy 段。
            // 两个名字都认；可能与 totalFrames 同行（逗号拼接格式）也可能各自一行
            // （逐字段格式），逐行只认领一次不会重复计数。
            if (claimable) {
                val missed = (MISSED_RE.find(line) ?: DROPPED_RE.find(line))
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (missed != null) matchedMissed += missed
            }
        }

        return Timestats(
            totalFrames = matchedFrames,
            missedFrames = matchedMissed,
            frameSpaceMs = parseFrameSpace(out),
            perLayerFrames = perLayer,
            ).also {
            if (!sawAny) Log.w(TAG, "timestats 未解析到任何 totalFrames 字段")
        }
    }

    /**
     * 从全局 presentToPresent 直方图算加权平均帧间隔。
     *
     * 直方图行形如 `8ms=593 16ms=6 ...`（也可能跨多行），加权平均比"取众数档位"
     * 更能反映长尾卡顿 —— Kite 的 FrameSpace 也是同一个量。
     */
    private fun parseFrameSpace(out: String): Double {
        var weighted = 0.0
        var total = 0L
        var inHistogram = false
        for (rawLine in out.lineSequence()) {
            val line = rawLine.trim()
            if (line.contains("presentToPresent", ignoreCase = true)) {
                inHistogram = true
            } else if (inHistogram && line.isNotEmpty() && !line.contains("=")) {
                // 直方图段结束（遇到下一个小节）
                if (total > 0) break
            }
            if (!inHistogram) continue
            for (m in HISTOGRAM_RE.findAll(line)) {
                val ms = m.groupValues[1].toLongOrNull() ?: continue
                val count = m.groupValues[2].toLongOrNull() ?: continue
                weighted += ms.toDouble() * count
                total += count
            }
        }
        return if (total > 0) weighted / total else 0.0
    }

    // ── CPU 快样（/proc/stat 差分 + 逐核频率，250ms 级，2026-09-25 重构）──────
    //
    // 由「1s 一拍的 readCpuUsage / readCpuMhz」合并而来（用户对照 Scene 工具箱实测
    // 反馈 CPU 两卡「明显不如 Scene」：CPU 使用率与频率是快变量，1s 一点把使用率的
    // 真实抖动、频率的升降挡全部摊平 —— 频率图几乎成台阶）。快样由录制循环的 250ms
    // 子拍**串行**调用：SuSession 常驻 shell 靠 stdin 喂命令，不能另起协程并发跑。

    /** 一次 CPU 快样：全核合计 + 逐核使用率（%），以及逐核实时频率 MHz */
    class CpuFastSample(
        /** 全核合计使用率 %；无差分基线（首拍）/ 命令失败时为 null */
        val totalPct: Double?,
        /** 逐核使用率 %；null = 该核本拍无有效差分（首拍 / 离线核 jiffies 零增长） */
        val corePct: List<Double?>,
        /** 逐核实时频率 MHz；null = 该核 scaling_cur_freq 没读到（离线 / SELinux 拦截） */
        val mhz: List<Double?>,
    )

    /** 上一次 /proc/stat 合计行快照（totalJiffies to idleJiffies）；null = 还没有基线 */
    @Volatile
    private var lastFastTotal: Pair<Long, Long>? = null

    /** 上一次 /proc/stat 逐核快照（下标 = 核心号）；null = 还没有基线 */
    @Volatile
    private var lastFastCores: List<Pair<Long, Long>?>? = null

    /** 上一次快样的墙钟时刻；用于基线新鲜度判定（基线是**进程级**的，跨场次残留） */
    @Volatile
    private var lastFastAt = 0L

    /**
     * 快样基线的新鲜度上限（ms）：距上一次快样超过它（典型 = 两场录制之间的间隔），
     * 差分窗口横跨了停顿期，使用率 = "整个停顿期的平均"，是假读数 —— 按无差分处理，
     * 本拍只建基线。正常间隔 250~300ms，取 2s 留足余量。
     */
    private const val FAST_BASELINE_FRESH_MS = 2_000L

    /**
     * 解析一行 /proc/stat 的 cpu 条目（`cpu ` 合计行或 `cpuN` 逐核行），
     * 返回 totalJiffies to idleJiffies（idle = idle + iowait，与 top/load 的常用口径一致）。
     */
    private fun parseCpuStatLine(line: String): Pair<Long, Long>? {
        val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        val idle = fields[3] + fields.getOrElse(4) { 0L }
        return fields.sum() to idle
    }

    /** 单条差分：使用率 % = (Δtotal - Δidle) / Δtotal；Δtotal ≤ 0（离线核 / 时钟回拨）按无效 */
    private fun diffCpuPct(prev: Pair<Long, Long>, cur: Pair<Long, Long>): Double? {
        val dTotal = (cur.first - prev.first).toDouble()
        if (dTotal <= 0.0) return null
        val dIdle = (cur.second - prev.second).toDouble()
        return ((dTotal - dIdle) / dTotal * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * CPU 快样：`cat /proc/stat` + 逐核 scaling_cur_freq **一条命令**取回（见
     * [CPU_FAST_CMD]），/proc/stat 与上一拍快样差分出使用率 —— 使用率是差分窗口内的
     * 平均值，窗口 = 两次快样的实际间隔（250ms 级）。逐核行按核心号对齐，核心数不写死
     * （列表长度 = 本拍读到的最大核心号 + 1）。
     *
     * ⚠️ totalPct / corePct / mhz 里 null 的语义：「该项没有有效差分 / 没读到」
     * （首拍还没基线 / 核心离线 / 节点不可读），调用方聚合时跳过，**绝不猜 0** ——
     * 空值在详情页是断线，不是「使用率为 0 / 频率为 0」。
     *
     * ⚠️ 整份输出拿回来、本地解析：只有几 KB，远够不着 binder 上限
     * （[readTopPackage] 的教训是"大输出必须在 shell 层收窄"，不是"一律禁止整份回传"）。
     *
     * ⚠️ 必须在后台线程调用（内部起进程）。
     */
    fun readCpuFastSample(): CpuFastSample? {
        val out = exec(CPU_FAST_CMD) ?: return null
        var totalNow: Pair<Long, Long>? = null
        val coresNow = HashMap<Int, Pair<Long, Long>>()
        val mhzNow = ArrayList<Double?>(8)
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("cpu") -> {
                    val stat = parseCpuStatLine(line) ?: continue
                    val name = line.substringBefore(' ')
                    if (name == "cpu") totalNow = stat
                    else name.removePrefix("cpu").toIntOrNull()?.let { coresNow[it] = stat }
                }
                // 频率行形如 "freq3 1804800"；节点没读到时 echo 只吐前缀（无数字），
                // 该核按序号留空位，后续核心不会错位
                line.startsWith("freq") -> FREQ_LINE_RE.find(line)?.let { m ->
                    val idx = m.groupValues[1].toInt()
                    while (mhzNow.size <= idx) mhzNow.add(null)
                    mhzNow[idx] = m.groupValues[2].toLongOrNull()?.let { it / 1_000.0 }
                }
            }
        }
        if (totalNow == null && mhzNow.isEmpty()) return null
        // 逐核快照按核心号对齐存储；核心数以本拍实际读到的为准
        val coreList = (coresNow.keys.maxOrNull() ?: -1).let { maxIdx ->
            (0..maxIdx).map { coresNow[it] }
        }
        val nowMs = System.currentTimeMillis()
        val fresh = nowMs - lastFastAt <= FAST_BASELINE_FRESH_MS
        lastFastAt = nowMs
        val totalPct =
            if (fresh) lastFastTotal?.let { prev -> totalNow?.let { cur -> diffCpuPct(prev, cur) } }
            else null
        val corePct = if (fresh) {
            lastFastCores?.let { prev ->
                coreList.mapIndexed { i, cur ->
                    prev.getOrNull(i)?.let { p -> cur?.let { c -> diffCpuPct(p, c) } }
                }
            }
        } else null
        lastFastTotal = totalNow
        lastFastCores = coreList
        return CpuFastSample(totalPct, corePct ?: List(coreList.size) { null }, mhzNow)
    }

    private val FREQ_LINE_RE = Regex("""freq(\d+)\s+(\d+)""")

    /**
     * CPU 快样命令：/proc/stat 与逐核频率**一次 exec 取回**（拆成两条的话 250ms 子拍里
     * fork 次数翻倍）。两段输出靠行首形态区分：/proc/stat 行都有标签（cpu/cpuN/intr/ctxt…），
     * 频率段每核先 echo 出 `freqN` 前缀再接节点值 —— 某核节点读不到时该行只有前缀没有
     * 数字，解析按序号入位，后续核不会错位。
     *
     * `\$i` / `\$(` 是 shell 变量与命令替换，在 Kotlin 字符串里必须转义（见旧 CPU_CMD 注释）。
     */
    private val CPU_FAST_CMD: String =
        "cat /proc/stat; " +
            "for i in 0 1 2 3 4 5 6 7; do " +
            "echo \"freq\$i \$(cat /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_cur_freq 2>/dev/null)\"; " +
            "done"

    /**
     * 当前前台应用包名；解析不到时返回空串（调用方按"未知"处理）
     *
     * ⚠️ **不用 `| grep -m1`**（2026-09-22 实测改掉）：`grep -m1` 命中后立刻关闭管道读端，
     * 上游 `dumpsys` 随即被 SIGPIPE 杀死，连接层把这次执行判成"无输出" —— 在通过 stdin
     * 喂命令的会话里尤为常见（真机复现：`dumpsys activity activities | grep -m1 topResumedActivity`
     * 整条返回空，而同一命令换个写法就有值）。包名就是这么变成空的，而空的包名会让
     * [parseTimestats] 一个图层都不认领 → 帧率恒等于 0。
     *
     * ⚠️⚠️ 但也**不能整份输出拿回来**（2026-09-22 修，Shizuku 模式帧率恒采不到的根因）：
     * `dumpsys activity activities` 在现代 ROM（HyperOS，装几百个应用）上输出可达
     * 几百 KB 甚至 1MB+，而 **Shizuku 通道的整份输出要跨**一次 binder 事务回主进程
     * （[com.chen.powermeter.shizuku.ShellService] 的 `String exec()` 返回值）——
     * binder 单事务上限约 1MB，超限直接 TransactionTooLargeException，整次调用作废
     * → 包名恒空 → 目标永远锁不住 → 帧率页永远「—」、录制 6 秒后报「无可用取数通道」。
     * root 通道的输出走 su 的 stdout 管道，不跨 binder，所以"root 正常、Shizuku 采不到"。
     *
     * 修法：`| grep topResumedActivity`（**不带 -m1**）在 shell 层把输出收窄成一两行再回传。
     * 不带 -m1 的 grep 会把输入**读完才退出**，不存在上面那条 SIGPIPE 路径，两条经验不冲突。
     * 尾部 `; true` 兜底退出码：目标行不存在时 grep rc=1，不能让整条命令被判成失败
     * （口径同 [com.chen.powermeter.data.RootPowerReader] 的 binderDump）。
     */
    fun readTopPackage(): String {
        val out = exec(TOP_PACKAGE_CMD) ?: return ""
        // 形如：topResumedActivity=ActivityRecord{... u0 com.chen.powermeter/.MainActivity t123}
        for (line in out.lineSequence()) {
            if (!line.contains("topResumedActivity")) continue
            val m = Regex("""u0\s+([A-Za-z0-9_.\-]+)/""").find(line) ?: continue
            return m.groupValues[1]
        }
        return ""
    }

    private val TOP_PACKAGE_CMD: String =
        "dumpsys activity activities 2>/dev/null | grep topResumedActivity; true"

    /**
     * 当前显示刷新率 Hz（`dumpsys display` 的 mActiveRenderFrameRate）。
     *
     * ⚠️ 与 [readTopPackage] 同一条铁律：**不用 `| grep -m1`**（2026-09-22 修）——
     * grep 命中后立刻关闭管道读端，上游 `dumpsys` 随即被 SIGPIPE 杀死，连接层把这次
     * 执行判成"无输出"→ 刷新率偶发读 0。改成整份输出拿回来、本地找第一处
     * `mActiveRenderFrameRate`：一次进程创建没增加，却消除了 SIGPIPE 这条失败路径。
     * （本函数只在 `sessionRefreshHz == 0` 时重试，整份输出大也无妨。）
     *
     * ⚠️ 2026-09-22 再收窄成 `| grep mActiveRenderFrameRate`（不带 -m1，SIGPIPE 逻辑同上）：
     * `dumpsys display` 整份输出也有几十上百 KB，Shizuku 通道没必要跨 binder 搬运，
     * shell 层过滤后只剩一两行（[readTopPackage] 的 binder 上限事故同一预防）。
     */
    fun readRefreshRateHz(): Int {
        val out = exec(REFRESH_RATE_CMD) ?: return 0
        return Regex("""mActiveRenderFrameRate\s*=\s*(-?\d+(?:\.\d+)?)""")
            .find(out)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.let { kotlin.math.round(it).toInt() }
            ?: 0
    }

    private val REFRESH_RATE_CMD: String =
        "dumpsys display 2>/dev/null | grep mActiveRenderFrameRate; true"

    /**
     * 图层名：捕获到**行尾**而不是 `[^,\s]+`。
     *
     * ⚠️ 旧写法在第一个空格处截断，于是 `SurfaceView - com.x/.A#0`（游戏 / 视频播放器 /
     * 地图这类自绘场景的典型图层名）只抓到 `SurfaceView` —— 不含包名 → 永远认领不到，
     * 这类被测应用会全体显示 0 帧。
     *
     * ⚠️⚠️ `[=:]` 必须写成 `\s*[=:]`（2026-09-22 真机 22081212C / Android 15 实测事故）：
     * AOSP 的 dump 文本是 `"layerName = %s\n"`（TimeStatsHelper.cpp:109，**等号前有空格**），
     * 旧正则 `layerName[=:]` 要求等号紧跟字段名 → **整份输出一个图层都匹配不上** →
     * currentLayer 恒 null → 认领帧数恒 0 → 差分恒 0 → 悬浮 tab 永远显示「0.0」，
     * 且 sawAny=true（全局段的 totalFrames 命中了）不触发任何告警，全程静默。
     * FRAMES/MISSED/DROPPED 三个正则写的是 `\s*=\s*` 所以没事，唯独这里漏了空格。
     */
    private val LAYER_RE = Regex("""layerName\s*[=:]\s*(.+)""")
    private val FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")
    private val MISSED_RE = Regex("""missedFrames\s*=\s*(\d+)""")
    /** Android 14+ 逐图层段的丢帧字段名（老版本与全局 Legacy 段用 missedFrames） */
    private val DROPPED_RE = Regex("""droppedFrames\s*=\s*(\d+)""")
    private val HISTOGRAM_RE = Regex("""(\d+)ms\s*=\s*(\d+)""")

    /**
     * 字段行允许距 `layerName` 行的最大行距（行距守卫，见 [parseTimestats]）。
     * 原生格式下 totalFrames 恰为 1 行、droppedFrames 恰为 2 行；图层段之后附加的
     * 私有统计段至少再远一行，取 2 恰好把它们挡在认领之外。
     */
    private const val FIELD_MAX_LINES_FROM_LAYER = 2

    // ── 虚拟温度（CPU 代表温感区，录制期间按 5s 抽稀）──────────
    //
    // ⚠️ 电量四项（电压 / 电流 / 功率 / 电池温度）**不走这里**（2026-09-22 起）：
    // FrameRecordController 直接调 `RootPowerReader.read()`，与功率监测同一条取数链
    // （root 机器 sysfs 节点、Shizuku 机器 BatteryManagerSource 的 CURRENT_NOW 实时电流）。
    // 原先在此自采 `dumpsys battery` + `dumpsys thermalservice` ibat，实测三处硬伤：
    // ① 22081212C 的 thermalservice 没有 ibat/type=7 字段，电流与功率恒为空；
    // ② ibat 未按功率侧口径取反（本应用统一"正=充电"）；
    // ③ 单位靠 |v|<100 启发式判定，换 ROM 有误判风险。

    /**
     * CPU 代表温度 + GPU 温度，**一条命令同时取回**（2026-09-25 合并：原先两个函数各自
     * fork 一轮 thermal_zone 遍历，录制慢速拍平白多花一倍耗时，把采样周期拉得更长）。
     * 返回 (cpuTempC, gpuTempC)。
     *
     * 口径：
     * - CPU = "cpu/soc/cluster/ap" 类温感区里**最热**的那个（温感区命名千奇百怪，contains
     *   宽松匹配；一个都没匹配上退回全部温感区的最大值）——"这台机器现在多烫"的代表值；
     * - GPU = type 含 "gpu" 的温感区里最热的那个；机型没有 GPU 温感区时为 null
     *   （Temperature 卡 GPU 线整段缺失，不画成 0）；
     * - null = 该侧读不到（命令失败 / 一个区段都没解析到），调用方沿用上一次的值。
     *
     * ⚠️ 必须在后台线程调用（内部起进程）。
     */
    fun readCpuGpuTempsC(): Pair<Double?, Double?> {
        val out = exec(VIRTUAL_TEMP_CMD) ?: return null to null
        val zones = out.lineSequence().mapNotNull { line ->
            val m = THERMAL_ZONE_RE.find(line.trim()) ?: return@mapNotNull null
            val milliC = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            m.groupValues[1] to milliC / 1_000.0
        }.toList()
        if (zones.isEmpty()) return null to null
        val cpuTemps = zones
            .filter { z -> CPU_ZONE_KEYWORDS.any { z.first.contains(it, ignoreCase = true) } }
            .map { it.second }
        val gpuTemps = zones
            .filter { z -> z.first.contains("gpu", ignoreCase = true) }
            .map { it.second }
        return (cpuTemps.maxOrNull() ?: zones.maxOfOrNull { it.second }) to gpuTemps.maxOrNull()
    }

    /**
     * GPU 占用率 %（FPS 卡右轴可切换的 GPU(%) 线）：高通 kgsl 的 gpu_busy_percentage，
     * 节点内容形如 "42 %"。内容异常时返回 null（断线处理）。
     *
     * ⚠️ 实机验证（24031PN0DC / HyperOS V816，2026-09-25）：shell 对 /sys/class/kgsl
     * **全目录 Permission denied**（SELinux 策略，同 power_supply 节点），Shizuku 模式
     * 在该机型恒 null——UI 侧按"整场无数据即隐藏选项"处理；root 身份可读（未验证）。
     * 系统内也无其它可读的 GPU 利用率节点（tracing events 除外）。
     * GPU **温度**不受影响：gpuss-0..4 温感区 shell 可读（readCpuGpuTempsC 正常出数）。
     *
     * ⚠️ **allowBlank 必须为 true**（2026-09-25 修）：本函数**每拍**都在跑，而节点不可读时
     * `cat` rc=0 但无输出是这台机器的**常态结论**——按失败处理会让空输出走完整个通道回退链
     * （Shizuku → su 再试一轮）且 [exec] 每秒刷一条 warning，白白拖长每拍耗时、刷屏日志。
     * 空输出在此处本来就是"无 GPU 占用数据"的有效结论，返回 "" → 解析为 null 即可。
     *
     * ⚠️ 必须在后台线程调用（内部起进程）。
     */
    fun readGpuLoadPct(): Double? {
        val out = exec(GPU_LOAD_CMD, allowBlank = true) ?: return null
        val digits = out.trim().takeWhile { it.isDigit() }
        return digits.ifEmpty { null }?.toDoubleOrNull()
    }

    private val THERMAL_ZONE_RE = Regex("""^(\S+)\s+(-?\d+)$""")

    private val CPU_ZONE_KEYWORDS = arrayOf("cpu", "soc", "cluster", "ap")

    /**
     * 温感区 type/temp 一把读。`$z` / `$(cat ...)` 都是 **shell 变量与命令替换**，
     * 在 Kotlin 字符串里必须转义成 `\$`，否则会被当成 Kotlin 模板引用不存在的变量。
     */
    private val VIRTUAL_TEMP_CMD: String =
        "for z in /sys/class/thermal/thermal_zone*; do " +
            "echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done"

    /** GPU 占用率节点（高通 kgsl；内容形如 "42 %"，只有主 GPU 一份） */
    private val GPU_LOAD_CMD: String =
        "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null"

    /** 整数帧率格式化（Locale.US：小数点是点，不受系统语言影响） */
    internal fun formatFps(fps: Double): String = String.format(Locale.US, "%.0f", fps)
}
