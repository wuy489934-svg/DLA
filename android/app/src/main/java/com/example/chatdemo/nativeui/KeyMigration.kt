package com.example.chatdemo.nativeui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * 一次性迁移：把网页界面存在 localStorage 的 API Key 搬到原生设置里。
 *
 * 做法：用一个临时 WebView 打开**同源空白页**（不启动网页应用本体），
 * 只读取 `chat-demo:api-key` 后立即销毁。读完就打标记，之后不再执行。
 * 迁移只在原生侧没有 Key 时写入，不会覆盖用户已经填过的值。
 */
object KeyMigration {

    private const val LEGACY_KEY = "chat-demo:api-key"
    private const val BLANK_PATH = "/blank/"

    fun migrateOnce(context: Context, onDone: (String?) -> Unit) {
        val prefs = context.getSharedPreferences("dla-native", Context.MODE_PRIVATE)
        if (prefs.getBoolean("keyMigrated", false)) {
            onDone(null)
            return
        }
        runCatching { run(context, prefs, onDone) }
            .onFailure {
                prefs.edit().putBoolean("keyMigrated", true).apply()
                onDone(null)
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun run(
        context: Context,
        prefs: android.content.SharedPreferences,
        onDone: (String?) -> Unit,
    ) {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler(BLANK_PATH, WebViewAssetLoader.PathHandler {
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    ByteArrayInputStream("<html><body></body></html>".toByteArray()),
                )
            })
            .build()

        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): WebResourceResponse? = request?.let { loader.shouldInterceptRequest(it.url) }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view ?: return
                    view.evaluateJavascript("localStorage.getItem('$LEGACY_KEY')") { raw ->
                        val key = unquote(raw)
                        prefs.edit().putBoolean("keyMigrated", true).apply()
                        if (!key.isNullOrBlank() && prefs.getString("apiKey", "").isNullOrEmpty()) {
                            prefs.edit().putString("apiKey", key).apply()
                        }
                        view.destroy()
                        onDone(key)
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net$BLANK_PATH")
        }
    }

    /** evaluateJavascript 返回的是 JSON 字符串字面量，需要去引号并解转义 */
    private fun unquote(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return null
        val body = trimmed.substring(1, trimmed.length - 1)
        return body.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
