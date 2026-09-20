package com.kongj.powermeter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kongj.powermeter.MainActivity
import com.kongj.powermeter.R
import com.kongj.powermeter.data.BatteryInfo
import com.kongj.powermeter.data.PowerSample
import com.kongj.powermeter.data.RootPowerReader
import com.kongj.powermeter.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 前台采样服务：
 * - 锁屏后持续以 root 读取底层电量节点
 * - 常驻通知实时显示功率/电压/电流/温度
 * - 可选 PARTIAL_WAKE_LOCK 保证息屏后采样连续
 */
class SamplingService : Service() {

    companion object {
        const val ACTION_STOP = "com.kongj.powermeter.action.STOP"

        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "powermeter_sampling"
        private const val MAX_SAMPLES = 3600
        private const val NOTIFY_MIN_INTERVAL_MS = 800L

        private val _samples = MutableStateFlow<List<PowerSample>>(emptyList())
        val samples: StateFlow<List<PowerSample>> = _samples.asStateFlow()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _batteryInfo = MutableStateFlow<BatteryInfo?>(null)
        val batteryInfo: StateFlow<BatteryInfo?> = _batteryInfo.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _intervalMs = MutableStateFlow(1_000L)
        val intervalMs: StateFlow<Long> = _intervalMs.asStateFlow()

        fun setInterval(ms: Long) {
            _intervalMs.value = ms
        }

        fun clearSamples() {
            _samples.value = emptyList()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        _intervalMs.value = Prefs.getIntervalMs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_running.value) return START_STICKY

        _running.value = true
        _error.value = null
        startForegroundCompat(buildNotification(null))
        acquireWakeLock()

        scope.launch(Dispatchers.IO) {
            _batteryInfo.value = RootPowerReader.readBatteryInfo()
        }
        samplingJob = scope.launch { samplingLoop() }
        return START_STICKY
    }

    private suspend fun samplingLoop() {
        while (true) {
            val sample = withContext(Dispatchers.IO) { RootPowerReader.read() }
            if (sample != null) {
                _samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)
                _error.value = null
                maybeNotify(sample)
            } else {
                _error.value = RootPowerReader.lastError ?: "读取失败"
            }
            delay(_intervalMs.value.coerceAtLeast(200L))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
        }
    }

    private fun maybeNotify(sample: PowerSample) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_MIN_INTERVAL_MS) return
        lastNotifyAt = now
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(sample))
    }

    private fun buildNotification(sample: PowerSample?): Notification {
        val text = if (sample == null) {
            "正在启动采样…"
        } else {
            "${sample.powerW.f3()} W · ${sample.voltageV.f3()} V · " +
                "${sample.currentMa.f3()} mA · ${sample.tempBatteryC.f3()} ℃"
        }
        val title = when {
            sample == null -> "功率监测"
            sample.isCharging -> "充电中 · ${sample.socPct}%"
            else -> "放电中 · ${sample.socPct}%"
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时功率采样", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "锁屏后持续读取底层电量节点"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun acquireWakeLock() {
        if (!Prefs.getWakeLock(this)) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PowerMeter:Sampling").apply {
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        samplingJob = null
        releaseWakeLock()
        _running.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)
}
