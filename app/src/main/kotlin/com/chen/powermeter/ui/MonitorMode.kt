package com.chen.powermeter.ui

import androidx.annotation.StringRes
import com.chen.powermeter.R

/**
 * 顶栏标题**双击**切换的两个监测模式。
 *
 * 用双击而非菜单/图标，是因为这两个模式是平级的「主页」而非同一页面下的两个 tab：
 * 双击标题是系统应用（相册、文件）里常见的隐藏入口，不占顶栏横向空间，也不会让
 * 顶栏右侧的「设置」按钮再多一个兄弟。
 */
enum class MonitorMode(
    /** 存入 Prefs 的键（见 Prefs.getMonitorMode）：字符串而非序号，避免枚举项增删导致漂移 */
    val key: String,
    @StringRes val titleRes: Int,
) {
    POWER("power", R.string.mode_power),
    FRAME("frame", R.string.mode_frame),
    ;

    companion object {
        fun of(key: String?): MonitorMode = entries.firstOrNull { it.key == key } ?: POWER
    }
}
