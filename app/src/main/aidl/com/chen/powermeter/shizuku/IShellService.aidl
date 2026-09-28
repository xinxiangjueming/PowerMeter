package com.chen.powermeter.shizuku;

/**
 * 运行在 Shizuku 进程（shell 身份）中的命令执行接口。
 * 口径对齐 fold src/main/aidl/com/example/fold/shizuku/IShellService.aidl。
 */
interface IShellService {
    /** 以 shell 身份执行一条命令；返回 stdout+stderr，非 0 退出码时以 "ERROR:<code>:<msg>" 前缀返回 */
    String exec(String command) = 1;

    // ── 直读方法（2026-09-29 加，采样循环去 fork）────────────────────────
    // UserService 进程本身是常驻 uid 2000，对 /proc、/sys 节点与 awk 是同一权限身份
    // （SELinux 上下文都是 shell）——这三类高频读数直接在进程内 java.io 读文件，
    // 省掉每次 exec 的 fork+exec（sh + awk 两个进程），稳态录制从 ~13.5 fork/s 降到 ~1.2。
    // ⚠️ 输出字符串与对应 awk 命令的输出**逐行同构**（FrameRateSource 的解析函数零改动）：
    // readCpuFastSample ↔ CPU_FAST_CMD、readGpuLoad ↔ GPU_LOAD_CMD、readThermalTemps ↔ VIRTUAL_TEMP_CMD。
    // ⚠️ 路径白名单写死在实现里、接口不收路径参数——不做成任意文件读工具。

    /** /proc/stat cpu 行 + 逐核 scaling_cur_freq，格式 = CPU_FAST_CMD 的 awk 输出 */
    String readCpuFastSample() = 2;

    /** GPU 占用率候选节点探测（busy/total 比值），格式 = GPU_LOAD_CMD 的 awk 输出；全不可读返回空串 */
    String readGpuLoad() = 3;

    /** thermal_zone 的 type/temp 配对行 "type temp"，格式 = VIRTUAL_TEMP_CMD 的 awk 输出 */
    String readThermalTemps() = 4;

    // ── 系统 TaskFpsCallback 桥（2026-09-29 加，v3）──────────────────────
    //
    // AOSP 的隐藏 AIDL `android.window.ITaskFpsCallback`（oneway void onFpsReported(in float fps)，
    // 单方法 → 事务码 = FIRST_CALL_TRANSACTION）+ `IWindowManager.registerTaskFpsCallback(taskId, cb)`：
    // 系统对指定 taskId **主动推送**实时 FPS（WMS 侧 TaskFpsCallbackController.nativeRegister 挂在
    // SF 帧事件上），零采样开销。调用门槛 = `ACCESS_FPS_COUNTER` 权限 —— **AOSP 的 Shell 包
    // manifest 自带它**（packages/Shell/AndroidManifest.xml，为 CtsTaskFpsCallbackTestCases 而加），
    // 本机（24031PN0DC / HyperOS V816）实测 com.android.shell granted=true，故 UserService
    // （uid 2000）可直接调。注册走反射 + 运行时 hidden API 豁免（见 ShellService.exemptHiddenApi），
    // 回调侧不引用框架隐藏类：手写 descriptor 相同的 Binder（见 ShellService.taskFpsCallbackBinder）。
    //
    // ⚠️ 只应经 Shizuku（shell 身份）使用；root（su）通道的主进程是普通应用 uid，无权注册。

    /**
     * 为前台包名 [packageName] 的任务注册系统 FPS 回调（切换目标时重复调用即重注册）。
     * 返回 "ok <taskId>"；解析不到目标任务时返回 "ERROR:-2:no-task"（调用方下一拍重试），
     * 注册失败返回 "ERROR:-1:<msg>"（调用方按「算法不可用」回落 timestats）。
     */
    String registerTaskFps(String packageName) = 5;

    /** 最近一次系统 FPS 推送，格式 "<fps> <atMillis>"；从未收到推送返回空串 */
    String readTaskFps() = 6;

    /** 注销系统 FPS 回调（UserService 进程死亡时 WMS 也会经 linkToDeath 自动清理） */
    void unregisterTaskFps() = 7;

    // Shizuku server 保留的 destroy 方法（transaction code 16777114）。
    // UserService 进程被 unbind 时不会自动退出，需在此清理并 System.exit()，
    // 否则会残留一个常驻的 shell 进程。缺了它会导致「Shizuku 重启后残留进程 + 端口占用」。
    void destroy() = 16777114;
}
