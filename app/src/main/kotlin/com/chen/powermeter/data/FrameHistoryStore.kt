package com.chen.powermeter.data

import android.content.Context
import android.util.Log
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.data.db.FrameDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "FrameHistoryStore"

/**
 * 帧率历史记录的进程内仓库（[FrameSession] 列表）。
 *
 * 范式同 [BatteryInfoStore]：进程内单例 + 自带 IO 作用域，调用方不关心线程，
 * UI 侧只订阅 [sessions]。读取失败**不覆盖**已有值（一次偶发 IO 异常不该让列表清空）。
 *
 * 刷新时机：进入「帧率监测」时一次、从详情页返回时一次（详情页可能删过记录）。
 * 不做实时 Flow 订阅 —— 本阶段只有用户操作会改变数据，Room 的 Flow 查询会常驻一条
 * 表监听，为「每秒都没有变化」的表付常驻代价不划算。
 */
object FrameHistoryStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 并发刷新串行化：两次 refresh 几乎同时发起时只查一次 */
    private val mutex = Mutex()

    private val _sessions = MutableStateFlow<List<FrameSession>>(emptyList())
    val sessions: StateFlow<List<FrameSession>> = _sessions.asStateFlow()

    /** 从 Room 重新拉取历史列表 */
    fun refresh(context: Context) {
        scope.launch {
            mutex.withLock {
                runCatching {
                    FrameDatabase.getInstance(context.applicationContext)
                        .frameDao()
                        .allSessions()
                }.onSuccess { list ->
                    _sessions.value = list
                }.onFailure { e ->
                    Log.w(TAG, "读取帧率历史失败", e)
                }
            }
        }
    }

    /**
     * 删除一条会话（frame_samples 随外键级联清理，见 [com.chen.powermeter.data.db.FrameDao.deleteSession]），
     * 成功后立刻重拉列表 —— UI 侧只订阅 [sessions]，无需自己刷新。
     * 删除失败不重拉（保留旧列表），只留痕；列表的下一次 refresh 仍会反映真实数据。
     */
    fun delete(context: Context, sessionId: Long) {
        scope.launch {
            mutex.withLock {
                val dao = FrameDatabase.getInstance(context.applicationContext).frameDao()
                runCatching { dao.deleteSession(sessionId) }
                    .onFailure { e ->
                        Log.w(TAG, "删除帧率记录失败 id=$sessionId", e)
                        return@launch
                    }
                runCatching { dao.allSessions() }
                    .onSuccess { list ->
                        _sessions.value = list
                    }
                    .onFailure { e ->
                        Log.w(TAG, "读取帧率历史失败", e)
                    }
            }
        }
    }
}
