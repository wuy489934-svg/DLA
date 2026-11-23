package com.example.chatdemo.nativeui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出：把 `native-sessions` 里所有会话合并成一个 JSON，写进系统 Downloads。
 */
object SessionExport {

    /** 导出全部会话到 Downloads/DLA/dla-history-<时间>.json，返回展示路径 */
    fun exportAll(context: Context): String {
        val dir = File(context.filesDir, "native-sessions")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        val array = JSONArray()
        files.sortedBy { it.name }.forEach { file ->
            runCatching { array.put(org.json.JSONObject(file.readText())) }
        }
        val payload = org.json.JSONObject()
            .put("kind", "dla-native-history")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("sessions", array)
            .toString()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "dla-history-$stamp.json"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DLA")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入 Downloads")
            resolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法打开输出流")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Download/DLA/$name"
        }
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DLA/$name",
        )
        target.parentFile?.mkdirs()
        target.writeText(payload)
        return target.absolutePath
    }
}
