package com.chen.powermeter.util

import android.os.Build
import com.chen.powermeter.data.FrameSample
import com.chen.powermeter.data.db.FrameSession
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 帧率记录的 **Kite 兼容 xlsx** 导出器（2026-09-25 加）。
 *
 * 表结构逐列对齐 Kite 的采集文件（用户提供的 `Kite_20260918_02_49_50.xlsx` 为准）：
 * - 元信息：`Packge Name`（Kite 原文如此，含拼写错误**刻意保留**——下游若按表头字符串
 *   解析，改名反而对不上）、`Device Type`、`Stat` 行（Avg(FPS) / Avg(Power)[mW] /
 *   Sum(Battery)[mWh]，全部由样本现算）；
 * - 数据表：`Num / Time / Label / FPS / FrameSpace / CPUClock0..7[MHz] /
 *   current[mA] / voltage[mV] / power[mW] / batTemp[°C] / virTemp[°C]`，时间格式
 *   `yyyy-MM-dd_HH:mm:ss`（Kite 同款，日期与时刻之间是下划线）；
 * - ⚠️ 与 Kite 的两处**有意偏差**：① CPU 列输出 8 核（Kite 只写 7 列，本应用样本存
 *   8 核，砍一列等于丢数据）；② 不写 Kite 的 `PolicyNum` 行与 `frame_1[ms]` /
 *   流畅度 / 抖动率等专属统计（口径不明、本应用无逐帧数据）。
 *
 * 实现说明：**手写 OpenXML 最小集**（[Content_Types].xml + rels + workbook + sheet，
 * 字符串用 inlineStr，无需 sharedStrings/styles），零第三方依赖 —— Apache POI 单是
 * 打进 APK 就 10MB 级，为一个导出文件不值。产物 Excel / WPS 均可直接打开。
 *
 * ⚠️ 必须在后台线程调用（内含压缩与字符串拼接，1800 样本 ≈ 几百 KB）。
 */
object KiteXlsxExporter {

    /**
     * 分享文件名：`Kite_20260918_02_49_50.xlsx` —— 与 Kite 同款命名（取开始时刻），
     * 便于下游 Kite 系工具按文件名规律识别。
     */
    fun fileName(session: FrameSession): String =
        "Kite_${SimpleDateFormat("yyyyMMdd_HH_mm_ss", Locale.US).format(Date(session.startTime))}.xlsx"

    fun build(session: FrameSession, samples: List<FrameSample>): ByteArray {
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
            entry("xl/worksheets/sheet1.xml", buildSheet(session, samples))
        }
        return out.toByteArray()
    }

    // ---- sheet 组装 ----

    private fun buildSheet(session: FrameSession, samples: List<FrameSample>): String {
        val sb = StringBuilder(XML_HEADER)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        // 列宽：时间列放得下完整时间戳，数值列不挤成 ####
        sb.append("<cols>")
        sb.append("<col min=\"2\" max=\"2\" width=\"21\" customWidth=\"1\"/>")
        sb.append("<col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/>")
        sb.append("<col min=\"4\" max=\"18\" width=\"13\" customWidth=\"1\"/>")
        sb.append("</cols>")
        sb.append("<sheetData>")

        // 元信息（行号沿用 Kite 骨架：1/2 元信息、3 统计标签、4 统计值）
        row(sb, 1) {
            str(1, "Packge Name") // Kite 原文如此，刻意保留
            str(2, session.packageName)
        }
        row(sb, 2) {
            str(1, "Device Type")
            str(2, Build.MODEL)
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

        // 表头（行 6；行 5 留空，与 Kite 统计块的留白一致）
        val headers = buildList {
            add("Num"); add("Time"); add("Label"); add("FPS"); add("FrameSpace")
            for (i in 0..7) add("CPUClock$i[MHz]")
            add("current[mA]"); add("voltage[mV]"); add("power[mW]")
            add("batTemp[°C]"); add("virTemp[°C]")
        }
        row(sb, 6) { headers.forEachIndexed { i, h -> str(i + 1, h) } }

        // 数据行：列序与表头一致；null = 该周期未采集（整格省略，Excel 显示空）
        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US)
        samples.forEachIndexed { index, s ->
            row(sb, index + 7) {
                num(1, (index + 1).toDouble(), 0)
                str(2, timeFmt.format(Date(s.timeMillis)))
                str(3, session.appLabel)
                num(4, s.fps, 2)
                num(5, s.frameSpaceMs, 2)
                s.cpuMhz.forEachIndexed { core, mhz -> num(6 + core, mhz, 0) }
                s.currentMa?.let { num(14, it, 0) }
                s.voltageMv?.let { num(15, it, 0) }
                s.powerMw?.let { num(16, it, 2) }
                s.tempBatteryC?.let { num(17, it, 2) }
                s.tempVirtualC?.let { num(18, it, 2) }
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

    // ---- OpenXML 最小包骨架（命名空间与 Kite 文件解包核对一致）----

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
