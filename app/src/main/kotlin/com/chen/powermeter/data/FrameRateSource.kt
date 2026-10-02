package com.chen.powermeter.data

import android.os.Build
import android.util.Base64
import android.util.Log
import com.chen.powermeter.util.ShizukuHelper
import java.util.Locale
import kotlin.math.roundToInt

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
        /**
         * 全局 presentToPresent 直方图（桶 = 整毫秒 → 帧数；**自 -clear 起累计**）。
         * ⚠️ 上层对相邻快照做**直方图差分**才得到「当秒」帧间隔 —— 累计值是一条衰减收敛
         * 曲线（开局加载的大间隔被逐渐稀释），既读不出当秒、也判不了短谷真假
         * （2026-09-27 帧时间卡改差分口径的根由，见 FrameRecordController.p2pDeltaHistogram）。
         */
        val p2pHistogram: Map<Int, Long> = emptyMap(),
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
     *
     * ⚠️ ROM 不认 `-maxlayers` 的降级判定（2026-09-27 加，动机与口径见
     * [timestatsDumpCmd] 上方注释）：输出**非空却不含 `totalFrames`** = 拿到的是
     * usage/错误文本而非统计 —— 拉黑后换全量命令立即重读一次。正常 dump（哪怕刚
     * clear 完一帧还没渲）的全局段恒有 totalFrames（0 也打印），不会误入此分支；
     * 误拉黑的代价只是退回全量 dump（慢/噪，功能无损）。
     */
    fun readTimestats(pkg: String): Timestats? {
        ensureTimestatsReady()
        var out = exec(timestatsDumpCmd(pkg))?.takeIf { it.isNotBlank() }
        if (out == null && !timestatsMaxLayersDead) {
            // 裸命令失败（Shizuku 瞬断，或 ROM 无视 -maxlayers 把全量 dump 灌爆 binder
            // 事务——异常形态返回 null，探测不到 usage 文本）→ 立即用 awk 收窄版重试；
            // 收窄版能出数 = 通道活着而 maxlayers 有问题，直接拉黑（代价只是退回旧管道）
            out = exec(timestatsFullDumpCmd(pkg))?.takeIf { it.isNotBlank() }
            if (out != null) timestatsMaxLayersDead = true
        }
        if (out == null) {
            out = exec("dumpsys SurfaceFlinger --timestats -enable", allowBlank = true)
                .let { exec(timestatsDumpCmd(pkg)) }
                ?.takeIf { it.isNotBlank() }
            ?: return null
        }
        if (!timestatsMaxLayersDead && !out.contains("totalFrames")) {
            timestatsMaxLayersDead = true
            out = exec(timestatsDumpCmd(pkg))?.takeIf { it.isNotBlank() } ?: return null
        }
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
    // 行（ROM 在图层列表后附加的私有统计段）也会被本管道带回 —— 解析侧必须靠「图层块边界」
    // （[parseTimestats] + [TIMESTATS_KV_RE]）把它们挡在认领之外（2026-09-25 加，
    // 真机 tab 冒 1000+ 假帧率的候选根因；2026-10-01 由行距守卫改为块边界）。
    //
    // - `index($0, pkg)` 是字面子串匹配不吃正则，包名里的点不用转义；
    //   pkg 为空时 havePkg=0，一个图层都不认领——与 [parseTimestats] 的空包名守卫同口径。
    // - pkg 只保留 `[A-Za-z0-9._-]` 字符再嵌入命令：防注入的 belt-and-suspenders
    //   （正常包名本就这个字符集，来源是 readTopPackage 的白名单正则）。
    // - awk 不命中也不非零退出，无需 `; true` 兜底退出码；
    //   读到 EOF 才结束（无 early exit），不碰 grep -m1 的 SIGPIPE 老坑。
    // - root（su）通道同一命令同样受益：awk 在 /system/bin/awk，且省去逐层管道体积。
    // ── `-dump -maxlayers` 截断（2026-09-27 加，真机 24031PN0DC 实测）──────────
    //
    // ⚠️⚠️ 动机 = 差分 dt 的**计时噪声**：帧数快照在 SF 内部完成，时间戳却打在 dump
    // 传回之后，中间隔着「dumpsys 拼整份文本 → 管道 → awk」。图层数随开机只增不减
    // （几百个、~700KB），这条尾巴实测 145~200ms、拍间抖动 ±20-40ms —— dt 误差在
    // 120Hz 下就是 ±2.9fps 的读数噪声（30 拍审计实测单拍 σ=2.4%），再叠加
    // FPS_LIVE_CEILING_TOLERANCE 拒收式守卫砍掉噪声高侧，实时/落库读数的重心被
    // 系统性压低 ~1.5fps（用户实测"Scene 120 本应用 115"的构成之一）。
    //
    // 修法 = `-dump -maxlayers 8`：**SF 侧**只拼 totalFrames 降序前 8 个图层的文本，
    // 尾巴实测降到 53~79ms，每拍仍是独立的 1s 测量（不是平滑，是量得更准）。正确性：
    // - 条目全局按 totalFrames 降序，resetTimestats 后前台目标涨得最快、必然第一；
    // - updateFpsFromDiff 逐图层取 max，只要「主导表面」在列读数就正确 —— 而主导表面
    //   恰是目标名下帧数最多的条目，8 槽内必含它；
    // - 语义损失：目标某低帧率条目被挤出 8 槽会读不到 → totalFrames=0 → NaN（「—」）。
    //   需要 ≥8 个非目标条目比目标主导表面累计帧数更多才触发，clear 后实际不可达；
    // - 全局段（presentToPresent / frameSpaceMs）在图层段之前，不受截断影响。
    //
    // ⚠️ 兼容性兜底（timestatsMaxLayersDead）：AOSP parseArgs 自 timestats 引入起支持
    // `-maxlayers`，但 ROM 私改不认参数时会输出 usage/错误文本 —— 特征是**输出非空却
    // 不含 totalFrames**（正常 dump 的全局段恒有它，0 也打印）。命中即拉黑、本进程
    // 回落全量命令（退回旧行为：慢/噪，功能无损）。判定在 readTimestats。
    private const val TIMESTATS_MAX_LAYERS = 8

    /** ROM 不认 `-maxlayers` 的拉黑标记（判定口径见上方注释）；false = 用截断命令 */
    @Volatile
    private var timestatsMaxLayersDead = false

    private fun timestatsDumpCmd(pkg: String): String {
        if (!timestatsMaxLayersDead) {
            // ⚠️ 裸命令（2026-09-29 去 awk）：-maxlayers 8 下 SF 侧只拼全局段 + 8 图层段
            //（几 KB，远够不着 binder 事务上限），不再需要 shell 层收窄 —— 省掉管道里的
            // awk（每拍 1 fork），输出直接回主进程由 [parseTimestats]/[parseP2pHistogram]
            // 解析。两个解析函数本来就按原始格式写正则（awk 只是预收窄），逐图层段仅 8 个、
            // 行距守卫照常生效；全局 presentToPresent 直方图之后的收口逻辑见
            // parseP2pHistogram（非 "=" 行闭合，逐图层 present2present 不互含）。
            return "dumpsys SurfaceFlinger --timestats -dump -maxlayers $TIMESTATS_MAX_LAYERS 2>/dev/null"
        }
        return timestatsFullDumpCmd(pkg)
    }

    /**
     * 全量 dump 的 awk 收窄版（[timestatsDumpCmd] 的 ROM 不认 `-maxlayers` / 裸命令异常
     * 时的回退）：管道尾接 awk 只保留全局段 + 目标图层段字段行（~2KB），整份 ~1.23MB
     * 的输出跨 binder 的 UTF-16 回传会超 ~1MB 事务上限。
     */
    private fun timestatsFullDumpCmd(pkg: String): String {
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
     * ⚠️ **图层块边界**（2026-10-01 改，真机 24031PN0DC / Android 16 / HyperOS OS3.0.306 实测定案）：
     * 认领只在「当前图层块内」成立，块的**结束判据 = 出现任何非 `key = value` 行**（段落标题
     * `Jank payload for this layer:` / `… histogram is as below:`，或直方图数据行 `0ms=21 …`）。
     * 图层身份属性（layerName / packageName / gameMode）与计数（totalFrames / droppedFrames /
     * lateAcquireFrames）全是 `key = value`，必然排在本块第一个段落标题之前 → 天然被包在块内；
     * 而图层列表之后 ROM 追加的私有统计段必然落在直方图之后 → 落在块外，照旧挡掉（旧注释记录的
     * 「tab 冒 1000+ 假帧率」正是被这类段落的 totalFrames 污染，见 [TIMESTATS_KV_RE]）。
     *
     * ⚠️⚠️ 旧实现是**硬编码行距上限 2**（FIELD_MAX_LINES_FROM_LAYER），前提是字段序恒为
     * `layerName → totalFrames → droppedFrames`（各距 1 / 2 行）。本 ROM 实测字段序为
     * `layerName → packageName → gameMode → totalFrames → droppedFrames`
     * （totalFrames 距 **3** 行、droppedFrames 距 **4** 行，`-clear` 前后一致）→
     * **全部图层的计数被守卫拒绝** → matchedFrames 恒 0 → 上层走「目标已锁定但没认领到图层」
     * 分支 → 帧率恒「—」。且全局 Legacy 段的 `totalFrames` 已让 [sawAny] 为 true，
     * 「timestats 未解析到任何 totalFrames 字段」这条 warning **不触发，全程零日志零告警**。
     * 新判据不依赖字段个数与顺序，ROM 换字段序不再碎。
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
        // 是否处于某个图层块的「可认领区」；false = 尚未见到 layerName，或已越过本块末尾
        var inLayerBlock = false

        for (rawLine in out.lineSequence()) {
            val line = rawLine.trim()

            val layer = LAYER_RE.find(line)?.groupValues?.getOrNull(1)
            if (layer != null) {
                currentLayer = layer
                inLayerBlock = true
            } else if (inLayerBlock && line.isNotEmpty() && !TIMESTATS_KV_RE.containsMatchIn(line)) {
                // 段落标题 / 直方图数据行 = 本图层块到此为止（见上方「图层块边界」）
                inLayerBlock = false
            }
            val claimable = pkg.isNotEmpty() && inLayerBlock &&
                currentLayer != null && currentLayer.contains(pkg)

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

        val (frameSpaceMs, p2p) = parseP2pHistogram(out)
        return Timestats(
            totalFrames = matchedFrames,
            missedFrames = matchedMissed,
            frameSpaceMs = frameSpaceMs,
            perLayerFrames = perLayer,
            p2pHistogram = p2p,
            ).also {
            if (!sawAny) Log.w(TAG, "timestats 未解析到任何 totalFrames 字段")
        }
    }

    /**
     * 解析全局 presentToPresent 直方图：返回（累计加权平均帧间隔 ms，逐桶计数）。
     *
     * 直方图行形如 `8ms=593 16ms=6 ...`（也可能跨多行），加权平均比"取众数档位"
     * 更能反映长尾卡顿 —— Kite 的 FrameSpace 也是同一个量。
     *
     * ⚠️ 返回的平均值是**自 -clear 起的累计口径**（桶按整毫秒取整，低估真实间隔）；
     * 逐桶计数（[Timestats.p2pHistogram]）供上层做相邻快照差分，得到真正的「当秒」
     * 帧间隔 —— 判别"FPS 低谷是真实掉帧还是计时伪差"只有差分口径能做。
     */
    private fun parseP2pHistogram(out: String): Pair<Double, Map<Int, Long>> {
        var weighted = 0.0
        var total = 0L
        var inHistogram = false
        val buckets = HashMap<Int, Long>()
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
                val ms = m.groupValues[1].toIntOrNull() ?: continue
                val count = m.groupValues[2].toLongOrNull() ?: continue
                weighted += ms.toDouble() * count
                total += count
                buckets[ms] = (buckets[ms] ?: 0L) + count
            }
        }
        val avg = if (total > 0) weighted / total else 0.0
        return avg to buckets
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
     * ⚠️ 必须在后台线程调用（直读 = 同步 binder + 文件 IO；回退 exec 时内部起进程）。
     */
    fun readCpuFastSample(): CpuFastSample? {
        // ⚠️ 直读优先（2026-09-29）：UserService 进程内 java.io 读 /proc/stat + 逐核频率，
        // 零 fork —— CPU 快样每 250ms 子拍一次，是采样循环里最高频的 exec，fork 成本
        // （满载 100~200ms）曾把 1s 拍拖到 1.35s。直读输出与 CPU_FAST_CMD 的 awk 输出
        // 逐行同构，解析零改动。直读不可用（未绑定 / 旧版 UserService / 异常）回退
        // awk 单进程版；个别 ROM 裁剪 awk 时再退逐核 shell 循环（见 CPU_FAST_CMD 注释）。
        val out = ShizukuHelper.readCpuFastSampleDirect()
            ?: exec(CPU_FAST_CMD)
            ?: exec(CPU_FAST_CMD_LEGACY)
            ?: return null
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
     * CPU 快样命令（**单进程 awk**，2026-09-26 改）：/proc/stat 的 cpu 行与逐核频率一次
     * exec 取回。旧写法 `cat /proc/stat` + 每核一次 `$(cat ...)` 命令替换 = 每子拍 9 次
     * 进程创建、每秒 36 次，游戏满载时每次 fork 都被排队，一个子拍就要 200~600ms ——
     * 250ms 子拍名存实亡、完整拍被整体拖长（真机 xlsx 实测平均 5.45s/条的主因之一）。
     * awk 版 fork 数从 9 → 1，输出格式与旧命令逐行兼容（cpu 行原样、freq 行 `freqN <值>`）。
     *
     * 输出两段靠行首形态区分：/proc/stat 行以 `cpu` 开头（`cpu` 合计行与 `cpuN` 逐核行），
     * 频率段每核一行 `freqN`；节点读不到时该行只有前缀没有数字，解析按序号留空位，
     * 后续核心不会错位。
     */
    private val CPU_FAST_CMD: String =
        "awk 'BEGIN{" +
            "while((getline l < \"/proc/stat\")>0)if(l ~ /^cpu/)print l;" +
            "for(i=0;i<8;i++){" +
            "f=\"/sys/devices/system/cpu/cpu\" i \"/cpufreq/scaling_cur_freq\";v=\"\";" +
            "getline v < f;print \"freq\" i \" \" v}}'"

    /**
     * CPU 快样旧命令（awk 不可用时的兜底）：每核一次命令替换，9 fork/子拍。
     * `\$i` / `\$(...)` 是 shell 变量与命令替换，在 Kotlin 字符串里必须转义。
     */
    private val CPU_FAST_CMD_LEGACY: String =
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
     * timestats 的 `key = value` 行（图层身份属性与计数都是这个形态）。
     * 不命中 = 段落标题（`Jank payload for this layer:` / `… histogram is as below:`）或
     * 直方图数据行（`0ms=21 1ms=0 …`，以数字开头）→ 视为当前图层块结束（见 [parseTimestats]）。
     *
     * ⚠️ 键名限定为**无空格的标识符**，正是这一点让 `Jank payload for this layer:` 不能命中
     * （"Jank" 之后是空格 + "payload"，凑不出 `[=:]`）—— 它是本 ROM 每个图层块的第一个段落标题，
     * 也就是把块关掉的那一行。换成「含 `=` 即算字段行」会把直方图数据行也放进来，守卫失效。
     */
    private val TIMESTATS_KV_RE = Regex("""^[A-Za-z_][A-Za-z0-9_]*\s*[=:]""")

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
     * - null = 该侧读不到（命令失败 / 一个区段都没解析到），调用方沿用上一次的值；
     *   ROM 裁剪 awk 时自动退回 legacy 逐区循环（见 [VIRTUAL_TEMP_CMD_LEGACY]）。
     *
     * ⚠️ 必须在后台线程调用（直读 = 同步 binder + 文件 IO；回退 exec 时内部起进程）。
     */
    fun readCpuGpuTempsC(): Pair<Double?, Double?> {
        // 直读优先（零 fork，每 5s 错峰一次的频率不敏感，但同享去 fork 收益）；不可用回退
        // awk 单进程版；个别 ROM 裁剪 awk 时再退逐区 shell 循环（兜底口径同 readCpuFastSample）
        var out = ShizukuHelper.readThermalTempsDirect()
            ?: exec(VIRTUAL_TEMP_CMD)
        if (out == null && !virtualTempLegacyDead) {
            out = exec(VIRTUAL_TEMP_CMD_LEGACY)
            if (out == null && ShizukuHelper.serviceBound.value) virtualTempLegacyDead = true
        }
        out ?: return null to null
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
     * GPU 占用率 + 频率，**一条命令/一次直读同时取回**（2026-09-29 加频率段：候选池口径对齐
     * Metric helper 的 GpuSampler；本机 kgsl/ged 全被 SELinux 拦 → 频率恒缺，节点可读的机型自动出数）。
     * 返回 (占用率 %, 频率 MHz)。允许只出其一（占用率命中而频率全拦是常见组合）。
     *
     * 占用率口径（沿革见下）：候选节点按厂商分叉，第一个非空命中；
     * ⚠️ 节点**按厂商分叉**，[GPU_LOAD_CMD] 单进程 awk 按候选序探测、读到第一个非空即回：
     * ① `/sys/class/kgsl/kgsl-3d0/gpubusy` —— 高通 kgsl 双数对（"busy total" 微秒），本
     *    awk 分支直接算 busy÷total×100（total=0 = GPU 整窗断电，按 0 处理）；
     * ② `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` —— 高通经典节点（内容形如 "42 %"）；
     * ③ `/sys/kernel/gpu/gpu_busy` —— 通用 GPU sysfs（Exynos 常见可读）；
     * ④ `/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load` —— kgsl 的 devfreq 挂点；
     * ⑤⑥⑦⑧ MTK/Mali：ged gpu_utilization、mali0 utilisation、ged parameters gpu_loading、
     *    devfreq mali_ondemand utilisation。①以外输出原样透传，解析取首个数字。
     *
     * ⚠️ 实机定案（24031PN0DC / HyperOS V816，2026-09-28）：**gpubusy 可读** ——
     * 同目录的 gpu_busy_percentage 被拦、gpubusy 放行：SELinux 按文件标签逐一判定，
     * 不能拿"kgsl 目录被拦"推断全拦（09-27 的"本机恒 null、选项自动隐藏"结论据此修正）。
     * 取证路径：Scene（同为 Shizuku 模式无 root）在同机录出 GPU(%) 且 GPU(KHz)=-1 →
     * 抠它 APK 里的 sysfs 候选池 → adb shell（与 Shizuku 同为 uid 2000/shell 上下文，
     * 测试结论对 Shizuku 等效）逐点实测，候选里仅 gpubusy 返回数据。
     * 节点实测语义：busy/total 微秒对（如 219056/1001290 ≈ 22%），GPU 断电的整窗返回
     * "0 0"、有载窗口 ~1s 规模、快速连读不清零 —— 每拍读一次，busy/total 即该窗口占用率
     * （游戏满载时 GPU 恒上电，比值 ≈ 真实占用率；Scene 同款数据源同款口径）。
     *
     * ⚠️ **allowBlank 必须为 true**（2026-09-25 修）：本函数**每拍**都在跑，而节点不可读时
     * awk 空输出是这类机器的**常态结论**——按失败处理会让空输出走完整个通道回退链
     * （Shizuku → su 再试一轮）且 [exec] 每秒刷一条 warning。空输出在此处本来就是
     * "无 GPU 占用数据"的有效结论，返回 "" → 解析为 null 即可。
     *
     * ⚠️ 无 legacy 兜底（对比温度/CPU 快样）：awk 单进程读 8 个文件成本可忽略，真缺 awk 的
     * ROM 连 CPU 快样都死了，GPU 这条辅助线跟着 null（UI 隐藏选项）是可接受的一致降级，
     * 不值得为它养多 fork/拍的 shell 循环。
     *
     * ⚠️ 必须在后台线程调用（直读 = 同步 binder + 文件 IO；回退 exec 时内部起进程）。
     */
    fun readGpuLoadFreq(): Pair<Double?, Double?> {
        // 直读优先（UserService 进程内读节点，零 fork；gpubusy 的窗口由内核维护，
        // 1s 一次读恰好是当拍窗口）；直读不可用回退 awk 探测命令，口径完全一致
        val out = ShizukuHelper.readGpuLoadDirect()
            ?: exec(GPU_LOAD_CMD, allowBlank = true)
            ?: return null to null
        var load: Double? = null
        var freqMhz: Double? = null
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("freq") -> {
                    // 频率行 `freq <原始值>`：单位不统一（Hz/kHz/MHz 都有），按量级换算。
                    // GPU 频率实际范围 ~100-3500MHz：≥1e6 视作 Hz、≥1e3 视作 kHz、否则已是 MHz。
                    val v = Regex("""\d+(\.\d+)?""").find(line.substringAfter("freq"))?.value
                        ?.toDoubleOrNull() ?: continue
                    freqMhz = when {
                        v >= 1_000_000.0 -> v / 1_000_000.0
                        v >= 1_000.0 -> v / 1_000.0
                        else -> v
                    }
                }
                load == null && line.isNotEmpty() -> {
                    load = Regex("""\d+(\.\d+)?""").find(line)?.value?.toDoubleOrNull()
                }
            }
        }
        return load to freqMhz
    }

    private val THERMAL_ZONE_RE = Regex("""^(\S+)\s+(-?\d+)$""")

    private val CPU_ZONE_KEYWORDS = arrayOf("cpu", "soc", "cluster", "ap")

    /**
     * 温感区 type/temp 一把读（**单进程 awk**，2026-09-26 改）。
     * `$z` / `$(cat ...)` 都是 **shell 变量与命令替换**，在 Kotlin 字符串里必须转义成 `\$`，
     * 否则会被当成 Kotlin 模板引用不存在的变量。
     *
     * ⚠️ 旧写法 `for z in thermal_zone*; do echo "$(cat type) $(cat temp)"; done` 每区 fork
     * 两次：本机 **105 个温感区 = 210 次进程创建**，空载实测 **4.78s**（RootPowerReader.
     * probeThermal 同款实测，awk 单进程 0.02s）——游戏满载时更慢，是录制慢速拍被拖到
     * 9~14s、平均 5.45s/条的直接主因（2026-09-26 xlsx 实测定案），必须单进程一次读完。
     *
     * 实现：type 与 temp 两段 glob 按序作 awk 的文件参数（shell 展开序一致，前半 type 后半
     * temp），`FNR==1` 按 FILENAME 结尾区分；type 按 zone 编号入表，读到 temp 段时配对输出
     * `type temp`。type 读不到的 zone 不输出，与旧写法一致。
     * ⚠️⚠️ `${'$'}0` 必须写成 `\$0`（2026-09-27 真机定案）：`\$` 已经是字面 `$`，再跟
     * `{'$'}` 不是转义而是**普通文本**，字符串里会原样留下 `${'$'}0` → awk 语法错误
     * rc=2、空输出（stderr 被 2>/dev/null 吞掉）→ 温度恒 null。该坑存活于 09-26/27
     * 两个装机批次，详情页 CPU/GPU 温度线整场缺失即此根因。
     */
    private val VIRTUAL_TEMP_CMD: String =
        "awk 'FNR==1{isTemp=(FILENAME ~ /\\/temp\$/)}" +
            " !isTemp{z=FILENAME;sub(/.*thermal_zone/,\"\",z);sub(/\\/.*/,\"\",z);type[z]=\$0}" +
            " isTemp{z=FILENAME;sub(/.*thermal_zone/,\"\",z);sub(/\\/.*/,\"\",z);" +
            "if(z in type)print type[z] \" \" \$0}' " +
            "$THERMAL_ZONE_DIR/thermal_zone*/type $THERMAL_ZONE_DIR/thermal_zone*/temp 2>/dev/null"

    /**
     * 温感区旧命令（ROM 裁剪 awk 时的兜底，取舍同 [CPU_FAST_CMD_LEGACY]）：每区一次 echo、
     * 两次命令替换 = 每区 2 次 fork —— 本机 105 个温感区空载实测 4.78s，满载更慢。
     * 输出与 awk 版逐行同构（`type temp`），共用同一段解析。
     * `\$z` / `\$(...)` 是 shell 变量与命令替换，在 Kotlin 字符串里必须转义。
     */
    private val VIRTUAL_TEMP_CMD_LEGACY: String =
        "for z in $THERMAL_ZONE_DIR/thermal_zone*; do " +
            "echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done"

    /**
     * legacy 温度命令的拉黑标记：跑过仍拿不到输出（典型 = 温感区被 SELinux 拦截 + 无 root，
     * 空输出是常态结论）就置位，进程重启前不再重试 —— 否则这台机器每 5s 白跑 210 fork
     * （4.78s/次），慢速拍被拖回 awk 版要治的 9~14s 病。⚠️ 拉黑前提是命令真跑过
     * （Shizuku 在位）：重绑窗口期的 null 是通道问题而非命令问题，误拉黑会让 awk 缺失的
     * 机器在 Shizuku 恢复后永远失去温度兜底。
     */
    @Volatile
    private var virtualTempLegacyDead = false

    /** 温感区根目录（本文件多处命令共用） */
    private const val THERMAL_ZONE_DIR = "/sys/class/thermal"

    /** GPU 占用率候选节点（[readGpuLoadFreq] 的探测序，厂商分叉见该函数注释）。
     *  ⚠️ gpubusy 排第一且独享双数比值分支：它是本机（24031PN0DC）唯一可读的 GPU 活动
     *  节点（2026-09-28 Scene APK 抠串 + adb 逐点实测定案，见 [readGpuLoadFreq]）。 */
    private const val GPU_BUSY_NODES: String =
        "/sys/class/kgsl/kgsl-3d0/gpubusy " +
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage " +
            "/sys/kernel/gpu/gpu_busy " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load " +
            "/sys/kernel/ged/hal/gpu_utilization " +
            "/sys/class/misc/mali0/device/utilisation " +
            "/sys/module/ged/parameters/gpu_loading " +
            "/sys/class/devfreq/gpufreq/mali_ondemand/utilisation"

    /**
     * GPU 频率候选节点（与 ShellService.GPU_FREQ_NODES 同表同序，两处改动必须同步）。
     * 单位不统一（kgsl/高通 = Hz、ged = kHz、部分挂点 = MHz），解析侧按量级换算（见
     * [readGpuLoadFreq]）。本机（24031PN0DC）kgsl 与 /sys/kernel/gpu 全拦 → 恒缺（预期）。
     */
    private const val GPU_FREQ_NODES: String =
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq " +
            "/sys/class/kgsl/kgsl-3d0/gpuclk " +
            "/sys/kernel/gpu/gpu_clock " +
            "/sys/class/misc/mali0/device/clock " +
            "/sys/devices/11800000.mali/clock " +
            "/sys/devices/14ac0000.mali/clock " +
            "/sys/kernel/ged/hal/current_freqency " +
            "/sys/kernel/debug/ged/hal/current_freqency " +
            "/sys/class/devfreq/gpufreq/cur_freq " +
            "/sys/kernel/tegra_gpu/gpu_rate"

    /**
     * GPU 占用率 + 频率命令：**单进程 awk** 依次 getline 两组候选节点（负载命中即回、
     * 频率独立探测），输出第一行为占用率（口径同前），命中频率时追加一行 `freq <原始值>`。
     * ⚠️ 命令里全是字面路径与 awk 内建，无 shell 变量/命令替换，不踩 `\$` 双重转义的坑
     * （批次十七教训）；`\\n` 是 awk printf 的换行转义。
     */
    private val GPU_LOAD_CMD: String =
        "awk 'BEGIN{" +
            "n=split(\"$GPU_BUSY_NODES\",a,\" \");" +
            "for(i=1;i<=n;i++){v=\"\";getline v < a[i];" +
            "if(v!=\"\"){" +
            "if(a[i]~/gpubusy/){split(v,b,\" \");" +
            "if(b[2]+0>0)printf \"%.1f\\n\",b[1]*100.0/b[2];else print \"0\"}" +
            "else print v;break}}" +
            "m=split(\"$GPU_FREQ_NODES\",f,\" \");" +
            "for(i=1;i<=m;i++){v=\"\";getline v < f[i];" +
            "if(v!=\"\"){print \"freq \" v;exit}}}' 2>/dev/null"

    // ── DDR 频率（2026-10-02 加，候选池逆向 Metric libmetric_daemon.so 定案）────────
    //
    // Metric 的 ddrThermalSampler（Rust 常驻守护进程）按厂商分叉取 DDR 频率（so 内
    // 字符串定案）：qcom.cur_freq / qcom.clock_measure / mtk.cur_freq / helio dump。
    // 候选池取**纯文件读**两支，dump 解析与 debugfs 支不进池：
    // - QCOM：`/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq` —— 高通 taro 起的总线
    //   DCVS 挂点，单位 kHz（22081212C / SM8475 实测 547000..3196000，与同目录
    //   available_frequencies 档位表一致；**shell 身份可读，无需 root**）。本机
    //   /sys/class/devfreq 下没有 DDR devfreq 条目，只有这个挂点。
    // - MTK：`/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq` —— DVFSRC 挂点。
    // - （不进池）qcom.clock_measure = `/sys/kernel/debug/clk/measure_only_mccc_clk/
    //   clk_measure`（Hz 实测值）——要 debugfs 已挂载，普通机器拿不到；helio-dvfsrc 的
    //   dvfsrc_dump 是多行文本 dump，解析成本高收益低。
    //
    // ⚠️ 单位不统一（kHz/Hz/MHz 都可能），解析按量级换算：DDR 实际范围 ~300-4500MHz
    // —— kHz 档 300000..4500000 与 Hz 档 3e8.. 跨 1e8 分界不会误判（见 [readDdrFreqMhz]）。
    private const val DDR_FREQ_NODES: String =
        "/sys/devices/system/cpu/bus_dcvs/DDR/cur_freq " +
            "/sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq"

    /** DDR 频率命令：**单进程 awk** 按序探测候选节点，读到第一个非空值原样回传（同行文口径见 GPU_LOAD_CMD） */
    private val DDR_FREQ_CMD: String =
        "awk 'BEGIN{" +
            "n=split(\"$DDR_FREQ_NODES\",a,\" \");" +
            "for(i=1;i<=n;i++){v=\"\";getline v < a[i];" +
            "if(v!=\"\"){print v;exit}}}' 2>/dev/null"

    /**
     * DDR 频率 MHz。null = 候选节点全不可读（节点缺失 / SELinux 拦截）——这是无该节点
     * 机型的**常态结论**，与 GPU 占用率同口径：详情页整卡隐藏，不按失败处理。
     *
     * ⚠️ 必须在后台线程调用（直读 = 进程内文件 IO；回退 exec 时内部起进程）。
     */
    fun readDdrFreqMhz(): Double? {
        val out = ShizukuHelper.readDdrFreqDirect()
            ?: exec(DDR_FREQ_CMD, allowBlank = true)
            ?: return null
        val v = Regex("""\d+(\.\d+)?""").find(out.trim())?.value?.toDoubleOrNull() ?: return null
        return when {
            v >= 1e8 -> v / 1e6   // Hz（部分内核 dvfsrc 导 Hz 原始值）
            v >= 1e5 -> v / 1e3   // kHz（QCOM bus_dcvs 口径，本机 547000 = 547MHz）
            else -> v             // MHz
        }
    }

    // ── SF --latency 路径（2026-09-29 加，可选帧率算法 ①）─────────────────
    //
    // `dumpsys SurfaceFlinger --latency <layer>`：AOSP 老牌的逐帧 present 时间戳查询 ——
    // 首行 = 刷新周期（ns），随后 ≤127 行「desiredPresent actualPresent frameReady」三元组
    // （ns），末尾 "0 0 0" 终止。**原始时间戳口径**：帧率 = 帧数 ÷（时间戳差），没有累计
    // 计数器、没有跟踪表上限、不用 -enable/-clear 改全局状态、差分窗口由时间戳自带 ——
    // timestats 路径的五套护栏（见本文件上方）在这里整个消失。Scene 的通用解析器同款。
    //
    // ⚠️⚠️ 本机定案（24031PN0DC / HyperOS V816，2026-09-29 adb 复核）：**--latency 已死** ——
    // 精确图层名（--list 原样返回的 `com.tencent.mm/...LauncherUI#483086`）查询只回一行
    // 周期数 `8333333`，无任何帧时间戳（批次二十六的结论成立，「图层名没匹配」假设被否定）。
    // 因此本路径带**存活探测自回落**：命中「只有周期行」即置 [latencyDead]，本进程永久回落
    // timestats（调用方 FrameRecordController 处理），不再浪费每拍一次 exec。图层名不精确
    // （未渲染/已销毁）与「ROM 砍功能」输出同形，区分方式 = 探测时用 --list 原样返回的
    // 名字且要求该图层 FIFO 里有帧——已尽最大努力区分，ROM 行为如超出此判别能力，
    // 代价只是回落 timestats，无损。
    //
    // ⚠️ --list 输出形态随版本分叉：经典 AOSP = 每行一个图层名；AOSP 16 / HyperOS =
    // `RequestedLayerState{…}` 内部状态行，且**同一份输出里带 hex 句柄与不带句柄两种排布并存**
    // （前者是容器/镜像层、后者才是真实渲染面）。解析两种都认、两种排布都认
    // （判据见 [parseLatencyListLine]），并按 Metric 同款排除无帧镜像层。

    /** 一次 --latency 差分窗口：fps = ΔF ÷ 时间戳 dt（时间戳自带窗口，免墙钟） */
    data class LatencySample(
        /** 本窗帧率；0.0 = 本周期无新帧（语义同 timestats 路径的 0 帧） */
        val fps: Double,
        /** 本窗新帧数 */
        val frames: Long,
        /** 本窗**帧间隔分布**（真实逐帧 present 间隔，桶 = 整毫秒；含跨拍边界间隔） */
        val p2pHistogram: Map<Int, Long>,
        /** 命中的图层名（日志/诊断用） */
        val layerName: String,
        /** true = FIFO 溢出（127 帧装不下一个轮询窗，帧数有缺，fps 为降级估计） */
        val overflow: Boolean,
    )

    // ⚠️ 判死不是一次性的终态（2026-10-02 改，K50 Ultra 真机教训）：目标应用瞬时无活图层
    // （相机休眠销毁 Surface / 页面转场）同样会让全部候选只回周期行，旧实现当场判死且
    // **进程级永久**回落 timestats——相机唤醒后 SF_LATENCY 整场失效，重启应用才恢复。
    // 现口径：连续 [LATENCY_DEAD_STRIKES] 次「有候选却全只回周期行」才判死（≈2.5s @4Hz
    // 子拍，真死 ROM 的代价只是晚这一会儿回落）；判死后由 [maybeReviveLatency] 每
    // [LATENCY_REVIVE_PROBE_INTERVAL_MS] 低频重探一次，探到活图层即自愈恢复采样。
    @Volatile
    private var latencyDead = false

    /** 连续「有候选却全只回周期行」的选层次数；任何一次探到活图层（含全零骨架）即清零 */
    @Volatile
    private var latencyDeadStrikes = 0

    /** 上次自愈探测时刻（判死期间节流：静止期每 5s 才白跑一轮 --list + ≤3 次 --latency） */
    @Volatile
    private var latencyReviveProbeAt = 0L

    @Volatile
    private var latencyLayer: String? = null

    /** 当前缓存图层对应的包名（目标切换即重新选层） */
    @Volatile
    private var latencyLayerPkg = ""

    /** 上一拍最后一条新帧的 present 时间戳（ns）；0 = 尚无基线（首拍只建基线不出数） */
    private var latencyLastPresentNs = 0L

    /** 连续 0 新帧拍数（图层可能已被销毁重建）；≥[LATENCY_IDLE_REPICK_BEATS] 触发重新选层 */
    private var latencyIdleBeats = 0

    private const val LATENCY_IDLE_REPICK_BEATS = 5

    /**
     * 判死所需连续全败次数：10 次 × 250ms 子拍 ≈ 2.5s。真死 ROM（如 24031PN0DC / A16）
     * 从选 SF_LATENCY 到回落 timestats 多等这一会儿；瞬时无活图层（转场 / 相机休眠）
     * 远撑不满这个数，不会误杀。
     */
    private const val LATENCY_DEAD_STRIKES = 10

    /** 判死期间自愈探测的间隔：1 次探测 = 1 次 --list（grep 收窄）+ ≤3 次 --latency，成本可忽略 */
    private const val LATENCY_REVIVE_PROBE_INTERVAL_MS = 5_000L

    /** 目标切换 / 算法切换时清空 latency 侧状态（选层与时间戳基线一并作废） */
    fun resetLatency() {
        latencyLayer = null
        latencyLayerPkg = ""
        latencyLastPresentNs = 0L
        latencyIdleBeats = 0
        // strike 跨目标不累计：真死 ROM 换目标后重新数满再判死（多花 ~2.5s，可接受）
        latencyDeadStrikes = 0
    }

    /** 本机当前是否判定 --latency 不可用（设置页据此压暗选项；**非终态**——判死后自愈重探，见 [maybeReviveLatency]） */
    fun isLatencyDead(): Boolean = latencyDead

    /**
     * 判死后的**自愈探测**：采集循环在 SF_LATENCY 被判死回落 timestats 期间每子拍调用，
     * 内部按 [LATENCY_REVIVE_PROBE_INTERVAL_MS] 节流。重新列层探活，探到活图层
     * （FIFO 有骨架即可，帧数据随后续渲染填充）即清判死、装填选层缓存——下一拍
     * resolveAndSwitchAlgo 见 isLatencyDead()=false 自然恢复 SF_LATENCY，读数无缝接回。
     *
     * ⚠️ 必须在后台线程调用（内部 exec：最多 1 次 --list + 3 次 --latency）。
     */
    fun maybeReviveLatency(pkg: String) {
        if (!latencyDead || pkg.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - latencyReviveProbeAt < LATENCY_REVIVE_PROBE_INTERVAL_MS) return
        latencyReviveProbeAt = now
        val (best, anyAlive, _) = probeLatencyLayerCandidates(pkg) ?: return
        if (!anyAlive || best == null) return // 仍全周期行 / 无候选：维持判死，下个周期再探
        latencyDead = false
        latencyDeadStrikes = 0
        latencyLayer = best
        latencyLayerPkg = pkg
        latencyLastPresentNs = 0L
        latencyIdleBeats = 0
        Log.i(TAG, "--latency 自愈：重新探测到活图层 $best，恢复采样")
    }

    /**
     * 取一次 --latency 差分窗口。null = 无可出数（首拍建基线 / 图层未渲染 / 本机已判定死），
     * 调用方按「本拍无可差分」处理，**不算通道失败**（存活探测是独立语义，见 [latencyDead]）。
     *
     * 必须在后台线程调用（内部 exec，图层探测一轮最多 4 次 fork）。
     */
    fun readLatencySample(pkg: String): LatencySample? {
        if (latencyDead || pkg.isEmpty()) return null
        // 选层：包名变化 / 尚无缓存图层 → 重新探测；长时间 0 帧（图层销毁重建）→ 再试一轮
        var layer = latencyLayer
        if (layer == null || latencyLayerPkg != pkg) {
            layer = pickLatencyLayer(pkg) ?: return null
            latencyLayer = layer
            latencyLayerPkg = pkg
            latencyLastPresentNs = 0L
            latencyIdleBeats = 0
        }

        val out = exec(
            "dumpsys SurfaceFlinger --latency '${layer.filter { it != '\'' && it != '\\' }}' 2>/dev/null",
        ) ?: return null // 通道失败：状态原样保留（选层/基线不作废），下一拍重试
        val actuals = parseLatencyTimestamps(out)
        if (actuals == null) {
            // 只有周期行：图层刚被销毁（选层时还有帧）或本机砍功能 —— 作废选层，
            // 下一拍重新探测；若本机真的死了，[pickLatencyLayer] 的全候选判定会置 latencyDead
            latencyLayer = null
            return null
        }
        if (actuals.isEmpty()) {
            // FIFO 空 = 该图层自 SF 启动没渲染过（选层探测后 theoretically 不该发生），按待渲染处理
            return null
        }

        if (latencyLastPresentNs == 0L) {
            // 首拍只建时间戳基线（口径同 timestats 路径：不拿无基线的窗口算数）
            latencyLastPresentNs = actuals.last()
            return null
        }

        val lastNs = actuals.last()
        val newFrames = actuals.filter { it > latencyLastPresentNs }
        if (newFrames.isEmpty()) {
            latencyIdleBeats++
            if (latencyIdleBeats >= LATENCY_IDLE_REPICK_BEATS) {
                // 长时间无帧：图层可能被销毁重建（新 Surface 拿到新名字），重新选层
                latencyLayer = null
            }
            return LatencySample(0.0, 0L, emptyMap(), layer, overflow = false)
        }
        latencyIdleBeats = 0

        // FIFO 深度 = 127：一个轮询窗的帧数超过它就会滑出旧帧（ΔF 少计 → fps 系统性偏低）。
        // 判据 = FIFO 满（≥126 行）且最老帧晚于基线（早于基线的被滑出的帧无法从输出看出）。
        // 降级口径：整个 FIFO 当一个重叠窗 —— fps = (n-1) ÷ FIFO 跨度，不再对齐轮询窗。
        // 正常采样节奏（1s 拍、≤120Hz → ≤120 帧）不会触发。
        val fifoFull = actuals.size >= 126
        val overflow = fifoFull && actuals.first() > latencyLastPresentNs
        val hist = HashMap<Int, Long>()
        return if (overflow) {
            Log.w(TAG, "--latency FIFO 溢出（127 帧装不下一个轮询窗），本拍降级为整窗平均：layer=$layer")
            var prev = actuals.first()
            for (i in 1 until actuals.size) {
                val bucket = ((actuals[i] - prev) / 1_000_000.0).roundToInt().coerceAtLeast(0)
                hist[bucket] = (hist[bucket] ?: 0L) + 1
                prev = actuals[i]
            }
            val spanSec = (actuals.last() - actuals.first()) / 1e9
            LatencySample(
                fps = if (spanSec > 0) (actuals.size - 1) / spanSec else 0.0,
                frames = (actuals.size - 1).toLong(),
                p2pHistogram = hist,
                layerName = layer,
                overflow = true,
            ).also { latencyLastPresentNs = lastNs }
        } else {
            // 正常窗：帧间隔从「上一拍末帧」到「本拍每条新帧」—— 边界间隔是真实 present-to-present，
            // 逐拍拼起来恰好每条间隔计一次（与 timestats 直方图差分同一目标，但这里是原始值）
            var prev = latencyLastPresentNs
            for (ns in newFrames) {
                val bucket = ((ns - prev) / 1_000_000.0).roundToInt().coerceAtLeast(0)
                hist[bucket] = (hist[bucket] ?: 0L) + 1
                prev = ns
            }
            val dtSec = (lastNs - latencyLastPresentNs) / 1e9
            latencyLastPresentNs = lastNs
            LatencySample(
                fps = if (dtSec > 0) newFrames.size / dtSec else 0.0,
                frames = newFrames.size.toLong(),
                p2pHistogram = hist,
                layerName = layer,
                overflow = false,
            )
        }
    }

    /**
     * 解析 --latency 输出为 actualPresent 时间戳列表（ns，升序）。
     * 返回 null = 「只有周期行」（本机砍功能的签名，见 [readLatencySample] 上方注释）；
     * 空列表 = 命令成功但 FIFO 无帧。
     */
    private fun parseLatencyTimestamps(out: String?): List<Long>? {
        if (out.isNullOrBlank()) return null
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        // 首行 = 刷新周期（单数字）。只此一行 = 本机死；多行才继续解析帧三元组。
        if (lines.size == 1) return null
        val actuals = ArrayList<Long>(lines.size - 1)
        for (line in lines.drop(1)) {
            val tokens = line.split(WHITESPACE_SPLIT_RE)
            if (tokens.size < 2) continue // "0 0 0" 终止行或残行
            val actual = tokens[1].toLongOrNull() ?: continue
            if (actual > 0) actuals.add(actual)
        }
        actuals.sort()
        return actuals
    }

    /**
     * 从 --list 里选出目标应用的图层（Metric 同款：候选逐个探测 FIFO 帧数，最多者胜）。
     * 全部候选探测都只回周期行 = 记一次 strike，连续 [LATENCY_DEAD_STRIKES] 次才判死
     * （瞬时无活图层不误杀，见 [latencyDead] 注释）；无候选 = 应用没在渲染，返回 null
     * 且**不**计数（等下一拍目标锁定再试）。
     */
    private fun pickLatencyLayer(pkg: String): String? {
        val (best, anyAlive, names) = probeLatencyLayerCandidates(pkg) ?: return null
        if (anyAlive) {
            latencyDeadStrikes = 0
        } else {
            latencyDeadStrikes++
            if (!latencyDead && latencyDeadStrikes >= LATENCY_DEAD_STRIKES) {
                latencyDead = true
                Log.w(
                    TAG,
                    "--latency 连续 $latencyDeadStrikes 次全候选只回周期行，判定本机不可用，" +
                        "回落 timestats（每 ${LATENCY_REVIVE_PROBE_INTERVAL_MS / 1000}s 自愈重探）：candidates=$names",
                )
            }
        }
        return best
    }

    /**
     * 列层 + 探活的核心（[pickLatencyLayer] 与自愈探测 [maybeReviveLatency] 共用）。
     * 返回 (best, anyAlive, names)：
     * - best = FIFO 帧数最多的候选图层名（全候选只回周期行时为 null）；
     * - anyAlive = 是否有任一候选返回了 FIFO 结构（**含全零骨架**——图层在、数据等渲染填充，
     *   这与「只有周期行」的 ROM 砍功能形态是两种命运，不能混为一谈）；
     * - names = 参与探测的候选图层名（日志/诊断用）。
     * 无候选（--list 里 grep 不到目标包名）= null：应用当前没有可探测图层。
     */
    private fun probeLatencyLayerCandidates(pkg: String): Triple<String?, Boolean, List<String>>? {
        val needle = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        if (needle.isEmpty()) return null
        // --list 输出可能上百 KB：shell 层 grep 收窄再回传（大输出必须在 shell 层收窄的铁律，
        // 同 readTopPackage / timestatsFullDumpCmd）。grep -F 字面匹配包名。
        val out = exec("dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$needle'; true")
            ?: return null
        val names = out.lineSequence()
            .mapNotNull { parseLatencyListLine(it, needle) }
            .distinct()
            .toMutableList()
        if (names.isEmpty()) return null
        // SurfaceView 优先：游戏 / 视频 / 地图的主渲染面（帧数最多）几乎恒在它名下
        names.sortByDescending { it.contains("SurfaceView") }
        var best: String? = null
        var bestFrames = -1
        var anyAlive = false
        for (name in names.take(3)) {
            val safe = name.filter { it != '\'' && it != '\\' }
            val probe = exec("dumpsys SurfaceFlinger --latency '$safe' 2>/dev/null")
            val actuals = parseLatencyTimestamps(probe)
            if (actuals == null) continue // 周期行：该图层无数据（或本机死，见 strike 判定）
            anyAlive = true
            if (actuals.size > bestFrames) {
                bestFrames = actuals.size
                best = safe
            }
        }
        return Triple(best, anyAlive, names)
    }

    /**
     * --list 单行 → 图层名。两种形态：
     * - 经典 AOSP：整行就是名字（含目标包名才到这里）；
     * - AOSP 16 / HyperOS：`RequestedLayerState{…}` 内部状态行 —— 剥壳后按属性截断、按句柄判首。
     * 排除无帧镜像层：InputSink（输入镜面）、ActivityRecord{（任务镜像）、animation-leash。
     *
     * ⚠️⚠️ **剥壳后不能固定取 `tokens[1]`**（2026-10-01 真机 24031PN0DC / Android 16 /
     * HyperOS OS3.0.306 实测定案，SF_LATENCY 永久置死的根因）。本 ROM 的 `RequestedLayerState`
     * 有**两种排布并存**（231 行实测：28 行 hex 起头且 ≥3 token、203 行非 hex 起头，两者互斥无歧义）：
     *
     * | 排布 | 样例 | 语义 | `--latency` |
     * |---|---|---|---|
     * | 带 hex 句柄 | `RequestedLayerState{f13e32b pkg/Act#654976 parentId=654967}` | 容器 / 镜像层 | **只回周期行（无帧）** |
     * | 不带句柄 | `RequestedLayerState{pkg/Act#654969 parentId=654968}` | **真实渲染面** | **129 行（有帧）** |
     *
     * 旧写法固定取 `tokens[1]`（前提「首 token 必为 hash」），于是：哈希形态侥幸取对名字但选中的是
     * **无帧容器**；非哈希形态——也就是**唯一真正渲染的那一层**——取到的是 `parentId=…}` 这种垃圾名；
     * `ActivityRecord{…}#654685` 行则取到 `"u0"`。候选集全是容器 + 垃圾名 → 每个 `--latency`
     * 探测都只回周期行 → [pickLatencyLayer] 的 `anyAlive` 恒 false → **`latencyDead` 置位（进程级
     * 永久）** → 本机明明可用的 SF_LATENCY 被自动回落 timestats。运行期日志实形：
     * `--latency 全部候选图层均只返回周期行…candidates=[…MainSettings#654771, u0]`。
     *
     * 正确判据 = **先按属性 token 截断，再按「首 token 是否为纯 hex 句柄」决定丢不丢**：
     * 属性（`parentId=` / `z=` / `relativeParentId=`）之后的内容不属于图层名；首 token 是 hex 句柄
     * 才丢弃，不是句柄时它本身就是名字（无句柄形态）。名字允许含空格（如
     * `ActivityRecordInputSink pkg/Act#654976`），故按空格重新拼接。
     */
    private fun parseLatencyListLine(line: String, needle: String): String? {
        val trimmed = line.trim()
        if (!trimmed.contains(needle)) return null
        val name = if (trimmed.startsWith("RequestedLayerState{")) {
            val inner = trimmed.removePrefix("RequestedLayerState{").removeSuffix("}")
            val tokens = inner.trim().split(WHITESPACE_SPLIT_RE).filter { it.isNotEmpty() }
            // 属性 token 及其后内容不属于名字
            val attrAt = tokens.indexOfFirst { LATENCY_ATTR_RE.matches(it) }
            val head = if (attrAt >= 0) tokens.subList(0, attrAt) else tokens
            // 仅当首 token 是 hex 句柄（且后面还有内容）才丢弃；否则它就是名字本身
            val startAt = if (head.size >= 2 && LATENCY_HANDLE_RE.matches(head[0])) 1 else 0
            head.drop(startAt).joinToString(" ").ifEmpty { return null }
        } else {
            trimmed
        }
        if (name.isEmpty()) return null
        if (EXCLUDED_LATENCY_LAYERS.any { name.contains(it) }) return null
        return name
    }

    /**
     * `--list` 条目里的属性 token（父层 ID / 相对父层 / z 序）—— 其后的内容不属于图层名。
     * 用 `matches`（整 token 匹配）而非 `contains`：属性值里可能含路径分隔符，整段比对更稳。
     */
    private val LATENCY_ATTR_RE = Regex("""(parentId|relativeParentId|z)=.*""")

    /**
     * `--list` 条目的 hex 句柄（`RequestedLayerState{<handle> <name> …}` 形态的首 token）。
     * 实测本机句柄为 7 位小写 hex（`147770c` / `301c218` / `2c739a4` …）；名字里不会出现纯 hex
     * （真机 231 行全量核对：hex 起头 28 行全部是句柄，无一例外，且与长度 ≥3 token 完全对应）。
     * 下限取 4 位，避免与 3 字符以内的短名（`abc` 类）混淆。
     */
    private val LATENCY_HANDLE_RE = Regex("""[0-9a-f]{4,}""")

    private val EXCLUDED_LATENCY_LAYERS = arrayOf("InputSink", "ActivityRecord{", "animation-leash")

    private val WHITESPACE_SPLIT_RE = Regex("\\s+")

    // ── 系统 TaskFpsCallback 路径（2026-09-29 加，可选帧率算法 ②）─────────
    //
    // AOSP 隐藏 AIDL：IWindowManager.registerTaskFpsCallback(taskId, ITaskFpsCallback) ——
    // 系统对指定任务的帧呈现**主动推送** FPS（oneway onFpsReported(float)），零采样开销。
    // 调用门槛 = ACCESS_FPS_COUNTER，AOSP Shell 包 manifest 自带（本机 granted=true 实测），
    // 故只在 Shizuku（UserService = uid 2000）通道可用；注册/注销实现在 ShellService，
    // 本类只做薄包装。口径细节见 IShellService.aidl 同段注释。

    /** TaskFps 算法是否可用：需要 Shizuku UserService（v3+）且系统 ≥S（API 31 引入该 AIDL） */
    fun isTaskFpsSupported(): Boolean =
        Build.VERSION.SDK_INT >= 31 && ShizukuHelper.serviceBound.value

    /** 为前台任务注册系统 FPS 推送；见 [ShizukuHelper.registerTaskFps] 的返回语义 */
    fun taskFpsRegister(pkg: String): Triple<Boolean, Int, String?> =
        ShizukuHelper.registerTaskFps(pkg)

    fun taskFpsUnregister() = ShizukuHelper.unregisterTaskFps()

    /**
     * 最近一次系统推送 (fps, atMillis)；null = 从未推送 / 服务未绑定。
     * ⚠️ 推送节奏由系统决定（FPS 变化或按窗口上报，随 ROM 而异）：调用方按
     * 「atMillis 是否落在本拍窗口」判新值，陈旧值不补样本（判口径见 FrameRecordController）。
     */
    fun readTaskFpsSample(): Pair<Float, Long>? = ShizukuHelper.readTaskFpsDirect()

    /** 整数帧率格式化（Locale.US：小数点是点，不受系统语言影响） */
    internal fun formatFps(fps: Double): String = String.format(Locale.US, "%.0f", fps)

    // ── 系统 FrameTimeline 路径（2026-10-03 加，可选帧率算法 ④）─────────
    //
    // `/system/bin/perfetto` 抓 `android.surfaceflinger.frametimeline` 数据源（逆向
    // Metric 录制链定案的机制；它的实时侧反而不提供这条路）。SF 作为 perfetto
    // producer 注册该数据源，逐帧 actualPresent 绝对真值直接来自系统 —— 不依赖
    // --latency 的 127 帧 FIFO（A16 上已死），也不受 timestats 跟踪表上限约束。
    //
    // 生命周期 = 一条常驻 trace 会话：录制/预览进入本算法时 [startFrameTimelineSession]
    // 拉起后台 perfetto（write_into_file 每秒把增量刷进 trace 文件），每个子拍
    // [readFrameTimelineSample] 用 `tail -c +offset` 只取**新增字节**、喂进增量解析器
    // （perfetto trace 文件 = `0x0A + varint长度 + TracePacket` 的顺序流，定案过程见
    // 2026-10-03 会话；真机 pm_ptr.ptrace 832 帧 / 120Hz cadence / jank 位掩码全实证）。
    // 切走算法 / stopPreview 时 [stopFrameTimelineSession]（kill -INT 优雅收尾 + 删文件）。
    //
    // ⚠️ trace 文件与 cfg 写在 shell 可写路径（/data/misc/perfetto-traces 由 traced
    // 落盘、本通道可读；/data/local/tmp 存 pbtxt 配置）。会话属主 = 拉起它的取数通道
    // （Shizuku → shell / su → root），读取走同一通道所以权限自洽。
    //
    // ⚠️ 二进制安全：trace 是二进制 protobuf，**不能走 exec 的 String 回传**（UTF-16/
    // UTF-8 往返会损坏）—— 管道尾接 `base64` 再回传，Kotlin 侧解码（体积 ×4/3，子拍
    // 增量 ~几 KB，远够不着 binder 上限）。
    //
    // ⚠️ 静止画面 0 帧是常态（同 timestats 0 帧语义），不算失败；判死只看「trace 文件
    // 消失」（SIZE=-1，perfetto 被系统杀 / 从未启动成功），连续 [FTL_DEAD_STRIKES]
    // 次才判死回落 TIMESTATS，判死后 [maybeReviveFrameTimeline] 每 5s 重拉会话自愈。
    private const val FTL_TRACE_FILE = "/data/misc/perfetto-traces/powermeter_ftl.ptrace"
    private const val FTL_CFG_FILE = "/data/local/tmp/powermeter_ftl.pbtxt"
    private const val FTL_PID_FILE = "/data/local/tmp/powermeter_ftl.pid"

    /**
     * perfetto 文本配置（write_into_file 每秒增量落盘；256MB 上限 ≈ 1.5h 高帧率录制） */
    private val FTL_CONFIG =
        "buffers { size_kb: 16384 }\n" +
            "data_sources { config { name: \"android.surfaceflinger.frametimeline\" } }\n" +
            "write_into_file: true\n" +
            "file_write_period_ms: 1000\n" +
            "max_file_size_bytes: 268435456\n" +
            "flush_period_ms: 1000\n"

    /**
     * 后台拉起会话：cfg 写入 /data/local/tmp（printf 单引号字面量，cfg 内只有双引号）
     * → `setsid sh -c 'echo $$ > pidfile; exec perfetto ...'` 脱离会话常驻。
     *
     * ⚠️⚠️ pid 必须经 **pidfile（$$ 在 sh -c 内落盘）** 拿，不能用外层 `echo $!`：
     * 真机实证（2026-10-03）setsid 会 fork，$! 是 setsid 包装进程、真 perfetto 是
     * 另一个 pid —— 旧实现收尾 kill 杀错进程 → 旧会话泄漏、两个 perfetto 同写一个
     * trace 文件 → 混流损坏、解析零帧（装机首翻车根因）。
     *
     * ⚠️ 拉起前先 `pgrep -f 'powermeter_ft[l]'` 清扫本应用的残留 perfetto（含上一版
     * 泄漏的）；`[l]` 字符类防 pgrep 匹配到自身所在 shell 的 cmdline（经典自匹配坑）。
     * 输出末行 `PID=<perfetto pid>`（sleep 0.3 等 pidfile 落盘）。
     */
    private val FTL_SPAWN_CMD: String =
        "kill -9 \$(pgrep -f 'powermeter_ft[l]') 2>/dev/null; " +
            "rm -f $FTL_TRACE_FILE $FTL_CFG_FILE $FTL_PID_FILE; " +
            "printf '%s' '$FTL_CONFIG' > $FTL_CFG_FILE && " +
            "setsid sh -c 'echo \$\$ > $FTL_PID_FILE; exec perfetto -c $FTL_CFG_FILE --txt -o $FTL_TRACE_FILE' >/dev/null 2>&1 & " +
            "sleep 0.3; echo PID=\$(cat $FTL_PID_FILE 2>/dev/null)"

    /** 收尾：先 INT 优雅 flush，300ms 后兜底 -9，再按 pgrep 清扫漏网，最后删文件 */
    private val FTL_STOP_CMD: String =
        "kill -INT \$(cat $FTL_PID_FILE 2>/dev/null) 2>/dev/null; sleep 0.3; " +
            "kill -9 \$(cat $FTL_PID_FILE 2>/dev/null) 2>/dev/null; " +
            "kill -9 \$(pgrep -f 'powermeter_ft[l]') 2>/dev/null; " +
            "rm -f $FTL_TRACE_FILE $FTL_CFG_FILE $FTL_PID_FILE"

    /** 连续多少拍「trace 文件消失」才判死（≈2.5s @4Hz 子拍，口径同 latency 的 strike） */
    private const val FTL_DEAD_STRIKES = 10

    /** perfetto 进程 pid；0 = 会话未拉起 */
    @Volatile
    private var ftlPid = 0L

    /** 已消费的 trace 文件字节数（tail -c +offset+1 的增量游标） */
    private var ftlReadOffset = 0L

    /** 上一子拍认领图层的最大 present ts（p2p 边界差分的基线；0 = 尚无基线） */
    private var ftlPrevLastNs = 0L

    /** 每认领图层的最后 present ts（跨子拍边界间隔；图层销毁重建后旧名自然失活） */
    private val ftlLastPresentNs = HashMap<String, Long>()

    @Volatile
    private var ftlDead = false

    private var ftlDeadStrikes = 0

    @Volatile
    private var ftlReviveProbeAt = 0L

    /** 增量解析器（会话生命周期内持续累积未收尾的字节） */
    private var ftlParser: FtlStreamParser? = null

    /** 诊断日志节流（装机排查用，随后续修复移除） */
    @Volatile
    private var ftlDiagAt = 0L

    /** 本机是否可用：root / Shizuku 任一通道即可（perfetto 由通道身份拉起与读取） */
    fun isFrameTimelineSupported(): Boolean =
        RootPowerReader.accessMode != RootPowerReader.AccessMode.NONE

    /** 本机是否已判死（设置页据此压暗选项；非终态，[maybeReviveFrameTimeline] 会自愈重探） */
    fun isFrameTimelineDead(): Boolean = ftlDead

    /**
     * 拉起后台 perfetto 会话（幂等：已拉起直接返回 true）。@return true = 会话在跑
     * （或已在跑）；false = 拉起失败（通道不可用 / 命令失败 —— 计一次 strike）。
     * 必须在后台线程调用（内部 exec）。
     */
    fun startFrameTimelineSession(): Boolean {
        if (ftlPid != 0L) return true
        if (!isFrameTimelineSupported()) return false
        val out = exec(FTL_SPAWN_CMD) ?: run { ftlStrike(); return false }
        // PID=<perfetto 真身>（pidfile 落盘的真实进程，口径见 FTL_SPAWN_CMD 注释）
        val pid = Regex("PID=(\\d+)").findAll(out).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
        if (pid == null) { ftlStrike(); return false }
        ftlPid = pid
        ftlReadOffset = 0L
        ftlPrevLastNs = 0L
        ftlLastPresentNs.clear()
        ftlParser = FtlStreamParser()
        Log.i(TAG, "FrameTimeline 会话已拉起：pid=$pid")
        return true
    }

    /** 会话是否在跑（采样循环据此自愈式补拉起） */
    fun isFrameTimelineSessionUp(): Boolean = ftlPid != 0L

    /**
     * 收尾会话：kill -INT 让 perfetto 优雅 flush 后自杀，300ms 后兜底 -9，trace 文件与
     * 配置一并删除（逐帧数据早已随子拍解析进采样循环，不依赖 trace 文件存活）。
     * 必须在后台线程调用（内部 exec，含 300ms sleep）。
     */
    fun stopFrameTimelineSession() {
        val pid = ftlPid
        ftlPid = 0L
        ftlReadOffset = 0L
        ftlPrevLastNs = 0L
        ftlLastPresentNs.clear()
        ftlParser = null
        if (pid != 0L) {
            exec(FTL_STOP_CMD, allowBlank = true)
            Log.i(TAG, "FrameTimeline 会话已收尾：pid=$pid")
        }
    }

    /** 目标切换时清 p2p 边界基线（图层认领口径变了，跨图层的间隔不能混算） */
    fun resetFrameTimelineTarget() {
        ftlLastPresentNs.clear()
        ftlPrevLastNs = 0L
    }

    private fun ftlStrike() {
        ftlDeadStrikes++
        if (!ftlDead && ftlDeadStrikes >= FTL_DEAD_STRIKES) {
            ftlDead = true
            Log.w(TAG, "FrameTimeline 连续 $ftlDeadStrikes 拍 trace 文件不可读，判定本机不可用，回落 timestats")
            stopFrameTimelineSession()
        }
    }

    /**
     * 判死后的自愈：每 5s 节流重拉一次会话，拉起成功即解除判死（口径同 latency 的
     * [maybeReviveLatency]）。采样循环在判死回落期间每子拍调用。
     */
    fun maybeReviveFrameTimeline() {
        if (!ftlDead) return
        val now = System.currentTimeMillis()
        if (now - ftlReviveProbeAt < 5_000L) return
        ftlReviveProbeAt = now
        stopFrameTimelineSession()
        if (startFrameTimelineSession()) {
            ftlDead = false
            ftlDeadStrikes = 0
            Log.i(TAG, "FrameTimeline 自愈：会话重新拉起，恢复采样")
        }
    }

    /**
     * 取一次子拍增量。null = 无可出数（会话未拉起 / 首拍建基线 / 文件暂缺），调用方按
     * 「本拍无可差分」处理，不算通道失败。返回值复用 [LatencySample]（layerName 恒空，
     * overflow 恒 false —— 语义同源：真实逐帧 present 间隔的聚合窗）。
     * 必须在后台线程调用（内部 exec：tail + base64）。
     */
    fun readFrameTimelineSample(pkg: String): LatencySample? {
        if (ftlPid == 0L || ftlDead) return null
        val out = exec("echo SIZE=\$(stat -c %s $FTL_TRACE_FILE 2>/dev/null || echo -1); tail -c +${ftlReadOffset + 1} $FTL_TRACE_FILE 2>/dev/null | base64")
            ?: run { ftlStrike(); return null }
        // 首行 SIZE=<当前文件字节数>；-1 = 文件消失（perfetto 被杀 / 从未写盘）→ 判死链
        var size = -1L
        var b64 = out
        val nl = out.indexOf('\n')
        if (nl >= 0) {
            size = out.substring(5, nl).trim().trimEnd('\r').toLongOrNull() ?: -1L
            b64 = out.substring(nl + 1)
        }
        if (size < 0) { ftlStrike(); return null }
        ftlDeadStrikes = 0
        if (size < ftlReadOffset) {
            // 文件被重建（旧会话残留被清）：游标与图层基线全部归零，下一子拍从头读
            ftlReadOffset = 0L
            ftlPrevLastNs = 0L
            ftlLastPresentNs.clear()
            return null
        }
        val b64Compact = b64.filter { it != '\n' && it != '\r' && it != ' ' }
        if (b64Compact.isEmpty()) return null // 本子拍无新增字节（静止期常态）
        val bytes = try {
            Base64.decode(b64Compact, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "FrameTimeline tail base64 解码失败（${b64Compact.length} chars）", e)
            return null
        }
        val parser = ftlParser ?: FtlStreamParser().also { ftlParser = it }
        parser.feed(bytes)
        ftlReadOffset += bytes.size
        // 认领口径同 parseTimestats：图层名含目标包名（"TXH - pkg/..."、"SurfaceView - pkg/..."）
        val all = parser.drain()
        val claimed = if (pkg.isEmpty()) emptyList() else all.filter { it.layer.contains(pkg) }
        // 诊断日志（2s 节流）：装机排查 FrameTimeline 认领链
        if (System.currentTimeMillis() - ftlDiagAt > 2_000L) {
            ftlDiagAt = System.currentTimeMillis()
            Log.i(
                TAG,
                "FTL诊断: size=$size offset=$ftlReadOffset all=${all.size} claimed=${claimed.size} " +
                    "pkg='$pkg' firstLayer=${all.firstOrNull()?.layer?.take(48)}",
            )
        }
        if (claimed.isEmpty()) {
            // 目标本拍无帧（静止 / 不在前台）：0 帧语义，边界基线推进到本拍最大 ts
            all.maxOfOrNull { it.ts }?.let { ftlPrevLastNs = it }
            return LatencySample(0.0, 0L, emptyMap(), "", overflow = false)
        }
        val maxTs = claimed.maxOf { it.ts }
        val dtSec = if (ftlPrevLastNs > 0L) (maxTs - ftlPrevLastNs) / 1e9 else 0.0
        ftlPrevLastNs = maxTs
        if (dtSec <= 0.0) return null // 首拍只建基线（同 latency 的基线口径）
        val fps = claimed.size / dtSec
        // p2p 直方图：**按图层分组**算相邻间隔（多图层交错会污染间隔分布），跨子拍边界
        // 用 ftlLastPresentNs 接缝（每图层的最后一帧）
        val hist = HashMap<Int, Long>()
        val byLayer = claimed.groupBy { it.layer }
        for ((layer, frames) in byLayer) {
            var prev = ftlLastPresentNs[layer] ?: 0L
            for (frame in frames.sortedBy { it.ts }) {
                if (prev > 0L && frame.ts > prev) {
                    val bucket = ((frame.ts - prev) / 1_000_000L).coerceAtLeast(0).toInt()
                    hist[bucket] = (hist[bucket] ?: 0L) + 1
                }
                prev = frame.ts
            }
            ftlLastPresentNs[layer] = prev
        }
        return LatencySample(fps, claimed.size.toLong(), hist, "", overflow = false)
    }

    /** FrameTimeline 逐帧事件（认领前的原始形态） */
    private class FtlFrame(val ts: Long, val layer: String, val jank: Int)

    /**
     * perfetto trace 增量解析器 —— **手写最小 protobuf 流**（依赖极简口径同 xlsx 导出器）。
     *
     * 文件帧格式（真机 v46 实证）：`0x0A + varint(len) + TracePacket 载荷` 的顺序流；
     * 逐帧数据在 `TracePacket.frame_timeline_event`（field 76）里，其中 **field 4 =
     * SurfaceFrame（完成态，带图层名 + jank）**：f1=token、f2/f3=vsync id、f4=pid、
     * f5=图层名、f6=jank_type 位掩码（1=无 2=AppDeadlineMissed 4=AppBufferDelay
     * 8=SfDeadlineMissed 16=PredictionError 32=SfStuffing 64=DisplayHal 128=SfScheduling）、
     * f7=present_type（1=onTime）。**逐帧 present 真值 = TracePacket 的 timestamp
     * （field 8，ns，MONOTONIC 域）** —— 只消费完成态 SurfaceFrame 的包。
     * field 1/2/5 = DisplayFrame 各阶段 / 批量标记，本路径不消费。
     *
     * feed() 追加新字节；跨子拍收尾不齐的半个包留在缓冲里等下一拍续上。desync（首字节
     * 非 0x0A）逐字节滑窗重找帧头 —— 正常由 traced 落盘不会发生，兜底而已。
     */
    private class FtlStreamParser {
        private var buf = ByteArray(0)
        private var readPos = 0
        private val frames = ArrayList<FtlFrame>()

        /** 取走已解析完的事件（调用方每拍 drain 一次） */
        fun drain(): List<FtlFrame> {
            val out = ArrayList(frames)
            frames.clear()
            return out
        }

        fun feed(bytes: ByteArray) {
            buf = if (buf.isEmpty() || readPos >= buf.size) {
                bytes.copyOf()
            } else {
                val merged = ByteArray(buf.size - readPos + bytes.size)
                System.arraycopy(buf, readPos, merged, 0, buf.size - readPos)
                System.arraycopy(bytes, 0, merged, buf.size - readPos, bytes.size)
                merged
            }
            readPos = 0
            parseLoop()
            // 已消费部分定期丢弃，防止长录制下缓冲无限膨胀
            if (readPos > (1 shl 20)) {
                buf = buf.copyOfRange(readPos, buf.size)
                readPos = 0
            }
        }

    private fun parseLoop() {
        while (readPos < buf.size) {
            if (buf[readPos] != 0x0A.toByte()) { readPos++; continue } // desync 滑窗
            var q = readPos + 1
            var len = 0L
            var shift = 0
            var headerDone = false
            while (q < buf.size) {
                // ⚠️⚠️ varint 终止判断 = 原始字节的**最高位**。两个坑叠在一起：
                // ① 先掩码再比较（byte < 0x80 恒真）；② ByteArray.toInt() 对 ≥0x80 的
                // 字节**符号扩展成负数**（0x8B → -117），负数 < 0x80 同样恒真 —— 两种写法
                // 都会让多字节 varint 只读首字节，≥128B 的包全部解析失败（装机翻车根因）。
                // 正解：负数说明最高位是 1（继续），只有 0..0x7F 才收尾。
                val raw = buf[q].toInt()
                q++
                len = len or ((raw and 0x7F).toLong() shl shift)
                if (raw in 0..0x7F) { headerDone = true; break }
                shift += 7
                if (shift > 28) break
            }
            if (!headerDone) break // 长度头未收齐，等下一拍
            if (len > (8 shl 20).toLong()) { readPos = q; continue } // 异常长度，滑窗重找
            val payloadEnd = q + len.toInt()
            if (payloadEnd > buf.size) break // 载荷未收齐，等下一拍
            parsePacket(q, payloadEnd)
            readPos = payloadEnd
        }
    }

    /** 解析一个 TracePacket：取 timestamp（field 8）+ frame_timeline_event（field 76） */
    private fun parsePacket(start: Int, end: Int) {
        var ts = 0L
        var ftlStart = -1
        var ftlEnd = -1
        var q = start
        while (q < end) {
            val (tag, q1) = readVarint(q)
            val f = ((tag ushr 3) and 0x1FFFFFFFL).toInt()
            val wt = (tag and 0x7L).toInt()
            q = q1
            when (wt) {
                // ⚠️ timestamp（field 8）是 **varint（wire type 0）**——值必须在 wt=0 分支
                // 里取（放在 wt=2 分支里是死代码，ts 恒 0 → 所有包被丢弃，装机二次翻车根因）
                0 -> {
                    val (v, q2) = readVarint(q)
                    q = q2
                    if (f == 8) ts = v
                }
                1 -> q += 8
                5 -> q += 4
                2 -> {
                    val (l, q2) = readVarint(q)
                    q = q2
                    val len2 = l.toInt()
                    if (f == 76) { ftlStart = q; ftlEnd = q + len2 }
                    q += len2
                }
                else -> return
            }
            if (q > end) return
        }
            if (ftlStart < 0 || ts == 0L) return
            var q2 = ftlStart
            while (q2 < ftlEnd) {
                val (tag, q3) = readVarint(q2)
                val f = ((tag ushr 3) and 0x1FFFFFFFL).toInt()
                val wt = (tag and 0x7L).toInt()
                q2 = q3
                when (wt) {
                    0 -> q2 = readVarint(q2).second
                    1 -> q2 += 8
                    5 -> q2 += 4
                    2 -> {
                        val (l, q4) = readVarint(q2)
                        q2 = q4
                        val len2 = l.toInt()
                        if (f == 4) parseSurfaceFrame(q2, q2 + len2, ts)?.let { frames.add(it) }
                        q2 += len2
                    }
                    else -> return
                }
                if (q2 > ftlEnd) return
            }
        }

        /** SurfaceFrame（field 4）：f4=pid、f5=图层名、f6=jank 位掩码；f1/f2/f3 本路径不用 */
        private fun parseSurfaceFrame(start: Int, end: Int, ts: Long): FtlFrame? {
            var layer = ""
            var hasLayer = false
            var jank = 0
            var q = start
            while (q < end) {
                val (tag, q1) = readVarint(q)
                val f = ((tag ushr 3) and 0x1FFFFFFFL).toInt()
                val wt = (tag and 0x7L).toInt()
                q = q1
                when (wt) {
                    0 -> {
                        val (v, q2) = readVarint(q)
                        q = q2
                        if (f == 6) jank = v.toInt()
                    }
                    1 -> q += 8
                    5 -> q += 4
                    2 -> {
                        val (l, q2) = readVarint(q)
                        q = q2
                        val len2 = l.toInt()
                        if (f == 5) {
                            layer = String(buf, q, len2, Charsets.UTF_8)
                            hasLayer = true
                        }
                        q += len2
                    }
                    else -> return null
                }
                if (q > end) return null
            }
            if (!hasLayer) return null
            return FtlFrame(ts, layer, jank)
        }

        /** 读 [buf] 上 [q0] 起的 varint，返回 (值, 下一位置)。⚠️ 终止判断 = 原始字节最高位
         *  （toInt() 符号扩展：负数 = 继续位，0..0x7F = 收尾；见 parseLoop 同款注释） */
        private fun readVarint(q0: Int): Pair<Long, Int> {
            var v = 0L
            var shift = 0
            var q = q0
            while (true) {
                if (q >= buf.size) throw IllegalStateException("varint oob")
                val raw = buf[q].toInt()
                q++
                v = v or ((raw and 0x7F).toLong() shl shift)
                if (raw in 0..0x7F) break
                shift += 7
                if (shift > 63) throw IllegalStateException("varint too long")
            }
            return v to q
        }
    }
}
