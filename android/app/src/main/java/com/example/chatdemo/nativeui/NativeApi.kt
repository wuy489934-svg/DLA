package com.example.chatdemo.nativeui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生侧的最小 API 辅助：只做「拉取模型 / 测试连接」这类一次性校验。
 *
 * 两种协议的鉴权头不一样（anthropic 用 x-api-key，openai 用 Bearer），
 * 但两边的 /models 都认 Bearer；所以这里统一用 Bearer 试，失败了再按协议补一次，
 * 免得「Key 明明是对的，却因为少一个头拉不到模型列表」。
 */
object NativeApi {

    data class Result(val ok: Boolean, val message: String, val models: List<String> = emptyList())

    /** 候选地址：用户可能只填 origin、也可能把 …/messages 或 …/chat/completions 整段贴进来 */
    private fun candidates(baseUrl: String, protocol: String): List<String> {
        val trimmed = baseUrl.trim().trimEnd('/')
        val out = LinkedHashSet<String>()
        if (trimmed.isNotEmpty()) {
            var base = trimmed
                .removeSuffix("/chat/completions")
                .removeSuffix("/messages")
            out += base + "/models"
            // DeepSeek 的 anthropic 兼容端点在 /anthropic/v1，模型列表在 /v1 或根上
            if (base.endsWith("/anthropic/v1")) {
                out += base.removeSuffix("/anthropic/v1") + "/v1/models"
                out += base.removeSuffix("/anthropic/v1") + "/models"
            }
            if (base.endsWith("/v1")) {
                out += base.removeSuffix("/v1") + "/models"
            }
        }
        // 最后再兜到默认端点，避免用户留空时直接报「请填写 Base URL」
        if (trimmed.isEmpty()) {
            out += if (protocol == "openai") {
                "https://api.deepseek.com/v1/models"
            } else {
                "https://api.deepseek.com/anthropic/v1/models"
            }
        }
        return out.toList()
    }

    fun fetchModels(apiKey: String, baseUrl: String, protocol: String = "anthropic"): Result {
        if (apiKey.isBlank()) return Result(false, Lang.t("请先填写 API Key"))
        var lastError = ""
        for (url in candidates(baseUrl, protocol)) {
            val attempt = fetchOnce(url, apiKey, protocol)
            if (attempt.ok) return attempt
            lastError = attempt.message
            // Key 都不认，换地址也没用
            if (attempt.message.contains("401") || attempt.message.contains("403")) break
        }
        return Result(false, lastError.ifEmpty { Lang.t("网络错误") })
    }

    private fun fetchOnce(url: String, apiKey: String, protocol: String): Result = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            if (protocol != "openai") setRequestProperty("x-api-key", apiKey)
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val text = runCatching {
            (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        }.getOrDefault("")
        if (code !in 200..299) {
            val message = runCatching {
                val json = JSONObject(text)
                json.optJSONObject("error")?.optString("message").orEmpty()
                    .ifEmpty { json.optString("message") }
            }.getOrDefault("")
            return Result(false, message.ifEmpty { "HTTP $code（$url）" })
        }
        val models = parseModels(text)
        if (models.isEmpty()) {
            Result(true, Lang.t("连接成功（该服务未提供模型列表）"), emptyList())
        } else {
            Result(true, Lang.t("连接成功，发现 %d 个模型", models.size), models)
        }
    }.getOrElse { Result(false, it.message ?: Lang.t("网络错误")) }

    /** OpenAI 是 data[].id；个别兼容层直接给数组，也有给 models[] 的 */
    private fun parseModels(text: String): List<String> {
        val out = LinkedHashSet<String>()
        val json = runCatching { JSONObject(text) }.getOrNull()
        val array = json?.optJSONArray("data")
            ?: json?.optJSONArray("models")
            ?: runCatching { org.json.JSONArray(text) }.getOrNull()
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                val id = when (item) {
                    is JSONObject -> item.optString("id").ifEmpty { item.optString("name") }
                    is String -> item
                    else -> ""
                }
                if (id.isNotBlank()) out += id
            }
        }
        return out.toList()
    }
}
