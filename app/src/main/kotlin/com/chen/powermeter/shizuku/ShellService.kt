package com.chen.powermeter.shizuku

import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

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

    /**
     * Shizuku server 保留的 destroy 方法（transaction code 16777114）：
     * 主进程 unbind 时被调用。UserService 进程不会自动退出，需在此结束进程。
     */
    override fun destroy() {
        Log.i("PowerMeterShell", "destroy called, exiting process")
        System.exit(0)
    }
}
