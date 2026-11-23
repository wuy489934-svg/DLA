package com.example.chatdemo.nativeui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 朗读引擎（TTS provider）。
 *
 * DLA 的语音默认走系统 TTS（和小米/华为等本机引擎对接）；用户也可以自己添加
 * OpenAI 兼容的云端 TTS 服务（`POST {baseUrl}/audio/speech`），填 Base URL、
 * Key、模型与音色即可。云端与系统引擎可以随时切换，互不影响对话内容。
 */
data class TtsProvider(
    val id: String,
    val name: String,
    val kind: String = KIND_SYSTEM,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    val format: String = "mp3",
    val speed: Float = 1.0f,
) {

    fun isSystem(): Boolean = kind == KIND_SYSTEM

    fun summary(): String = if (isSystem()) {
        "本机系统语音"
    } else {
        buildString {
            append(model.ifBlank { "未填模型" })
            if (voice.isNotBlank()) append(" · ").append(voice)
            append(" · ").append(format)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("voice", voice)
        put("format", format)
        put("speed", speed.toDouble())
    }

    companion object {
        const val KIND_SYSTEM = "system"
        const val KIND_OPENAI = "openai"

        fun system(): TtsProvider = TtsProvider(id = "system", name = "系统语音（本机）")

        fun newCloud(): TtsProvider = TtsProvider(
            id = UUID.randomUUID().toString(),
            name = "",
            kind = KIND_OPENAI,
            baseUrl = "",
            model = "",
            voice = "",
            format = "mp3",
        )

        fun fromJson(json: JSONObject): TtsProvider = TtsProvider(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            kind = json.optString("kind", KIND_OPENAI),
            baseUrl = json.optString("baseUrl", ""),
            apiKey = json.optString("apiKey", ""),
            model = json.optString("model", ""),
            voice = json.optString("voice", ""),
            format = json.optString("format", "mp3").ifBlank { "mp3" },
            speed = json.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4.0f),
        )
    }
}

/** 朗读引擎的存取：系统引擎固定存在，云端引擎按用户添加的顺序保存。 */
object TtsStore {

    private const val PREFS = "dla-native"
    private const val KEY_PROVIDERS = "ttsProviders"
    private const val KEY_SELECTED = "ttsProviderId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun providers(context: Context): List<TtsProvider> {
        val list = ArrayList<TtsProvider>()
        list.add(TtsProvider.system())
        val raw = prefs(context).getString(KEY_PROVIDERS, null).orEmpty()
        if (raw.isNotBlank()) {
            runCatching {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val provider = TtsProvider.fromJson(item)
                    if (!provider.isSystem() && provider.id.isNotBlank()) {
                        list.add(provider)
                    }
                }
            }
        }
        return list
    }

    fun selected(context: Context): TtsProvider {
        val id = prefs(context).getString(KEY_SELECTED, null).orEmpty()
        return providers(context).firstOrNull { it.id == id } ?: TtsProvider.system()
    }

    fun select(context: Context, id: String) {
        prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    fun upsert(context: Context, provider: TtsProvider) {
        if (provider.isSystem()) return
        val current = providers(context).filter { !it.isSystem() }
        val next = current.toMutableList()
        val index = next.indexOfFirst { it.id == provider.id }
        if (index >= 0) {
            next[index] = provider
        } else {
            next.add(provider)
        }
        save(context, next)
    }

    fun remove(context: Context, id: String) {
        if (id == "system") return
        save(context, providers(context).filter { !it.isSystem() && it.id != id })
        if (prefs(context).getString(KEY_SELECTED, null) == id) {
            prefs(context).edit().remove(KEY_SELECTED).apply()
        }
    }

    private fun save(context: Context, providers: List<TtsProvider>) {
        val array = JSONArray()
        providers.filter { !it.isSystem() }.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_PROVIDERS, array.toString()).apply()
    }
}
