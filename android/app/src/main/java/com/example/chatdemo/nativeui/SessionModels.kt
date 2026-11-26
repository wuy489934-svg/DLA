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
    /** 输入框里有没发出去的草稿：0 条消息时列表要标出来，别看起来像空会话 */
    val hasDraft: Boolean = false,
)

/** 消息内容块 */
sealed interface Block {
    data class Text(val text: String) : Block
    data class Thinking(val text: String, val seconds: Double?, /** Agent 迭代轮次；0 表示未知（旧数据 / LLM 区） */
                        val round: Int = 0) : Block
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
    /**
     * Agent 区某一轮的「旁白」：工具调用之间模型的过渡叙述。
     * 不放进正文气泡，收在「执行过程」卡里对应轮次的最前面。
     */
    data class RoundNote(val text: String, val round: Int) : Block
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
    /** 这一轮从开始到结束的实际耗时（助手消息落盘时记录） */
    val elapsedMs: Long = 0L,
    /** Agent 运行 id：界面重启后认领日志时用来去重 */
    val runId: String? = null,
)

/** 一条附件：图片走 data URL，文本类文档直接带正文 */
data class Attachment(
    val kind: String,
    val name: String? = null,
    val dataUrl: String? = null,
    val text: String? = null,
    /** Agent 区：原文件已复制到应用私有目录，这里存绝对路径，供 phone_sh 读取/转换 */
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
    /** 输入框里还没发出去的草稿：有草稿的空会话也要保留 */
    val draft: String = "",
    /** Agent 的计划/笔记，跨轮次（点「继续」）恢复用 */
    val taskState: TaskState? = null,
    /** 最近一轮的用量（轮/步、上下文占用、单轮耗时） */
    val usageStats: UsageStats? = null,
    /** 整个会话累计的 token 明细（缓存命中率） */
    val usageTotals: TokenTotals? = null,
)

/**
 * 一次助手回复落盘的用量数据。
 *
 * - rounds / steps：这一轮 Agent 迭代了几轮、调了几次工具；
 * - contextTokens：最后一次请求的上下文 token（input + cache + output）；
 * - turnMs：这一轮从用户发出到结束的实际耗时，**只记这一轮，不累计**。
 */
data class UsageStats(
    val rounds: Int = 0,
    val steps: Int = 0,
    val contextTokens: Int = 0,
    val turnMs: Long = 0L,
    /** 这一轮的 token 明细：缓存命中率要用 */
    val inputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val outputTokens: Int = 0,
)

/**
 * 整个会话累计的 token 明细（每次助手回复累加）。
 *
 * 缓存命中率 = cacheRead / (input + cacheRead + cacheCreation)，
 * 也就是「这一整个对话里，提示词有多少比例是从缓存读出来的」。
 */
data class TokenTotals(
    val input: Int = 0,
    val cacheRead: Int = 0,
    val cacheCreation: Int = 0,
    val output: Int = 0,
    val requests: Int = 0,
) {
    val promptTotal: Int get() = input + cacheRead + cacheCreation
    /**
     * null 表示接口没给缓存字段（或这一轮缓存命中为 0），显示「—」而不是假的 0%。
     *
     * Qwen / GPT 这类端点不回报 cached_tokens 时，旧算法会算出一个「0%」，
     * 看起来像缓存能力坏了；现在只要没有缓存读数就显示「—」。
     */
    val cachePercent: Int?
        get() = if (promptTotal <= 0 || (cacheRead == 0 && cacheCreation == 0)) null
        else Math.round(cacheRead * 100f / promptTotal)
    val isEmpty: Boolean get() = requests == 0 && promptTotal == 0 && output == 0
}

/** Agent 任务状态：计划、当前步、笔记 */
data class TaskState(
    val plan: List<String> = emptyList(),
    val current: Int = 1,
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = plan.isEmpty() && notes.isEmpty()
}

/**
 * 耗时显示：1s 以下保留毫秒，1 分钟内保留一位小数，再长就按 m/s。
 * 之前工具卡直接打印 `${'$'}{elapsedMs}ms`，长任务会出现 1484111ms 这种数字。
 */
fun formatElapsed(millis: Long): String = when {
    millis <= 0 -> ""
    millis < 1000 -> "${millis}ms"
    millis < 60_000 -> String.format(java.util.Locale.US, "%.1fs", millis / 1000.0)
    millis < 3_600_000 -> {
        val minutes = millis / 60_000
        val seconds = (millis % 60_000) / 1000
        "${minutes}m ${seconds}s"
    }
    else -> {
        val hours = millis / 3_600_000
        val minutes = (millis % 3_600_000) / 60_000
        "${hours}h ${minutes}m"
    }
}

