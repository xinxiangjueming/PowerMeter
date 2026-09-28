package com.chen.powermeter.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.chen.powermeter.R
import com.chen.powermeter.data.FpsAlgorithm
import com.chen.powermeter.data.FrameRateSource
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.service.FrameOverlayService
import com.chen.powermeter.service.FrameRecordController
import com.chen.powermeter.ui.common.AppCard
import com.chen.powermeter.ui.common.BlurTopBar
import com.chen.powermeter.ui.theme.LocalCornerRadius
import com.chen.powermeter.util.AppTransitions
import com.chen.powermeter.util.Prefs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeSource
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 数字 / 单位统一等宽字体（口径同 PowerMeterScreen.NumericFontFamily） */
private val FrameNumericFont = FontFamily.Monospace

private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

/**
 * 帧率监测页 —— 顶栏标题「帧率监测」+ 副标题「历史记录 共x条」（同功率页「已停止」的
 * 副标题位），顶栏右侧 Delete 图标 = 管理模式开关（对应功率页的「设置」按钮位）；
 * 下方是历史记录列表；右下角 + 按钮开关**系统悬浮窗**。
 *
 * ⚠️ 帧率 tab 本体已从应用内 Compose 悬浮层迁到 [FrameOverlayService]（TYPE_APPLICATION_OVERLAY）：
 * tab 要盖在**其它应用**上方、切走应用也常显实时帧率 —— Compose 的悬浮层做不到（只活在自家窗口里）。
 * tab 的拖动 / 轻点开始停止录制 / 时长窗口（仅两行：标题 + 5/10/15/30，5s 无点击自动隐藏）
 * 全部在服务里，见该类 KDoc。失败反馈走 [FrameRecordController] 的 Toast 与常驻通知，
 * 时长窗口内按用户要求不放任何其它信息。
 *
 * 顶栏与内容层的模糊 / 沉浸口径与 [PowerMeterScreen] 完全一致（同一套 BlurTopBar 三档模糊、
 * 水平只避挖孔不避导航栏、内容从顶栏下方穿过），两个模式切换时观感才不会跳。
 *
 * 历史列表数据来自 [com.chen.powermeter.data.FrameHistoryStore]（Room `frame_sessions` 表）；
 * 采集由 [FrameRecordController] 承载（进程级单例，页面重建不打断录制）。
 */
