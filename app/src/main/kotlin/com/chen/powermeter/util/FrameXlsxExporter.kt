package com.chen.powermeter.util

import android.os.Build
import com.chen.powermeter.data.FrameSample
import com.chen.powermeter.data.db.FrameFpsSampleEntity
import com.chen.powermeter.data.db.FrameSession
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * 帧率记录的**原生节奏 xlsx** 导出器（2026-09-27 起，取代 Kite 兼容格式 —— 用户口径：
 * "导出按库里面时间的数据来导出，不需要兼容 Kite 的 1s 一次数据"）。
 *
 * 行节奏 = **库里最细的采集节奏**：
 * - 新会话（子拍表有数据）：一行一个 250ms 子拍，时间取帧率子拍点的打点时刻 —— FPS 列
 *   本子拍的值；CPU 各列按**最近邻单调配对**的 CPU 快样（fps 与 cpu 在同一子拍内打点，
 *   时刻相差 100~200ms，配对阈值 500ms，超阈 = 该子拍无 CPU 数据留空）；功率 / 电压 /
 *   电流 / 各温度 / 电量 / GpuLoad 是 1s 粒度，把"所在秒"的 1s 样本值重复填在本秒的
 *   4 行上（表头标注 [1s]，做秒级对齐分析时自行去重）；
 * - 旧会话（子拍表为空）：回退一行一个 1s 样本 —— 那时库里的原生节奏就是 1s。
 *
 * 时间格式 `yyyy-MM-dd_HH:mm:ss.SSS`（毫秒精度 —— 250ms 行距下秒级时间戳会 4 行同秒）。
 * 文件名 `PowerMeter_yyyyMMdd_HH_mm_ss.xlsx`（不再冒充 Kite 命名：桌面 Kite 自产文件
 * 与本应用导出曾因同名前缀混淆，导致一轮"Kite 没这么低"的误判，见 2026-09-27 记录）。
 *
 * 实现说明：手写 OpenXML 最小集（[Content_Types].xml + rels + workbook + sheet，
 * 字符串用 inlineStr，无需 sharedStrings/styles），零第三方依赖 —— Apache POI 单是
 * 打进 APK 就 10MB 级。产物 Excel / WPS 均可直接打开。
 *
 * ⚠️ 必须在后台线程调用（内含压缩与字符串拼接，4Hz × 30 分钟 ≈ 7200 行 × 30 列）。
 */
object FrameXlsxExporter {

    /**
     * 分享文件名：`PowerMeter_20260927_130500.xlsx`（取会话开始时刻）。
     */
    fun fileName(session: FrameSession): String =
        "PowerMeter_${SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.US).format(Date(session.startTime))}.xlsx"

    /**
     * CPU 快样行的导出视图 —— util 层不依赖 ui 的 CpuPoint / db 实体，调用方映射。
     * corePct / mhz 下标 = 核心号；null = 该核本拍无有效读数。
     */
    class CpuExportRow(
        val timeMillis: Long,
        val totalPct: Double?,
        val corePct: List<Double?>,
        val mhz: List<Double?>,
    )

