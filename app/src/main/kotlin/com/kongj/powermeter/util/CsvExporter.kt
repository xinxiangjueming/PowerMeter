package com.kongj.powermeter.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import com.kongj.powermeter.data.PowerSample
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出采样序列为 CSV（保存到系统 Download 目录，Android 10+ 无需存储权限） */
object CsvExporter {

    fun export(context: Context, samples: List<PowerSample>): Uri? {
        if (samples.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "powermeter_$stamp.csv"

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
                OutputStreamWriter(stream, Charsets.UTF_8).use { w ->
                    w.write("timestamp,datetime,voltage_v,voltage_ocv_v,current_ma,fg_current_ma,power_w,temp_battery_c,temp_usb_c,temp_charger_c,soc_pct,status,charge_type,remaining_mah,usb_voltage_v\n")
                    // 毫秒级：采样间隔最小 500ms，秒级精度下相邻两行 datetime 会重复，
                    // 无法直接作为时间轴；SSS 保证每行唯一（timestamp 列仍是完整毫秒 epoch）
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    for (s in samples) {
                        w.write(
                            listOf(
                                s.timeMillis.toString(),
                                fmt.format(Date(s.timeMillis)),
                                s.voltageV.f3(),
                                s.voltageOcvV.f3(),
                                s.currentMa.f3(),
                                s.fgCurrentMa?.f3() ?: "",
                                s.powerW.f3(),
                                s.tempBatteryC.f3(),
                                s.tempUsbC?.f3() ?: "",
                                s.tempChargerC?.f3() ?: "",
                                s.socPct.toString(),
                                s.status,
                                s.chargeType,
                                s.remainingMah?.f3() ?: "",
                                s.usbVoltageV?.f3() ?: "",
                            ).joinToString(",") + "\n"
                        )
                    }
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

    private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)
}
