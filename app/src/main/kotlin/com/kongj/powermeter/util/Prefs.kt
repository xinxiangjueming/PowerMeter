package com.kongj.powermeter.util

import android.content.Context

/** 轻量配置持久化 */
object Prefs {
    private const val NAME = "powermeter"
    private const val KEY_INTERVAL = "interval_ms"
    private const val KEY_WAKELOCK = "wake_lock"

    /** 曲线自定义颜色（ARGB Int），按指标名分键存储 */
    private const val KEY_METRIC_COLOR_PREFIX = "metric_color_"

    fun getIntervalMs(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL, 1_000L)

    fun setIntervalMs(context: Context, value: Long) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_INTERVAL, value).apply()
    }

    fun getWakeLock(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAKELOCK, true)

    fun setWakeLock(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WAKELOCK, value).apply()
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
