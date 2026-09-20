package com.chen.powermeter.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.chen.powermeter.R
import com.chen.powermeter.util.appString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * CSV 导入解析器 —— 支持「打开本应用导出的 CSV 查看历史数据」。
 *
 * 口径严格对齐 [com.chen.powermeter.util.CsvExporter] 写出的 15 列表头：
 * ```
 * timestamp,datetime,voltage_v,voltage_ocv_v,current_ma,fg_current_ma,power_w,
 * temp_battery_c,temp_usb_c,temp_charger_c,soc_pct,status,charge_type,
 * remaining_mah,usb_voltage_v
 * ```
 *
 * 容错点（都是文件经过微信/网盘/Excel 转手后的常见形态）：
 * - UTF-8 BOM 头、CRLF / LF 混用；
 * - 列顺序被调整、列被增删 —— **按列名取值，不按列序号**，故不会错位；
 * - 单元格留空 → 数值列取 null（而非 0），避免把「无数据」画成「0 值曲线」；
 * - 单元格被加双引号；
 * - `power_w` 缺失或留空 → 由 voltage × current 现算，与实时路径口径一致。
 *
 * 与实时采样路径的边界：导入是**只读展示**，不写 [com.chen.powermeter.service.SamplingService]
 * 的实时缓冲、不参与 root 节点采样；退出查看即回到实时数据。
 */
object CsvImporter {

    /**
     * 单文件最大导入行数。
     *
     * 实时缓冲上限 3600（[com.chen.powermeter.data.SampleStore.CAPACITY]），此处放宽 5 倍给长历史文件留余量，
     * 同时挡住「误选了几十万行的表格」把绘制拖垮。
     */
    private const val MAX_ROWS = 20_000

    /**
     * 入口快筛的文件大小上限（32 MB）。
     *
     * 外部打开会注册得很宽（含 content:// 的 MIME 通配兜底），把一部视频/一个安装包丢进来时
     * 不能真的把它读成字符串。20000 行 × 约 150 B ≈ 3 MB，32 MB 留了一个数量级余量。
     */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /**
     * 入口快筛结论。
     *
     * 之所以要它：[read] 会把整个文件读成字符串再按表头校验，对误入的大文件太贵；
     * 先用文件名/大小做一次零成本判断，把「一眼不是 CSV」的情况挡在读文件之前。
     */
    enum class QuickCheck { OK, NOT_CSV, TOO_LARGE }

    /** 导出列的毫秒/秒两种 datetime 形态；先试带毫秒的，不回退丢精度 */
    private val DATETIME_PATTERNS = listOf("yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss")

    /** 解析结果；[skippedRows] = 因时间戳无法解析而丢弃的行数（会在 UI 上提示） */
    data class Result(val samples: List<PowerSample>, val skippedRows: Int)

    /** 文件不可读、缺时间列或缺数据列时抛出，message 直接用于展示 */
    class CsvFormatException(message: String) : Exception(message)

    fun read(context: Context, uri: Uri): Result {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: throw CsvFormatException(appString(R.string.error_csv_unreadable))
        return parse(text)
    }

    /**
     * 入口快筛：在真正读文件之前挡掉「一眼不是 CSV」与超大文件。
     *
     * 为什么需要：对外打开注册得很宽（VIEW 带 content:// 的 MIME 通配兜底），
     * 从「打开方式」点一份视频/安装包也会进到这里，而 [read] 会把整个文件读成字符串。
     * 本函数**只看文件名与大小**，不看内容 —— 内容正确性由 [parse] 的表头校验兜底，
     * 两层职责不重叠。
     *
     * 拿不到文件名时返回 [QuickCheck.OK] 放行：SAF 的 content:// 末段常是文档 ID（如 "1234"），
     * 拿它当文件名会把正经 CSV 误杀，宁可多读一次文件也不能误拒。
     *
     * ⚠️ 会走 provider 查询，**必须在 IO 线程调用**。
     */
    fun quickCheck(context: Context, uri: Uri): QuickCheck {
        val (name, size) = readMeta(context, uri)
        if (size > MAX_BYTES) return QuickCheck.TOO_LARGE
        if (name.isNullOrEmpty()) return QuickCheck.OK
        return if (name.endsWith(".csv", ignoreCase = true)) QuickCheck.OK else QuickCheck.NOT_CSV
    }

