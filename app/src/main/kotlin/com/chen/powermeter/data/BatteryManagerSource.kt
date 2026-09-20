package com.chen.powermeter.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/**
 * 主进程内的**零进程创建**取数通道（档三，2026-09-21）。
 *
 * 动机：原先的 binder 兜底通道要 fork 一条 `sh -c` 跑
 * `cmd battery get -f current_now` + `dumpsys battery` + thermal awk，
 * 实测每个采样点约 4 次进程创建。在 SELinux 拦截 sysfs 的 ROM 上（如本机 HyperOS）
 * 这条路径是**每样本唯一**的取数路径，于是息屏时每秒都要唤醒 CPU 起进程 —— 这是息屏功耗的大头。
 *
 * 本通道全部走 SDK 公共 API，**不需要 root、不需要 Shizuku**：
 * 1. 电流：`BatteryManager.getLongProperty(BATTERY_PROPERTY_CURRENT_NOW)` —— 每次采样同步查一次
 *    health HAL（binder 调用，无进程创建），拿到的是**实时值**，密度不受广播频率限制；
 * 2. 电压 / 温度 / 电量 / 状态：`ACTION_BATTERY_CHANGED` **粘性广播**快照，注册即回投当前值，
 *    之后由系统在电量变化时推送（充电时约 10s 一次），本侧零轮询；
 * 3. 剩余容量：`BATTERY_PROPERTY_CHARGE_COUNTER`，随电流一起查（同为一次 binder 调用）。
 *
 * ⚠️ 电流符号：沿用 [RootPowerReader] 实测定案的口径 —— health HAL 透传的是**内核约定值**
 * （负 = 充电），与 sysfs current_now 同号，故此处同样取反。
 *
 * ⚠️ 拿不到的字段：OCV（无对应属性，用工作电压顶替，与旧 binder 通道一致）、
 *    charge_type、USB 输入电压/限流。接口温度、充电 IC 温度、PMIC 温度改由
 *    [RootPowerReader] 的**低频（30s）** thermal 刷新补充，不再每样本 fork。
 */
internal object BatteryManagerSource {

    /** getLongProperty 不支持该属性时的返回值 */
    private const val UNSUPPORTED = Long.MIN_VALUE

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var registered = false

    // ---- 粘性广播快照（变化缓慢的字段）----
    @Volatile
    private var voltageMv = 0

    @Volatile
    private var tempDeciC = 0

    @Volatile
    private var socPct = 0

    @Volatile
    private var statusInt = BatteryManager.BATTERY_STATUS_UNKNOWN

    @Volatile
    private var hasSnapshot = false

    /**
     * 是否已拿到第一条粘性广播。
     *
     * `registerReceiver(ACTION_BATTERY_CHANGED)` 是**粘性**广播，系统会在注册调用的返回前
     * 把当前值直接投递给 receiver，故 [ensureRegistered] 返回后这里即为 true。
     * 上层据此区分「通道尚未就绪」与「通道在本 ROM 上不可用」——后者才需要永久回退。
     */
    val ready: Boolean get() = hasSnapshot

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            tempDeciC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            socPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
            statusInt = i.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            )
            hasSnapshot = true
        }
    }

    /** 在 Application.onCreate 调用一次即可（幂等） */
    fun ensureRegistered(context: Context) {
        // 用局部 val 承接：appContext 是可变的 @Volatile 属性，直接用会丢失智能转换
        val ctx = context.applicationContext
        appContext = ctx
        if (registered) return
        runCatching {
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
    }

    /**
     * 读一次快照。
     *
     * @param thermal 低频刷新的温感区读数（键与 [RootPowerReader] 口径一致：battery / usb /
     *                charger_therm0 / pm8350c_tz / pm8350b_tz）
     * @return null = 本 ROM 上该通道不可用（电流属性不支持 / 快照缺失 / 电压非法），
     *         调用方应回退到命令通道
     */
    fun read(thermal: Map<String, Double>): PowerSample? {
        val ctx = appContext ?: return null
        if (!hasSnapshot) return null
        val voltageV = voltageMv / 1000.0
        if (voltageV <= 0.0) return null

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null

        // ⚠️ 必须在 IO 线程调用：内部是到 health HAL 的同步 binder 调用
        val currentUa = runCatching {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(UNSUPPORTED)
        if (currentUa == UNSUPPORTED) return null

        val counterUah = runCatching {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrDefault(UNSUPPORTED)

        val currentMa = -currentUa / 1000.0
        return PowerSample(
            timeMillis = System.currentTimeMillis(),
            voltageV = voltageV,
            // 无 OCV 属性：与旧 binder 通道同口径，用工作电压顶替
            voltageOcvV = voltageV,
            currentMa = currentMa,
            fgCurrentMa = null,
            powerW = voltageV * currentMa / 1000.0,
            tempBatteryC = if (tempDeciC > 0) tempDeciC / 10.0 else thermal["battery"] ?: 0.0,
            tempUsbC = thermal["usb"],
            tempChargerC = thermal["charger_therm0"],
            tempPmicC = thermal["pm8350c_tz"] ?: thermal["pm8350b_tz"],
            socPct = socPct,
            status = statusLabel(statusInt),
            chargeType = "", // 该通道没有内核 charge_type
            remainingMah = if (counterUah > 0) counterUah / 1000.0 else null,
            fullMah = null,
            usbVoltageV = null,
            usbCurrentLimitMa = null,
        )
    }

    /** BatteryManager 的 status 常量 → 与内核 b_status 同形的字符串 */
    private fun statusLabel(v: Int): String = when (v) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        else -> "Unknown"
    }
}