object SessionParser {

    private fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    fun card(file: File): SessionCard? = runCatching {
        val json = JSONObject(file.readText())
        val messages = json.optJSONArray("messages") ?: JSONArray()
        val usage = json.optJSONObject("usageStats")
        val draft = json.optString("draft")
        val rawTitle = json.str("title").ifEmpty { "未命名会话" }
        // 只有草稿、还没发出去的会话：标题直接用草稿开头，一眼能认出是没写完的内容
        val title = if (messages.length() == 0 && draft.isNotBlank() &&
            (rawTitle == "新对话" || rawTitle == "未命名会话")
        ) {
            Lang.t("草稿") + "：" + draft.replace("\n", " ").take(16)
        } else {
            rawTitle
        }
        SessionCard(
            id = json.str("id").ifEmpty { file.nameWithoutExtension },
            title = title,
            mode = json.str("mode").ifEmpty { "llm" },
            updatedAt = json.optLong("updatedAt", 0L),
            createdAt = json.optLong("createdAt", 0L),
            messageCount = messages.length(),
            rounds = usage?.optInt("rounds", 0) ?: 0,
            steps = usage?.optInt("steps", 0) ?: 0,
            file = file,
            hasDraft = draft.isNotBlank(),
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
            draft = json.str("draft"),
            taskState = taskState(json.optJSONObject("taskState")),
            usageStats = usageStats(json.optJSONObject("usageStats")),
            usageTotals = tokenTotals(json.optJSONObject("usageTotals")),
        )
    }.getOrNull()

    /** 用量统计：老会话没有这个字段，返回 null */
    private fun usageStats(json: JSONObject?): UsageStats? {
        if (json == null) return null
        val stats = UsageStats(
            rounds = json.optInt("rounds", 0),
            steps = json.optInt("steps", 0),
            contextTokens = json.optInt("contextTokens", 0),
            turnMs = json.optLong("turnMs", 0L),
        )
        return if (stats.rounds == 0 && stats.steps == 0 &&
            stats.contextTokens == 0 && stats.turnMs == 0L
        ) {
            null
        } else {
            stats
        }
    }

    private fun tokenTotals(json: JSONObject?): TokenTotals? {
        if (json == null) return null
        val totals = TokenTotals(
            input = json.optInt("input", 0),
            cacheRead = json.optInt("cacheRead", 0),
            cacheCreation = json.optInt("cacheCreation", 0),
            output = json.optInt("output", 0),
            requests = json.optInt("requests", 0),
        )
        return if (totals.isEmpty) null else totals
    }

    private fun taskState(json: JSONObject?): TaskState? {
        if (json == null) return null
        val plan = json.optJSONArray("plan")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }
        } ?: emptyList()
        val notes = json.optJSONArray("notes")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).ifBlank { null } }
        } ?: emptyList()
        val state = TaskState(plan = plan, current = json.optInt("current", 1), notes = notes)
        return if (state.isEmpty) null else state
    }

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
            elapsedMs = json.optLong("elapsedMs", 0L),
            runId = if (json.isNull("runId")) null else json.optString("runId").ifEmpty { null },
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
        "text" -> Block.Text(cleanLegacyNulls(json.str("text")))
        "thinking" -> Block.Thinking(
            text = cleanLegacyNulls(json.str("thinking")),
            seconds = if (json.has("elapsedSeconds") && !json.isNull("elapsedSeconds"))
                json.optDouble("elapsedSeconds", 0.0) else null,
            round = json.optInt("round", 0),
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
        "round_note" -> Block.RoundNote(cleanLegacyNulls(json.str("text")), json.optInt("round", 0))
        else -> Block.Other(type)
    }
}

/** 3.5.0 之前把 JSON null 拼成了 "null"，老会话里会留下成片的 null：读的时候折成一句提示 */
private val LEGACY_NULL_SPAM = Regex("(?:null){6,}")

private fun cleanLegacyNulls(text: String): String {
    if (text.length < 24 || !text.contains("null")) return text
    val cleaned = LEGACY_NULL_SPAM.replace(text, "…").trim()
    val meaningful = cleaned.replace("…", "").trim().length
    // 整段几乎都是 null 噪音：直接说明原因，别只留一个省略号
    return if (meaningful * 10 < text.length) {
        "（这条回答是旧版本解析错误留下的，已隐藏；可以删除本会话重试）"
    } else {
        cleaned
    }
}
