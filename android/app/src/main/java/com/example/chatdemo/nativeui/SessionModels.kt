package com.example.chatdemo.nativeui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 原生壳读取的数据模型。
 *
 * 数据来源是原生自己的会话镜像目录 `files/host-sessions` 下的 json 快照
 * （网页侧每次变更后通过 `AndroidHost.saveSessionSnapshot` 落盘），
 * 因此原生 UI 不依赖 WebView 即可展示历史；本阶段只读。
 */

/** 会话列表项 */
data class SessionCard(
    val id: String,
    val title: String,
    val mode: String,
    val updatedAt: Long,
    val createdAt: Long,
    val messageCount: Int,
    val rounds: Int,
    val steps: Int,
    val file: File,
)

/** 消息内容块 */
sealed interface Block {
    data class Text(val text: String) : Block
    data class Thinking(val text: String, val seconds: Double?) : Block
    data class Tool(
        val id: String,
        val name: String,
        val status: String,
        val input: String,
        val result: String?,
        val elapsedMs: Long,
        /** Agent 迭代轮次；0 表示未知（旧数据） */
        val round: Int = 0,
    ) : Block
    data class Other(val type: String) : Block
}

/** 一条消息（用户或助手） */
data class ChatMessage(
    val id: String,
    val role: String,
    val createdAt: Long,
    val text: String,
    val blocks: List<Block>,
    val status: String? = null,
    val model: String? = null,
    /** 用户消息携带的附件，来自 JSON 的 files 字段 */
    val attachments: List<Attachment> = emptyList(),
)

/** 一条附件：图片走 data URL，文本类文档直接带正文 */
data class Attachment(
    val kind: String,
    val name: String? = null,
    val dataUrl: String? = null,
    val text: String? = null,
    /** Agent 区：原文件已复制到应用私有目录，这里存绝对路径，供 Termux 读取/转换 */
    val localPath: String? = null,
    val sizeBytes: Long = 0L,
)

/** 发给模型的一条历史：只给最后一条用户消息带附件，避免请求越滚越大 */
data class Turn(
    val role: String,
    val text: String,
    val images: List<String> = emptyList(),
    val documents: List<Pair<String, String>> = emptyList(),
)

/** 会话详情 */
data class SessionDetail(
    val id: String,
    val title: String,
    val mode: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
)

object SessionParser {

    private fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    fun card(file: File): SessionCard? = runCatching {
        val json = JSONObject(file.readText())
        val messages = json.optJSONArray("messages") ?: JSONArray()
        val usage = json.optJSONObject("usageStats")
        SessionCard(
            id = json.str("id").ifEmpty { file.nameWithoutExtension },
            title = json.str("title").ifEmpty { "未命名会话" },
            mode = json.str("mode").ifEmpty { "llm" },
            updatedAt = json.optLong("updatedAt", 0L),
            createdAt = json.optLong("createdAt", 0L),
            messageCount = messages.length(),
            rounds = usage?.optInt("rounds", 0) ?: 0,
            steps = usage?.optInt("steps", 0) ?: 0,
            file = file,
        )
    }.getOrNull()

    fun detail(file: File): SessionDetail? = runCatching {
        val json = JSONObject(file.readText())
        val array = json.optJSONArray("messages") ?: JSONArray()
        val messages = ArrayList<ChatMessage>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            messages += message(item)
        }
        SessionDetail(
            id = json.str("id").ifEmpty { file.nameWithoutExtension },
            title = json.str("title").ifEmpty { "未命名会话" },
            mode = json.str("mode").ifEmpty { "llm" },
            updatedAt = json.optLong("updatedAt", 0L),
            messages = messages,
        )
    }.getOrNull()

    private fun message(json: JSONObject): ChatMessage {
        val role = json.str("role")
        val blocks = ArrayList<Block>()
        if (role == "assistant") {
            val array = json.optJSONArray("blocks")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val block = array.optJSONObject(i) ?: continue
                    blocks += block(block)
                }
            }
        }
        val text = when {
            role == "user" -> json.str("content")
            else -> blocks.filterIsInstance<Block.Text>().joinToString("\n\n") { it.text }
        }
        return ChatMessage(
            id = json.str("id"),
            role = role,
            createdAt = json.optLong("createdAt", 0L),
            text = text,
            blocks = blocks,
            status = if (json.has("status")) json.str("status") else null,
            model = if (json.isNull("model")) null else json.optString("model").ifEmpty { null },
            attachments = attachments(json),
        )
    }

    private fun attachments(json: JSONObject): List<Attachment> {
        val array = json.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<Attachment>(array.length())
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                // 旧数据是纯字符串：一律当图片处理
                is String -> if (item.isNotBlank()) {
                    out += Attachment(kind = "image", name = null, dataUrl = item)
                }
                is JSONObject -> {
                    val data = item.optString("dataUrl").ifEmpty { item.optString("data") }
                    val text = item.optString("text")
                    val kind = item.optString("kind").ifEmpty {
                        if (data.isNotBlank()) "image" else "text"
                    }
                    val localPath = item.optString("localPath").ifEmpty { null }
                    val size = item.optLong("sizeBytes", 0L)
                    if (kind == "text" && text.isNotBlank()) {
                        out += Attachment(
                            kind = "text",
                            name = item.optString("name"),
                            text = text,
                            localPath = localPath,
                            sizeBytes = size,
                        )
                    } else if (kind == "file" && localPath != null) {
                        out += Attachment(
                            kind = "file",
                            name = item.optString("name"),
                            localPath = localPath,
                            sizeBytes = size,
                        )
                    } else if (data.isNotBlank()) {
                        out += Attachment(kind = "image", name = item.optString("name"), dataUrl = data)
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun block(json: JSONObject): Block = when (val type = json.str("type")) {
        "text" -> Block.Text(json.str("text"))
        "thinking" -> Block.Thinking(
            text = json.str("thinking"),
            seconds = if (json.has("elapsedSeconds") && !json.isNull("elapsedSeconds"))
                json.optDouble("elapsedSeconds", 0.0) else null,
        )
        "agent_tool" -> Block.Tool(
            id = json.str("id"),
            name = json.str("name"),
            status = json.str("status").ifEmpty { "done" },
            input = json.str("input"),
            result = if (json.isNull("result")) null else json.optString("result").ifEmpty { null },
            elapsedMs = json.optLong("elapsedMs", 0L),
            round = json.optInt("round", 0),
        )
        else -> Block.Other(type)
    }
}
