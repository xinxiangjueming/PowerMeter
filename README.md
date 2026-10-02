# PowerMeter · 安卓功率 / 帧率双模式监测

> 一个应用、两种仪器：
> **功率监测**直读手机电池的瞬时功率、电压、电流与温度，支持锁屏常驻采样、趋势曲线缩放叠加、充电涓流自动记录与 CSV 导出回看；
> **帧率监测**用系统级悬浮窗测**任意应用**的实时帧率，一键录制后产出 Kite / PerfDog 口径的性能报告（帧时间、卡顿、CPU/GPU/DDR、功率温度全链）。
>
> 纯 Jetpack Compose + Material 3 Expressive，无第三方数据层，无网络权限，无数据上报。

**模式切换**：双击顶栏标题，在「功率监测」与「帧率监测」之间切换（从标题矩形撑开的 ClipReveal 转场，见 `ui/ModeTransition.kt`）；选择持久化，冷启动停在上次所在模式。

## 快速跳转

| | |
| --- | --- |
| **[功率监测](#功率监测)** | 电池瞬时功率 / 电压 / 电流 / 温度的仪器级采样：实时读数、趋势曲线与全屏页、充电涓流自动记录、串联双电池、CSV 导出回看、取数通道与踩坑实录 |
| **[帧率监测](#帧率监测)** | 任意应用帧率量化：系统悬浮窗常显帧率、四采样源录制、Kite 版式性能报告（统计网格 + 九张图表卡）、xlsx 分享 |

共通章节：[架构与模块地图](#架构与模块地图) ｜ [构建与运行](#构建与运行) ｜ [依赖清单](#依赖清单) ｜ [已知限制](#已知限制)

---

## 功率监测

### 1.1 这是什么

大多数「电池检测」应用只能读到 Android 框架层 `BatteryManager` 给的那几个粗糙字段，拿不到**瞬时功率**，也无法区分「充电档位上限」与「实际功率」。

PowerMeter 把功率当作**被测物理量**来做：以特权身份读取内核 `sysfs` 节点（`/sys/class/power_supply/battery`、高通私有节点 `/sys/class/qcom-battery`、温感区 `/sys/class/thermal`），换算成统一应用层单位后**自行计算功率**（`P = U × I`），并以 500ms 起的间隔持续采样、绘成趋势曲线；在此基础上做充电涓流段的自动判停与留存。

一句话定位：**给充电头、数据线、快充协议、电池健康做量化验证的仪器型工具。**

> 数据源为平台电量计（fuel gauge）。当前实现主要基于 **Xiaomi 22081212C / SM8475（taro）** 与 **Xiaomi 24031PN0DC / Android 16 / HyperOS V816** 两台真机标定；其它机型多数节点同名可用，但单位与语义存在差异，见 [三个实测陷阱](#三个实测陷阱)。

### 1.2 实时采样

| 能力 | 说明 |
| --- | --- |
| 前台服务 | `SamplingService`（`foregroundServiceType="specialUse"`），锁屏后继续采样 |
| 常驻通知 | 显示 `功率 · 电压 · 电流 · 电池温度`，标题为「充电中 / 放电中 · SOC」 |
| 通知节流 | 亮屏 5s / 息屏 10s 下限 + **读数显著变化**（功率 Δ≥0.5W 或 ≥10%，或 SOC / 充放电状态变化）+ 60s 保底强刷 |
| 采样间隔 | 0.5s / 1s / 2s / 5s 四档，持久化到 `SharedPreferences` |
| 息屏降频 | 息屏自动放宽到 5s、亮屏回用户设定值（取 `max`，不会给用户设定"提速"） |
| 锁屏保持 | 可选 `PARTIAL_WAKE_LOCK`。**默认关闭**；开启后息屏仍按设定间隔出点 |
| 数据落库 | **每 10s 增量写入 Room**（应用私有目录）。进程被杀最多丢 10 秒，不再受内存窗口限制 |
| 内存窗口 | 环形缓冲 **7200** 条，**只作用于界面曲线的显示窗口**（1s 间隔 ≈ 2 小时，见 [1.9](#19-采样数据落库与会话生命周期)） |
| 权限 | 首次启动采样时按需申请 `POST_NOTIFICATIONS`（Android 13+），拒绝则提示原因 |
| 错误反馈 | 取数通道不可用 / 节点读不到 / 解析失败三种情形均在页面顶部以错误卡明示，并附原始输出摘要 |

### 1.3 状态页（竖屏单列 / 横屏双列）

横屏以「趋势」卡为界分左右两列，控制按钮置于右列底部。

- **主功率卡**：56sp 等宽字体大号功率读数，充电用 `primary`、放电用 `tertiary` 着色；副行显示 `SOC · charge_type · 剩余 mAh`
- **指标网格**：电压 / 电流 / 电池温度 / 接口温度 / 开路电压 / 充电 IC 温度
- **趋势卡**：功率、电压、电流、温度、**PMIC 温度**五指标可切换（PMIC 温度**仅真 root 机器**出现，见 [各通道字段可用性](#各通道字段可用性)）；**tab 上带色点标识该指标的曲线色**（与全屏页同口径：8dp 圆点 + `rememberMetricColor`）；曲线色可自定义（入口是标题行右端的「颜色」胶囊，按钮色不跟随曲线色）；可一键进入全屏
- **统计卡**：样本数、时长、平均功率、峰值充电/放电、电压区间、最高温度、累计充入/放出（mAh 与 Wh）
- **电池卡**：型号、技术、健康度 SOH、循环次数、满充容量、设计容量、最大充电档位
- **控制条**：开始 / 停止采样、导出 CSV
- **设置面板**：底部上滑 bottom sheet —— 采样间隔、锁屏保持采样、充电功率监测、串联双电池、数据读取权限、清空采样数据

**统计量口径**（2026-09 起）：峰值 / 电压区间 / 最高温度 / 平均功率 / 累计 mAh·Wh 均为**本次会话累计**，不随内存窗口滑出而丢失；累计量用梯形积分，且两条通道独立结算 —— `mAh ← currentMa` 积分、`Wh ← powerW` 积分。

### 1.4 趋势曲线

承载四项能力的组合，各调用点按需开启：

1. **多序列叠加**（全屏页）：各曲线按**自身在可见窗口内的量程**归一化到绘图区，实现不同量纲指标（W / V / mA / ℃）同屏对比
2. **曲线下方同色渐变填充**：锚定该序列自身量程上下界，标准面积图形态；**仅单曲线时绘制**（叠加时各条量程不同，填充高度无统一含义且会互相叠色），单↔多切换有 260ms 淡入淡出
3. **按住读数**：竖线 + 数据点 + 悬浮气泡，气泡含时间与真实数值
4. **双指缩放 + 单指平移 + 底部滑条**：仅全屏页启用；最多放大 50 倍

X 轴按**时间比例**映射（预计算归一化时间分数 `FloatArray`），而非按下标均分 —— 导入的历史 CSV 常出现采样间隔突变（如两次充电会话被拼进同一文件），按下标均分会把时间轴画歪。

**绘制抽稀**：可见点数超过绘图区像素宽的 2 倍时，按分桶 min/max 抽稀到「约 1 像素 2 点」再建 `Path`。抽稀**只作用于绘制** —— 几何量与读数索引仍按全量样本计算，否则按住气泡取到的值会与手指位置错位。

**指标 tab 集合**：桌面趋势卡与全屏页共用 `rememberAvailableMetrics()` **一处口径** —— 功率 / 电压 / 电流 / 温度恒在，**PMIC 温度仅真 root 机器出现**。判据是 `RootPowerReader.rootAvailable`（su 探测结论），**不是** `accessMode`：root 机器若同时开着 Shizuku，通道会优先走 Shizuku，用「当前通道」判定会把这个 tab 误藏（与电池卡片同一考量）。任何 tab 行都不要另写一份过滤条件。

**断点语义**：某指标在某个采样点无读数时取 `NaN` 而非 `0` —— 曲线建 `Path` 时在该点**断开**，抽稀的分桶极值跳过无读数点，读数气泡与 Y 轴刻度显示「—」（与指标卡片的 `f3OrDash` 同口径）。绝不拿 `0` 兜底：那会被画成一条贴在 0℃ 的假曲线。触发场景是旧版 15 列 CSV（没有 `temp_pmic_c` 列）或本机缺该温感区。

### 1.5 全屏趋势页

独立 `Activity`，进入即**强制横屏**（`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，允许 180° 翻转跟随重力）：

- **真沉浸**：隐藏状态栏与手势导航条，从屏幕边缘上滑可瞬时唤出；背景铺满全屏、内容延伸到系统栏之下
- **避让挖孔**：窗口声明 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`（否则默认模式会在挖孔侧留黑带），再交由 Compose 的 `WindowInsets.displayCutout` 全边避让 —— 竖屏顶部中央、横屏左右侧一次覆盖
- 指标**多选**叠加对比，胶囊上带色点标识对应曲线色；至少保留一条（不允许清空）
- tab 集合与主页同源（`rememberAvailableMetrics()`，PMIC 温度仅真 root 机器可选）；选中项与可用集合求交，交集为空则回落功率 —— 兜住「`rememberSaveable` 里存着本机当前不可用的指标」
- 进入 / 返回走 **AppTransitions 一镜到底转场**（ClipReveal 裁剪展开/收拢，跨 Activity 进出窗口动画压 0）：进入从趋势卡矩形四向撑开 —— 横屏主页首帧直开，竖屏主页先垫页面底色遮罩、等旋转沉降且主页按新方向重排完成后**现拍**趋势卡横屏矩形再展开；返回按设备朝向分流（横握/平放 = 收拢回卡片当前位置，明确竖握 = 侧滑滑出）。机制与踩坑实录见 [趋势全屏页的一镜到底转场](#趋势全屏页的一镜到底转场)

### 1.6 充电功率监测（默认关闭）

面向「测涓流截止点」的场景，把熄屏与自动留存串成一条链：

1. 点击「开始采样」后 **5 秒自动熄屏**（去掉屏幕自身那几瓦耗电，让电池端读数接近真实充电功率）
2. **输入电池的电流为 0mA 且连续保持 30 秒**时，**自动导出一份 CSV** 到 `Download/PowerMeter/powermeter_charge_*.csv`，并发一条通知告知文件名与条数
3. **采样本身不中断** —— 自动保存只写文件，不触碰采样循环

判据取 `current_ma`（见 [1.13](#113-csv-数据格式) 的列定义）而不是功率：涓流截止、已充满、充电器断开，输入电流都会干脆地塌到 0，而此时电压仍在、功率读数只在零点附近飘。判定取绝对值比较 —— 本机充电时 `current_ma` 上报为负值（shizuku / binder 两通道同口径，不翻符号）。

三道护栏缺一不可（缺任一条都会在真实场景里静默误触发）：

| 护栏 | 作用 |
| --- | --- |
| **武装前提** | 本场会话至少出现过一次 ≥ 50mA 的输入电流才认为「确实在充电」。这条同时兜住 binder 通道 `CURRENT_NOW` 恒返回 0 的机型 —— 不设就会在「根本没充上电」时直接误触发 |
| **连续区间** | 电流一旦回到 1mA 以上即复位，只认**连续**归零，避免把若干段零散的零电流累加成 30 秒 |
| **幂等** | 命中一次后本场会话不再触发。停充以后电流长期为 0，不拦就会每 30 秒刷出一个新文件 |

熄屏走特权通道注入电源键（`input keyevent 26`）—— `PowerManager.goToSleep` 需要 signature 级 `DEVICE_POWER` 权限，`lockNow` 需要 DeviceAdmin 且要用户手动激活，二者都不可行。

### 1.7 串联双电池（默认关闭）

串联机型的电量计常上报**单节**电芯电压，整组为两节叠加。开关打开后，换算在**采样入口**统一完成：电压（工作电压与开路电压）与功率 ×2，**电流与容量不变**（串联电流处处相等），USB 输入侧电压不翻（Type-C 输入与电池组无关）。下游的曲线、统计、常驻通知、导出、自动保存因此全部自动同口径（充电功率监测的「电流归零」判定不受本换算影响 —— 串联回路电流处处相等，`current_ma` 不翻）。

**帧率录制同样吃这个开关**：帧率侧每秒电量四项走同一条取数链，换算因子 `SERIES_DUAL_FACTOR` 单源共用 —— 开着开关录帧率，详情页与 xlsx 导出的电压 / 功率也是整组口径，与功率页读数一致；开关每秒现读、中途切换次拍生效。

### 1.8 CSV 导出与回看

- **导出**：写入系统 `MediaStore.Downloads`，落在 `Download/PowerMeter/powermeter_yyyyMMdd_HHmmss.csv`（Android 10+ 无需存储权限）。导出的是**「当前所看的那一份」** —— 查看导入文件时导出的是该文件数据，与界面所见一致
- **导入**：从系统「打开方式」或「分享」打开 CSV，以只读方式装载为「查看态」数据源；页面顶部出现醒目提示条标出来源与条数，附「退出查看」按钮
- **查看态与实时态互不干扰**：查看历史文件时实时采样照常进行，两者写入不同的 `StateFlow`，UI 统一按 `if (导入非空) 导入 else 实时` 取数。退出查看即自动回到实时曲线

### 1.9 采样数据落库与会话生命周期

2026-09 起，采样数据不再只活在内存里。**每 10 秒**把这一批样本增量写入 Room（`/data/data/<pkg>/databases/powermeter.db`，私有目录，不需要任何存储权限），因此：

- **进程被杀最多丢 10 秒**（上次刷盘到被杀之间的样本），而不是整场数据；
- **导出不再受内存窗口限制** —— 导的是库里的会话全量，1s 间隔跑一整天也能完整导出；
- 内存环形缓冲**退化为纯显示窗口**（曲线画最近 7200 点），不再承担"数据唯一副本"的角色。

落库会话 (session) 不是用户资产，而是**自动保存的临时存档**，生命周期规则如下：

| 阶段 | 行为 |
| --- | --- |
| 采样中 | 每 10s flush 一批；`(sessionId, timeMillis)` 唯一索引 + REPLACE 让重复 flush 幂等 |
| 停止采样 | 收尾刷盘、定格结束时间，**会话保留在库里等用户导出** |
| 点「导出 CSV」 | 从库里分页读全量 → 写 CSV 到 `Download/PowerMeter/` → **删除该会话**（外键级联删样本） |
| 停止后没点导出 | **保留为「最近一次未导出会话」**，进程被杀也不丢 |
| 开始新一场采样 | 开新会话前先删掉上一场（同一条规则的另一面：用户不要了） |
| 冷启动 | 只保留最新一条，更早的删掉 |

**为什么冷启动不干脆全删**：HyperOS 上「停止采样 → 切走 → 进程被回收」发生得很快，用户常常还没来得及导出，全删等于把刚测完的一整场直接丢掉。保留最新一条让「打开应用 → 点导出」这条路始终可用；而这条存档会在用户导出或开始新一场采样时清掉，不会长期堆积。

⇒ **稳态下库里最多只有一行**，即最近一次未导出的那一场。

两个补充规则：

- **采样仍在运行时点导出**：导出并删除后**立刻开一个新会话**，后续样本写进新会话。不这么做的话，样本会继续带着已删除的 `sessionId` 写入，直接撞外键约束。
- **「充电功率监测」自动保存的场景**：输入电流归零满 30 秒时从库里导出（`deleteAfter = false` —— 采样没停，会话必须留着继续累积），并打上 `autoSaved` 标记；停止采样时该会话直接删除，因为用户手里已经有 CSV 了。

清空数据（「清空采样数据」按钮）同样走这条链：删会话 + 若采样仍在运行则立刻开一个新的空会话。

### 1.10 取数通道

全链路只有 `RootPowerReader` 一个数据源，内部按优先级选择通道。

#### 优先级与身份

| 优先级 | 通道 | 身份 | 说明 |
| --- | --- | --- | --- |
| 1 | **sysfs 节点** | Shizuku（shell, uid 2000）或 root | 字段最全（含 OCV、charge_type、USB 输入、燃料计容量）。单进程 `awk` 一次读完 |
| 2 | **主进程 BatteryManager** | 本应用自身，**无需任何权限** | sysfs 不可读时的首选兜底。**零进程创建**、事件驱动 |
| 3 | **命令兜底** | Shizuku / root | `cmd battery get` + `dumpsys battery`。仅当 `getLongProperty` 在本 ROM 上不可用 |

**Shizuku 优先于 root**：`checkAccess()` 的判定顺序是「Shizuku 已绑定 → su 可用 → 都没有」。Shizuku 以 root 模式启动（uid 0）时等价于 root 通道。但**「电池卡片是否显示」用的是「这台机器有没有 root」这一独立事实**（单独做一次 `id -u` 探测），而不是「当前通道是不是 root」—— 否则 root 机器同时开着 Shizuku 时，通道会优先走 Shizuku，电池卡片会被连带藏掉。

判定结果缓存的边界：**成功缓存、失败不缓存**（Shizuku 的 UserService 绑定是异步的，冷启动首帧可能尚未连上，把「无权限」缓存下来就永远不会恢复）。

#### sysfs 节点清单

| 目录 | 读取字段 | 用途 |
| --- | --- | --- |
| `/sys/class/power_supply/battery` | `capacity` `status` `charge_type` `health` `technology` `model_name` `voltage_now` `voltage_ocv` `current_now` `temp` `charge_full` `charge_full_design` `charge_counter` `cycle_count` | 电压、电流、温度、SOC、状态、快充档位 |
| `/sys/class/qcom-battery` | `fg1_ai` `fg1_rm` `fg1_fcc` `fg1_soh` `fg1_cycle` `connector_temp` `power_max` `real_type` `usb_real_type` `typec_mode` | 燃料计寄存器（权威容量 / SOH / 循环数）、接口温度、快充协议 |
| `/sys/class/power_supply/usb` | `voltage_now` `current_now` `online` `type` | USB 输入侧电压与限流 |
| `/sys/class/thermal/thermal_zone*` | `temp`（按 `type` 匹配 `battery` / `usb` / `charger_therm0` / `pm8350c_tz` / `pm8350b_tz`） | 电池口 / Type-C / 充电 IC / PMIC 温度 |

**电池目录不硬编码**：部分机型没有 `/sys/class/power_supply/battery`（或该目录下无 `voltage_now`）而只有 `bms`，因此由 `detectBatteryDir()` 用 shell 内建 `[ -r ... ]` 在**当前身份**下实测选定 —— 该判定检查的正是执行命令的那个身份能否打开文件，结论与后续 `cat` 完全一致，不会出现「探测说能读、实际读不到」的错配。

**读取方式为单进程 `awk`**：本机 35 个节点，旧的「逐文件 `cat` + `tr`」写法要 fork 约 70 次、实测 **1.37s**；`awk` 单进程 **0.01s**（105 个温感区从 4.78s 降到 0.02s）。这是「点开始采样后要等好几秒才出第一个点」的直接成因，已修复。

#### 单位换算与符号

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

**数值精度分级**（2026-09-21 定案，显示与 CSV 记录同口径）：
- **整数**：电流 mA（内核只上报 mA 整数，含燃料计电流）、接口温度、容量类（剩余 / 满充 / 设计 mAh）
- **1 位小数**：电池温度、最高温度、常驻通知内电池温度、CSV 的 `temp_battery_c`、图表 TEMP 系列（Y 轴刻度 / 图例 / 读数气泡）
- **3 位小数**：电压 / OCV / 功率 / Wh、充电 IC / PMIC 温度与其余指标
- 图表按指标走 `TrendChartView.fMetric(metric)`，Y 轴刻度、图例量程、读数气泡共用同一函数

#### 三个实测陷阱

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

#### 各通道字段可用性

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

**PMIC 温度同样只在真 root 机器上露面**：芯片温度（温感区 `pm8350c_tz` / `pm8350b_tz`）由图表的 **PMIC 温度 tab** 呈现，该 tab **仅在真 root 机器列出**，Shizuku(shell) 机器不出现（与电池卡片同一判据 `RootPowerReader.rootAvailable`，不做提示、不加占位）。tab 本身的取数与换算不受影响：节点每样本从温感区读取，binder 通道下走 30s 低频缓存。

### 1.11 息屏功耗优化

息屏功耗由三块构成：**取数的进程创建开销**、**常驻通知的跨进程刷新**、**CPU 是否被唤醒锁钉住**。三块在 2026-09 集中处理。

#### 取数：进程创建 ≈5 次/样本 → 0 次

| 阶段 | 每个采样点的进程创建 |
| --- | --- |
| 改造前（本机 HyperOS，Shizuku + 命令兜底） | `[ -r ]` 探测 1 + `sh` 1 + `cmd battery get` 1 + `dumpsys battery` 1 + thermal `awk` 1 ≈ **5** |
| 改造后 | **0** |

三个来源各修一处：

1. **节点可读性探测被重复执行** —— 探测命令在 SELinux 拦截下是「**rc=0 但 stdout 为空**」（命令成功，结论=都不可读），而旧的判定把空输出当失败，于是缓存永不置位，**每个采样周期都重跑一次探测**。现区分「命令失败（不缓存）」与「命令成功但无结果（缓存）」，并记录探测时的通道身份，换身份才重探。
2. **命令通道改为主进程 BatteryManager** —— `BatteryManager` 是 SDK 公共 API，主进程可直接使用、**不需要 shell 身份**；真正需要 shell 的只有 `cmd battery get` / `dumpsys` 那层封装。电流每样本用 `getLongProperty(CURRENT_NOW)` 同步查询（实时值，密度不受广播频率限制），电压 / 温度 / SOC / 状态走 `ACTION_BATTERY_CHANGED` 粘性广播（注册即回投，之后由系统按需推送）。
3. **温感区温度改低频刷新** —— 接口 / 充电 IC / PMIC 温度变化极慢，从「每样本一条 awk」改为 **30s 一次**的缓存。

> root 机器另有一项：`SuSession` 常驻 root shell（命令写 stdin、stdout 按哨兵行切分），免掉每个采样点 fork `su` + `sh`。该项**未在真机验证**（开发机无 root），已做成多层兜底 —— 启动自检不通过即永久禁用、任何超时/异常立即销毁、空闲 60s 自动关闭，会话不可用时原样回落到改造前的一次性 fork 路径。

#### 常驻通知：800ms 无差别刷新 → 分档 + 变化判定

| 维度 | 改造前 | 改造后 |
| --- | --- | --- |
| 时间下限 | 800ms，亮息屏同口径 | 亮屏 **5s** / 息屏 **10s** |
| 变化判定 | 无 | 功率 Δ≥0.5W 或 ≥10%，或 SOC / 充放电状态变化 |
| 保底 | 无 | 60s 无条件刷新一次，避免读数长期平稳时通知看起来像卡死 |

常驻通知的价值在**亮屏瞥一眼**；息屏时每秒重建 `Notification` 并跨进程 `notify` 纯亏。

#### 息屏自适应降频与唤醒锁

- **采样间隔**：息屏放宽到 5s、亮屏回用户设定值，取 `max(user, 5s)` —— 用户自己设了更慢的间隔不会被"提速"。实现为运行时注册 `ACTION_SCREEN_ON/OFF`（Android 8 起禁止清单静态注册隐式广播），服务启动时按 `PowerManager.isInteractive` 取初值，不假定屏幕亮着。
- **唤醒锁**：默认**关闭**；每次获取带 **30 分钟超时**，由独立协程每 25 分钟续期（裸 `acquire()` 一旦漏掉 `release()` 就是永久泄漏）。
  - **充电功率监测开启时无条件不持锁**：该场景要测的是"电池真实在被充多少瓦"，而 CPU 不睡本身就是一笔负载，会直接抬高电池端读数、污染涓流段。这是**测量精度**问题，不只是耗电问题。
  - 「锁屏保持采样」开关是**唯一**决定息屏后是否持续唤醒 CPU 的地方 —— 关掉它，息屏段允许系统休眠，采样出现间隙（对曲线形态影响有限，且充电监测场景本就接受这一点）。

#### 内存与重组开销

- 采样序列改为**环形缓冲 + 按需快照**：旧实现每个采样周期都做 `(list + sample).takeLast(MAX_SAMPLES)`，即每秒新建一个等长列表（息屏时照做）。现在服务侧只做 O(1) 追加，快照由真正需要的调用方索取。
- UI 侧订阅**版本号**而非列表本身，重装时才取一次快照 —— **息屏无帧即无重组，连快照都不会执行**。
- 统计量改为**每样本 O(1) 增量更新**，不再挂在重组上做 O(n) 全量重算。
- `LoadingIndicator` 只在「已启动、尚无首个样本」的真实等待期显示；采样全程挂着它等于让界面每帧都有动画驱动。

#### 落库：绝不在采样点里写盘（2026-09）

每 10s 一次批量 insert 对功耗几乎无影响（WAL + 事务合并），但**写法**有硬性要求：

- **采样点只做一次 `trySend`**（Channel UNLIMITED），全部 DB 写入由 `SessionRecorder` 里唯一的消费协程在 IO 线程完成。采样循环跑在 `Dispatchers.Default`，任何同步 IO 都会把 0.5s 档拖成抖动 —— 抖动直接反映在曲线的时间轴上。
- **定时 flush 而不是"凑够 N 条就写"**：条数阈值在用户把间隔调到 5s 时会退化成几分钟才落一次盘，丢失窗口不可控；固定 10s 让"最多丢多少"有确定上界。
- **batch insert 用一次事务**：Room 对 List 参数的 `@Insert` 本身就是单事务，100 条样本一次 `insertSamples` 而不是 100 次。

### 1.12 关键技术决策与踩坑记录

这些是本项目在真实设备上验证过的结论，直接影响了代码结构，也都标在原文件注释里。

#### 顶栏三档模糊（`ui/common/BlurTopBar.kt`）

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

#### 真沉浸（`util/EdgeToEdge.kt`）

「透明系统栏」与「真沉浸」是两件事。本项目的口径是：**背景铺满全屏、内容延伸到手势条之下，手势条浮在内容上**；而用 `safeDrawing` 做全边避让只会把系统栏区域换成一条背景色带 —— 观感上就是「小白条没沉浸」。

三个必须处理的点：

1. `enableEdgeToEdge()` 在最后一步会把 `isNavigationBarContrastEnforced` 置为 `true`（`SystemBarStyle.auto()` 且 nightMode 为 `MODE_NIGHT_AUTO` 时），浅色模式会盖一层不透明白色遮罩 → 必须在调用后显式置透明并关闭 contrast
2. `setDecorFitsSystemWindows(window, false)` **无条件**压回 —— 旋转后 MIUI/HyperOS 会把窗口重新按系统栏避让，重放链里没有人压回去就会退回「避让状态」
3. 状态栏与导航栏的明暗（`APPEARANCE_LIGHT_*`）**必须同时设置**，mask 也要同步包含导航栏那一位，否则清除时清不掉

重放链：`onConfigurationChanged` + `decorView.post` 一帧兜底 + decorView insets 监听（覆盖 Android 12+ 的 180° 翻转不回调 `onConfigurationChanged` 的情形）。注意**不在 insets 回调里重放 `enterImmersive()`** —— 用户上滑唤出的瞬时系统栏会触发 insets 回调，若在此重新 `hide`，刚唤出的手势条会被立刻收回（手势无反馈）。

**横向 insets 只避挖孔、不避导航栏**：内容区用 `WindowInsets.displayCutout.only(Horizontal)`。横屏时 `systemBars` 含侧边手势条，拿它做水平避让会把内容从手势条一侧额外顶开一整个导航栏宽度，那条区域只剩纯背景色 —— 观感上同样是「小白条没沉浸」。

#### `configChanges` 里有意不含 `uiMode`

`MainActivity` 与 `TrendFullscreenActivity` 均声明：

```
orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|fontScale
```

旋转**不重建** Activity，窗口层设置由重放链恢复。但**有意不含 `uiMode`** —— 深浅色切换需要重建 Activity 以重跑 `onCreate` 里的窗口设置。

> 曾试过去掉 `orientation|screenSize` 改走「旋转重建」，实测**「设备横握冷启动照样不复现修复」**，证明问题与旋转路径无关，已回滚。

**一次性 Intent 必须用 `savedInstanceState == null` 兜住**：CSV 的 `VIEW` / `SEND` Intent 是一次性的，但深浅色切换、字体缩放、进程被杀后恢复都会重建 Activity 并把 intent 原样带回 `onCreate`。不拦一道就会重新解析整份文件、再弹一次 Toast。配合 `launchMode="singleTop"` + `onNewIntent`，应用运行中再从外部打开 CSV 不会在栈顶叠出第二个实例。

#### 趋势全屏页的一镜到底转场

跨 Activity 的容器变换转场（`util/AppTransitions.kt`，2026-09-30 起对齐 SportLink 图表卡），核心链路：

**源端**：卡片挂 `Modifier.containerSource`（窗口矩形采集）+ `rememberContainerTextSource`（内部内容矩形），点击 `AppTransitions.register` → `capture`（decorView `View.draw` 整窗截图后裁出卡片 + 文字层 + 表面色采样）→ `launchWithTransform`（Handoff 单例 + 压制窗口滑动动画）。

**进场**（目标页 `onPostCreate` 调 `installWindowTransform`）：页面根包进 `ClipRevealLayout`，从源卡片矩形四向撑开（580ms FastOutSlowIn，截图 0→0.22 淡出 / 内容 0.48→0.88 淡入）。

- **横屏主页（同方向）**：首帧 PreDraw 直接撑开 —— 横屏源窗口即终态尺寸，无旋转叠层
- **竖屏主页（跨方向，目标锁横屏）**：先垫不透明页面底色遮罩 + 整窗零尺寸裁剪，逐帧轮询「旋转沉降 ≥380ms 且主页按新旋转重排完成（活性源矩形落窗内且偏离 Handoff 矩形）」后**现拍**趋势卡的新鲜截图（真实横屏矩形 + 当前主题像素）再展开；超时（时间预算 2.5s）退化全宽中心线。展开完成/开始收拢时拆遮罩

**返回**（`finish()` 按设备物理朝向分流）：

- **横握/平放（显示停留横屏）**：还系统栏等 300ms（半透明窗口下主页被连带排成无栏全高，收拢前现取的锚点必须是还栏后位置 ——「还栏防列表跳动」）→ 收拢前向活性源条目**现取**当前矩形/新截图 → 420ms 收拢回真实卡片，末帧压制窗口过渡无缝切回
- **明确竖握（显示将转回竖屏）**：不收拢，主题侧滑滑出 —— 收拢播在被本页钉住的横屏窗口里等于「先强制横屏再旋转」（2026-09-30 用户实测否决）

**踩坑实录（每条都有装机日志或 `dumpsys` 实锤）**：

1. **旋转横竖分支重建 → Source 孤儿**：主页 `if (landscape)` 两分支整体重建子树，`remember` 出的 Source 作废、bounds 冻结在竖屏坐标 —— 收拢现取/进场轮询读到的全是旧值（横握返回"直接闪"的根因）。修法 = 进程级 (Activity, siteId) 共享槽位（`AppTransitions.sharedSource`），重建后的新节点续刷同一实例（SportLink 0ad7e29 同款）
2. **跨方向判定不能用目标页"当前 Configuration"**：锁横屏页的旋转在 `onPostCreate` 时**尚未落地**（配置仍报竖屏）—— 由调用页显式传 `landscapeTarget = true`；跟随方向的详情页不传（恒同方向装配）
3. **轮询预算用时间不用帧数**：60 帧（SportLink 原值）在 120Hz 下只有 0.5s，而「旋转落地 + pause 态主页重排 + Compose 重组」实测 ~1s，必然误降级中心线。改 2.5s 时间预算（`dumpsys window` 实锤两窗口均已 3200×1440 而帧数预算已耗尽）
4. **锚点截图必须在装配时挂载**：曾随方案 A 挪进 PreDraw 闸门，而闸门只对展开进场生效 —— `expandOnEnter=false` 的详情页收拢从此丢了卡片本体/文字滑移层，直到 2026-09-30 才发现回修
5. **`View.draw` 整窗截图遇 `RuntimeShader` 会抛异常**（miuix 毛玻璃 highlight 在软件画布上拒绝绘制）：现拍路径捕获后降级 clip-only 展开（无截图交叉淡变、动画仍在），不会崩
6. **竖握返回不能收拢**：收拢播在被钉住的横屏窗口里 = 「先强制横屏再旋转」；且加速度计在近水平持机姿势读数 UNKNOWN/抖动，平放必须单独归类为"停留横屏"，否则横握被误判竖握又回到闪退

#### Shizuku 集成要点

- **UserService 由 Shizuku 反射加载**，不能、也不需要注册在 Manifest。R8 混淆会重命名类名 → `bindUserService` 永久失败（表现为「已授权但读不到数据」），故服务类加 `@Keep`，并在 `proguard-rules.pro` 保留 `rikka.shizuku.**` / `moe.shizuku.**` / `com.chen.powermeter.shizuku.**`
- Manifest 中 `ShizukuProvider` 的 permission **必须是** `INTERACT_ACROSS_USERS_FULL`（写 `API_V3` 会连不上）
- `ComponentName` 的 package 用**运行时** `packageName`、class 用**编译期包名**；改包名时必须同步，否则绑定永久失败
- 与同类项目（fold）的差异：本应用 `ShellService` **不做命令注入过滤** —— 全部命令都由内部常量拼接，且必须使用 `;` `|` `$()` 构造复合读取命令
- 绑定重试：`onServiceDisconnected` 后自动重绑，最多 3 次退避重试（500ms / 1s / 2s）；`MainActivity.onResume` 触发 `recheck()`，覆盖「用户刚在 Shizuku 里授权」的场景

#### CSV 导入：注册宽 + 入口严

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

#### 图表手势：`pointerInput` 的 key 必须是 `Unit`

手势用 `awaitEachGesture` 自写：单指 = 读数、≥2 指 = 缩放/平移。

`pointerInput` 的 key 必须恒为 `Unit`，几何量通过 `rememberUpdatedState` 持有 —— 把几何量当 key 会让每一步缩放都重建协程，捏合手势当场断掉。

`TrendChartState` 用**窗口**（归一化时间 `[0, 1]`）而非缩放系数建模，让捏合、平移、滑条三种操作落到同一组状态上，避免反复做系数 ↔ 窗口换算，也更容易在 0 / 1 边界上算出越界窗口。

卡片内嵌与全屏两种模式的分工：`state != null` = 全屏（可缩放 + **单指直接**读数）；`state == null` = 卡片内嵌（不可缩放 + **长按**后拖动读数）—— 单指直接拖动会与页面竖直滚动抢手势。

`ChartBody` 必须写成 **`ColumnScope` 的扩展函数**：`Modifier.weight` 只在 `ColumnScope` 可用，跨 Composable 调用会断掉隐式接收者。

#### 其它已验证事项

- **文字用 Compose 节点而非 Canvas 绘制**：本项目 Compose 版本下 `DrawScope.drawText` / `nativeCanvas` 已不存在，刻度文字、读数气泡一律走 Compose 文本节点
- **数字统一 `FontFamily.Monospace`**：MIUI/HyperOS 默认字体 MiSans 的等宽数字 tag 是自定义的 `thum`，标准 OpenType `tnum` 在小米设备上不生效
- **数值行整组右对齐**（`Arrangement.End`）：单位锚在卡片右缘，数值长度变化（`—` → `4.421`）时只向左侧扩展，单位不跳动
- **颜色胶囊文字色按 `Color.luminance()` 反算**，而非写死白/黑。API 31+ 走 Material You `secondaryContainer` 取色（随壁纸），API 30- 回落固定淡紫；规则同时兜住「日后换底色」与「未来其它胶囊」两种情形
- **曲线色只由 tab 上的色点表达，不由按钮底纹表达**：跟随曲线色会让按钮变成随数据漂移的色块（用户把线调成白色时按钮底变白、文字反算成深色，看上去像禁用态），也会抢占"点开颜色面板"这个功能语义
- **`Prefs.getMetricColor` 返回 `null` 表示未自定义**：必须区分「未设置」与「设成黑色」，故先用 `contains` 判存在性，不能用 `getInt` 的默认值
- **水波纹规范**：`clip` 必须紧贴 `clickable` **之前**，ripple 以节点矩形为边界，否则圆形色块外会溢出方形水波纹；转场锚点（会 spawn 截图的点击目标）一律 `indication = null` —— 按压视觉会被烤进截图
- **底部 sheet 自绘拖拽横条**：`dragHandle = null`，规避 M3 默认手柄长按弹出的「拖动手柄」tooltip；竖屏开板即全展开（`enabledValues` 去掉 `PartiallyExpanded`）
- **页面底色压深一档**（`Theme.withDeeperBackground`）：M3 baseline / dynamic scheme 中 `background` 与卡片填充色只差一个 tonal step（夜间 `#1C1B1F` vs `#211F26`），视觉上连成一片，卡片边界全靠阴影撑；2026-09-27 起中性色钉成 SportLink 固定值（日 `#E0E0E0` / 夜 `#2A2A2A`），**改 `Theme.kt` 中性色必须连带核 `colors.xml` 的 `windowBackground`**（消费面 = 窗口首帧 + 转场窗口外区域 + HyperOS 窗口明暗判定）
- **主题过渡动画昼夜两份都要挂**：过渡动画可能取自打开方或被打开方的主题，只在 `values` 声明的话夜间会退回系统默认
- **Kotlin/AIDL 注释里禁止出现 `*/` 与 `/*`**：前者（包括夹在反引号里的通配 MIME 字面量）会提前结束 KDoc，报出上百条 `Expecting member declaration`；后者会开启**嵌套块注释**（Kotlin 支持嵌套），令外层 KDoc 永不闭合。AIDL 注释里写 `thermal_zone*/` 同理炸编译
- **取 MediaStore 导出文件名不能用 `uri.lastPathSegment`**：对 `content://` 记录它返回数字 ID 而非 `DISPLAY_NAME`，要 query `OpenableColumns.DISPLAY_NAME`

### 1.13 CSV 数据格式

#### 导出（18 列，UTF-8，LF）

```csv
timestamp,datetime,voltage_v,voltage_ocv_v,current_ma,fg_current_ma,power_w,temp_battery_c,temp_usb_c,temp_charger_c,soc_pct,status,charge_type,remaining_mah,usb_voltage_v,temp_pmic_c,full_mah,usb_current_limit_ma
1763692800123,2026-09-21 02:40:00.123,4.312,4.355,2841,2803,12.253,33.4,31,38.900,78,Charging,Fast,3455,5.102,36.200,4500,3000
```

| 列 | 单位 | 说明 |
| --- | --- | --- |
| `timestamp` | ms | 完整毫秒 epoch，导入时优先使用 |
| `datetime` | — | `yyyy-MM-dd HH:mm:ss.SSS`。**带毫秒是必须的**：采样间隔最小 500ms，秒级精度下相邻两行会重复，无法直接作为时间轴 |
| `voltage_v` | V | 电池端电压 |
| `voltage_ocv_v` | V | 开路电压（OCV） |
| `current_ma` | mA | **正 = 充电，负 = 放电**；整数（内核只上报 mA 整数） |
| `fg_current_ma` | mA | 燃料计独立测得的电流，用于交叉校验；整数，无数据时留空 |
| `power_w` | W | `voltage_v × current_ma / 1000`，符号跟随电流 |
| `temp_battery_c` | ℃ | 电池温度（1 位小数） |
| `temp_usb_c` | ℃ | Type-C 接口温度（整数） |
| `temp_charger_c` | ℃ | 充电 IC 温度（3 位小数） |
| `soc_pct` | % | 系统 SOC |
| `status` | — | `Charging` / `Discharging` / `Full` / `Not charging` |
| `charge_type` | — | `Fast` / `Trickle` / `None` |
| `remaining_mah` | mAh | 剩余容量（来自燃料计 `fg1_rm`，权威）；整数 |
| `usb_voltage_v` | V | USB 输入电压 |
| `temp_pmic_c` | ℃ | 主 PMIC 温度（`pm8350c_tz` / `pm8350b_tz`）；3 位小数 |
| `full_mah` | mAh | 当前满充容量（`charge_full`），即电池健康度分母；整数。仅 sysfs 通道有，binder 通道留空 |
| `usb_current_limit_ma` | mA | USB 输入限流；整数。⚠️ 多数机型上报的是**限流上限而非实测电流**，仅作参考 |

#### 导入的容错范围

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

## 帧率监测

### 2.1 这是什么

面向「人在游戏 / 视频里看帧率」的场景：**系统级悬浮窗**常显实时帧率，人待在被测应用里就能读数；轻点悬浮窗开始录制，停止后落库成一场会话，详情页产出对齐 Kite / PerfDog 口径的性能报告。

一句话定位：**给游戏流畅度、掉帧卡顿、整机功耗温度做量化验证的仪器型工具 —— 与功率监测共用同一条电量取数链，帧率与功耗逐秒对齐。**

页面总览（`FrameMeterScreen`）：

- **顶栏**：标题「帧率监测」+ 副标题「历史记录 共 x 条」+ 右侧 Delete 图标（管理模式开关）
- **采样源选择卡**（置顶跨行）：四胶囊切换帧率采样算法，见 [2.2](#22-帧率采样源四算法)
- **历史记录列表**：竖屏单列 / 横屏两列（`LazyVerticalGrid`，旋转不断滚位）；记录卡 = 应用图标 + 显示名 / 包名 / 起始时间 + 右侧平均帧率
- **管理模式**：顶栏 Delete 开启后卡片右侧出现红色删除框，逐条删 → 红色确认弹窗才真删（交互对齐 SportLink 设备管理页；删除激活红固定 `0xFFD32F2F`，不走主题 `error` —— 本项目走 Material You 动态色，error 偏粉）
- **右下角 FAB**：miuix `FloatingActionButton`（+ ↔ × `AnimatedContent` 过渡），开关**系统悬浮窗**，见 [2.3](#23-帧率悬浮窗系统级)
- **详情页**：点记录卡一镜到底进入，见 [2.6](#26-详情页kite-版式性能报告)

应用图标/显示名解析需要 `QUERY_ALL_PACKAGES`（Android 11+ 包可见性过滤会 `NameNotFoundException`，个人工具不上架直接全量声明）；旧脏数据（落库时 appLabel==packageName）显示时现场补解析。

### 2.2 帧率采样源（四算法）

| 采样源 | 机制 | 特点 |
| --- | --- | --- |
| **Timestats**（默认） | `dumpsys SurfaceFlinger --timestats` 逐图层 `totalFrames` **差分** | 兼容性最好，shell / root 身份即可；自带逐帧 presentToPresent 直方图 —— 帧时间 / 卡顿 / 稳帧指数的数据源 |
| **SF Latency** | `dumpsys SurfaceFlinger --latency <图层>` 的 127 帧环形缓冲读**真实 present 时间戳** | 逐帧时间戳原生口径：帧率 = ΔF ÷ 时间戳 dt（窗口自带，免墙钟计时）；不少 ROM 已砍此接口 |
| **TaskFps** | `WindowManager.registerTaskFpsCallback`，WMS 系统推送 | 系统直推零采样开销；Android 11+ 且 Shizuku 已绑定即可用 —— `ACCESS_FPS_COUNTER` 是 signature\|privileged 权限，AOSP 派生 ROM 的 `com.android.shell` 特权包持有它，shell 身份即满足（个别 ROM 未授予则该源压暗回落）；不逐帧推时间戳，帧时间 / 卡顿指标该源整场缺测（详情页相应格子隐藏） |
| **FrameTimeline**（精度最高） | `/system/bin/perfetto` 抓 `android.surfaceflinger.frametimeline` 数据源，解析**逐帧 actualPresent 绝对真值** + SF 自带逐帧 jank 位掩码 | AOSP 12+ 全机型可用：SF 作为 perfetto producer 注册该数据源，**不依赖 `--latency` 的 127 帧 FIFO、也不受 timestats 跟踪表上限约束** —— `--latency` 已死的机型（如 24031PN0DC / A16）它仍能出数；shell / root 任一通道即可（perfetto 由通道身份拉起与读取，权限自洽） |

- 页面置顶选择卡切换并持久化（`Prefs`）；不可用的源压暗 + 标注「自动回落」，**回落不改写用户设置**
- 切换采样源 / 切换目标应用时作废全部差分基线（跨算法计数不可比）

**Timestats 的实现要点**（全部真机定案，注释留证在 `FrameRateSource`）：

- **timestats 是累计值**：帧率由相邻采样周期差分得到，首轮只建基线、不产出样本
- **部分 ROM 默认关闭 timestats**（plain 读法恒 0 行）：必须先 `-enable`；读取用 `-dump -maxlayers 8`（SF 侧只拼 totalFrames 降序前 8 图层，dump 尾巴 145-200ms → 53-79ms，120Hz 单拍计时噪声从 ±2.9 收到 ±1.2fps）；ROM 不认 `-maxlayers` 参数时自动回落全量命令
- **图层跟踪上限**：AOSP 默认只跟踪 64 个图层，图层数随开机只增不减（实测 304），新渲染图层被**静默拒绝**，认领到的全是停渲老图层的冻结累计值 → 差分恒 0 且零日志 —— 目标 / 算法切换时 `-clear` 清跟踪表再 `-enable`
- **awk 收窄**：全量 dump 可达 MB 级，Shizuku 通道跨 binder 回传会超限（1.23MB→2.46MB UTF-16 炸）—— shell 层按目标包名收窄到 ~2KB
- **块边界解析**：字段认领只在「当前图层块内」成立（出现段落标题 / 直方图数据行即块结束）—— 挡掉 ROM 在图层列表后附加的私有统计段（曾致悬浮 tab 冒 1000+ 假帧率）；判据不依赖字段个数与顺序，ROM 换字段序不再碎
- **丢帧逐行认领**：missedFrames / droppedFrames 在**各自所在行**认领（Android 14+ 逐图层段是逐字段一行，旧写法只找 totalFrames 同一行会恒假 0）
- **物理上限守卫**：差分超 `刷新率 + 1/dt` 的拍拒绝出数（读数保持上一拍、基线照常推进自愈），每拍 warning 留痕 —— 持续触发说明有污染源，拿日志里的 layer 名定位
- **陈旧基线作废**：差分窗口超 5s 作废（防重开悬浮窗假尖峰）
- **差分窗口按快照时刻打点**：timestats 快照落在本拍全部取数命令跑完之后，dt 必须取「快照时刻」之差而非拍首之差 —— 否则慢速拍把窗口周期性压短，120Hz 会被系统性拖到 ~95

**SF Latency 的实现要点**：

- `--list` 在 AOSP 16 / HyperOS 上有**两种排布并存**：带 hex 句柄 = 容器 / 镜像层（**无帧**），不带句柄 = 真实渲染面 —— 剥壳解析先按属性 token 截断、再按「首 token 是否纯 hex」决定丢不丢（旧写法固定取 `tokens[1]` 曾把唯一真渲染层解析成垃圾名，导致本机可用的 SF_LATENCY 被永久判死）
- 候选图层逐个探测 FIFO 帧数、取最多者；**判死不是终态**：连续 10 次（≈2.5s @250ms 子拍）「有候选却全只回周期行」才回落 timestats；判死期间每 5s 自愈重探，探到活图层自动恢复 —— 相机休眠销毁 Surface、页面转场等瞬时无图层场景不误杀

**TaskFps 的实现要点**：

- 注册走反射框架 `IWindowManager` + 手写 `ITaskFpsCallback` Binder（同 descriptor 单方法，签名来自 Metric APK 逆向；hidden API 豁免两级：经典元反射 → `HiddenApiBypass`）
- WMS **变化才推**（恒定帧率段不重复推送）→ 沿用上一拍 + 陈旧判 0：超 2.5s 无推送按 0.0 出数（语义「无帧周期」，如熄屏）
- taskId 挂在注册时解析的任务上，被测应用任务重建后 taskId 变而包名不变 → 推送永久静默：静默超时自动「踢注册」，重新解析最新 taskId（一次静默期只踢一次）

**FrameTimeline 的实现要点**（2026-10-03 加，逆向 Metric 录制链定案；Metric 的实时侧反而没有这条路）：

- **常驻 trace 会话**：进入本算法时经取数通道拉起后台 `perfetto`（`android.surfaceflinger.frametimeline` 数据源，`write_into_file` 每秒增量落盘，256MB 上限 ≈ 1.5h 高帧率录制）；trace 文件在 `/data/misc/perfetto-traces/`（traced 落盘、本通道可读），配置在 `/data/local/tmp/`。切走算法 / 关悬浮窗即收尾（`kill -INT` 优雅退出 + 删文件），预览 → 录制同算法**幂等续用同一会话**，会话意外缺失时子拍自愈补拉 —— 不留常驻 trace 空耗电
- **增量解析**：每个子拍 `tail -c +offset` 只取**新增字节**，喂进增量解析器（trace 文件 = `0x0A + varint 长度 + TracePacket` 的顺序流，真机 832 帧 / 120Hz cadence / jank 位掩码全实证）—— 二进制 protobuf 不能走 String 回传（编码往返会损坏），管道尾接 `base64` 再解码（子拍增量仅几 KB，远够不着 binder 上限）
- **判死与自愈**：静止画面 0 帧是常态（同 timestats 0 帧语义），不算失败；只有 **trace 文件消失**（perfetto 被系统杀 / 从未启动成功）连续 10 次才回落 Timestats，判死期间每 5s 重拉会话自愈
- **进程管理坑**：拉起用 `setsid` 脱离会话常驻 —— `setsid` 会 fork，`$!` 拿到的是包装进程而非真 perfetto，故由内部 `sh` 先把 `$$` 写 pidfile 再 `exec`，收尾按 pidfile 杀真身；启动前先 `pgrep` 清扫本应用残留的 perfetto（防旧会话泄漏、两个 perfetto 同写一个文件）

### 2.3 帧率悬浮窗（系统级）

**为什么是系统级悬浮窗而不是 Compose 内嵌悬浮层**：帧率监测的核心场景是「人在别的应用（游戏 / 视频）里看帧率」，Compose 的悬浮层只活在自家窗口里，一切到被测应用就没了。`FrameOverlayService`（原生 View，`TYPE_APPLICATION_OVERLAY`）—— tab 盖在**任何**应用上方，常显实时帧率。

| 项 | 说明 |
| --- | --- |
| 权限 | 需 `SYSTEM_ALERT_WINDOW`（「显示在其它应用上层」）。这是**特殊权限**，没有系统弹窗，只能引导：Toast 说明用途 → 跳 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`；从设置页回来时若已授权，直接拉起悬浮窗 |
| 进程保活 | 悬浮窗服务是**前台服务**（`specialUse`，常驻通知共用 `frame_record` 低重要性渠道）。悬浮窗本身不能阻止 Android 12+ 冻结缓存进程，进程一冻结预览采样就停了，帧率会变成再也不动的死数字 |
| 常显帧率 | 悬浮窗一出现就开始 **250ms 子拍（4Hz）预览采样**（只更新读数、不产样本、不落库）—— tab 恒显示实时帧率，与是否录制无关，**任何前台界面都测**（不排除本应用自身）；关悬浮窗才停预览。4Hz 读数让滑动 / 加载期的帧率起落跟手，稳定后与 Scene 同步 |
| 形态 | 椭圆形 pill（43×25dp），内部**只有一行等宽加粗帧率数字**：**< 100 保留 1 位小数**（89.0）、**≥ 100 取整**（120）；尚未采到有效差分显示 **`—`** 而不是 `0.0`（`NaN` 语义）。「录没在录」由**底色**承担，tab 内无其它装饰 |
| 配色 | 未录制 = 浅绿 `0xFFA5D6A7` 黑字；**录制中 = 热烈红 `0xFFE53935` 白字**（录制态必须一眼可辨）。底色 **70% 半透明**透出被测画面（色相与对比度反算不受透明度影响），文字色统一由底色相对亮度（>0.55 配黑字）反算 |
| 位置 | 默认位 = **右缘贴死 + 离顶 1/4 屏高**；窗口 `fitInsetsTypes = 0` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，横向贴死物理边缘（挖孔条带无系统 UI 不拦触摸，遮挡由用户自己权衡），纵向避开系统栏与前摄。**位置不持久化**，每次开窗回默认位。单指拖动（超 touch slop 即拖动，不再是轻点）；旋转后按**高度比例**跟随（竖屏 1/2 高 → 横屏 1/2 高），贴边侧别显式记忆 —— 竖屏的左右缘物理上映射成横屏的上下缘，侧别不能重算 |
| 起停 | 轻点 tab 开始 / 停止录制。**默认就是不限时**（每次 `stop()` 都会 `clearLimit()`），必须再点一次 tab 手动停止；只有在时长窗口里挑了 5 / 10 / 15 / 30，才按**本场开始时间**推算到点自动停。停止录制**不再弹**时长窗口（收工即收起） |
| 时长窗口 | tab **正下方、与 tab 左对齐**（拖动同步更新，下方放不下翻上方）；整体沿对角线缩到 **2/3 尺寸**；底色 = 一枚**半透明黑 `0xCC1E1E24`**，无任何特效 —— overlay 窗口拿不到背后像素（`FLAG_BLUR_BEHIND` 对第三方应用无效），做不了真 backdrop blur。内容**仅两行**（标题 + 5 / 10 / 15 / 30 四枚胶囊），**5s 无点击自动隐藏**，**不记忆上次选择**。⚠️ 窗口内**不放任何其它信息**：错误原因 / 已录时长 / 起始帧率都不在这里展示，失败反馈走 Toast 与录制服务的常驻通知 |
| 取数失败 | 首轮读不到**不判死**：连续 16 个子拍（≈4s）内持续重试（等 Shizuku 绑定完成 / 通道恢复）；**预览循环读不到也不退场**、通道自愈后读数自动恢复；宽限期满仍无一个样本才停表并补一条 Toast。⚠️ 功率侧的「看起来正常」不能反证通道可用：功率有 `BatteryManagerSource`（零特权调用）兜底，帧率每条命令都要走特权通道，没有这层掩护 |
| 关闭 | FAB（此时为 ×）关悬浮窗；**正在录制时关闭会先停止并落库**，预览采样随之停止 |

⚠️ **拖动边界的「空区间」陷阱**（2026-09-22 实测崩溃）：面板比 tab 宽，横向夹取的下界要按**面板宽度**算，而 Kotlin 的一元负号**覆盖整条调用链** —— `-x.coerceAtMost(-4f)` 实际是 `-(x.coerceAtMost(-4f))`，屏宽 384 时算出来是 **+4**，与上界 `-4` 正好颠倒，于是 `coerceIn(4f, -4f)` 抛 `IllegalArgumentException`，症状是**手指一在悬浮 tab 上移动（超过 touch slop）整个应用立刻崩**。正确写法是先取负、再夹上界，并保证 `min ≤ max`。

### 2.4 录制与采集管线

采集由 `FrameRecordController`（进程级单例）承载 —— 页面旋转 / 深浅色切换重建不会打断录制。点开始录制会同时拉起前台服务 `FrameRecordService`（常驻通知显示当前帧率与已录时长）+ 持 `PARTIAL_WAKE_LOCK`：前台服务让**进程**不被冻结 / 回收，唤醒锁让 **CPU** 在息屏后不休眠。

**差分基线是控制器对象字段**：预览 → 录制无缝交棒，点录制 tab 读数连续不回「—」（旧实现强制重建基线，clear 后要 2-3 拍重建，预览明明在出数却全部丢弃）。

采样节奏（子拍制）：

| 数据 | 节奏 | 说明 |
| --- | --- | --- |
| 实时帧率读数（悬浮 tab） | **250ms 子拍（4Hz）** | 四个采样源统一在下放到子拍：切算法 / 换目标即时生效，读数跟手 |
| 帧率样本（落库） | **1s / 条** | 1s 样本的 fps = 本拍窗口内子拍差分合成（ΣΔF ÷ Σdt，首尾相接可 telescoping，与整拍一次差分逐位一致）；缺测拍落 null（曲线断线、网格均匀） |
| CPU 快样（总占用 + 8 核占用/频率） | **250ms 子拍** | `/proc/stat` + 逐核 `scaling_cur_freq`；1s 样本取快样窗口均值。密集采样让详情页 CPU 卡呈现 Scene 式锯齿而非 1s 台阶 |
| 电量四项（电压 / 电流 / 功率 / 电池温度） | 1s | 与功率监测走**同一条取数链** `RootPowerReader.read()`（见 [1.10](#110-取数通道)），与帧率逐秒对齐；串联双电池换算同口径生效（见 [1.7](#17-串联双电池默认关闭)）；落库毫口径（mV / mW / mA）、绝对值 |
| 前台应用 / 刷新率 / CPU·GPU 温度 / DDR·GPU 频率 | 5s 错峰 | 变化慢，且每条命令都要起进程；多项在不同拍轮转（beat%5），摊平单拍耗时 |

其它要点：

- **目标锁定**：前台应用包名（`topResumedActivity`）。每拍检测目标变化，变化即作废全部差分基线 + 重置对应源的选层 / 目标状态（timestats 重清跟踪表、latency / frametimeline 重置选层与解析基线）
- **采集去 fork**：Shizuku UserService（AIDL v3）进程本身常驻 shell 身份，对 `/proc/stat`、逐核频率、thermal、`gpubusy`、DDR 频率节点与 awk **同一权限** —— 直接 java.io 读 = 零 fork（稳态 fork 从 ≈13.5 次/秒降到 ~1 次/秒，拍周期 1.35s → ~1.05-1.15s）；timestats 改裸 `dumpsys`（收窄后 37KB，低于 binder 上限），仅全量回退路径保留 awk 管道
- **屏幕静止或熄屏时无合成帧，帧率为 0** —— 语义是「本周期没有帧」，不是「掉到 0 帧」（四个采样源同语义）
- **限时自动停**：时长窗口选了 5/10/15/30 才生效，按本场开始时间推算；`stop()` 一律清限时
- **停止录制**：快照会话四项后落库（帧率会话 + 1s 样本 + 读数点 + CPU 快样），列表即出现新场次

### 2.5 数据链与存储

采集数据源（帧率 / CPU / 前台应用需 shell / Shizuku 身份即可；电量四项与功率监测共用取数链，均不需要额外特权）：

| 字段 | 数据源 | 备注 |
| --- | --- | --- |
| FPS / 帧间隔 / 丢帧 | 四采样源（见 [2.2](#22-帧率采样源四算法)） | 帧间隔 = presentToPresent 直方图**当秒差集**加权平均（timestats）或**真实逐帧间隔**（sf_latency / frame_timeline）；task_fps 由 1000/fps 推导；timestats 的逐帧分布整体落库（帧时间 / 卡顿 / 稳帧指数的数据基础） |
| CPU 总占用 + 8 核占用 / 频率 | `/proc/stat` + `scaling_cur_freq`，250ms 快样 | UserService 直读优先，命令回退；核心数不写死 |
| GPU 负载 | `gpubusy`（kgsl）等 8 候选单进程 awk | SELinux **按文件标签逐一判定**：shell 身份常只有 `gpubusy` 可读（Scene 同款节点），root 身份全通；全缺则相应曲线/图例自动不出现（不是 bug） |
| GPU 频率 | devfreq `cur_freq` 等 10 候选 | 多被 SELinux 拦截，root 机型可读；量级自适应（Hz / kHz / MHz） |
| DDR 频率 | `bus_dcvs/DDR/cur_freq`（QCOM）/ MTK dvfsrc `cur_freq`，候选池逆向 Metric 守护进程定案 | 22081212C 实测 **shell 身份可读、无需 root**；kHz/Hz/MHz 按量级换算；节点全不可读的机器详情页整卡隐藏 |
| 电压 / 电流 / 功率 / 电池温度 | `RootPowerReader.read()`（与功率监测**同一实现**，见 [1.10](#110-取数通道)） | 落库 mV / mW / mA 毫口径，显示侧 ÷1000 回 V / W |
| 电量 % | 功率链 `socPct`（sysfs `capacity` / BatteryManager level） | 三通道都有，0 视为缺测 |
| CPU / GPU 温度 | thermal_zone 取 CPU / GPU 类**最热**区（5s 抽稀） | 旧会话缺列 = 曲线断线不画 |
| 前台应用 / 刷新率 | `dumpsys activity` / `dumpsys display` | 刷新率用于差分守卫阈值与 FPS 卡轴顶 |

存储 —— **独立帧率库**：

- `FrameDatabase`（`frame.db`，version 8，真实迁移链 v1→v8），与功率库 `powermeter.db`（v10）**拆分**：两侧 schema 演进互不牵连，功率库刻意不设破坏性兜底（功率数据宁可崩不静默清库），帧率库保留 `fallbackToDestructiveMigration` 兜开发期意外跳变
- 4 张表：

| 表 | 内容 |
| --- | --- |
| `frame_sessions` | 会话行：应用 / 包名 / 起止时长 / avg·min·max·1%·5% Low / 方差 / 丢帧 / 刷新率 / 平均帧间隔 / **本场采样源** |
| `frame_samples` | 1s 样本：fps、帧间隔、丢帧、电量四项（mV/mW/mA）、电量%、CPU/GPU/虚拟温度、GPU 负载/频率、**DDR 频率**（v8 起）、**p2p 逐帧间隔分布**（`ms:count` 序列化） |
| `frame_fps_samples` | 每秒帧率读数点（含缺测 null 拍）—— 详情页 FPS 曲线与 MIN / 1% / 5% Low 的取数来源 |
| `frame_cpu_samples` | 250ms CPU 快样：总占用 + 逐核占用 + 逐核频率（全可空列） |

### 2.6 详情页（Kite 版式性能报告）

`FrameDetailActivity` —— 版式对齐 Kite 桌面版性能报告（卡片圆角统一 10dp），从上到下：

1. **统计网格**（3 行 × 4 列）：`MAX / MIN / AVG / VARIANCE`（FPS 四项，VARIANCE = FPS 总体标准差）· `1% / 5% Low` · `MAX 电池温度` · `AVG 功率` · `帧能耗`（\|平均功率\| ÷ 平均帧率）· `卡顿率`（TaskFps 源无逐帧数据，该格隐藏并补空位对齐）· `稳帧指数`ⓘ
2. **FPS 卡**：FPS 灰线走左轴（轴顶 = max(面板最大刷新率, 本场刷新率) + 2，与实测峰值取大）；右轴**多选叠加**：电量% / 温度 / **CPU/GPU 负载(%)**（一个图例项粉蓝双**色块**，数据自适应 —— 任一有数据即出现、整列缺的线不画）；图例点选显隐（至少留一条）、Refresh 钮轮换单选；右轴宽度恒按 0-100 预留，切换选项绘图区不跳动
3. **帧时间卡**：**逐帧渲染**（p2pHist 分布展开成逐帧竖条，等值 run 合并、最小 1px 宽 —— 密集段重叠成实心带，Scene 同观感）；y 轴钉死 **0-100**：有分布 = 12 档 8/16/25/…/100（120Hz vsync 整数倍，与 Scene 逐位一致），超顶帧钉轴顶画出（真值看 footer）；无分布（TaskFps 源 / 旧会话）回退每秒平均柱、0-100 步长 20；footer MAX = 最大单帧 / 方差
4. **Jank 卡**：**PerfDog 口径逐帧三档** —— 小卡顿 = 单帧 > 2× 前 3 有效秒均值、卡顿 = 单帧 > 83ms（两倍电影帧）、严重卡顿 = 单帧 > 3× 且 > 125ms；图例 = 各档**帧数**、柱 = 该秒最高档；ⓘ 说明弹窗
5. **Power(W) 卡**：功率蓝线左轴（1/2/5×10ⁿ 序列选**行数最接近 7** 的整步长）+ 电量% 右轴 0-100；**纵轴口径 = 放电为正**（Kite 观感，与统计网格 AVG Power 的「正 = 充电」相反）；footer MAX/MIN/AVG
6. **Temperature(°C) 卡**：CPU / GPU / BAT / **VIR** 四线，图例可点显隐（隐藏压暗、至少留一条）；y 轴两档 —— 有 CPU/GPU 温度的场次 0-100 每 10 一档（温感区满载可破 50），只有 BAT/VIR 维持 0-50；**VIR = 0.75 × 电池温度 + 0.25 × 43.5℃**（Kite virTemp 公式：外壳表面没有热敏电阻，用电池温度 + 热点常数的合成值占位 —— 不是温感区读数；渲染期现算、不落库，旧会话自动出线）；ⓘ 说明弹窗
7. **CPU Usage 卡**：Total 线 + 四分簇（0-1 / 2-4 / 5-6 / 7，Kite 同款划分）均值线，**簇内逐核淡色细线**从属绘制（不进图例，图例开关整簇）；y 轴 0-100 每 10% 一档；250ms 快样呈现密集抖动
8. **CPU Frequency 卡**：同四分簇；y 轴 300MHz 一格、顶档 = **本场峰值**（随场次变，Scene 同款）
9. **GPU 频率卡**：频率线左轴（500MHz 一格 + 顶档 = 本场峰值的 500MHz 档上限）+ 负载线右轴 0-100（与 FPS 卡「CPU/GPU 负载」同列数据），双线恒显不进图例点击；无 gpuFreq 数据的场次整卡隐藏
10. **DDR 频率卡**（置底，2026-10-02 加）：青绿单线（与 GPU 紫 / CPU 簇四色均不撞色），版式与 GPU 频率卡同款 —— 左轴 0 → 本场峰值的 500MHz 档上限、500MHz 一格刻度，静态色点图例；`ddrFreqMhz` 列 v8 起采集，旧会话 / 候选节点全不可读的机器整卡隐藏

**进场与性能**：

- 点卡片**一镜到底**进场：从卡片矩形四向撑开、截图交叉淡变，返回收拢回卡片当前位置（转屏后返回走重定位收拢）。大场次不卡的三件套：
  1. **按压预热** —— 按下卡片（比 click 早一个抬手）即后台读库（`FrameDetailPreheat` 单槽位）
  2. **图表位图后台光栅化** —— 折线位图在 `Dispatchers.Default` 构建，Canvas 网格骨架先行，曲线在展开交叉淡变期内逐卡到达
  3. **内容就绪闸门** —— 首帧重活落地后等两帧再起跑展开动画（3s 超时兜底）
- 列表**连点防叠层**：`startActivity` 到目标窗口接管触点之间落在窗口期的每次 tap 都会叠开一层详情 —— `LaunchGate` 按生命周期上闸（首次放行即上闸、宿主页重新 ON_RESUME 才复位），不拍任何超时数值
- 曲线实现要点：缺测断线（NaN 不画 0 兜底）、FPS 线 ≤3 拍连片缺测**桥接连线**（SF 图层 churn 的单拍断口不再成虚线，长缺测仍断）、x 轴四分点真实时刻、轴标签按最宽标签实测内缩贴边

### 2.7 统计口径

- **MAX = 全量口径**：实际记录到的最大帧率，不做任何剔除 —— 120Hz 下 1s 差分窗口的边界效应可到 121（厂商的平均帧率好看手段同源：Scene 的 max 121 一样）
- **AVG / 1% / 5% Low 走过滤集**：剔除超 `刷新率 + 0.5` 的异常拍（60Hz 游戏均值不会报成 61）；1% / 5% Low = **帧加权调和**口径
- **MIN 从读数点取**：`frame_fps_samples` 读数点（>0 过滤；0 = 该秒没有合成帧不计），无则回退 1s 样本
- **卡顿率** = 卡顿 + 严重卡顿帧耗时 ÷ 全部帧耗时（PerfDog 帧耗时占比口径，**不是**丢帧占比）
- **稳帧指数** = 帧时间标准差（逐帧 Σx/Σx²；PerfDog Smooth 的简化口径，官方综合公式未公开）
- **帧间隔** = 当秒 presentToPresent 直方图差集加权平均（≥1s 的桶按呈现中断处理，切页 / 空闲不进任何指标）；SF Latency / FrameTimeline 源为真实逐帧间隔
- **读数与 Scene / 游戏内置对不上的三个已知机制**（真机审计定案：Δ帧数 ÷ 快照间隔公式无系统偏差，30 拍连采 mean 与真值一致）：
  1. 单拍计时噪声：快照在 SF 内部、时间戳在 dump 传回后 —— 已用 `-maxlayers 8` 把 120Hz 噪声收到 ±1.2fps（FrameTimeline 源直接读逐帧真值，无此噪声）
  2. 物理上限守卫砍掉噪声高侧 → 可见读数重心略低于 120
  3. 口径差：timestats 数「SF 真实合成上屏」（buffer 迟到 vsync 即被跳过），游戏内置 / Scene 数「应用提交」—— GPU 压满时提交 120、上屏 ~115。与 Kite 原始数据交叉验证过：三仪器（本应用 / Kite / Scene）低谷同分布，低谷真实
- **采样粒度**：帧率样本 1s 聚合；单帧尖刺由帧时间卡 / Jank 卡的**逐帧直方图**承载，不靠 1s 均值

### 2.8 导出与分享（xlsx）

- `FrameXlsxExporter`：**手写 OpenXML 最小集，零第三方依赖**，经 FileProvider（`cacheDir/share/`）+ `ACTION_SEND` 走系统分享
- 结构：元信息（Package Name / Device Type / **Cadence**）→ 汇总块（Avg FPS / Avg Power[mW] / Sum Battery[mWh]）→ 31 列数据表
- 行节奏 = **库内最细**：帧率 ~1s 一行（`yyyy-MM-dd_HH:mm:ss.SSS` 毫秒精度时间戳）；CPU 各列 = 最近邻**单调配对**的 250ms 快样（同子拍打点差 100-200ms，配对阈值 500ms，超阈留空）；功率 / 电压 / 电流 / 三温度 / 电量 / GPU 负载为 1s 粒度，在所在秒的行上重复、表头 `[1s]` 标注；旧会话（无快样表）回退 1s 行
- 末列 `FrameTimeHist[1s]` = 当秒逐帧间隔分布（`8:115,9:3` 形态），与库内同格式
- 文件名 `PowerMeter_yyyyMMdd_HHmmss.xlsx`（不用 Kite_ 前缀 —— 桌面 Kite 自产文件与本应用导出同名混淆曾导致误判）

### 2.9 会话语义（与功率侧相反）

⚠️ 帧率会话与功率会话的语义**相反**，勿混用生命周期规则：

- `frame_sessions` = **用户资产**：出现在历史列表里供随时回看，只有用户在管理模式显式删除才消失（样本外键级联清理）
- `power_sessions` = **自动保存的临时存档**（导出即删、稳态下最多一行，见 [1.9](#19-采样数据落库与会话生命周期)）

---

## 架构与模块地图

```
app/src/main/
├── aidl/com/chen/powermeter/shizuku/IShellService.aidl      UserService 的 AIDL 契约（v3：命令执行 + CPU 快样/GPU/DDR/温度直读 + TaskFps 桥）
└── kotlin/com/chen/powermeter/
    ├── PowerMeterApp.kt                       53 行  Application：Shizuku / 双库 / i18n 初始化 + 冷启动清理
    ├── MainActivity.kt                       550 行  入口：权限、外部 Intent 分发、导出、双模式装配 + ClipReveal 切换转场
    ├── data/
    │   ├── PowerSample.kt                     70 行  功率采样快照 / 会话统计
    │   ├── FrameSample.kt                    123 行  帧率采样快照（FPS / p2p 分布 / 8 核 / 电量 / 温度 / GPU / DDR）
    │   ├── FpsAlgorithm.kt                    61 行  帧率采样源枚举（timestats / sf_latency / task_fps / frame_timeline）
    │   ├── RootPowerReader.kt                879 行  三通道取数 + 单位换算 + 解析（功率与帧率两侧唯一的电量来源）
    │   ├── SampleStore.kt                    194 行  功率实时环形缓冲 + O(1) 增量统计（只作显示窗口）
    │   ├── BatteryManagerSource.kt           158 行  主进程零 fork 通道（getLongProperty + 粘性广播）
    │   ├── SuSession.kt                      168 行  root 常驻 shell 会话（免每次 fork su）
    │   ├── BatteryInfoStore.kt                69 行  电池静态信息仓库（进程级单例）
    │   ├── FrameHistoryStore.kt               80 行  帧率历史记录仓库（进程级单例，StateFlow）
    │   ├── FrameRateSource.kt              1 756 行  帧率取数：四算法 + CPU/GPU/DDR/温度直读 + timestats/latency/FrameTimeline 解析
    │   ├── CsvImporter.kt                    266 行  功率 CSV 解析 + ImportedSeries（导入态单例）
    │   └── db/
    │       ├── PowerMeterDatabase.kt         273 行  功率库 powermeter.db（v10，迁移链 v1→v10；v9→v10 起剥离帧率表）
    │       ├── PowerSession.kt                25 行  功率会话（临时存档，非用户资产）
    │       ├── PowerSampleEntity.kt           99 行  功率样本行（唯一索引保 flush 幂等）
    │       ├── SampleDao.kt                   58 行  功率会话 + 样本 DAO（含导出用分页查询）
    │       ├── SessionRecorder.kt            339 行  功率会话录制器：10s 增量落库 / 导出 / 会话生命周期
    │       ├── FrameDatabase.kt              173 行  帧率库 frame.db（v8，独立于功率库，真实迁移链 v1→v8）
    │       ├── FrameSession.kt                53 行  帧率会话（用户资产，与 PowerSession 语义相反）
    │       ├── FrameSampleEntity.kt          186 行  帧率 1s 样本行（含 p2p 直方图序列化 + 毫口径电量 + DDR 频率）
    │       ├── FrameFpsSampleEntity.kt        61 行  帧率读数点行（曲线 / MIN / 1% / 5% Low 取数源）
    │       ├── FrameCpuSampleEntity.kt       102 行  CPU 250ms 快样行（总占用 + 逐核占用 + 逐核频率）
    │       └── FrameDao.kt                    57 行  帧率会话 / 样本 DAO
    ├── service/
    │   ├── SamplingService.kt                761 行  功率前台服务：采样循环、通知节流、息屏降频、wakelock、充电监测
    │   ├── FrameRecordController.kt        1 555 行  帧率录制控制器（进程级单例：250ms 子拍 / 四算法切换 / 守卫 / 落库）
    │   ├── FrameRecordService.kt             166 行  帧率录制前台服务（保持进程前台 + 常驻通知）
    │   └── FrameOverlayService.kt            713 行  帧率系统悬浮窗（TYPE_APPLICATION_OVERLAY 常显帧率 + 比例跟随拖动 + 录制态变红）
    ├── shizuku/
    │   └── ShellService.kt                   470 行  Shizuku UserService（v3：命令执行 + /proc·thermal·gpubusy·DDR 直读 + TaskFps 反射桥）
    ├── ui/
    │   ├── PowerMeterScreen.kt             1 470 行  功率监测主页（竖屏单列 / 横屏双列）、Metric 枚举与 tab 过滤
    │   ├── TrendChartView.kt               1 170 行  功率图表组件：多序列 / 填充 / 读数 / 缩放 / 抽稀 / 断点
    │   ├── TrendFullscreenActivity.kt        694 行  趋势全屏页（沉浸式 + 挖孔避让 + 一镜到底 + 退出分流）
    │   ├── FrameMeterScreen.kt               878 行  帧率监测页：采样源选卡 + 历史列表（横两列）+ 管理模式 + FAB
    │   ├── FrameDetailActivity.kt          3 036 行  帧率详情页：Kite 版式报告（统计网格 + 九张图表卡）+ 位图后台光栅化
    │   ├── MonitorMode.kt                     25 行  监测模式枚举（双击标题切换，键持久化到 Prefs）
    │   ├── ModeTransition.kt                 185 行  模式切换 ClipReveal 转场（从标题矩形展开/收拢）
    │   ├── FrameFormat.kt                     35 行  帧率两页共用的时间 / 时长格式化
    │   ├── ColorPickerDialog.kt              397 行  ChartColors 仓库 + 颜色选择面板（miuix ColorPalette）
    │   ├── ClipReveal.kt / ClipRevealLayout.kt        容器变换转场核心（锚点矩形展开 / 分层截图 / 收拢）
    │   ├── ContainerSource.kt                102 行  转场锚点采集 Modifier（矩形 + 文字层）
    │   ├── BlurGlass.kt                      646 行  玻璃弹窗管线（毛玻璃 + 高光 + 按压预备 + 管线预热）
    │   ├── ThemeTransition.kt                302 行  深浅色切换圆孔揭露转场（PixelCopy 截旧界面 + 圆孔扫出）
    │   ├── common/BlurTopBar.kt              115 行  顶栏三档模糊封装
    │   ├── common/AppCard.kt                  57 行  通用卡片
    │   ├── common/LaunchGate.kt               59 行  跨页启动重复点击闸门（生命周期闸，防连点叠层）
    │   └── theme/
    │       ├── Theme.kt                      170 行  M3 Expressive 主题 + SportLink 中性色 + 圆孔揭露闸门
    │       └── CornerRadius.kt                55 行  LocalCornerRadius（小米读物理圆角）+ 屏幕圆角 px
    └── util/
        ├── AppTransitions.kt                1 619 行  一镜到底转场全链（截图 / 展开 / 收拢 / 转屏重定位 / 现取锚点）
        ├── ShizukuHelper.kt                 384 行  Shizuku 三态 + UserService 绑定重试 + 直读桥
        ├── EdgeToEdge.kt                    162 行  NavigationBarHelper：真沉浸 / 重放链
        ├── ScreenController.kt               76 行  特权通道注入电源键熄屏
        ├── CsvExporter.kt                   166 行  功率 CSV 导出到 MediaStore.Downloads（分页流式写出）
        ├── FrameXlsxExporter.kt             307 行  帧率 xlsx 导出（手写 OpenXML 零依赖 + FileProvider 分享）
        ├── AppStrings.kt                     40 行  object 单例取文案的桥（i18n，由 Application 注入）
        └── Prefs.kt                         123 行  SharedPreferences 封装（监测模式 / 采样源持久化）
```

Kotlin 共 **56 个文件、22 741 行**；另有 1 个 AIDL 契约与 14 个资源文件。

### 功率侧数据流

```
                    ┌─ 通道1  sysfs awk（Shizuku / root）
采样循环 ──────────► ├─ 通道2  BatteryManager（主进程，零 fork）
（按间隔 tick）      └─ 通道3  cmd battery get + dumpsys（兜底）
                              │
                              ▼
                      RootPowerReader.read() ──► PowerSample
                              │
              ┌───────────────┼───────────────────────────────┐
              ▼               ▼                               ▼
   SampleStore（环形 7200    SessionRecorder ──每 10s 批量──► PowerMeterDatabase
    + 增量统计，只作显示窗口）  （Channel 单消费者，零阻塞入队）   （Room，私有目录）
              │                       │
              │                       ▼ 导出时：分页读全量 → CSV → 删会话
              │               Download/PowerMeter/*.csv
              │                                               ImportedSeries（导入态，上限 20000）
              └──────────┬──────────────────────────────────────────────┘
                         ▼  UI: if (导入非空) 导入 else 实时
              PowerMeterScreen / TrendFullscreenActivity
                         ▼
                  TrendChartView (Canvas)
```

三条数据源**并列且互不写入**，因此「查看历史文件」不会覆盖正在进行的实时采样，「清空采样数据」也不会把导入的数据一起清掉。落库链路（SessionRecorder → Room）与显示链路（SampleStore → UI）也是分开的：后者容量只有 7200 条但零 IO，前者不受条数限制但只在导出时被读。

### 帧率侧数据流

```
                 ┌─ TIMESTATS      dumpsys SurfaceFlinger --timestats（逐图层差分 + p2p 直方图）
                 ├─ SF_LATENCY     dumpsys SurfaceFlinger --latency（127 帧环形缓冲 present 时间戳）
采集循环 ───────► ├─ TASK_FPS       WindowManager TaskFpsCallback（WMS 系统推送）
（250ms 子拍      └─ FRAME_TIMELINE perfetto frametimeline trace（逐帧 actualPresent 真值，增量解析）
  + 1s 完整拍）          │（FrameRateSource：UserService 直读优先 → exec 回退）
                         ▼
                 FrameRecordController（差分基线 / 上限守卫 / 目标切换）
                         │                          │
                         ▼                          ▼
              悬浮 tab 实时读数（4Hz）      RootPowerReader.read()（电量四项，与功率侧同链）
                         │                          │
                         └────────────┬─────────────┘
                                      ▼ 每 1s 组装样本落库
                      FrameDatabase（frame.db，独立于功率库）
       frame_sessions / frame_samples / frame_fps_samples / frame_cpu_samples
                                      │
                       ┌──────────────┴──────────────┐
                       ▼                             ▼
            FrameMeterScreen（历史列表）      FrameDetailActivity（Kite 版式报告）
                                                └─ FrameXlsxExporter → FileProvider 分享
```

### 共通 UI 基建

- **一镜到底转场**（`AppTransitions` + `ClipReveal`）：模式切换（标题矩形）、趋势全屏页（趋势卡矩形）、帧率详情页（列表卡片矩形）三条链路共用同一套容器变换
- **深浅色切换**：前台配置变化播 **PixelCopy 圆孔揭露**（800ms 圆孔从右下角扫出新主题）；退场交棒与被二级页盖住时刻意静默（防闪回旧色 / 双层动画）
- **玻璃弹窗管线**（`BlurGlass`）：毛玻璃 + 高光描边 + 按压预备态（按住即以 2% alpha 组合好，松开只播入场）+ 空闲管线预热（shader 编译挪出点击帧）
- **顶栏三档模糊**（`BlurTopBar`）：API 33+ AGSL 渐进 / 31-32 Haze / 30- 渐变盖板，见功率侧 [1.12](#112-关键技术决策与踩坑记录)

---

## 构建与运行

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

1. **取数通道**按模式分：
   - **功率监测**：root 或 Shizuku 至少其一（字段最全的是 root；无 root 机器 sysfs 被 SELinux 拦时自动降级主进程 `BatteryManager` 通道，功能收窄但不取不了数）
   - **帧率监测**：**必须 Shizuku**（adb 或 root 模式授权 —— 帧率 / CPU / 前台应用每条命令都走特权通道，没有 BatteryManager 那层兜底）。四个采样源里：Timestats / SF Latency / FrameTimeline shell 身份即可；**TaskFps 需 Android 11+ 且本 ROM 给 `com.android.shell` 授了 `ACCESS_FPS_COUNTER`**（AOSP 派生 ROM 一般都有；Shizuku 以 root 模式启动亦可）
2. 首次点「开始采样」/ 开悬浮窗会申请通知权限（Android 13+）与「显示在其它应用上层」特殊权限，各自有引导文案
3. 通道不可用时页面顶部显示错误卡，说明具体原因（含通道身份与原始输出摘要，便于截图定位）
4. 功率侧历史数据：把导出的 CSV 从文件管理器「用其它应用打开」或直接「分享」给本应用即可；帧率侧历史在应用内列表（导出走 xlsx 分享）

### Release 构建注意事项

`buildTypes.release` 已开启 R8（`isMinifyEnabled` + `isShrinkResources`），规则见 `app/proguard-rules.pro`。本项目不使用序列化与 JNI，**`-keep` 需求来自 Shizuku**（UserService 类由 Shizuku 反射加载，必须保留类名）与 TaskFps 的框架接口反射桥。

出包后建议归档 `app/build/outputs/mapping/release/mapping.txt`，用于还原 Release 崩溃堆栈。

---

## 依赖清单

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
| `dev.chrisbanes.haze:haze` | 1.7.2 | API 31–32 顶栏真实模糊 + 弹窗玻璃管线 |
| `io.github.kyant0:backdrop` | 2.0.1 | API ≥ 33 渐进式 AGSL 模糊（**需 exclude CMP 传递依赖**） |
| `top.yukonga.miuix.kmp:miuix-ui` / `miuix-icons` / `miuix-blur` | 0.9.2 | 色盘 / FAB 图标 / 弹窗纹理模糊与高光（与 SportLink 同版对齐；blur AAR minSdk=33，Manifest `tools:overrideLibrary` 放行，低版本自动失效） |
| `dev.rikka.shizuku:api` / `provider` | 13.1.5 | 非 root 机器的 shell 身份取数通道 |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 4.3 | UserService 反射框架接口的 hidden API 豁免（经典元反射配方在 Android 12+ 被堵，TaskFps 桥依赖） |
| `androidx.room:room-runtime` / `room-ktx` | 2.8.4 | 双库落库（私有目录），免写 SQL 与 Cursor 映射 |
| `com.google.devtools.ksp`（插件） | 2.3.9 | Room 注解处理。⚠️ 版本必须与 Kotlin 对齐，改 Kotlin 版本时同步升 |

无网络权限、无数据上报、无第三方统计 SDK。

---

## 已知限制

### 功率监测

- **取数仍需一条特权通道（root 或 Shizuku）才能启动采样**。主进程 `BatteryManager` 兜底通道本身不需要任何权限，但当前它只在特权通道已建立、且 sysfs 不可读时才会被启用；纯无权限设备目前不取数
- **非 root 机器看不到电池卡片**（SOH / 循环次数 / 满充容量取自高通私有节点，shell 身份读不到）。这是有意为之，不做提示、不加占位卡片
- **非 root 机器也没有 PMIC 温度 tab**（趋势卡与全屏页同源过滤，判据是 su 探测结论）。root 机器的 tab 上若因旧版 CSV 缺列而整段无读数，曲线显示为空白、读数与刻度显示「—」，不会用 0 值顶替
- **机型差异**：主要在两台小米真机上标定。其它平台的节点名与单位可能不同，`charge_counter` 量级校准、温感区 `type` 名称、电流符号是最可能出问题的地方
- **导入的数据不落库**。实时采样已每 10s 写入 Room（见 [1.9](#19-采样数据落库与会话生命周期)），但**导入的 CSV 仍只存活于进程内**（`ImportedSeries` 单例），退出查看即丢弃 —— 它本来就来自一个磁盘上的文件，没有二次留存的必要
- **落库会话是临时存档，不是用户资产**：库里最多只保留「最近一次未导出的会话」，且会在用户导出、或开始新一场采样时被删掉。要长期留存就必须导出成 CSV（见 [1.9](#19-采样数据落库与会话生命周期)）
- **一场数据只能留一场**：连测两场而都没导出，第一场会在第二场开始的那一刻被删除 —— 这是「临时存档」语义的必然结果
- **进程被杀最多丢 10 秒采样**（上次 flush 到被杀之间）。再往上加严只能缩短 flush 间隔，代价是写放大
- **内存显示窗口 7200 条**。按 1s 间隔约 2 小时、0.5s 间隔约 1 小时；更早的点被环形淘汰、**不再画在曲线上**，但它们仍在库里、能完整导出（会话统计量也不受影响，见 [1.3](#13-状态页竖屏单列--横屏双列)）
- **图表读数只覆盖内存窗口**：曲线上能按住读数的点就是显示窗口内的点。要看更早的数据请导出 CSV 再导入回看
- **多序列叠加时 Y 轴刻度无统一物理含义**，此时不再画数值刻度，改由图例标量程 + 按住气泡显示真实值 —— 这是量纲不同的必然取舍，不是缺陷
- **`usb/current_now` 多为限流上限而非实测电流**，仅作参考（已作为 `usb_current_limit_ma` 列导出）
- **`SuSession`（root 常驻 shell）未真机验证**，逻辑上有多层兜底并会自动回落到一次性 fork 路径
- **导出把整场会话写进内存的是"页码"而非全量**：导出走分页（每页 5000 行），但单场会话的**行数**仍然决定耗时；十万行级别的超长会话导出时会有可感知的等待（在 IO 线程，不冻界面）

### 帧率监测

- **必须 Shizuku（或 root）**：帧率 / CPU / 前台应用的每条命令都走特权通道，没有零权限兜底。Shizuku 掉线时悬浮 tab 显示「—」，宽限期满自动停表
- **GPU 负载 / 频率节点普遍被 SELinux 拦截**：shell 身份常只剩 `gpubusy` 可读（负载有、频率无），root 机型全通；DDR 频率候选池只有 QCOM bus_dcvs 与 MTK dvfsrc 两类挂点，无候选节点的机器详情页整卡隐藏 —— 这些「选项 / 卡片自动不出现」都是数据自适应的预期行为，不是 bug
- **SF Latency 在不少 ROM 已被砍**（只回周期行）→ 连续 10 次全败自动回落 timestats，判死期间每 5s 自愈重探；要逐帧真值可改用 **FrameTimeline 源**（AOSP 12+ 全机型，见 [2.2](#22-帧率采样源四算法)）
- **FrameTimeline 需要 Android 12+**（低版本无该数据源，选项压暗回落）；trace 会话 256MB 上限 ≈ 1.5h 高帧率录制；perfetto 进程被系统杀时 trace 文件消失 → 连续 10 次判死回落 timestats，判死期间每 5s 自愈重拉
- **TaskFps 依赖 ROM 授权**：需要 Android 11+ 且 `com.android.shell` 持有 `ACCESS_FPS_COUNTER`（AOSP 派生 ROM 一般都有）；未授予的 ROM 该源压暗回落。该源无逐帧时间戳，帧时间 / 卡顿指标整场缺测
- **timestats `-clear` 是全局操作**：清掉 SF 的整个跟踪表，可能干扰设备上其它读 timestats 的组件 —— 已接受的副作用
- **帧率样本 1s 聚合**：实时读数 4Hz，但落库与统计是 1s 粒度，MIN 抓的是秒级低谷而非单帧深谷；单帧尖刺由帧时间 / Jank 卡的逐帧直方图承载
- **计时噪声与口径差**：timestats 源 120Hz 下单拍 ±1.2fps 起，读数与 Scene / 游戏内置可能差 1-5fps，三个机制见 [2.7](#27-统计口径) —— 不是计算 bug（公式已真机审计无系统偏差）；FrameTimeline / SF Latency 源读逐帧真值，无此噪声
- **悬浮 tab 可能被系统冻结打断**：Android 12+ 对缓存进程的冻结前台服务只能缓解、不能根除；进程被冻结后 tab 数字定格，重开悬浮窗即恢复
- **SF 图层 churn**：目标应用的 Surface 结构变化（转场 / 表面重建）期间图层条目可能短暂蒸发，单拍缺测 → 曲线断口（≤3 拍已桥接连线，更长仍断线、网格均匀）

---

## 许可

本项目未声明开源许可证。代码仅供学习与个人使用参考；因依赖特权通道读取系统节点，请自行评估设备风险，由此产生的任何后果由使用者承担。
