package com.chen.powermeter.util

import android.content.Context

/** 轻量配置持久化 */
object Prefs {
    private const val NAME = "powermeter"
    private const val KEY_INTERVAL = "interval_ms"
    private const val KEY_WAKELOCK = "wake_lock"
    private const val KEY_CHARGE_MONITOR = "charge_monitor"
    private const val KEY_SERIES_DUAL_BATTERY = "series_dual_battery"
    private const val KEY_MONITOR_MODE = "monitor_mode"

    /** 曲线自定义颜色（ARGB Int），按指标名分键存储 */
    private const val KEY_METRIC_COLOR_PREFIX = "metric_color_"

    /** 帧率悬浮窗位置（px）；-1 = 未拖动过，用默认位（右缘留 4dp、垂直 1/2 屏高） */

    fun getIntervalMs(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL, 1_000L)

    fun setIntervalMs(context: Context, value: Long) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_INTERVAL, value).apply()
    }

    /**
     * 锁屏保持采样：持 PARTIAL_WAKE_LOCK，阻止 CPU 在息屏后休眠，保证息屏段仍按设定间隔出点。
     *
     * **默认关闭**（2026-09-21 改动，原为 true）：持锁让 CPU 全程不睡，是息屏功耗最大的一项开销；
     * 而绝大多数场景下「息屏后采样稀疏一点」完全可以接受 —— 息屏期间采样间隔本身已自适应放宽到 5s，
     * 系统 suspend 造成的间隙对曲线形态影响有限。需要严格连续曲线（如测涓流）时再手动打开。
     *
     * ⚠️ 「充电功率监测」开启时无论本开关如何都**不持锁**：此时 CPU 不睡造成的自身耗电会
     * 直接抬高电池端读数，污染涓流段测量 —— 那是测量精度问题，不只是耗电问题。
     */
    fun getWakeLock(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAKELOCK, false)

    fun setWakeLock(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WAKELOCK, value).apply()
    }

    /**
     * 充电功率监测：开始采样 5s 后自动熄屏，并在「输入电流为 0 持续 30s」时自动导出 CSV。
     * 默认关闭 —— 该开关会主动改变屏幕状态，必须由用户显式开启。
     */
    fun getChargeMonitor(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CHARGE_MONITOR, false)

    fun setChargeMonitor(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CHARGE_MONITOR, value).apply()
    }

    /**
     * 串联双电池：内核上报的是**单节**电芯电压，串联机型整组电压为两节叠加（×2），功率随之 ×2。
     * 默认关闭 —— 这是机型相关的换算口径，误开会让电压、功率读数翻倍。
     * 小米机型内核口径不同，无需开启。
     */
    fun getSeriesDualBattery(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERIES_DUAL_BATTERY, false)

    fun setSeriesDualBattery(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERIES_DUAL_BATTERY, value).apply()
    }

    /**
     * 顶栏双击切换的监测模式（[com.chen.powermeter.ui.MonitorMode.key]）。
     *
     * 存字符串而非枚举序号：序号会随枚举项增删漂移，字符串始终可读且可安全回落。
     * 默认 POWER —— 老用户升级后仍停在功率监测。
     */
    fun getMonitorMode(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_MONITOR_MODE, "power") ?: "power"

    fun setMonitorMode(context: Context, mode: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MONITOR_MODE, mode).apply()
    }

    /**
     * 读某指标的自定义曲线颜色（ARGB）。
     *
     * 未设置时返回 **null 而不是某个占位色** —— 「未自定义」与「自定义成了黑色」必须可区分：
     * 前者要回落到默认调色板（功率指标还会跟随主题 primary），后者是用户明确的黑色选择。
     * 故这里先 [contains] 判存在性，不能直接用 getInt 的默认值。
     */
    fun getMetricColor(context: Context, metric: String): Int? {
        val sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val key = KEY_METRIC_COLOR_PREFIX + metric
        return if (sp.contains(key)) sp.getInt(key, 0) else null
    }

    fun setMetricColor(context: Context, metric: String, argb: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_METRIC_COLOR_PREFIX + metric, argb).apply()
    }

}
