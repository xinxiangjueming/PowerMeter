# PowerMeter · 安卓底层充放电功率监测

> 通过 **root** 直读内核电量节点，实时记录手机电池的充放电功率、电压、电流与温度，支持锁屏常驻采样、趋势曲线缩放叠加、CSV 导出与回看。
>
> 纯 Jetpack Compose + Material 3 Expressive，无第三方数据层，无网络权限。

---

## 目录

- [1. 这是什么](#1-这是什么)
- [2. 功能总览](#2-功能总览)
- [3. 数据来源：内核节点](#3-数据来源内核节点)
- [4. 架构与模块地图](#4-架构与模块地图)
- [5. 关键技术决策与踩坑记录](#5-关键技术决策与踩坑记录)
- [6. CSV 数据格式](#6-csv-数据格式)
- [7. 构建与运行](#7-构建与运行)
- [8. 依赖清单](#8-依赖清单)
- [9. 已知限制](#9-已知限制)

---

## 1. 这是什么

大多数「电池检测」应用只能读到 Android 框架层 `BatteryManager` 给的那几个粗糙字段（`EXTRA_VOLTAGE`、`EXTRA_TEMPERATURE`），拿不到**瞬时功率**，也无法区分「充电档位上限」和「实际功率」。

PowerMeter 换了一条路：以 root 权限读取内核 `sysfs` 节点（高通平台的 `/sys/class/power_supply/battery`、私有节点 `/sys/class/qcom-battery`、温感区 `/sys/class/thermal`），把电压、电流、温度换算成统一应用层单位，由此**自行计算功率**（`P = U × I`），并以 500ms 起的间隔持续采样、绘成趋势曲线。

一句话定位：**给充电头、数据线、快充协议做量化验证的仪器型工具**。

> 数据源为高通 PMIC 电量计（fuel gauge）。当前实现基于 **Xiaomi 22081212C / SM8475（taro）实测**标定；其它机型多数节点同名可用，但单位与语义存在差异，见 [3.3 两个实测陷阱](#33-两个实测陷阱)。

---

## 2. 功能总览

### 2.1 实时采样

| 能力 | 说明 |
| --- | --- |
| 前台服务 | `SamplingService`（`foregroundServiceType="specialUse"`），锁屏后继续采样 |
| 常驻通知 | 实时显示 `功率 · 电压 · 电流 · 温度`，标题为「充电中 / 放电中 · SOC」；刷新节流 800ms，避免刷屏 |
| 采样间隔 | 0.5s / 1s / 2s / 5s 四档，持久化到 `SharedPreferences` |
| 锁屏保持 | 可选 `PARTIAL_WAKE_LOCK`，息屏后采样更连续，耗电略增 |
| 缓冲上限 | 3600 条（环形淘汰，只保留最近 3600 个样本） |
| 权限 | 首次启动采样时按需申请 `POST_NOTIFICATIONS`（Android 13+），拒绝则提示原因 |
| 错误反馈 | root 不可用、节点读取失败、解析失败三种情形均在页面顶部以错误卡明示 |

### 2.2 状态页（竖屏单列 / 横屏双列）

- **主功率卡**：56sp 等宽字体大号功率读数，充电用 `primary`、放电用 `tertiary` 着色；副行显示 `SOC · charge_type · 剩余 mAh`
- **指标网格**：电压 / 电流 / 电池温度 / 接口温度 / 开路电压 / 充电 IC 温度
- **趋势卡**：功率、电压、电流、温度四指标可切换；曲线色可自定义；可一键进入全屏
- **统计卡**：样本数、时长、平均功率、峰值充电/放电、电压区间、最高温度、累计充入/放出（mAh 与 Wh，梯形积分）
- **电池卡**：型号、技术、健康度 SOH、循环次数、满充容量、设计容量、最大充电档位
- **控制条**：开始 / 停止采样、导出 CSV
- **设置面板**：底部上滑 bottom sheet —— 采样间隔、锁屏保持开关、清空采样数据

### 2.3 趋势曲线

承载四项能力的组合，各调用点按需开启：

1. **多序列叠加**（全屏页）：各曲线按**自身在可见窗口内的量程**归一化到绘图区，实现不同量纲指标（W / V / mA / ℃）同屏对比
2. **曲线下方同色渐变填充**：锚定在该序列自身量程上下界，标准面积图形态；**仅单曲线时绘制**
3. **按住读数**：竖线 + 数据点 + 悬浮气泡，气泡含时间与真实数值
4. **双指缩放 + 单指平移 + 底部滑条**：仅全屏页启用

X 轴按**时间比例**映射（预计算归一化时间分数），而非按下标均分 —— 导入的历史 CSV 常出现采样间隔突变（如两次充电会话被拼进同一文件），按下标均分会把时间轴画歪。

### 2.4 全屏趋势页

独立 `Activity`，进入即**强制横屏**（`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，允许 180° 翻转跟随重力）：

- **真沉浸**：隐藏状态栏与手势导航条，从屏幕边缘上滑可瞬时唤出；背景铺满全屏、内容延伸到系统栏之下
- **避让挖孔**：窗口声明 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`（否则默认模式会在挖孔侧留黑带），再交由 Compose 的 `WindowInsets.displayCutout` 全边避让 —— 竖屏顶部中央、横屏左右侧一次覆盖
- 指标**多选**叠加对比，胶囊上带色点标识对应曲线色；至少保留一条（不允许清空）
- 进入 / 返回走主题声明的四向水平滑动过渡（300ms decelerate）

### 2.5 CSV 导出与回看

- **导出**：写入系统 `MediaStore.Downloads`，落在 `Download/PowerMeter/powermeter_yyyyMMdd_HHmmss.csv`（Android 10+ 无需存储权限）。导出的是**「当前所看的那一份」** —— 查看导入文件时导出的是该文件数据，与界面所见一致
- **导入**：从系统「打开方式」或「分享」打开 CSV，以只读方式装载为「查看态」数据源；页面顶部出现醒目提示条标出来源与条数，附「退出查看」按钮
- **查看态与实时态互不干扰**：查看历史文件时实时采样照常进行，两者写入不同的 `StateFlow`，UI 统一按 `if (导入非空) 导入 else 实时` 取数。退出查看即自动回到实时曲线

---

## 3. 数据来源：内核节点

### 3.1 节点清单

| 目录 | 读取字段 | 用途 |
| --- | --- | --- |
| `/sys/class/power_supply/battery` | `capacity` `status` `charge_type` `health` `technology` `model_name` `voltage_now` `voltage_ocv` `current_now` `temp` `charge_full` `charge_full_design` `charge_counter` `cycle_count` | 电压、电流、温度、SOC、状态、快充档位 |
| `/sys/class/qcom-battery` | `fg1_ai` `fg1_rm` `fg1_fcc` `fg1_soh` `fg1_cycle` `connector_temp` `power_max` `real_type` `usb_real_type` `typec_mode` | 燃料计寄存器（权威容量 / SOH / 循环数）、接口温度、快充协议 |
| `/sys/class/power_supply/usb` | `voltage_now` `current_now` `online` `type` | USB 输入侧电压与限流 |
| `/sys/class/thermal/thermal_zone*` | `temp`（按 `type` 匹配 `battery` / `usb` / `charger_therm0` / `pm8350c_tz` / `pm8350b_tz`） | 电池口 / Type-C / 充电 IC / PMIC 温度 |

实现上，`RootPowerReader` 只在首次调用时执行一次 `id -u` 判定 root 可用性（结果缓存），并一次性建立 `thermal_zone` 的 `type → zone 编号` 索引（避免每次采样遍历上百个 zone）；此后每次采样都只发**一条** `su -c` 组合 shell，把四组节点合并读取后一次解析，把进程启动开销摊薄到一个采样周期。

### 3.2 单位换算

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

### 3.3 两个实测陷阱

这两条直接影响数据正确性，都已在 `RootPowerReader` 中以注释形式留证：

1. **`battery/power_now` 与 `power_avg` 不可用**
   在本机为**恒定值**（`10000000` / `5000000`），是充电档位上限而非实测功率。
   → 功率必须由 `voltage_now × current_now` 自行计算。

2. **`battery/charge_counter` 单位是 mAh 而非标准 µAh**
   实测 `charge_counter = 4454`，而燃料计 `fg1_rm = 4457000` µAh（= 4457 mAh）。
   → 剩余容量优先取 `fg1_rm`；回退 `charge_counter` 时按设计容量做 1000 倍量级校准。

---

## 4. 架构与模块地图

```
app/src/main/kotlin/com/chen/powermeter/
├── MainActivity.kt              253 行  入口：权限、外部 Intent 分发、导出、配置变更重放
├── data/
│   ├── PowerSample.kt            70 行  采样快照 PowerSample / 会话统计 SessionStats
│   ├── RootPowerReader.kt       264 行  root 读节点 + 单位换算 + 解析（唯一数据来源）
│   └── CsvImporter.kt           263 行  CSV 解析 + ImportedSeries（导入态单例）
├── service/
│   └── SamplingService.kt       211 行  前台服务：采样循环、常驻通知、WakeLock
├── ui/
│   ├── PowerMeterScreen.kt      956 行  主页面（竖屏单列 / 横屏双列）、Metric 枚举、各卡片
│   ├── TrendChartView.kt        817 行  图表组件：多序列 / 填充 / 读数 / 缩放 / 滑条
│   ├── TrendFullscreenActivity.kt 334 行 趋势全屏页（沉浸式 + 挖孔避让）
│   ├── ColorPickerDialog.kt     290 行  ChartColors 仓库 + 颜色选择面板（miuix ColorPalette）
│   ├── common/BlurTopBar.kt     115 行  顶栏三档模糊封装
│   └── theme/
│       ├── Theme.kt              87 行  Material 3 Expressive 主题 + 底色压深
│       └── CornerRadius.kt       37 行  LocalCornerRadius（小米读物理圆角，其余 28dp）
└── util/
    ├── EdgeToEdge.kt            153 行  NavigationBarHelper：真沉浸 / 重放链
    ├── CsvExporter.kt            76 行  导出 CSV 到 MediaStore.Downloads
    └── Prefs.kt                  49 行  SharedPreferences 封装
```

Kotlin 约 **3975 行**，15 个文件；资源 12 个文件。

### 数据流

```
sysfs 节点
   │  su -c（单次组合 shell，IO 线程）
   ▼
RootPowerReader.read() ──► PowerSample
   │
   ▼
SamplingService.samples (StateFlow, 上限 3600)   ImportedSeries.samples (StateFlow, 上限 20000)
   └──────────────────────┬──────────────────────────────────────┘
                          ▼  UI: if (导入非空) 导入 else 实时
                   PowerMeterScreen / TrendFullscreenActivity
                          ▼
                     TrendChartView (Canvas)
```

两个数据源**并列且互不写入**，因此「查看历史文件」不会覆盖正在进行的实时采样，「清空采样数据」也不会把导入的数据一起清掉。

---

## 5. 关键技术决策与踩坑记录

这些是本项目在真实设备上验证过的结论，直接影响了代码结构，也标在原文件注释里。

### 5.1 顶栏三档模糊（`ui/common/BlurTopBar.kt`）

按 API 分级降级，同一份顶栏内容三路复用：

| API | 方案 | 效果 |
| --- | --- | --- |
| ≥ 33 | `com.kyant.backdrop` + AGSL 着色器 `ProgressiveBlurAlphaMask` | 顶部全模糊 → 底部渐隐至透明的玻璃质感 |
| 31 – 32 | Haze（`RenderEffect` 可用区间） | 真实高斯模糊 |
| 26 – 30 | `surface` 色纵向渐变盖板 | 渐变假模糊（非真实模糊） |

**结构铁律**：采样源（`hazeSource` / `layerBackdrop`）挂在**内容层**，顶栏做**兄弟节点**覆盖。模糊节点一旦位于被采样的层内部就会自我引用 —— 这是 MIUI/HyperOS 上 `MiBackgroundBlurBlend` 崩溃的根因（同类教训在其它项目同样适用）。

内容层的 `padding(top = topBarHeight)` 必须排在 `verticalScroll` **之后**，否则内容不会从顶栏下方穿过。

> 依赖处理：`io.github.kyant0:backdrop:2.0.1` 是 Compose Multiplatform 制品，在纯 androidx Compose 项目里必须 `exclude(group = "org.jetbrains.compose.foundation")` 与 `exclude(group = "org.jetbrains.compose.ui")`，否则出包时重复类冲突。2.0.1 的 `BackdropEffectScope` 也不再暴露 `downsampleScale`，着色器尺寸直接传实际像素尺寸。

### 5.2 真沉浸（`util/EdgeToEdge.kt`）

「透明系统栏」与「真沉浸」是两件事。本项目的口径是：**背景铺满全屏、内容延伸到手势条之下，手势条浮在内容上**；而用 `safeDrawing` 做全边避让只会把系统栏区域换成一条背景色带 —— 观感上就是「小白条没沉浸」。

三个必须处理的点：

1. `enableEdgeToEdge()` 在最后一步会把 `isNavigationBarContrastEnforced` 置为 `true`（`SystemBarStyle.auto()` 且 nightMode 为 `MODE_NIGHT_AUTO` 时），浅色模式会盖一层不透明白色遮罩 → 必须在调用后显式置透明并关闭 contrast
2. `setDecorFitsSystemWindows(window, false)` **无条件**压回 —— 旋转后 MIUI/HyperOS 会把窗口重新按系统栏避让，重放链里没有人压回去就会退回「避让状态」
3. 状态栏与导航栏的明暗（`APPEARANCE_LIGHT_*`）**必须同时设置**，mask 也要同步包含导航栏那一位，否则清除时清不掉

重放链：`onConfigurationChanged` + `decorView.post` 一帧兜底 + decorView insets 监听（覆盖 Android 12+ 的 180° 翻转不回调 `onConfigurationChanged` 的情形）。注意**不在 insets 回调里重放 `enterImmersive()`** —— 用户上滑唤出的瞬时系统栏会触发 insets 回调，若在此重新 `hide`，刚唤出的手势条会被立刻收回（手势无反馈）。

### 5.3 `configChanges` 里有意不含 `uiMode`

`MainActivity` 与 `TrendFullscreenActivity` 均声明：

```
orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|fontScale
```

旋转**不重建** Activity，窗口层设置由重放链恢复。但**有意不含 `uiMode`** —— 深浅色切换需要重建 Activity 以重跑 `onCreate` 里的窗口设置。

> 曾试过去掉 `orientation|screenSize` 改走「旋转重建」，实测**「设备横握冷启动照样不复现修复」**，证明问题与旋转路径无关，已回滚。

### 5.4 一次性 Intent 必须用 `savedInstanceState == null` 兜住

CSV 的 `VIEW` / `SEND` Intent 是**一次性**的，但深浅色切换、字体缩放、进程被杀后恢复都会重建 Activity 并把 intent 原样带回 `onCreate`。不拦一道就会重新解析整份文件、再弹一次 Toast。

配合 `launchMode="singleTop"` + `onNewIntent`，应用运行中再从外部打开 CSV 不会在栈顶叠出第二个实例。

### 5.5 CSV 导入：注册宽 + 入口严

Android 上 CSV 的 MIME 类型极不统一（`text/csv` / `text/comma-separated-values` / `application/csv` / `application/vnd.ms-excel` / `application/octet-stream` 都见过），因此采取「**注册得宽、入口校验得严**」策略。Manifest 中四段 `intent-filter` 各司其职，合并任意两段都会漏或误伤：

| # | 类型 | 作用 | 注意 |
| --- | --- | --- | --- |
| ① | `VIEW` + 五种 MIME | 常规命中路径 | — |
| ② | `VIEW` + `scheme`/`pathPattern` | 专收「无 type 但路径带 `.csv`」的 Intent | **不能声明 type**：同一 filter 内 type 与 scheme 是「与」关系 |
| ③ | `VIEW` + `content://` + `*/*` | 任何 content 文件都进候选 | 副作用已知并接受：图片/视频的「打开方式」里也会出现本应用，点进去被 `quickCheck` 拦下 |
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

### 5.6 图表手势：`pointerInput` 的 key 必须是 `Unit`

手势用 `awaitEachGesture` 自写：单指 = 读数、≥2 指 = 缩放/平移。

`pointerInput` 的 key 必须恒为 `Unit`，几何量通过 `rememberUpdatedState` 持有 —— 把几何量当 key 会让每一步缩放都重建协程，捏合手势当场断掉。

`TrendChartState` 用**窗口**（归一化时间 `[0, 1]`）而非缩放系数建模，让捏合、平移、滑条三种操作落到同一组状态上，避免反复做系数 ↔ 窗口换算，也更容易在 0 / 1 边界上算出越界窗口。最多放大 50 倍（`MIN_SPAN = 0.02`）。

卡片内嵌与全屏两种模式的分工：`state != null` = 全屏（可缩放 + **单指直接**读数）；`state == null` = 卡片内嵌（不可缩放 + **长按**后拖动读数）—— 单指直接拖动会与页面竖直滚动抢手势。

### 5.7 其它已验证事项

- **文字用 Compose 节点而非 Canvas 绘制**：本项目 Compose 版本下 `DrawScope.drawText` / `nativeCanvas` 已不存在，刻度文字、读数气泡一律走 Compose 文本节点
- **数字统一 `FontFamily.Monospace`**：MIUI/HyperOS 默认字体 MiSans 的等宽数字 tag 是自定义的 `thum`，标准 OpenType `tnum` 在小米设备上不生效
- **颜色胶囊文字色按 `Color.luminance()` 反算**：色值由用户自由指定，写死白色会在浅色底（黄、白）上完全看不见
- **`Prefs.getMetricColor` 返回 `null` 表示未自定义**：必须区分「未设置」与「设成黑色」，故先用 `contains` 判存在性，不能用 `getInt` 的默认值
- **水波纹规范**：`clip` 必须紧贴 `clickable` **之前**，ripple 以节点矩形为边界，否则圆形色块外会溢出方形水波纹
- **底部 sheet 自绘拖拽横条**：`dragHandle = null`，规避 M3 默认手柄长按弹出的「拖动手柄」tooltip
- **页面底色压深一档**（`Theme.withDeeperBackground`）：M3 baseline / dynamic scheme 中 `background` 与卡片填充色只差一个 tonal step（夜间 `#1C1B1F` vs `#211F26`），视觉上连成一片，卡片边界全靠阴影撑
- **主题过渡动画昼夜两份都要挂**：过渡动画可能取自打开方或被打开方的主题，只在 `values` 声明的话夜间会退回系统默认
- **Kotlin 注释里禁止出现 `*/`**：包括夹在反引号里的通配 MIME 字面量 —— 会提前结束 KDoc，后续文字被当代码解析，报出上百条 `Expecting member declaration`

---

## 6. CSV 数据格式

### 导出（15 列，UTF-8，LF）

```csv
timestamp,datetime,voltage_v,voltage_ocv_v,current_ma,fg_current_ma,power_w,temp_battery_c,temp_usb_c,temp_charger_c,soc_pct,status,charge_type,remaining_mah,usb_voltage_v
1763692800123,2026-09-21 02:40:00.123,4.312,4.355,2841.500,2803.200,12.253,33.400,31.200,38.900,78,Charging,Fast,3455.000,5.102
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
| `temp_battery_c` | ℃ | 电池温度 |
| `temp_usb_c` | ℃ | Type-C 接口温度 |
| `temp_charger_c` | ℃ | 充电 IC 温度 |
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

## 7. 构建与运行

### 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17+（Gradle daemon 工具链由 `gradle/gradle-daemon-jvm.properties` 指定为 25） |
| Gradle | 9.5.1（wrapper 自带） |
| Android SDK | compileSdk **37**，需安装对应 Platform 与 Build-Tools |
| AGP / Kotlin | 9.2.1 / 2.4.0 |

### 配置 SDK 路径

`local.properties`（**不入库**，需自行创建）：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 构建命令

```bash
# 日常快速编译验证（推荐，约 1 分钟）
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

1. 设备需已 **root**（Magisk / KernelSU 等均可，`su` 需可被 `ProcessBuilder` 调起）
2. 首次点「开始采样」时会申请通知权限（Android 13+），授予后前台服务启动
3. root 未授权时页面顶部显示错误卡，说明具体原因
4. 要查看历史数据：把导出的 CSV 从文件管理器「用其它应用打开」或直接「分享」给本应用即可

### Release 构建注意事项

`buildTypes.release` 已开启 R8（`isMinifyEnabled` + `isShrinkResources`），规则见 `app/proguard-rules.pro`。本项目不使用反射、序列化、注解处理器与 JNI，因此无需额外 `-keep` 类；唯一「反射式」调用是按资源名查平台 dimen（`resources.getIdentifier("rounded_corner_radius_top", ...)`），不涉及应用内类名，R8 不影响其行为。

出包后建议归档 `app/build/outputs/mapping/release/mapping.txt`，用于还原 Release 崩溃堆栈。

---

## 8. 依赖清单

版本集中在 `gradle/libs.versions.toml`。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.android.application` (AGP) | 9.2.1 | 构建插件（AGP 9 起内置 Kotlin 支持，无需 `kotlin.android`） |
| `org.jetbrains.kotlin.plugin.compose` | 2.4.0 | Compose 编译器插件 |
| Compose BOM | 2026.09.00 | Compose 版本对齐 |
| `androidx.compose.material3:material3` | 1.5.0-alpha27 | **Expressive API 自 1.5.0-alpha19 起可用**，稳定版 1.4.0 不含 |
| `androidx.activity:activity-compose` | 1.13.0 | `enableEdgeToEdge` / Activity 结果 API |
| `androidx.core:core-ktx` | 1.18.0 | `ServiceCompat` / `NotificationCompat` |
| `androidx.graphics:graphics-shapes-android` | 1.0.1 | 形状工具 |
| `dev.chrisbanes.haze:haze` | 1.7.2 | API 31–32 顶栏真实模糊 |
| `io.github.kyant0:backdrop` | 2.0.1 | API ≥ 33 渐进式 AGSL 模糊（**需 exclude CMP 传递依赖**） |
| `top.yukonga.miuix.kmp:miuix-ui` | 0.9.2 | 曲线颜色色盘 `ColorPalette` |

无网络权限、无数据上报、无第三方统计 SDK。

---

## 9. 已知限制

- **必须 root**。非 root 设备无法读取私有节点，应用无法提供任何数据
- **仅实测标定一款机型**（Xiaomi 22081212C / SM8475 taro，Android 16）。其它高通机型的节点名与单位可能不同，`charge_counter` 量级校准、温感区 `type` 名称是最可能出问题的地方
- **数据不落库**。实时缓冲与导入数据均只存活于进程内（`StateFlow` 单例），进程被杀即丢失；需要留存请先导出 CSV
- **实时缓冲上限 3600 条**。按 1s 间隔约 1 小时，0.5s 间隔约 30 分钟，更早的数据被环形淘汰
- **多序列叠加时 Y 轴刻度无统一物理含义**，此时不再画数值刻度，改由图例标量程 + 按住气泡显示真实值 —— 这是量纲不同的必然取舍，不是缺陷
- **`temp_pmic_c`、`full_mah`、`usb_current_limit_ma` 未纳入 CSV**，导入后对应位置显示 `—`
- **`usb/current_now` 多为限流上限而非实测电流**，仅作参考
- **无 i18n**。仅 `values/strings.xml`（应用名），UI 文案硬编码在 Kotlin 中

---

## 许可

本项目未声明开源许可证。代码仅供学习与个人使用参考；因依赖 root 权限读取系统节点，请自行评估设备风险，由此产生的任何后果由使用者承担。
