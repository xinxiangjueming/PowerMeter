package com.chen.powermeter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chen.powermeter.MainActivity
import com.chen.powermeter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 帧率录制的**前台服务外壳**。
 *
 * 存在的唯一理由：录制时必须能切到被测应用（游戏 / 视频），此时本应用退到后台 ——
 * 而 Android 12+ 会冻结缓存进程、更早的版本也随时可能回收，纯进程内协程撑不住
 * 5~30 分钟的录制。挂上前台服务后进程被提升为前台优先级，采集循环才能按 1s 一拍
 * 稳定跑完全程。
 *
 * 职责边界刻意收窄：**本类不做采集**，采集循环仍在 [FrameRecordController]（同进程单例）。
 * 服务只做两件事：把进程提到前台、把控制器状态渲染成常驻通知。
 * 这样启停顺序只有一个源头（控制器），不会出现"服务还活着但采集早停了"的错位。
 */
class FrameRecordService : Service() {

    companion object {
        /** 独立渠道：与功率采样的渠道分开，用户可单独关闭帧率录制的常驻通知 */
        private const val CHANNEL_ID = "frame_record"

        private const val NOTIFY_ID = 0x103

        /** 前台化 + 拉起通知刷新。采集本身由 [FrameRecordController.start] 负责 */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, FrameRecordService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun shutdown(context: Context) {
            runCatching { context.stopService(Intent(context, FrameRecordService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** onStartCommand 可能被重复调用（重复 startForegroundService），订阅只挂一次 */
    private var subscribed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 必须先 startForeground：Android 要求 startForegroundService 之后 5s 内完成，
        //    放在任何可能阻塞的调用（su 探测、Shizuku 绑定）之后都会触发 ANR/FGS 异常
        startForeground(
            NOTIFY_ID,
            buildNotification(fps = 0.0, elapsedMs = 0L, hasAccess = true),
        )
        if (!subscribed) {
            subscribed = true
            scope.launch { observeController() }
        }
        return START_NOT_STICKY
    }

    /**
     * 跟随控制器状态刷新通知。
     *
     * 控制器停止录制时会主动调 [shutdown]（见 [FrameRecordController.loop]），
     * 所以这里不需要"发现不在录制就 stopSelf"的分支 —— 少一条结束路径就少一处竞态。
     */
    private suspend fun observeController() {
        combine(
            FrameRecordController.fps,
            FrameRecordController.elapsedMs,
            FrameRecordController.recording,
        ) { fps, elapsed, recording -> Triple(fps, elapsed, recording) }
            .collect { (fps, elapsed, recording) ->
                if (!recording) return@collect
                // 通知只是采集状态的装饰品，渲染失败不该连带进程（它跑在主线程，
                // 任何异常都等于"点一下录制整个应用消失"），故兜一层
                runCatching { notify(buildNotification(fps, elapsed, hasAccess = true)) }
                    .onFailure { Log.w("PowerMeterShell", "刷新帧率通知失败", it) }
            }
    }

    override fun onDestroy() {
        // ⚠️ 自带作用域必须显式取消（项目约定：不留悬挂协程）
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(fps: Double, elapsedMs: Long, hasAccess: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // ⚠️ fps 可能是 NaN（录制刚开始、还没有第一帧有效差分，见 FrameRecordController._fps）。
        // Double.roundToInt() 对 NaN 是**直接抛 IllegalArgumentException**（不是返回 0）——
        // 真机实测：点录制 20ms 内整个应用崩掉（2026-09-22），因为 onStartCommand 在主线程用
        // Main.immediate 同步跑第一次 collect，combine 立刻吐出还没被采样覆盖的 NaN。
        // 这里必须按"无读数"显示破折号，不能用 0 冒充。
        val fpsText = if (fps.isNaN()) "—" else fps.roundToInt().toString()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(
                if (hasAccess) {
                    getString(R.string.notify_frame_title, fpsText)
                } else {
                    getString(R.string.notify_frame_no_access)
                },
            )
            .setContentText(getString(R.string.notify_frame_text, formatMmSs(elapsedMs)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name_frame_record),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_desc_frame_record)
                setShowBadge(false)
            },
        )
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    }
}
