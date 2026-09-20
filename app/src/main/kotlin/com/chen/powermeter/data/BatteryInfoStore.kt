package com.chen.powermeter.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 电池静态信息（型号 / 技术 / 健康度 / 循环次数 / 设计容量 / 最大充电档位）的进程内仓库。
 *
 * 为什么与 [com.chen.powermeter.service.SamplingService] 解耦（2026-09-21）：
 * 这份数据是**慢变量**，与「是否正在采样」没有关系。此前它只挂在采样服务的
 * `onStartCommand` 里读取，于是冷启动 App（前台服务没起来）时 `batteryInfo` 恒为 null，
 * UI 侧 `if (batteryInfo != null)` 令电池卡片整张不渲染 —— 表现为「必须先点开始采样，
 * 电池卡片才出现」。
 *
 * 现在：`MainActivity.onCreate` 冷启动即调 [loadIfNeeded]；采样服务启动时调 [refresh]
 * 刷新循环次数等缓慢变化的量。读取走 Shizuku / root 通道（见 [RootPowerReader]），
 * 必须在后台线程，故本仓库自带 IO 协程作用域，调用方无需关心线程，也不会阻塞首帧。
 *
 * 进程内单例（服务与 Activity 未声明独立进程，静态状态天然共享），与
 * [ImportedSeries]、[com.chen.powermeter.ui.ChartColors] 同一范式。
 *
 * ⚠️ 显示条件（用户拍板 2026-09-21）：**仅 root 机器显示**。无取数权限（既无 Shizuku
 * 也无 root）与 Shizuku(shell) 模式下都直接不显示，不做任何提示、不报错。
 *
 * 实现上不需要额外分支 —— [RootPowerReader.readBatteryInfo] 已自行收口：非 root 机器、
 * 或关键字段一个都读不到时返回 null；本仓库遇到 null 不写入，`info` 保持 null，
 * UI 侧 `if (batteryInfo != null)` 自然不渲染。冷启动预读也不会把失败写进
 * `SamplingService.error`（不经过 samplingLoop），故用户看不到任何「读取失败」打扰。
 * 请勿在此处新增「未 root」提示或占位卡片。
 */
object BatteryInfoStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 冷启动预读与采样启动刷新可能几乎同时发起，串行化以避免并发 su */
    private val mutex = Mutex()

    private val _info = MutableStateFlow<BatteryInfo?>(null)
    val info: StateFlow<BatteryInfo?> = _info.asStateFlow()

    /**
     * 冷启动预读。已有值直接跳过（幂等）—— Activity 因配置变更重建时不会反复执行 su。
     * 读取失败（未授权 / su 不可用）不写入任何值，下次冷启动自然重试。
     */
    fun loadIfNeeded() {
        if (_info.value != null) return
        refresh()
    }

    /**
     * 强制重读。采样服务启动时调用一次：循环次数、健康度会随使用缓慢变化，开新一场会话时刷新。
     *
     * 读取失败（返回 null）**不覆盖**已有值：一次偶发的 su 超时不应让已显示的卡片消失。
     */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                RootPowerReader.readBatteryInfo()?.let { _info.value = it }
            }
        }
    }
}
