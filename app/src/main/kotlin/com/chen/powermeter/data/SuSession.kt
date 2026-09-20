package com.chen.powermeter.data

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * root 通道的常驻 shell 会话（档二-4，2026-09-21）。
 *
 * 动机：`ProcessBuilder("su", "-c", cmd)` 每个采样周期都要 fork `su` + `sh`，
 * 采样间隔 1s 时就是每秒 1~2 次进程创建；root 机器上这条路径是**每样本唯一**的取数路径
 * （sysfs 单进程 awk），fork 掉就等于归零。
 *
 * 实现：起一个常驻 `su -c sh`，命令写 stdin，stdout 按**哨兵行**切分 ——
 * 每条命令尾部追加 `echo <魔数><上一条命令退出码>`，读到该行即认为本次执行结束。
 *
 * ⚠️ **本机（无 su）无法真机验证**，故安全性由四层兜底保证，任一不成立都自动退回
 * [RootPowerReader] 的一次性 fork 路径（行为与改造前完全一致）：
 * 1. 会话启动后必须先通过一次 `echo` 往返自检，不通过立即销毁并**永久禁用**；
 * 2. 任何超时 / IO 异常 / 会话进程退出 → 销毁并永久禁用，不留半死状态持续拖慢采样；
 * 3. 空闲超过 [IDLE_TIMEOUT_MS] 自动关闭，不让一个 root shell 长期挂着；
 * 4. 禁用是**进程内**的：换候选路径、跨会话都不会再尝试，避免反复付启动成本。
 */
internal object SuSession {

    /** 哨兵行前缀。后面紧跟命令退出码，行内容形如 `__PM_SU_DONE__0` */
    private const val MAGIC = "__PM_SU_DONE__"

    /** 自检超时：su 首次调用可能弹授权框，给宽松一些，但仍必须有上界 */
    private const val SELF_CHECK_TIMEOUT_MS = 10_000L

    /** 空闲多久关闭会话（下次使用重新拉起） */
    private const val IDLE_TIMEOUT_MS = 60_000L

    private val lock = Any()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var lines: LinkedBlockingQueue<String>? = null
    private var lastUsedAt = 0L

    /** 已判定本进程内不可用：全部命令走一次性 fork 路径 */
    @Volatile
    private var disabled = false

    val disabledForProcess: Boolean get() = disabled

    /**
     * 在常驻会话里执行一条命令。
     *
     * **必须在后台线程调用**（内部阻塞读取 stdout）。
     *
     * @return stdout（已剔除哨兵行）；null = 会话不可用，调用方应回退到一次性 fork
     */
    fun exec(cmd: String, timeoutMs: Long, suCandidates: List<String>): String? {
        if (disabled) return null
        synchronized(lock) {
            return try {
                if (!ensureStartedLocked(suCandidates)) return null
                val out = runLocked(cmd, timeoutMs)
                if (out == null) {
                    // 超时或会话已死：销毁 + 永久禁用，避免半死状态持续拖慢采样
                    shutdownLocked()
                    disabled = true
                    return null
                }
                lastUsedAt = System.currentTimeMillis()
                out
            } catch (_: Throwable) {
                shutdownLocked()
                disabled = true
                null
            }
        }
    }

    /** 会话是否已经建好（仅用于诊断 / 测试，不参与正确性判断） */
    val running: Boolean get() = synchronized(lock) { process?.isAlive == true }

    private fun ensureStartedLocked(suCandidates: List<String>): Boolean {
        val p = process
        if (p != null) {
            if (p.isAlive && System.currentTimeMillis() - lastUsedAt <= IDLE_TIMEOUT_MS) return true
            shutdownLocked()
        }

        for (candidate in suCandidates) {
            val proc = try {
                ProcessBuilder(candidate, "-c", "sh").redirectErrorStream(true).start()
            } catch (_: Exception) {
                continue // 该路径不存在 / 不可执行 → 试下一个候选
            }
            if (attachLocked(proc)) return true
            // 进程起来了却自检不过（被 su 拒绝 / 不是真 root shell）→ 不再浪费时间试别的候选
            break
        }
        // 所有候选都不可用（无 su，或 su 拒绝）→ 本进程内不再尝试
        shutdownLocked()
        disabled = true
        return false
    }

    /** 接管进程流并做一次 `echo` 往返自检；失败则销毁会话 */
    private fun attachLocked(proc: Process): Boolean {
        val queue = LinkedBlockingQueue<String>()
        val w = BufferedWriter(OutputStreamWriter(proc.outputStream))
        Thread({
            // 必须持续抽干 stdout：管道写满会让 root shell 阻塞在 write 上，
            // 后续命令永远等不到哨兵行（与 ScreenController 的 su 抽干同理）
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        queue.add(line)
                    }
                }
            }
        }, "PowerMeterSuReader").apply { isDaemon = true }.start()

        process = proc
        writer = w
        lines = queue
        lastUsedAt = System.currentTimeMillis()

        return runLocked("echo ready", SELF_CHECK_TIMEOUT_MS)?.trim() == "ready"
    }

    /**
     * 写入一条命令并读取到哨兵行。
     *
     * @return 命令的 stdout（不含哨兵行）；null = 超时 / 会话已断
     */
    private fun runLocked(cmd: String, timeoutMs: Long): String? {
        val w = writer ?: return null
        val queue = lines ?: return null
        queue.clear()

        val payload = buildString {
            append(cmd)
            append('\n')
            append("__pm_rc=").append('$').append("?\n")
            append("echo ").append(MAGIC).append('$').append("__pm_rc\n")
        }
        w.write(payload)
        w.flush()

        val out = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0L) return null
            val line = queue.poll(remain, TimeUnit.MILLISECONDS) ?: return null
            if (line.startsWith(MAGIC)) return out.toString()
            out.append(line).append('\n')
        }
    }

    private fun shutdownLocked() {
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        writer = null
        lines = null
        process = null
    }
}
