package com.chen.powermeter.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chen.powermeter.R
import com.chen.powermeter.data.db.FrameSession
import com.chen.powermeter.service.FrameOverlayService
import com.chen.powermeter.service.FrameRecordController
import com.chen.powermeter.ui.common.BlurTopBar
import com.chen.powermeter.ui.theme.LocalCornerRadius
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
import java.util.Locale

/** 数字 / 单位统一等宽字体（口径同 PowerMeterScreen.NumericFontFamily） */
private val FrameNumericFont = FontFamily.Monospace

private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

/** 可空帧率的一位小数；null = 旧会话未计算 1% / 5% Low（破折号，不谎报 0） */
private fun Double?.f1OrDash(): String = this?.f1() ?: "—"

/**
 * 帧率监测页 —— 顶栏标题「帧率监测」，下方是历史记录列表；右下角 + 按钮开关**系统悬浮窗**。
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
    /** 点击某条记录：进入该条记录的详情页 */
    onOpenSession: (Long) -> Unit,
    /** 确认删除某条记录：落库删除后列表自动收窄（样本随外键级联清理，见 [FrameHistoryStore.delete]） */
    onDeleteSession: (Long) -> Unit,
    /** 双击顶栏标题：切回功率监测。入参 = 点击点的窗口 Y（ClipReveal 上下展开的锚点线） */
    onToggleMode: (Float) -> Unit,
) {
    val corner = LocalCornerRadius.current
    val cardShape = remember(corner) { RoundedCornerShape(corner) }

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
                FrameHistoryEmpty(topBarHeight = topBarHeight)
            } else {
                LazyColumn(
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
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.frame_history_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                // ⚠️ 必须显式给色：不传 color 会落到 LocalContentColor 的默认值黑色
                                // （同 FrameHistoryEmpty 的教训），深色模式下"黑底黑字"；
                                // onSurface 随深浅主题自动切换
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.frame_history_count, sessions.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FrameNumericFont,
                            )
                            Spacer(Modifier.weight(1f))
                            // 管理模式开关：激活态点亮成红色。⚠️ 必须用固定的 DeleteRed 而非
                            // colorScheme.error —— 本项目走 Material You 动态取色，本机壁纸
                            // 派生的 error 是粉色档，和 SportLink（主题覆盖 error = 正红、
                            // 无动态取色）的观感差一截；DeleteRed 与红框 / 确认键同色三处统一
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
                        }
                    }
                    items(items = sessions, key = { it.id }) { session ->
                        FrameSessionEntry(
                            session = session,
                            deleteMode = deleteMode,
                            shape = cardShape,
                            onClick = { onOpenSession(session.id) },
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
                    // 标题的窗口坐标：双击时把点击点换算成窗口 Y，作为模式切换
                    // ClipReveal 上下展开的锚点线（见 ModeRevealOverlay）
                    var titleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Column(
                        // 双击标题 = 切回功率监测（与功率监测页同一手势、同一位置的对称入口）
                        Modifier
                            .onGloballyPositioned { titleCoords = it }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { offset ->
                                    onToggleMode(titleCoords?.localToWindow(offset)?.y ?: 0f)
                                })
                            },
                    ) {
                        Text(
                            stringResource(R.string.mode_frame),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
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
        val context = LocalContext.current
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

/**
 * 单条帧率记录卡。
 *
 * 右端主值是**平均帧率**（这条记录最想回答的问题就是"跑得动吗"），
 * 副信息给时长 / 最低帧率 / 最高帧率 / 丢帧 / 刷新率 —— 读一条记录要能在不点进去的情况下判断
 * 值不值得细看。
 */
@Composable
private fun FrameSessionCard(
    session: FrameSession,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.appLabel,
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
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FrameStatCell(
                    stringResource(R.string.stat_duration),
                    formatFrameDuration(session.durationMs),
                )
                FrameStatCell(stringResource(R.string.frame_fps_min), session.minFps.f1())
                FrameStatCell(stringResource(R.string.frame_fps_max), session.maxFps.f1())
                FrameStatCell(stringResource(R.string.frame_jank), session.jankCount.toString())
                FrameStatCell(
                    stringResource(R.string.frame_refresh_rate),
                    "${session.refreshRateHz} Hz",
                )
            }
            // 1% / 5% Low（帧加权，CapFrameX 口径）：比 min/max 更能代表"卡不卡"。
            // 旧会话（v5 前落库）未计算 → 破折号
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
            ) {
                FrameStatCell(stringResource(R.string.frame_fps_low_1), session.lowFps1.f1OrDash())
                FrameStatCell(stringResource(R.string.frame_fps_low_5), session.lowFps5.f1OrDash())
            }
        }
    }
}

@Composable
private fun FrameStatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FrameNumericFont,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 会话时长（末样本时间 - 首样本时间）；异常值兜底为 0，避免出现负时长 */
private val FrameSession.durationMs: Long
    get() = (endTime - startTime).coerceAtLeast(0L)