@Composable
fun FrameMeterScreen(
    sessions: List<FrameSession>,
    /** 确认删除某条记录：落库删除后列表自动收窄（样本随外键级联清理，见 [FrameHistoryStore.delete]） */
    onDeleteSession: (Long) -> Unit,
    /** 双击顶栏标题：切回功率监测（圆形揭露切换，圆孔从右下角展开，见 [ModeTransition]） */
    onToggleMode: () -> Unit,
) {
    val corner = LocalCornerRadius.current
    val cardShape = remember(corner) { RoundedCornerShape(corner) }
    val context = LocalContext.current
    // 横屏两列（口径同 PowerMeterScreen 的横屏分支）：历史卡是窄信息卡，半宽可读
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 页面底色（Compose 主题）：转场 Handoff 里带给详情页当裁剪容器底色 —— 保证展开/
    // 收拢窗口内的底色与主页连续（同 PowerMeterScreen.pageBackgroundArgb）
    val pageBackgroundArgb = MaterialTheme.colorScheme.background.toArgb()

    // 列表卡片 → 详情页：卡片本体已在 FrameSessionCard 内 register 转场锚点，这里消费
    // 锚点截图 + 登记 Handoff。与趋势全屏页（launchWithTransform）不同口径：详情页进场
    // 走主题侧边滑入，截图只供**退场**收拢一镜到底（2026-09-27 用户定稿：大场次整页
    // 图表卡首帧组合重，进场展开会卡）；截图失败 / 非 Activity 容器 → capture = null 普通启动
    val openSession: (Long) -> Unit = { sessionId ->
        val act = context as? Activity
        // keepPageSnapshot=false（2026-09-28 二改）：详情页已改**半透明窗口主题** →
        // 收拢期间裁剪窗口外直接露出**真实列表**（实时、已跟主题重绘），不再需要整窗
        // 冻结截图；留着它反而有害——不透明整窗图会把底下的真实列表盖住，且进场侧滑时
        // 整窗带着"列表像素"滑入（观感错）。整窗图不截也省下 ~18MB 峰值内存。
        val capture = act?.let {
            AppTransitions.capture(it.window.decorView, pageBackgroundArgb)
        }
        FrameDetailActivity.launch(act ?: context, sessionId, capture)
    }

    // 内容区水平 insets：只避挖孔，不避导航栏（口径同 PowerMeterScreen）
    val sideInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
    val topBarInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Top)
        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

    val hazeState = remember { HazeState() }
    val topBarHazeStyle = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        blurRadius = 20.dp,
        tint = null,
    )
    val useKyantTopBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val topBarBackdrop = rememberLayerBackdrop()
    val topBarHeight = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 64.dp

    // 录制态：× 关悬浮窗时若在录制，要先停并落库
    val recording by FrameRecordController.recording.collectAsState()

    // 管理模式（历史行右侧 Delete 图标开关）：卡片缩为 9/10，右侧 1/10 出现红色删除框逐条删。
    // 交互口径对齐 SportLink 设备管理页 DeviceManageScreen（顶栏 Delete 开关 + 半选红 Checkbox）
    var deleteMode by rememberSaveable { mutableStateOf(false) }
    // 待删除的记录：点卡片右侧红框先弹红色确认弹窗，确认才真删（危险操作不直删，同 SportLink）
    var deleteTarget by remember { mutableStateOf<FrameSession?>(null) }
    // 最后一条也删掉时自动退出管理模式（同 SportLink DeviceManageActivity）
    if (deleteMode && sessions.isEmpty()) deleteMode = false

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .then(if (useKyantTopBar) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
        ) {
            if (sessions.isEmpty()) {
                // 空态也要有采样源选择卡（第一场录制之前就得能选）；空态文案居中在
                // 「顶栏 + 选择卡」以下的剩余区域（topBarHeight 已由上方 Spacer 占位）
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(topBarHeight))
                    FpsSourceSelector(Modifier.padding(horizontal = 16.dp), cardShape)
                    FrameHistoryEmpty(topBarHeight = 0.dp)
                }
            } else {
                // 横屏两列 / 竖屏单列共用同一套 LazyVerticalGrid：列数按方向切换，
                // item key 保持条目身份，旋转不打断滚动位置。
                // （「历史记录 共x条」表头 + 删除开关原是这里的跨整行表头 item，2026-09-27
                // 按用户反馈搬进顶栏 —— 副标题位 + 右侧 Delete 按钮，不再随列表滚动）
                LazyVerticalGrid(
                    columns = if (landscape) GridCells.Fixed(2) else GridCells.Fixed(1),
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(sideInsets),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topBarHeight,
                        bottom = WindowInsets.safeDrawing.asPaddingValues()
                            .calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 帧率采样源选择卡（2026-09-29）：跨整行置顶，切算法即时生效
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FpsSourceSelector(shape = cardShape)
                    }
                    items(items = sessions, key = { it.id }) { session ->
                        FrameSessionEntry(
                            session = session,
                            deleteMode = deleteMode,
                            shape = cardShape,
                            onClick = { openSession(session.id) },
                            onDeleteClick = { deleteTarget = session },
                        )
                    }
                }
            }
        }

        BlurTopBar(
            kyantBackdrop = if (useKyantTopBar) topBarBackdrop else null,
            hazeState = if (useKyantTopBar) null else hazeState,
            hazeStyle = if (useKyantTopBar) null else topBarHazeStyle,
        ) {
            TopAppBar(
                title = {
                    Column(
                        // 双击标题 = 切回功率监测（与功率监测页同一手势、同一位置的对称入口）。
                        // 圆形揭露转场固定从屏幕右下角展开，无需采集标题矩形（见 ModeTransition）
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onToggleMode() })
                        },
                    ) {
                        Text(
                            stringResource(R.string.mode_frame),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        // 副标题 = 历史记录概要（口径同功率页「已停止/采样中」的副标题位：
                        // labelMedium + 弱化色，跟随条目数实时变化）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.frame_history_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.frame_history_count, sessions.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FrameNumericFont,
                            )
                        }
                    }
                },
                // 管理模式开关（2026-09-27 从滚播列表表头搬进顶栏：表头会随列表滚走，
                // 盖在毛玻璃顶栏下穿帮；交互口径本就应对齐 SportLink 设备管理页的
                // 「顶栏 Delete 开关」，对应功率页顶栏右侧的「设置」按钮位）。
                // ⚠️ 激活态必须用固定的 DeleteRed 而非 colorScheme.error —— 本项目走
                // Material You 动态取色，本机壁纸派生的 error 是粉色档，和 SportLink
                // （主题覆盖 error = 正红）的观感差一截；DeleteRed 与红框/确认键三处统一
                actions = {
                    IconButton(onClick = { deleteMode = !deleteMode }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = stringResource(R.string.frame_delete),
                            tint = if (deleteMode) {
                                DeleteRed
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                windowInsets = topBarInsets,
            )
        }

        // ── 右下角 FAB：帧率悬浮窗的开关（+ 开 / × 关）──
        val overlayRunning by FrameOverlayService.running.collectAsState()
        val overlayPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            // 从系统设置页回来：授了权就立刻把悬浮窗拉起来，用户不必再点一次 +
            if (Settings.canDrawOverlays(context)) FrameOverlayService.start(context)
        }
        FloatingActionButton(
            onClick = {
                when {
                    overlayRunning -> {
                        // × = 关悬浮窗；正在录制则先停并落库（服务 onDestroy 里还有一道兜底）
                        if (recording) FrameRecordController.stop()
                        FrameOverlayService.stop(context)
                    }
                    Settings.canDrawOverlays(context) -> FrameOverlayService.start(context)
                    else -> {
                        // 「显示在其它应用上层」是特殊权限，没有系统弹窗，只能引导去设置页开关
                        Toast.makeText(
                            context,
                            context.getString(R.string.frame_overlay_permission_rationale),
                            Toast.LENGTH_LONG,
                        ).show()
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            },
            // 紫色底（与悬浮 tab 未录制态的淡紫同一色系、更深一档，白图标对比度才够）
            containerColor = ColorFabBlue,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 16.dp),
        ) {
            FrameFabIcon(overlayVisible = overlayRunning)
        }

        // ── 删除确认弹窗（管理模式点卡片右侧红框触发；危险操作 → 红色确认键）──
        deleteTarget?.let { target ->
            GlassDialog(
                onDismissRequest = { deleteTarget = null },
                title = {
                    Text(
                        stringResource(R.string.frame_delete_confirm_title),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                // 仅标题 + 按钮，无正文描述（口径同 SportLink WearDeviceArchiveDialog）。
                // 按钮布局/长宽由 GlassDialog 的按钮栏统一管（半区 80% 宽、miuix 默认 40dp 高），
                // 这里只负责组件与配色 —— 与 SportLink DialogButtonRow 体系逐项对齐：
                dismissButton = {
                    // 取消 = 灰底灰字（SportLink DialogCancelButton 口径：背景
                    // surfaceContainerHigh、文字用与底色对比达标的一档）。PowerMeter 未包
                    // MiuixTheme，色值显式取自 MaterialTheme 保证深浅色自适应（同 FAB/Checkbox 做法）
                    MiuixTextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { deleteTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = corner,
                        colors = MiuixButtonDefaults.textButtonColors(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            textColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
                confirmButton = {
                    // 确认 = 红色实心（破坏性操作，SportLink DialogDangerConfirmButton 口径，
                    // DeleteRed 取值取证见其定义）。⚠️ 按钮内必须用 MiuixText —— miuix Button
                    // 提供的是 miuix 自己的 LocalContentColor，material3 Text 读不到（会黑字）
                    MiuixButton(
                        onClick = {
                            deleteTarget = null
                            onDeleteSession(target.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = corner,
                        colors = MiuixButtonDefaults.buttonColors(
                            color = DeleteRed,
                            contentColor = Color.White,
                        ),
                    ) {
                        MiuixText(
                            text = stringResource(R.string.action_confirm),
                            style = MiuixTheme.textStyles.button,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun FrameFabIcon(overlayVisible: Boolean) {
    // + ↔ × 的过渡：旧图标缩小淡出、新图标放大淡入（AnimatedContent 交叉切换），
    // 硬切会在同一帧内换掉图标，视觉上是"闪一下"（用户 2026-09-22 反馈无过渡动画）
    AnimatedContent(
        targetState = overlayVisible,
        transitionSpec = {
            val ms = 200
            (
                fadeIn(tween(ms, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        animationSpec = tween(ms, easing = FastOutSlowInEasing),
                        initialScale = 0.4f,
                    )
                ) togetherWith (
                fadeOut(tween(ms, easing = FastOutSlowInEasing)) +
                    scaleOut(
                        animationSpec = tween(ms, easing = FastOutSlowInEasing),
                        targetScale = 0.4f,
                    )
                )
        },
        label = "frameFabIcon",
    ) { visible ->
        Icon(
            imageVector = if (visible) MiuixIcons.Close else MiuixIcons.Add,
            contentDescription = stringResource(
                if (visible) R.string.frame_fab_close else R.string.frame_fab_open,
            ),
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * 右下角悬浮 + 按钮的底色：miuix 官方主色（蓝）。
 *
 * 取值取证而非拍脑袋：`miuix-ui 0.9.2` → sources.jar → `theme/Colors.kt:342`
 * light palette `primary = Color(0xFF3482FF)`（深色主题那一档是 `0xFF277AF7`）。
 *
 * 上一版用紫 `0xFF8B5CF6`（对白图标约 3.4:1）。换成 miuix 蓝后按 WCGA 相对亮度算约
 * **3.6:1**，比原来还高一档 —— FAB 里是白色 + / × 图标，不会糊。
 */
private val ColorFabBlue = Color(0xFF3482FF)

/**
 * 帧率采样源选择卡（2026-09-29 加，对标 Metric 的 realtime_fps_algorithm 设置）：
 * 三选一（Timestats 累计差分 / SF Latency 帧时间戳 / TaskFps 系统直推），点击即写
 * [Prefs.setFpsAlgorithm]，采集循环下一拍生效（切换拍自动作废旧算法的差分基线）。
 * 本机不可用的选项压暗 + 文案标注「自动回落」：SF Latency 已被存活探测判死、
 * TaskFps 缺 Shizuku v3 UserService（或系统 < Android 11）时实际仍走 Timestats。
 * ⚠️ 点击反馈 = 选中态变色（miuix 蓝，同 [ColorFabBlue] 的取证口径），无水波纹
 * （全应用 2026-09-25 起的去波纹口径）。
 * ⚠️ 切换时说明文案长短不一会让卡片高度跳变（2026-09-29 用户反馈）——内容列挂
 * [animateContentSize] 衔接高度，文案本身走 [AnimatedContent] 交叉淡变。
 */
@Composable
private fun FpsSourceSelector(modifier: Modifier = Modifier, shape: Shape) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(FpsAlgorithm.fromKey(Prefs.getFpsAlgorithm(context))) }
    // 可用性在组合期快照即可：判死/绑定状态在一次停留内变化时下一拍也会自动回落，不误导
    val latencyDead = remember { FrameRateSource.isLatencyDead() }
    val taskSupported = remember { FrameRateSource.isTaskFpsSupported() }
    val entries = listOf(
        Triple(FpsAlgorithm.TIMESTATS, stringResource(R.string.frame_source_timestats), true),
        Triple(FpsAlgorithm.SF_LATENCY, stringResource(R.string.frame_source_latency), !latencyDead),
        Triple(FpsAlgorithm.TASK_FPS, stringResource(R.string.frame_source_taskfps), taskSupported),
    )
    val desc = when (selected) {
        FpsAlgorithm.TIMESTATS -> stringResource(R.string.frame_source_desc_timestats)
        FpsAlgorithm.SF_LATENCY ->
            stringResource(R.string.frame_source_desc_latency) +
                if (latencyDead) " · " + stringResource(R.string.frame_source_fallback) else ""
        FpsAlgorithm.TASK_FPS ->
            stringResource(R.string.frame_source_desc_taskfps) +
                if (!taskSupported) " · " + stringResource(R.string.frame_source_fallback) else ""
    }
    AppCard(modifier = modifier.fillMaxWidth(), shape = shape) {
        // animateContentSize：三段说明文案行数不同（sf_latency/task_fps 还可能拼上
        // 「自动回落」后缀换行），不挂它，点一下卡片高度就硬跳一档
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(animationSpec = tween(200, easing = FastOutSlowInEasing)),
        ) {
            Text(
                stringResource(R.string.frame_source_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { (algo, label, available) ->
                    FpsSourceChip(
                        label = label,
                        selected = selected == algo,
                        dimmed = !available,
                        onClick = {
                            selected = algo
                            Prefs.setFpsAlgorithm(context, algo.key)
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // AnimatedContent：文案随选中项交叉淡变（只改 text 会在同一帧硬切换）
            AnimatedContent(
                targetState = desc,
                transitionSpec = { (fadeIn(tween(150)) togetherWith fadeOut(tween(150))) },
                label = "fpsSourceDesc",
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 采样源胶囊：选中 = miuix 蓝（light 0xFF3482FF / dark 0xFF277AF7，口径同 ColorFabBlue），白字 */
@Composable
private fun FpsSourceChip(
    label: String,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val bg by animateColorAsState(
        targetValue = when {
            selected -> if (isDark) Color(0xFF277AF7) else Color(0xFF3482FF)
            else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
        },
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "fpsSourceChipBg",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (dimmed) 0.45f else 1f),
        )
    }
}

@Composable
private fun FrameHistoryEmpty(topBarHeight: Dp) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = topBarHeight)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.frame_history_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                // ⚠️ 必须显式给色：不传 color 会落到 LocalContentColor 的默认值黑色，
                // 深色模式下就是"黑底黑字"，且永远不跟随主题
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.frame_history_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 删除确认按钮与管理模式红框共用的红色：固定 0xFFD32F2F（对齐 SportLink
 * DialogDangerConfirmButton 的取值取证）—— 不用 MaterialTheme.error，动态取色下 error
 * 可能被 ROM 主题带偏；固定红在浅深两色弹窗底上的白字对比度均达标。
 */
private val DeleteRed = Color(0xFFD32F2F)

/**
 * 单条记录条目：普通模式卡片占满可用宽；管理模式（[deleteMode]）下卡片**动画**缩为 9/10，
 * 右侧 1/10 出现 miuix Checkbox **半选中（Indeterminate）样式、内部红色**，点击 → 弹删除确认
 * （交互口径对齐 SportLink 设备管理页 DeviceManageScreen.DeviceCardEntry）。
 */
@Composable
private fun FrameSessionEntry(
    session: FrameSession,
    deleteMode: Boolean,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    // 管理模式右侧删除槽位权重动画 0→1 → 卡片由满宽平滑缩为 9/10、槽位渐宽至 1/10。
    // ⚠️ 槽位必须**常驻组合**且权重 coerceAtLeast(0.001f)：RowScope.weight 要求 > 0，
    // 非管理模式动画值为 0 直接抛 IllegalArgumentException；≈0 时被 clip 裁净，
    // 等价隐藏，进出管理模式宽度都平滑动画（SportLink 2026-09-03 两次闪退教训同款兜底）。
    val slotWeight by animateFloatAsState(
        targetValue = if (deleteMode) 1f else 0f,
        label = "frameDeleteSlotWeight",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FrameSessionCard(
            session = session,
            shape = shape,
            modifier = Modifier.weight(9f),
            onClick = onClick,
        )
        Box(
            modifier = Modifier
                .weight(slotWeight.coerceAtLeast(0.001f))
                .clip(RoundedCornerShape(8.dp))
                .padding(start = if (deleteMode) 10.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            // miuix Checkbox 半选中样式：Indeterminate 显示横线，与选中共用 checked 色 → 红色。
            // 仅删除模式才组合，非管理模式不残留可点击/无障碍节点（同 SportLink）。
            if (deleteMode) {
                Checkbox(
                    state = ToggleableState.Indeterminate,
                    onClick = onDeleteClick,
                    colors = CheckboxDefaults.checkboxColors(
                        checkedBackgroundColor = DeleteRed,
                        checkedForegroundColor = Color.White,
                    ),
                )
            }
        }
    }
}

/** 被测应用的身份（显示名 + 图标）：PackageManager 现场解析，两项都可能为 null（未解析到） */
private data class AppIdentity(
    val label: String?,
    val icon: ImageBitmap?,
)

/**
 * 现场解析被测应用身份（IO 线程，按包名缓存到组合）。
 *
 * 需要 Manifest 声明 `QUERY_ALL_PACKAGES`：Android 11+ 的包可见性过滤下，未声明的包
 * `getApplicationInfo` 直接 NameNotFoundException（旧会话 appLabel 回落成包名的根因）。
 */
@Composable
private fun rememberAppIdentity(packageName: String): AppIdentity {
    val context = LocalContext.current
    var identity by remember(packageName) { mutableStateOf(AppIdentity(null, null)) }
    LaunchedEffect(packageName) {
        identity = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                AppIdentity(
                    label = pm.getApplicationLabel(info).toString(),
                    icon = pm.getApplicationIcon(info).toIconBitmap(),
                )
            }.getOrDefault(AppIdentity(null, null))
        }
    }
    return identity
}

/**
 * Drawable → 方形位图。自适应图标（AdaptiveIconDrawable 等）画到画布上；
 * BitmapDrawable 直接复用原位图（Image 侧按 ContentScale 缩放）。失败返回 null。
 */
private fun Drawable.toIconBitmap(sizePx: Int = 128): ImageBitmap? = runCatching {
    (this as? BitmapDrawable)?.bitmap?.asImageBitmap()
        ?: Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }.asImageBitmap()
}.getOrNull()

/** 历史卡片的应用图标：40dp 圆角块；解析失败 / 未就绪时灰底占位（不闪空、不崩） */
@Composable
private fun AppIconBadge(icon: ImageBitmap?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(40.dp)
                .clip(shape),
        )
    } else {
        Box(
            modifier
                .size(40.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

/**
 * 单条帧率记录卡。
 *
 * 右端主值是**平均帧率**（这条记录最想回答的问题就是"跑得动吗"）。时长 / 最低 / 最高 /
 * 丢帧 / 刷新率 / 1% / 5% Low 统计格已按用户要求（2026-09-27）从卡片移除，
 * 全量指标点进详情页看。
 */
@Composable
private fun FrameSessionCard(
    session: FrameSession,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 容器变换源条目（同 PowerMeterScreen.FullscreenPillButton 口径）：登记自身窗口矩形，
    // 点击先 register 锚点再回调 —— 详情页一镜到底的展开起点/收拢终点（AppTransitions 链路）。
    // containerSource 挂卡片本体（含四角圆角的完整矩形，SportLink DeviceItemCard 同位）；
    // textSource 挂 padding 内全部内容 —— 分层动画"卡片底面钉在原位、全部内容（图标+
    // 文字+统计）随窗口边滑移渐隐"（2026-09-27 用户定稿：不只左侧文本列，卡片内所有
    // 东西一起上滑/落回；SportLink 原版挂左侧文本列，本项目扩成全内容）
    val source = rememberContainerSource()
    val textSource = rememberContainerTextSource(source)
    // 容器统一走 AppCard（全 App 唯一卡片实现）：此前这里用 ElevatedCard，其内部 Surface
    // 不传 tonalElevation（=0）→ 不吃 surfaceTint，与功率页 2dp 的卡片同色号却不同观感
    AppCard(
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .containerSource(source),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // ⚠️ indication = null（全 app 去水波纹口径，2026-09-25 起）：默认 Material 波纹
                // 在按下瞬间就开始扩散，而 AppTransitions.capture 恰在此刻截取整窗 —— 波纹的
                // 灰色圆形斑块（被卡片圆角裁剪）会被烤进锚点截图、跟着一镜到底全程走
                // （2026-09-27 用户截图实锤：卡片右端出现波纹形状的灰斑）。点击反馈由转场承担
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    AppTransitions.register(source)
                    onClick()
                }
                .padding(16.dp)
        ) {
            // 滑移层 = 16dp padding **内**的全部内容（图标 + 应用名/包名/时间 + 右侧帧率 +
            // 统计格，2026-09-27 用户定稿：卡片内所有东西一起随窗口边上滑/落回，不只左侧
            // 文本列——SportLink 是挂左侧文本列，本项目按用户要求扩成全内容）。
            // ⚠️ 必须套在 padding 之内：textBounds 若取到含 padding 的整卡矩形，抹字留白带
            // <8px 会拒绝抹除 → 钉住的本体带着全部文字 → 滑移层与本体双重出现
            Column(Modifier.then(textSource)) {
            // 顶行 = 应用图标 + 应用显示名（2026-09-27 用户指定）。
            // 显示名优先用落库的 appLabel（记录时刻的事实，应用改名/卸载后不变）；
            // ⚠️ 旧会话落库时 Manifest 还没声明 QUERY_ALL_PACKAGES，Android 11+ 包可见性让
            // getApplicationInfo 抛 NameNotFoundException、resolveAppLabel 回落成了包名 ——
            // appLabel == packageName 的这批记录现场补解析（现在可见了）；新会话落库已正确。
            val identity = rememberAppIdentity(session.packageName)
            val displayName =
                if (session.appLabel != session.packageName) session.appLabel
                else identity.label ?: session.appLabel
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconBadge(identity.icon)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        session.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatFrameStamp(session.startTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FrameNumericFont,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        session.avgFps.f1(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FrameNumericFont,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.unit_fps),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            } // textSource 内层：滑移层到此为止（卡片 16dp padding 环留在钉住的本体上）
        }
    }
}
