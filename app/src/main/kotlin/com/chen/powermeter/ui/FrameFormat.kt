package com.chen.powermeter.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 帧率监测两处界面（历史列表 / 详情页）共用的时间格式化。
 *
 * 收在同一个文件里而不是各写一份：两个页面显示的是同一批 [com.chen.powermeter.data.db.FrameSession]，
 * 格式一旦分叉（例如列表用「MM-dd HH:mm」、详情用「yyyy-MM-dd HH:mm:ss」）用户会以为
 * 列表和详情对不上号。
 */
private val STAMP_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 会话时间戳：同一天显示到分钟即可 —— 年与秒对「刚测完的那条记录」没有分辨力，
 * 只会把列表项右端的平均帧率挤开。
 */
internal fun formatFrameStamp(millis: Long): String = STAMP_FORMAT.format(Date(millis))

/** 时长：>1h 走 h/m/s，>1min 走 m/s，否则只给秒 */
internal fun formatFrameDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
}

/**
 * 实时帧率读数（悬浮 tab / 通知共用）。
 *
 * 精度口径：**< 100 保留 1 位小数**（89.0，能看出 59.6 与 60 的差别），**≥ 100 取整**（120）。
 * ⚠️ `NaN` = 还没有一帧有效差分（通道未通 / 首轮只建基线）→ 显示破折号。
 * 绝不能落到 `%.1f` 上（会渲染出字符串 "NaN"），更不能用 0.0 兜底 ——
 * 「通道没通」和「实测 0 帧」是两件事（项目约定：无读数返回 NaN）。
 * ⚠️ `Double.roundToInt()` 遇 NaN 是**抛异常**而不是返回 0（真机崩过一次），故此处只用字符串拼装。
 */
internal fun formatLiveFps(fps: Double): String = when {
    fps.isNaN() -> "—"
    fps < 100.0 -> String.format(Locale.US, "%.1f", fps)
    else -> String.format(Locale.US, "%.0f", fps)
}
