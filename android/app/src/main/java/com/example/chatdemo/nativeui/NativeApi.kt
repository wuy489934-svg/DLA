package com.example.chatdemo.nativeui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生侧的最小 API 辅助：只做「拉取模型 / 测试连接」这类一次性校验，
 * 走 Bearer 鉴权（与网页端的 verifyApiKey 保持一致）。
 */
object NativeApi {

    data class Result(val ok: Boolean, val message: String, val models: List<String> = emptyList())

    fun fetchModels(apiKey: String, baseUrl: String): Result {
        if (apiKey.isBlank()) return Result(false, Lang.t("请先填写 API Key"))
        val origin = originOf(baseUrl)
        return runCatching {
            val url = URL("$origin/models")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val text = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            }.getOrDefault("")
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                return Result(false, message.ifEmpty { "HTTP $code" })
            }
            val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull()
            val models = ArrayList<String>()
            if (data != null) {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotEmpty()) models += id
                }
            }
            if (models.isEmpty()) {
                Result(true, Lang.t("连接成功（该服务未提供模型列表）"), emptyList())
            } else {
                Result(true, Lang.t("连接成功，发现 %d 个模型", models.size), models)
            }
        }.getOrElse { Result(false, it.message ?: Lang.t("网络错误")) }
    }

    private fun originOf(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return trimmed.removeSuffix("/anthropic/v1").ifBlank { "https://api.deepseek.com" }
    }
}
