# PowerMeter · 安卓底层充放电功率监测

> 直读手机电池的**瞬时功率、电压、电流与温度**，支持锁屏常驻采样、趋势曲线缩放叠加、充电涓流自动记录与 CSV 导出回看。
>
> 纯 Jetpack Compose + Material 3 Expressive，无第三方数据层，无网络权限，无数据上报。

---

## 目录

- [1. 这是什么](#1-这是什么)
- [2. 功能总览](#2-功能总览)
- [3. 取数通道](#3-取数通道)
- [4. 架构与模块地图](#4-架构与模块地图)
- [5. 息屏功耗优化](#5-息屏功耗优化)
- [6. 关键技术决策与踩坑记录](#6-关键技术决策与踩坑记录)
- [7. CSV 数据格式](#7-csv-数据格式)
- [8. 构建与运行](#8-构建与运行)
- [9. 依赖清单](#9-依赖清单)
- [10. 已知限制](#10-已知限制)

---

## 1. 这是什么

大多数「电池检测」应用只能读到 Android 框架层 `BatteryManager` 给的那几个粗糙字段，拿不到**瞬时功率**，也无法区分「充电档位上限」与「实际功率」。

PowerMeter 把功率当作**被测物理量**来做：以特权身份读取内核 `sysfs` 节点（`/sys/class/power_supply/battery`、高通私有节点 `/sys/class/qcom-battery`、温感区 `/sys/class/thermal`），换算成统一应用层单位后**自行计算功率**（`P = U × I`），并以 500ms 起的间隔持续采样、绘成趋势曲线；在此基础上做充电涓流段的自动判停与留存。

一句话定位：**给充电头、数据线、快充协议、电池健康做量化验证的仪器型工具。**

> 数据源为平台电量计（fuel gauge）。当前实现主要基于 **Xiaomi 22081212C / SM8475（taro）** 与 **Xiaomi 24031PN0DC / Android 16 / HyperOS V816** 两台真机标定；其它机型多数节点同名可用，但单位与语义存在差异，见 [3.4 三个实测陷阱](#34-三个实测陷阱)。

---

## 2. 功能总览

### 2.1 实时采样

| 能力 | 说明 |
| --- | --- |
| 前台服务 | `SamplingService`（`foregroundServiceType="specialUse"`），锁屏后继续采样 |
| 常驻通知 | 显示 `功率 · 电压 · 电流 · 电池温度`，标题为「充电中 / 放电中 · SOC」 |
| 通知节流 | 亮屏 5s / 息屏 10s 下限 + **读数显著变化**（功率 Δ≥0.5W 或 ≥10%，或 SOC / 充放电状态变化）+ 60s 保底强刷 |
| 采样间隔 | 0.5s / 1s / 2s / 5s 四档，持久化到 `SharedPreferences` |
| 息屏降频 | 息屏自动放宽到 5s、亮屏回用户设定值（取 `max`，不会给用户设定"提速"） |
| 锁屏保持 | 可选 `PARTIAL_WAKE_LOCK`。**默认关闭**；开启后息屏仍按设定间隔出点 |
| 缓冲上限 | 3600 条环形缓冲，只保留最近 3600 个样本 |
| 权限 | 首次启动采样时按需申请 `POST_NOTIFICATIONS`（Android 13+），拒绝则提示原因 |
| 错误反馈 | 取数通道不可用 / 节点读不到 / 解析失败三种情形均在页面顶部以错误卡明示，并附原始输出摘要 |

### 2.2 状态页（竖屏单列 / 横屏双列）

横屏以「趋势」卡为界分左右两列，控制按钮置于右列底部。

- **主功率卡**：56sp 等宽字体大号功率读数，充电用 `primary`、放电用 `tertiary` 着色；副行显示 `SOC · charge_type · 剩余 mAh`
- **指标网格**：电压 / 电流 / 电池温度 / 接口温度 / 开路电压 / 充电 IC 温度
- **趋势卡**：功率、电压、电流、温度四指标可切换；曲线色可自定义；可一键进入全屏
- **统计卡**：样本数、时长、平均功率、峰值充电/放电、电压区间、最高温度、累计充入/放出（mAh 与 Wh）
- **电池卡**：型号、技术、健康度 SOH、循环次数、满充容量、设计容量、最大充电档位
- **控制条**：开始 / 停止采样、导出 CSV
- **设置面板**：底部上滑 bottom sheet —— 采样间隔、锁屏保持采样、充电功率监测、串联双电池、数据读取权限、清空采样数据

**统计量口径**（2026-09 起）：峰值 / 电压区间 / 最高温度 / 平均功率 / 累计 mAh·Wh 均为**本次会话累计**，不随 3600 条缓冲滑出而丢失；累计量用梯形积分，且两条通道独立结算 —— `mAh ← currentMa` 积分、`Wh ← powerW` 积分。

### 2.3 趋势曲线

承载四项能力的组合，各调用点按需开启：

1. **多序列叠加**（全屏页）：各曲线按**自身在可见窗口内的量程**归一化到绘图区，实现不同量纲指标（W / V / mA / ℃）同屏对比
2. **曲线下方同色渐变填充**：锚定该序列自身量程上下界，标准面积图形态；**仅单曲线时绘制**（叠加时各条量程不同，填充高度无统一含义且会互相叠色），单↔多切换有 260ms 淡入淡出
3. **按住读数**：竖线 + 数据点 + 悬浮气泡，气泡含时间与真实数值
4. **双指缩放 + 单指平移 + 底部滑条**：仅全屏页启用；最多放大 50 倍

X 轴按**时间比例**映射（预计算归一化时间分数 `FloatArray`），而非按下标均分 —— 导入的历史 CSV 常出现采样间隔突变（如两次充电会话被拼进同一文件），按下标均分会把时间轴画歪。

**绘制抽稀**：可见点数超过绘图区像素宽的 2 倍时，按分桶 min/max 抽稀到「约 1 像素 2 点」再建 `Path`。抽稀**只作用于绘制** —— 几何量与读数索引仍按全量样本计算，否则按住气泡取到的值会与手指位置错位。

### 2.4 全屏趋势页

独立 `Activity`，进入即**强制横屏**（`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，允许 180° 翻转跟随重力）：

- **真沉浸**：隐藏状态栏与手势导航条，从屏幕边缘上滑可瞬时唤出；背景铺满全屏、内容延伸到系统栏之下
- **避让挖孔**：窗口声明 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`（否则默认模式会在挖孔侧留黑带），再交由 Compose 的 `WindowInsets.displayCutout` 全边避让 —— 竖屏顶部中央、横屏左右侧一次覆盖
- 指标**多选**叠加对比，胶囊上带色点标识对应曲线色；至少保留一条（不允许清空）
- 进入 / 返回走主题声明的四向水平滑动过渡（300ms）；**关闭时序有讲究**，见 [6.4](#64-趋势全屏页的关闭时序)

### 2.5 充电功率监测（默认关闭）

面向「测涓流截止点」的场景，把熄屏与自动留存串成一条链：

1. 点击「开始采样」后 **5 秒自动熄屏**（去掉屏幕自身那几瓦耗电，让电池端读数接近真实充电功率）
2. 充电功率**连续低于 1W 满 5 分钟**时，**自动导出一份 CSV** 到 `Download/PowerMeter/powermeter_charge_*.csv`，并发一条通知告知文件名与条数
3. **采样本身不中断** —— 自动保存只写文件，不触碰采样循环

三道护栏缺一不可（缺任一条都会在真实场景里静默误触发）：

| 护栏 | 作用 |
| --- | --- |
| **武装前提** | 本场会话至少出现过一次 > 1W 的功率才认为「正在充电」。没插充电器时功率恒为负值，不设这道判断会直接误触发 |
| **连续区间** | 功率一旦回升到阈值以上即复位，只认连续低功率，避免把若干段零散低功率累加成 5 分钟 |
| **幂等** | 命中一次后本场会话不再触发。涓流/已充满阶段功率会长期低于 1W，不拦就会每 5 分钟刷出一个新文件 |

熄屏走特权通道注入电源键（`input keyevent 26`）—— `PowerManager.goToSleep` 需要 signature 级 `DEVICE_POWER` 权限，`lockNow` 需要 DeviceAdmin 且要用户手动激活，二者都不可行。

### 2.6 串联双电池（默认关闭）

串联机型的电量计常上报**单节**电芯电压，整组为两节叠加。开关打开后，换算在**采样入口**统一完成：电压（工作电压与开路电压）与功率 ×2，**电流与容量不变**（串联电流处处相等），USB 输入侧电压不翻（Type-C 输入与电池组无关）。下游的曲线、统计、常驻通知、导出、自动保存与 1W 判定因此全部自动同口径。

### 2.7 CSV 导出与回看

- **导出**：写入系统 `MediaStore.Downloads`，落在 `Download/PowerMeter/powermeter_yyyyMMdd_HHmmss.csv`（Android 10+ 无需存储权限）。导出的是**「当前所看的那一份」** —— 查看导入文件时导出的是该文件数据，与界面所见一致
- **导入**：从系统「打开方式」或「分享」打开 CSV，以只读方式装载为「查看态」数据源；页面顶部出现醒目提示条标出来源与条数，附「退出查看」按钮
- **查看态与实时态互不干扰**：查看历史文件时实时采样照常进行，两者写入不同的 `StateFlow`，UI 统一按 `if (导入非空) 导入 else 实时` 取数。退出查看即自动回到实时曲线

---

## 3. 取数通道

全链路只有 `RootPowerReader` 一个数据源，内部按优先级选择通道。

### 3.1 优先级与身份

| 优先级 | 通道 | 身份 | 说明 |
| --- | --- | --- | --- |
| 1 | **sysfs 节点** | Shizuku（shell, uid 2000）或 root | 字段最全（含 OCV、charge_type、USB 输入、燃料计容量）。单进程 `awk` 一次读完 |
| 2 | **主进程 BatteryManager** | 本应用自身，**无需任何权限** | sysfs 不可读时的首选兜底。**零进程创建**、事件驱动 |
| 3 | **命令兜底** | Shizuku / root | `cmd battery get` + `dumpsys battery`。仅当 `getLongProperty` 在本 ROM 上不可用 |

**Shizuku 优先于 root**：`checkAccess()` 的判定顺序是「Shizuku 已绑定 → su 可用 → 都没有」。Shizuku 以 root 模式启动（uid 0）时等价于 root 通道。但**「电池卡片是否显示」用的是「这台机器有没有 root」这一独立事实**（单独做一次 `id -u` 探测），而不是「当前通道是不是 root」—— 否则 root 机器同时开着 Shizuku 时，通道会优先走 Shizuku，电池卡片会被连带藏掉。

判定结果缓存的边界：**成功缓存、失败不缓存**（Shizuku 的 UserService 绑定是异步的，冷启动首帧可能尚未连上，把「无权限」缓存下来就永远不会恢复）。

### 3.2 sysfs 节点清单

| 目录 | 读取字段 | 用途 |
| --- | --- | --- |
| `/sys/class/power_supply/battery` | `capacity` `status` `charge_type` `health` `technology` `model_name` `voltage_now` `voltage_ocv` `current_now` `temp` `charge_full` `charge_full_design` `charge_counter` `cycle_count` | 电压、电流、温度、SOC、状态、快充档位 |
| `/sys/class/qcom-battery` | `fg1_ai` `fg1_rm` `fg1_fcc` `fg1_soh` `fg1_cycle` `connector_temp` `power_max` `real_type` `usb_real_type` `typec_mode` | 燃料计寄存器（权威容量 / SOH / 循环数）、接口温度、快充协议 |
| `/sys/class/power_supply/usb` | `voltage_now` `current_now` `online` `type` | USB 输入侧电压与限流 |
| `/sys/class/thermal/thermal_zone*` | `temp`（按 `type` 匹配 `battery` / `usb` / `charger_therm0` / `pm8350c_tz` / `pm8350b_tz`） | 电池口 / Type-C / 充电 IC / PMIC 温度 |

**电池目录不硬编码**：部分机型没有 `/sys/class/power_supply/battery`（或该目录下无 `voltage_now`）而只有 `bms`，因此由 `detectBatteryDir()` 用 shell 内建 `[ -r ... ]` 在**当前身份**下实测选定 —— 该判定检查的正是执行命令的那个身份能否打开文件，结论与后续 `cat` 完全一致，不会出现「探测说能读、实际读不到」的错配。

**读取方式为单进程 `awk`**：本机 35 个节点，旧的「逐文件 `cat` + `tr`」写法要 fork 约 70 次、实测 **1.37s**；`awk` 单进程 **0.01s**（105 个温感区从 4.78s 降到 0.02s）。这是「点开始采样后要等好几秒才出第一个点」的直接成因，已修复。

### 3.3 单位换算与符号

| 原始字段 | 内核单位 | 应用层单位 | 换算 |
| --- | --- | --- | --- |
| `battery/voltage_now` | µV | V | `/ 1e6` |
| `battery/current_now` | µA | mA | `-x / 1000`（**取反**，见下） |
| `battery/temp` | 0.1 ℃ | ℃ | `/ 10` |
| `thermal_zone*/temp` | 毫摄氏度 | ℃ | `/ 1000` |
| `qcom-battery/fg1_rm` | µAh | mAh | `/ 1000` |
| `battery/charge_full` | µAh | mAh | `/ 1000` |
| `battery/charge_counter` | **本机为 mAh**（非标准 µAh） | mAh | 按 `charge_full_design` 量级自适应校准 |

**符号约定**：内核 `current_now` 为**负 = 充电**（实测 `status=Charging` 时为负值）；应用层统一为 **正 = 充电、负 = 放电**，故读取时取反。功率符号跟随电流。

> 框架层 `BATTERY_PROPERTY_CURRENT_NOW`（`cmd battery get`、`BatteryManager.getLongProperty`）是 HAL 原始值透传，**与 sysfs 同号**（同样负 = 充电），故两条通道共用同一取反口径。Android 文档称其「正 = 充电」，本机实测不成立；换 ROM 若发现「充电时功率为负」，说明该 ROM 按文档取号，届时适配。

**温度精度分级**：电池温度、接口温度、最高温度、常驻通知内电池温度、CSV 的 `temp_battery_c` / `temp_usb_c`、图表 TEMP 系列的 Y 轴刻度与读数气泡 = **1 位小数**；充电 IC / PMIC 温度与其余指标 = 3 位小数。

### 3.4 三个实测陷阱

这三条直接影响数据正确性，都以注释形式留证在 `RootPowerReader` 中：

1. **`battery/power_now` 与 `power_avg` 不可用**
   在本机为**恒定值**（`10000000` / `5000000`），是充电档位上限而非实测功率。
   → 功率必须由 `voltage_now × current_now` 自行计算。

2. **`battery/charge_counter` 单位是 mAh 而非标准 µAh**
   实测 `charge_counter = 4454`，而燃料计 `fg1_rm = 4457000` µAh（= 4457 mAh）。
   → 剩余容量优先取 `fg1_rm`；回退 `charge_counter` 时按设计容量做 1000 倍量级校准。

3. **SELinux 会拦截 shell 身份读 sysfs（决定性发现，2026-09 真机实测）**
   在 **Xiaomi 24031PN0DC / Android 16 / HyperOS V816（无 su）** 上，shell 身份读**任何**
   `power_supply` / `qcom-battery` 节点都被拒：`cat battery/voltage_now`、`ls battery/`、
   `ls qcom-battery/` 全部 `Permission denied`；`qcom-battery` 目录 mode 明明是 `drwxr-xr-x` 却仍被拒。
   → **根因是 SELinux（`u:r:shell:s0`）策略拦截，与文件权限无关**。
   → 结论：**Shizuku（adb 模式）走 sysfs 测功率在该 ROM 上不可能成功，与代码质量无关**。
   → 但 `/sys/class/thermal/thermal_zone*` 在该 ROM 上**可读**（温度仍能取）。
   → 这正是「通道 2」存在的原因。

### 3.5 各通道字段可用性

| 字段 | sysfs | BatteryManager | 命令兜底 |
| --- | :---: | :---: | :---: |
| 电压 | ✅ | ✅ 粘性广播 | ✅ |
| 电流 | ✅ | ✅ 每样本实时查询 | ✅ |
| 开路电压 OCV | ✅ | — 用工作电压顶替 | — 同左 |
| 电池温度 | ✅ | ✅ 粘性广播 | ✅ |
| 接口 / 充电 IC / PMIC 温度 | ✅ | ✅ 低频（30s）刷新 | ✅ 低频刷新 |
| SOC | ✅ | ✅ | ✅ |
| 充放电状态 | ✅ | ✅ | ✅ |
| 快充档位 `charge_type` | ✅ | — | — |
| 剩余容量 | ✅ 燃料计 | ✅ `CHARGE_COUNTER` | ✅ |
| 当前满充容量 | ✅ | — | — |
| USB 输入电压 / 限流 | ✅ | — | — |

**通道 2 / 3 下的界面差异**：`RootPowerReader.binderFallback` 为 true 时，「开路电压」顶替「接口温度」的位置，接口温度与充电 IC 温度两张卡片隐藏。

**电池静态信息（型号 / SOH / 循环次数 / 满充容量）仅 root 通道提供** —— 核心字段取自高通私有 `qcom-battery` 节点，shell 身份读不到。故非 root 机器**整张电池卡片不渲染，且不做任何提示、不加占位卡片**（渲染一张全是「—」的空壳卡片反而误导）。

---

## 4. 架构与模块地图

```
app/src/main/
├── aidl/com/chen/powermeter/shizuku/IShellService.aidl    15 行  UserService 的 AIDL 契约
└── kotlin/com/chen/powermeter/
    ├── PowerMeterApp.kt                      28 行  Application：Shizuku + BatteryManager 通道初始化
    ├── MainActivity.kt                      304 行  入口：权限、外部 Intent 分发、导出、配置变更重放
    ├── data/
    │   ├── PowerSample.kt                    70 行  采样快照 PowerSample / 会话统计 SessionStats
    │   ├── RootPowerReader.kt               796 行  三通道取数 + 单位换算 + 解析（唯一数据来源）
    │   ├── SampleStore.kt                   178 行  实时采样环形缓冲 + O(1) 增量统计
    │   ├── BatteryManagerSource.kt          158 行  主进程零 fork 通道（getLongProperty + 粘性广播）
    │   ├── SuSession.kt                     168 行  root 常驻 shell 会话（免每次 fork su）
    │   ├── BatteryInfoStore.kt               69 行  电池静态信息仓库（进程级单例）
    │   └── CsvImporter.kt                   263 行  CSV 解析 + ImportedSeries（导入态单例）
    ├── service/
    │   └── SamplingService.kt               629 行  前台服务：采样循环、通知节流、息屏降频、wakelock、充电监测
    ├── shizuku/
    │   └── ShellService.kt                   59 行  Shizuku UserService（shell 身份执行命令）
    ├── ui/
    │   ├── PowerMeterScreen.kt             1120 行  主页面（竖屏单列 / 横屏双列）、Metric 枚举、各卡片
    │   ├── TrendChartView.kt                899 行  图表组件：多序列 / 填充 / 读数 / 缩放 / 抽稀 / 滑条
    │   ├── TrendFullscreenActivity.kt       443 行  趋势全屏页（沉浸式 + 挖孔避让 + 关闭时序）
    │   ├── ColorPickerDialog.kt             301 行  ChartColors 仓库 + 颜色选择面板（miuix ColorPalette）
    │   ├── common/BlurTopBar.kt             115 行  顶栏三档模糊封装
    │   └── theme/
    │       ├── Theme.kt                      87 行  Material 3 Expressive 主题 + 底色压深
    │       └── CornerRadius.kt               37 行  LocalCornerRadius（小米读物理圆角，其余 28dp）
    └── util/
        ├── ShizukuHelper.kt                 300 行  Shizuku 三态 + UserService 绑定重试
        ├── EdgeToEdge.kt                    162 行  NavigationBarHelper：真沉浸 / 重放链
        ├── ScreenController.kt               76 行  特权通道注入电源键熄屏
        ├── CsvExporter.kt                    87 行  导出 CSV 到 MediaStore.Downloads
        └── Prefs.kt                          88 行  SharedPreferences 封装
```

Kotlin 共 **23 个文件、6437 行**；另有 1 个 AIDL 契约与 11 个资源文件。

### 数据流

```
                    ┌─ 通道1  sysfs awk（Shizuku / root）
采样循环 ──────────► ├─ 通道2  BatteryManager（主进程，零 fork）
（按间隔 tick）      └─ 通道3  cmd battery get + dumpsys（兜底）
                              │
                              ▼
                      RootPowerReader.read() ──► PowerSample
                              │
                              ▼
                    SampleStore（环形缓冲 3600 + 增量统计）   ImportedSeries（导入态，上限 20000）
                              └──────────┬──────────────────────────────┘
                                         ▼  UI: if (导入非空) 导入 else 实时
                              PowerMeterScreen / TrendFullscreenActivity
                                         ▼
                                  TrendChartView (Canvas)
```

两个数据源**并列且互不写入**，因此「查看历史文件」不会覆盖正在进行的实时采样，「清空采样数据」也不会把导入的数据一起清掉。

---

## 5. 息屏功耗优化

息屏功耗由三块构成：**取数的进程创建开销**、**常驻通知的跨进程刷新**、**CPU 是否被唤醒锁钉住**。三块在 2026-09 集中处理。

### 5.1 取数：进程创建 ≈5 次/样本 → 0 次

| 阶段 | 每个采样点的进程创建 |
| --- | --- |
| 改造前（本机 HyperOS，Shizuku + 命令兜底） | `[ -r ]` 探测 1 + `sh` 1 + `cmd battery get` 1 + `dumpsys battery` 1 + thermal `awk` 1 ≈ **5** |
| 改造后 | **0** |

三个来源各修一处：

1. **节点可读性探测被重复执行** —— 探测命令在 SELinux 拦截下是「**rc=0 但 stdout 为空**」（命令成功，结论=都不可读），而旧的判定把空输出当失败，于是缓存永不置位，**每个采样周期都重跑一次探测**。现区分「命令失败（不缓存）」与「命令成功但无结果（缓存）」，并记录探测时的通道身份，换身份才重探。
2. **命令通道改为主进程 BatteryManager** —— `BatteryManager` 是 SDK 公共 API，主进程可直接使用、**不需要 shell 身份**；真正需要 shell 的只有 `cmd battery get` / `dumpsys` 那层封装。电流每样本用 `getLongProperty(CURRENT_NOW)` 同步查询（实时值，密度不受广播频率限制），电压 / 温度 / SOC / 状态走 `ACTION_BATTERY_CHANGED` 粘性广播（注册即回投，之后由系统按需推送）。
3. **温感区温度改低频刷新** —— 接口 / 充电 IC / PMIC 温度变化极慢，从「每样本一条 awk」改为 **30s 一次**的缓存。

> root 机器另有一项：`SuSession` 常驻 root shell（命令写 stdin、stdout 按哨兵行切分），免掉每个采样点 fork `su` + `sh`。该项**未在真机验证**（开发机无 root），已做成多层兜底 —— 启动自检不通过即永久禁用、任何超时/异常立即销毁、空闲 60s 自动关闭，会话不可用时原样回落到改造前的一次性 fork 路径。

### 5.2 常驻通知：800ms 无差别刷新 → 分档 + 变化判定

| 维度 | 改造前 | 改造后 |
| --- | --- | --- |
| 时间下限 | 800ms，亮息屏同口径 | 亮屏 **5s** / 息屏 **10s** |
| 变化判定 | 无 | 功率 Δ≥0.5W 或 ≥10%，或 SOC / 充放电状态变化 |
| 保底 | 无 | 60s 无条件刷新一次，避免读数长期平稳时通知看起来像卡死 |

常驻通知的价值在**亮屏瞥一眼**；息屏时每秒重建 `Notification` 并跨进程 `notify` 纯亏。

### 5.3 息屏自适应降频与唤醒锁

- **采样间隔**：息屏放宽到 5s、亮屏回用户设定值，取 `max(user, 5s)` —— 用户自己设了更慢的间隔不会被"提速"。实现为运行时注册 `ACTION_SCREEN_ON/OFF`（Android 8 起禁止清单静态注册隐式广播），服务启动时按 `PowerManager.isInteractive` 取初值，不假定屏幕亮着。
- **唤醒锁**：默认**关闭**；每次获取带 **30 分钟超时**，由独立协程每 25 分钟续期（裸 `acquire()` 一旦漏掉 `release()` 就是永久泄漏）。
  - **充电功率监测开启时无条件不持锁**：该场景要测的是"电池真实在被充多少瓦"，而 CPU 不睡本身就是一笔负载，会直接抬高电池端读数、污染涓流段。这是**测量精度**问题，不只是耗电问题。
  - 「锁屏保持采样」开关是**唯一**决定息屏后是否持续唤醒 CPU 的地方 —— 关掉它，息屏段允许系统休眠，采样出现间隙（对曲线形态影响有限，且充电监测场景本就接受这一点）。

### 5.4 内存与重组开销

- 采样序列改为**环形缓冲 + 按需快照**：旧实现每个采样周期都做 `(list + sample).takeLast(3600)`，即每秒新建一个 3600 元素列表（息屏时照做）。现在服务侧只做 O(1) 追加，快照由真正需要的调用方索取。
- UI 侧订阅**版本号**而非列表本身，重装时才取一次快照 —— **息屏无帧即无重组，连快照都不会执行**。
- 统计量改为**每样本 O(1) 增量更新**，不再挂在重组上做 O(n) 全量重算。
- `LoadingIndicator` 只在「已启动、尚无首个样本」的真实等待期显示；采样全程挂着它等于让界面每帧都有动画驱动。

---

## 6. 关键技术决策与踩坑记录

这些是本项目在真实设备上验证过的结论，直接影响了代码结构，也都标在原文件注释里。

### 6.1 顶栏三档模糊（`ui/common/BlurTopBar.kt`）

按 API 分级降级，同一份顶栏内容三路复用：

| API | 方案 | 效果 |
| --- | --- | --- |
| ≥ 33 | `com.kyant.backdrop` + AGSL 着色器 `ProgressiveBlurAlphaMask` | 顶部全模糊 → 底部渐隐至透明的玻璃质感 |
| 31 – 32 | Haze（`RenderEffect` 可用区间） | 真实高斯模糊 |
| 26 – 30 | `surface` 色纵向渐变盖板 | 渐变假模糊（非真实模糊） |

**结构铁律**：采样源（`hazeSource` / `layerBackdrop`）挂在**内容层**，顶栏做**兄弟节点**覆盖。模糊节点一旦位于被采样的层内部就会自我引用 —— 这是 MIUI/HyperOS 上 `MiBackgroundBlurBlend` 崩溃的根因。

内容层的 `padding(top = topBarHeight)` 必须排在 `verticalScroll` **之后**，否则内容不会从顶栏下方穿过。

> 依赖处理：`io.github.kyant0:backdrop:2.0.1` 是 Compose Multiplatform 制品，在纯 androidx Compose 项目里必须 `exclude(group = "org.jetbrains.compose.foundation")` 与 `exclude(group = "org.jetbrains.compose.ui")`，否则出包时重复类冲突。2.0.1 的 `BackdropEffectScope` 也不再暴露 `downsampleScale`，着色器尺寸直接传实际像素尺寸。
>
> 反过来，`miuix-ui` 与 `haze` 里出现的 `org.jetbrains.compose.*` 坐标是**虚拟重定位模块**（Gradle 缓存中没有任何 aar/jar），不会产生重复类，**不需要 exclude**。

### 6.2 真沉浸（`util/EdgeToEdge.kt`）

「透明系统栏」与「真沉浸」是两件事。本项目的口径是：**背景铺满全屏、内容延伸到手势条之下，手势条浮在内容上**；而用 `safeDrawing` 做全边避让只会把系统栏区域换成一条背景色带 —— 观感上就是「小白条没沉浸」。

三个必须处理的点：

1. `enableEdgeToEdge()` 在最后一步会把 `isNavigationBarContrastEnforced` 置为 `true`（`SystemBarStyle.auto()` 且 nightMode 为 `MODE_NIGHT_AUTO` 时），浅色模式会盖一层不透明白色遮罩 → 必须在调用后显式置透明并关闭 contrast
2. `setDecorFitsSystemWindows(window, false)` **无条件**压回 —— 旋转后 MIUI/HyperOS 会把窗口重新按系统栏避让，重放链里没有人压回去就会退回「避让状态」
3. 状态栏与导航栏的明暗（`APPEARANCE_LIGHT_*`）**必须同时设置**，mask 也要同步包含导航栏那一位，否则清除时清不掉

重放链：`onConfigurationChanged` + `decorView.post` 一帧兜底 + decorView insets 监听（覆盖 Android 12+ 的 180° 翻转不回调 `onConfigurationChanged` 的情形）。注意**不在 insets 回调里重放 `enterImmersive()`** —— 用户上滑唤出的瞬时系统栏会触发 insets 回调，若在此重新 `hide`，刚唤出的手势条会被立刻收回（手势无反馈）。

**横向 insets 只避挖孔、不避导航栏**：内容区用 `WindowInsets.displayCutout.only(Horizontal)`。横屏时 `systemBars` 含侧边手势条，拿它做水平避让会把内容从手势条一侧额外顶开一整个导航栏宽度，那条区域只剩纯背景色 —— 观感上同样是「小白条没沉浸」。

### 6.3 `configChanges` 里有意不含 `uiMode`

`MainActivity` 与 `TrendFullscreenActivity` 均声明：

```
orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|fontScale
```

旋转**不重建** Activity，窗口层设置由重放链恢复。但**有意不含 `uiMode`** —— 深浅色切换需要重建 Activity 以重跑 `onCreate` 里的窗口设置。

> 曾试过去掉 `orientation|screenSize` 改走「旋转重建」，实测**「设备横握冷启动照样不复现修复」**，证明问题与旋转路径无关，已回滚。

**一次性 Intent 必须用 `savedInstanceState == null` 兜住**：CSV 的 `VIEW` / `SEND` Intent 是一次性的，但深浅色切换、字体缩放、进程被杀后恢复都会重建 Activity 并把 intent 原样带回 `onCreate`。不拦一道就会重新解析整份文件、再弹一次 Toast。配合 `launchMode="singleTop"` + `onNewIntent`，应用运行中再从外部打开 CSV 不会在栈顶叠出第二个实例。

### 6.4 趋势全屏页的关闭时序

从全屏页返回主页时，主页会**概率性闪一下**。这不是曲线绘制问题，闪的是**布局方向翻转**：

`TrendFullscreenActivity` 锁的是显示方向（`SENSOR_LANDSCAPE`），被它覆盖的 `MainActivity` 也拿到横屏 Configuration；而 `MainActivity` 的 `configChanges` 含 `orientation|screenSize`（不重建），整页结构由 `LocalConfiguration.orientation` 分支 ⇒ 返回瞬间主页先按**横屏两列**画出来，显示转回后再翻成竖屏单列。

关闭必须四步走，**不能简化成直接 `finish()`**：

1. 先退出沉浸（系统栏回来，否则主页 `topBarHeight` 会跳一次）
2. 解锁 `requestedOrientation = UNSPECIFIED` —— 让旋转发生在**内容已淡到主题底色**的纯色屏之下
3. 等方向落地（`onConfigurationChanged`，关闭中**不**重放沉浸；加 `postDelayed(250ms)` 兜底，覆盖设备本就横握、不产生配置变更的情形）
4. 才 `finish()`，交给主题声明的过渡动画

系统返回手势必须挂 `onBackPressedDispatcher.addCallback`，否则默认实现直接 finish，绕过整套时序。

**打开方向不需要这套处理**：本页是独立窗口，`setRequestedOrientation` 在启动窗口（StartingWindow）之下就生效，首帧即横屏。⇒ 这也是**否掉「单 Activity 覆盖层」重构**的原因：同 Activity 没有新窗口可遮，覆盖层首帧会按旋转前的方向画出来再重排（把坑从主页搬到全屏页）。

### 6.5 Shizuku 集成要点

- **UserService 由 Shizuku 反射加载**，不能、也不需要注册在 Manifest。R8 混淆会重命名类名 → `bindUserService` 永久失败（表现为「已授权但读不到数据」），故服务类加 `@Keep`，并在 `proguard-rules.pro` 保留 `rikka.shizuku.**` / `moe.shizuku.**` / `com.chen.powermeter.shizuku.**`
- Manifest 中 `ShizukuProvider` 的 permission **必须是** `INTERACT_ACROSS_USERS_FULL`（写 `API_V3` 会连不上）
- `ComponentName` 的 package 用**运行时** `packageName`、class 用**编译期包名**；改包名时必须同步，否则绑定永久失败
- 与同类项目（fold）的差异：本应用 `ShellService` **不做命令注入过滤** —— 全部命令都由内部常量拼接，且必须使用 `;` `|` `$()` 构造复合读取命令
- 绑定重试：`onServiceDisconnected` 后自动重绑，最多 3 次退避重试（500ms / 1s / 2s）；`MainActivity.onResume` 触发 `recheck()`，覆盖「用户刚在 Shizuku 里授权」的场景

### 6.6 CSV 导入：注册宽 + 入口严

Android 上 CSV 的 MIME 类型极不统一（`text/csv` / `text/comma-separated-values` / `application/csv` / `application/vnd.ms-excel` / `application/octet-stream` 都见过），因此采取「**注册得宽、入口校验得严**」策略。Manifest 中四段 `intent-filter` 各司其职，合并任意两段都会漏或误伤：

| # | 类型 | 作用 | 注意 |
| --- | --- | --- | --- |
| ① | `VIEW` + 五种 MIME | 常规命中路径 | — |
| ② | `VIEW` + `scheme`/`pathPattern` | 专收「无 type 但路径带 `.csv`」的 Intent | **不能声明 type**：同一 filter 内 type 与 scheme 是「与」关系 |
| ③ | `VIEW` + `content://` + 通配 MIME | 任何 content 文件都进候选 | 副作用已知并接受：图片/视频的「打开方式」里也会出现本应用，点进去被 `quickCheck` 拦下 |
| ④ | `ACTION_SEND` + 五种 MIME | 「分享」列表可选中本应用 | **不能声明 scheme**：分享时文件在 `EXTRA_STREAM`、data URI 通常为空 |

入口两层校验，**任一步不通过都只 Toast、不改动当前展示**：

1. `CsvImporter.quickCheck` —— 只看文件名与大小（上限 32MB），挡掉「一眼不是 CSV」，避免把视频/安装包整个读成字符串
2. `CsvImporter.read` / `parse` —— 按表头做权威校验（快筛不看内容，两层职责不重叠）

> 两个易踩的细节：
> - `pathPattern` 必须写成 `.*\\.csv`（**两个反斜杠**）—— aapt2 会对 manifest 属性值做一次 Android 字符串转义，编译进包的才是 `.*\.csv`；只写一个反斜杠会被吃掉，退化成 `.*.csv`（任意字符点）
> - 拿不到文件名时**放行**而不是拒绝 —— SAF 的 `content://` 末段常是文档 ID（如 `1234`），拿它当文件名会把正经 CSV 误杀

验证注册是否生效（需 `-d`，否则 scheme 为 null 会假阴性）：

```bash
adb shell cmd package query-activities --brief \
  -a android.intent.action.VIEW -c android.intent.category.DEFAULT \
  -d "content://x/y.csv" -t "text/csv"
```

### 6.7 图表手势：`pointerInput` 的 key 必须是 `Unit`

手势用 `awaitEachGesture` 自写：单指 = 读数、≥2 指 = 缩放/平移。

`pointerInput` 的 key 必须恒为 `Unit`，几何量通过 `rememberUpdatedState` 持有 —— 把几何量当 key 会让每一步缩放都重建协程，捏合手势当场断掉。

`TrendChartState` 用**窗口**（归一化时间 `[0, 1]`）而非缩放系数建模，让捏合、平移、滑条三种操作落到同一组状态上，避免反复做系数 ↔ 窗口换算，也更容易在 0 / 1 边界上算出越界窗口。

卡片内嵌与全屏两种模式的分工：`state != null` = 全屏（可缩放 + **单指直接**读数）；`state == null` = 卡片内嵌（不可缩放 + **长按**后拖动读数）—— 单指直接拖动会与页面竖直滚动抢手势。

`ChartBody` 必须写成 **`ColumnScope` 的扩展函数**：`Modifier.weight` 只在 `ColumnScope` 可用，跨 Composable 调用会断掉隐式接收者。

### 6.8 其它已验证事项

- **文字用 Compose 节点而非 Canvas 绘制**：本项目 Compose 版本下 `DrawScope.drawText` / `nativeCanvas` 已不存在，刻度文字、读数气泡一律走 Compose 文本节点
- **数字统一 `FontFamily.Monospace`**：MIUI/HyperOS 默认字体 MiSans 的等宽数字 tag 是自定义的 `thum`，标准 OpenType `tnum` 在小米设备上不生效
- **数值行整组右对齐**（`Arrangement.End`）：单位锚在卡片右缘，数值长度变化（`—` → `4.421`）时只向左侧扩展，单位不跳动
- **颜色胶囊文字色按 `Color.luminance()` 反算**：色值由用户自由指定，写死白色会在浅色底（黄、白）上完全看不见
- **`Prefs.getMetricColor` 返回 `null` 表示未自定义**：必须区分「未设置」与「设成黑色」，故先用 `contains` 判存在性，不能用 `getInt` 的默认值
- **水波纹规范**：`clip` 必须紧贴 `clickable` **之前**，ripple 以节点矩形为边界，否则圆形色块外会溢出方形水波纹
- **底部 sheet 自绘拖拽横条**：`dragHandle = null`，规避 M3 默认手柄长按弹出的「拖动手柄」tooltip；竖屏开板即全展开（`enabledValues` 去掉 `PartiallyExpanded`）
- **页面底色压深一档**（`Theme.withDeeperBackground`）：M3 baseline / dynamic scheme 中 `background` 与卡片填充色只差一个 tonal step（夜间 `#1C1B1F` vs `#211F26`），视觉上连成一片，卡片边界全靠阴影撑
- **主题过渡动画昼夜两份都要挂**：过渡动画可能取自打开方或被打开方的主题，只在 `values` 声明的话夜间会退回系统默认
- **Kotlin 注释里禁止出现 `*/` 与 `/*`**：前者（包括夹在反引号里的通配 MIME 字面量）会提前结束 KDoc，报出上百条 `Expecting member declaration`；后者会开启**嵌套块注释**（Kotlin 支持嵌套），令外层 KDoc 永不闭合，报 `<EOF> Unclosed comment` 并级联出整项目 `Unresolved reference`
- **取 MediaStore 导出文件名不能用 `uri.lastPathSegment`**：对 `content://` 记录它返回数字 ID 而非 `DISPLAY_NAME`，要 query `OpenableColumns.DISPLAY_NAME`

---

## 7. CSV 数据格式

### 导出（15 列，UTF-8，LF）

```csv
timestamp,datetime,voltage_v,voltage_ocv_v,current_ma,fg_current_ma,power_w,temp_battery_c,temp_usb_c,temp_charger_c,soc_pct,status,charge_type,remaining_mah,usb_voltage_v
1763692800123,2026-09-21 02:40:00.123,4.312,4.355,2841.500,2803.200,12.253,33.4,31.2,38.900,78,Charging,Fast,3455.000,5.102
```

| 列 | 单位 | 说明 |
| --- | --- | --- |
| `timestamp` | ms | 完整毫秒 epoch，导入时优先使用 |
| `datetime` | — | `yyyy-MM-dd HH:mm:ss.SSS`。**带毫秒是必须的**：采样间隔最小 500ms，秒级精度下相邻两行会重复，无法直接作为时间轴 |
| `voltage_v` | V | 电池端电压 |
| `voltage_ocv_v` | V | 开路电压（OCV） |
| `current_ma` | mA | **正 = 充电，负 = 放电** |
| `fg_current_ma` | mA | 燃料计独立测得的电流，用于交叉校验；无数据时留空 |
| `power_w` | W | `voltage_v × current_ma / 1000`，符号跟随电流 |
| `temp_battery_c` | ℃ | 电池温度（1 位小数） |
| `temp_usb_c` | ℃ | Type-C 接口温度（1 位小数） |
| `temp_charger_c` | ℃ | 充电 IC 温度（3 位小数） |
| `soc_pct` | % | 系统 SOC |
| `status` | — | `Charging` / `Discharging` / `Full` / `Not charging` |
| `charge_type` | — | `Fast` / `Trickle` / `None` |
| `remaining_mah` | mAh | 剩余容量（来自燃料计 `fg1_rm`，权威） |
| `usb_voltage_v` | V | USB 输入电压 |

### 导入的容错范围

文件常经过微信/网盘/Excel 转手，解析器按**列名**取值（**不按列序号**）并覆盖以下形态：

- UTF-8 BOM 头；CRLF / LF 混用
- 列顺序被调整、列被增删（按列名取值，故不会错位）
- 单元格留空 → 数值取 `null` 而非 `0`，避免把「无数据」画成「0 值曲线」
- 单元格被加双引号
- `power_w` 缺失或留空 → 由 `voltage × current / 1000` 现算，与实时路径口径一致
- 时间戳优先 `timestamp`（毫秒 epoch），回退 `datetime` 两种格式；两者都解析不出则该行计入丢弃数并在提示中告知
- 上限 **20000 行**（超出部分截断）、**32MB**（超出直接拒绝）

解析后统一按时间**升序**排序 —— 曲线 X 轴按时间比例映射，乱序文件会让折线回折。

---

## 8. 构建与运行

### 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17+（Gradle daemon 工具链由 `gradle/gradle-daemon-jvm.properties` 指定） |
| Gradle | 9.5.1（wrapper 自带） |
| Android SDK | compileSdk **37**，需安装对应 Platform 与 Build-Tools |
| AGP / Kotlin | 9.2.1 / 2.4.0 |
| minSdk / targetSdk | 30 / 36 |

### 配置 SDK 路径

`local.properties`（**不入库**，需自行创建）：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 构建命令

```bash
# 日常快速编译验证（推荐，约 40~60s）
./gradlew :app:compileReleaseKotlin --no-daemon --offline

# 完整构建
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

> 引入新依赖的那一轮需去掉 `--offline`，否则无法下载。

### 安装与首次运行

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

1. **取数通道**需至少具备其一：
   - **root**（Magisk / KernelSU 等，`su` 需可被 `ProcessBuilder` 调起）—— 字段最全，电池卡片只在此时显示
   - **Shizuku**（adb 或无线调试授权）—— 以 shell 身份读节点。⚠️ 部分 ROM（如 HyperOS）的 SELinux 会拦截 shell 读 sysfs，此时自动降级到主进程 `BatteryManager` 通道
2. 首次点「开始采样」时会申请通知权限（Android 13+），授予后前台服务启动
3. 通道不可用时页面顶部显示错误卡，说明具体原因（含通道身份与原始输出摘要，便于截图定位）
4. 要查看历史数据：把导出的 CSV 从文件管理器「用其它应用打开」或直接「分享」给本应用即可

### Release 构建注意事项

`buildTypes.release` 已开启 R8（`isMinifyEnabled` + `isShrinkResources`），规则见 `app/proguard-rules.pro`。本项目不使用反射、序列化与 JNI，因此**唯一的 `-keep` 需求来自 Shizuku** —— UserService 类由 Shizuku 反射加载，必须保留类名。

出包后建议归档 `app/build/outputs/mapping/release/mapping.txt`，用于还原 Release 崩溃堆栈。

---

## 9. 依赖清单

版本集中在 `gradle/libs.versions.toml`。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.android.application` (AGP) | 9.2.1 | 构建插件（AGP 9 起内置 Kotlin 支持，无需 `kotlin.android`） |
| `org.jetbrains.kotlin.plugin.compose` | 2.4.0 | Compose 编译器插件 |
| Compose BOM | 2026.09.00 | Compose 版本对齐 |
| `androidx.compose.material3:material3` | 1.5.0-alpha27 | **Expressive API 自 1.5.0-alpha19 起可用**，稳定版 1.4.0 不含 |
| `androidx.activity:activity-compose` | 1.13.0 | `enableEdgeToEdge` / Activity 结果 API |
| `androidx.core:core-ktx` | 1.18.0 | `ServiceCompat` / `NotificationCompat` / `ContextCompat.registerReceiver` |
| `androidx.graphics:graphics-shapes-android` | 1.0.1 | 形状工具 |
| `dev.chrisbanes.haze:haze` | 1.7.2 | API 31–32 顶栏真实模糊 |
| `io.github.kyant0:backdrop` | 2.0.1 | API ≥ 33 渐进式 AGSL 模糊（**需 exclude CMP 传递依赖**） |
| `top.yukonga.miuix.kmp:miuix-ui` | 0.9.2 | 曲线颜色色盘 `ColorPalette` |
| `dev.rikka.shizuku:api` / `provider` | 13.1.5 | 非 root 机器的 shell 身份取数通道 |

无网络权限、无数据上报、无第三方统计 SDK。

---

## 10. 已知限制

- **取数仍需一条特权通道（root 或 Shizuku）才能启动采样**。主进程 `BatteryManager` 兜底通道本身不需要任何权限，但当前它只在特权通道已建立、且 sysfs 不可读时才会被启用；纯无权限设备目前不取数
- **非 root 机器看不到电池卡片**（SOH / 循环次数 / 满充容量取自高通私有节点，shell 身份读不到）。这是有意为之，不做提示、不加占位卡片
- **机型差异**：主要在两台小米真机上标定。其它平台的节点名与单位可能不同，`charge_counter` 量级校准、温感区 `type` 名称、电流符号是最可能出问题的地方
- **数据不落库**。实时缓冲与导入数据均只存活于进程内（`StateFlow` 单例），进程被杀即丢失；需要留存请先导出 CSV
- **实时缓冲上限 3600 条**。按 1s 间隔约 1 小时，0.5s 间隔约 30 分钟，更早的数据被环形淘汰（会话统计量不受影响，见 [2.2](#22-状态页竖屏单列--横屏双列)）
- **多序列叠加时 Y 轴刻度无统一物理含义**，此时不再画数值刻度，改由图例标量程 + 按住气泡显示真实值 —— 这是量纲不同的必然取舍，不是缺陷
- **`temp_pmic_c`、`full_mah`、`usb_current_limit_ma` 未纳入 CSV**，导入后对应位置显示 `—`
- **`usb/current_now` 多为限流上限而非实测电流**，仅作参考
- **`SuSession`（root 常驻 shell）未真机验证**，逻辑上有多层兜底并会自动回落到一次性 fork 路径
- **无 i18n**。仅 `values/strings.xml`（应用名），UI 文案硬编码在 Kotlin 中

---

## 许可

本项目未声明开源许可证。代码仅供学习与个人使用参考；因依赖特权通道读取系统节点，请自行评估设备风险，由此产生的任何后果由使用者承担。
