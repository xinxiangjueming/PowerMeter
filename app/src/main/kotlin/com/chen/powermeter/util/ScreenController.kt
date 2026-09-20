package com.chen.powermeter.util

import java.util.concurrent.TimeUnit

/**
 * 主动熄灭屏幕（供「充电功率监测」使用）。
 *
 * 实现方式：注入电源键 —— `input keyevent 26`（26 = KEYCODE_POWER），
 * 与 [com.chen.powermeter.data.RootPowerReader] 读 /sys 节点同源，不需要设备管理员、
 * 也不需要引导用户去系统设置做任何激活。
 *
 * **两条通道**（2026-09-21 起，与 RootPowerReader 同口径）：
 * 1. **Shizuku**（优先）—— 非 root 机器上以 shell 身份执行。`input` 是 /system/bin 下的
 *    shell 脚本，**不需要 root**，shell 身份即可执行；
 * 2. **root（su）**（兜底）—— 旧路径。
 *
 * 排除了两条更"正统"但不可行的路线：
 * - [android.os.PowerManager.goToSleep]：要求 `android.permission.DEVICE_POWER`，
 *   该权限属 signature|privileged 级，第三方应用申请不到；
 * - `DevicePolicyManager.lockNow()`：需要额外注册 DeviceAdminReceiver 并由用户手动激活。
 *
 * ⚠️ 两条通道都不可用时本方法返回 false（静默失败）。调用方必须容错 —— 熄屏只是降低屏幕
 * 自身耗电、让充电功率读数更接近真实值的手段，失败不应影响采样本身。
 * 且"无权限"这一情况 UI 侧已由 [com.chen.powermeter.data.RootPowerReader.lastError]
 * 显式提示，此处无需再重复告警。
 */
object ScreenController {

    private const val KEYCODE_POWER = 26
    private const val TIMEOUT_MS = 5_000L

    /** su 可执行文件的候选路径，口径与 RootPowerReader.SU_CANDIDATES 一致 */
    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/sbin/su", "/system/xbin/su")

    /**
     * 熄灭屏幕。**必须在后台线程调用**（Shizuku 为同步 Binder 调用；su 通道会 fork 进程并等待）。
     *
     * @return true = 命令以退出码 0 结束
     */
    fun turnScreenOff(): Boolean {
        // 1. Shizuku（shell 身份；`input` 不需要 root，非 root 机器走这条）
        if (ShizukuHelper.serviceBound.value) {
            if (ShizukuHelper.execSync("input keyevent $KEYCODE_POWER") != null) return true
        }
        // 2. root（su）兜底
        for (su in SU_CANDIDATES) {
            var process: Process? = null
            try {
                process = ProcessBuilder(su, "-c", "input keyevent $KEYCODE_POWER")
                    .redirectErrorStream(true)
                    .start()
                // 必须单独起线程把输出抽干：su 的 stdout/stderr 一旦写满管道缓冲区就会阻塞，
                // 主线程在 waitFor 上等到超时，命令实际并未执行完。
                val pump = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { /* 抽干即可，不求内容 */ }
                        }
                    }
                }.apply { isDaemon = true }
                pump.start()

                val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                pump.join(500L)
                if (!finished) {
                    process.destroyForcibly()
                    continue
                }
                if (process.exitValue() == 0) return true
            } catch (_: Exception) {
                process?.destroyForcibly()
            }
        }
        return false
    }
}
