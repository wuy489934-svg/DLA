package com.example.chatdemo.nativeui

import android.content.Context

/** 朗读引擎：系统 TTS 或 Qwen3-TTS（阿里云百炼）。 */
enum class TtsEngineKind(val id: String) {
    SYSTEM("system"),
    QWEN("qwen");

    companion object {
        fun of(id: String?): TtsEngineKind = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Qwen3-TTS 配置。
 *
 * 走 DashScope 原生接口：
 * `POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation`
 * Body `{ model, input: { text, voice, language_type } }`，返回 `output.audio.url`（WAV，24 小时有效），
 * 需要再下载一次。单次文本上限 600 字，长回复按句子切成多段依次合成与播放。
 */
data class QwenTtsConfig(
    val apiKey: String = "",
    val voice: String = "Cherry",
    /** beijing | singapore */
    val region: String = "beijing",
    val model: String = "qwen3-tts-flash",
) {
    fun endpoint(): String = if (region == "singapore") {
        "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    } else {
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    }

    fun summary(): String = voice + " · " + model + " · " + if (region == "singapore") "新加坡" else "北京"
}

/** 朗读设置：引擎选择 + Qwen 配置，全部存在 dla-native。 */
object TtsStore {

    private const val PREFS = "dla-native"
    private const val KEY_ENGINE = "ttsEngine"
    private const val KEY_QWEN_KEY = "qwenTtsApiKey"
    private const val KEY_QWEN_VOICE = "qwenTtsVoice"
    private const val KEY_QWEN_REGION = "qwenTtsRegion"
    private const val KEY_QWEN_MODEL = "qwenTtsModel"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engine(context: Context): TtsEngineKind =
        TtsEngineKind.of(prefs(context).getString(KEY_ENGINE, null))

    fun selectEngine(context: Context, kind: TtsEngineKind) {
        prefs(context).edit().putString(KEY_ENGINE, kind.id).apply()
    }

    fun qwen(context: Context): QwenTtsConfig {
        val prefs = prefs(context)
        return QwenTtsConfig(
            apiKey = prefs.getString(KEY_QWEN_KEY, "").orEmpty(),
            voice = prefs.getString(KEY_QWEN_VOICE, "Cherry").orEmpty().ifBlank { "Cherry" },
            region = prefs.getString(KEY_QWEN_REGION, "beijing").orEmpty().ifBlank { "beijing" },
            model = prefs.getString(KEY_QWEN_MODEL, "qwen3-tts-flash").orEmpty()
                .ifBlank { "qwen3-tts-flash" },
        )
    }

    fun saveQwen(context: Context, config: QwenTtsConfig) {
        prefs(context).edit()
            .putString(KEY_QWEN_KEY, config.apiKey.trim())
            .putString(KEY_QWEN_VOICE, config.voice.trim().ifBlank { "Cherry" })
            .putString(KEY_QWEN_REGION, config.region)
            .putString(KEY_QWEN_MODEL, config.model.trim().ifBlank { "qwen3-tts-flash" })
            .apply()
    }
}
