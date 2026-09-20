package com.chen.powermeter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chen.powermeter.data.BatteryInfoStore
import com.chen.powermeter.data.CsvImporter
import com.chen.powermeter.data.ImportedSeries
import com.chen.powermeter.service.SamplingService
import com.chen.powermeter.ui.ChartColors
import com.chen.powermeter.ui.PowerMeterScreen
import com.chen.powermeter.ui.theme.PowerMeterTheme
import com.chen.powermeter.util.CsvExporter
import com.chen.powermeter.util.NavigationBarHelper
import com.chen.powermeter.util.Prefs
import com.chen.powermeter.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchService()
            else Toast.makeText(this, "需要通知权限才能在锁屏后常驻采样", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
        // 曲线颜色从 Prefs 恢复一次；全屏页同样会 load 一次，两处共用一个仓库
        ChartColors.load(this)
        // 电池静态信息冷启动即预读：root 机器上不点「开始采样」也能看到电池卡片。
        // 内部为 IO 协程 + 幂等（已有值即返回），不阻塞首帧，Activity 重建也不会重复执行 su
        BatteryInfoStore.loadIfNeeded()

        setContent {
            PowerMeterTheme {
                val running by SamplingService.running.collectAsState()
                // 实时序列走环形缓冲（档二-1）：订阅**版本号**触发重组，再按需取一次快照。
                // ⚠️ 刻意不订阅 List 类型的 StateFlow —— 那等价于每秒做一次整表分配。
                //    息屏无帧时不发生重组，连 snapshot 都不会执行。
                val liveVersion by SamplingService.sampleVersion.collectAsState()
                val liveSamples = remember(liveVersion) { SamplingService.snapshot() }
                val importedSamples by ImportedSeries.samples.collectAsState()
                val importedFileName by ImportedSeries.fileName.collectAsState()
                val batteryInfo by BatteryInfoStore.info.collectAsState()
                val error by SamplingService.error.collectAsState()
                // Shizuku 三态（供「采样设置」里的权限区块显示 + 授权/重试操作）
                val shizukuAvailable by ShizukuHelper.available.collectAsState()
                val shizukuGranted by ShizukuHelper.granted.collectAsState()
                val shizukuBound by ShizukuHelper.serviceBound.collectAsState()

                // 数据源二选一：导入态优先。查看历史文件期间实时采样照常进行、互不覆盖，
                // 退出查看（onExitImport）后自动回到实时曲线
                val viewingImport = importedSamples.isNotEmpty()
                val samples = if (viewingImport) importedSamples else liveSamples

                var interval by remember { mutableLongStateOf(Prefs.getIntervalMs(this@MainActivity)) }
                var wakeLock by remember { mutableStateOf(Prefs.getWakeLock(this@MainActivity)) }
                var chargeMonitor by remember {
                    mutableStateOf(Prefs.getChargeMonitor(this@MainActivity))
                }
                var seriesDualBattery by remember {
                    mutableStateOf(Prefs.getSeriesDualBattery(this@MainActivity))
                }

                PowerMeterScreen(
                    running = running,
                    samples = samples,
                    batteryInfo = batteryInfo,
                    error = error,
                    intervalMs = interval,
                    wakeLock = wakeLock,
                    chargeMonitor = chargeMonitor,
                    seriesDualBattery = seriesDualBattery,
                    shizukuAvailable = shizukuAvailable,
                    shizukuGranted = shizukuGranted,
                    shizukuBound = shizukuBound,
                    importedName = if (viewingImport) importedFileName.ifEmpty { "CSV" } else null,
                    onStart = { startSampling() },
                    onStop = { stopSampling() },
                    onIntervalChange = { value ->
                        interval = value
                        Prefs.setIntervalMs(this@MainActivity, value)
                        SamplingService.setInterval(value)
                    },
                    onWakeLockChange = { value ->
                        wakeLock = value
                        Prefs.setWakeLock(this@MainActivity, value)
                        // 立即同步持锁策略，不必等下一次采样循环或息屏广播
                        SamplingService.onPrefsChanged()
                    },
                    onChargeMonitorChange = { value ->
                        chargeMonitor = value
                        Prefs.setChargeMonitor(this@MainActivity, value)
                        // 充电监测开启 → 强制释放 wakelock（测量精度要求 CPU 不参与负载）
                        SamplingService.onPrefsChanged()
                    },
                    onSeriesDualBatteryChange = { value ->
                        seriesDualBattery = value
                        Prefs.setSeriesDualBattery(this@MainActivity, value)
                    },
                    onShizukuRequest = { ShizukuHelper.requestPermission() },
                    onShizukuRetry = { ShizukuHelper.forceRebind() },
                    onExport = { exportCsv() },
                    onClear = { SamplingService.clearSamples() },
                    onExitImport = { ImportedSeries.clear() },
                )
            }
        }

        // 冷启动由「打开方式 / 分享」拉起时，onCreate 收到的就是那个 Intent。
        // ⚠️ 必须用 savedInstanceState == null 兜住：重建（深浅色切换、字体缩放等未在
        //    configChanges 里声明的配置变更，以及进程被杀后的恢复）都会让 intent 原样回到
        //    onCreate —— 不拦一道就会重新解析整个文件、再弹一次 Toast。
        //    重建时 ImportedSeries 仍在进程内，展示态不受影响，跳过是正确的。
        if (savedInstanceState == null) handleOpenIntent(intent)
    }

    /**
     * 应用已在运行时再从外部打开一个 CSV。
     *
     * Manifest 中 MainActivity 声明了 `launchMode="singleTop"`，因此这里会被回调而不是
     * 在栈顶再叠一个实例（叠实例会导致「返回」时看到两份界面、且共享同一份进程内状态）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val uri = incomingUri(intent) ?: return
        loadCsv(uri)
    }

    /**
     * 从 Intent 里取出要打开的文件。
     *
     * 对外注册了两套通道（见 Manifest 注释），这里一并兼容：
     * - `ACTION_VIEW` → `data`：「用其它应用打开」、文件管理器点击；
     * - `ACTION_SEND` → `EXTRA_STREAM`：「分享」。少数 App 只塞 `clipData`（Android 通常
     *   会把 EXTRA_STREAM 同步进 clipData），故 clipData 作为兜底。
     */
    private fun incomingUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            stream ?: intent.clipData?.getItemAt(0)?.uri
        }
        else -> null
    }

    /**
     * 读取 content:// 并解析为采样序列，装载到「查看态」数据源。
     *
     * 流程分两步，都不能省：
     * 1. [CsvImporter.quickCheck] —— 因为对外注册很宽（含 content:// 的 MIME 通配兜底，
     *    通配字面量本身含块注释结束符号，故此处改用文字表述），
     *    点进来一份图片/视频是常态，必须先用文件名/大小挡掉，避免把整个文件读成字符串；
     * 2. [CsvImporter.read] —— 按表头做权威校验（快筛只看文件名，不看内容）。
     *
     * 任一步不通过都**只提示、不改动当前展示**，用户仍停留在原来的界面上。
     * 走 lifecycleScope：两步都是 IO，且 Activity 销毁后无需再回调 UI。
     */
    private fun loadCsv(uri: Uri) {
        lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) { CsvImporter.quickCheck(this@MainActivity, uri) }
            when (check) {
                CsvImporter.QuickCheck.TOO_LARGE -> {
                    toast("文件过大，无法作为采样数据打开")
                    return@launch
                }
                CsvImporter.QuickCheck.NOT_CSV -> {
                    toast("只能打开 CSV 文件")
                    return@launch
                }
                CsvImporter.QuickCheck.OK -> Unit
            }

            val result = withContext(Dispatchers.IO) {
                runCatching { CsvImporter.read(this@MainActivity, uri) }
            }
            result
                .onSuccess { parsed ->
                    ImportedSeries.set(queryDisplayName(uri), parsed.samples)
                    val dropped = if (parsed.skippedRows > 0) "，丢弃 ${parsed.skippedRows} 行" else ""
                    toast("已打开 ${parsed.samples.size} 条采样记录$dropped")
                }
                .onFailure { e ->
                    toast("打开失败：${e.message ?: e.javaClass.simpleName}")
                }
        }
    }

    /** 显示名优先取 provider 的 DISPLAY_NAME，取不到再退回 URI 末段（SAF 末段通常是文档 ID） */
    private fun queryDisplayName(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
        return fromProvider
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "CSV"
    }

    private fun startSampling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchService()
    }

    private fun launchService() {
        val intent = Intent(this, SamplingService::class.java)
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            Toast.makeText(this, "启动采样服务失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopSampling() {
        stopService(Intent(this, SamplingService::class.java))
    }

    private fun exportCsv() {
        // 导出「当前正在看的那一份」：查看导入文件时导出的就是该文件的数据，
        // 与界面所见一致（否则容易导出后才发现拿错了数据）
        val list = ImportedSeries.samples.value.ifEmpty { SamplingService.snapshot() }
        if (list.isEmpty()) {
            toast("暂无采样数据")
            return
        }
        // ⚠️ 不能在主线程导出：CsvExporter 内含 MediaStore insert / openOutputStream /
        //    逐行 String.format / update，全部同步。实时态上限 3600 行、导入态上限 20000 行
        //    （约 20 万次格式化），主线程执行会冻结界面数百 ms ~ 1s+，有 ANR 风险。
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { CsvExporter.export(this@MainActivity, list) }
            // Toast 回到主线程弹（lifecycleScope 默认 Dispatchers.Main）
            toast(
                if (uri != null) "已导出：Download/PowerMeter/${uri.lastPathSegment}" else "导出失败",
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在 Shizuku 里完成授权，或重启过 Shizuku 服务；回到前台重新探测一次
        // （绑定 UserService 是异步的，recheck 内部会按需重新绑定）
        ShizukuHelper.recheck()
    }

    // 配置变更（旋转/深浅色切换/180° 翻转）后重放透明系统栏设置
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
        window.decorView.post {
            if (!isFinishing && !isDestroyed) {
                NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
