package com.chen.powermeter

import android.app.Application
import com.chen.powermeter.data.RootPowerReader
import com.chen.powermeter.data.db.SessionRecorder
import com.chen.powermeter.util.AppStrings
import com.chen.powermeter.util.ShizukuHelper

/**
 * Application：尽早初始化 [ShizukuHelper] 与 [RootPowerReader]。
 *
 * 为什么放在 Application.onCreate 而不是 MainActivity：
 * - Shizuku 的 binder 到达 / 死亡 / 授权结果三个回调必须**常驻监听**。若挂在 Activity 上，
 *   进程内没有 Activity（例如采样服务在后台跑、用户已退出界面）时 binder 死亡收不到通知，
 *   会导致「Shizuku 重启后 App 再也连不上，必须杀进程重开」。
 * - 绑定 UserService 是异步的（binder 到达 → 授权检查 → bind → onServiceConnected），
 *   越早发起越可能在用户点「开始采样」之前就绪。
 * 口径对齐 fold FoldApp.kt:14-21。
 *
 * [RootPowerReader.init] 只做一件事：让主进程注册 `ACTION_BATTERY_CHANGED` 粘性广播
 * （档三的零 fork 取数通道）。越早注册越可能在首个采样点之前就拿到快照 —— 否则首次
 * [RootPowerReader.read] 会因为通道未就绪而退回命令通道，白起一次进程。
 */
class PowerMeterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 必须最先注入：RootPowerReader / CsvImporter 都在 object 里拼装用户可见的错误文案，
        // 它们没有 Context，只能走 AppStrings 拿 applicationContext 这一份
        AppStrings.init(this)
        ShizukuHelper.init(this)
        RootPowerReader.init(this)

        // 采样会话落库（Room，私有目录）。init 只做两件事：取 DAO、拉起单消费者协程，
        // 不建库不写盘（Room 是懒打开的），因此对冷启动耗时无实质影响。
        SessionRecorder.init(this)
        // 冷启动清理：保留最近一次会话（供「打开应用 → 点导出」这条路径使用），删掉更早的。
        // 之所以不全删：停止采样后进程被杀很常见，用户往往还没来得及导出，全删等于白测一场。
        // 放在 Application 里而不是 Activity —— 服务可能在无界面的情况下被拉起，
        // 届时同样需要把历史存档收敛到一条。
        SessionRecorder.pruneOldSessionsAsync()
    }
}
