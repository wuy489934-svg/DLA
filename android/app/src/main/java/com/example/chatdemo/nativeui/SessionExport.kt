package com.example.chatdemo.nativeui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 会话导出。
 *
 * 3.6.2 起 LLM 区与 Agent 区**分开导出**，不再合成一个 JSON：
 * - 每个区一个 zip，格式参考 DeepSeek 官方导出的 `deepseek_data-<日期>.zip`：
 *   根目录一个 `conversations.json`（全部会话），外加 `conversations/<id>.json` 单会话文件；
 * - 字段名与官方对齐（`export_format_version` / `conversations[].messages[].created_at` 等），
 *   思考、工具调用、附件元信息另外带上，方便二次处理或被别的工具导入；
 * - 图片本体（base64 data URL）不写进 zip：多图会话单文件能到几 MB，导出一份就没法看了，
 *   README 里会说明这一点。
 *
 * 写盘固定走系统 Downloads（MediaStore），失败才退回旧式文件路径。
 */
object SessionExport {

    /** 与 DeepSeek 官方导出保持同一代格式号 */
    private const val FORMAT_VERSION = 3

    /** 导出某个分区：llm / agent。返回展示用路径 */
    fun exportZone(context: Context, zone: String): String {
        val dir = File(context.filesDir, "native-sessions")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        val details = files.mapNotNull { file ->
            val detail = SessionParser.detail(file) ?: return@mapNotNull null
            if (detail.mode != zone) return@mapNotNull null
            detail
        }.sortedByDescending { it.updatedAt }

        val conversations = JSONArray()
        val single = ArrayList<Pair<String, String>>(details.size)
        details.forEach { detail ->
            val conversation = conversation(detail)
            conversations.put(conversation)
            single += "conversations/${safeName(detail.id)}.json" to conversation.toString(2)
        }

        val payload = JSONObject()
            .put("export_format_version", FORMAT_VERSION)
            .put("source", "dla-android")
            .put("zone", zone)
            .put("exported_at", iso(System.currentTimeMillis()))
            .put("count", details.size)
            .put("conversations", conversations)
            .toString(2)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zoneName = if (zone == "agent") "agent" else "llm"
        val name = "dla_${zoneName}_data-$stamp.zip"

        val entries = ArrayList<Pair<String, String>>(single.size + 2)
        entries += "README.txt" to readme(zone, details.size)
        entries += "conversations.json" to payload
        entries += single
        val zip = zip(entries)

        return saveDownload(context, name, "application/zip", zip)
    }

    // ---------------- 结构 ----------------

    private fun conversation(detail: SessionDetail): JSONObject {
        val messages = JSONArray()
        detail.messages.forEach { messages.put(message(it)) }
        return JSONObject()
            .put("id", detail.id)
            .put("title", detail.title)
            .put("mode", detail.mode)
            .put("created_at", iso(detail.messages.firstOrNull()?.createdAt ?: detail.updatedAt))
            .put("updated_at", iso(detail.updatedAt))
            .put("message_count", detail.messages.size)
            .put("messages", messages)
    }

    private fun message(message: ChatMessage): JSONObject {
        val json = JSONObject()
            .put("id", message.id)
            .put("role", message.role)
            .put("content", message.text)
            .put("created_at", iso(message.createdAt))
        if (!message.model.isNullOrBlank()) json.put("model", message.model)
        if (message.status != null) json.put("status", message.status)
        if (message.elapsedMs > 0) json.put("elapsed_ms", message.elapsedMs)

        val thinking = message.blocks.filterIsInstance<Block.Thinking>()
        if (thinking.isNotEmpty()) {
            json.put("thinking", thinking.joinToString("\n\n") { it.text })
            json.put("thinking_rounds", JSONArray().apply {
                thinking.forEach { block ->
                    put(
                        JSONObject()
                            .put("round", block.round)
                            .put("seconds", block.seconds ?: 0.0)
                            .put("text", block.text),
                    )
                }
            })
        }
        val tools = message.blocks.filterIsInstance<Block.Tool>()
        if (tools.isNotEmpty()) {
            json.put("tool_calls", JSONArray().apply {
                tools.forEach { tool ->
                    put(
                        JSONObject()
                            .put("id", tool.id)
                            .put("name", tool.name)
                            .put("status", tool.status)
                            .put("input", tool.input)
                            .put("result", tool.result.orEmpty())
                            .put("elapsed_ms", tool.elapsedMs)
                            .put("round", tool.round),
                    )
                }
            })
        }
        if (message.attachments.isNotEmpty()) {
            json.put("attachments", JSONArray().apply {
                message.attachments.forEach { attachment ->
                    put(
                        JSONObject()
                            .put("kind", attachment.kind)
                            .put("name", attachment.name.orEmpty())
                            .put("bytes", attachment.sizeBytes),
                    )
                }
            })
        }
        return json
    }

    private fun readme(zone: String, count: Int): String {
        val title = if (zone == "agent") "Agent 区（任务与工具调用）" else "LLM 区（对话）"
        return """
            DLA 聊天记录导出 · $title
            导出时间：${iso(System.currentTimeMillis())}
            会话数：$count

            目录结构（参考 DeepSeek 官方导出的 zip）：
              conversations.json          全部会话，字段与官方导出的 conversations.json 同一套口径
              conversations/<会话 id>.json 一条会话一个文件，内容与上面数组里的对应项一致
              README.txt                  本说明

            字段：
              id / title / mode / created_at / updated_at / message_count / messages
              messages[]：id、role（user / assistant）、content、created_at；
                          assistant 另带 thinking（思考原文）、thinking_rounds（按轮的思考）、
                          tool_calls（工具名、参数、结果、耗时、轮次）、model、status。

            说明：
              - 图片附件只保留元信息（类型 / 文件名 / 大小），base64 本体不写进 zip；
                多图会话单文件能到几 MB，写进去反而没法看。
              - Agent 区的工具结果可能很长，导出保留原文，未做截断。
              - 本文件由 DLA 在本机生成，导出过程不联网、不上传。
        """.trimIndent()
    }

    // ---------------- 写盘 ----------------

    private fun zip(entries: List<Pair<String, String>>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun saveDownload(context: Context, name: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DLA")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入 Downloads")
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                ?: throw IllegalStateException("无法打开输出流")
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
        target.writeBytes(bytes)
        return target.absolutePath
    }

    private fun iso(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return format.format(Date(if (millis > 0) millis else System.currentTimeMillis()))
    }

    private fun safeName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "session" }
}
