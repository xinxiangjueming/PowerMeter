package com.chen.powermeter

import android.app.Application
import com.chen.powermeter.util.ShizukuHelper

/**
 * Application：唯一的职责是尽早初始化 [ShizukuHelper]。
 *
 * 为什么放在 Application.onCreate 而不是 MainActivity：
 * - Shizuku 的 binder 到达 / 死亡 / 授权结果三个回调必须**常驻监听**。若挂在 Activity 上，
 *   进程内没有 Activity（例如采样服务在后台跑、用户已退出界面）时 binder 死亡收不到通知，
 *   会导致「Shizuku 重启后 App 再也连不上，必须杀进程重开」。
 * - 绑定 UserService 是异步的（binder 到达 → 授权检查 → bind → onServiceConnected），
 *   越早发起越可能在用户点「开始采样」之前就绪。
 * 口径对齐 fold FoldApp.kt:14-21。
 */
class PowerMeterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init(this)
    }
}
