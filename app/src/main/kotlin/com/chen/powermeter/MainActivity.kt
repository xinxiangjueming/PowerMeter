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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chen.powermeter.R
import com.chen.powermeter.data.BatteryInfoStore
import com.chen.powermeter.data.CsvImporter
import com.chen.powermeter.data.FrameHistoryStore
import com.chen.powermeter.data.ImportedSeries
import com.chen.powermeter.data.PowerSample
import com.chen.powermeter.data.db.SessionRecorder
import com.chen.powermeter.service.SamplingService
import com.chen.powermeter.ui.ChartColors
import com.chen.powermeter.ui.DialogBackdropHost
import com.chen.powermeter.ui.FrameDetailActivity
import com.chen.powermeter.ui.FrameMeterScreen
import com.chen.powermeter.ui.ModeRevealOverlay
import com.chen.powermeter.ui.ModeSwitchArgs
import com.chen.powermeter.ui.MonitorMode
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

    companion object {
        /** 手动导出文件名前缀（自动保存用 `powermeter_charge`，见 SamplingService） */
        private const val EXPORT_PREFIX = "powermeter"

        /**
         * 实况更新（Live Update / 超级岛 / 灵动岛）准入权限。
         *
         * 用字符串字面量而非 `Manifest.permission.*` 常量：该权限较新，低版本 compileSdk 上
         * 无对应常量（SportLink 同样写字面量）。Android 16 起它是**运行时权限**。
         */
        private const val PROMOTED_NOTIFICATION_PERMISSION =
            "android.permission.POST_PROMOTED_NOTIFICATIONS"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) ensurePromotedPermissionThenStart()
            else Toast.makeText(
                this,
                getString(R.string.toast_notification_permission_required),
                Toast.LENGTH_SHORT,
            ).show()
        }

    /**
     * 实况更新（Live Update / 超级岛 / 灵动岛）准入权限的申请回调。
     *
     * ⚠️ 不授予时 `NotificationManager.canPostPromotedNotifications()` 恒为 false，
     * [SamplingService] 里 `android.requestPromotedOngoing` 会被系统忽略 ——
     * 表现即「常驻通知不进实况更新 / 灵动岛」。这里只提示，不阻断采样。
     */
    private val requestPromotedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_focus_notification_required),
                    Toast.LENGTH_LONG,
                ).show()
            }
            // 无论是否授予都继续启动采样：实况更新是锦上添花，不该拦住核心功能
            launchService()
        }

    /** 通知权限已满足后：再争取实况更新权限（未授予则弹系统授权框），最后启动采样服务 */
    private fun ensurePromotedPermissionThenStart() {
        // 守卫 SDK≥34，口径与 SportLink `SportsActivity.requestAllPermissions()` 一致
        if (Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, PROMOTED_NOTIFICATION_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPromotedPermission.launch(PROMOTED_NOTIFICATION_PERMISSION)
            return
        }
        launchService()
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
                // 顶栏双击切换的监测模式，持久化在 Prefs（下次冷启动仍停在上次所在的模式）
                var mode by remember {
                    mutableStateOf(MonitorMode.of(Prefs.getMonitorMode(this@MainActivity)))
                }
                // 进行中的模式切换转场（ClipReveal 锚点展开，见 ModeRevealOverlay）；null = 无转场。
                // 双击标题 → 先起覆盖层（底层旧屏保持原样），收拢动画结束（onCommit）才落地 mode / Prefs
                var modeReveal by remember { mutableStateOf<ModeSwitchArgs?>(null) }
                val sessions by FrameHistoryStore.sessions.collectAsState()
                // 切进帧率监测时拉一次历史列表（冷启动恢复该模式时同样覆盖）
                LaunchedEffect(mode) {
                    if (mode == MonitorMode.FRAME) {
                        FrameHistoryStore.refresh(this@MainActivity)
                    }
                }

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

                // DialogBackdropHost：弹窗毛玻璃的「宿主 + slot」结构 —— 外部 Haze 采样源 +
                // 内部 miuix textureBlur 采样源，GlassDialog 卡片作为源兄弟渲染（同窗口兄弟铁律）。
                // 此前只有全屏趋势页挂了宿主，主页面的弹窗会静默降级实色卡（2026-09-25 接入，
                // 口径对齐 SportLink：弹窗三件套 = 外部 haze 模糊 + 内部 miuix 模糊 + 高光描边）。
                // 双模式屏的统一渲染入口：底屏（当前模式）与转场覆盖层（目标模式）共用
                // 同一套参数与回调（2026-09-25 切换动画 = ClipReveal 锚点展开，见 ModeRevealOverlay）
                val monitorScreen: @Composable (MonitorMode, (Rect) -> Unit) -> Unit = { m, onToggle ->
                    when (m) {
                        MonitorMode.POWER -> PowerMeterScreen(
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
                            importedName = if (viewingImport) {
                                importedFileName.ifEmpty { "CSV" }
                            } else {
                                null
                            },
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
                            onToggleMode = onToggle,
                        )
    
                        MonitorMode.FRAME -> FrameMeterScreen(
                            sessions = sessions,
                            onOpenSession = { sessionId ->
                                FrameDetailActivity.launch(this@MainActivity, sessionId)
                            },
                            onDeleteSession = { sessionId ->
                                FrameHistoryStore.delete(this@MainActivity, sessionId)
                            },
                            onToggleMode = onToggle,
                        )
                    }
                }

                DialogBackdropHost {
                    monitorScreen(mode) { titleRect ->
                        // 双击标题 = 起转场：目标模式在覆盖层里从标题矩形本体展开，**展开完成即落地**
                        // mode / Prefs —— 一次双击完整切换（见 ModeRevealOverlay 的类 KDoc）。
                        // 动画期 ClipReveal 已吞掉全部触摸（第一道防线），已有转场在途时
                        // 这里再兜一道：在途转场的重入会抹掉/覆盖在途状态。
                        if (modeReveal == null) {
                            modeReveal = ModeSwitchArgs(
                                target = if (mode == MonitorMode.POWER) {
                                    MonitorMode.FRAME
                                } else {
                                    MonitorMode.POWER
                                },
                                anchorRectInWindow = titleRect,
                            )
                        }
                    }
                }

                // 转场覆盖层挂在整棵 compose 树之上 —— 同 SportLink DeviceDetailOverlay 的
                // 挂载位置（android.R.id.content 的兄弟层）
                modeReveal?.let { args ->
                    ModeRevealOverlay(
                        args = args,
                        // 覆盖层底色 = 页面真实背景（Compose 主题色，随深浅色/动态取色走；
                        // View 层主题的 colorBackground 深色下是白的，动画会闪白）
                        backgroundColor = MaterialTheme.colorScheme.background.toArgb(),
                        // 展开完成 = 切换落地（一次双击完整切换，见 ModeRevealOverlay 的类 KDoc）
                        onCommit = {
                            // 幂等守卫：迟到的收尾回调不得覆盖新转场的在途状态
                            if (modeReveal === args) {
                                mode = args.target
                                Prefs.setMonitorMode(this@MainActivity, args.target.key)
                                // mode 变化触发上面的 LaunchedEffect(mode) 拉帧率历史（若切到帧率）
                                modeReveal = null
                            }
                        },
                        // 预览期收回（返回键）= 放弃切换，留在旧模式
                        onCancel = { if (modeReveal === args) modeReveal = null },
                    ) { onToggle ->
                        // 预览期内再双击 = 收回放弃（420ms 窗口，触摸被吞基本不可达）
                        monitorScreen(args.target, onToggle)
                    }
                }
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
                    toast(getString(R.string.toast_file_too_large))
                    return@launch
                }
                CsvImporter.QuickCheck.NOT_CSV -> {
                    toast(getString(R.string.toast_not_csv))
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
                    val dropped = if (parsed.skippedRows > 0) {
                        getString(R.string.import_dropped_rows, parsed.skippedRows)
                    } else {
                        ""
                    }
                    toast(getString(R.string.toast_import_done, parsed.samples.size, dropped))
                }
                .onFailure { e ->
                    toast(
                        getString(
                            R.string.toast_open_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
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
        ensurePromotedPermissionThenStart()
    }

    private fun launchService() {
        val intent = Intent(this, SamplingService::class.java)
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.toast_start_service_failed, it.message),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun stopSampling() {
        stopService(Intent(this, SamplingService::class.java))
    }

    /**
     * 导出 CSV。三条路径按优先级：
     * 1. **查看导入文件时** → 导出该文件数据（与界面所见一致，否则容易导出后才发现拿错了数据）；
     * 2. **有落库会话时** → 从 Room 分页导出会话全量，**导出成功后删除该会话**
     *    （会话是自动保存的临时存档，不是用户资产 —— 用户点了导出就说明已经拿到想要的东西）；
     * 3. **库中无会话时** → 回落到内存快照（覆盖「会话落库失败」「刚导出过又点一次」两种情形）。
     *
     * ⚠️ 不能在主线程导出：MediaStore insert / 逐行 String.format / update 全部同步，
     *    长会话（数万行）在主线程执行会冻结界面数百 ms ~ 秒级，有 ANR 风险。
     */
    private fun exportCsv() {
        val imported = ImportedSeries.samples.value
        if (imported.isNotEmpty()) {
            exportSamples(imported)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                SessionRecorder.exportCurrent(
                    context = this@MainActivity,
                    prefix = EXPORT_PREFIX,
                    // 采样仍在运行时导出 → 导出并删除后立刻开新会话，后续样本写进新会话；
                    // 不 rotate 的话样本会继续往已删除的 sessionId 写，撞外键约束
                    rotate = SamplingService.running.value,
                )
            }
            if (result.uri != null) {
                toast(getString(R.string.toast_export_done, fileNameOf(result.uri)))
                return@launch
            }
            val snapshot = SamplingService.snapshot()
            if (snapshot.isEmpty()) {
                toast(getString(R.string.toast_no_data))
                return@launch
            }
            exportSamples(snapshot)
        }
    }

    /** 导出给定序列（导入态与内存兜底共用） */
    private fun exportSamples(samples: List<PowerSample>) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                CsvExporter.export(this@MainActivity, samples, EXPORT_PREFIX)
            }
            toast(
                if (uri != null) {
                    getString(R.string.toast_export_done, fileNameOf(uri))
                } else {
                    getString(R.string.toast_export_failed)
                },
            )
        }
    }

    /** ⚠️ 不能用 uri.lastPathSegment：MediaStore 的 content:// 记录会返回数字 ID 而非文件名 */
    private fun fileNameOf(uri: Uri): String =
        CsvExporter.displayNameOf(this, uri) ?: "CSV"

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在 Shizuku 里完成授权，或重启过 Shizuku 服务；回到前台重新探测一次
        // （绑定 UserService 是异步的，recheck 内部会按需重新绑定）
        ShizukuHelper.recheck()
        // 从帧率详情页返回时同步一次历史列表（详情页可能删过记录）。
        // 开销是一次带索引的小表查询，功率监测模式下也执行，代价可忽略
        FrameHistoryStore.refresh(this)
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
