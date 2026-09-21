package com.chen.powermeter.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import com.chen.powermeter.data.PowerSample
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出采样序列为 CSV（保存到系统 Download 目录，Android 10+ 无需存储权限） */
object CsvExporter {

    /** 18 列表头；与 CsvImporter 按列名取值配套（列顺序可变，列名不可变） */
    private const val HEADER =
        "timestamp,datetime,voltage_v,voltage_ocv_v,current_ma,fg_current_ma,power_w," +
            "temp_battery_c,temp_usb_c,temp_charger_c,soc_pct,status,charge_type," +
            "remaining_mah,usb_voltage_v,temp_pmic_c,full_mah,usb_current_limit_ma\n"

    /**
     * 导出内存中的序列（导入态 / 兜底路径）。
     *
     * @param prefix 文件名前缀，默认 `powermeter`（手动导出）。自动导出传 `powermeter_charge`，
     *               以便与手动导出的文件在 Download 目录里一眼区分。
     */
    suspend fun export(
        context: Context,
        samples: List<PowerSample>,
        prefix: String = "powermeter",
    ): Uri? {
        if (samples.isEmpty()) return null
        return writeCsv(context, prefix) { w, fmt ->
            for (s in samples) w.write(row(s, fmt))
        }
    }

    /**
     * 分页导出（Room 会话导出路径）。
     *
     * 为什么分页：落库后一场会话可以远超 3600 条（1s 间隔跑 12 小时 = 43200 行），
     * 一次性 load 进内存再 format 是几十 MB 级别的临时分配，低端机上有 OOM 风险。
     * 这里按 [limit] 逐页取、逐行写，内存占用恒定在一页。
     *
     * @param total 库中的样本总数（调用方用 COUNT 查一次，避免这里多查一轮）
     * @param fetch 分页取数回调。⚠️ 声明为 **suspend** 而非普通函数类型 —— 它内部要调 Room 的
     *              `suspend` DAO 查询，普通函数类型的 lambda 里不允许调用挂起函数
     *              （会报 `Suspension functions can only be called within coroutine body`）。
     *              ⚠️ 内含 DB 查询，调用方必须已在 IO 线程。
     */
    suspend fun exportPaged(
        context: Context,
        prefix: String,
        total: Int,
        limit: Int = 5_000,
        fetch: suspend (offset: Int, limit: Int) -> List<PowerSample>,
    ): Uri? {
        if (total <= 0) return null
        return writeCsv(context, prefix) { w, fmt ->
            var offset = 0
            while (offset < total) {
                val page = fetch(offset, limit)
                if (page.isEmpty()) break
                for (s in page) w.write(row(s, fmt))
                offset += page.size
            }
        }
    }

    /**
     * 取 MediaStore 记录的 DISPLAY_NAME。
     *
     * ⚠️ 不能用 `uri.lastPathSegment` —— 对 MediaStore 的 content:// 记录它返回的是数字 ID
     * 而非文件名，Toast 里会变成「已导出：Download/PowerMeter/1024」。
     */
    fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    /**
     * MediaStore 写盘的公共外壳：建记录 → 写内容 → 解除 IS_PENDING。
     * 失败时删掉半成品记录并返回 null（否则 Download 里会留下打不开的 0 字节 CSV）。
     */
    private suspend fun writeCsv(
        context: Context,
        prefix: String,
        body: suspend (BufferedWriter, SimpleDateFormat) -> Unit,
    ): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$stamp.csv"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/PowerMeter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

        runCatching {
            resolver.openOutputStream(uri)?.use { stream ->
                // 毫秒级：采样间隔最小 500ms，秒级精度下相邻两行 datetime 会重复，
                // 无法直接作为时间轴；SSS 保证每行唯一（timestamp 列仍是完整毫秒 epoch）
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 16).use { w ->
                    w.write(HEADER)
                    body(w, fmt)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    private fun row(s: PowerSample, fmt: SimpleDateFormat): String =
        listOf(
            s.timeMillis.toString(),
            fmt.format(Date(s.timeMillis)),
            s.voltageV.f3(),
            s.voltageOcvV.f3(),
            s.currentMa.f0(),
            s.fgCurrentMa?.f0() ?: "",
            s.powerW.f3(),
            s.tempBatteryC.f1(),
            s.tempUsbC?.f0() ?: "",
            s.tempChargerC?.f3() ?: "",
            s.socPct.toString(),
            s.status,
            s.chargeType,
            s.remainingMah?.f0() ?: "",
            s.usbVoltageV?.f3() ?: "",
            s.tempPmicC?.f0() ?: "",
            s.fullMah?.f0() ?: "",
            s.usbCurrentLimitMa?.f0() ?: "",
        ).joinToString(",") + "\n"

    private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

    /** 温度类（电池）按用户约定取 1 位小数（2026-09-21）；充电 IC 温度取 3 位，接口 / PMIC 温度取整 */
    private fun Double.f1(): String = String.format(Locale.US, "%.1f", this)

    /**
     * 整数档（2026-09-21 用户约定）：电流 mA / 燃料计电流 mA / 接口温度 / **PMIC 温度**
     * （温感区分辨率只到整度）/ 剩余容量 mAh / 满充容量 mAh / USB 限流 mA
     * 分辨率只到个位，落盘与界面显示同口径（避免 CSV 里出现无意义的 .000 尾巴）。
     */
    private fun Double.f0(): String = String.format(Locale.US, "%.0f", this)
}