    fun build(
        session: FrameSession,
        samples: List<FrameSample>,
        fpsPoints: List<FrameFpsSampleEntity>,
        cpuRows: List<CpuExportRow>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("xl/workbook.xml", WORKBOOK)
            entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            entry("xl/worksheets/sheet1.xml", buildSheet(session, samples, fpsPoints, cpuRows))
        }
        return out.toByteArray()
    }

    // ---- sheet 组装 ----

    private fun buildSheet(
        session: FrameSession,
        samples: List<FrameSample>,
        fpsPoints: List<FrameFpsSampleEntity>,
        cpuRows: List<CpuExportRow>,
    ): String {
        val sb = StringBuilder(XML_HEADER)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        // 列宽：时间列放得下毫秒级时间戳，数值列不挤成 ####
        sb.append("<cols>")
        sb.append("<col min=\"2\" max=\"2\" width=\"24\" customWidth=\"1\"/>")
        sb.append("<col min=\"3\" max=\"30\" width=\"13\" customWidth=\"1\"/>")
        sb.append("</cols>")
        sb.append("<sheetData>")

        // 元信息（行 1/2；行 3/4 汇总；行 5 留空；行 6 表头）
        row(sb, 1) {
            str(1, "Package Name")
            str(2, session.packageName)
        }
        row(sb, 2) {
            str(1, "Device Type")
            str(2, Build.MODEL)
            str(3, "Cadence")
            str(4, if (fpsPoints.isNotEmpty()) "FPS/CPU 250ms, power/temp 1s" else "1s")
        }
        row(sb, 3) {
            str(1, "Stat")
            str(2, "Avg(FPS)")
            str(3, "Avg(Power)[mW]")
            str(4, "Sum(Battery)[mWh]")
        }
        row(sb, 4) {
            num(2, session.avgFps, 2)
            avgPowerMw(samples)?.let { num(3, it, 2) }
            batteryMwh(samples)?.let { num(4, it, 2) }
        }

        // 表头（行 6）。[1s] 标注 = 1 秒粒度、在本秒的 4 个子拍行上重复
        val headers = buildList {
            add("Num"); add("Time"); add("FPS")
            add("FrameSpace(ms)[1s]"); add("MissedFrames[1s]")
            add("Power(mW)[1s]"); add("Voltage(mV)[1s]"); add("Current(mA)[1s]")
            add("BatTemp(°C)[1s]"); add("VirTemp(°C)[1s]"); add("GpuTemp(°C)[1s]")
            add("Capacity(%)[1s]"); add("GpuLoad(%)[1s]")
            add("CPU(%)")
            for (i in 0..7) add("CPU$i(%)")
            for (i in 0..7) add("CPU$i(MHz)")
        }
        row(sb, 6) { headers.forEachIndexed { i, h -> str(i + 1, h) } }

        // 列号常量（1 起）：与 headers 顺序一一对应
        val colFrameSpace = 4
        val colPower = 6

        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss.SSS", Locale.US)

        if (fpsPoints.isNotEmpty()) {
            // ── 新会话：一行一个 250ms 帧率子拍 ──
            var ci = 0
            var si = 0
            fpsPoints.forEachIndexed { index, fp ->
                val t = fp.timeMillis
                // CPU 快样最近邻单调配对（同子拍打点，时刻差 100~200ms）
                while (ci + 1 < cpuRows.size &&
                    abs(cpuRows[ci + 1].timeMillis - t) <= abs(cpuRows[ci].timeMillis - t)
                ) ci++
                // 所在秒的 1s 样本（时间上最后一条 ≤ t 的样本；行在首样本前则无上下文）
                while (si + 1 < samples.size && samples[si + 1].timeMillis <= t) si++
                val s = samples.getOrNull(si)?.takeIf { it.timeMillis <= t }
                // 配对阈值：CPU 快样名义节奏 250ms，超 500ms 视为该子拍无 CPU 数据
                val c = cpuRows.getOrNull(ci)?.takeIf { abs(it.timeMillis - t) <= 500 }

                row(sb, index + 7) {
                    num(1, (index + 1).toDouble(), 0)
                    str(2, timeFmt.format(Date(t)))
                    fp.fps?.let { num(3, it, 2) }
                    if (s != null) {
                        num(colFrameSpace, s.frameSpaceMs, 2)
                        num(5, s.missedFrames.toDouble(), 0)
                        s.powerMw?.let { num(colPower, it, 2) }
                        s.voltageMv?.let { num(7, it, 0) }
                        s.currentMa?.let { num(8, it, 0) }
                        s.tempBatteryC?.let { num(9, it, 2) }
                        s.tempVirtualC?.let { num(10, it, 2) }
                        s.gpuTempC?.let { num(11, it, 2) }
                        s.capacityPct?.let { num(12, it, 2) }
                        s.gpuLoadPct?.let { num(13, it, 2) }
                    }
                    c?.let { cc ->
                        cc.totalPct?.let { num(14, it, 1) }
                        cc.corePct.forEachIndexed { core, pct -> pct?.let { num(15 + core, it, 1) } }
                        cc.mhz.forEachIndexed { core, mhz -> mhz?.let { num(23 + core, it, 0) } }
                    }
                }
            }
        } else {
            // ── 旧会话：一行一个 1s 样本（子拍表建立前的数据，CPU 字段是 1s 均值）──
            samples.forEachIndexed { index, s ->
                row(sb, index + 7) {
                    num(1, (index + 1).toDouble(), 0)
                    str(2, timeFmt.format(Date(s.timeMillis)))
                    num(3, s.fps, 2)
                    num(colFrameSpace, s.frameSpaceMs, 2)
                    num(5, s.missedFrames.toDouble(), 0)
                    s.powerMw?.let { num(colPower, it, 2) }
                    s.voltageMv?.let { num(7, it, 0) }
                    s.currentMa?.let { num(8, it, 0) }
                    s.tempBatteryC?.let { num(9, it, 2) }
                    s.tempVirtualC?.let { num(10, it, 2) }
                    s.gpuTempC?.let { num(11, it, 2) }
                    s.capacityPct?.let { num(12, it, 2) }
                    s.gpuLoadPct?.let { num(13, it, 2) }
                    s.cpuUsagePct?.let { num(14, it, 1) }
                    s.cpuCoreUsagePct.forEachIndexed { core, pct -> pct?.let { num(15 + core, it, 1) } }
                    s.cpuMhz.forEachIndexed { core, mhz -> num(23 + core, mhz, 0) }
                }
            }
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun avgPowerMw(samples: List<FrameSample>): Double? =
        samples.mapNotNull { it.powerMw }.takeIf { it.isNotEmpty() }?.average()

    /** Sum(Battery)[mWh]：相邻样本的 (平均功率 mW × 间隔 s) ÷ 3600 累加；两侧都有功率才计 */
    private fun batteryMwh(samples: List<FrameSample>): Double? {
        var sum = 0.0
        var pairs = 0
        for (i in 1 until samples.size) {
            val a = samples[i - 1].powerMw ?: continue
            val b = samples[i].powerMw ?: continue
            val dtSec = (samples[i].timeMillis - samples[i - 1].timeMillis).coerceAtLeast(0L) / 1000.0
            sum += (a + b) / 2.0 * dtSec / 3600.0
            pairs++
        }
        return if (pairs > 0) sum else null
    }

    /** 行级写格器：封装行号，cell 函数只需列号（Excel 单元格引用 = 列字母 + 行号） */
    private class RowBuilder(private val sb: StringBuilder, private val row: Int) {
        /** 数值格（t 缺省 = 数字）；[decimals] 固定小数位，避免科学计数法进 XML */
        fun num(col: Int, value: Double, decimals: Int) {
            if (value.isNaN() || value.isInfinite()) return
            sb.append("<c r=\"").append(colLetter(col)).append(row).append("\"><v>")
            sb.append(String.format(Locale.US, "%.${decimals}f", value))
            sb.append("</v></c>")
        }

        /** 字符串格（inlineStr，不引入 sharedStrings） */
        fun str(col: Int, value: String) {
            sb.append("<c r=\"").append(colLetter(col)).append(row).append("\" t=\"inlineStr\"><is><t>")
            value.forEach { c ->
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(c)
                }
            }
            sb.append("</t></is></c>")
        }
    }

    private inline fun row(sb: StringBuilder, index: Int, cells: RowBuilder.() -> Unit) {
        sb.append("<row r=\"").append(index).append("\">")
        RowBuilder(sb, index).cells()
        sb.append("</row>")
    }

    /** 1 → A，27 → AA（Excel 列号） */
    private fun colLetter(index1: Int): String {
        var n = index1
        val out = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            out.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return out.toString()
    }

    // ---- OpenXML 最小包骨架 ----

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private val CONTENT_TYPES = XML_HEADER +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "</Types>"

    private val RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val WORKBOOK = XML_HEADER +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private val WORKBOOK_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "</Relationships>"
}
