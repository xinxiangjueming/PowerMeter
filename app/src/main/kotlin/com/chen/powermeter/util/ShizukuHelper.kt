package com.chen.powermeter.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.chen.powermeter.shizuku.IShellService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

private const val TAG = "PowerMeterShizuku"

/**
 * Shizuku 集成（非 root 机器上以 shell 身份读 /sys 电量节点）。
 *
 * 架构与 fold util/ShizukuHelper.kt 同源，做了两处本应用专属的取舍：
 * 1. **不依赖 R.string**：本项目无 i18n 需求、文案硬编码在 Kotlin 中，故失败原因直接返回
 *    中文字面量，[appContext] 仅用于取 packageName 构造 ComponentName；
 * 2. 额外提供**同步**的 [execSync]，因为 [com.chen.powermeter.data.RootPowerReader] 的
 *    `read()` 是同步函数（在采样 IO 线程调用），需要给它一个"非 suspend"的取数入口。
 *
 * 生命周期：
 * - [init] 在 Application.onCreate 里调用一次（注册常驻监听器 + 首次探测）；
 * - [recheck] 在 Activity.onResume 里调用（用户可能刚从 Shizuku 里授权/断电重启了服务）。
 *
 * 三态（与 UI 一一对应）：
 * - [available] = Shizuku 本体在位（已安装且服务运行）
 * - [granted]   = 本应用已被授权
 * - [serviceBound] = UserService 已绑定，真正可以执行命令
 */
object ShizukuHelper {

    private const val REQUEST_CODE = 1001
    private const val MAX_BIND_ATTEMPTS = 3

    @Volatile
    private var appContext: Context? = null

    // ---- 状态 ----
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    private val _serviceBound = MutableStateFlow(false)
    val serviceBound: StateFlow<Boolean> = _serviceBound.asStateFlow()

    /** UserService 就绪回调（UI 可据此刷新列表/状态） */
    var onServiceReady: (() -> Unit)? = null

    private var shellService: IShellService? = null

    /**
     * UserService 的组件名。
     * - package 用运行时 [Context.getPackageName]（= applicationId，可能带 debug 后缀），
     *   Shizuku 据此定位已安装的 APK；
     * - class 用**编译期包名**（= namespace），因为 R8 保留的是这个类名，与 applicationId 无关。
     */
    private fun serviceComponent(): ComponentName = ComponentName(
        appContext?.packageName ?: "com.chen.powermeter",
        "com.chen.powermeter.shizuku.ShellService",
    )

    // ---- 绑定重试状态 ----
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bindMutex = Mutex()

    @Volatile
    private var binding = false
    @Volatile
    private var rebindAttempts = 0
    private var rebindJob: Job? = null

