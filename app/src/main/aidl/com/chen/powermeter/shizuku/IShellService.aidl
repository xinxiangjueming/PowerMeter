package com.chen.powermeter.shizuku;

/**
 * 运行在 Shizuku 进程（shell 身份）中的命令执行接口。
 * 口径对齐 fold src/main/aidl/com/example/fold/shizuku/IShellService.aidl。
 */
interface IShellService {
    /** 以 shell 身份执行一条命令；返回 stdout+stderr，非 0 退出码时以 "ERROR:<code>:<msg>" 前缀返回 */
    String exec(String command) = 1;

    // Shizuku server 保留的 destroy 方法（transaction code 16777114）。
    // UserService 进程被 unbind 时不会自动退出，需在此清理并 System.exit()，
    // 否则会残留一个常驻的 shell 进程。缺了它会导致「Shizuku 重启后残留进程 + 端口占用」。
    void destroy() = 16777114;
}
