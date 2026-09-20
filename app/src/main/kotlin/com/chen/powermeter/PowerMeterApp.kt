package com.chen.powermeter

import android.app.Application
import com.chen.powermeter.data.RootPowerReader
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
    }
}
