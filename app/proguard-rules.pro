# R8 规则 —— PowerMeter
#
# 本项目不使用反射、序列化（kotlinx.serialization / Gson / Moshi）、
# 注解处理器与 JNI；依赖为 AndroidX Core / Activity / Compose / graphics-shapes，
# 以及 haze、kyant backdrop（顶栏模糊）与 miuix-ui（曲线颜色色盘）三个 Compose 库；
# 各库自带的 consumer-rules.pro 会在构建时自动合并，因此无需额外 -keep 类。
# miuix-ui 的 ColorPalette 是纯 Compose 组合函数、不涉及运行时反射查找，同样无需 keep。
#
# 唯一的"反射式"调用是 CornerRadius.kt 中通过资源名字符串
# resources.getIdentifier("rounded_corner_radius_top", "dimen", "android")
# 查平台 dimen —— 它按字符串查系统资源，不涉及应用内类名，因此 R8 不影响其行为。
#
# CSV 导入走 CsvImporter 的纯字符串解析（无反射、无 Gson），R8 亦可安全压缩。

# 保留行号信息与源文件名映射，便于配合 mapping.txt 还原 Release 崩溃堆栈
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Manifest 中声明的组件（MainActivity / SamplingService）由 AAPT 自动生成 keep 规则，
# 此处显式声明仅为提高可读性，防止后续被误改
-keep class com.chen.powermeter.MainActivity { <init>(...); }
-keep class com.chen.powermeter.service.SamplingService { <init>(...); }

# ---- Shizuku ----
# Shizuku SDK 内部靠类名/反射访问（ShizukuProvider、Binder 接口等），必须整包保留
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
# 自研 Shizuku UserService：Shizuku 通过 UserServiceArgs 里的 ComponentName **反射加载**本类，
# R8 混淆会重命名类名 → bindUserService 永久失败 → 表现为「已授权但读不到数据」。
# ShellService 已加 @Keep，此处 keep 规则为双保险（与 fold 同口径）。
-keep class com.chen.powermeter.shizuku.** { *; }
