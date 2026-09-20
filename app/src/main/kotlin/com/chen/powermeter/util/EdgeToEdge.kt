package com.chen.powermeter.util

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 小白条（手势导航栏）真沉浸适配。
 *
 * 三层要点：
 * 1. enableEdgeToEdge() 在 Android 15(API 35) 上对 SystemBarStyle.auto() 会调用
 *    setNavigationBarContrastEnforced(true)，浅色模式会盖一层不透明白色遮罩 →
 *    必须在调用后显式置透明并关闭 contrast。
 * 2. 横竖屏旋转（声明 configChanges 时不重建）与系统重放窗口属性，需要在
 *    onConfigurationChanged 里重设，并 post 一帧兜底。
 * 3. Android 12+ 的 180° 翻转（landscape↔reverseLandscape）不回调 onConfigurationChanged，
 *    需靠 decorView 的 insets 监听兜底。
 */
object NavigationBarHelper {

    /**
     * 仅置窗口属性，**不**隐藏系统栏——隐藏动作必须是 Activity 的显式意图（见 [enterImmersive]）。
     *
     * @param immersive 保留仅为调用点可读性（如全屏页标注"随后要隐藏系统栏"）。
     *                  自 decorFits 改为**无条件**置 false 后，本参数不再影响行为；
     *                  保留签名以免调用方改动（TrendFullscreenActivity 仍传 true）。
     */
    fun setupEdgeToEdge(
        activity: ComponentActivity,
        lightStatusBar: Boolean? = null,
        immersive: Boolean = false,
    ) {
        val window = activity.window

        activity.enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        applyWindowProperties(activity, lightStatusBar)

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    applyWindowProperties(activity, lightStatusBar)
                    // ⚠️ 这里**不能**重放 enterImmersive()：用户上滑唤出的瞬时系统栏会触发
                    //    insets 回调，若在此重新 hide，刚唤出的小白条会被立刻收回（手势无反馈）。
                    //    隐藏系统栏只在 onConfigurationChanged / onWindowFocusChanged 重放。
                }
            }
            insets
        }
    }

    /**
     * 真沉浸：隐藏状态栏与小白条，从屏幕边缘上滑可瞬时唤出（再次滑动或超时自动收回）。
     *
     * 必须先调 [setupEdgeToEdge]（置系统栏透明 + 关 contrast），否则隐藏后残留的
     * 不透明导航栏底色会露出一条色带。
     */
    fun enterImmersive(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 退出真沉浸：恢复状态栏与小白条常驻显示。
     *
     * 调用点：`TrendFullscreenActivity.requestClose()`（关闭全屏页的 C+ 时序第一步）。
     * 必须在 finish 之前调用 —— 主页顶栏高度 = `safeDrawing` 顶部 inset + 64dp，若等窗口销毁
     * 才由系统恢复系统栏，主页首帧会先按"无系统栏"排一次、再跳一次（返回瞬闪的第二个来源）。
     *
     * ⚠️ **有意不碰** `setDecorFitsSystemWindows`：本方法的使用场景是"全屏页即将销毁"，
     *    改成 true 只会让窗口在过渡期按系统栏内缩、在栏一侧露出色带；edge-to-edge 的窗口属性
     *    由 [applyWindowProperties] 无条件维护（重放链的公共出口），此处改回去反而制造断层。
     */
    fun exitImmersive(activity: Activity) {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 幂等重放：只写窗口属性，不注册监听器（避免递归）
     *
     * ⚠️ 这三行看着"冗余"（androidx.activity 1.13 的 `EdgeToEdgeApi35.setUp()` 内部确实已经把
     *    两根系统栏置为 TRANSPARENT），但**删不得**——曾据此删过一次，结果横屏小白条立刻回归：
     *  1. `enableEdgeToEdge()` 在最后一步会把 `isNavigationBarContrastEnforced` 置为 true
     *     （`SystemBarStyle.auto()` 的 nightMode == MODE_NIGHT_AUTO 时），必须在这里覆盖掉，
     *     否则浅色模式导航栏被盖一层不透明白色遮罩（"小白条被涂白"）；
     *  2. 旋转 / 180° 翻转后，系统与 MIUI/HyperOS 会按主题默认值**重放**导航栏颜色，
     *     而本方法正是重放的兜底入口（`onConfigurationChanged` 与 decorView insets 监听都调它），
     *     缺了它旋转后就会退回不透明导航栏。
     *
     * 3. **decorFitsSystemWindows 必须无条件压回 false**（原实现只在 `immersive == true` 时设置）：
     *    主页走 immersive=false 分支，此前仅依赖 `enableEdgeToEdge()` 内部那一次；实测横屏
     *    内容在导航栏上缘被硬裁切（窗口被系统 inset ≈30dp，且竖屏不复现）说明 ROM 会在旋转后
     *    把窗口**重新按系统栏避让**。本方法正是"重放链"（`onConfigurationChanged` /
     *    decorView insets 监听）的公共出口，缺这一道，旋转后窗口就退回避让系统栏的状态。
     *
     * Android 16（API 36）起这三个属性被标记 @Deprecated，但功能仍然可用且没有 1:1 替代 API，
     * 因此保留调用并显式抑制告警（而不是删除）。
     */
    @Suppress("DEPRECATION")
    private fun applyWindowProperties(
        activity: Activity,
        lightStatusBar: Boolean?,
    ) {
        val window = activity.window
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // 无条件：内容延伸到系统栏之下（幂等，enableEdgeToEdge 内部同样置 false）。
        // 不能只在沉浸页设置——否则重放链里没人把系统/ROM 重放回来的"避让系统栏"压回去。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (lightStatusBar != null) setSystemBarsAppearance(activity, lightStatusBar)
    }

    /**
     * 状态栏与导航栏（手势条）的明暗。
     *
     * **两根系统栏必须同时设置**：只传 `APPEARANCE_LIGHT_STATUS_BARS` 时，导航栏的明暗
     * 不受应用控制，MIUI/HyperOS 会按自己的判定（windowBackground / 内容采样）决定手势条
     * 配色，浅色内容下容易给出不透明白底（"小白条被白底包裹"）。mask 必须同步包含
     * `APPEARANCE_LIGHT_NAVIGATION_BARS`，否则清除 appearance 时导航栏那一位清不掉。
     */
    private fun setSystemBarsAppearance(activity: Activity, light: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = activity.window.insetsController ?: return
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (light) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            val decor = activity.window.decorView
            @Suppress("DEPRECATION")
            val lightFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                if (light) decor.systemUiVisibility or lightFlag
                else decor.systemUiVisibility and lightFlag.inv()
        }
    }
}