    // ---- ServiceConnection ----
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "ShellService connected")
            shellService = IShellService.Stub.asInterface(binder)
            _serviceBound.value = true
            rebindAttempts = 0
            onServiceReady?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ShellService disconnected")
            shellService = null
            _serviceBound.value = false
            // 服务崩溃/断开后自动重绑（前提：binder 仍存活且已授权）
            if (_available.value && _granted.value) bindServiceWithRetry()
        }
    }

    // ---- 初始化（Application.onCreate 调用） ----
    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "register listeners failed", e)
        }
        try {
            val alive = Shizuku.pingBinder()
            Log.d(TAG, "init: pingBinder=$alive")
            if (alive) {
                _available.value = true
                checkAndBind()
            }
        } catch (e: Exception) {
            // Shizuku 未安装时 pingBinder 可能抛异常，属正常情况，静默降级到 root 通道
            Log.d(TAG, "init: pingBinder failed (${e.message})")
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {
        }
    }

    // ---- Activity.onResume 时调用 ----
    fun recheck() {
        try {
            val alive = Shizuku.pingBinder()
            Log.d(
                TAG,
                "recheck: pingBinder=$alive, available=${_available.value}, " +
                    "granted=${_granted.value}, bound=${_serviceBound.value}",
            )
            if (alive) {
                _available.value = true
                checkAndBind()
                if (_granted.value && !_serviceBound.value) bindServiceWithRetry()
            } else {
                _available.value = false
                _granted.value = false
                shellService = null
                _serviceBound.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "recheck failed", e)
        }
    }

    private fun checkAndBind() {
        try {
            val result = Shizuku.checkSelfPermission()
            val isGranted = result == PackageManager.PERMISSION_GRANTED
            _granted.value = isGranted
            if (isGranted && !_serviceBound.value) bindServiceWithRetry()
        } catch (e: Exception) {
            Log.e(TAG, "checkSelfPermission failed", e)
            _granted.value = false
        }
    }

    /** 申请授权（需要宿主 Activity 处于前台；Shizuku 会弹授权框） */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "requestPermission: binder dead (Shizuku 未运行)")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _granted.value = true
                if (!_serviceBound.value) bindServiceWithRetry()
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    /** 强制重绑（UI「重试」按钮调用） */
    fun forceRebind() {
        rebindAttempts = 0
        bindServiceWithRetry()
    }

    // ---- 绑定 ShellService（带退避重试） ----
    private fun bindServiceWithRetry() {
        if (binding) return
        if (rebindJob?.isActive == true) return
        rebindJob = helperScope.launch {
            bindMutex.withLock {
                while (rebindAttempts < MAX_BIND_ATTEMPTS) {
                    if (!_available.value || !_granted.value || _serviceBound.value) {
                        rebindAttempts = 0
                        return@withLock
                    }
                    binding = true
                    try {
                        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
                        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                        Log.d(
                            TAG,
                            "bindUserService: attempt=${rebindAttempts + 1}/$MAX_BIND_ATTEMPTS, " +
                                "version=$version, uid=$uid（uid=2000 为 adb 模式，uid=0 为 root 模式）",
                        )
                        Shizuku.bindUserService(
                            Shizuku.UserServiceArgs(serviceComponent())
                                .daemon(false)
                                .processNameSuffix("shell")
                                .debuggable(true)
                                .version(1),
                            connection,
                        )
                        // 等待绑定结果（onServiceConnected 会置 _serviceBound=true），最长 ~2.5s
                        for (i in 1..50) {
                            if (_serviceBound.value) break
                            delay(50)
                        }
                        if (_serviceBound.value) {
                            rebindAttempts = 0
                            return@withLock
                        }
                        rebindAttempts++
                        delay(500L * (1 shl (rebindAttempts - 1))) // 500ms / 1s / 2s
                    } catch (e: Exception) {
                        Log.e(TAG, "bindUserService failed: ${e.message}", e)
                        rebindAttempts++
                        if (rebindAttempts >= MAX_BIND_ATTEMPTS) break
                        delay(500L * (1 shl (rebindAttempts - 1)))
                    } finally {
                        binding = false
                    }
                }
                rebindAttempts = 0
            }
        }
    }

    /**
     * 同步执行一条命令。**必须在后台（IO）线程调用** —— 内部是同步 Binder 调用。
     *
     * @return stdout（服务未绑定或命令以非 0 退出码结束时返回 null）
     */
    fun execSync(command: String): String? {
        val svc = shellService
        if (svc == null) {
            // 服务未绑定时主动触发重绑，下一次采样循环就可能成功
            if (_available.value && _granted.value) helperScope.launch { bindServiceWithRetry() }
            return null
        }
        return try {
            val output = svc.exec(command)
            if (output.startsWith("ERROR:")) {
                Log.w(TAG, "exec non-zero: ${output.take(200)}")
                null
            } else {
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "exec exception: ${e.message}")
            null
        }
    }

    // ---- 监听器 ----
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "binder received")
        _available.value = true
        checkAndBind()
        if (_granted.value && !_serviceBound.value) bindServiceWithRetry()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "binder dead（Shizuku 服务停止）")
        _available.value = false
        _granted.value = false
        shellService = null
        _serviceBound.value = false
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            val granted = result == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "permission result: code=$code, granted=$granted")
            _granted.value = granted
            if (granted && !_serviceBound.value) bindServiceWithRetry()
        }
}
