package com.example.chatdemo.nativeui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 原生会话仓库（方案 B 的写入侧，原生从此成为数据源）。
 *
 * - 目录：`files/native-sessions/<id>.json`，一条会话一个文件；
 * - 首次启动把网页留下的 `files/host-sessions` 目录下的 json 导入一次（只补缺失，不覆盖、不删除）；
 * - 文件结构与网页快照兼容，网页端仍能读，便于过渡期回退。
 */
class SessionStore(context: Context) {

    private val app = context.applicationContext
    private val nativeDir = File(app.filesDir, "native-sessions")
    private val legacyDir = File(app.filesDir, "host-sessions")

    init {
        nativeDir.mkdirs()
        importLegacy()
    }

    /** 一次性导入网页镜像：只补原生还没有的会话 */
    private fun importLegacy() {
        val legacy = legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (file in legacy) {
            val target = File(nativeDir, file.name)
            if (target.exists()) continue
            runCatching { file.copyTo(target, overwrite = false) }
        }
    }

    fun cards(): List<SessionCard> {
        val files = nativeDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { SessionParser.card(it) }.sortedByDescending { it.updatedAt }
    }

    fun detail(id: String): SessionDetail? {
        val file = File(nativeDir, "$id.json")
        if (!file.exists()) return null
        return SessionParser.detail(file)
    }

    fun create(mode: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("id", id)
            .put("title", "新对话")
            .put("createdAt", now)
            .put("updatedAt", now)
            .put("mode", mode)
            .put("messages", JSONArray())
        write(id, json)
        return id
    }

    fun appendUser(sessionId: String, text: String, attachments: List<Attachment> = emptyList()) {
        val json = read(sessionId) ?: return
        val messages = json.optJSONArray("messages") ?: JSONArray().also { json.put("messages", it) }
        messages.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("role", "user")
                .put("content", text)
                .put("files", JSONArray().apply {
                    attachments.forEach { attachment ->
                        put(
                            JSONObject()
                                .put("kind", attachment.kind)
                                .put("name", attachment.name ?: "")
                                .put("dataUrl", attachment.dataUrl ?: "")
                                .put("text", attachment.text ?: "")
                                .put("localPath", attachment.localPath ?: "")
                                .put("sizeBytes", attachment.sizeBytes)
                        )
                    }
                })
                .put("createdAt", System.currentTimeMillis())
        )
        val title = json.optString("title")
        if (title.isEmpty() || title == "新对话") {
            val derived = text.replace("\n", " ").take(18)
            json.put("title", derived.ifBlank { if (attachments.isEmpty()) "新对话" else "图片" })
        }
        json.put("updatedAt", System.currentTimeMillis())
        write(sessionId, json)
    }

    fun appendAssistant(
        sessionId: String,
        text: String,
        thinking: String,
        tools: List<Block.Tool>,
        status: String,
    ) {
        val json = read(sessionId) ?: return
        val messages = json.optJSONArray("messages") ?: JSONArray().also { json.put("messages", it) }
        val blocks = JSONArray()
        if (thinking.isNotBlank()) {
            blocks.put(JSONObject().put("type", "thinking").put("thinking", thinking))
        }
        for (tool in tools) {
            blocks.put(
                JSONObject()
                    .put("type", "agent_tool")
                    .put("id", tool.id)
                    .put("name", tool.name)
                    .put("status", tool.status)
                    .put("input", tool.input)
                    .put("result", tool.result ?: "")
                    .put("elapsedMs", tool.elapsedMs)
                    .put("round", tool.round)
            )
        }
        if (text.isNotBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", text))
        }
        messages.put(
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("role", "assistant")
                .put("blocks", blocks)
                .put("status", status)
                .put("createdAt", System.currentTimeMillis())
        )
        json.put("updatedAt", System.currentTimeMillis())
        write(sessionId, json)
    }

    fun delete(sessionId: String) {
        File(nativeDir, "$sessionId.json").delete()
    }

    /** 供 Agent 运行时读取历史（只回传用户与助手的纯文本） */
    fun history(sessionId: String): List<Turn> {
        val detail = detail(sessionId) ?: return emptyList()
        val turns = ArrayList<Turn>(detail.messages.size)
        detail.messages.forEach { message ->
            val text = when (message.role) {
                "user" -> message.text
                else -> message.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
            }
            if (text.isBlank() && message.attachments.isEmpty()) return@forEach
            turns += Turn(
                role = message.role,
                text = text.ifBlank {
                    if (message.attachments.any { it.kind == "image" }) "（图片）" else "（附件）"
                },
                images = message.attachments.filter { it.kind == "image" }.mapNotNull { it.dataUrl },
                documents = message.attachments
                    .filter { it.kind == "text" }
                    .mapNotNull { attachment -> attachment.text?.let { (attachment.name ?: "文档") to it } },
            )
        }
        return turns
    }

    private fun read(sessionId: String): JSONObject? = runCatching {
        JSONObject(File(nativeDir, "$sessionId.json").readText())
    }.getOrNull()

    private fun write(sessionId: String, json: JSONObject) {
        val target = File(nativeDir, "$sessionId.json")
        val temp = File(nativeDir, "$sessionId.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) {
            target.writeText(json.toString())
            temp.delete()
        }
    }
}
