package com.example.chatdemo.nativeui

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生 LLM 客户端（DeepSeek 兼容 Messages 协议，SSE 流式）。
 *
 * 与网页端保持同一套请求形态：`POST {baseUrl}/messages`、`x-api-key`、`stream:true`；
 * 另支持深度思考（thinking）、联网搜索（tools）与立绘注入（首轮图片块）。
 */
object LlmClient {

    data class Config(
        val apiKey: String,
        val baseUrl: String = "https://api.deepseek.com/anthropic/v1",
        val model: String = "deepseek-flash",
        val system: String = DEFAULT_SYSTEM,
        val thinking: Boolean = false,
        val search: Boolean = false,
    )

    interface Sink {
        fun onText(delta: String) {}
        fun onThinking(delta: String) {}
        fun onDone(stopReason: String?) {}
        fun onError(message: String) {}
    }

    class Handle internal constructor() {
        private val cancelled = AtomicBoolean(false)
        internal var connection: HttpURLConnection? = null
        fun cancel() {
            cancelled.set(true)
            runCatching { connection?.disconnect() }
        }
        internal fun isCancelled() = cancelled.get()
    }

    /**
     * 组装消息。
     *
     * 立绘的注入方式与网页版一致：**在最前面放一对隐藏回合**
     * （用户：图片 + “这是你的形象立绘，仅供你认识自己”；助手：“我看到自己了”），
     * 这样模型会把它当成「自己的样子」，而不是用户发来的照片。
     */
    fun buildMessages(
        history: List<com.example.chatdemo.nativeui.Turn>,
        imageDataUrl: String?,
        imageNote: String? = null,
    ): JSONArray {
        val array = JSONArray()
        val block = imageDataUrl?.let { imageBlock(it) }
        if (block != null) {
            array.put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray().apply {
                        put(block)
                        put(
                            JSONObject().put("type", "text").put(
                                "text",
                                "（应用底层注入：这是你的形象立绘，供你认识自己的外貌。" +
                                    "仅作认知，不需要评论、也不要在回复里提起，除非对方主动问起你的样子。" +
                                    "如果它和文字设定冲突，以文字设定为准。" +
                                    "用户本人并没有发送任何图片。" +
                                    (imageNote?.takeIf { it.isNotBlank() }?.let { "【重要】$it" } ?: "") +
                                    "）",
                            ),
                        )
                    },
                ),
            )
            array.put(
                JSONObject().put("role", "assistant").put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", "嗯，我看到自己了，记在心里啦。"),
                    ),
                ),
            )
        }
        val lastUserIndex = history.indexOfLast { it.role == "user" }
        history.forEachIndexed { index, turn ->
            // 只给最后一条用户消息带附件，历史图片不再重复上传
            val images = if (index == lastUserIndex) turn.images else emptyList()
            val documents = if (index == lastUserIndex) turn.documents else emptyList()
            if (images.isEmpty() && documents.isEmpty()) {
                array.put(JSONObject().put("role", turn.role).put("content", turn.text))
                return@forEachIndexed
            }
            val parts = JSONArray()
            images.forEach { dataUrl -> imageBlock(dataUrl)?.let { parts.put(it) } }
            val text = buildString {
                documents.forEach { (name, content) ->
                    append("【附件：").append(name).append("】\n")
                    append(content).append("\n\n")
                }
                if (turn.text.isNotBlank()) append(turn.text)
            }.ifBlank { "（附件）" }
            parts.put(JSONObject().put("type", "text").put("text", text))
            array.put(JSONObject().put("role", turn.role).put("content", parts))
        }
        return array
    }

    internal fun imageBlock(dataUrl: String): JSONObject? {
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith("data:") || comma < 0) return null
        val header = dataUrl.substring(5, comma)
        val mediaType = header.substringBefore(';').ifBlank { "image/webp" }
        val data = dataUrl.substring(comma + 1)
        if (data.isBlank()) return null
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", data),
            )
    }

    fun stream(config: Config, messages: JSONArray, sink: Sink): Handle {
        val handle = Handle()
        Thread({
            try {
                run(config, messages, sink, handle)
            } catch (error: Throwable) {
                if (!handle.isCancelled()) {
                    sink.onError(error.message ?: error.javaClass.simpleName)
                }
            }
        }, "dla-llm").start()
        return handle
    }

    private fun run(config: Config, messages: JSONArray, sink: Sink, handle: Handle) {
        val url = URL(config.baseUrl.trimEnd('/') + "/messages")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("x-api-key", config.apiKey)
        }
        handle.connection = connection

        val payload = JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("max_tokens", 8192)
            put("system", config.system)
            put("thinking", JSONObject().put("type", if (config.thinking) "enabled" else "disabled"))
            put("safety_check", JSONObject().put("level", "high"))
            put("messages", messages)
            put("tools", JSONArray().apply {
                put(JSONObject().put("type", "deepseek_read_url").put("name", "deepseek_read_url"))
                if (config.search) {
                    put(JSONObject().put("type", "deepseek_web_search").put("name", "deepseek_web_search"))
                }
            })
        }

        connection.outputStream.use { out ->
            out.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        if (code !in 200..299) {
            val text = runCatching {
                (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            val message = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
            }.getOrDefault("")
            sink.onError(if (message.isNotEmpty()) message else "HTTP $code")
            return
        }

        val contentType = connection.contentType.orEmpty()
        if (!contentType.contains("text/event-stream")) {
            sink.onError(Lang.t("服务端没有返回事件流（%s）", contentType))
            return
        }

        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            var event = ""
            val data = StringBuilder()
            while (true) {
                if (handle.isCancelled()) return
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val finished = handleEvent(event, data.toString(), sink)
                            if (finished) return
                        }
                        event = ""
                        data.setLength(0)
                    }
                }
            }
        }
        sink.onDone(null)
    }

    /** 返回 true 表示已收到 message_stop，可以结束 */
    private fun handleEvent(event: String, data: String, sink: Sink): Boolean {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return false
        when (event.ifEmpty { json.optString("type") }) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return false
                when (delta.optString("type")) {
                    "text_delta" -> sink.onText(delta.optString("text"))
                    "thinking_delta" -> sink.onThinking(delta.optString("thinking"))
                }
            }
            "message_stop" -> {
                sink.onDone(null)
                return true
            }
            "message_delta" -> Unit // 只带 stop_reason/usage，不是结束信号
            "error" -> {
                val message = json.optJSONObject("error")?.optString("message") ?: data
                sink.onError(message)
            }
        }
        return false
    }

    const val DEFAULT_SYSTEM = "该助手为DeepSeek，由深度求索公司创造。"
}
