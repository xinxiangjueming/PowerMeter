package com.chen.powermeter.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * 非 Compose 环境取字符串资源的统一入口。
 *
 * Compose 侧一律用 `stringResource(R.string.xxx)`。但有一批文案产自拿不到 Context 的地方：
 * - [com.chen.powermeter.data.RootPowerReader]（object 单例，错误文案在后台 IO 线程拼装）；
 * - [com.chen.powermeter.data.CsvImporter]（object 单例，解析异常在 IO 线程抛出）。
 *
 * 由 `PowerMeterApp.onCreate` 注入一份 applicationContext 供它们取串。
 *
 * ⚠️ 这里强制取 `context.applicationContext` —— 直接持有 Activity 会泄漏。
 */
object AppStrings {

    @Volatile
    private var context: Context? = null

    /** 在 `PowerMeterApp.onCreate` 调用一次（幂等） */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * 取一条字符串资源。
     *
     * @param args 格式化参数；为空时走无参重载，避免 `getString(id)` 被当成
     *             `getString(id, *emptyArray)` 时在某些 ROM 上解析出多余的格式化警告
     */
    fun get(@StringRes resId: Int, vararg args: Any?): String {
        val ctx = context ?: return ""
        return if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
    }
}

/** [AppStrings.get] 的顶层简写，便于单例里直接 `appString(R.string.xxx)` */
fun appString(@StringRes resId: Int, vararg args: Any?): String = AppStrings.get(resId, *args)
