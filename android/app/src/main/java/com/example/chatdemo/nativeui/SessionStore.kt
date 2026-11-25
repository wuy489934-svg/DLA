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
        ensure(id, mode)
        return id
    }

    /**
     * 空会话不落盘：只有在确实有内容（消息或草稿）时才建文件。
     *
     * ChatEngine 新建会话时只保留内存中的 Open，第一条消息 / 第一段草稿才会走到这里，
     * 所以列表里不会再出现「新对话 0 条」。
     */
    fun ensure(sessionId: String, mode: String) {
        val file = File(nativeDir, "$sessionId.json")
        if (file.exists()) return
        val now = System.currentTimeMillis()
        write(
            sessionId,
            JSONObject()
                .put("id", sessionId)
                .put("title", "新对话")
                .put("createdAt", now)
                .put("updatedAt", now)
                .put("mode", mode)
                .put("messages", JSONArray()),
        )
    }

    /** 保存输入框草稿；草稿被清空且没有消息时顺手删掉这个空会话 */
    fun saveDraft(sessionId: String, mode: String, text: String) {
        val file = File(nativeDir, "$sessionId.json")
        if (!file.exists() && text.isBlank()) return
        ensure(sessionId, mode)
        val json = read(sessionId) ?: return
        if (json.optString("draft") == text) return
        val messageCount = json.optJSONArray("messages")?.length() ?: 0
        if (text.isBlank() && messageCount == 0) {
            file.delete()
            return
        }
        json.put("draft", text)
        json.put("updatedAt", System.currentTimeMillis())
        write(sessionId, json)
    }

    /** 清理历史遗留的空会话（没有消息也没有草稿） */
    fun pruneEmpty() {
        val files = nativeDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (file in files) {
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            val messageCount = json.optJSONArray("messages")?.length() ?: 0
            if (messageCount == 0 && json.optString("draft").isBlank()) {
                file.delete()
            }
        }
    }

    /** Agent 的计划/笔记，供「继续」时恢复 */
    fun saveTaskState(sessionId: String, mode: String, state: TaskState?) {
        if (state == null || state.isEmpty) return
        ensure(sessionId, mode)
        val json = read(sessionId) ?: return
        json.put(
            "taskState",
            JSONObject()
                .put("plan", JSONArray(state.plan))
                .put("current", state.current)
                .put("notes", JSONArray(state.notes)),
        )
        write(sessionId, json)
    }

    /**
     * 给 Agent 的历史上下文。
     *
     * 之前每次运行只发一条「当前用户消息」，所以到步数上限后点「继续」，
     * 模型看到的只有「继续」两个字，只能回答「当前会话是空的」。
     * 这里把最近若干轮整理成 user / assistant 交替的 messages：
     * 助手侧带一段工具调用摘要，让模型知道上一轮到底做了什么。
     */
    fun agentMessages(sessionId: String, maxTurns: Int = 8, dropLastUser: Boolean = false): JSONArray {
        val detail = detail(sessionId) ?: return JSONArray()
        // send() 会先把这一轮的用户消息落盘，再启动 Agent；
        // 这一条要由调用方带着图片重新拼，所以这里把它摘掉，避免重复。
        val messages = if (dropLastUser && detail.messages.lastOrNull()?.role == "user") {
            detail.messages.dropLast(1)
        } else {
            detail.messages
        }
        val out = JSONArray()
        var lastRole = ""
        for (message in messages.takeLast(maxTurns)) {
            val isUser = message.role == "user"
            val content = if (isUser) userSummary(message) else assistantSummary(message)
            if (content.isBlank()) continue
            val role = if (isUser) "user" else "assistant"
            // 连续同角色合并：Anthropic 协议要求 user/assistant 交替
            if (role == lastRole) {
                val previous = out.optJSONObject(out.length() - 1)
                if (previous != null) {
                    previous.put("content", previous.optString("content") + "\n\n" + content)
                    continue
                }
            }
            out.put(JSONObject().put("role", role).put("content", content))
            lastRole = role
        }
        return out
    }

    private fun userSummary(message: ChatMessage): String = buildString {
        append(message.text)
        val documents = message.attachments.filter { it.kind == "text" && !it.text.isNullOrBlank() }
        documents.forEach { attachment ->
            append("\n\n【附件：").append(attachment.name ?: "文档").append("】\n")
            append(attachment.text)
        }
        val files = message.attachments.filter { it.kind == "file" && !it.localPath.isNullOrBlank() }
        if (files.isNotEmpty()) {
            append("\n\n用户上传的文件（本机路径）：")
            files.forEach { append("\n- ").append(it.localPath) }
        }
        if (message.attachments.any { it.kind == "image" }) {
            append("\n（这条消息还带过图片，图片本身已不在历史里；需要再看请让用户重新发送）")
        }
    }.take(2500)

    private fun assistantSummary(message: ChatMessage): String = buildString {
        val text = message.blocks.filterIsInstance<Block.Text>().joinToString("\n\n") { it.text }
        if (text.isNotBlank()) append(text)
        val tools = message.blocks.filterIsInstance<Block.Tool>()
        if (tools.isNotEmpty()) {
            val grouped = tools.groupingBy { it.name }.eachCount()
                .entries.joinToString("、") { "${it.key}×${it.value}" }
            if (isNotEmpty()) append("\n\n")
            append("（上一轮调用工具：").append(grouped)
            val last = tools.lastOrNull { it.status == "done" && !it.result.isNullOrBlank() }
            if (last != null) {
                append("；最后一次 ").append(last.name).append(" 的结果摘要：")
                append(last.result!!.replace("\n", " ").take(400))
            }
            append("）")
        }
        if (text.isBlank() && tools.isEmpty()) append("（无输出）")
    }.take(2500)

    fun appendUser(
        sessionId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
        mode: String = "llm",
    ) {
        ensure(sessionId, mode)
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
        // 消息已经发出去，输入框草稿作废
        json.put("draft", "")
        json.put("updatedAt", System.currentTimeMillis())
        write(sessionId, json)
    }

    /** 单段思考（LLM 区 / 旧调用）：等价于 round 0 的一段 */
    fun appendAssistant(
        sessionId: String,
        text: String,
        thinking: String,
        tools: List<Block.Tool>,
        status: String,
        mode: String = "llm",
        usage: UsageStats? = null,
        runId: String? = null,
    ) = appendAssistant(
        sessionId, text, listOf(0 to thinking), tools, status, mode, usage, runId,
    )

    /**
     * 写一条助手消息。
     *
     * [thinkingSections] 是「第几轮 → 那一轮的思考」，按轮把思考卡和该轮的工具卡交错写入，
     * 界面就能还原「这一轮的思考 + 这一轮调了什么」，而不是一坨思考堆在最前面。
     */
    fun appendAssistant(
        sessionId: String,
        text: String,
        thinkingSections: List<Pair<Int, String>>,
        tools: List<Block.Tool>,
        status: String,
        mode: String = "llm",
        usage: UsageStats? = null,
        /** 这一轮的运行 id：界面重启后认领日志时用来去重，避免同一条回答补两次 */
        runId: String? = null,
    ) {
        ensure(sessionId, mode)
        val json = read(sessionId) ?: return
        val messages = json.optJSONArray("messages") ?: JSONArray().also { json.put("messages", it) }
        val blocks = JSONArray()
        val thinkingByRound = LinkedHashMap<Int, String>()
        thinkingSections.forEach { (round, section) ->
            if (section.isNotBlank()) {
                thinkingByRound[round] = (thinkingByRound[round] ?: "") + section
            }
        }
        val toolsByRound = tools.groupBy { it.round }
        val rounds = (thinkingByRound.keys + toolsByRound.keys).sorted()
        for (round in rounds) {
            thinkingByRound[round]?.takeIf { it.isNotBlank() }?.let { section ->
                blocks.put(
                    JSONObject()
                        .put("type", "thinking")
                        .put("thinking", section)
                        .put("round", round)
                )
            }
            toolsByRound[round]?.forEach { tool ->
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
        }
        if (text.isNotBlank()) {
            blocks.put(JSONObject().put("type", "text").put("text", text))
        }
        val message = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("role", "assistant")
            .put("blocks", blocks)
            .put("status", status)
            .put("elapsedMs", usage?.turnMs ?: 0L)
            .put("createdAt", System.currentTimeMillis())
        if (!runId.isNullOrBlank()) message.put("runId", runId)
        messages.put(message)
        // 用量落到会话文件：重开会话时用量条 / 「本轮」耗时还能显示上一次的值
        if (usage != null) {
            json.put(
                "usageStats",
                JSONObject()
                    .put("rounds", usage.rounds)
                    .put("steps", usage.steps)
                    .put("contextTokens", usage.contextTokens)
                    .put("turnMs", usage.turnMs)
                    .put("inputTokens", usage.inputTokens)
                    .put("cacheReadTokens", usage.cacheReadTokens)
                    .put("cacheCreationTokens", usage.cacheCreationTokens)
                    .put("outputTokens", usage.outputTokens),
            )
            // 整个会话的累计：缓存命中率是「整个对话」的指标，不是单轮的
            if (usage.inputTokens + usage.cacheReadTokens + usage.cacheCreationTokens + usage.outputTokens > 0) {
                val totals = json.optJSONObject("usageTotals")
                    ?: JSONObject().also { json.put("usageTotals", it) }
                totals.put("input", totals.optInt("input", 0) + usage.inputTokens)
                totals.put("cacheRead", totals.optInt("cacheRead", 0) + usage.cacheReadTokens)
                totals.put("cacheCreation", totals.optInt("cacheCreation", 0) + usage.cacheCreationTokens)
                totals.put("output", totals.optInt("output", 0) + usage.outputTokens)
                totals.put("requests", totals.optInt("requests", 0) + 1)
            }
        }
        json.put("updatedAt", System.currentTimeMillis())
        write(sessionId, json)
    }

    /**
     * 改掉最后一条用户消息，并删掉它之后的消息（通常是它的回答）。
     *
     * LLM 区「编辑上一条并重新生成」用：改完立刻重跑，历史里不会留下改之前的旧回答。
     */
    fun updateLastUserMessage(sessionId: String, text: String): Boolean {
        val json = read(sessionId) ?: return false
        val messages = json.optJSONArray("messages") ?: return false
        var lastUser = -1
        for (i in 0 until messages.length()) {
            if (messages.optJSONObject(i)?.optString("role") == "user") lastUser = i
        }
        if (lastUser < 0) return false
        val kept = JSONArray()
        for (i in 0..lastUser) {
            kept.put(messages.optJSONObject(i) ?: JSONObject())
        }
        kept.optJSONObject(lastUser)?.put("content", text)
        json.put("messages", kept)
        json.put("updatedAt", System.currentTimeMillis())
        write(sessionId, json)
        return true
    }

    fun delete(sessionId: String) {
        File(nativeDir, "$sessionId.json").delete()
    }

    /**
     * 这一轮运行（runId）是不是已经写进会话了。
     *
     * 界面重启后可能同时走两条路（实时事件 + 日志认领），这条判断保证同一条回答只落一次。
     */
    fun hasRun(sessionId: String, runId: String): Boolean {
        if (runId.isBlank()) return false
        val json = read(sessionId) ?: return false
        val messages = json.optJSONArray("messages") ?: return false
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            if (runId == item.optString("runId")) return true
        }
        return false
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