    /** 取显示名与大小；取不到即 null / -1，由调用方决定是否放行 */
    private fun readMeta(context: Context, uri: Uri): Pair<String?, Long> {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            var name: String? = null
            var size = -1L
            runCatching {
                context.contentResolver
                    .query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                    }
            }
            // provider 不支持查询或未授权时保持 null/-1 → 调用方放行，交给内容校验
            return name to size
        }
        // file:// 等：路径末段就是文件名
        return uri.lastPathSegment?.substringAfterLast('/') to -1L
    }

    fun parse(text: String): Result {
        val lines = text.split('\n')

        // 第一条非空行即表头。BOM 只可能出现在文件首行，但逐行 removePrefix 成本可忽略，
        // 也顺便容忍「首行为空、BOM 落在第二个非空行」这种被编辑器动过手脚的文件。
        val headerLineIdx = lines.indexOfFirst { it.removePrefix("\uFEFF").isNotBlank() }
        if (headerLineIdx < 0) throw CsvFormatException(appString(R.string.error_csv_empty))

        val header = lines[headerLineIdx]
            .removePrefix("\uFEFF")
            .split(',')
            .map { it.trim().trim('"').lowercase(Locale.US) }
        // 按列名建索引：重复列名后者覆盖前者（导出格式无重名列，此处只是防脏数据）
        val at = HashMap<String, Int>(header.size)
        header.forEachIndexed { index, name -> if (name.isNotEmpty()) at[name] = index }

        if ("timestamp" !in at && "datetime" !in at) {
            throw CsvFormatException(appString(R.string.error_csv_missing_time_column))
        }
        if (listOf("voltage_v", "current_ma", "power_w").none { it in at }) {
            throw CsvFormatException(appString(R.string.error_csv_missing_data_column))
        }

        val capacity = (lines.size - headerLineIdx - 1).coerceIn(0, MAX_ROWS)
        val out = ArrayList<PowerSample>(capacity)
        var skipped = 0

        for (i in headerLineIdx + 1 until lines.size) {
            if (out.size >= MAX_ROWS) break
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val cells = line.split(',')

            val timeMillis = parseTime(cells, at)
            if (timeMillis == null) {
                skipped++
                continue
            }

            val voltage = num(cells, at, "voltage_v") ?: 0.0
            val current = num(cells, at, "current_ma") ?: 0.0
            out += PowerSample(
                timeMillis = timeMillis,
                voltageV = voltage,
                // 无 OCV 列时回落端电压：OCV 只用于内阻展示，不影响曲线可用性
                voltageOcvV = num(cells, at, "voltage_ocv_v") ?: voltage,
                currentMa = current,
                fgCurrentMa = num(cells, at, "fg_current_ma"),
                // power_w 留空/缺列时现算，避免出现「有电压电流但功率为 0」的假曲线
                powerW = num(cells, at, "power_w") ?: (voltage * current / 1000.0),
                tempBatteryC = num(cells, at, "temp_battery_c") ?: 0.0,
                tempUsbC = num(cells, at, "temp_usb_c"),
                tempChargerC = num(cells, at, "temp_charger_c"),
                // 以下三列导出格式中不存在 → 固定 null，UI 侧 f3OrDash() 会显示 "—"
                tempPmicC = null,
                socPct = num(cells, at, "soc_pct")?.toInt() ?: 0,
                status = str(cells, at, "status") ?: "Unknown",
                chargeType = str(cells, at, "charge_type") ?: "",
                remainingMah = num(cells, at, "remaining_mah"),
                fullMah = null,
                usbVoltageV = num(cells, at, "usb_voltage_v"),
                usbCurrentLimitMa = null,
            )
        }

        if (out.isEmpty()) {
            throw CsvFormatException(appString(R.string.error_csv_no_valid_rows, skipped))
        }
        // 曲线 X 轴按时间比例映射，乱序文件会让折线回折，故统一升序
        out.sortBy { it.timeMillis }
        return Result(out, skipped)
    }

    /**
     * 时间戳：优先 `timestamp`（完整毫秒 epoch，本应用导出列），
     * 其次 `datetime`。两者都解析不出时返回 null，该行计为丢弃。
     */
    private fun parseTime(cells: List<String>, at: Map<String, Int>): Long? {
        at["timestamp"]?.let { index ->
            cells.getOrNull(index)?.trim()?.trim('"')?.toLongOrNull()?.let { return it }
        }
        at["datetime"]?.let { index ->
            val raw = cells.getOrNull(index)?.trim()?.trim('"')
            if (!raw.isNullOrEmpty()) {
                for (pattern in DATETIME_PATTERNS) {
                    // SimpleDateFormat 非线程安全，且导入是一次性动作，按需新建（不做缓存）
                    val parsed = runCatching {
                        SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(raw)
                    }.getOrNull()
                    if (parsed != null) return parsed.time
                }
            }
        }
        return null
    }

    /** 数值列：列缺失 / 单元格为空 / 无法解析 三者统一返回 null（调用方决定回落策略） */
    private fun num(cells: List<String>, at: Map<String, Int>, key: String): Double? {
        val index = at[key] ?: return null
        val raw = cells.getOrNull(index)?.trim()?.trim('"') ?: return null
        if (raw.isEmpty()) return null
        return raw.toDoubleOrNull()
    }

    /** 文本列：空串视为 null，便于调用方区分「空值」与「有值的空字符串」 */
    private fun str(cells: List<String>, at: Map<String, Int>, key: String): String? {
        val index = at[key] ?: return null
        val raw = cells.getOrNull(index)?.trim()?.trim('"') ?: return null
        return raw.ifEmpty { null }
    }
}

/**
 * 「查看中」的导入序列（进程内单例）。
 *
 * 与 [com.chen.powermeter.data.SampleStore]（实时环形缓冲）**并列且互不写入**：
 * UI 统一按 `if (imported 非空) imported else live` 取数源，因此
 * ① 查看历史文件时实时采样照常进行、不被覆盖；
 * ② [clear] 之后自动回到实时曲线，无需任何额外状态同步。
 *
 * 与实时缓冲一致的取舍：仅存活于进程内，不做落库。
 */
object ImportedSeries {

    private val _samples = MutableStateFlow<List<PowerSample>>(emptyList())
    val samples: StateFlow<List<PowerSample>> = _samples.asStateFlow()

    private val _fileName = MutableStateFlow("")
    val fileName: StateFlow<String> = _fileName.asStateFlow()

    fun set(fileName: String, samples: List<PowerSample>) {
        _fileName.value = fileName
        _samples.value = samples
    }

    fun clear() {
        _samples.value = emptyList()
        _fileName.value = ""
    }
}
