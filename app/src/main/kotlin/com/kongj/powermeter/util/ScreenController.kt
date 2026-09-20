package com.kongj.powermeter.util

import java.util.concurrent.TimeUnit

/**
 * 主动熄灭屏幕（供「充电功率监测」使用）。
 *
 * 实现方式：root 注入电源键 —— `su -c "input keyevent 26"`（26 = KEYCODE_POWER），
 * 与 [com.kongj.powermeter.data.RootPowerReader] 读 /sys 节点同源，不需要设备管理员、
 * 也不需要引导用户去系统设置做任何激活。
 *
 * 排除了两条更"正统"但不可行的路线：
 * - [android.os.PowerManager.goToSleep]：要求 `android.permission.DEVICE_POWER`，
 *   该权限属 signature|privileged 级，第三方应用申请不到；
 * - `DevicePolicyManager.lockNow()`：需要额外注册 DeviceAdminReceiver 并由用户手动激活。
 *
 * ⚠️ 无 root 时本方法返回 false（静默失败）。调用方必须容错 —— 熄屏只是降低屏幕自身
 * 耗电、让充电功率读数更接近真实值的手段，失败不应影响采样本身。
 * 且"su 不可用"这一情况 UI 侧已由 [com.kongj.powermeter.data.RootPowerReader.lastError]
 * 显式提示，此处无需再重复告警。
 */
object ScreenController {

    private const val KEYCODE_POWER = 26
    private const val TIMEOUT_MS = 5_000L

    /** su 可执行文件的候选路径，口径与 RootPowerReader.SU_CANDIDATES 一致 */
    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/sbin/su", "/system/xbin/su")

    /**
     * 熄灭屏幕。**必须在后台线程调用**（会 fork su 进程并同步等待退出）。
     *
     * @return true = 命令以退出码 0 结束
     */
    fun turnScreenOff(): Boolean {
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
