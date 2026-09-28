package com.chen.powermeter.data

/**
 * 实时帧率**采样源**（2026-09-29 加，对标 Metric 的 realtime_fps_algorithm 三选一）。
 *
 * 选择存 [com.chen.powermeter.util.Prefs.getFpsAlgorithm]（字符串 key，口径同
 * MonitorMode：序号会随枚举增删漂移，字符串可读且可安全回落）。采样循环每拍重读：
 * 切换即时生效，跨算法的差分基线由 FrameRecordController 在切换拍作废。
 *
 * ⚠️ 三条路的"原始程度"不同，落库字段的可用性随之分叉（详见各实现）：
 * | 算法 | 帧数来源 | 帧间隔 | 丢帧 |
 * | TIMESTATS | 累计计数差分 | presentToPresent 直方图差分（整 ms 桶） | droppedFrames 差分 |
 * | SF_LATENCY | 原始 present 时间戳 | **真实逐帧间隔** | 无（缺测 0） |
 * | TASK_FPS | 系统直推 fps | 1000/fps 推导 | 无（缺测 0） |
 */
enum class FpsAlgorithm(val key: String) {

    /**
     * `dumpsys SurfaceFlinger --timestats -dump -maxlayers 8` 累计计数差分 —— 改造前唯一路径，
     * 五套护栏（maxLayers 收窄 / 行距守卫 / 物理上限 / LTPO 探测 / 陈旧基线作废）护航，
     * 全机型全通道可用，**默认值**。
     */
    TIMESTATS("timestats"),

    /**
     * `dumpsys SurfaceFlinger --latency <layer>` 原始帧时间戳 FIFO —— Scene 的通用解析器
     * 同款。无累计计数器、无跟踪表上限、窗口由时间戳自带（免 dt 计时噪声）。
     * ⚠️ 本机（24031PN0DC / HyperOS V816）adb 实测已死（精确图层名也只回周期行），
     * FrameRateSource 存活探测判死后自动回落 TIMESTATS；其它 ROM（尤其 AOSP 14 及以下）
     * 仍可用。
     */
    SF_LATENCY("sf_latency"),

    /**
     * 系统 TaskFpsCallback（IWindowManager.registerTaskFpsCallback）—— 系统对前台任务
     * **主动推送** FPS，零采样开销。只走 Shizuku（UserService = shell 身份持有
     * ACCESS_FPS_COUNTER，AOSP Shell manifest 自带）；root/su 通道与旧版 UserService
     * 不可用 → 自动回落 TIMESTATS。帧间隔为 1000/fps 推导值（系统不逐帧推时间戳）。
     */
    TASK_FPS("task_fps"),
    ;

    companion object {
        fun fromKey(key: String?): FpsAlgorithm =
            values().firstOrNull { it.key == key } ?: TIMESTATS
    }
}
